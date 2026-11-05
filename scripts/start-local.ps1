#requires -Version 7.0
param([switch]$Build)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
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
    $redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { '192.168.220.128' }
    $redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
    $rabbitHost = if ($env:RABBITMQ_HOST) { $env:RABBITMQ_HOST } else { 'localhost' }
    $rabbitPort = if ($env:RABBITMQ_PORT) { [int]$env:RABBITMQ_PORT } else { 5672 }
    foreach ($endpoint in @(@('MySQL','localhost',3306),@('Redis',$redisHost,$redisPort),
                            @('RabbitMQ',$rabbitHost,$rabbitPort),@('Nacos',($nacos -split ':')[0],[int]($nacos -split ':')[1]))) {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync([string]$endpoint[1], [int]$endpoint[2])
            if (-not $connect.Wait(3000) -or -not $client.Connected) { throw 'Connection timeout' }
        } catch { throw "Dependency unavailable: $($endpoint[0]) at $($endpoint[1]):$($endpoint[2])" }
        finally { $client.Dispose() }
    }
    if ($Build) {
        & mvn.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
    }
    $runtime = Join-Path $root 'logs/local-runtime'
    New-Item -ItemType Directory -Force $runtime | Out-Null
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    foreach ($entry in @(@('account-service',8081),@('driver-service',8082),@('order-service',8083),@('gateway',8080))) {
        $name = $entry[0]; $port = [int]$entry[1]
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
            if ($owner.Name -ne 'java.exe' -or $owner.CommandLine -notlike "*$name*.jar*" -or
                $owner.CommandLine -notlike "*$root*") { throw "Unexpected process on $port; no process was stopped" }
        } else {
            $source = Join-Path $root "$name/target/$name-1.0.0-SNAPSHOT.jar"
            if (-not (Test-Path -LiteralPath $source)) { throw "Missing $name JAR; run with -Build" }
            $jar = Join-Path $runtime "$name.jar"
            Copy-Item -LiteralPath $source -Destination $jar -Force
            $tag = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
            $process = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $root `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/$name-$tag.out.log" `
                -RedirectStandardError "$runtime/$name-$tag.err.log"
            @{service=$name; pid=$process.Id; port=$port; jarSha256=(Get-FileHash $jar -Algorithm SHA256).Hash;
                startedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content "$runtime/$name.json"
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
        Write-Output "$name UP ($port)"
    }
    foreach ($name in 'account','driver','order') {
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
