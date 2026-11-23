$ErrorActionPreference = 'Stop'

$projeto = $PSScriptRoot
$repositorio = (Resolve-Path -LiteralPath (Join-Path $projeto '../../..')).Path
$dados = Join-Path $projeto 'dados-lider'
$pom = Join-Path $projeto 'pom.xml'

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error 'Maven nao encontrado no PATH.'
    exit 1
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error 'Java nao encontrado no PATH.'
    exit 1
}

Write-Host "Pasta de dados: $dados"
Write-Host 'A chave existente sera reutilizada; se nao existir, o lider tentara cria-la.'

Push-Location -LiteralPath $repositorio
try {
    & mvn -f $pom "-Dtpf.dataDir=$dados" '-Dexec.mainClass=tpf.LeaderBot' compile 'org.codehaus.mojo:exec-maven-plugin:3.5.0:java'
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
