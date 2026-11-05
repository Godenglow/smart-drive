function Assert-LocalDependency([string]$Name, [string]$Hostname, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync($Hostname, $Port)
        if (-not $connect.Wait(3000) -or -not $client.Connected) { throw 'Connection timeout' }
    } catch { throw "Dependency unavailable: $Name at ${Hostname}:$Port" }
    finally { $client.Dispose() }
}
