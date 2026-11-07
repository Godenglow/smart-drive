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
$cleanupErrors = [Collections.Generic.List[string]]::new()
$runId = [Guid]::NewGuid().ToString()
$passenger = $null

function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --default-character-set=utf8mb4 --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Step11 test SQL failed' }
    return $result
}
function Assert-True($condition, [string]$message) {
    if (-not $condition) { throw $message }
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
    $account = [PSCustomObject]@{id=[long]$registered.userId;phone=$phone;role=$role;headers=@{}}
    $accounts.Add($account)
    $login = Api Post '/api/auth/login' @{} @{phone=$phone;password=$password} 200
    $account.headers = @{Authorization="Bearer $($login.accessToken)"}
    return $account
}
function New-Order {
    $place = @{name="Step11-$runId";lng=117.215;lat=39.136}
    $created = Api Post '/api/orders' $passenger.headers @{requestId=[Guid]::NewGuid().ToString();pickup=$place;dropoff=$place} 201
    $orders.Add([long]$created.id)
    Wait-OrderMatching @([long]$created.id)
    Assert-True ($created.status -eq 'WAITING' -and $created.amount -eq 50.00) 'New order state or demo amount mismatch'
    return [long]$created.id
}
function Race([long]$id, [string]$action, $headers, [string]$state) {
    $requests = @(1..5 | ForEach-Object { @{url="$GatewayUrl/api/orders/$id/$action";authorization=$headers.Authorization} })
    $results = @($requests | ForEach-Object -Parallel {
        $response = Invoke-WebRequest -Method Post -Uri $_.url -Headers @{Authorization=$_.authorization} -SkipHttpErrorCheck -TimeoutSec 20
        $body = $response.Content | ConvertFrom-Json
        [PSCustomObject]@{status=[int]$response.StatusCode;code=$body.code;state=$body.data.status}
    } -ThrottleLimit 5)
    Assert-True (@($results | Where-Object { $_.status -eq 200 -and $_.state -eq $state }).Count -eq 1) "$action must have one successful transition"
    Assert-True (@($results | Where-Object { $_.status -eq 409 -and $_.code -eq 'ORDER_STATUS_INVALID' }).Count -eq 4) "$action duplicates must return conflict"
    return [PSCustomObject]@{action=$action;attempts=5;results=$results}
}

try {
    $null = Sql 'SELECT 1 FROM driver_db.order_notice LIMIT 1; SELECT 1 FROM order_db.driver_active_order LIMIT 1;'
    foreach ($service in @('account','driver','order')) {
        $health = Invoke-RestMethod "$GatewayUrl/actuator/$service/health" -TimeoutSec 20
        Assert-True ($health.status -eq 'UP') "$service health is not UP"
    }
    $passenger = Account PASSENGER
    $otherPassenger = Account PASSENGER
    $driver = Account DRIVER
    $otherDriver = Account DRIVER
    $null = Api Put '/api/drivers/me/profile' $driver.headers @{displayName="Step11-$runId"} 200
    $null = Api Put '/api/drivers/me/online' $driver.headers @{lng=117.215;lat=39.136} 200
    $id = New-Order
    foreach ($action in @('start','finish','pay-demo')) {
        $null = Api Post "/api/orders/$id/$action" @{} $null 401 UNAUTHORIZED
        $wrongRole = if ($action -eq 'pay-demo') { $driver.headers } else { $passenger.headers }
        $owner = if ($action -eq 'pay-demo') { $passenger.headers } else { $driver.headers }
        $null = Api Post "/api/orders/$id/$action" $wrongRole $null 403 FORBIDDEN
        $null = Api Post "/api/orders/9223372036854775807/$action" $owner $null 404 NOT_FOUND
        $null = Api Post "/api/orders/invalid/$action" $owner $null 400 INVALID_PARAM
    }
    $null = Api Post "/api/orders/$id/pay-demo" $passenger.headers $null 409 ORDER_STATUS_INVALID
    Assert-True ((Sql "SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id=$id AND driver_id=$($driver.id)") -eq 1) 'Real matching notification missing'
    $null = Api Post "/api/orders/$id/accept" $driver.headers $null 200
    foreach ($action in @('start','finish')) {
        $null = Api Post "/api/orders/$id/$action" $otherDriver.headers $null 403 FORBIDDEN
    }
    $null = Api Post "/api/orders/$id/finish" $driver.headers $null 409 ORDER_STATUS_INVALID
    $null = Api Post "/api/orders/$id/pay-demo" $passenger.headers $null 409 ORDER_STATUS_INVALID
    $startRace = Race $id start $driver.headers IN_SERVICE
    Assert-True ((Sql "SELECT order_id FROM order_db.driver_active_order WHERE driver_id=$($driver.id)") -eq $id) 'Start must keep occupation'
    $null = Api Post "/api/orders/$id/pay-demo" $passenger.headers $null 409 ORDER_STATUS_INVALID
    $finishRace = Race $id finish $driver.headers FINISHED
    Assert-True ((Sql "SELECT COUNT(*) FROM order_db.driver_active_order WHERE driver_id=$($driver.id)") -eq 0) 'Finish must release occupation before payment'

    # Accept another order before the old one is paid, then retry the old finish.
    $next = New-Order
    Assert-True ((Sql "SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id=$next AND driver_id=$($driver.id)") -eq 1) 'Next order notification missing'
    $null = Api Post "/api/orders/$next/accept" $driver.headers $null 200
    $null = Api Post "/api/orders/$id/finish" $driver.headers $null 409 ORDER_STATUS_INVALID
    $null = Api Post "/api/orders/$id/pay-demo" $otherPassenger.headers $null 403 FORBIDDEN
    $payRace = Race $id pay-demo $passenger.headers PAID
    Assert-True ((Sql "SELECT order_id FROM order_db.driver_active_order WHERE driver_id=$($driver.id)") -eq $next) 'Old finish/payment changed the new occupation'
    Assert-True ((Sql "SELECT amount FROM order_db.ride_order WHERE id=$id") -eq '50.00') 'Demo payment changed amount'
    foreach ($action in @('start','finish','pay-demo')) {
        $owner = if ($action -eq 'pay-demo') { $passenger.headers } else { $driver.headers }
        $null = Api Post "/api/orders/$id/$action" $owner $null 409 ORDER_STATUS_INVALID
    }
    $cancelled = New-Order
    $null = Api Post "/api/orders/$cancelled/cancel" $passenger.headers $null 200
    $null = Api Post "/api/orders/$cancelled/pay-demo" $passenger.headers $null 409 ORDER_STATUS_INVALID
    $history = Api Get '/api/orders/mine?page=1&size=2' $passenger.headers $null 200
    Assert-True ($history.total -eq 3 -and $history.items.Count -eq 2 -and $history.items[0].id -eq $cancelled -and $history.items[1].id -eq $next) 'Passenger history ordering or pagination failed'
    $paidPage = Api Get '/api/orders/mine?page=2&size=2' $passenger.headers $null 200
    Assert-True ($paidPage.items.Count -eq 1 -and $paidPage.items[0].id -eq $id -and $paidPage.items[0].status -eq 'PAID') 'Paid order missing from passenger history'
    $driverHistory = Api Get '/api/orders/mine?page=1&size=10' $driver.headers $null 200
    Assert-True ($driverHistory.total -eq 2 -and $driverHistory.items[1].id -eq $id -and $driverHistory.items[1].status -eq 'PAID') 'Driver history is missing completed order'
    foreach ($stranger in @($otherPassenger,$otherDriver)) {
        $empty = Api Get '/api/orders/mine' $stranger.headers $null 200
        Assert-True ($empty.total -eq 0) 'History leaked another user orders'
        $null = Api Get "/api/orders/$id" $stranger.headers $null 403 FORBIDDEN
    }
    foreach ($owner in @($passenger,$driver)) {
        $detail = Api Get "/api/orders/$id" $owner.headers $null 200
        Assert-True ($detail.status -eq 'PAID') 'Final detail is not PAID'
    }
    foreach ($query in @('page=0','size=101')) {
        $null = Api Get "/api/orders/mine?$query" $passenger.headers $null 400 INVALID_PARAM
    }
    $doc = Invoke-RestMethod 'http://localhost:8083/v3/api-docs' -TimeoutSec 20
    foreach ($action in @('start','finish','pay-demo')) {
        Assert-True ($null -ne $doc.paths."/api/orders/{id}/$action".post) "OpenAPI $action endpoint missing"
    }
    $report = [PSCustomObject]@{time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');runId=$runId;orderIds=$orders.ToArray();races=@($startRace,$finishRace,$payRace);checks=$checks.ToArray();history='Passenger and driver scoped, ordered and paginated';releasedBeforePayment=$true;newOccupationPreserved=$true;openApi=$true}
} finally {
    try {
    # Do not remove deduplication rows while an event can still arrive or be redelivered.
    Wait-OrderMatching $orders.ToArray()
    # Attempt all cleanup steps even if a service is unavailable; retain the original failure.
    foreach ($account in $accounts) {
        if ($account.role -eq 'DRIVER' -and $account.headers.Authorization) {
            try { $null = Api Put '/api/drivers/me/offline' $account.headers $null 200 }
            catch { $cleanupErrors.Add("Driver offline failed for test account $($account.id)") }
        }
    }
    foreach ($id in $orders) {
        try {
            $null = Sql "DELETE FROM driver_db.processed_order_event WHERE order_id=$id; DELETE FROM order_db.outbox_event WHERE aggregate_id=$id;"
            foreach ($account in $accounts) {
                $null = Sql "DELETE FROM driver_db.order_notice WHERE order_id=$id AND driver_id=$($account.id); DELETE FROM order_db.driver_active_order WHERE order_id=$id AND driver_id=$($account.id);"
            }
            if ($passenger) { $null = Sql "DELETE FROM order_db.ride_order WHERE id=$id AND passenger_id=$($passenger.id)" }
            Assert-True ((Sql "SELECT (SELECT COUNT(*) FROM order_db.ride_order WHERE id=$id) + (SELECT COUNT(*) FROM order_db.driver_active_order WHERE order_id=$id) + (SELECT COUNT(*) FROM driver_db.order_notice WHERE order_id=$id)") -eq 0) 'Test order cleanup incomplete'
        } catch { $cleanupErrors.Add("Test order cleanup failed for $id") }
    }
    foreach ($account in $accounts) {
        try { $null = Sql "DELETE FROM driver_db.driver_profile WHERE user_id=$($account.id) AND display_name='Step11-$runId'; DELETE FROM account_db.account WHERE id=$($account.id) AND phone='$($account.phone)';" }
        catch { $cleanupErrors.Add("Test account cleanup failed for $($account.id)") }
    }
    } finally { $env:MYSQL_PWD = $previousMysqlPassword }
    if ($cleanupErrors.Count -gt 0) { Write-Warning ($cleanupErrors -join '; ') }
}
Assert-True ($cleanupErrors.Count -eq 0) 'Step11 test cleanup did not finish'
$logDir = Join-Path $projectRoot 'logs'
$null = New-Item -ItemType Directory -Force -Path $logDir
$report | Add-Member -NotePropertyName cleanup -NotePropertyValue 'Verified: only this run test rows removed'
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $logDir 'step11-http-result.json') -Encoding utf8
$report | ConvertTo-Json -Depth 8
