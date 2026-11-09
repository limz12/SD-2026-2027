[CmdletBinding()]
param(
    [ValidateSet('Verificar', 'Tudo', 'No', 'Processador', 'Produtor', 'RaftCheck', 'CausalCheck', 'FalharNo', 'IsolarNo', 'RepararNo')]
    [string]$Acao = 'Tudo',
    [ValidateRange(0, 2)][int]$Particao = 1,
    [ValidateRange(1, 3)][int]$NoId = 1,
    [ValidateSet(3)][int]$Particoes = 3,
    [string]$PastaDados = 'dados-raft04',
    [string]$JavaHome,
    [string]$KuboPath,
    [ValidateRange(1, 60000)][long]$Periodo = 2000,
    [ValidateRange(1, 100)][int]$FatorTimeout = 3,
    [ValidateRange(1, 600000)][long]$EleicaoMin = 6000,
    [ValidateRange(1, 600000)][long]$EleicaoMax = 12000,
    [string]$OrdemNos,
    [switch]$SincronizarArranque,
    [switch]$SemProcessador
)

$ErrorActionPreference = 'Stop'
$sprintRoot = $PSScriptRoot
$dataRoot = if ([IO.Path]::IsPathRooted($PastaDados)) {
    [IO.Path]::GetFullPath($PastaDados)
} else {
    [IO.Path]::GetFullPath((Join-Path $sprintRoot $PastaDados))
}
$localRoot = Join-Path $env:LOCALAPPDATA 'SD-COMINDIR05'
$api = 'http://127.0.0.1:5001/api/v0'

function Add-LogLine([string]$Name, [string]$Line) {
    [IO.File]::AppendAllText((Join-Path $dataRoot $Name), "$Line`r`n", [Text.UTF8Encoding]::new($false))
}

function Get-NodeProcess {
    $pattern = 'raft04\.PartitionNode\s+' + $Particao + '\s+' + $NoId + '(?:\s+3)?(?:\s|$)'
    $matches = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and $_.CommandLine -match $pattern
    })
    if ($matches.Count -ne 1) {
        throw "Esperava um processo p$Particao/n$NoId; encontrei $($matches.Count)."
    }
    return $matches[0]
}

try {
    if ($Acao -in @('FalharNo', 'IsolarNo', 'RepararNo')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) {
            throw "Pasta de dados inexistente: $dataRoot"
        }
        $flag = Join-Path $dataRoot "isolar-p$Particao-n$NoId"
        if ($Acao -eq 'FalharNo') {
            $node = Get-NodeProcess
            $instant = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            Stop-Process -Id $node.ProcessId -Force -ErrorAction Stop
            Add-LogLine 'falhas.txt' "p$Particao;n$NoId;$instant"
            Write-Host "Terminou p$Particao/n$NoId (PID $($node.ProcessId)); falha anotada."
        } elseif ($Acao -eq 'IsolarNo') {
            if (Test-Path -LiteralPath $flag) { throw "Já está isolado: $flag" }
            New-Item -ItemType File -Path $flag -ErrorAction Stop | Out-Null
            Add-LogLine 'isolamentos.txt' "p$Particao;n$NoId;inicio;$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
            Write-Host "Isolado p$Particao/n$NoId."
        } else {
            if (!(Test-Path -LiteralPath $flag)) { throw "Não está isolado: $flag" }
            Remove-Item -LiteralPath $flag -ErrorAction Stop
            Add-LogLine 'isolamentos.txt' "p$Particao;n$NoId;fim;$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
            Write-Host "Reparada a ligação de p$Particao/n$NoId."
        }
        exit 0
    }
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

function Find-JavaTool([string]$Name) {
    foreach ($root in @($JavaHome, $env:JAVA_HOME)) {
        if ($root) {
            $candidate = Join-Path $root "bin\$Name.exe"
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    $found = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    throw "$Name.exe não encontrado; indica -JavaHome com um JDK 11 ou superior."
}

function Test-Kubo {
    try {
        $version = Invoke-RestMethod -Method Post -Uri "$api/version" -TimeoutSec 2
        return [bool]$version.Version
    } catch { return $false }
}

function Test-PubSub {
    try {
        Invoke-RestMethod -Method Post -Uri "$api/pubsub/ls" -TimeoutSec 3 | Out-Null
        return $true
    } catch { return $false }
}

function Find-Kubo {
    if ($KuboPath) {
        if (!(Test-Path -LiteralPath $KuboPath -PathType Leaf)) { throw "Kubo inexistente: $KuboPath" }
        return (Resolve-Path -LiteralPath $KuboPath).Path
    }
    $found = Get-Command ipfs.exe -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($folder in @((Join-Path $sprintRoot '.tools'), (Join-Path (Split-Path $sprintRoot) 'E1-COMINDIR02\.tools'))) {
        if (Test-Path -LiteralPath $folder) {
            $found = Get-ChildItem -LiteralPath $folder -Filter ipfs.exe -Recurse -File | Select-Object -First 1
            if ($found) { return $found.FullName }
        }
    }
    throw 'Kubo não encontrado. Indica -KuboPath ou inicia um Kubo com PubSub na API 127.0.0.1:5001.'
}

function Ensure-Kubo {
    if (Test-Kubo) {
        if (!(Test-PubSub)) { throw 'O Kubo da API 5001 não disponibiliza PubSub.' }
        return
    }
    $kubo = Find-Kubo
    $repo = Join-Path $localRoot 'ipfs-repo-raft04'
    New-Item -ItemType Directory -Path $repo -Force | Out-Null
    $previous = $env:IPFS_PATH
    try {
        $env:IPFS_PATH = $repo
        if (!(Test-Path -LiteralPath (Join-Path $repo 'config'))) {
            & $kubo init
            if ($LASTEXITCODE -ne 0) { throw 'Falha ao inicializar Kubo.' }
        }
        foreach ($setting in @(@('Pubsub.Enabled','true'), @('Bootstrap','[]'), @('Addresses.Swarm','[]'), @('Discovery.MDNS.Enabled','false'))) {
            & $kubo config --json $setting[0] $setting[1]
            if ($LASTEXITCODE -ne 0) { throw "Falha na configuração Kubo: $($setting[0])" }
        }
        Start-Process -FilePath $kubo -ArgumentList @('daemon', '--enable-pubsub-experiment') -WorkingDirectory $dataRoot -WindowStyle Hidden | Out-Null
    } finally {
        $env:IPFS_PATH = $previous
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (!(Test-Kubo)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Kubo não ficou pronto na API 5001.' }
        Start-Sleep -Milliseconds 500
    }
    if (!(Test-PubSub)) { throw 'Kubo arrancou sem PubSub.' }
}

function Open-Java([string]$Title, [string]$Main, [string]$Build, [string[]]$AppArgs, [string]$Ready, [string]$Gate) {
    $javaArgs = @($script:properties) + @('-cp', $Build, $Main) + @($AppArgs)
    $quotedExe = "'" + $script:java.Replace("'", "''") + "'"
    $quotedArgs = @($javaArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" })
    $lines = @(
        "`$Host.UI.RawUI.WindowTitle = '" + $Title.Replace("'", "''") + "'",
        "Set-Location -LiteralPath '" + $dataRoot.Replace("'", "''") + "'"
    )
    if ($Gate) {
        $lines += "[IO.File]::WriteAllText('" + $Ready.Replace("'", "''") + "', '')"
        $lines += "while (!(Test-Path -LiteralPath '" + $Gate.Replace("'", "''") + "')) { Start-Sleep -Milliseconds 20 }"
    }
    $lines += '& ' + ((@($quotedExe) + $quotedArgs) -join ' ')
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(($lines -join "`r`n")))
    Start-Process -FilePath powershell.exe -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) -WindowStyle Normal | Out-Null
}

try {
    if ($EleicaoMax -lt $EleicaoMin) { throw 'EleicaoMax deve ser pelo menos EleicaoMin.' }
    $java = Find-JavaTool 'java'
    $javac = Find-JavaTool 'javac'
    $projects = switch ($Acao) {
        'No' { @('raft04-no') }
        'Produtor' { @('raft04-produtor') }
        'Processador' { @('raft04-processador') }
        'RaftCheck' { @('raft04-no') }
        'CausalCheck' { @('raft04-no') }
        default { @('raft04-no', 'raft04-processador', 'raft04-produtor') }
    }
    $buildRoot = Join-Path $localRoot ("build\" + [guid]::NewGuid().ToString('N'))
    foreach ($project in $projects) {
        $src = Join-Path $sprintRoot "$project\src\raft04"
        $sources = @(Get-ChildItem -LiteralPath $src -Filter '*.java' -File | ForEach-Object FullName)
        if ($sources.Count -eq 0) { throw "Sem ficheiros Java em $src" }
        $output = Join-Path $buildRoot $project
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        & $javac --release 11 -encoding UTF-8 -d $output @sources
        if ($LASTEXITCODE -ne 0) { throw "Falha a compilar $project." }
        Write-Host "Compilado: $project"
    }
    if ($Acao -eq 'Verificar') { Write-Host 'Compilação concluída; nenhum processo iniciado.'; exit 0 }

    if ($Acao -in @('RaftCheck', 'CausalCheck')) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) { throw "Pasta de dados inexistente: $dataRoot" }
        $args = @('-cp', (Join-Path $buildRoot 'raft04-no'), "raft04.$Acao", "$Particoes", $dataRoot)
        $failures = Join-Path $dataRoot 'falhas.txt'
        if ($Acao -eq 'RaftCheck' -and (Test-Path -LiteralPath $failures)) { $args += $failures }
        & $java @args
        if ($LASTEXITCODE -ne 0) { throw "Falha ao executar $Acao." }
        exit 0
    }

    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    Ensure-Kubo
    $properties = @("-Draft04.periodo=$Periodo", "-Draft04.fator=$FatorTimeout", "-Draft04.eleicaoMin=$EleicaoMin", "-Draft04.eleicaoMax=$EleicaoMax")

    if ($Acao -eq 'No') {
        Open-Java "RAFT04 p$Particao/n$NoId" 'raft04.PartitionNode' (Join-Path $buildRoot 'raft04-no') @("$Particao", "$NoId", "$Particoes") '' ''
        exit 0
    }
    if ($Acao -eq 'Processador') {
        Open-Java 'RAFT04 Processador' 'raft04.AlarmProcessor' (Join-Path $buildRoot 'raft04-processador') @("$Particoes", $api) '' ''
        exit 0
    }
    if ($Acao -eq 'Produtor') {
        Open-Java 'RAFT04 Produtor' 'raft04.Producer' (Join-Path $buildRoot 'raft04-produtor') @("$Particoes", $api) '' ''
        exit 0
    }

    $nodes = @()
    if ($OrdemNos) {
        foreach ($token in $OrdemNos.Split(',')) {
            $nodeMatch = [regex]::Match($token.Trim(), '^p([0-2])n([1-3])$')
            if (!$nodeMatch.Success) { throw "Nó inválido em OrdemNos: $token" }
            $nodes += "p$($nodeMatch.Groups[1].Value)n$($nodeMatch.Groups[2].Value)"
        }
        if ($nodes.Count -ne 9 -or @($nodes | Select-Object -Unique).Count -ne 9) {
            throw 'OrdemNos deve conter exatamente os nove nós, uma vez cada.'
        }
    } else {
        foreach ($k in 0..2) { foreach ($i in 1..3) { $nodes += "p${k}n$i" } }
    }

    $gate = ''
    $readyFiles = @()
    if ($SincronizarArranque) { $gate = Join-Path $dataRoot ("arranque-" + [guid]::NewGuid().ToString('N') + '.go') }
    foreach ($node in $nodes) {
        $nodeMatch = [regex]::Match($node, '^p([0-2])n([1-3])$')
        $k = $nodeMatch.Groups[1].Value
        $i = $nodeMatch.Groups[2].Value
        $ready = if ($gate) { "$gate-$node.ready" } else { '' }
        if ($ready) { $readyFiles += $ready }
        Open-Java "RAFT04 p$k/n$i" 'raft04.PartitionNode' (Join-Path $buildRoot 'raft04-no') @($k, $i, "$Particoes") $ready $gate
    }
    if ($gate) {
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        while (@($readyFiles | Where-Object { !(Test-Path -LiteralPath $_) }).Count -gt 0) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Nem todas as consolas de nós ficaram prontas.' }
            Start-Sleep -Milliseconds 100
        }
        New-Item -ItemType File -Path $gate -ErrorAction Stop | Out-Null
        Write-Host 'Arranque dos nove nós libertado em conjunto.'
    }
    if (!$SemProcessador) {
        Open-Java 'RAFT04 Processador' 'raft04.AlarmProcessor' (Join-Path $buildRoot 'raft04-processador') @("$Particoes", $api) '' ''
    }
    Open-Java 'RAFT04 Produtor' 'raft04.Producer' (Join-Path $buildRoot 'raft04-produtor') @("$Particoes", $api) '' ''
    Write-Host "Consolas abertas. Dados: $dataRoot"
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
