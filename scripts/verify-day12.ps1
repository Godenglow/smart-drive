#requires -Version 7.0
param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$PassengerPhone = '13800138000',
    [string]$PassengerPassword = 'Passw0rd!23'
)
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
$mqUrl = if ($env:RABBITMQ_MANAGEMENT_URL) { $env:RABBITMQ_MANAGEMENT_URL } else { 'http://localhost:15672' }
$mqUser = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'guest' }
$mqPassword = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'guest' }
$mqVhost = if ($env:RABBITMQ_VHOST) { $env:RABBITMQ_VHOST } else { '/' }
$mqPrefix = '/api/queues/' + [Uri]::EscapeDataString($mqVhost) + '/'
$mqHeaders = @{Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${mqUser}:$mqPassword"))}
$dbUser = if ($env:SMARTDRIVE_DB_USER) { $env:SMARTDRIVE_DB_USER } else { 'smartdrive' }
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:SMARTDRIVE_DB_PASSWORD
$runId = [Guid]::NewGuid().ToString()
$capture = "smartdrive.day12.capture.$runId"
$captureCreated = $false
$driver = $null
$orderId = 0L
$passengerHeaders = @{}
$cleanupErrors = [Collections.Generic.List[string]]::new()
$report = $null

function Assert-True($condition, [string]$message) { if (-not $condition) { throw $message } }
function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --default-character-set=utf8mb4 --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Day12 SQL check failed' }
    return $result
}
function Api($method, $path, $headers, $body, $expected = 200) {
    $options = @{Method=$method;Uri="$GatewayUrl$path";Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 8 -Compress); $options.ContentType='application/json' }
    $response = Invoke-WebRequest @options
    $json = $response.Content | ConvertFrom-Json
    Assert-True ([int]$response.StatusCode -eq $expected -and $json.code -eq '0') "Unexpected HTTP/code for $method $path"
    return $json.data
}
function Mq($method, $path, $body = $null) {
    $options = @{Method=$method;Uri="$mqUrl$path";Headers=$mqHeaders;TimeoutSec=15}
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 8 -Compress); $options.ContentType='application/json' }
    return Invoke-RestMethod @options
}

try {
    $null = Sql 'SELECT 1 FROM order_db.outbox_event LIMIT 1'
    $login = Api Post '/api/auth/login' @{} @{phone=$PassengerPhone;password=$PassengerPassword}
    Assert-True ($login.role -eq 'PASSENGER') 'A demo passenger account is required'
    $passengerHeaders = @{Authorization="Bearer $($login.accessToken)"}

    # Capture a copy through a temporary binding; never consume or purge the driver's shared queue.
    $null = Mq Put "$mqPrefix$capture" @{durable=$true;auto_delete=$false;arguments=@{'x-expires'=600000}}
    $captureCreated = $true
    $bindingPath = '/api/bindings/' + [Uri]::EscapeDataString($mqVhost) + '/e/ride.events/q/' + $capture
    # RabbitAdmin declares the production topology on its first connection. The service health
    # check opens that connection after packaging/startup, before this binding is installed.
    $health = Invoke-RestMethod "$GatewayUrl/actuator/order/health" -TimeoutSec 20
    Assert-True ($health.status -eq 'UP') 'Order service (including RabbitMQ) must be UP'
    $null = Mq Post $bindingPath @{routing_key='order.created';arguments=@{}}

    $phone = '199' + (Get-Random -Minimum 10000000 -Maximum 100000000)
    $password = [Guid]::NewGuid().ToString('N')
    $account = Api Post '/api/auth/register' @{} @{phone=$phone;password=$password;role='DRIVER'}
    $driver = @{id=[long]$account.userId;phone=$phone;headers=@{}}
    $driverLogin = Api Post '/api/auth/login' @{} @{phone=$phone;password=$password}
    $driver.headers = @{Authorization="Bearer $($driverLogin.accessToken)"}
    $null = Api Put '/api/drivers/me/profile' $driver.headers @{displayName="Day12-$runId"}
    $null = Api Put '/api/drivers/me/online' $driver.headers @{lng=117.215;lat=39.136}

    $place = @{name="Day12-$runId";lng=117.215;lat=39.136}
    $request = @{requestId=[Guid]::NewGuid().ToString();pickup=$place;dropoff=$place}
    $created = Api Post '/api/orders' $passengerHeaders $request 201
    $orderId = [long]$created.id
    Assert-True ($created.status -eq 'WAITING') 'Created order must be WAITING'
    $retry = Api Post '/api/orders' $passengerHeaders $request 201
    Assert-True ($retry.id -eq $orderId) 'Retry created another order'

    $event = $null
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $rows = @(Sql "SELECT JSON_OBJECT('eventId',event_id,'status',status,'attempts',attempts,'payload',payload,'sentAt',sent_at) FROM order_db.outbox_event WHERE aggregate_id=$orderId AND event_type='order.created'")
        Assert-True ($rows.Count -eq 1) 'Order must have exactly one outbox event'
        $event = $rows[0] | ConvertFrom-Json
        if ($event.status -eq 'SENT') { break }
        Start-Sleep -Milliseconds 500
    }
    Assert-True ($event.status -eq 'SENT' -and $event.sentAt) 'Scheduled publisher did not confirm the event'

    $messages = @(Mq Post "$mqPrefix$capture/get" @{count=100;ackmode='ack_requeue_false';encoding='auto';truncate=50000})
    $matched = @($messages | Where-Object { $_.properties.message_id -eq $event.eventId })
    Assert-True ($matched.Count -ge 1) 'Capture queue did not receive this event'
    $message = $matched[0]
    $payload = $message.payload | ConvertFrom-Json
    Assert-True ($message.properties.delivery_mode -eq 2 -and $message.properties.content_type -eq 'application/json') 'Message must be persistent JSON'
    Assert-True ($payload.eventId -eq $event.eventId -and $payload.type -eq 'order.created' -and $payload.orderId -eq $orderId) 'Wire event identity mismatch'
    Assert-True ($payload.pickupLng -eq 117.215 -and $payload.pickupLat -eq 39.136 -and $payload.occurredAt) 'Wire coordinates/time mismatch'
    foreach ($queue in @('driver.matching.q','driver.matching.dlq')) {
        $details = Mq Get "$mqPrefix$queue"
        Assert-True ($details.durable) "$queue must be durable"
        if ($queue -eq 'driver.matching.q') {
            Assert-True ($details.arguments.'x-dead-letter-exchange' -eq 'ride.events.dlx') 'Missing DLX configuration'
        }
    }
    $report = [PSCustomObject]@{time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');runId=$runId;orderId=$orderId;eventId=$event.eventId;outboxStatus=$event.status;attempts=$event.attempts;scheduledPublisher=$true;persistentJson=$true;idempotentCreation=$true;topology=$true}
} finally {
    if ($orderId -gt 0) {
        # Keep this valid demonstration record because the shared queue retains its event.
        # Cancel through the API rather than deleting an order that consumers may still see.
        try { $null = Api Post "/api/orders/$orderId/cancel" $passengerHeaders $null }
        catch { $cleanupErrors.Add("Could not cancel demonstration order $orderId") }
    }
    if ($driver) {
        try { if ($driver.headers.Authorization) { $null = Api Put '/api/drivers/me/offline' $driver.headers $null } }
        catch { $cleanupErrors.Add('Temporary driver offline failed') }
        try {
            $null = Sql "DELETE FROM driver_db.driver_profile WHERE user_id=$($driver.id) AND display_name='Day12-$runId'; DELETE FROM account_db.account WHERE id=$($driver.id) AND phone='$($driver.phone)';"
            Assert-True ((Sql "SELECT (SELECT COUNT(*) FROM driver_db.driver_profile WHERE user_id=$($driver.id)) + (SELECT COUNT(*) FROM account_db.account WHERE id=$($driver.id))") -eq 0) 'Temporary driver rows remain'
        } catch { $cleanupErrors.Add('Temporary driver cleanup failed') }
    }
    if ($captureCreated) {
        try { $null = Mq Delete "$mqPrefix$capture" }
        catch { $cleanupErrors.Add('Capture queue cleanup failed') }
    }
    $env:MYSQL_PWD = $previousMysqlPassword
    if ($cleanupErrors.Count -gt 0) { Write-Warning ($cleanupErrors -join '; ') }
}
Assert-True ($cleanupErrors.Count -eq 0) 'Day12 cleanup incomplete'
$report | Add-Member -NotePropertyName cleanup -NotePropertyValue 'Temporary driver/capture queue removed; demonstration order retained as CANCELLED with its SENT event'
$null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'logs')
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $projectRoot 'logs/day12-http-result.json') -Encoding utf8
$report | ConvertTo-Json -Depth 8
