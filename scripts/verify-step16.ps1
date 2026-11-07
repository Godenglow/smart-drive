#requires -Version 7.0
param([switch]$Live)
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
$null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'logs')
$createdFile = Join-Path $projectRoot 'logs/step16-created-accounts.jsonl'
$reportPath = Join-Path $projectRoot 'logs/step16-verification.json'
$oldMysqlPassword = $env:MYSQL_PWD
$oldLive = $env:SMARTDRIVE_STEP16_LIVE
$oldOutput = $env:PLAYWRIGHT_JSON_OUTPUT
$env:MYSQL_PWD = $env:SMARTDRIVE_DB_PASSWORD
$env:SMARTDRIVE_STEP16_LIVE = if ($Live) { 'true' } else { 'false' }
$env:PLAYWRIGHT_JSON_OUTPUT = '../logs/step16-browser-results.json'
$dbUser = if ($env:SMARTDRIVE_DB_USER) { $env:SMARTDRIVE_DB_USER } else { 'smartdrive' }
$testPassed = $false
$accountsVerified = 0
$ordersVerified = 0
$cleanupErrors = [Collections.Generic.List[string]]::new()
function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Step16 SQL failed' }
    return $result
}
Push-Location (Join-Path $projectRoot 'web-app')
try {
    if ($Live) {
        if (-not $env:SMARTDRIVE_DB_PASSWORD) { throw '真实验收需要 SMARTDRIVE_DB_PASSWORD，以核对并清理临时数据。' }
        if (Test-Path $createdFile) { throw '存在上次未清理的 Step16 数据清单，请先核对处理，避免覆盖。' }
        $null = Sql 'SELECT COUNT(*) FROM order_db.ride_order;'
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    # 回归 Step15 的模拟/移动测试；Step16 真实链路包含真实账号登录。
    & npm.cmd test -- --project=chromium --project=mobile --project=orders --project=orders-mobile --project=orders-live
    if ($LASTEXITCODE -ne 0) { throw '浏览器验收失败，见 logs/step16-browser-results.json' }
    $testPassed = $true
} finally {
    Pop-Location
    if ($Live -and (Test-Path $createdFile)) {
        try {
            $accounts = @(Get-Content $createdFile | ForEach-Object { $_ | ConvertFrom-Json })
            foreach ($account in $accounts) {
                $id = [long]$account.userId
                if ($id -le 0 -or $account.phone -notmatch '^199\d{8}$' -or $account.role -notin @('PASSENGER','DRIVER')) { throw '临时账号标识不符合清理约束' }
                $count = Sql ("SELECT COUNT(*) FROM account_db.account WHERE id={0} AND phone='{1}' AND role='{2}' AND password_hash REGEXP '^[$]2[aby][$]';" -f $id,$account.phone,$account.role)
                if ([int]$count -ne 1) { throw '临时账号或 BCrypt 核对失败' }
                $accountsVerified++
            }
            $passengers = @($accounts | Where-Object role -eq 'PASSENGER' | ForEach-Object { [long]$_.userId })
            $orderIds = if ($passengers.Count) { @(Sql "SELECT id FROM order_db.ride_order WHERE passenger_id IN ($($passengers -join ','));") } else { @() }
            if ($orderIds.Count) {
                $ids = @($orderIds | ForEach-Object { [long]$_ }) -join ','
                # 只在本次事件已完成发布、消费且无共享在途消息后清理引用。
                $mqUrl = if ($env:RABBITMQ_MANAGEMENT_URL) { $env:RABBITMQ_MANAGEMENT_URL } else { 'http://localhost:15672' }
                $mqUser = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'guest' }
                $mqPassword = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'guest' }
                $vhost = [Uri]::EscapeDataString($(if ($env:RABBITMQ_VHOST) { $env:RABBITMQ_VHOST } else { '/' }))
                $headers = @{ Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${mqUser}:$mqPassword")) }
                for ($attempt=0; $attempt -lt 40; $attempt++) {
                    $processed = [int](Sql "SELECT COUNT(*) FROM order_db.outbox_event e JOIN driver_db.processed_order_event p ON p.order_id=e.aggregate_id AND p.event_id=e.event_id WHERE e.aggregate_id IN ($ids) AND e.status='SENT';")
                    $queue = Invoke-RestMethod "$mqUrl/api/queues/$vhost/driver.matching.q" -Headers $headers -TimeoutSec 10
                    if ($processed -eq $orderIds.Count -and $queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0) { break }
                    Start-Sleep -Milliseconds 500
                }
                if ($processed -ne $orderIds.Count -or $queue.messages_ready -ne 0 -or $queue.messages_unacknowledged -ne 0) { throw '事件或在途消息未处理完，保留本次数据清单供恢复清理' }
                $ordersVerified = $orderIds.Count
                $null = Sql "START TRANSACTION; DELETE FROM order_db.driver_active_order WHERE order_id IN ($ids); DELETE FROM driver_db.order_notice WHERE order_id IN ($ids); DELETE FROM driver_db.processed_order_event WHERE order_id IN ($ids); DELETE FROM order_db.outbox_event WHERE aggregate_id IN ($ids) AND event_type='order.created'; DELETE FROM order_db.ride_order WHERE id IN ($ids) AND passenger_id IN ($($passengers -join ',')); COMMIT;"
                if ([int](Sql "SELECT COUNT(*) FROM order_db.ride_order WHERE id IN ($ids);") -ne 0) { throw '订单清理核对失败' }
            }
            foreach ($account in $accounts) {
                $id = [long]$account.userId
                if ([int](Sql "SELECT COUNT(*) FROM order_db.ride_order WHERE passenger_id=$id OR driver_id=$id;") -ne 0) { throw '仍有订单引用临时账号，保留账号' }
                $null = Sql "DELETE FROM driver_db.order_notice WHERE driver_id=$id; DELETE FROM driver_db.driver_profile WHERE user_id=$id AND display_name='Step16-browser-fixture'; DELETE FROM account_db.account WHERE id=$id AND phone='$($account.phone)';"
                if ([int](Sql "SELECT COUNT(*) FROM account_db.account WHERE id=$id AND phone='$($account.phone)';") -ne 0) { throw '临时账号清理核对失败' }
            }
            Remove-Item -LiteralPath $createdFile
        } catch { $cleanupErrors.Add($_.Exception.Message) }
    }
    $env:MYSQL_PWD = $oldMysqlPassword
    $env:SMARTDRIVE_STEP16_LIVE = $oldLive
    $env:PLAYWRIGHT_JSON_OUTPUT = $oldOutput
    @{timestamp=(Get-Date).ToString('o'); live=[bool]$Live; testPassed=$testPassed; accountsVerified=$accountsVerified; ordersVerified=$ordersVerified; cleanupPassed=($cleanupErrors.Count -eq 0); cleanupErrors=@($cleanupErrors)} | ConvertTo-Json -Depth 5 | Set-Content $reportPath
    if ($cleanupErrors.Count) { throw "临时数据核对/清理失败，见 $reportPath" }
}
