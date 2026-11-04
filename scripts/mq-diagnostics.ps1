#requires -Version 7.0
param([ValidateRange(1,100)][int]$Count = 20,
      [string]$ReplayEventId = '', [string]$Vhost = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (Test-Path (Join-Path $projectRoot '.env.local')) {
    Get-Content (Join-Path $projectRoot '.env.local') | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
$url = if ($env:RABBITMQ_MANAGEMENT_URL) { $env:RABBITMQ_MANAGEMENT_URL.TrimEnd('/') } else { 'http://localhost:15672' }
$user = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'guest' }
$password = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'guest' }
if (-not $Vhost) { $Vhost = if ($env:RABBITMQ_VHOST) { $env:RABBITMQ_VHOST } else { '/' } }
$encodedVhost = [Uri]::EscapeDataString($Vhost)
$headers = @{Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${user}:$password"))}
function Mq($method, $path, $body = $null) {
    $options = @{Method=$method;Uri="$url$path";Headers=$headers;TimeoutSec=15}
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 20 -Compress); $options.ContentType='application/json' }
    Invoke-RestMethod @options
}
if ($ReplayEventId -and $ReplayEventId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
    throw 'ReplayEventId must be an existing canonical event UUID'
}
$queues = @('driver.matching.q','driver.matching.dlq') | ForEach-Object {
    $q = Mq Get "/api/queues/$encodedVhost/$_"
    [pscustomobject]@{queue=$q.name;ready=$q.messages_ready;unacknowledged=$q.messages_unacknowledged;consumers=$q.consumers}
}
# Peek and immediately requeue. Never acknowledge/delete the source DLQ messages.
# This can change delivery order and redelivered flags; use during diagnosis, not a polling loop.
$messages = Mq Post "/api/queues/$encodedVhost/driver.matching.dlq/get" @{
    count=$Count;ackmode='ack_requeue_true';encoding='base64';truncate=1048576
}
$entries = @()
$replayed = $false
foreach ($message in $messages) {
    $bytes = [Convert]::FromBase64String($message.payload)
    $event = $null
    try { $event = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json } catch { }
    $entries += [pscustomobject]@{
        messageId=$message.properties.message_id;eventId=$event.eventId;orderId=$event.orderId
        occurredAt=$event.occurredAt;death=$message.properties.headers.'x-death'
        sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    }
    if ($ReplayEventId -and -not $replayed -and $event.eventId -ceq $ReplayEventId) {
        if ($event.type -ne 'order.created' -or $event.orderId -le 0 -or
            $message.properties.message_id -cne $ReplayEventId -or $bytes.Length -ne $message.payload_bytes) {
            throw 'Replay requires a complete original order.created event with matching message_id'
        }
        Write-Host "Replaying eventId=$ReplayEventId; the original remains in the DLQ."
        $result = Mq Post "/api/exchanges/$encodedVhost/ride.events/publish" @{
            properties=@{content_type='application/json';delivery_mode=2;message_id=$ReplayEventId}
            routing_key='order.created';payload=$message.payload;payload_encoding='base64'
        }
        if (-not $result.routed) { throw 'Replay was not routed; original DLQ message retained' }
        $replayed = $true
    }
}
if ($ReplayEventId -and -not $replayed) { throw "Event not found in the first $Count DLQ messages; no replay performed" }
[pscustomobject]@{
    time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');vhost=$Vhost;queues=@($queues)
    deadLetters=$entries;replayRouted=$replayed;originalsRetained=$true
} | ConvertTo-Json -Depth 20
