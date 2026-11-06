[CmdletBinding()]
param(
    [string]$KuboPath,
    [string]$JavaHome,
    [ValidateSet(3)][int]$Particoes = 3,
    [ValidateSet('Tudo', 'Lider', 'Replica', 'Processador', 'Produtor', 'CausalCheck', 'HeartbeatCheck', 'PartitionCheck', 'TerminarLider', 'SuspenderLider', 'RetomarLider')]
    [string]$Acao = 'Tudo',
    [ValidateRange(0, 2)][int]$Lider = 0,
    [ValidateRange(1, 3)][int]$ReplicaId = 1,
    [string]$ParticoesReplica,
    [string]$PastaDados = 'dados-8',
    [ValidateRange(0, 1)][double]$PerdaReplica = 0,
    [ValidateScript({ $_ -ge -1 })][long]$DescartarHb = -1,
    [ValidateRange(1, 60000)][long]$Periodo = 5000,
    [ValidateRange(1, 100)][int]$FatorTimeout = 3,
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
$localRoot = Join-Path $env:LOCALAPPDATA 'SD-COMINDIR04'
$toolsRoot = Join-Path $localRoot 'tools'
$api = 'http://127.0.0.1:5001/api/v0'

function Get-LeaderProcess {
    $pattern = 'rep04\.PartitionLeader\s+' + $Lider + '\s+' + $Particoes + '(?:\s|$)'
    $leaderProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match $pattern
    })
    if ($leaderProcesses.Count -ne 1) {
        throw "Esperava encontrar exatamente um processo do lider p$Lider; encontrados: $($leaderProcesses.Count). Consulte os processos Java antes de repetir."
    }
    return $leaderProcesses[0]
}

function Stop-Leader {
    $leaderProcess = Get-LeaderProcess
    if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
        throw "Pasta de dados inexistente: $dataRoot"
    }
    $failureFile = Join-Path $dataRoot 'falhas.txt'
    Stop-Process -Id $leaderProcess.ProcessId -Force -ErrorAction Stop
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::AppendAllText($failureFile, "p$Lider;$timestamp`r`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Lider p$Lider terminado (PID $($leaderProcess.ProcessId)); falha anotada em $failureFile." -ForegroundColor Yellow
}

function Set-LeaderSuspension([bool]$Suspend) {
    $leaderProcess = Get-LeaderProcess
    if (!('ComindirProcessControl' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ComindirProcessControl {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);
    [DllImport("ntdll.dll")]
    public static extern int NtSuspendProcess(IntPtr processHandle);
    [DllImport("ntdll.dll")]
    public static extern int NtResumeProcess(IntPtr processHandle);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr handle);
}
'@
    }

    $handle = [ComindirProcessControl]::OpenProcess(0x0800, $false, [int]$leaderProcess.ProcessId)
    if ($handle -eq [IntPtr]::Zero) {
        throw "Nao foi possivel abrir o processo do lider p$Lider (Win32 $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
    }
    try {
        $status = if ($Suspend) {
            [ComindirProcessControl]::NtSuspendProcess($handle)
        } else {
            [ComindirProcessControl]::NtResumeProcess($handle)
        }
        if ($status -ne 0) { throw "Falha ao $(if ($Suspend) { 'suspender' } else { 'retomar' }) p$Lider (NTSTATUS 0x$('{0:X8}' -f $status))." }
    } finally {
        [void][ComindirProcessControl]::CloseHandle($handle)
    }
    $verb = if ($Suspend) { 'suspenso' } else { 'retomado' }
    Write-Host "Lider p$Lider $verb (PID $($leaderProcess.ProcessId))." -ForegroundColor Yellow
}

if ($Acao -in @('TerminarLider', 'SuspenderLider', 'RetomarLider')) {
    try {
        if ($Acao -eq 'TerminarLider') { Stop-Leader }
        if ($Acao -eq 'SuspenderLider') { Set-LeaderSuspension $true }
        if ($Acao -eq 'RetomarLider') { Set-LeaderSuspension $false }
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
    $buildRoot = Join-Path $localRoot ("build\" + [guid]::NewGuid().ToString('N'))
    $projects = @('rep04-lider', 'rep04-processador', 'rep04-produtor', 'rep04-replica')
    foreach ($project in $projects) {
        $source = Join-Path $sprintRoot "$project\src\rep04"
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

    $replicaBuild = Join-Path $buildRoot 'rep04-replica'
    $leaderBuild = Join-Path $buildRoot 'rep04-lider'
    $producerBuild = Join-Path $buildRoot 'rep04-produtor'
    $processorBuild = Join-Path $buildRoot 'rep04-processador'

    if ($Acao -in @('CausalCheck', 'HeartbeatCheck', 'PartitionCheck')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) { throw "Pasta de dados inexistente: $dataRoot" }
        $checkArgs = @("-Drep04.periodo=$Periodo", "-Drep04.fator=$FatorTimeout", '-cp', $replicaBuild)
        if ($Acao -eq 'HeartbeatCheck') {
            $checkArgs += @('rep04.HeartbeatCheck', "$Particoes", $dataRoot)
            $failureFile = Join-Path $dataRoot 'falhas.txt'
            if (Test-Path -LiteralPath $failureFile) { $checkArgs += $failureFile }
        } elseif ($Acao -eq 'CausalCheck') {
            $checkArgs += @('rep04.CausalCheck', "$Particoes", $dataRoot)
        } else {
            $checkArgs += @('rep04.PartitionCheck', "$Particoes", $dataRoot)
        }
        & $java @checkArgs
        if ($LASTEXITCODE -ne 0) { throw "Falha ao executar $Acao." }
        exit 0
    }

    if ($Acao -in @('Tudo', 'Lider')) {
        $leaderPorts = if ($Acao -eq 'Lider') { @(7000 + $Lider) } else { @(7000, 7001, 7002) }
        $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $leaderPorts })
        if ($occupied.Count) { throw "Ja existe um processo num porto de lider ($($occupied.LocalPort -join ', ')). Para-o antes de repetir o arranque." }
    }

    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    if (!(Test-Kubo)) {
        $ipfs = Resolve-Kubo
        $repo = Join-Path $toolsRoot 'ipfs-repo-comindir04'
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

        Open-Console 'COMINDIR04 - Kubo' $ipfs @('daemon', '--enable-pubsub-experiment') $repo
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
            throw "Ja existe um Kubo ativo na API 5001, mas PubSub nao esta disponivel ($pubSubError). Termina esse daemon e volta a executar o script para iniciar o Kubo COMINDIR04 com PubSub ativado."
        }
        Write-Host 'A reutilizar o Kubo ativo na API 5001; a configuracao existente nao foi alterada.'
    }

    $commonProperties = @("-Drep04.periodo=$Periodo", "-Drep04.fator=$FatorTimeout")
    $replicaAssignments = @('0,1', '1,2', '2,0')

    if ($Acao -eq 'Lider') {
        $leaderArgs = @($commonProperties)
        if ($Silencioso) { $leaderArgs += '-Dsilencioso=true' }
        $leaderArgs += @('-cp', $leaderBuild, 'rep04.PartitionLeader', "$Lider", "$Particoes", '127.0.0.1', $api)
        Open-Console "COMINDIR04 - Lider p$Lider" $java $leaderArgs
        exit 0
    }
    if ($Acao -eq 'Replica') {
        $assignment = if ($ParticoesReplica) { $ParticoesReplica } else { $replicaAssignments[$ReplicaId - 1] }
        $replicaArgs = @($commonProperties, "-Dperda=$($PerdaReplica.ToString([Globalization.CultureInfo]::InvariantCulture))", "-Ddescartar=$DescartarHb")
        if ($Silencioso) { $replicaArgs += '-Dsilencioso=true' }
        $replicaArgs += @('-cp', $replicaBuild, 'rep04.PartitionReplica', "$ReplicaId", $assignment, "$Particoes", '5001')
        Open-Console "COMINDIR04 - Replica $ReplicaId" $java $replicaArgs
        exit 0
    }
    if ($Acao -eq 'Processador') {
        $processorArgs = @($commonProperties, '-cp', $processorBuild, 'rep04.AlarmProcessor', "$Particoes", $api)
        Open-Console 'COMINDIR04 - Processador' $java $processorArgs
        exit 0
    }
    if ($Acao -eq 'Produtor') {
        $producerArgs = @($commonProperties, '-cp', $producerBuild, 'rep04.Producer', "$Particoes", $api)
        Open-Console 'COMINDIR04 - Produtor' $java $producerArgs
        exit 0
    }

    foreach ($id in 1..3) {
        $replicaArgs = @($commonProperties, "-Dperda=$($PerdaReplica.ToString([Globalization.CultureInfo]::InvariantCulture))", "-Ddescartar=$DescartarHb")
        if ($Silencioso) { $replicaArgs += '-Dsilencioso=true' }
        $replicaArgs += @('-cp', $replicaBuild, 'rep04.PartitionReplica', "$id", $replicaAssignments[$id - 1], "$Particoes", '5001')
        Open-Console "COMINDIR04 - Replica $id" $java $replicaArgs
    }
    Read-Host 'Confirma as subscricoes das tres replicas. Enter para abrir os lideres' | Out-Null

    foreach ($k in 0..($Particoes - 1)) {
        $leaderArgs = @($commonProperties)
        if ($Silencioso) { $leaderArgs += '-Dsilencioso=true' }
        $leaderArgs += @('-cp', $leaderBuild, 'rep04.PartitionLeader', "$k", "$Particoes", '127.0.0.1', $api)
        Open-Console "COMINDIR04 - Lider p$k" $java $leaderArgs
    }
    Read-Host 'Confirma os portos e a publicacao de LOTEs pelos lideres. Enter para continuar' | Out-Null

    if (!$SemProcessador) {
        $processorArgs = @($commonProperties, '-cp', $processorBuild, 'rep04.AlarmProcessor', "$Particoes", $api)
        Open-Console 'COMINDIR04 - Processador' $java $processorArgs
        Read-Host 'Confirma as subscricoes do processador. Enter para abrir o produtor' | Out-Null
    }

    $producerArgs = @($commonProperties, '-cp', $producerBuild, 'rep04.Producer', "$Particoes", $api)
    Open-Console 'COMINDIR04 - Produtor' $java $producerArgs
    Write-Host "Consolas abertas. Dados e registos de monitorizacao: $dataRoot" -ForegroundColor Green
} catch {
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
