# Painel de controlo COMINDIR04 — janela com botões para arrancar/parar
# o sistema e gerar relatórios, sem usar a linha de comandos.
#
# Execução: duplo clique em painel.cmd (na raiz do sprint).

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# Localizar o script principal (pasta própria ou pasta pai)
$raiz = $PSScriptRoot
if (-not (Test-Path (Join-Path $raiz 'iniciar-comindir04.ps1'))) {
    $raiz = Split-Path $raiz -Parent
}
$scriptPrincipal = Join-Path $raiz 'iniciar-comindir04.ps1'

if (-not (Test-Path $scriptPrincipal)) {
    [System.Windows.Forms.MessageBox]::Show(
        'Não encontrei iniciar-comindir04.ps1 nem nesta pasta nem na pasta pai.',
        'Painel COMINDIR04') | Out-Null
    exit 1
}

# ---------------------------------------------------------------
# Paleta e estilos
# ---------------------------------------------------------------
$corFundo      = [System.Drawing.Color]::FromArgb(241, 245, 249)
$corCabecalho  = [System.Drawing.Color]::FromArgb(15, 23, 42)
$corVerde      = [System.Drawing.Color]::FromArgb(22, 163, 74)
$corVerdeHover = [System.Drawing.Color]::FromArgb(21, 128, 61)
$corVermelho   = [System.Drawing.Color]::FromArgb(220, 38, 38)
$corVermHover  = [System.Drawing.Color]::FromArgb(185, 28, 28)
$corAzul       = [System.Drawing.Color]::FromArgb(37, 99, 235)
$corAzulHover  = [System.Drawing.Color]::FromArgb(29, 78, 216)
$corNeutro     = [System.Drawing.Color]::FromArgb(100, 116, 139)
$corNeutroHover= [System.Drawing.Color]::FromArgb(71, 85, 105)
$corTextoClaro = [System.Drawing.Color]::White

function Estilizar-Botao([System.Windows.Forms.Button]$b, [System.Drawing.Color]$fundo, [System.Drawing.Color]$hover, [bool]$grande = $false) {
    $b.FlatStyle     = 'Flat'
    $b.FlatAppearance.BorderSize = 0
    $b.FlatAppearance.MouseOverBackColor = $hover
    $b.BackColor     = $fundo
    $b.ForeColor     = $corTextoClaro
    $b.Cursor        = 'Hand'
    if ($grande) {
        $b.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 11)
        $b.Height = 44
    } else {
        $b.Font = New-Object System.Drawing.Font('Segoe UI', 9, [System.Drawing.FontStyle]::Bold)
        $b.Height = 34
    }
}

function Novo-Grupo([string]$titulo, [int]$x, [int]$y, [int]$w, [int]$h) {
    $g = New-Object System.Windows.Forms.GroupBox
    $g.Text = $titulo
    $g.Font = New-Object System.Drawing.Font('Segoe UI', 9, [System.Drawing.FontStyle]::Bold)
    $g.ForeColor = [System.Drawing.Color]::FromArgb(51, 65, 85)
    $g.Location = New-Object System.Drawing.Point($x, $y)
    $g.Size = New-Object System.Drawing.Size($w, $h)
    return $g
}

function Novo-Rotulo([System.Windows.Forms.Control]$pai, [string]$texto, [int]$x, [int]$y) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $texto
    $l.Location = New-Object System.Drawing.Point($x, $y)
    $l.AutoSize = $true
    $l.Font = New-Object System.Drawing.Font('Segoe UI', 9)
    $l.ForeColor = [System.Drawing.Color]::FromArgb(71, 85, 105)
    $pai.Controls.Add($l)
    return $l
}

function Novo-Campo([System.Windows.Forms.Control]$pai, [string]$conteudo, [int]$x, [int]$y, [int]$w = 90) {
    $t = New-Object System.Windows.Forms.TextBox
    $t.Text = $conteudo
    $t.Location = New-Object System.Drawing.Point($x, $y - 3)
    $t.Width = $w
    $t.Font = New-Object System.Drawing.Font('Segoe UI', 9)
    $pai.Controls.Add($t)
    return $t
}

# ---------------------------------------------------------------
# Formulário
# ---------------------------------------------------------------
$form = New-Object System.Windows.Forms.Form
$form.Text = 'COMINDIR04 · Painel de controlo'
$form.Size = New-Object System.Drawing.Size(680, 700)
$form.StartPosition = 'CenterScreen'
$form.BackColor = $corFundo
$form.Font = New-Object System.Drawing.Font('Segoe UI', 9)

# Cabeçalho
$cabecalho = New-Object System.Windows.Forms.Panel
$cabecalho.Location = New-Object System.Drawing.Point(0, 0)
$cabecalho.Size = New-Object System.Drawing.Size(680, 56)
$cabecalho.BackColor = $corCabecalho
$form.Controls.Add($cabecalho)

$titulo = New-Object System.Windows.Forms.Label
$titulo.Text = '⬡  COMINDIR04 — Painel de controlo'
$titulo.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 13)
$titulo.ForeColor = $corTextoClaro
$titulo.Location = New-Object System.Drawing.Point(18, 14)
$titulo.AutoSize = $true
$cabecalho.Controls.Add($titulo)

$subtitulo = New-Object System.Windows.Forms.Label
$subtitulo.Text = 'Batimentos · deteção de falhas · recuperação por TCP'
$subtitulo.Font = New-Object System.Drawing.Font('Segoe UI', 8.5)
$subtitulo.ForeColor = [System.Drawing.Color]::FromArgb(148, 163, 184)
$subtitulo.Location = New-Object System.Drawing.Point(438, 22)
$subtitulo.AutoSize = $true
$cabecalho.Controls.Add($subtitulo)

# ----- Grupo: parâmetros do ensaio -----
$grupoEnsaio = Novo-Grupo 'Parâmetros do ensaio' 15 66 645 66
$form.Controls.Add($grupoEnsaio)

Novo-Rotulo $grupoEnsaio 'Pasta de dados:' 15 27 | Out-Null
$txtPasta = Novo-Campo $grupoEnsaio 'dados-n3' 105 27 130
Novo-Rotulo $grupoEnsaio 'Período HB (ms):' 260 27 | Out-Null
$txtPeriodo = Novo-Campo $grupoEnsaio '5000' 360 27 55
Novo-Rotulo $grupoEnsaio 'Fator timeout:' 435 27 | Out-Null
$txtFator = Novo-Campo $grupoEnsaio '3' 530 27 35

# ----- Grupo: sistema -----
$grupoSistema = Novo-Grupo 'Sistema' 15 142 645 78
$form.Controls.Add($grupoSistema)

$btnArrancar = New-Object System.Windows.Forms.Button
$btnArrancar.Text = '▶  ARRANCAR SISTEMA'
$btnArrancar.Location = New-Object System.Drawing.Point(15, 24)
$btnArrancar.Width = 305
$grupoSistema.Controls.Add($btnArrancar)
Estilizar-Botao $btnArrancar $corVerde $corVerdeHover $true

$btnParar = New-Object System.Windows.Forms.Button
$btnParar.Text = '■  PARAR TUDO'
$btnParar.Location = New-Object System.Drawing.Point(330, 24)
$btnParar.Width = 300
$grupoSistema.Controls.Add($btnParar)
Estilizar-Botao $btnParar $corVermelho $corVermHover $true

# ----- Grupo: reabrir processos -----
$grupoReabrir = Novo-Grupo 'Reabrir um processo individual (p. ex. depois de matar um líder)' 15 230 645 96
$form.Controls.Add($grupoReabrir)

function Botao-Reabrir([string]$texto, [int]$x, [int]$y) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $texto
    $b.Location = New-Object System.Drawing.Point($x, $y)
    $b.Width = 198
    $grupoReabrir.Controls.Add($b)
    Estilizar-Botao $b $corNeutro $corNeutroHover
    return $b
}

$btnLider0 = Botao-Reabrir 'Líder p0' 15 26
$btnLider1 = Botao-Reabrir 'Líder p1' 223 26
$btnLider2 = Botao-Reabrir 'Líder p2' 431 26
$btnProc   = Botao-Reabrir 'Processador' 15 26
$btnProd   = Botao-Reabrir 'Produtor' 223 26
$btnProc.Location  = New-Object System.Drawing.Point(15, 26)
$btnProd.Location  = New-Object System.Drawing.Point(223, 26)

# ----- Grupo: relatórios -----
$grupoRelatorios = Novo-Grupo 'Relatórios' 15 336 645 300
$form.Controls.Add($grupoRelatorios)

$btnRelatorios = New-Object System.Windows.Forms.Button
$btnRelatorios.Text = '⟳  Gerar relatórios  (HeartbeatCheck + CausalCheck)'
$btnRelatorios.Location = New-Object System.Drawing.Point(15, 24)
$btnRelatorios.Width = 615
$grupoRelatorios.Controls.Add($btnRelatorios)
Estilizar-Botao $btnRelatorios $corAzul $corAzulHover $true

$saida = New-Object System.Windows.Forms.RichTextBox
$saida.ReadOnly = $true
$saida.BackColor = [System.Drawing.Color]::FromArgb(15, 23, 42)
$saida.ForeColor = [System.Drawing.Color]::FromArgb(226, 232, 240)
$saida.Font = New-Object System.Drawing.Font('Consolas', 8.5)
$saida.Location = New-Object System.Drawing.Point(15, 82)
$saida.Size = New-Object System.Drawing.Size(615, 200)
$saida.BorderStyle = 'None'
$grupoRelatorios.Controls.Add($saida)

# ----- Lógica -----
function Log-Painel([string]$mensagem, [System.Drawing.Color]$cor = $null) {
    $saida.SelectionStart = $saida.TextLength
    $saida.SelectionColor = [System.Drawing.Color]::FromArgb(148, 163, 184)
    $saida.AppendText("[" + (Get-Date -Format 'HH:mm:ss') + "] ")
    $saida.SelectionColor = if ($cor) { $cor } else { [System.Drawing.Color]::FromArgb(226, 232, 240) }
    $saida.AppendText("$mensagem`r`n")
    $saida.ScrollToCaret()
}

function Obter-Parametros {
    return @(
        '-PastaDados', $txtPasta.Text.Trim(),
        '-PeriodoHb', $txtPeriodo.Text.Trim(),
        '-FatorTimeout', $txtFator.Text.Trim()
    )
}

function Lancar-Script([string[]]$argumentos) {
    Start-Process -FilePath 'powershell.exe' -ArgumentList (
        @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPrincipal) + $argumentos
    )
}

function Reabrir([string]$acao, [int]$k = -1) {
    $args2 = Obter-Parametros + @('-Acao', $acao)
    if ($k -ge 0) { $args2 += @('-Lider', "$k") }
    Lancar-Script $args2
    Log-Painel "Reaberto: $acao — consola nova no ambiente de trabalho"
}

$btnArrancar.Add_Click({
    Lancar-Script (Obter-Parametros)
    Log-Painel ("Sistema a arrancar — dados em '" + $txtPasta.Text.Trim() + "', período " + $txtPeriodo.Text.Trim() + " ms, fator " + $txtFator.Text.Trim() + ". Aguarde ~30 s.") $corVerde
})

$btnParar.Add_Click({
    taskkill /F /IM java.exe /T 2>$null | Out-Null
    taskkill /F /IM ipfs.exe /T 2>$null | Out-Null
    Log-Painel 'Processos java e ipfs parados.' $corVermelho
})

$btnLider0.Add_Click({ Reabrir 'Lider' 0 })
$btnLider1.Add_Click({ Reabrir 'Lider' 1 })
$btnLider2.Add_Click({ Reabrir 'Lider' 2 })
$btnProc.Add_Click({ Reabrir 'Processador' })
$btnProd.Add_Click({ Reabrir 'Produtor' })

$btnRelatorios.Add_Click({
    $pasta = $txtPasta.Text.Trim()
    $caminhoPasta = Join-Path $raiz $pasta
    if (-not (Test-Path $caminhoPasta)) {
        Log-Painel "ERRO: pasta de dados inexistente: '$pasta'" $corVermelho
        return
    }
    Log-Painel "A gerar relatórios de '$pasta'… (aguarde alguns segundos)" $corAzul
    $form.Cursor = 'WaitCursor'
    try {
        $argsCheck = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPrincipal,
                       '-Acao', 'HeartbeatCheck', '-PastaDados', $pasta)
        $falhas = Join-Path $caminhoPasta 'falhas.txt'
        if (Test-Path $falhas) { $argsCheck += @('-Falhas', $falhas) }
        $hb = & powershell.exe $argsCheck 2>&1 | Out-String
        $cc = & powershell.exe @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPrincipal,
                       '-Acao', 'CausalCheck', '-PastaDados', $pasta) 2>&1 | Out-String
        $saida.SelectionStart = $saida.TextLength
        $saida.SelectionColor = [System.Drawing.Color]::FromArgb(250, 204, 21)
        $saida.AppendText('===== HEARTBEATCHECK =====' + "`r`n")
        $saida.SelectionColor = [System.Drawing.Color]::FromArgb(226, 232, 240)
        $saida.AppendText($hb + "`r`n")
        $saida.SelectionColor = [System.Drawing.Color]::FromArgb(250, 204, 21)
        $saida.AppendText('===== CAUSALCHECK =====' + "`r`n")
        $saida.SelectionColor = [System.Drawing.Color]::FromArgb(226, 232, 240)
        $saida.AppendText($cc + "`r`n")
        $saida.ScrollToCaret()
        Log-Painel 'Relatórios concluídos.' $corVerde
    } finally {
        $form.Cursor = 'Default'
    }
})

$form.Topmost = $true
$form.Add_Shown({ $form.Activate() })
[System.Windows.Forms.Application]::Run($form)
