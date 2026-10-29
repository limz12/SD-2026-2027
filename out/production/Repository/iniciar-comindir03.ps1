# Arranque local dos quatro projetos COMINDIR03.
# Compilacao e preparacao dos ensaios; conserva os dados.
[CmdletBinding()]
param(
    [string]$KuboPath,
    [string]$JavaHome,
    [ValidateSet(3)][int]$Particoes = 3,
    [ValidateSet('Tudo','Lider','Processador','Produtor','CausalCheck','PartitionCheck')]
    [string]$Acao = 'Tudo',
    [ValidateRange(0,2)][int]$Lider = 2,
    [string]$PastaDados = 'dados-n3',
    [switch]$SemProcessador,
    [ValidateRange(0,1)][double]$PerdaReplica3 = 0,
    [switch]$Verificar
)

$ErrorActionPreference = 'Stop'
$sprintRoot = $PSScriptRoot
$dataRoot = if ([IO.Path]::IsPathRooted($PastaDados)) {
    [IO.Path]::GetFullPath($PastaDados)
} else {
    [IO.Path]::GetFullPath((Join-Path $sprintRoot $PastaDados))
}
$buildRoot = Join-Path $env:LOCALAPPDATA ("SD-COMINDIR03\build\" + [guid]::NewGuid().ToString('N'))
$toolsRoot = Join-Path $sprintRoot '.tools'
$api = 'http://127.0.0.1:5001/api/v0'

# 1. Ferramentas e compilacao. Os binarios ficam fora do repositorio.
function Find-JavaTool([string]$Name) {
    foreach ($jdkRoot in @($JavaHome, $env:JAVA_HOME)) {
        if ($jdkRoot) {
            $candidate = Join-Path $jdkRoot "bin\$Name.exe"
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name.exe nao encontrado. Usa -JavaHome com a pasta de um JDK."
}

function Open-Console([string]$Title, [string]$Executable, [string[]]$Arguments, [string]$IpfsRepo) {
    # Aspas simples duplicadas preservam caminhos, virgulas e argumentos -D.
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
    } catch { return $false }
}

function Resolve-Kubo {
    if ($KuboPath) {
        if (!(Test-Path -LiteralPath $KuboPath -PathType Leaf)) { throw "Executavel Kubo inexistente: $KuboPath" }
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
    if (!$found) { throw 'O arquivo descarregado nao contem ipfs.exe.' }
    return $found.FullName
}

try {
    $java = Find-JavaTool 'java'
    $javac = Find-JavaTool 'javac'
    foreach ($project in @('rep03-produtor', 'rep03-lider', 'rep03-replica', 'rep03-processador')) {
        $source = Join-Path $sprintRoot "$project\src\rep03"
        $sources = @(Get-ChildItem -LiteralPath $source -Filter '*.java' -File | ForEach-Object FullName)
        if (!$sources.Count) { throw "Sem ficheiros Java em $source" }
        $output = Join-Path $buildRoot $project
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        & $javac --release 11 -encoding UTF-8 -d $output @sources
        if ($LASTEXITCODE -ne 0) { throw "Falha a compilar $project." }
        Write-Host "Compilado: $project" -ForegroundColor Green
    }

    Write-Host "`nParticoes: $Particoes | API: $api"
    Write-Host "Dados: $dataRoot"
    Write-Host "Classes: $buildRoot"
    if ($Verificar) {
        Write-Host 'Verificacao concluida. Nao foram abertos processos nem descarregado Kubo.' -ForegroundColor Green
        exit 0
    }

    $replicaBuild = Join-Path $buildRoot 'rep03-replica'
    $leaderBuild = Join-Path $buildRoot 'rep03-lider'
    $producerBuild = Join-Path $buildRoot 'rep03-produtor'
    $processorBuild = Join-Path $buildRoot 'rep03-processador'
    if ($Acao -in @('CausalCheck','PartitionCheck')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
            throw "Pasta de dados inexistente: $dataRoot"
        }
        & $java -cp $replicaBuild "rep03.$Acao" "$Particoes" $dataRoot
        if ($LASTEXITCODE -ne 0) { throw "Falha a executar $Acao." }
        exit 0
    }
    if ($Acao -in @('Tudo','Lider')) {
        $leaderPorts = if ($Acao -eq 'Lider') { @(7000 + $Lider) } else { @(7000,7001,7002) }
        $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $leaderPorts })
        if ($occupied.Count) { throw "Ja existe um processo num porto de lider. Para-o antes de repetir o arranque." }
    }

    # 2. Kubo local. Um repositorio proprio evita alterar o IPFS do utilizador.
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    if (!(Test-Kubo)) {
        $ipfs = Resolve-Kubo
        $repo = Join-Path $toolsRoot 'ipfs-repo-comindir03'
        $previousRepo = $env:IPFS_PATH
        try {
            $env:IPFS_PATH = $repo
            if (!(Test-Path -LiteralPath (Join-Path $repo 'config'))) {
                & $ipfs init
                if ($LASTEXITCODE -ne 0) { throw 'Falha a inicializar o repositorio Kubo.' }
            }
            # Demonstração num unico no: sem descoberta nem ligacoes externas.
            $settings = @(
                @('Pubsub.Enabled','true'),
                @('Bootstrap','[]'),
                @('Addresses.Swarm','[]'),
                @('Discovery.MDNS.Enabled','false'),
                @('Peering.Peers','[]')
            )
            foreach ($setting in $settings) {
                & $ipfs config --json $setting[0] $setting[1]
                if ($LASTEXITCODE -ne 0) { throw "Falha na configuracao Kubo: $($setting[0])" }
            }
            & $ipfs config Routing.Type none
            if ($LASTEXITCODE -ne 0) { throw 'Falha a configurar Routing.Type.' }
        } finally { $env:IPFS_PATH = $previousRepo }
        Open-Console 'COMINDIR03 - Kubo' $ipfs @('daemon', '--enable-pubsub-experiment') $repo
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while (!(Test-Kubo)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Kubo nao ficou pronto. Consulta a consola Kubo.' }
            Start-Sleep -Milliseconds 500
        }
    } else {
        Write-Host 'A reutilizar o Kubo ativo na porta 5001. A sua configuracao nao foi alterada.'
    }

    if ($Acao -eq 'Lider') {
        Open-Console "COMINDIR03 - Lider p$Lider" $java @('-cp', $leaderBuild, 'rep03.PartitionLeader', "$Lider", "$Particoes", '127.0.0.1', $api)
        exit 0
    }
    if ($Acao -eq 'Processador') {
        Open-Console 'COMINDIR03 - Processador' $java @('-cp', $processorBuild, 'rep03.AlarmProcessor', "$Particoes", $api)
        exit 0
    }
    if ($Acao -eq 'Produtor') {
        Open-Console 'COMINDIR03 - Produtor' $java @('-cp', $producerBuild, 'rep03.Producer', "$Particoes", $api)
        exit 0
    }

    # 3. Replicas, lideres, processador e produtor.
    $assignments = if ($Particoes -eq 3) { @('0,1','1,2','2,0') } else { @('0','0','0') }
    $replicaBuild = Join-Path $buildRoot 'rep03-replica'
    $leaderBuild = Join-Path $buildRoot 'rep03-lider'
    $producerBuild = Join-Path $buildRoot 'rep03-produtor'
    for ($id = 1; $id -le 3; $id++) {
        $javaArgs = @('-cp', $replicaBuild)
        if ($id -eq 3) { $javaArgs += '-Dperda=' + $PerdaReplica3.ToString([Globalization.CultureInfo]::InvariantCulture) }
        $javaArgs += @('rep03.PartitionReplica', "$id", $assignments[$id - 1], $api)
        Open-Console "COMINDIR03 - Replica $id" $java $javaArgs
    }
    Read-Host 'Confirma as subscricoes nas tres consolas das replicas. Enter para abrir os lideres' | Out-Null
    for ($k = 0; $k -lt $Particoes; $k++) {
        Open-Console "COMINDIR03 - Lider p$k" $java @('-cp', $leaderBuild, 'rep03.PartitionLeader', "$k", "$Particoes", '127.0.0.1', $api)
    }
    Read-Host 'Confirma os portos e subscricoes dos lideres. Enter para continuar' | Out-Null
    if (!$SemProcessador) {
        Open-Console 'COMINDIR03 - Processador' $java @('-cp', $processorBuild, 'rep03.AlarmProcessor', "$Particoes", $api)
        Read-Host 'Confirma as tres subscricoes do processador. Enter para abrir o produtor' | Out-Null
    }
    Open-Console 'COMINDIR03 - Produtor' $java @('-cp', $producerBuild, 'rep03.Producer', "$Particoes", $api)
    Write-Host 'Consolas abertas. Os ficheiros de dados existentes foram conservados.' -ForegroundColor Green
} catch {
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
