#requires -Version 7.0
param([switch]$Live)
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
$createdFile = Join-Path $projectRoot 'logs/day15-created-accounts.jsonl'
$oldMysqlPassword = $env:MYSQL_PWD
$oldLive = $env:SMARTDRIVE_LIVE_TEST
$dbUser = if ($env:SMARTDRIVE_DB_USER) { $env:SMARTDRIVE_DB_USER } else { 'smartdrive' }
$env:MYSQL_PWD = $env:SMARTDRIVE_DB_PASSWORD
$env:SMARTDRIVE_LIVE_TEST = if ($Live) { 'true' } else { 'false' }
$testPassed = $false
$cleanupErrors = [Collections.Generic.List[string]]::new()
$accountsVerified = 0
$reportPath = Join-Path $projectRoot 'logs/day15-verification.json'
function Sql([string]$statement) {
    $result = & mysql.exe --host=127.0.0.1 "--user=$dbUser" --batch --skip-column-names "--execute=$statement"
    if ($LASTEXITCODE -ne 0) { throw 'Day15 SQL failed' }
    return $result
}
Push-Location (Join-Path $projectRoot 'web-app')
try {
    if ($Live -and -not $env:SMARTDRIVE_DB_PASSWORD) { throw '真实验收需要 SMARTDRIVE_DB_PASSWORD，用于清理本次临时账号。' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw '浏览器验收失败，见 logs/day15-browser-results.json' }
    $testPassed = $true
} finally {
    Pop-Location
    if ($Live -and (Test-Path $createdFile)) {
        foreach ($line in Get-Content $createdFile) {
            try {
                $account = $line | ConvertFrom-Json
                $id = [long]$account.userId
                $phone = [string]$account.phone
                if ($id -le 0 -or $phone -notmatch '^199\d{8}$') { throw '临时账号标识不符合清理约束' }
                $count = Sql ('SELECT COUNT(*) FROM account_db.account WHERE id={0} AND phone=''{1}'' AND password_hash REGEXP ''^[$]2[aby][$]'';' -f $id, $phone)
                if ([int]$count -ne 1) { throw '注册账号或 BCrypt 存储核对失败' }
                $accountsVerified++
                $null = Sql "DELETE FROM account_db.account WHERE id=$id AND phone='$phone';"
                $remaining = Sql "SELECT COUNT(*) FROM account_db.account WHERE id=$id AND phone='$phone';"
                if ([int]$remaining -ne 0) { throw '临时账号未清理' }
            } catch { $cleanupErrors.Add($_.Exception.Message) }
        }
        if ($cleanupErrors.Count -eq 0) { Remove-Item -LiteralPath $createdFile }
    }
    $env:MYSQL_PWD = $oldMysqlPassword
    $env:SMARTDRIVE_LIVE_TEST = $oldLive
    New-Item -ItemType Directory -Path (Join-Path $projectRoot 'logs') -Force | Out-Null
    @{timestamp=(Get-Date).ToString('o');live=[bool]$Live;testPassed=$testPassed;accountsVerified=$accountsVerified;cleanupPassed=($cleanupErrors.Count -eq 0);cleanupErrors=@($cleanupErrors)} | ConvertTo-Json -Depth 5 | Set-Content $reportPath
    if ($cleanupErrors.Count -gt 0) { throw "临时账号核对/清理失败，见 $reportPath" }
}
