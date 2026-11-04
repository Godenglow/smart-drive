#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (Test-Path (Join-Path $root '.env.local')) {
    Get-Content (Join-Path $root '.env.local') | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
$url = if ($env:RABBITMQ_MANAGEMENT_URL) { $env:RABBITMQ_MANAGEMENT_URL.TrimEnd('/') } else { 'http://localhost:15672' }
$user = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { 'guest' }
$password = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { 'guest' }
$headers = @{Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${user}:$password"))}
$vhost = 'smartdrive-day14-diagnostics-' + [Guid]::NewGuid().ToString()
$eventId = [Guid]::NewGuid().ToString()
function Mq($method,$path,$body=$null) {
    $options=@{Method=$method;Uri="$url$path";Headers=$headers;TimeoutSec=15}
    if ($null -ne $body) { $options.Body=($body | ConvertTo-Json -Depth 20 -Compress);$options.ContentType='application/json' }
    Invoke-RestMethod @options
}
function Check($ok,$message) { if (-not $ok) { throw $message } }
try {
    $null=Mq Put "/api/vhosts/$vhost" @{}
    $null=Mq Put "/api/permissions/$vhost/$([Uri]::EscapeDataString($user))" @{configure='.*';write='.*';read='.*'}
    $null=Mq Put "/api/exchanges/$vhost/ride.events" @{type='topic';durable=$true}
    $null=Mq Put "/api/exchanges/$vhost/ride.events.dlx" @{type='direct';durable=$true}
    $null=Mq Put "/api/queues/$vhost/driver.matching.q" @{durable=$true;arguments=@{'x-dead-letter-exchange'='ride.events.dlx';'x-dead-letter-routing-key'='driver.matching.dlq'}}
    $null=Mq Put "/api/queues/$vhost/driver.matching.dlq" @{durable=$true;arguments=@{}}
    $null=Mq Post "/api/bindings/$vhost/e/ride.events/q/driver.matching.q" @{routing_key='order.created';arguments=@{}}
    $null=Mq Post "/api/bindings/$vhost/e/ride.events.dlx/q/driver.matching.dlq" @{routing_key='driver.matching.dlq';arguments=@{}}
    $payload=@{eventId=$eventId;type='order.created';orderId=987654321;pickupLng=20;pickupLat=20;occurredAt=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json -Compress
    foreach ($item in @(@{id='invalid';body='broken-json'},@{id=$eventId;body=$payload})) {
        $sent=Mq Post "/api/exchanges/$vhost/ride.events/publish" @{properties=@{content_type='application/json';delivery_mode=2;message_id=$item.id};routing_key='order.created';payload=$item.body;payload_encoding='string'}
        Check $sent.routed 'Fixture publish failed'
        $rejected=Mq Post "/api/queues/$vhost/driver.matching.q/get" @{count=1;ackmode='reject_requeue_false';encoding='base64'}
        Check ($rejected.Count -eq 1) 'Fixture reject failed'
    }
    $before=(& (Join-Path $PSScriptRoot 'mq-diagnostics.ps1') -Vhost $vhost) | ConvertFrom-Json
    Check ($before.deadLetters.Count -eq 2) 'Peek must locate both messages'
    $valid=@($before.deadLetters | Where-Object eventId -eq $eventId)
    Check ($valid.Count -eq 1 -and $valid[0].death[0].reason -eq 'rejected') 'Missing original identity or x-death'
    $replay=(& (Join-Path $PSScriptRoot 'mq-diagnostics.ps1') -Vhost $vhost -ReplayEventId $eventId) | ConvertFrom-Json
    Check ($replay.replayRouted -and $replay.originalsRetained) 'Replay failed'
    $source=Mq Post "/api/queues/$vhost/driver.matching.dlq/get" @{count=10;ackmode='ack_requeue_true';encoding='base64'}
    $target=Mq Post "/api/queues/$vhost/driver.matching.q/get" @{count=10;ackmode='ack_requeue_true';encoding='base64'}
    Check ($source.Count -eq 2 -and $target.Count -eq 1) 'Source retention or target count incorrect'
    Check ($target[0].properties.message_id -eq $eventId) 'Replay changed message identity'
    Check ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($target[0].payload)) -ceq $payload) 'Replay changed original body'
    [pscustomobject]@{time=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');eventId=$eventId;deadLettersLocated=2;originalsRetained=2;replaysRouted=1;identityAndBodyPreserved=$true;xDeathLocated=$true} |
        ConvertTo-Json | Set-Content (Join-Path $root 'logs/day14-diagnostics-result.json')
    Get-Content (Join-Path $root 'logs/day14-diagnostics-result.json')
} finally {
    Check ($vhost.StartsWith('smartdrive-day14-diagnostics-')) 'Unsafe cleanup target'
    $null=Mq Delete "/api/vhosts/$vhost"
}
