#requires -Version 7.0
param([string]$GatewayUrl = 'http://localhost:8080',
      [string]$PassengerPhone = '13800138000', [string]$PassengerPassword = 'Passw0rd!23')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localEnv = Join-Path $projectRoot '.env.local'
if (Test-Path $localEnv) {
    Get-Content $localEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
$runId = [Guid]::NewGuid().ToString()
$accounts = [Collections.Generic.List[object]]::new()
$cleanupErrors = [Collections.Generic.List[string]]::new()
$orderId = 0L
$report = $null
$dbUser = if ($env:SMARTDRIVE_DB_USER) { $env:SMARTDRIVE_DB_USER } else { 'smartdrive' }
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:SMARTDRIVE_DB_PASSWORD
$mqUrl = if ($env:RABBITMQ_MANAGEMENT_URL) { $env:RABBITMQ_MANAGEMENT_URL } else { 'http://localhost:15672' }
$mqUser = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'guest' }
$mqPassword = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'guest' }
$vhost = [Uri]::EscapeDataString($(if ($env:RABBITMQ_VHOST) { $env:RABBITMQ_VHOST } else { '/' }))
$mqHeaders = @{Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${mqUser}:$mqPassword"))}
function Assert-True($condition, [string]$message) { if (-not $condition) { throw $message } }
function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Day13 SQL failed' }
    return $result
}
function Api($method, $path, $headers, $body = $null, $expected = 200, $code = '0') {
    $options = @{Method=$method;Uri="$GatewayUrl$path";Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 8 -Compress); $options.ContentType='application/json' }
    $response = Invoke-WebRequest @options
    $json = $response.Content | ConvertFrom-Json
    Assert-True ([int]$response.StatusCode -eq $expected -and $json.code -eq $code) "Unexpected response for $method $path : HTTP $($response.StatusCode), code=$($json.code)"
    return $json.data
}
function Driver([double]$lng) {
    $phone = '199' + (Get-Random -Minimum 10000000 -Maximum 100000000)
    $password = [Guid]::NewGuid().ToString('N')
    $registered = Api Post '/api/auth/register' @{} @{phone=$phone;password=$password;role='DRIVER'}
    $account = [PSCustomObject]@{id=[long]$registered.userId;phone=$phone;headers=@{}}
    $accounts.Add($account)
    $login = Api Post '/api/auth/login' @{} @{phone=$phone;password=$password}
    $account.headers = @{Authorization="Bearer $($login.accessToken)"}
    $null = Api Put '/api/drivers/me/profile' $account.headers @{displayName="Day13-$runId"}
    $null = Api Put '/api/drivers/me/online' $account.headers @{lng=$lng;lat=36.5}
    return $account
}
try {
    $null = Sql 'SELECT 1 FROM driver_db.processed_order_event LIMIT 1'
    $login = Api Post '/api/auth/login' @{} @{phone=$PassengerPhone;password=$PassengerPassword}
    Assert-True ($login.role -eq 'PASSENGER') 'Demo passenger required'
    $passengerHeaders = @{Authorization="Bearer $($login.accessToken)"}
    $first = Driver 118.8
    $second = Driver 118.8001
    $far = Driver 119.8
    $place = @{name="Day13-$runId";lng=118.8;lat=36.5}
    $created = Api Post '/api/orders' $passengerHeaders @{requestId=[Guid]::NewGuid().ToString();pickup=$place;dropoff=$place} 201
    $orderId = [long]$created.id
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        $ids = @(Api Get '/api/drivers/me/notices?status=NEW' $first.headers)
        $otherIds = @(Api Get '/api/drivers/me/notices' $second.headers)
        if ($ids -contains $orderId -and $otherIds -contains $orderId) { break }
        Start-Sleep -Milliseconds 500
    }
    Assert-True ($ids -contains $orderId -and $otherIds -contains $orderId) 'Both nearby drivers must receive asynchronous notice'
    Assert-True (@(Api Get '/api/drivers/me/notices' $far.headers) -notcontains $orderId) 'Far driver was notified'
    $candidate = Api Get "/api/orders/$orderId" $first.headers
    Assert-True ($candidate.status -eq 'WAITING' -and $null -eq $candidate.passengerId -and $null -eq $candidate.requestId -and $null -eq $candidate.driverId) 'Candidate detail must be live and hide private identifiers'
    $null = Api Get "/api/orders/$orderId" $far.headers $null 403 'FORBIDDEN'
    $null = Api Get '/api/drivers/me/notices' $passengerHeaders $null 403 'FORBIDDEN'
    $null = Api Get '/api/drivers/me/notices' @{} $null 401 'UNAUTHORIZED'
    $null = Api Get '/api/drivers/me/notices?status=READ' $first.headers $null 400 'INVALID_PARAM'

    $late = Driver 118.8002
    $eventRow = Sql "SELECT JSON_OBJECT('eventId',event_id,'status',status,'payload',payload) FROM order_db.outbox_event WHERE aggregate_id=$orderId AND event_type='order.created'"
    $event = $eventRow | ConvertFrom-Json
    $wirePayload = if ($event.payload -is [string]) { $event.payload } else { $event.payload | ConvertTo-Json -Depth 8 -Compress }
    # Publish the exact same wire event twice. No shared queue is read or purged.
    for ($i=0; $i -lt 2; $i++) {
        $body = @{properties=@{content_type='application/json';delivery_mode=2;message_id=$event.eventId};routing_key='order.created';payload=$wirePayload;payload_encoding='string'}
        $published = Invoke-RestMethod -Method Post -Uri "$mqUrl/api/exchanges/$vhost/ride.events/publish" -Headers $mqHeaders -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 15
        Assert-True $published.routed 'Replay was not routed'
    }
    Start-Sleep -Seconds 2
    Assert-True ((Sql "SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id=$orderId") -eq 2) 'Duplicate delivery changed original cohort'
    Assert-True (@(Api Get '/api/drivers/me/notices' $late.headers) -notcontains $orderId) 'Replay notified a late driver'
    Assert-True ((Sql "SELECT COUNT(*) FROM driver_db.processed_order_event WHERE order_id=$orderId AND event_id='$($event.eventId)'") -eq 1) 'Missing unique consumption record'

    $requests = @($first,$second) | ForEach-Object { @{id=$_.id;url="$GatewayUrl/api/orders/$orderId/accept";authorization=$_.headers.Authorization} }
    $race = @($requests | ForEach-Object -Parallel {
        $response = Invoke-WebRequest -Method Post -Uri $_.url -Headers @{Authorization=$_.authorization} -SkipHttpErrorCheck -TimeoutSec 20
        $body = $response.Content | ConvertFrom-Json
        [PSCustomObject]@{driverId=$_.id;status=[int]$response.StatusCode;code=$body.code}
    } -ThrottleLimit 2)
    Assert-True (@($race | Where-Object status -eq 200).Count -eq 1) 'Expected exactly one winner'
    Assert-True (@($race | Where-Object { $_.status -eq 409 -and $_.code -eq 'ORDER_ALREADY_TAKEN' }).Count -eq 1) 'Expected one conflict'
    $winnerId = ($race | Where-Object status -eq 200).driverId
    $winner = @($first,$second) | Where-Object id -eq $winnerId
    $loser = @($first,$second) | Where-Object id -ne $winnerId
    $taken = Api Get "/api/orders/$orderId" $loser.headers
    Assert-True ($taken.status -eq 'ACCEPTED' -and $null -eq $taken.passengerId -and $null -eq $taken.driverId) 'Losing candidate must see current state without private data'
    $null = Api Post "/api/orders/$orderId/start" $winner.headers
    $null = Api Post "/api/orders/$orderId/finish" $winner.headers
    $paid = Api Post "/api/orders/$orderId/pay-demo" $passengerHeaders
    Assert-True ($paid.status -eq 'PAID') 'Full asynchronous order flow failed'
    $docs = Invoke-RestMethod 'http://localhost:8082/v3/api-docs' -TimeoutSec 20
    Assert-True ($null -ne $docs.paths.'/api/drivers/me/notices' -and $null -ne $docs.components.securitySchemes.bearerAuth) 'Driver OpenAPI missing notices/JWT'
    Assert-True (@($docs.paths.PSObject.Properties.Name | Where-Object { $_ -like '/internal/*' }).Count -eq 0) 'Internal driver APIs must be hidden'
    $report = [PSCustomObject]@{time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');runId=$runId;orderId=$orderId;eventId=$event.eventId;nearbyNotified=2;farExcluded=$true;replayCount=2;noticeRows=2;lateDriverExcluded=$true;candidatePrivacy=$true;auth=$true;race=$race;finalStatus=$paid.status;openApi=$true}
} finally {
    $canRemoveAccounts = $true
    if ($orderId -gt 0) {
        try {
            if ((Sql "SELECT status FROM order_db.ride_order WHERE id=$orderId") -eq 'WAITING') {
                $null = Api Post "/api/orders/$orderId/cancel" $passengerHeaders
            }
            # Only remove fixtures after publication/consumption is durable and all shared deliveries are ACKed.
            Assert-True ((Sql "SELECT COUNT(*) FROM order_db.outbox_event e JOIN driver_db.processed_order_event p ON p.order_id=e.aggregate_id AND p.event_id=e.event_id WHERE e.aggregate_id=$orderId AND e.status='SENT'") -eq 1) 'Event has not completed publication/consumption; retain fixtures'
            for ($attempt=0; $attempt -lt 30; $attempt++) {
                $queue = Invoke-RestMethod -Uri "$mqUrl/api/queues/$vhost/driver.matching.q" -Headers $mqHeaders -TimeoutSec 15
                if ($queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0) { break }
                Start-Sleep -Milliseconds 500
            }
            Assert-True ($queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0) 'Queue is not drained; retain fixtures'
            Assert-True ((Sql "SELECT COUNT(*) FROM order_db.ride_order WHERE id=$orderId AND pickup_name='Day13-$runId'") -eq 1) 'Fixture identity mismatch'
            $null = Sql "START TRANSACTION; DELETE FROM order_db.driver_active_order WHERE order_id=$orderId; DELETE FROM driver_db.order_notice WHERE order_id=$orderId; DELETE FROM driver_db.processed_order_event WHERE order_id=$orderId; DELETE FROM order_db.outbox_event WHERE aggregate_id=$orderId AND event_type='order.created'; DELETE FROM order_db.ride_order WHERE id=$orderId AND pickup_name='Day13-$runId'; COMMIT;"
        } catch {
            $canRemoveAccounts = $false
            $cleanupErrors.Add('Order/event cleanup deferred; fixture accounts retained to preserve references')
        }
    }
    foreach ($driver in $accounts) {
        try {
            if ($driver.headers.Authorization) { $null = Api Put '/api/drivers/me/offline' $driver.headers }
            if ($canRemoveAccounts) {
                $null = Sql "DELETE FROM driver_db.order_notice WHERE driver_id=$($driver.id); DELETE FROM driver_db.driver_profile WHERE user_id=$($driver.id) AND display_name='Day13-$runId'; DELETE FROM account_db.account WHERE id=$($driver.id) AND phone='$($driver.phone)';"
            }
        } catch { $cleanupErrors.Add("Temporary driver cleanup failed: $($driver.id)") }
    }
    $env:MYSQL_PWD = $previousMysqlPassword
    if ($cleanupErrors.Count -gt 0) { Write-Warning ($cleanupErrors -join '; ') }
}
Assert-True ($cleanupErrors.Count -eq 0) 'Day13 cleanup incomplete'
$report | Add-Member -NotePropertyName cleanup -NotePropertyValue 'Temporary drivers, notices, order, outbox event and consumption record removed after confirmed publication/consumption and queue drain'
$null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'logs')
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $projectRoot 'logs/day13-http-result.json') -Encoding utf8
$report | ConvertTo-Json -Depth 8
