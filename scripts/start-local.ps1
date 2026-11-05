#requires -Version 7.0
param(
    [switch]$Build,
    [switch]$Restart,
    [ValidateSet('all','order-service')][string]$Service = 'all',
    [string]$RecoveryJar
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'runtime-support.ps1')
Push-Location $root
try {
    if (Test-Path '.env.local') {
        Get-Content '.env.local' | ForEach-Object {
            if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$' -and
                $null -eq [Environment]::GetEnvironmentVariable($matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
            }
        }
    }
    foreach ($key in 'SMARTDRIVE_DB_PASSWORD','JWT_SECRET','SMARTDRIVE_INTERNAL_TOKEN') {
        if (-not [Environment]::GetEnvironmentVariable($key, 'Process')) { throw "Required environment variable: $key" }
    }
    if ([Text.Encoding]::UTF8.GetByteCount($env:JWT_SECRET) -lt 32) { throw 'JWT_SECRET needs at least 32 UTF-8 bytes' }
    $nacos = if ($env:NACOS_ADDR) { $env:NACOS_ADDR } else { 'localhost:8848' }
    $redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { '127.0.0.1' }
    $redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
    $rabbitHost = if ($env:RABBITMQ_HOST) { $env:RABBITMQ_HOST } else { 'localhost' }
    $rabbitPort = if ($env:RABBITMQ_PORT) { [int]$env:RABBITMQ_PORT } else { 5672 }
    foreach ($endpoint in @(@('MySQL','localhost',3306),@('Redis',$redisHost,$redisPort),
                            @('RabbitMQ',$rabbitHost,$rabbitPort),@('Nacos',($nacos -split ':')[0],[int]($nacos -split ':')[1]))) {
        Assert-LocalDependency $endpoint[0] $endpoint[1] $endpoint[2]
    }
    if ($Build) {
        & mvn.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
    }
    $runtime = Join-Path $root 'logs/local-runtime'
    New-Item -ItemType Directory -Force $runtime | Out-Null
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    if ($RecoveryJar -and $Service -ne 'order-service') { throw '-RecoveryJar requires -Service order-service' }
    foreach ($entry in @(@('account-service',8081),@('driver-service',8082),@('order-service',8083),@('gateway',8080))) {
        $name = $entry[0]; $port = [int]$entry[1]
        if ($Service -ne 'all' -and $Service -ne $name) { continue }
        $jar = Join-Path $runtime "$name.jar"
        $source = Join-Path $root "$name/target/$name-1.0.0-SNAPSHOT.jar"
        if ($RecoveryJar) { $source = [IO.Path]::GetFullPath($RecoveryJar) }
        elseif (-not (Test-Path -LiteralPath $source)) { $source = $jar }
        if (-not $source.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Runtime source JAR is outside the workspace'
        }
        if (-not (Test-Path -LiteralPath $source)) { throw "Missing $name JAR and stable runtime copy; run with -Build" }
        $expectedHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        $restartService = $false
        if ($listener) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
            if ($owner.Name -ne 'java.exe' -or $owner.CommandLine -notlike "*$name*.jar*" -or
                $owner.CommandLine -notlike "*$root*") { throw "Unexpected process on $port; no process was stopped" }
            $jarArgument = [regex]::Match($owner.CommandLine, '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
            if (-not $jarArgument.Success) { throw "Cannot identify running JAR on $port" }
            $runningJar = if ($jarArgument.Groups[1].Success) { $jarArgument.Groups[1].Value } else { $jarArgument.Groups[2].Value }
            $runningJar = [IO.Path]::GetFullPath($runningJar, $root)
            if (-not $runningJar.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Running JAR on $port is outside the workspace"
            }
            $runningHash = (Get-FileHash -LiteralPath $runningJar -Algorithm SHA256).Hash
            $restartService = $Restart -or ($runningHash -ne $expectedHash)
            if ($restartService) {
                # Preserve a stable source before stopping a process that may run directly from target.
                if ($source -ne $jar -and $runningJar -ne $jar) { Copy-Item -LiteralPath $source -Destination $jar -Force }
                Stop-Process -Id $listener[0].OwningProcess
                Wait-Process -Id $listener[0].OwningProcess -Timeout 30 -ErrorAction SilentlyContinue
            } else { $jar = $runningJar }
        }
        if (-not $listener -or $restartService) {
            if ($source -ne $jar) { Copy-Item -LiteralPath $source -Destination $jar -Force }
            $tag = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
            $null = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", "--server.port=$port") -WorkingDirectory $root `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$name-$tag.out.log" `
                -RedirectStandardError "$runtime/$name-$tag.err.log"
        }
        $ready = $false
        for ($attempt=0; $attempt -lt 90; $attempt++) {
            try {
                $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { $ready=$true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "$name did not become healthy; inspect logs/local-runtime" }
        # Record the listener's PID, rather than a launcher PID returned by Start-Process.
        $activeListener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
        @{service=$name; pid=$activeListener[0].OwningProcess; port=$port; jarPath=$jar;
            jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;
            verifiedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content "$runtime/$name.json"
        Write-Output "$name UP ($port)"
    }
    foreach ($name in $(if ($Service -eq 'all') { 'account','driver','order' } else { 'order' })) {
        $ready = $false
        for ($attempt=0; $attempt -lt 30; $attempt++) {
            try {
                $health = Invoke-RestMethod "http://127.0.0.1:8080/actuator/$name/health" -TimeoutSec 3
                if ($health.status -eq 'UP') { $ready=$true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Gateway route not ready: $name" }
    }
    Write-Output 'Gateway routes UP; start the UI with: cd web-app; npm.cmd run dev'
} finally { Pop-Location }
