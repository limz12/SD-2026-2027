# Funcoes de apoio: localizar o JDK e abrir consolas Java.
# Carregado por iniciar-comindir02.ps1; nao executar diretamente.

function Find-JavaTool {
    param(
        [string]$Name,
        [string]$JdkHome
    )
    if ($JdkHome) {
        $candidate = Join-Path $JdkHome "bin\$Name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
        throw "Nao encontrado: $candidate"
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Java nao encontrado. Indica -JavaHome com a pasta do JDK (Java 11 ou superior)."
}

# EncodedCommand preserva espacos, acentos e argumentos literais nos caminhos.
function Open-Console {
    param(
        [string]$Title,
        [string]$Directory,
        [string]$Executable,
        [string[]]$Arguments,
        [string]$IpfsRepository
    )
    $quote = { param($value) "'" + $value.Replace("'", "''") + "'" }
    $argumentText = ($Arguments | ForEach-Object { & $quote $_ }) -join ' '
    $commandText = '$Host.UI.RawUI.WindowTitle = ' + (& $quote $Title) + "`n" +
        'Set-Location -LiteralPath ' + (& $quote $Directory) + "`n" +
        '& ' + (& $quote $Executable) + ' ' + $argumentText
    if ($IpfsRepository) { $commandText = '$env:IPFS_PATH = ' + (& $quote $IpfsRepository) + "`n" + $commandText }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($commandText))
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) | Out-Null
}
