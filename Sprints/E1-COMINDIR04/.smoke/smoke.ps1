# Teste de fumo automatizado COMINDIR04 (Passo 10 da especificacao).
# Reproduce: S01 -> LOTE -> S04 -> LOTE vazio -> S01 + kill lider p0 < 1s
#            -> SUSPEITA em todos -> reinicio -> RETOMA + recuperacao TCP.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent   # .../E1-COMINDIR04
$data = Join-Path $root 'dados-test'
$build = Join-Path $env:TEMP 'opencode\build'
$ipfs = 'C:\ipfs\ipfs.exe'
$repo = Join-Path $env:TEMP 'opencode\ipfs-repo-smoke'
$api = 'http://127.0.0.1:5001/api/v0'
$java = (Get-Command java.exe).Source

function Log($m) { Write-Host ("[SMOKE {0:HH:mm:ss}] {1}" -f (Get-Date), $m) }

# 0. Pasta de dados fresca
if (Test-Path $data) { Remove-Item -Recurse -Force $data }
New-Item -ItemType Directory -Path $data | Out-Null

# 1. Kubo
$env:IPFS_PATH = $repo
if (!(Test-Path (Join-Path $repo 'config'))) {
    & $ipfs init | Out-Null
    & $ipfs config --json Pubsub.Enabled 'true' | Out-Null
    & $ipfs config --json Bootstrap '[]' | Out-Null
    & $ipfs config --json Addresses.Swarm '[]' | Out-Null
    & $ipfs config --json Discovery.MDNS.Enabled 'false' | Out-Null
    & $ipfs config --json Peering.Peers '[]' | Out-Null
    & $ipfs config Routing.Type none | Out-Null
}
$kuboProc = Start-Process -FilePath $ipfs -ArgumentList 'daemon','--enable-pubsub-experiment' -WindowStyle Hidden -PassThru
Log "Kubo a arrancar (pid $($kuboProc.Id))..."
$prazo = [DateTime]::UtcNow.AddSeconds(45)
$pronto = $false
while ([DateTime]::UtcNow -lt $prazo) {
    try {
        $req = [Net.HttpWebRequest]::Create("$api/version"); $req.Method='POST'; $req.Proxy=$null; $req.Timeout=1000
        $resp = $req.GetResponse(); $resp.Dispose(); $pronto = $true; break
    } catch { Start-Sleep -Milliseconds 800 }
}
if (!$pronto) { throw 'Kubo nao ficou pronto.' }
Log 'Kubo pronto.'

# 2. Arranque dos processos (replicas -> lideres -> processador -> produtor)
$procs = @{}
$global:procLogs = @{}
function Start-Java($nome, $argList) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = ($argList -join ' ')
    $psi.WorkingDirectory = $data
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    if ($nome -eq 'produtor') { $psi.RedirectStandardInput = $true }
    $p = [System.Diagnostics.Process]::Start($psi)
    $outLog = Join-Path $data ("consola-$nome.txt")
    $global:procLogs[$p.Id] = $outLog
    Register-ObjectEvent $p OutputDataReceived -MessageData $outLog -Action {
        if ($EventArgs.Data) { [IO.File]::AppendAllText($Event.MessageData, $EventArgs.Data + "`r`n") }
    } | Out-Null
    Register-ObjectEvent $p ErrorDataReceived -MessageData $outLog -Action {
        if ($EventArgs.Data) { [IO.File]::AppendAllText($Event.MessageData, $EventArgs.Data + "`r`n") }
    } | Out-Null
    $p.BeginOutputReadLine()
    $p.BeginErrorReadLine()
    $procs[$nome] = $p
    return $p
}

$periodo = '-Drep04.periodo=5000'
$fator = '-Drep04.fator=3'

Start-Java 'replica1' @($periodo,$fator,'-cp',"$build\rep04-replica",'rep04.PartitionReplica','1','0,1',$api)
Start-Java 'replica2' @($periodo,$fator,'-cp',"$build\rep04-replica",'rep04.PartitionReplica','2','1,2',$api)
Start-Java 'replica3' @($periodo,$fator,'-cp',"$build\rep04-replica",'rep04.PartitionReplica','3','2,0',$api)
Log 'Replicas lancadas.'; Start-Sleep -Seconds 3

$l0 = Start-Java 'lider0' @($periodo,$fator,'-cp',"$build\rep04-lider",'rep04.PartitionLeader','0','3','127.0.0.1',$api)
Start-Java 'lider1' @($periodo,$fator,'-cp',"$build\rep04-lider",'rep04.PartitionLeader','1','3','127.0.0.1',$api)
Start-Java 'lider2' @($periodo,$fator,'-cp',"$build\rep04-lider",'rep04.PartitionLeader','2','3','127.0.0.1',$api)
Log 'Lideres lancados.'; Start-Sleep -Seconds 3

Start-Java 'processador' @($periodo,$fator,'-cp',"$build\rep04-processador",'rep04.AlarmProcessor','3',$api)
Log 'Processador lancado.'; Start-Sleep -Seconds 3

$prod = Start-Java 'produtor' @($periodo,$fator,'-cp',"$build\rep04-produtor",'rep04.Producer','3',$api)
Log 'Produtor lancado.'; Start-Sleep -Seconds 3

function Enviar($linha) {
    $prod.StandardInput.WriteLine($linha)
    $prod.StandardInput.Flush()
    Log "PRODUTOR> $linha"
}

# 3. Cenario
Enviar 'S01 21.0'
Start-Sleep -Seconds 6          # espera pelo LOTE com o registo 1
Enviar 'S04 22.5'
Start-Sleep -Seconds 6          # espera por um LOTE vazio
Enviar 'S01 21.4'
Start-Sleep -Milliseconds 700   # matar o lider p0 EM MENOS DE 1 s

$instanteFalha = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
Add-Content -Path (Join-Path $data 'falhas.txt') -Value "p0;$instanteFalha"
if (!$l0.HasExited) { taskkill /F /T /PID $l0.Id | Out-Null; Log "Lider p0 morto em $instanteFalha" } else { Log 'AVISO: lider p0 ja tinha terminado!' }

Log 'A espera 25 s pela deteccao da SUSPEITA em todos os participantes...'
Start-Sleep -Seconds 25

# 4. Reiniciar o lider p0
Log 'A reiniciar o lider p0...'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java
$psi.Arguments = ($periodo,$fator,'-cp',"$build\rep04-lider",'rep04.PartitionLeader','0','3','127.0.0.1',$api) -join ' '
$psi.WorkingDirectory = $data
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$l0b = [System.Diagnostics.Process]::Start($psi)
$procs['lider0b'] = $l0b
Start-Sleep -Seconds 12

# 5. Parar tudo
Log 'A parar os processos...'
foreach ($p in $procs.Values) { if (!$p.HasExited) { taskkill /F /T /PID $p.Id | Out-Null } }
Stop-Process -Id $kuboProc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 6. Resumo dos eventos observados
Log '=== EVENTOS OBSERVADOS NAS CONSOLAS ==='
Select-String -Path (Join-Path $data 'consola-*.txt') -Pattern 'SUSPEITA|REINICIO|RETOMA|RECUPERADO|RECUPERACAO|HB_PERDIDO|HEARTBEAT' |
    ForEach-Object { "$($_.Filename): $($_.Line)" }

Log '=== HEARTBEATCHECK ==='
& $java -cp "$build\rep04-replica" rep04.HeartbeatCheck 3 $data (Join-Path $data 'falhas.txt')

Log '=== CAUSALCHECK ==='
& $java -cp "$build\rep04-replica" rep04.CausalCheck 3 $data

Log 'Concluido. Ficheiros em ' + $data
