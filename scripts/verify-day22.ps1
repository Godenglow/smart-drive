#requires -Version 7.0
param()
$ErrorActionPreference = 'Stop'
$oldPythonEncoding = $env:PYTHONIOENCODING
$env:PYTHONIOENCODING = 'utf-8'
$root = Split-Path $PSScriptRoot -Parent
$runId = [guid]::NewGuid().ToString()
$directory = Join-Path $root "logs/day22/$runId"
New-Item -ItemType Directory -Force $directory | Out-Null
$report = @{day=22; runId=$runId; startedAt=(Get-Date).ToString('o'); passed=$false; steps=@()}
function Step([string]$name, [scriptblock]$command) {
    $started = Get-Date
    & $command 2>&1 | Tee-Object "$directory/$name.log"
    if ($LASTEXITCODE -ne 0) { throw "Failed step: $name" }
    $report.steps += @{name=$name; passed=$true; startedAt=$started.ToString('o'); finishedAt=(Get-Date).ToString('o')}
}
Push-Location $root
try {
    Step 'tools' { python -m unittest discover -s scripts -p 'test_day*_tools.py' -v }
    Step 'package' { mvn.cmd -o -DskipTests package }
    Step 'startup' { & scripts/start-local.ps1 }
    Step 'historical-baseline' { python scripts/audit-day22.py --directory "logs/day22/$runId" --baseline }
    Step 'demo-reset' { python scripts/demo-data.py reset }
    Step 'api-example' { python scripts/demo-api.py }
    Copy-Item 'logs/day22/demo-api-result.json' $directory
    Step 'demo-reset-check' { python scripts/audit-day22.py --directory "logs/day22/$runId" --exercise-demo }
    Step 'backend' { & scripts/verify-day19.ps1 -IsolatePublisher }
    foreach ($name in 'day19-verification.json','day19-http-result.json') { Copy-Item "logs/$name" $directory }
    foreach ($day in 15,16,17,18) {
        Step "browser-day$day" { & "scripts/verify-day$day.ps1" -Live }
        foreach ($name in "day$day-verification.json","day$day-browser-results.json") { Copy-Item "logs/$name" $directory }
        $browser = Get-Content "logs/day$day-browser-results.json" -Raw | ConvertFrom-Json
        $verification = Get-Content "logs/day$day-verification.json" -Raw | ConvertFrom-Json
        if (-not $verification.testPassed -or -not $verification.cleanupPassed -or
            $browser.stats.unexpected -ne 0 -or $browser.stats.flaky -ne 0 -or $browser.stats.skipped -ne 0) {
            throw "Browser tests or cleanup incomplete: Day$day"
        }
    }
    foreach ($name in 'passenger','driver-a','driver-b') { Copy-Item "logs/day18-$name.webm" "$directory/$name.webm" }
    Copy-Item 'logs/day18-timeline.json' $directory
    Step 'video-qa' { node scripts/verify-demo-videos.cjs "logs/day22/$runId" }
    Step 'mq-diagnostics' { & scripts/verify-mq-diagnostics.ps1 }
    Copy-Item 'logs/day14-diagnostics-result.json' $directory
    Step 'performance-audit' { python scripts/audit-day21.py --output "logs/day22/$runId/day21-comparison.json" }
    Step 'delivery-audit' { python scripts/audit-day22.py --directory "logs/day22/$runId" }
    $report.passed = $true
} finally {
    $report.finishedAt = (Get-Date).ToString('o')
    $report | ConvertTo-Json -Depth 10 | Set-Content "$directory/result.json" -Encoding utf8
    $report | ConvertTo-Json -Depth 10 | Set-Content 'logs/day22-result.json' -Encoding utf8
    $env:PYTHONIOENCODING = $oldPythonEncoding
    Pop-Location
}
$report | ConvertTo-Json -Depth 10
