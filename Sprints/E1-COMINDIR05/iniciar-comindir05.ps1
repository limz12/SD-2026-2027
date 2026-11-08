[CmdletBinding()]
param(
    [string]$KuboPath,
    [string]$JavaHome,
    [ValidateSet(3)][int]$Particoes = 3,
    [ValidateSet('Tudo', 'Nos', 'No', 'Processador', 'Produtor', 'RaftCheck', 'CausalCheck',
        'TerminarNo', 'IsolarNo', 'RepararNo')]
    [string]$Acao = 'Tudo',
    [ValidateRange(0, 2)][int]$Particao = 0,
    [ValidateRange(1, 3)][int]$NoId = 1,
    [string]$PastaDados = 'dados-05',
    [ValidateRange(1, 60000)][long]$Periodo = 2000,
    [ValidateRange(1, 100)][int]$FatorTimeout = 3,
    [ValidateRange(1, 600000)][long]$EleicaoMin = 0,
    [ValidateRange(1, 600000)][long]$EleicaoMax = 0,
    [switch]$SemProcessador,
    [switch]$Silencioso,
    [switch]$Verificar
)

$ErrorActionPreference = 'Stop'
$sprintRoot = $PSScriptRoot
$dataRoot = if ([IO.Path]::IsPathRooted($PastaDados)) {
    [IO.Path]::GetFullPath($PastaDados)
} else {
    [IO.Path]::GetFullPath((Join-Path $sprintRoot $PastaDados))
}
$localRoot = Join-Path $env:LOCALAPPDATA 'SD-COMINDIR05'
$toolsRoot = Join-Path $localRoot 'tools'
$api = 'http://127.0.0.1:5001/api/v0'
$groupSize = 3

if ($EleicaoMin -eq 0) { $EleicaoMin = 3 * $Periodo }
if ($EleicaoMax -eq 0) { $EleicaoMax = 6 * $Periodo }
if ($EleicaoMax -lt $EleicaoMin) {
    Write-Error '-EleicaoMax tem de ser maior ou igual a -EleicaoMin.'
    exit 2
}

function Find-JavaTool([string]$Name) {
    foreach ($jdkRoot in @($JavaHome, $env:JAVA_HOME)) {
        if ($jdkRoot) {
            $candidate = Join-Path $jdkRoot "bin\$Name.exe"
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name.exe não encontrado. Usa -JavaHome com um JDK 11 ou superior."
}

function Open-Console([string]$Title, [string]$Executable, [string[]]$Arguments, [string]$IpfsRepo) {
    $quoted = @($Executable) + @($Arguments) | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $lines = @(
        "`$Host.UI.RawUI.WindowTitle = '" + $Title.Replace("'", "''") + "'"
        "Set-Location -LiteralPath '" + $dataRoot.Replace("'", "''") + "'"
    )
    if ($IpfsRepo) { $lines += "`$env:IPFS_PATH = '" + $IpfsRepo.Replace("'", "''") + "'" }
    $lines += '& ' + ($quoted -join ' ')
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(($lines -join "`r`n")))
    Start-Process powershell.exe -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) | Out-Null
}

function Test-Kubo {
    try {
        $request = [Net.HttpWebRequest]::Create("$api/version")
        $request.Method = 'POST'
        $request.Proxy = $null
        $request.Timeout = 1500
        $response = $request.GetResponse()
        try {
            $reader = [IO.StreamReader]::new($response.GetResponseStream())
            try { $version = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            return [bool]$version.Version
        } finally { $response.Dispose() }
    } catch [Net.WebException] {
        return $false
    }
}

function Get-KuboPubSubError {
    try {
        $request = [Net.HttpWebRequest]::Create("$api/pubsub/ls")
        $request.Method = 'POST'
        $request.Proxy = $null
        $request.Timeout = 3000
        $response = $request.GetResponse()
        $response.Dispose()
        return $null
    } catch [Net.WebException] {
        if (!$_.Exception.Response) { return $_.Exception.Message }
        $response = $_.Exception.Response
        try {
            $reader = [IO.StreamReader]::new($response.GetResponseStream())
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        } finally { $response.Dispose() }
    }
}

function Resolve-Kubo {
    if ($KuboPath) {
        if (!(Test-Path -LiteralPath $KuboPath -PathType Leaf)) { throw "Executável Kubo inexistente: $KuboPath" }
        return (Resolve-Path -LiteralPath $KuboPath).Path
    }
    $command = Get-Command ipfs.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($folder in @($toolsRoot, (Join-Path (Split-Path $sprintRoot) 'E1-COMINDIR02\.tools'))) {
        if (Test-Path -LiteralPath $folder) {
            $found = Get-ChildItem -LiteralPath $folder -Filter ipfs.exe -Recurse -File | Select-Object -First 1
            if ($found) { return $found.FullName }
        }
    }
    $downloadRoot = Join-Path $toolsRoot 'kubo-v0.32.1'
    New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
    $archive = Join-Path $downloadRoot 'kubo.zip'
    Write-Host 'A obter Kubo 0.32.1 para Windows (GitHub oficial)...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/ipfs/kubo/releases/download/v0.32.1/kubo_v0.32.1_windows-amd64.zip' -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $downloadRoot -Force
    $found = Get-ChildItem -LiteralPath $downloadRoot -Filter ipfs.exe -Recurse -File | Select-Object -First 1
    if (!$found) { throw 'O arquivo descarregado não contém ipfs.exe.' }
    return $found.FullName
}

function Ensure-Kubo {
    if (Test-Kubo) {
        $pubSubError = Get-KuboPubSubError
        if ($pubSubError) {
            throw "Já existe um Kubo ativo na API 5001, mas PubSub não está disponível ($pubSubError). Termina esse daemon antes de iniciar o Kubo do COMINDIR05."
        }
        Write-Host 'A reutilizar o Kubo ativo na API 5001.'
        return $null
    }

    $ipfs = Resolve-Kubo
    $repo = Join-Path $toolsRoot 'ipfs-repo-comindir05'
    New-Item -ItemType Directory -Path (Split-Path $repo) -Force | Out-Null
    $previousRepo = $env:IPFS_PATH
    try {
        $env:IPFS_PATH = $repo
        if (!(Test-Path -LiteralPath (Join-Path $repo 'config'))) {
            & $ipfs init
            if ($LASTEXITCODE -ne 0) { throw 'Falha ao inicializar o repositório Kubo.' }
        }
        $settings = @(
            @('Pubsub.Enabled', 'true'),
            @('Bootstrap', '[]'),
            @('Addresses.Swarm', '[]'),
            @('Discovery.MDNS.Enabled', 'false'),
            @('Peering.Peers', '[]')
        )
        foreach ($setting in $settings) {
            & $ipfs config --json $setting[0] $setting[1]
            if ($LASTEXITCODE -ne 0) { throw "Falha na configuração Kubo: $($setting[0])" }
        }
        & $ipfs config Routing.Type none
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao configurar Routing.Type.' }
    } finally {
        $env:IPFS_PATH = $previousRepo
    }

    Open-Console 'COMINDIR05 - Kubo' $ipfs @('daemon', '--enable-pubsub-experiment') $repo
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (!(Test-Kubo)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Kubo não ficou pronto. Consulta a consola Kubo.' }
        Start-Sleep -Milliseconds 500
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $pubSubError = Get-KuboPubSubError
        if (!$pubSubError) { break }
        if ([DateTime]::UtcNow -gt $deadline) {
            throw "Kubo iniciou, mas PubSub não ficou disponível: $pubSubError"
        }
        Start-Sleep -Milliseconds 500
    } while ($true)
    return $repo
}

function Get-NodeProcess {
    $pattern = 'raft04\.PartitionNode\s+' + $Particao + '\s+' + $NoId + '\s+' + $Particoes + '(?:\s|$)'
    $processes = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match $pattern
    })
    if ($processes.Count -ne 1) {
        throw "Esperava encontrar exatamente um PartitionNode p$Particao/n$NoId; encontrados: $($processes.Count)."
    }
    return $processes[0]
}

function Stop-Node {
    $process = Get-NodeProcess
    if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
        throw "Pasta de dados inexistente: $dataRoot"
    }
    Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $failureFile = Join-Path $dataRoot 'falhas.txt'
    [IO.File]::AppendAllText($failureFile, "p$Particao;n$NoId;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Nó p$Particao/n$NoId terminado (PID $($process.ProcessId)); falha anotada em $failureFile."
}

function Set-NodeIsolation([bool]$Isolate) {
    if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
        throw "Pasta de dados inexistente: $dataRoot"
    }
    $flag = Join-Path $dataRoot "isolar-p$Particao-n$NoId"
    $log = Join-Path $dataRoot 'particoes.txt'
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($Isolate) {
        if (Test-Path -LiteralPath $flag) { throw "O nó p$Particao/n$NoId já está isolado." }
        [IO.File]::WriteAllText($flag, '', [Text.UTF8Encoding]::new($false))
        [IO.File]::AppendAllText($log, "INICIO;p$Particao;n$NoId;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
        Write-Host "Nó p$Particao/n$NoId isolado às $timestamp."
    } else {
        if (!(Test-Path -LiteralPath $flag)) { throw "O nó p$Particao/n$NoId não está isolado." }
        Remove-Item -LiteralPath $flag -Force
        [IO.File]::AppendAllText($log, "FIM;p$Particao;n$NoId;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
        Write-Host "Isolamento de p$Particao/n$NoId reparado às $timestamp."
    }
}

function Test-NodePorts([int[]]$Ports) {
    $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $Ports })
    if ($occupied.Count) {
        throw "Portos TCP de recuperação já ocupados: $(($occupied | Select-Object -ExpandProperty LocalPort -Unique) -join ', ')."
    }
}

try {
    if (!$Verificar) {
        if ($Acao -eq 'TerminarNo') {
            Stop-Node
            exit 0
        }
        if ($Acao -eq 'IsolarNo') {
            Set-NodeIsolation $true
            exit 0
        }
        if ($Acao -eq 'RepararNo') {
            Set-NodeIsolation $false
            exit 0
        }
    }

    $java = Find-JavaTool 'java'
    $projects = if ($Verificar -or $Acao -eq 'Tudo') {
        @('raft04-no', 'raft04-processador', 'raft04-produtor')
    } elseif ($Acao -in @('No', 'Nos', 'RaftCheck', 'CausalCheck')) {
        @('raft04-no')
    } elseif ($Acao -eq 'Processador') {
        @('raft04-processador')
    } else {
        @('raft04-produtor')
    }
    $javac = Find-JavaTool 'javac'
    $buildRoot = Join-Path $localRoot ("build\" + [guid]::NewGuid().ToString('N'))
    $builds = @{}
    foreach ($project in $projects) {
        $source = Join-Path $sprintRoot "$project\src\raft04"
        $sources = @(Get-ChildItem -LiteralPath $source -Filter '*.java' -File | ForEach-Object FullName)
        if (!$sources.Count) { throw "Sem ficheiros Java em $source" }
        $output = Join-Path $buildRoot $project
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        & $javac --release 11 -encoding UTF-8 -d $output @sources
        if ($LASTEXITCODE -ne 0) { throw "Falha a compilar $project." }
        $builds[$project] = $output
        Write-Host "Compilado: $project" -ForegroundColor Green
    }

    if ($Verificar) {
        Write-Host 'Verificação concluída. Não foram abertos processos nem iniciado o Kubo.' -ForegroundColor Green
        exit 0
    }

    if ($Acao -in @('RaftCheck', 'CausalCheck')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) { throw "Pasta de dados inexistente: $dataRoot" }
        $nodeBuild = $builds['raft04-no']
        if ($Acao -eq 'RaftCheck') {
            $checkerArgs = @('-cp', $nodeBuild, 'raft04.RaftCheck', "$Particoes", $dataRoot)
            $failureFile = Join-Path $dataRoot 'falhas.txt'
            if (Test-Path -LiteralPath $failureFile) { $checkerArgs += $failureFile }
        } else {
            $checkerArgs = @('-cp', $nodeBuild, 'raft04.CausalCheck', "$Particoes", $dataRoot)
        }
        & $java @checkerArgs
        if ($LASTEXITCODE -ne 0) { throw "Falha ao executar $Acao." }
        exit 0
    }

    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    $nodeBuild = $builds['raft04-no']
    $processorBuild = $builds['raft04-processador']
    $producerBuild = $builds['raft04-produtor']
    $commonProperties = @(
        "-Draft04.periodo=$Periodo",
        "-Draft04.fator=$FatorTimeout",
        "-Draft04.eleicaoMin=$EleicaoMin",
        "-Draft04.eleicaoMax=$EleicaoMax"
    )

    if ($Acao -in @('No', 'Nos', 'Tudo')) {
        $ports = if ($Acao -eq 'No') {
            @(7100 + $Particao * $groupSize + $NoId - 1)
        } else {
            @(0..($Particoes - 1) | ForEach-Object { $partition = $_; 1..$groupSize | ForEach-Object { 7100 + $partition * $groupSize + $_ - 1 } })
        }
        Test-NodePorts $ports
    }

    $ipfsRepo = $null
    if ($Acao -in @('No', 'Nos', 'Processador', 'Produtor', 'Tudo')) {
        $ipfsRepo = Ensure-Kubo
    }

    if ($Acao -eq 'No') {
        $nodeArgs = @($commonProperties)
        if ($Silencioso) { $nodeArgs += '-Dsilencioso=true' }
        $nodeArgs += @('-cp', $nodeBuild, 'raft04.PartitionNode', "$Particao", "$NoId", "$Particoes", $api)
        Open-Console "COMINDIR05 - Nó p$Particao/n$NoId" $java $nodeArgs $ipfsRepo
        exit 0
    }

    if ($Acao -in @('Nos', 'Tudo')) {
        foreach ($partition in 0..($Particoes - 1)) {
            foreach ($nodeId in 1..$groupSize) {
                $nodeArgs = @($commonProperties)
                if ($Silencioso) { $nodeArgs += '-Dsilencioso=true' }
                $nodeArgs += @('-cp', $nodeBuild, 'raft04.PartitionNode', "$partition", "$nodeId", "$Particoes", $api)
                Open-Console "COMINDIR05 - Nó p$partition/n$nodeId" $java $nodeArgs $ipfsRepo
            }
        }
        if ($Acao -eq 'Nos') {
            Write-Host "Iniciados os nove nós. Dados em $dataRoot."
            exit 0
        }
        Read-Host 'Confirma que os nove nós arrancaram e subscreveram. Enter para abrir o processador' | Out-Null
    }

    if ($Acao -eq 'Processador') {
        $processorArgs = @($commonProperties)
        if ($Silencioso) { $processorArgs += '-Dsilencioso=true' }
        $processorArgs += @('-cp', $processorBuild, 'raft04.AlarmProcessor', "$Particoes", $api)
        Open-Console 'COMINDIR05 - Processador' $java $processorArgs $ipfsRepo
        exit 0
    }

    if ($Acao -eq 'Produtor') {
        $producerArgs = @($commonProperties)
        if ($Silencioso) { $producerArgs += '-Dsilencioso=true' }
        $producerArgs += @('-cp', $producerBuild, 'raft04.Producer', "$Particoes", $api)
        Open-Console 'COMINDIR05 - Produtor' $java $producerArgs $ipfsRepo
        exit 0
    }

    if ($Acao -eq 'Tudo') {
        if (!$SemProcessador) {
            $processorArgs = @($commonProperties)
            if ($Silencioso) { $processorArgs += '-Dsilencioso=true' }
            $processorArgs += @('-cp', $processorBuild, 'raft04.AlarmProcessor', "$Particoes", $api)
            Open-Console 'COMINDIR05 - Processador' $java $processorArgs $ipfsRepo
            Read-Host 'Confirma que o processador está pronto. Enter para abrir o produtor' | Out-Null
        }
        $producerArgs = @($commonProperties)
        if ($Silencioso) { $producerArgs += '-Dsilencioso=true' }
        $producerArgs += @('-cp', $producerBuild, 'raft04.Producer', "$Particoes", $api)
        Open-Console 'COMINDIR05 - Produtor' $java $producerArgs $ipfsRepo
        Write-Host "Arranque COMINDIR05 concluído. Dados em $dataRoot."
        exit 0
    }

    throw "Ação não suportada: $Acao"
} catch {
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
