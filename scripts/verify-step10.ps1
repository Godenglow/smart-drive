#requires -Version 7.0
param([string]$GatewayUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localEnv = Join-Path $projectRoot '.env.local'
if (Test-Path $localEnv) {
    Get-Content $localEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$' -and
            $null -eq [Environment]::GetEnvironmentVariable($matches[1], 'Process')) {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:SMARTDRIVE_DB_PASSWORD
. (Join-Path $PSScriptRoot 'live-order-fixtures.ps1')
$dbUser = if ($env:SMARTDRIVE_DB_USER) { $env:SMARTDRIVE_DB_USER } else { 'smartdrive' }
$checks = [Collections.Generic.List[object]]::new()
$accounts = [Collections.Generic.List[object]]::new()
$orders = [Collections.Generic.List[long]]::new()
$runId = [Guid]::NewGuid().ToString()

function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --default-character-set=utf8mb4 --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Step10 test SQL failed' }
    return $result
}
function Api($method, $path, $headers, $body, $expected, $code = $null) {
    $options = @{ Method=$method; Uri="$GatewayUrl$path"; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=20 }
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 6 -Compress); $options.ContentType='application/json' }
    $response = Invoke-WebRequest @options
    $json = $response.Content | ConvertFrom-Json
    if ([int]$response.StatusCode -ne $expected -or ($code -and $json.code -ne $code)) {
        throw "Unexpected response for $method $path : HTTP $($response.StatusCode), code=$($json.code)"
    }
    $checks.Add([PSCustomObject]@{method=$method;path=$path;status=[int]$response.StatusCode;code=$json.code})
    return $json.data
}
function Account([string]$role) {
    $phone = '199' + (Get-Random -Minimum 10000000 -Maximum 100000000)
    $password = [Guid]::NewGuid().ToString('N')
    $registered = Api Post '/api/auth/register' @{} @{phone=$phone;password=$password;role=$role} 200
    # Track immediately so a failed login still cleans up the registered test account.
    $account = [PSCustomObject]@{id=[long]$registered.userId;phone=$phone;role=$role;headers=@{}}
    $accounts.Add($account)
    $login = Api Post '/api/auth/login' @{} @{phone=$phone;password=$password} 200
    $account.headers = @{Authorization="Bearer $($login.accessToken)"}
    return $account
}
function New-Order($passenger) {
    $place = @{name="Step10-$runId";lng=117.215;lat=39.136}
    $created = Api Post '/api/orders' $passenger.headers @{requestId=[Guid]::NewGuid().ToString();pickup=$place;dropoff=$place} 201
    $orders.Add([long]$created.id)
    Wait-OrderMatching @([long]$created.id)
    return [long]$created.id
}
function Notice([long]$order, [long]$driver) {
    Assert-True ((Sql "SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id=$order AND driver_id=$driver") -eq 1) 'Real matching did not notify the expected driver'
}
function Assert-True($condition, [string]$message) {
    if (-not $condition) { throw $message }
}

try {
    $null = Sql 'SELECT 1 FROM driver_db.order_notice LIMIT 1; SELECT 1 FROM order_db.driver_active_order LIMIT 1;'
    foreach ($service in @('account','driver','order')) {
        $health = Invoke-RestMethod "$GatewayUrl/actuator/$service/health" -TimeoutSec 20
        Assert-True ($health.status -eq 'UP') "$service health is not UP"
    }
    $passenger = Account PASSENGER
    $d1 = Account DRIVER
    $d2 = Account DRIVER
    $unnotified = Account DRIVER
    foreach ($driver in @($d1,$d2)) {
        $null = Api Put '/api/drivers/me/profile' $driver.headers @{displayName="Step10-$runId"} 200
        $null = Api Put '/api/drivers/me/online' $driver.headers @{lng=117.215;lat=39.136} 200
    }
    $id = New-Order $passenger
    $null = Api Post "/api/orders/$id/accept" @{} $null 401 UNAUTHORIZED
    $null = Api Post "/api/orders/$id/accept" $passenger.headers $null 403 FORBIDDEN
    $null = Api Post "/api/orders/$id/accept" $unnotified.headers $null 403 FORBIDDEN
    Notice $id $d1.id
    $null = Api Put '/api/drivers/me/offline' $d1.headers $null 200
    $null = Api Post "/api/orders/$id/accept" $d1.headers $null 409 DRIVER_OFFLINE
    $null = Api Put '/api/drivers/me/online' $d1.headers @{lng=117.215;lat=39.136} 200
    Notice $id $d2.id
    # Release both HTTP calls together, traversing Gateway -> Nacos -> order -> driver -> Redis/MySQL.
    $requests = @(
        @{url="$GatewayUrl/api/orders/$id/accept";authorization=$d1.headers.Authorization},
        @{url="$GatewayUrl/api/orders/$id/accept";authorization=$d2.headers.Authorization}
    )
    $race = @($requests | ForEach-Object -Parallel {
        $response = Invoke-WebRequest -Method Post -Uri $_.url -Headers @{Authorization=$_.authorization} -SkipHttpErrorCheck -TimeoutSec 20
        $body = $response.Content | ConvertFrom-Json
        [PSCustomObject]@{status=[int]$response.StatusCode;code=$body.code;driverId=$body.data.driverId}
    } -ThrottleLimit 2)
    Assert-True (@($race | Where-Object status -eq 200).Count -eq 1) 'HTTP same-order race must have one winner'
    Assert-True (@($race | Where-Object { $_.status -eq 409 -and $_.code -eq 'ORDER_ALREADY_TAKEN' }).Count -eq 1) 'HTTP race loser must return order conflict'
    $winnerId = [long]($race | Where-Object status -eq 200).driverId
    $winner = @($d1,$d2) | Where-Object id -eq $winnerId
    $loser = @($d1,$d2) | Where-Object id -ne $winnerId
    $detail = Api Get "/api/orders/$id" $winner.headers $null 200
    Assert-True ($detail.status -eq 'ACCEPTED' -and $detail.driverId -eq $winnerId) 'Accepted ownership mismatch'
    $candidate = Api Get "/api/orders/$id" $loser.headers $null 200
    Assert-True ($candidate.status -eq 'ACCEPTED' -and $null -eq $candidate.passengerId -and
                 $null -eq $candidate.driverId -and $null -eq $candidate.requestId) 'Candidate view leaked private IDs'
    $null = Api Get "/api/orders/$id" $unnotified.headers $null 403 FORBIDDEN
    $null = Api Post "/api/orders/$id/accept" $winner.headers $null 409 ORDER_ALREADY_TAKEN
    $null = Api Post "/api/orders/$id/cancel" $passenger.headers $null 409 ORDER_NOT_WAITING
    $active = [long](Sql "SELECT COUNT(*) FROM order_db.driver_active_order WHERE order_id=$id AND driver_id=$winnerId")
    Assert-True ($active -eq 1) 'Winning occupation is missing'

    $second = New-Order $passenger
    Notice $second $winnerId
    $null = Api Post "/api/orders/$second/accept" $winner.headers $null 409 DRIVER_BUSY
    Assert-True ((Sql "SELECT status FROM order_db.ride_order WHERE id=$second") -eq 'WAITING') 'Busy attempt changed the second order'
    Notice $second $loser.id
    $accepted = Api Post "/api/orders/$second/accept" $loser.headers $null 200
    Assert-True ($accepted.driverId -eq $loser.id) 'Losing driver retained a stale occupation'

    $cancelled = New-Order $passenger
    Notice $cancelled $winnerId
    $null = Api Post "/api/orders/$cancelled/cancel" $passenger.headers $null 200
    $null = Api Post "/api/orders/$cancelled/accept" $winner.headers $null 409 ORDER_NOT_WAITING
    $null = Api Post '/api/orders/9223372036854775807/accept' $winner.headers $null 404 NOT_FOUND
    $internal = Invoke-WebRequest "$GatewayUrl/internal/drivers/$winnerId/eligibility?orderId=$id" -Headers $winner.headers -SkipHttpErrorCheck -TimeoutSec 20
    Assert-True ($internal.StatusCode -eq 404) 'Gateway exposed internal eligibility'
    $direct = Invoke-WebRequest "http://localhost:8082/internal/drivers/$winnerId/eligibility?orderId=$id" -SkipHttpErrorCheck -TimeoutSec 20
    Assert-True ($direct.StatusCode -eq 403) 'Internal endpoint accepted a request without its shared token'
    $doc = Invoke-RestMethod 'http://localhost:8083/v3/api-docs' -TimeoutSec 20
    Assert-True ($null -ne $doc.paths.'/api/orders/{id}/accept'.post) 'OpenAPI accept endpoint is missing'
    $report = [PSCustomObject]@{time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');runId=$runId;orderIds=$orders.ToArray();httpRace=$race;checks=$checks.ToArray();internalGatewayStatus=[int]$internal.StatusCode;internalDirectStatus=[int]$direct.StatusCode;openApiAccept=$true}
} finally {
    try {
    Wait-OrderMatching $orders.ToArray()
    foreach ($account in $accounts) {
        if ($account.role -eq 'DRIVER' -and $account.headers.Authorization) {
            $null = Api Put '/api/drivers/me/offline' $account.headers $null 200
        }
    }
    foreach ($id in $orders) {
        $null = Sql "DELETE FROM driver_db.processed_order_event WHERE order_id=$id; DELETE FROM order_db.outbox_event WHERE aggregate_id=$id;"
        foreach ($account in $accounts) {
            $null = Sql "DELETE FROM driver_db.order_notice WHERE order_id=$id AND driver_id=$($account.id); DELETE FROM order_db.driver_active_order WHERE order_id=$id AND driver_id=$($account.id);"
        }
        if ($passenger) { $null = Sql "DELETE FROM order_db.ride_order WHERE id=$id AND passenger_id=$($passenger.id)" }
    }
    foreach ($account in $accounts) {
        $null = Sql "DELETE FROM driver_db.driver_profile WHERE user_id=$($account.id) AND display_name='Step10-$runId'; DELETE FROM account_db.account WHERE id=$($account.id) AND phone='$($account.phone)';"
    }
    } finally { $env:MYSQL_PWD = $previousMysqlPassword }
}
$logDir = Join-Path $projectRoot 'logs'
$null = New-Item -ItemType Directory -Force -Path $logDir
$report | Add-Member -NotePropertyName cleanup -NotePropertyValue 'Completed: only this run test rows removed'
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $logDir 'step10-http-result.json') -Encoding utf8
$report | ConvertTo-Json -Depth 8
