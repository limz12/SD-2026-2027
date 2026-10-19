# Funcoes de apoio: API HTTP, configuracao local e prontidao dos nos Kubo.
# Carregado por iniciar-comindir02.ps1; nao executar diretamente.

function Invoke-Kubo([string]$Endpoint, [int]$PortoApi = 5001) {
    $request = [Net.WebRequest]::Create("http://127.0.0.1:$PortoApi/api/v0/$Endpoint")
    $request.Proxy = $null
    $request.Method = 'POST'
    $request.ContentLength = 0
    $request.Timeout = 2000
    $response = $request.GetResponse()
    try {
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
    } finally { $response.Dispose() }
}

function Test-Kubo {
    try {
        $null = Invoke-Kubo 'version'
        return $true
    } catch { return $false }
}

function Initialize-LocalNode([string]$Repository, [int]$ApiPort, [int]$GatewayPort, [int]$SwarmPort) {
    $previousIpfsPath = $env:IPFS_PATH
    try {
        $env:IPFS_PATH = $Repository
        $configPath = Join-Path $Repository 'config'
        if (!(Test-Path -LiteralPath $configPath)) {
            & $KuboPath init
            if ($LASTEXITCODE -ne 0) { throw 'Falhou a inicializacao do no IPFS.' }
        }
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $config.Addresses.API = "/ip4/127.0.0.1/tcp/$ApiPort"
        $config.Addresses.Gateway = "/ip4/127.0.0.1/tcp/$GatewayPort"
        $config.Addresses.Swarm = @("/ip4/127.0.0.1/tcp/$SwarmPort")
        # Autorizar apenas ligacoes IPv4 de loopback (127.0.0.0/8).
        $config.Swarm.AddrFilters = @(
            '/ip4/0.0.0.0/ipcidr/2', '/ip4/64.0.0.0/ipcidr/3',
            '/ip4/96.0.0.0/ipcidr/4', '/ip4/112.0.0.0/ipcidr/5',
            '/ip4/120.0.0.0/ipcidr/6', '/ip4/124.0.0.0/ipcidr/7',
            '/ip4/126.0.0.0/ipcidr/8', '/ip4/128.0.0.0/ipcidr/1',
            '/ip6/::/ipcidr/0'
        )
        $config.Swarm.DisableNatPortMap = $true
        $config.Swarm.RelayClient | Add-Member -NotePropertyName Enabled -NotePropertyValue $false -Force
        $config.Swarm.RelayService | Add-Member -NotePropertyName Enabled -NotePropertyValue $false -Force
        $config.Bootstrap = @()
        $config.Peering.Peers = @()
        $config.Discovery.MDNS.Enabled = $false
        $config.Routing | Add-Member -NotePropertyName Type -NotePropertyValue 'none' -Force
        $config.Pubsub | Add-Member -NotePropertyName Enabled -NotePropertyValue $true -Force
        [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 100), (New-Object Text.UTF8Encoding($false)))
    } finally {
        if ($null -eq $previousIpfsPath) { Remove-Item Env:IPFS_PATH -ErrorAction SilentlyContinue }
        else { $env:IPFS_PATH = $previousIpfsPath }
    }
}

function Wait-LocalNode([int]$ApiPort) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ($true) {
        try { $version = (Invoke-Kubo 'version' $ApiPort).Version; break }
        catch {
            if ([DateTime]::UtcNow -gt $deadline) { throw "API $ApiPort nao arrancou; consulta a consola Kubo." }
            Start-Sleep -Milliseconds 300
        }
    }
    if ($version -ne '0.32.1') { throw "API $ApiPort usa Kubo $version; esperado 0.32.1." }
    $null = Invoke-Kubo 'pubsub/ls' $ApiPort
}

function Wait-TopicLink {
    $topic = 'u' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('rep02-p0')).TrimEnd('=').Replace('+','-').Replace('/','_')
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ($true) {
        $peers1 = @((Invoke-Kubo "pubsub/peers?arg=$topic" 5001).Strings)
        $peers2 = @((Invoke-Kubo "pubsub/peers?arg=$topic" 5002).Strings)
        if ($peers1 -contains $script:node2Id -and $peers2 -contains $script:node1Id) { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'O topico rep02-p0 ainda nao liga os dois nos. Confirma as subscricoes das replicas.' }
        Start-Sleep -Milliseconds 300
    }
    Write-Host 'Topico rep02-p0 confirmado entre os dois nos.'
}

