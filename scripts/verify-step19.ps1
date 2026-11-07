#requires -Version 7.0
param([switch]$IsolatePublisher = $true)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localEnv = Join-Path $projectRoot '.env.local'
if (Test-Path $localEnv) {
    Get-Content $localEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            if ($null -eq [Environment]::GetEnvironmentVariable($matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
            }
        }
    }
}
if (-not $env:SMARTDRIVE_DB_PASSWORD) { throw 'SMARTDRIVE_DB_PASSWORD is required' }
$flags = 'SMARTDRIVE_MYSQL_INTEGRATION','SMARTDRIVE_REDIS_INTEGRATION','SMARTDRIVE_RABBITMQ_INTEGRATION'
$previous = @{}
foreach ($flag in $flags) {
    $previous[$flag] = [Environment]::GetEnvironmentVariable($flag, 'Process')
    [Environment]::SetEnvironmentVariable($flag, 'true', 'Process')
}
$null = New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'logs/step19')
$started = Get-Date
$report = @{startedAt=$started.ToString('o'); passed=$false; tests=0; failures=0; errors=0; skipped=0; suites=@()}
$publisherSuspended = $false
Push-Location $projectRoot
try {
    if ($IsolatePublisher) {
        # Tests share order_db and manually drive their own random outbox events.
        # A live scheduler can steal them, especially with Step21's shorter polling.
        $preflight = @'
import sys
sys.path.insert(0,'scripts')
import step19_support as s
s.load_env()
q=s.queue()
s.require(s.number("SELECT COUNT(*) FROM order_db.outbox_event WHERE status='NEW'")==0
          and q.get('messages_ready',0)==0 and q.get('messages_unacknowledged',0)==0,
          'Settle outbox/MQ before isolated regression')
'@
        & python -c $preflight
        if ($LASTEXITCODE -ne 0) { throw 'Outbox/MQ preflight failed' }
        $listener = Get-NetTCPConnection -LocalPort 8083 -State Listen -ErrorAction Stop
        $ownerId = $listener[0].OwningProcess
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId"
        if ($owner.Name -ne 'java.exe' -or $owner.CommandLine -notlike "*$projectRoot*" -or
            $owner.CommandLine -notlike '*order-service*.jar*') {
            throw 'Unexpected order-service process identity'
        }
        $jarArgument = [regex]::Match($owner.CommandLine, '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
        if (-not $jarArgument.Success) { throw 'Cannot identify order-service recovery JAR' }
        $runningJar = if ($jarArgument.Groups[1].Success) { $jarArgument.Groups[1].Value } else { $jarArgument.Groups[2].Value }
        $runningJar = [IO.Path]::GetFullPath($runningJar, $projectRoot)
        if (-not $runningJar.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Order-service recovery JAR is outside the workspace'
        }
        $recoveryDirectory = Join-Path $projectRoot 'logs/step19-recovery'
        $null = New-Item -ItemType Directory -Force -Path $recoveryDirectory
        $recoveryJar = Join-Path $recoveryDirectory 'order-service.jar'
        Copy-Item -LiteralPath $runningJar -Destination $recoveryJar -Force
        $report.recoveryJarSha256 = (Get-FileHash -LiteralPath $recoveryJar -Algorithm SHA256).Hash
        Stop-Process -Id $ownerId
        $publisherSuspended = $true
        $report.publisherIsolated = $true
    }
    & python -m unittest discover -s scripts -p 'test_step19*.py' 2>&1 | Tee-Object 'logs/step19-tools.log'
    if ($LASTEXITCODE -ne 0) { throw 'Step19 tooling tests failed' }
    # Windows services hold target/*.jar open. Remove only generated XML, then reject stale/missing suites.
    foreach ($oldReport in Get-ChildItem -Path '*/target/surefire-reports/TEST-*.xml') {
        if (-not $oldReport.FullName.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Surefire report path is outside the workspace'
        }
        Remove-Item -LiteralPath $oldReport.FullName
    }
    & mvn.cmd -o test 2>&1 | Tee-Object 'logs/step19-maven.log'
    if ($LASTEXITCODE -ne 0) { throw 'Maven regression failed' }
    $reports = @(Get-ChildItem -Path '*/target/surefire-reports/TEST-*.xml')
    if ($reports.Count -eq 0) { throw 'No Surefire reports generated' }
    foreach ($file in $reports) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $xml.testsuite
        if ($file.LastWriteTime -lt $started) { throw "Stale Surefire report: $($file.Name)" }
        foreach ($field in 'tests','failures','errors','skipped') { $report[$field] += [int]$suite.$field }
        $report.suites += @{name=[string]$suite.name; tests=[int]$suite.tests; skipped=[int]$suite.skipped}
    }
    foreach ($required in 'AccountMySqlIntegrationTest','OrderMySqlIntegrationTest','OrderAcceptanceMySqlIntegrationTest',
                          'OrderFulfillmentMySqlIntegrationTest','OutboxMySqlIntegrationTest','MatchingIntegrationTest',
                          'DriverRedisIntegrationTest','OutboxRabbitIntegrationTest') {
        $found = @($report.suites | Where-Object { $_.name.EndsWith(".$required") })
        if ($found.Count -ne 1 -or $found[0].tests -lt 1 -or $found[0].skipped -ne 0) { throw "Missing/skipped required suite: $required" }
    }
    if ($report.failures -ne 0 -or $report.errors -ne 0 -or $report.skipped -ne 0) { throw 'Real regression must have zero failures, errors and skips' }
    if ($publisherSuspended) {
        & (Join-Path $PSScriptRoot 'start-local.ps1') -Service order-service -RecoveryJar $recoveryJar
        $publisherSuspended = $false
    }
    & python scripts/verify-step19.py 2>&1 | Tee-Object 'logs/step19-live.log'
    if ($LASTEXITCODE -ne 0) { throw 'Step19 HTTP/data/load verification failed' }
    $live = Get-Content 'logs/step19-http-result.json' -Raw | ConvertFrom-Json
    if (-not $live.passed -or -not $live.cleanupPassed) { throw 'Live verification or cleanup incomplete' }
    $report.liveRunId = $live.runId
    $report.passed = $true
} finally {
    try {
        if ($publisherSuspended) {
            & (Join-Path $PSScriptRoot 'start-local.ps1') -Service order-service -RecoveryJar $recoveryJar
        }
    } finally {
        $report.finishedAt = (Get-Date).ToString('o')
        $report | ConvertTo-Json -Depth 8 | Set-Content 'logs/step19-verification.json' -Encoding utf8
        foreach ($flag in $flags) { [Environment]::SetEnvironmentVariable($flag, $previous[$flag], 'Process') }
        Pop-Location
    }
}
$report | ConvertTo-Json -Depth 8
