#requires -Version 7.0
param()
$ErrorActionPreference = 'Stop'
$oldPythonEncoding = $env:PYTHONIOENCODING
$env:PYTHONIOENCODING = 'utf-8'
$root = Split-Path $PSScriptRoot -Parent
$runId = [guid]::NewGuid().ToString()
$directory = Join-Path $root "logs/step22/$runId"
New-Item -ItemType Directory -Force $directory | Out-Null
$report = @{step=22; runId=$runId; startedAt=(Get-Date).ToString('o'); passed=$false; steps=@()}
function Step([string]$name, [scriptblock]$command) {
    $started = Get-Date
    & $command 2>&1 | Tee-Object "$directory/$name.log"
    if ($LASTEXITCODE -ne 0) { throw "Failed step: $name" }
    $report.steps += @{name=$name; passed=$true; startedAt=$started.ToString('o'); finishedAt=(Get-Date).ToString('o')}
}
Push-Location $root
try {
    Step 'tools' { python -m unittest discover -s scripts -p 'test_step*_tools.py' -v }
    Step 'package' { mvn.cmd -o -DskipTests package }
    Step 'startup' { & scripts/start-local.ps1 -Restart }
    Step 'historical-baseline' { python scripts/audit-step22.py --directory "logs/step22/$runId" --baseline }
    Step 'demo-reset' { python scripts/demo-data.py reset }
    Step 'api-example' { python scripts/demo-api.py }
    Copy-Item 'logs/step22/demo-api-result.json' $directory
    Step 'demo-reset-check' { python scripts/audit-step22.py --directory "logs/step22/$runId" --exercise-demo }
    Step 'backend' { & scripts/verify-step19.ps1 -IsolatePublisher }
    foreach ($name in 'step19-verification.json','step19-http-result.json') { Copy-Item "logs/$name" $directory }
    foreach ($step in 15,16,17,18) {
        Step "browser-step$step" { & "scripts/verify-step$step.ps1" -Live }
        foreach ($name in "step$step-verification.json","step$step-browser-results.json") { Copy-Item "logs/$name" $directory }
        $browser = Get-Content "logs/step$step-browser-results.json" -Raw | ConvertFrom-Json
        $verification = Get-Content "logs/step$step-verification.json" -Raw | ConvertFrom-Json
        if (-not $verification.testPassed -or -not $verification.cleanupPassed -or
            $browser.stats.unexpected -ne 0 -or $browser.stats.flaky -ne 0 -or $browser.stats.skipped -ne 0) {
            throw "Browser tests or cleanup incomplete: Step$step"
        }
    }
    foreach ($name in 'passenger','driver-a','driver-b') { Copy-Item "logs/step18-$name.webm" "$directory/$name.webm" }
    Copy-Item 'logs/step18-timeline.json' $directory
    Step 'video-qa' { node scripts/verify-demo-videos.cjs "logs/step22/$runId" }
    Step 'mq-diagnostics' { & scripts/verify-mq-diagnostics.ps1 }
    Copy-Item 'logs/step14-diagnostics-result.json' $directory
    Step 'performance-audit' { python scripts/audit-step21.py --output "logs/step22/$runId/step21-comparison.json" }
    Step 'delivery-audit' { python scripts/audit-step22.py --directory "logs/step22/$runId" }
    $report.passed = $true
} finally {
    $report.finishedAt = (Get-Date).ToString('o')
    $report | ConvertTo-Json -Depth 10 | Set-Content "$directory/result.json" -Encoding utf8
    $report | ConvertTo-Json -Depth 10 | Set-Content 'logs/step22-result.json' -Encoding utf8
    $env:PYTHONIOENCODING = $oldPythonEncoding
    Pop-Location
}
$report | ConvertTo-Json -Depth 10
