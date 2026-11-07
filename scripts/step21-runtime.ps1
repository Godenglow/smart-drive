param(
    [ValidateSet('all','order-service')][string]$Service = 'all',
    [ValidateRange(100,60000)][int]$PollIntervalMs = 5000,
    [switch]$Restore,
    [switch]$RefreshJar
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
if ($RefreshJar -and -not $Restore) { throw '-RefreshJar requires -Restore (outside measurement)' }
# Never print private environment values or put credentials in process arguments.
$localEnv = Join-Path $root '.env.local'
if (Test-Path -LiteralPath $localEnv) {
    Get-Content -LiteralPath $localEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$' -and
            $null -eq [Environment]::GetEnvironmentVariable($matches[1], 'Process')) {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
$java = (Get-Command java.exe -ErrorAction Stop).Source
$runtime = Join-Path $root 'logs/step21-runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$entries = @(@('account-service',8081),@('driver-service',8082),@('order-service',8083),@('gateway',8080))
foreach ($entry in $entries) {
    $name = $entry[0]; $port = [int]$entry[1]
    if ($Service -ne 'all' -and $Service -ne $name) { continue }
    $jar = Join-Path $runtime "$name.jar"
    $source = Join-Path $root "$name/target/$name-1.0.0-SNAPSHOT.jar"
    # Resolve build inputs before stopping a healthy service. No historical logs are required.
    if (($RefreshJar -or -not (Test-Path -LiteralPath $jar)) -and -not (Test-Path -LiteralPath $source)) {
        throw "Missing build JAR: $name; run mvn.cmd -DskipTests package first"
    }
    if (-not (Test-Path -LiteralPath $jar)) {
        Copy-Item -LiteralPath $source -Destination $jar
    }
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $ownerId = $listener[0].OwningProcess
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerId"
        if ($process.Name -ne 'java.exe' -or $process.CommandLine -notlike "*$root*" -or
            $process.CommandLine -notlike "*$name*.jar*") { throw "Unexpected process on port $port" }
        Stop-Process -Id $ownerId
        Start-Sleep -Seconds 2
    }
    if ($RefreshJar) {
        Copy-Item -LiteralPath (Join-Path $root "$name/target/$name-1.0.0-SNAPSHOT.jar") -Destination $jar -Force
    }
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing runtime jar: $name" }
    $metricPort = $port + 10000
    $arguments = @('-jar', "`"$jar`"")
    if (-not $Restore) {
        $arguments += @(
        '--management.server.address=127.0.0.1', "--management.server.port=$metricPort",
        '--management.endpoints.web.exposure.include=health,info,metrics')
    }
    if ($name -ne 'gateway' -and -not $Restore) {
        $arguments += '--spring.datasource.hikari.connection-init-sql="SET SESSION long_query_time=0.1"'
    }
    if ($name -eq 'order-service') {
        $arguments += "--smartdrive.outbox.poll-interval-ms=$PollIntervalMs"
        $arguments += '--smartdrive.outbox.batch-size=50'
    }
    $runTag = Get-Date -Format 'yyyyMMdd-HHmmss'
    $started = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$name-$runTag.out.log" `
        -RedirectStandardError "$runtime/$name-$runTag.err.log"
    $record = @{service=$name; pid=$started.Id; port=$port; metricsPort=$metricPort;
        jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower();
        startedAt=(Get-Date).ToUniversalTime().ToString('o'); performanceMode=(-not $Restore.IsPresent)}
    if ($Restore) { $record.metricsPort=$null; $metricPort=$port }
    else { $record.slowQueryThresholdSeconds=0.1 }
    if ($name -eq 'order-service') { $record.pollIntervalMs=$PollIntervalMs; $record.batchSize=50 }
    $record | ConvertTo-Json | Set-Content -Encoding utf8 "$runtime/$name.json"
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$metricPort/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { $ready=$true; break }
        } catch { }
    }
    if (-not $ready) { throw "$name failed to start; inspect private runtime logs" }
    Write-Output "$name UP; health=127.0.0.1:$metricPort"
}
