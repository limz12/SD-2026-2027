[CmdletBinding()]
param(
    # all | sanidade | 9a | 9b | 9c | 9d | 9e | falhas  (lista separada por vírgulas)
    [string]$Cenarios = 'all',
    [switch]$ManterKubo
)

# =============================================================================
# testar-comindir05.ps1 — bateria automática de ensaios da COMINDIR05
#
# Compila os três projetos, garante um Kubo com PubSub e executa, SEM
# intervenção: sanidade, 9(a) a 9(e) e as falhas deliberadas do ensaio 10
# (com flags -Draft04.falha*, sem tocar no código). No fim de cada cenário
# corre RaftCheck e CausalCheck e guarda a saída em testes\*.txt, com um
# resumo PASS/FAIL (testes\RESUMO-TESTES.md).
#
#   powershell -ExecutionPolicy Bypass -File .\testar-comindir05.ps1
#   powershell -ExecutionPolicy Bypass -File .\testar-comindir05.ps1 -Cenarios 9a,9d
# =============================================================================

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)   # a saída do Java é UTF-8 (JEP 400)
$sprint = $PSScriptRoot
$testesRoot = Join-Path $sprint 'testes'
$localRoot = Join-Path $env:LOCALAPPDATA 'SD-COMINDIR05'
$toolsRoot = Join-Path $localRoot 'tools'
$build = Join-Path $localRoot 'build-teste'
$api = 'http://127.0.0.1:5001/api/v0'
New-Item -ItemType Directory -Path $testesRoot -Force | Out-Null

# ------------------------------------------------------------------ java/kubo

function Find-JavaTool([string]$Name) {
    foreach ($jdkRoot in @($env:JAVA_HOME)) {
        if ($jdkRoot) {
            $candidate = Join-Path $jdkRoot "bin\$Name.exe"
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name.exe nao encontrado (precisas de um JDK 11+)."
}

$script:java = Find-JavaTool 'java'
$javac = Find-JavaTool 'javac'

function Build-Projects {
    foreach ($p in @('raft04-no', 'raft04-produtor', 'raft04-processador')) {
        $out = Join-Path $build $p
        New-Item -ItemType Directory -Path $out -Force | Out-Null
        & $javac --release 11 -encoding UTF-8 -d $out @(Get-ChildItem -LiteralPath (Join-Path $sprint "$p\src\raft04") -Filter '*.java' -File | ForEach-Object FullName)
        if ($LASTEXITCODE -ne 0) { throw "Falha a compilar $p." }
    }
    Write-Host 'Compilacao OK (raft04-no, raft04-produtor, raft04-processador).' -ForegroundColor Green
}

function Test-Kubo {
    try {
        $request = [Net.HttpWebRequest]::Create("$api/version")
        $request.Method = 'POST'; $request.Proxy = $null; $request.Timeout = 1500
        $response = $request.GetResponse()
        try {
            $reader = [IO.StreamReader]::new($response.GetResponseStream())
            try { $version = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            return [bool]$version.Version
        } finally { $response.Dispose() }
    } catch [Net.WebException] { return $false }
}

function Ensure-Kubo {
    if (Test-Kubo) {
        # confirma PubSub
        try {
            $request = [Net.HttpWebRequest]::Create("$api/pubsub/ls")
            $request.Method = 'POST'; $request.Proxy = $null; $request.Timeout = 3000
            $response = $request.GetResponse(); $response.Dispose()
            Write-Host 'Kubo ja ativo na API 5001 (com PubSub): reutilizado.' -ForegroundColor Green
            return
        } catch {
            throw "Ha um Kubo ativo na API 5001 mas PubSub nao responde. Termina-o primeiro."
        }
    }
    $ipfs = (Get-Command ipfs.exe -ErrorAction SilentlyContinue).Source
    if (!$ipfs) {
        foreach ($folder in @((Join-Path (Split-Path $sprint) 'E1-COMINDIR04\.tools'),
                               (Join-Path (Split-Path $sprint) 'E1-COMINDIR02\.tools'),
                               $toolsRoot)) {
            if ($folder -and (Test-Path -LiteralPath $folder)) {
                $found = Get-ChildItem -LiteralPath $folder -Filter ipfs.exe -Recurse -File | Select-Object -First 1
                if ($found) { $ipfs = $found.FullName; break }
            }
        }
    }
    if (!$ipfs) { throw 'ipfs.exe nao encontrado (instala o Kubo ou poe-o no PATH).' }
    $repo = Join-Path $toolsRoot 'ipfs-repo-comindir05'
    New-Item -ItemType Directory -Path (Split-Path $repo) -Force | Out-Null
    $previous = $env:IPFS_PATH
    try {
        $env:IPFS_PATH = $repo
        if (!(Test-Path -LiteralPath (Join-Path $repo 'config'))) {
            & $ipfs init | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'ipfs init falhou.' }
        }
        foreach ($setting in @(@('Pubsub.Enabled', 'true'), @('Bootstrap', '[]'),
                               @('Addresses.Swarm', '[]'), @('Discovery.MDNS.Enabled', 'false'),
                               @('Peering.Peers', '[]'))) {
            & $ipfs config --json $setting[0] $setting[1] | Out-Null
        }
        & $ipfs config Routing.Type none | Out-Null
        $script:kubo = Start-Process -FilePath $ipfs -ArgumentList @('daemon', '--enable-pubsub-experiment') `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testesRoot 'kubo-out.log') `
            -RedirectStandardError (Join-Path $testesRoot 'kubo-err.log')
    } finally { $env:IPFS_PATH = $previous }
    $deadline = [DateTime]::Now.AddSeconds(45)
    while (!(Test-Kubo)) {
        if ([DateTime]::Now -gt $deadline) { throw 'Kubo nao ficou pronto (ver testes/kubo-err.log).' }
        Start-Sleep -Milliseconds 500
    }
    Write-Host 'Kubo iniciado com PubSub.' -ForegroundColor Green
}

# ------------------------------------------------------------------ processos

$script:procs = @()
$script:prod = $null

function New-JavaProcess([string]$Nome, [string]$Projeto, [string]$Main, [string[]]$AppArgs,
        [string]$Pasta, [string[]]$Props, [switch]$ComEntrada) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:java
    $all = @($Props) + @('-cp', (Join-Path $build $Projeto), $Main) + @($AppArgs)
    $psi.Arguments = ($all -join ' ')
    $psi.WorkingDirectory = $Pasta
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    if ($ComEntrada) { $psi.RedirectStandardInput = $true }
    $p = [System.Diagnostics.Process]::Start($psi)
    $obj = [pscustomobject]@{ Nome = $Nome; Proc = $p; Pasta = $Pasta;
        Out = $p.StandardOutput.ReadToEndAsync(); Err = $p.StandardError.ReadToEndAsync() }
    $script:procs += $obj
    return $obj
}

function Start-No([int]$k, [int]$i, [string]$Pasta, [string[]]$Props = @()) {
    New-JavaProcess -Nome "p$k-n$i" -Projeto 'raft04-no' -Main 'raft04.PartitionNode' `
        -AppArgs @("$k", "$i", '3', '5001') -Pasta $Pasta -Props (@('-Dsilencioso=true') + $Props)
}

function Start-Processador([string]$Pasta, [string[]]$Props = @()) {
    New-JavaProcess -Nome 'processador' -Projeto 'raft04-processador' -Main 'raft04.AlarmProcessor' `
        -AppArgs @('3', $api) -Pasta $Pasta -Props (@('-Dsilencioso=true') + $Props)
}

function Start-Produtor([string]$Pasta, [string[]]$Props = @()) {
    $script:prod = New-JavaProcess -Nome 'produtor' -Projeto 'raft04-produtor' -Main 'raft04.Producer' `
        -AppArgs @('3', $api) -Pasta $Pasta -Props $Props -ComEntrada
}

function Enviar([string]$Comando) {
    $script:prod.Proc.StandardInput.WriteLine($Comando)
    $script:prod.Proc.StandardInput.Flush()
}

function Parar-Tudo([switch]$GuardarConsolas) {
    if ($script:prod -and !$script:prod.Proc.HasExited) {
        try { Enviar 'sair'; $script:prod.Proc.WaitForExit(5000) | Out-Null } catch {}
    }
    foreach ($p in $script:procs) {
        if (!$p.Proc.HasExited) {
            try { $p.Proc.Kill() } catch {}
            try { $p.Proc.WaitForExit(5000) | Out-Null } catch {}
        }
    }
    if ($GuardarConsolas) {
        foreach ($p in $script:procs) {
            try {
                $txt = ""
                try { if ($p.Out.IsCompleted) { $txt += $p.Out.Result } } catch {}
                try { if ($p.Err.IsCompleted) { $txt += $p.Err.Result } } catch {}
                [IO.File]::WriteAllText((Join-Path $p.Pasta ("consola-" + $p.Nome + ".txt")), $txt)
            } catch {}
        }
    }
    $script:procs = @()
    $script:prod = $null
}

# ------------------------------------------------------------------ utilidades

function Nova-Pasta([string]$Nome) {
    $pasta = Join-Path $testesRoot "dados-$Nome"
    if (Test-Path -LiteralPath $pasta) { Remove-Item -LiteralPath $pasta -Recurse -Force }
    New-Item -ItemType Directory -Path $pasta -Force | Out-Null
    return $pasta
}

function Wait-Condicao([scriptblock]$Cond, [int]$TimeoutSeg, [string]$Msg) {
    $fim = [DateTime]::Now.AddSeconds($TimeoutSeg)
    while ([DateTime]::Now -lt $fim) {
        if (& $Cond) { return $true }
        Start-Sleep -Milliseconds 200
    }
    Write-Host "  TIMEOUT ($TimeoutSeg s): $Msg" -ForegroundColor Red
    return $false
}

# eventos LIDER da partição k: último (por instante) -> @{ No; Termo; T }
function Get-Lider([int]$k, [string]$Pasta) {
    $melhor = $null
    foreach ($f in (Get-ChildItem -LiteralPath $Pasta -Filter "eventos-p$k-n*.txt" -File -ErrorAction SilentlyContinue)) {
        $no = [int]([regex]::Match($f.Name, 'n(\d+)\.txt').Groups[1].Value)
        foreach ($linha in (Get-Content -LiteralPath $f.FullName -ErrorAction SilentlyContinue)) {
            if ($linha -match '^(\d+);LIDER;p(\d+);termo=(\d+)') {
                $e = @{ No = $no; Termo = [long]$Matches[3]; T = [long]$Matches[1] }
                if (!$melhor -or $e.T -ge $melhor.T) { $melhor = $e }
            }
        }
    }
    return $melhor
}

function Wait-Lider([int]$k, [string]$Pasta, [long]$MinTermo = 1, [int]$TimeoutSeg = 30) {
    Wait-Condicao { (Get-Lider $k $Pasta) -and (Get-Lider $k $Pasta).Termo -ge $MinTermo } $TimeoutSeg `
        "lider de p$k com termo >= $MinTermo"
}

function Anotar-Falha([int]$k, [int]$i, [string]$Pasta) {
    $t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::AppendAllText((Join-Path $Pasta 'falhas.txt'), "p$k;n$i;$t`r`n", [Text.UTF8Encoding]::new($false))
}

function Set-Isolamento([int]$k, [int]$i, [string]$Pasta, [bool]$Isolar) {
    $f = Join-Path $Pasta "isolar-p$k-n$i"
    $t = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($Isolar) { Set-Content -LiteralPath $f -Value 'isolado' }
    else { Remove-Item -LiteralPath $f -ErrorAction Stop }
    [IO.File]::AppendAllText((Join-Path $Pasta 'isolamentos.txt'), "p$k;n$i;$(if ($Isolar) {'isolado'} else {'reparado'});$t`r`n", [Text.UTF8Encoding]::new($false))
}

function Matar-No([int]$k, [int]$i, [string]$Pasta, [bool]$Anotar = $true) {
    $alvo = $script:procs | Where-Object { $_.Nome -eq "p$k-n$i" } | Select-Object -First 1
    if (!$alvo) { throw "processo p$k-n$i nao existe" }
    $alvo.Proc.Kill()
    $alvo.Proc.WaitForExit(5000) | Out-Null
    if ($Anotar) { Anotar-Falha $k $i $Pasta }
}

function Invoke-Check([string]$Tipo, [string]$Nome, [string]$Pasta, [string[]]$Props = @(), [switch]$ComFalhas) {
    $args = @($Props) + @('-cp', (Join-Path $build 'raft04-no'), "raft04.$Tipo", '3', $Pasta)
    if ($ComFalhas -and (Test-Path -LiteralPath (Join-Path $Pasta 'falhas.txt'))) {
        $args += (Join-Path $Pasta 'falhas.txt')
    }
    $saida = & $script:java @args 2>&1 | Out-String
    $ficheiro = Join-Path $testesRoot "$Nome-$Tipo.txt"
    [IO.File]::WriteAllText($ficheiro, $saida, [Text.UTF8Encoding]::new($false))
    return $saida
}

# ------------------------------------------------------------------ resultados

$script:resultados = [System.Collections.Generic.List[object]]::new()
function Assert([string]$Nome, [bool]$Ok, [string]$Detalhe = '') {
    $script:resultados.Add([pscustomobject]@{ Teste = $Nome; Resultado = $(if ($Ok) { 'PASS' } else { 'FAIL' }); Detalhe = $Detalhe })
    Write-Host ("  [{0}] {1} {2}" -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Nome, $Detalhe) -ForegroundColor $(if ($Ok) { 'Green' } else { 'Red' })
}

function Info([string]$Nome, [string]$Valor) {
    $script:resultados.Add([pscustomobject]@{ Teste = $Nome; Resultado = 'INFO'; Detalhe = $Valor })
    Write-Host ("  [INFO] {0}: {1}" -f $Nome, $Valor) -ForegroundColor Cyan
}

# ------------------------------------------------------------------ cenários

function Cenario-Sanidade {
    $pasta = Nova-Pasta 'sanidade'
    Write-Host "`n===== CENÁRIO: sanidade (eleição, replicação, causalidade) =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) {
        Assert "sanidade: p$k elegeu líder" (Wait-Lider $k $pasta 1 30)
    }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 2
    foreach ($cmd in @('S01 21.0', 'S02 22.0', 'S05 21.5', 'S05 30.0', 'S01 25.0', 'S03 24.5')) {
        Enviar $cmd; Start-Sleep -Milliseconds 400
    }
    Start-Sleep -Seconds 6   # lotes com os registos (e o alarme do S05 30.0)
    Enviar 'S02 27.5'; Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' 'sanidade' $pasta -ComFalhas
    $cc = Invoke-Check 'CausalCheck' 'sanidade' $pasta
    Assert 'sanidade: sem termos com dois líderes' ($rc -match 'Termos com mais de um LIDER: 0')
    Assert 'sanidade: sem leituras perdidas' ($rc -match 'TOTAL de leituras perdidas: 0')
    Assert 'sanidade: coincidência final OK' ($rc -match 'TOTAL de seq divergentes: 0')
    Assert 'sanidade: CausalCheck OK' ($cc -match 'Resultado geral: OK')
    foreach ($k in 0..2) {
        $lider = Get-Lider $k $pasta
        if ($lider) { Info "sanidade: primeiro líder p$k" ("n$($lider.No), termo $($lider.Termo)") }
    }
    if ($rc -match 'p\d: (\d+) ms \(') { } # tempo até ao primeiro líder: extraído abaixo
    foreach ($m in [regex]::Matches($rc, 'p(\d): (\d+) ms \(n(\d+)')) {
        Info "sanidade: tempo até 1.º líder p$($m.Groups[1].Value)" "$($m.Groups[2].Value) ms (n$($m.Groups[3].Value))"
    }
}

function Cenario-9a {
    $pasta = Nova-Pasta '9a'
    Write-Host "`n===== ENSAIO 9(a): Figura 1 — falha do líder, eleição, TRUNCADO =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 2
    # duas leituras para p1 (S05 pertence a p1)
    Enviar 'S05 21.0'; Start-Sleep -Milliseconds 300
    Enviar 'S05 22.5'; Start-Sleep -Seconds 3   # aguarda um LOTE com as duas
    $lider = Get-Lider 1 $pasta
    $noAntigo = $lider.No
    Info '9a: líder inicial de p1' "n$noAntigo (termo $($lider.Termo))"
    # espera pelo LOTE que publicou as duas leituras (qualquer linha hb com
    # n >= 1) e, a seguir, pelo lote vazio seguinte: assim a 3.ª leitura cai
    # num ciclo novo e NÃO chega a ser publicada antes de o líder ser terminado
    $script:tLote = 0
    Wait-Condicao {
        $linhas = @(Get-Content (Join-Path $pasta "hb-p1-n$noAntigo.txt") -ErrorAction SilentlyContinue)
        for ($j = $linhas.Count - 1; $j -ge 0; $j--) {
            $f = $linhas[$j] -split ';'
            if ($f.Count -ge 7 -and ([long]$f[5]) -ge 1) { $script:tLote = [long]$f[2]; return $true }
        }
        return $false
    } 15 'lote com as duas leituras' | Out-Null
    $tLote = [long]((((Get-Content (Join-Path $pasta "hb-p1-n$noAntigo.txt") -ErrorAction SilentlyContinue | Select-Object -Last 1)) -split ';')[2])
    $espera = 2300 - ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $tLote)
    if ($espera -gt 0) { Start-Sleep -Milliseconds $espera }   # deixa passar o lote vazio seguinte
    Enviar 'S05 21.4'
    Start-Sleep -Milliseconds 700   # termina o líder menos de 1 s depois (registo fica por publicar)
    Matar-No 1 $noAntigo $pasta
    # leitura durante a eleição (perde-se)
    Enviar 'S05 21.1'
    $ok = Wait-Lider 1 $pasta ($lider.Termo + 1) 30
    Assert '9a: novo líder eleito após a falha' $ok
    Start-Sleep -Seconds 2
    Enviar 'S05 21.2'   # leitura depois de haver novo líder
    Start-Sleep -Seconds 4
    # volta a arrancar o antigo líder
    Start-No 1 $noAntigo $pasta | Out-Null
    $truncado = Wait-Condicao {
        (Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noAntigo.txt") -Pattern 'TRUNCADO' -SimpleMatch -ErrorAction SilentlyContinue)
    } 20 "TRUNCADO no antigo líder n$noAntigo"
    Assert '9a: antigo líder registou TRUNCADO' $truncado
    Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' '9a' $pasta -ComFalhas
    $cc = Invoke-Check 'CausalCheck' '9a' $pasta
    Assert '9a: sem termos com dois líderes' ($rc -match 'Termos com mais de um LIDER: 0')
    Assert '9a: TRUNCADO com de=3 e descartados=1' ($rc -match 'de=seq 3, descartados=1')
    Assert '9a: leitura durante a eleição perdida (21.1 ou 21.4)' ($rc -match '21\.1|21\.4')
    Assert '9a: coincidência final OK' ($rc -match 'TOTAL de seq divergentes: 0')
    Assert '9a: CausalCheck OK (V[k]==seq através das mudanças de líder)' ($cc -match 'Resultado geral: OK')
    foreach ($m in [regex]::Matches($rc, 'INDISPONIBILIDADE (\d+) ms')) {
        $v = [long]$m.Groups[1].Value
        Info '9a: indisponibilidade medida' "$v ms (previsto ]4000, 12200[ ms)"
        Assert '9a: indisponibilidade no intervalo previsto' ($v -gt 4000 -and $v -le 12200)
    }
}

function Cenario-9b([string]$Sufixo, [string[]]$Props) {
    $pasta = Nova-Pasta "9b-$Sufixo"
    New-Item -ItemType Directory -Path $pasta -Force | Out-Null
    $fixo = $Props -contains '-Draft04.eleicaoMin=6000'
    $rotulo = if ($fixo) { 'FIXO (6000 ms)' } else { 'aleatório [3Δ,6Δ]' }
    Write-Host "`n===== ENSAIO 9(b): prazo $rotulo — $Sufixo =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta -Props $Props | Out-Null } }
    $inicio = [DateTime]::Now
    $todos = $true
    foreach ($k in 0..2) { $todos = (Wait-Lider $k $pasta 1 70) -and $todos }
    $dur = [int]([DateTime]::Now - $inicio).TotalMilliseconds
    Assert "9b-${Sufixo}: todas as partições elegeram líder" $todos
    Info "9b-${Sufixo}: tempo até haver líder em p0/p1/p2" "$dur ms"
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' "9b-$Sufixo" $pasta -Props $Props
    foreach ($k in 0..2) {
        $m = [regex]::Match($rc, "p$k`: (\d+) ms \(n(\d+)")
        if ($m.Success) { Info "9b-${Sufixo}: tempo até 1.º líder p$k" "$($m.Groups[1].Value) ms" }
        $termos = ([regex]::Matches($rc, "  termo \d+:")).Count
        Info "9b-${Sufixo}: nº de termos observados (total)" "$termos"
        $div = ([regex]::Matches($rc, 'VOTO DIVIDIDO')).Count
        Info "9b-${Sufixo}: votos divididos" "$div"
    }
    Assert "9b-${Sufixo}: sem termos com dois líderes" ($rc -match 'Termos com mais de um LIDER: 0')
}

function Cenario-9c([string]$Sufixo) {
    $pasta = Nova-Pasta "9c-$Sufixo"
    New-Item -ItemType Directory -Path $pasta -Force | Out-Null
    Write-Host "`n===== ENSAIO 9(c): falha do líder durante rajada — $Sufixo =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 2
    $lider = Get-Lider 1 $pasta
    $noAntigo = $lider.No
    $termoAntigo = $lider.Termo
    Enviar 'rajada 300 10'
    Start-Sleep -Milliseconds 1200  # a MEIO da rajada (a rajada dura ~2 s)...
    Matar-No 1 $noAntigo $pasta
    # ... e continua a introduzir leituras durante a eleição (perdem-se)
    for ($j = 0; $j -lt 14; $j++) { Enviar "S05 2$($j + 20).7"; Start-Sleep -Milliseconds 900 }
    $ok = Wait-Lider 1 $pasta ($termoAntigo + 1) 30
    Assert "9c-${Sufixo}: novo líder após a falha" $ok
    foreach ($j in 0..4) { Enviar "S05 3$($j).1"; Start-Sleep -Milliseconds 800 }  # registadas pelo novo líder
    Start-Sleep -Seconds 10  # ... e volta a arrancar ~30 s depois
    Start-No 1 $noAntigo $pasta | Out-Null
    Wait-Condicao { (Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noAntigo.txt") -Pattern 'TRUNCADO' -SimpleMatch -ErrorAction SilentlyContinue) } 30 'TRUNCADO no antigo líder' | Out-Null
    Start-Sleep -Seconds 6
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' "9c-$Sufixo" $pasta -ComFalhas
    $cc = Invoke-Check 'CausalCheck' "9c-$Sufixo" $pasta
    Assert "9c-${Sufixo}: sem termos com dois líderes" ($rc -match 'Termos com mais de um LIDER: 0')
    Assert "9c-${Sufixo}: antigo líder truncou registos" ($rc -match 'TRUNCADO|de=seq \d+, descartados=\d+')
    Assert "9c-${Sufixo}: houve leituras perdidas" ($rc -notmatch 'TOTAL de leituras perdidas: 0')
    Assert "9c-${Sufixo}: coincidência final OK" ($rc -match 'TOTAL de seq divergentes: 0')
    Assert "9c-${Sufixo}: CausalCheck OK" ($cc -match 'Resultado geral: OK')
    foreach ($m in [regex]::Matches($rc, 'INDISPONIBILIDADE (\d+) ms')) {
        $v = [long]$m.Groups[1].Value
        Info "9c-${Sufixo}: indisponibilidade" "$v ms (previsto ]4000, 12200[ ms)"
        Assert "9c-${Sufixo}: indisponibilidade no intervalo previsto" ($v -gt 4000 -and $v -le 12200)
    }
    if ($rc -match 'p1: (\d+) leituras enviadas, (\d+) perdidas') {
        Info "9c-${Sufixo}: leituras p1" "enviadas=$($Matches[1]), perdidas=$($Matches[2])"
    }
}

function Cenario-9dLider {
    $pasta = Nova-Pasta '9d-lider'
    Write-Host "`n===== ENSAIO 9(d)-1: ISOLAMENTO do líder de p1 durante 30 s =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 1
    $lider = Get-Lider 1 $pasta
    $noIsolado = $lider.No
    $termoIsolado = $lider.Termo
    Enviar 'rajada 300 10'
    Start-Sleep -Milliseconds 1500   # isola no Meio da rajada
    Set-Isolamento 1 $noIsolado $pasta $true
    Info '9d: líder isolado' "n$noIsolado (termo $termoIsolado)"
    # leituras DURANTE o isolamento: chegam ao líder isolado (tópico in não é
    # afetado) E ao novo líder — ambos as registam; as do isolado são truncadas
    for ($j = 0; $j -lt 18; $j++) { Enviar "S05 2$($j + 30).3"; Start-Sleep -Milliseconds 1000 }
    $ok = Wait-Lider 1 $pasta ($termoIsolado + 1) 30
    Assert '9d-1: maioria elegeu novo líder durante o isolamento' $ok
    $novo = Get-Lider 1 $pasta
    Info '9d: dois líderes simultâneos' "isolado n$noIsolado (termo $termoIsolado) + maioria n$($novo.No) (termo $($novo.Termo))"
    # isola o líder durante 30 s: elege a maioria, espera, e repara
    Start-Sleep -Seconds (31 - 18)
    Set-Isolamento 1 $noIsolado $pasta $false
    $dem = Wait-Condicao {
        (Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noIsolado.txt") -Pattern 'DEMISSAO' -SimpleMatch -ErrorAction SilentlyContinue)
    } 20 'DEMISSAO do líder isolado após a reparação'
    Assert '9d-1: antigo líder demitiu-se após a reparação' $dem
    $trunc = Wait-Condicao {
        (Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noIsolado.txt") -Pattern 'TRUNCADO' -SimpleMatch -ErrorAction SilentlyContinue)
    } 20 'TRUNCADO no antigo líder'
    Assert '9d-1: antigo líder truncou os registos do isolamento' $trunc
    Start-Sleep -Seconds 5
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' '9d-lider' $pasta
    $cc = Invoke-Check 'CausalCheck' '9d-lider' $pasta
    Assert '9d-1: sem termos com dois líderes' ($rc -match 'Termos com mais de um LIDER: 0')
    Assert '9d-1: coincidência final OK (registos reconciliados)' ($rc -match 'TOTAL de seq divergentes: 0')
    Assert '9d-1: CausalCheck OK' ($cc -match 'Resultado geral: OK')
    Assert '9d-1: houve truncagem no antigo líder' ($rc -match 'de=seq \d+, descartados=(0|[1-9]\d*)')
}

function Cenario-9dSeguidor {
    $pasta = Nova-Pasta '9d-seguidor'
    Write-Host "`n===== ENSAIO 9(d)-2: ISOLAMENTO de um SEGUIDOR de p1 durante 60 s =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    $lider = Get-Lider 1 $pasta
    $noLider = $lider.No
    $termoLider = $lider.Termo
    $noSeguidor = @(1, 2, 3) | Where-Object { $_ -ne $noLider } | Select-Object -First 1
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 1
    Set-Isolamento 1 $noSeguidor $pasta $true
    Info '9d-2: seguidor isolado' "n$noSeguidor (líder n$noLider no termo $termoLider)"
    Start-Sleep -Seconds 60
    # termos e candidaturas do seguidor isolado
    $cands = @(Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noSeguidor.txt") -Pattern ';CANDIDATO;' -ErrorAction SilentlyContinue)
    Info '9d-2: candidaturas do seguidor isolado' "$($cands.Count) (termos crescentes)"
    $termoMax = 0
    foreach ($c in $cands) { if ($c.Line -match 'termo=(\d+)') { $termoMax = [Math]::Max($termoMax, [long]$Matches[1]) } }
    Info '9d-2: termo atingido pelo seguidor isolado' $termoMax
    Set-Isolamento 1 $noSeguidor $pasta $false
    $dem = Wait-Condicao {
        (Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$noLider.txt") -Pattern 'DEMISSAO' -SimpleMatch -ErrorAction SilentlyContinue)
    } 30 'DEMISSAO do líder saudável provocada pelo regresso do seguidor'
    Assert '9d-2: o regresso do seguidor isolado demitiu o líder saudável' $dem
    $ok = Wait-Lider 1 $pasta ($termoMax) 40
    Assert '9d-2: nova eleição após o regresso' $ok
    Start-Sleep -Seconds 5
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' '9d-seguidor' $pasta
    Assert '9d-2: sem termos com dois líderes' ($rc -match 'Termos com mais de um LIDER: 0')
    Assert '9d-2: termo nunca diminuiu' ($rc -match 'OK em todos')
}

function Cenario-9e {
    $pasta = Nova-Pasta '9e'
    Write-Host "`n===== ENSAIO 9(e): falha de DOIS nós de p1, leituras durante 30 s =====" -ForegroundColor Magenta
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 2
    $lider = Get-Lider 1 $pasta
    $sobrevive = @(1, 2, 3) | Where-Object { $_ -ne $lider.No } | Select-Object -First 1
    $paraArrancar = @(1, 2, 3) | Where-Object { $_ -ne $lider.No -and $_ -ne $sobrevive } | Select-Object -First 1
    $outroMorto = @(1, 2, 3) | Where-Object { $_ -notin @($lider.No, $sobrevive, $paraArrancar) } | Select-Object -First 1
    Info '9e: falha de dois nós' "sobrevivente n$sobrevive; terminados n$($lider.No) (líder) e n$outroMorto"
    Matar-No 1 $lider.No $pasta $true
    Matar-No 1 (@(1, 2, 3) | Where-Object { $_ -notin @($lider.No, $sobrevive) } | Select-Object -First 1) $pasta $true
    # leituras durante 30 s para uma partição sem maioria
    for ($j = 0; $j -lt 15; $j++) { Enviar "S05 2$($j + 10).5"; Start-Sleep -Seconds 2 }
    $semLider = Wait-Condicao { -not (Get-Lider 1 $pasta) -or (Get-Lider 1 $pasta).No -ne $sobrevive } 5 'sobrevivente não elegeu'
    $cands = @(Select-String -LiteralPath (Join-Path $pasta "eventos-p1-n$sobrevive.txt") -Pattern ';CANDIDATO;' -ErrorAction SilentlyContinue)
    Info '9e: candidaturas do sobrevivente sem maioria' "$($cands.Count)"
    Assert '9e: o sobrevivente nunca se elegeu sem maioria' ((Get-Lider 1 $pasta) -eq $null -or (Get-Lider 1 $pasta).No -ne $sobrevive)
    # volta a arrancar um dos terminados
    $antes = [DateTime]::Now
    Start-No 1 $paraArrancar $pasta | Out-Null
    $ok = Wait-Lider 1 $pasta 2 40
    $dur = [int]([DateTime]::Now - $antes).TotalMilliseconds
    Assert '9e: líder eleito após o regresso de um nó' $ok
    Info '9e: tempo até haver líder (desde o reinício)' "$dur ms"
    Start-Sleep -Seconds 4
    Enviar 'S05 29.9'; Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' '9e' $pasta -ComFalhas
    Assert '9e: leituras da janela sem maioria perdidas' ($rc -notmatch 'TOTAL de leituras perdidas: 0')
    Assert '9e: sem termos com dois líderes' ($rc -match 'Termos com mais de um LIDER: 0')
}

function Cenario-Falhas {
    # ---- f1: maioria como votes.size() >= G/2
    $pasta = Nova-Pasta 'f1-maioria'
    Write-Host "`n===== ENSAIO 10 f1: maioria calculada como votes.size() >= G/2 =====" -ForegroundColor Magenta
    $props = @('-Draft04.falhaMaioria=true', '-Draft04.eleicaoMin=6000', '-Draft04.eleicaoMax=6000')
    foreach ($i in 1..3) { Start-No 1 $i $pasta -Props $props | Out-Null }
    Start-Produtor $pasta -Props @('-Draft04.eleicaoMin=6000', '-Draft04.eleicaoMax=6000') | Out-Null
    Start-Sleep -Seconds 9   # todos tickam aos 6 s: cada um promove-se a líder
    Enviar 'S05 21.0'; Start-Sleep -Milliseconds 300
    Enviar 'S05 21.1'; Start-Sleep -Milliseconds 300
    Enviar 'S05 21.2'; Start-Sleep -Seconds 5
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' 'f1-maioria' $pasta -Props $props
    $cc = Invoke-Check 'CausalCheck' 'f1-maioria' $pasta
    Assert 'f1: detetada — termos com MAIS de um líder' ($rc -notmatch 'Termos com mais de um LIDER: 0')
    Assert 'f1: eventos ERRO dois líderes' ($rc -match "Eventos ERRO 'dois lideres no termo': [1-9]")
    Assert 'f1: registos divergem entre nós' ($rc -notmatch 'TOTAL de seq divergentes: 0')
    Assert 'f1: CausalCheck deteta erros' ($cc -notmatch 'Resultado geral: OK')

    # ---- f3: voto sem a restrição upToDate (o nó atrasado consegue ser eleito)
    $pasta = Nova-Pasta 'f3-uptodate'
    Write-Host "`n===== ENSAIO 10 f3: voto concedido sem a verificação upToDate =====" -ForegroundColor Magenta
    $f = @('-Draft04.falhaUpToDate=true')
    # A (n1): prazo 6 s -> líder inicial; B (n2) e C (n3): prazo 12 s
    Start-No 1 1 $pasta -Props ($f + @('-Draft04.eleicaoMin=6000', '-Draft04.eleicaoMax=6000')) | Out-Null
    Start-No 1 2 $pasta -Props ($f + @('-Draft04.eleicaoMin=12000', '-Draft04.eleicaoMax=12000')) | Out-Null
    Start-No 1 3 $pasta -Props ($f + @('-Draft04.eleicaoMin=12000', '-Draft04.eleicaoMax=12000')) | Out-Null
    Wait-Lider 1 $pasta 1 30 | Out-Null
    $lider = Get-Lider 1 $pasta
    Assert 'f3: pré-condição — líder inicial é n1' ($lider.No -eq 1)
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 1
    Enviar 'S05 21.0'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.1'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.2'; Start-Sleep -Seconds 3
    # n3 morre e perde os registos 4..6
    Matar-No 1 3 $pasta $false
    Enviar 'S05 21.3'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.4'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.5'; Start-Sleep -Seconds 3
    # o líder n1 morre; n3 (ATRASADO) volta a arrancar com prazo curto
    Matar-No 1 1 $pasta $true
    Start-No 1 3 $pasta -Props ($f + @('-Draft04.eleicaoMin=6000', '-Draft04.eleicaoMax=6000')) | Out-Null
    $ok = Wait-Lider 1 $pasta 2 40
    $novo = Get-Lider 1 $pasta
    Info 'f3: líder do termo 2' "n$($novo.No)"
    Assert 'f3: o nó ATRASADO (n3) foi eleito sem a restrição' ($novo.No -eq 3)
    Enviar 'S05 21.6'; Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' 'f3-uptodate' $pasta -ComFalhas
    $cc = Invoke-Check 'CausalCheck' 'f3-uptodate' $pasta
    Assert 'f3: houve truncagem no nó atualizado (de=seq 4)' ($rc -match 'de=seq 4')
    # os registos 4..6 do termo 1 já não estão no registo dos nós vivos (só no
    # ficheiro do nó morto n1): foram descartados pelo registo do novo líder
    $vivos = @(2, 3) | ForEach-Object { Get-Content (Join-Path $pasta "no-p1-n$_.txt") -ErrorAction SilentlyContinue } | Where-Object { $_ -match '^4;1;' }
    Assert 'f3: registos do líder anterior descartados dos nós vivos' ($vivos.Count -eq 0)
    Assert 'f3: a ELEIÇÃO continuou segura (sem dois líderes no mesmo termo)' ($rc -match 'Termos com mais de um LIDER: 0')

    # ---- f5: novo líder com L e V a zero
    $pasta = Nova-Pasta 'f5-lvzero'
    Write-Host "`n===== ENSAIO 10 f5: novo líder inicializa L e V a zero =====" -ForegroundColor Magenta
    $props = @('-Draft04.falhaLVzero=true')
    foreach ($i in 1..3) { Start-No 1 $i $pasta -Props $props | Out-Null }
    Wait-Lider 1 $pasta 1 30 | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 1
    Enviar 'S05 21.0'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.1'; Start-Sleep -Milliseconds 200
    Enviar 'S05 21.2'; Start-Sleep -Seconds 3
    $lider = Get-Lider 1 $pasta
    Matar-No 1 $lider.No $pasta $true
    $ok = Wait-Lider 1 $pasta ($lider.Termo + 1) 30
    Assert 'f5: novo líder eleito' $ok
    Enviar 'S05 21.3'; Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $cc = Invoke-Check 'CausalCheck' 'f5-lvzero' $pasta
    Assert 'f5: CausalCheck deteta V[k] != seq (registos inconsistentes)' ($cc -match '== seq: no-')
    Assert 'f5: resultado geral com erros' ($cc -match 'ERROS DETETADOS')

    # ---- f2: termo e votedFor só em memória (verificação de mecanismo)
    $pasta = Nova-Pasta 'f2-persistencia'
    Write-Host "`n===== ENSAIO 10 f2: termo e votedFor mantidos só em memória =====" -ForegroundColor Magenta
    $props = @('-Draft04.falhaPersistencia=true')
    foreach ($i in 1..3) { Start-No 1 $i $pasta -Props $props | Out-Null }
    Wait-Lider 1 $pasta 1 30 | Out-Null
    Start-Sleep -Seconds 2
    Parar-Tudo -GuardarConsolas
    $temEstado = @(Get-ChildItem -LiteralPath $pasta -Filter 'estado-p1-n*.txt' -ErrorAction SilentlyContinue).Count -gt 0
    Assert 'f2: o estado persistente NÃO foi gravado (mecanismo da falha ativo)' (-not $temEstado)

    # ---- f4: seguidor que aplica lotes de termo inferior (fumo: não rebenta)
    $pasta = Nova-Pasta 'f4-termoinferior'
    Write-Host "`n===== ENSAIO 10 f4: seguidor que aplica lotes de termo inferior =====" -ForegroundColor Magenta
    $props = @('-Draft04.falhaTermoInferior=true')
    foreach ($k in 0..2) { foreach ($i in 1..3) { Start-No $k $i $pasta -Props $props | Out-Null } }
    foreach ($k in 0..2) { Wait-Lider $k $pasta 1 30 | Out-Null }
    Start-Processador $pasta | Out-Null
    Start-Produtor $pasta | Out-Null
    Start-Sleep -Seconds 1
    Enviar 'S05 21.0'; Start-Sleep -Seconds 3
    $lider = Get-Lider 1 $pasta
    Matar-No 1 $lider.No $pasta $true
    $ok = Wait-Lider 1 $pasta ($lider.Termo + 1) 30
    Assert 'f4: sistema continua a funcionar com a falha (re-eleção)' $ok
    Enviar 'S05 21.1'; Start-Sleep -Seconds 4
    Parar-Tudo -GuardarConsolas
    $rc = Invoke-Check 'RaftCheck' 'f4-termoinferior' $pasta -ComFalhas
    $cc = Invoke-Check 'CausalCheck' 'f4-termoinferior' $pasta
    Assert 'f4: eleição mantém-se segura' ($rc -match 'Termos com mais de um LIDER: 0')
}

# ------------------------------------------------------------------ execução

$lista = if ($Cenarios -eq 'all') { @('sanidade', '9a', '9b', '9c', '9d', '9e', 'falhas') } else { $Cenarios.Split(',') | ForEach-Object { $_.Trim() } }

Build-Projects
Ensure-Kubo

$inicioTotal = [DateTime]::Now
foreach ($c in $lista) {
    switch ($c) {
        'sanidade' { Cenario-Sanidade }
        '9a' { Cenario-9a }
        '9b' {
            Cenario-9b 'aleatorio-1' @()
            Cenario-9b 'fixo-1' @('-Draft04.eleicaoMin=6000', '-Draft04.eleicaoMax=6000')
        }
        '9c' {
            Cenario-9c '1'
            Cenario-9c '2'
        }
        '9d' {
            Cenario-9dLider
            Cenario-9dSeguidor
        }
        '9e' { Cenario-9e }
        'falhas' { Cenario-Falhas }
        default { Write-Host "Cenário desconhecido: $c (usa: sanidade, 9a, 9b, 9c, 9d, 9e, falhas)" -ForegroundColor Red }
    }
    Parar-Tudo
    Start-Sleep -Seconds 2
}

$durTotal = [int]([DateTime]::Now - $inicioTotal).TotalSeconds
$fails = @($script:resultados | Where-Object { $_.Resultado -eq 'FAIL' }).Count

# resumo
$linhas = @()
$linhas += "# RESUMO DOS TESTES AUTOMÁTICOS — COMINDIR05"
$linhas += ""
$linhas += "Data: $([DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss'))  ·  duração: $durTotal s"
$linhas += ""
$linhas += "| Teste | Resultado | Detalhe |"
$linhas += "| --- | --- | --- |"
foreach ($r in $script:resultados) {
    $linhas += "| $($r.Teste) | $($r.Resultado) | $($r.Detalhe) |"
}
$linhas += ""
$linhas += "Total: $($script:resultados.Count) verificações, $fails FAIL."
[IO.File]::WriteAllLines((Join-Path $testesRoot 'RESUMO-TESTES.md'), $linhas, [Text.UTF8Encoding]::new($false))

Write-Host "`n====================================================================" -ForegroundColor Magenta
Write-Host "RESUMO: $($script:resultados.Count) verificações | PASS: $($script:resultados.Count - $fails) | FAIL: $fails" -ForegroundColor $(if ($fails) { 'Red' } else { 'Green' })
Write-Host "Relatório: $(Join-Path $testesRoot 'RESUMO-TESTES.md')"
Write-Host "Saídas dos verificadores e pastas de dados: $testesRoot"
Write-Host "====================================================================" -ForegroundColor Magenta

if (!$ManterKubo -and $script:kubo) {
    Write-Host 'A encerrar o Kubo iniciado pelo teste (usa -ManterKubo para o manter)...'
    try { Stop-Process -Id $script:kubo.Id -Force -ErrorAction Stop } catch {}
}
exit $(if ($fails) { 1 } else { 0 })
