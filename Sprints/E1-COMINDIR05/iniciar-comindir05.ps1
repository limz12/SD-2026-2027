[CmdletBinding()]
param(
    [string]$KuboPath,
    [string]$JavaHome,
    [ValidateSet(3)][int]$Particoes = 3,
    [ValidateSet('Tudo', 'No', 'Processador', 'Produtor', 'RaftCheck', 'CausalCheck', 'TerminarNo', 'IsolarNo', 'RepararNo')]
    [string]$Acao = 'Tudo',
    [ValidateRange(0, 2)][int]$Particao = 0,
    [ValidateRange(1, 3)][int]$No = 1,
    [string]$PastaDados = 'dados-9',
    [ValidateRange(1, 60000)][long]$Periodo = 2000,
    [ValidateRange(1, 100)][int]$FatorTimeout = 3,
    [ValidateRange(0, 60000)][long]$EleicaoMin = 0,
    [ValidateRange(0, 60000)][long]$EleicaoMax = 0,
    [switch]$Silencioso,
    [switch]$Verificar
)

# COMINDIR05 · Eleição do líder de partição (RAFT)
# Compila os três projetos (raft04-no, raft04-produtor, raft04-processador) e
# arranca os NOVE nós (3 por partição), o processador e o produtor.
#
# Ensaios manuais (ver ARRANQUE-COMINDIR05.md):
#   .\iniciar-comindir05.cmd -PastaDados dados-9a
#   .\iniciar-comindir05.cmd -Acao TerminarNo  -Particao 1 -No 2 -PastaDados dados-9a   # falha do líder
#   .\iniciar-comindir05.cmd -Acao No         -Particao 1 -No 2 -PastaDados dados-9a   # volta a arrancar
#   .\iniciar-comindir05.cmd -Acao IsolarNo   -Particao 1 -No 2 -PastaDados dados-9a   # partição de rede
#   .\iniciar-comindir05.cmd -Acao RepararNo  -Particao 1 -No 2 -PastaDados dados-9a
#   .\iniciar-comindir05.cmd -Acao RaftCheck   -PastaDados dados-9a
#   .\iniciar-comindir05.cmd -Acao CausalCheck -PastaDados dados-9a
#
# -EleicaoMin/-EleicaoMax = 0 (omissão) usam 3×Δ e 6×Δ. O ensaio 9(b) com prazo
# fixo usa -EleicaoMin 6000 -EleicaoMax 6000. Usa o MESMO -Periodo/-EleicaoMin/
# -EleicaoMax em TODOS os processos e verificadores de um ensaio.

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

function Get-NodeProcess([int]$k, [int]$i) {
    $pattern = 'raft04\.PartitionNode\s+' + $k + '\s+' + $i + '(?:\s|$)'
    $nodeProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match $pattern
    })
    if ($nodeProcesses.Count -ne 1) {
        throw "Esperava encontrar exatamente um processo do no n$i de p$k; encontrados: $($nodeProcesses.Count). Consulte os processos Java antes de repetir."
    }
    return $nodeProcesses[0]
}

function Stop-Node([int]$k, [int]$i) {
    $nodeProcess = Get-NodeProcess $k $i
    if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
        throw "Pasta de dados inexistente: $dataRoot"
    }
    $failureFile = Join-Path $dataRoot 'falhas.txt'
    Stop-Process -Id $nodeProcess.ProcessId -Force -ErrorAction Stop
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::AppendAllText($failureFile, "p$k;n$i;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
    Write-Host "No n$i de p$k terminado (PID $($nodeProcess.ProcessId)); falha anotada em $failureFile." -ForegroundColor Yellow
}

function Set-Isolamento([bool]$Isolar) {
    if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
        throw "Pasta de dados inexistente: $dataRoot"
    }
    $isolarFile = Join-Path $dataRoot "isolar-p$Particao-n$No"
    $diario = Join-Path $dataRoot 'isolamentos.txt'
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($Isolar) {
        Set-Content -LiteralPath $isolarFile -Value 'isolado'
        [IO.File]::AppendAllText($diario, "p$Particao;n$No;isolado;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
        Write-Host "PARTICAO DE REDE: n$No de p$Particao isolado (ficheiro $($isolarFile.Name)); anotado em $diario." -ForegroundColor Yellow
    } else {
        Remove-Item -LiteralPath $isolarFile -ErrorAction Stop
        [IO.File]::AppendAllText($diario, "p$Particao;n$No;reparado;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
        Write-Host "Particao de rede reparada: n$No de p$Particao volta a publicar e a processar; anotado em $diario." -ForegroundColor Yellow
    }
}

if ($Acao -in @('TerminarNo', 'IsolarNo', 'RepararNo')) {
    try {
        if ($Acao -eq 'TerminarNo') { Stop-Node $Particao $No }
        if ($Acao -eq 'IsolarNo') { Set-Isolamento $true }
        if ($Acao -eq 'RepararNo') { Set-Isolamento $false }
        exit 0
    } catch {
        Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

function Find-JavaTool([string]$Name) {
    foreach ($jdkRoot in @($JavaHome, $env:JAVA_HOME)) {
        if ($jdkRoot) {
            $candidate = Join-Path $jdkRoot "bin\$Name.exe"
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name.exe nao encontrado. Usa -JavaHome com a pasta de um JDK 11 ou superior."
}

function Open-Console([string]$Title, [string]$Executable, [string[]]$Arguments) {
    $quoted = @($Executable) + @($Arguments) | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $lines = @(
        "`$Host.UI.RawUI.WindowTitle = '" + $Title.Replace("'", "''") + "'"
        "Set-Location -LiteralPath '" + $dataRoot.Replace("'", "''") + "'"
        '& ' + ($quoted -join ' ')
    )
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
        if (!(Test-Path -LiteralPath $KuboPath -PathType Leaf)) { throw "Executavel Kubo inexistente: $KuboPath" }
        return (Resolve-Path -LiteralPath $KuboPath).Path
    }
    $command = Get-Command ipfs.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($folder in @(
        $toolsRoot,
        (Join-Path (Split-Path $sprintRoot) 'E1-COMINDIR04\.tools'),
        (Join-Path (Split-Path $sprintRoot) 'E1-COMINDIR02\.tools'),
        (Join-Path $env:LOCALAPPDATA 'SD-COMINDIR04\tools')
    )) {
        if ($folder -and (Test-Path -LiteralPath $folder)) {
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
    $buildRoot = Join-Path $localRoot ("build\" + [guid]::NewGuid().ToString('N'))
    $projects = @('raft04-no', 'raft04-produtor', 'raft04-processador')
    foreach ($project in $projects) {
        $source = Join-Path $sprintRoot "$project\src\raft04"
        $sources = @(Get-ChildItem -LiteralPath $source -Filter '*.java' -File | ForEach-Object FullName)
        if (!$sources.Count) { throw "Sem ficheiros Java em $source" }
        $output = Join-Path $buildRoot $project
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        & $javac --release 11 -encoding UTF-8 -d $output @sources
        if ($LASTEXITCODE -ne 0) { throw "Falha a compilar $project." }
        Write-Host "Compilado: $project" -ForegroundColor Green
    }

    if ($Verificar) {
        Write-Host 'Verificacao concluida. Nao foram abertos processos nem iniciado o Kubo.' -ForegroundColor Green
        exit 0
    }

    $noBuild = Join-Path $buildRoot 'raft04-no'
    $producerBuild = Join-Path $buildRoot 'raft04-produtor'
    $processorBuild = Join-Path $buildRoot 'raft04-processador'

    $commonProperties = @("-Draft04.periodo=$Periodo", "-Draft04.fator=$FatorTimeout")
    if ($EleicaoMin -gt 0) { $commonProperties += "-Draft04.eleicaoMin=$EleicaoMin" }
    if ($EleicaoMax -gt 0) { $commonProperties += "-Draft04.eleicaoMax=$EleicaoMax" }

    if ($Acao -in @('RaftCheck', 'CausalCheck')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) { throw "Pasta de dados inexistente: $dataRoot" }
        $checkArgs = $commonProperties + @('-cp', $noBuild)
        if ($Acao -eq 'RaftCheck') {
            $checkArgs += @('raft04.RaftCheck', "$Particoes", $dataRoot)
            $failureFile = Join-Path $dataRoot 'falhas.txt'
            if (Test-Path -LiteralPath $failureFile) { $checkArgs += $failureFile }
        } else {
            $checkArgs += @('raft04.CausalCheck', "$Particoes", $dataRoot)
        }
        & $java @checkArgs
        if ($LASTEXITCODE -ne 0) { throw "Falha ao executar $Acao." }
        exit 0
    }

    if ($Acao -in @('Tudo', 'No')) {
        $nodePorts = if ($Acao -eq 'No') { @(7100 + $Particao * 10 + $No) } else { @(7101..7123) }
        $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $nodePorts })
        if ($occupied.Count) { throw "Ja existe um processo num porto de no ($($occupied.LocalPort -join ', ')). Para-o antes de repetir o arranque." }
    }

    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    if (!(Test-Kubo)) {
        $ipfs = Resolve-Kubo
        $repo = Join-Path $toolsRoot 'ipfs-repo-comindir05'
        New-Item -ItemType Directory -Path (Split-Path $repo) -Force | Out-Null
        $previousRepo = $env:IPFS_PATH
        try {
            $env:IPFS_PATH = $repo
            if (!(Test-Path -LiteralPath (Join-Path $repo 'config'))) {
                & $ipfs init
                if ($LASTEXITCODE -ne 0) { throw 'Falha ao inicializar o repositorio Kubo.' }
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
                if ($LASTEXITCODE -ne 0) { throw "Falha na configuracao Kubo: $($setting[0])" }
            }
            & $ipfs config Routing.Type none
            if ($LASTEXITCODE -ne 0) { throw 'Falha ao configurar Routing.Type.' }
        } finally { $env:IPFS_PATH = $previousRepo }

        Open-Console 'COMINDIR05 - Kubo' $ipfs @('daemon', '--enable-pubsub-experiment')
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while (!(Test-Kubo)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Kubo nao ficou pronto. Consulta a consola Kubo.' }
            Start-Sleep -Milliseconds 500
        }
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $pubSubError = Get-KuboPubSubError
            if (!$pubSubError) { break }
            if ([DateTime]::UtcNow -gt $deadline) {
                throw "Kubo iniciou, mas PubSub nao ficou disponivel: $pubSubError"
            }
            Start-Sleep -Milliseconds 500
        } while ($true)
    } else {
        $pubSubError = Get-KuboPubSubError
        if ($pubSubError) {
            throw "Ja existe um Kubo ativo na API 5001, mas PubSub nao esta disponivel ($pubSubError). Termina esse daemon e volta a executar o script para iniciar o Kubo COMINDIR05 com PubSub ativado."
        }
        Write-Host 'A reutilizar o Kubo ativo na API 5001; a configuracao existente nao foi alterada.'
    }

    if ($Acao -eq 'No') {
        $nodeArgs = @($commonProperties)
        if ($Silencioso) { $nodeArgs += '-Dsilencioso=true' }
        $nodeArgs += @('-cp', $noBuild, 'raft04.PartitionNode', "$Particao", "$No", "$Particoes", '5001')
        Open-Console "COMINDIR05 - No p$Particao n$No" $java $nodeArgs
        Write-Host "Consola do no n$No de p$Particao aberta (dados em $dataRoot)." -ForegroundColor Green
        exit 0
    }
    if ($Acao -eq 'Processador') {
        $processorArgs = $commonProperties + @('-cp', $processorBuild, 'raft04.AlarmProcessor', "$Particoes", $api)
        Open-Console 'COMINDIR05 - Processador' $java $processorArgs
        exit 0
    }
    if ($Acao -eq 'Produtor') {
        $producerArgs = $commonProperties + @('-cp', $producerBuild, 'raft04.Producer', "$Particoes", $api)
        Open-Console 'COMINDIR05 - Produtor' $java $producerArgs
        exit 0
    }

    # arranque completo: os nove nós, por qualquer ordem (a primeira eleição
    # decorre no arranque), depois o processador e por fim o produtor
    foreach ($k in 0..($Particoes - 1)) {
        foreach ($i in 1..3) {
            $nodeArgs = @($commonProperties)
            if ($Silencioso) { $nodeArgs += '-Dsilencioso=true' }
            $nodeArgs += @('-cp', $noBuild, 'raft04.PartitionNode', "$k", "$i", "$Particoes", '5001')
            Open-Console "COMINDIR05 - No p$k n$i" $java $nodeArgs
        }
    }
    Write-Host "Nove nos abertos. AGUARDA a primeira eleicao de cada particao (prazo sorteado em [$([Math]::Max($EleicaoMin, 3 * $Periodo)), $([Math]::Max($EleicaoMax, 6 * $Periodo))] ms):" -ForegroundColor Yellow
    Write-Host "  em cada consola deve aparecer uma linha LIDER;p<k>;termo=1;... nos eventos-p<k>-n<i>.txt e o HEARTBEAT do novo lider."
    Read-Host 'Enter para abrir o processador e o produtor' | Out-Null

    $processorArgs = $commonProperties + @('-cp', $processorBuild, 'raft04.AlarmProcessor', "$Particoes", $api)
    Open-Console 'COMINDIR05 - Processador' $java $processorArgs

    $producerArgs = $commonProperties + @('-cp', $producerBuild, 'raft04.Producer', "$Particoes", $api)
    Open-Console 'COMINDIR05 - Produtor' $java $producerArgs
    Write-Host "Consolas abertas. Dados e registos de monitorizacao: $dataRoot" -ForegroundColor Green
    Write-Host "As leituras escrevem-se SO na consola COMINDIR05 - Produtor (comandos: <sensor> <temp> | rajada <n> <s> | sair)." -ForegroundColor Green
} catch {
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
