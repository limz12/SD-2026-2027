param(
    [string]$KuboPath,
    [string]$JavaHome,
    [string]$KuboRepo,
    [switch]$DoisNos,
    [double]$PerdaReplica3 = 0,
    [switch]$Verificar
)
$ErrorActionPreference = 'Stop'

function Find-JavaTool([string]$Name) {
    if ($JavaHome) {
        $candidate = Join-Path $JavaHome "bin\$Name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
        throw "Nao encontrado: $candidate"
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Java nao encontrado. Indica -JavaHome com a pasta do JDK (Java 11 ou superior)."
}

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

# EncodedCommand evita problemas com espacos e acentos nos caminhos.
function Open-Console([string]$Title, [string]$Directory, [string]$Executable, [string[]]$Arguments, [string]$IpfsRepository) {
    $quote = { param($value) "'" + $value.Replace("'", "''") + "'" }
    $argumentText = ($Arguments | ForEach-Object { & $quote $_ }) -join ' '
    $commandText = '$Host.UI.RawUI.WindowTitle = ' + (& $quote $Title) + "`n" +
        'Set-Location -LiteralPath ' + (& $quote $Directory) + "`n" +
        '& ' + (& $quote $Executable) + ' ' + $argumentText
    if ($IpfsRepository) { $commandText = '$env:IPFS_PATH = ' + (& $quote $IpfsRepository) + "`n" + $commandText }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($commandText))
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) | Out-Null
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
try {
    $java = Find-JavaTool 'java'
    $javac = Find-JavaTool 'javac'
    & $java -version
    $build = Join-Path $env:LOCALAPPDATA 'SD-COMINDIR02\build'
    $runtime = Join-Path $PSScriptRoot 'dados'
    foreach ($project in @('rep02-produtor', 'rep02-lider', 'rep02-replica')) {
        $source = Join-Path $PSScriptRoot "$project\src\rep02"
        if (!(Test-Path -LiteralPath $source)) { throw "Pasta de codigo inexistente: $source" }
        $output = Join-Path $build $project
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        $sources = @(Get-ChildItem -LiteralPath $source -Filter '*.java' | ForEach-Object FullName)
        if (!$sources.Count) { throw "Sem ficheiros Java em $source" }
        & $javac -encoding UTF-8 -d $output @sources
        if ($LASTEXITCODE -ne 0) { throw "A compilacao de $project falhou." }
        Write-Host "Compilado: $project"
    }
    # Ferramenta e configuracao locais ao sprint, independentes de AppData.
    if (!$KuboPath) {
        $toolRoot = Join-Path $PSScriptRoot '.tools\kubo-v0.32.1'
        $KuboPath = Join-Path $toolRoot 'kubo\ipfs.exe'
        if (!(Test-Path -LiteralPath $KuboPath -PathType Leaf)) {
            New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
            $archive = Join-Path $toolRoot 'kubo.zip'
            Write-Host 'A preparar Kubo 0.32.1 a partir da distribuicao oficial...'
            & curl.exe -fL --connect-timeout 15 --max-time 180 -o $archive 'https://github.com/ipfs/kubo/releases/download/v0.32.1/kubo_v0.32.1_windows-amd64.zip'
            if ($LASTEXITCODE -ne 0) { throw 'Download do Kubo falhou. Volta a executar quando houver ligacao.' }
            Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
        }
        if (!$KuboRepo) { $KuboRepo = Join-Path $PSScriptRoot '.tools\ipfs-repo-parteA-v0.32.1' }
    }
    Write-Host "Executavel Kubo: $KuboPath"
    if (!$KuboPath -or !(Test-Path -LiteralPath $KuboPath -PathType Leaf)) {
        throw "Executavel Kubo inexistente: [$KuboPath]"
    }
    $online = Test-Kubo
    if ($Verificar) {
        Write-Host "API Kubo ativa: $online"
        if (!$online -and (!$KuboPath -or !(Test-Path -LiteralPath $KuboPath))) {
            throw 'Codigo compilado, mas falta localizar o Kubo. Usa -KuboPath com o caminho de ipfs.exe.'
        }
        Write-Host 'Verificacao terminada; nenhum processo foi iniciado.'
        return
    }
    if ($DoisNos) {
        foreach ($apiPort in @(5001,5002)) {
            $busy = $false
            try { $null = Invoke-Kubo 'version' $apiPort; $busy = $true } catch {}
            if ($busy) { throw "Ja existe um Kubo na API $apiPort. Fecha-o antes de iniciar o cenario de dois nos." }
        }
        $repo1 = Join-Path $PSScriptRoot '.tools\ipfs-6f-no1'
        $repo2 = Join-Path $PSScriptRoot '.tools\ipfs-6f-no2'
        Initialize-LocalNode $repo1 5001 8080 4001
        Initialize-LocalNode $repo2 5002 8081 4002
        Open-Console 'COMINDIR02 - Kubo 1 - API 5001' $PSScriptRoot $KuboPath @('daemon','--enable-pubsub-experiment') $repo1
        Open-Console 'COMINDIR02 - Kubo 2 - API 5002' $PSScriptRoot $KuboPath @('daemon','--enable-pubsub-experiment') $repo2
        Wait-LocalNode 5001
        Wait-LocalNode 5002
        $script:node1Id = (Invoke-Kubo 'id' 5001).ID
        $script:node2Id = (Invoke-Kubo 'id' 5002).ID
        $address = [Uri]::EscapeDataString("/ip4/127.0.0.1/tcp/4001/p2p/$script:node1Id")
        $null = Invoke-Kubo "swarm/connect?arg=$address" 5002
        foreach ($apiPort in @(5001,5002)) {
            $expected = if ($apiPort -eq 5001) { $script:node2Id } else { $script:node1Id }
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            while ($true) {
                $peerList = @((Invoke-Kubo 'swarm/peers' $apiPort).Peers | Where-Object { $null -ne $_ } | Group-Object Peer | ForEach-Object { [pscustomobject]@{Peer=$_.Name} })
                if ($peerList.Count -eq 1 -and $peerList[0].Peer -eq $expected) { break }
                if ($peerList.Count -gt 1 -or [DateTime]::UtcNow -gt $deadline) { throw "Ligacao ao par esperado nao confirmada na API $apiPort." }
                Start-Sleep -Milliseconds 300
            }
        }
        Write-Host 'Dois nos ligados apenas por 127.0.0.1; sem bootstrap nem descoberta externa.'
    } else {
    if ($online) {
        if ($KuboRepo -and $KuboRepo.EndsWith('ipfs-repo-parteA-v0.32.1')) {
            throw 'Ja existe um Kubo na API 5001. Fecha-o com Ctrl+C para iniciar o no isolado da Parte A.'
        }
        $version = (Invoke-Kubo 'version').Version
        if ($version -ne '0.32.1') {
            throw "A API 5001 esta ocupada pelo Kubo $version. Fecha a consola desse Kubo com Ctrl+C e volta a executar este script. A configuracao antiga sera preservada."
        }
    }
    if (!$online) {
        if (!$KuboPath -or !(Test-Path -LiteralPath $KuboPath)) {
            throw 'Kubo parado e ipfs.exe nao encontrado. Usa -KuboPath com o caminho de ipfs.exe.'
        }
        $daemonArgs = @('daemon')
        if ($KuboRepo) {
            $previousIpfsPath = $env:IPFS_PATH
            try {
                $env:IPFS_PATH = $KuboRepo
                $configPath = Join-Path $KuboRepo 'config'
                if (!(Test-Path -LiteralPath $configPath)) {
                    & $KuboPath init
                    if ($LASTEXITCODE -ne 0) { throw 'Falhou a inicializacao do repositorio IPFS separado.' }
                }
                # Parte A: um unico no, sem descoberta nem ligacoes externas.
                # O cenario de dois nos sera preparado separadamente no ponto 6(f).
                & $KuboPath config --json Bootstrap '[]'
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a configuracao Bootstrap.' }
                & $KuboPath config --json Addresses.Swarm '[]'
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a configuracao Swarm.' }
                & $KuboPath config --json Discovery.MDNS.Enabled false
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a configuracao de descoberta local.' }
                & $KuboPath config --json Peering.Peers '[]'
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a configuracao de peering.' }
                & $KuboPath config Routing.Type none
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a configuracao de routing.' }
                & $KuboPath config --json Pubsub.Enabled true
                if ($LASTEXITCODE -ne 0) { throw 'Falhou a ativacao do PubSub.' }
            } finally {
                if ($null -eq $previousIpfsPath) { Remove-Item Env:IPFS_PATH -ErrorAction SilentlyContinue }
                else { $env:IPFS_PATH = $previousIpfsPath }
            }
            $daemonArgs = @('daemon', '--enable-pubsub-experiment')
            Write-Host "Configuracao IPFS deste sprint: $KuboRepo"
        }
        Open-Console 'COMINDIR02 - Kubo' $PSScriptRoot $KuboPath $daemonArgs $KuboRepo
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        while (!(Test-Kubo)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Kubo nao arrancou em 30 segundos. Consulta a consola Kubo.' }
            Start-Sleep -Milliseconds 300
        }
    }
    }
    $activeVersion = (Invoke-Kubo 'version').Version
    if ($activeVersion -ne '0.32.1') {
        throw "Kubo ativo: $activeVersion; esperado: 0.32.1. Nao foram iniciados processos Java."
    }
    Write-Host "Kubo confirmado pela API: $activeVersion"
    # Esta chamada falha se a API estiver ativa mas o PubSub estiver desativado.
    $null = Invoke-Kubo 'pubsub/ls'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    for ($id = 1; $id -le 3; $id++) {
        $replicaApi = if ($DoisNos -and $id -ge 2) { '5002' } else { '5001' }
        $replicaArgs = @('-cp', (Join-Path $build 'rep02-replica'))
        if ($id -eq 3 -and $PerdaReplica3 -gt 0) {
            $replicaArgs += '-Dperda=' + $PerdaReplica3.ToString([Globalization.CultureInfo]::InvariantCulture)
        }
        $replicaArgs += @('rep02.PartitionReplica', "$id", '0', $replicaApi)
        Open-Console "COMINDIR02 - Replica $id - p0 - API $replicaApi" $runtime $java $replicaArgs
    }
    Write-Host 'Confirma nas tres consolas que nao existem erros de subscricao.'
    $null = Read-Host 'Prime Enter para iniciar o lider depois de as replicas estarem prontas'
    if ($DoisNos) { Wait-TopicLink }
    Open-Console 'COMINDIR02 - Lider p0' $runtime $java @('-cp', (Join-Path $build 'rep02-lider'), 'rep02.PartitionLeader', '0', '1', '127.0.0.1', 'http://127.0.0.1:5001/api/v0')
    Write-Host 'Confirma que o lider esta a escuta no porto 7000 e nao apresenta erros.'
    $null = Read-Host 'Prime Enter para iniciar o produtor depois de o lider estar pronto'
    Open-Console 'COMINDIR02 - Produtor' $runtime $java @('-cp', (Join-Path $build 'rep02-produtor'), 'rep02.Producer', 'http://127.0.0.1:5001/api/v0')
    Write-Host "Dados preservados em: $runtime"
    Write-Host 'Escreve as leituras na consola PRODUTOR (exemplo: S01 23.5).'
    Write-Host 'Ficheiros: lider-p0.txt e replica-1-p0.txt, replica-2-p0.txt, replica-3-p0.txt.'
    Write-Host 'O arranque preserva os dados anteriores; nao inicies outra instancia em paralelo.'
    Write-Host 'Para terminar: sair no produtor; Ctrl+C no lider, nas replicas e no Kubo iniciado por este script.'
} catch {
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

















