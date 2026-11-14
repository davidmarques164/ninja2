# atualizar.ps1 - sobe a versao, compila e atualiza os plugins.json
# Coloque este arquivo na raiz do projeto (C:\Users\David Peter\Desktop\ninja2) e rode:
#   powershell -ExecutionPolicy Bypass -File .\atualizar.ps1              -> versao atual + 1
#   powershell -ExecutionPolicy Bypass -File .\atualizar.ps1 -Versao 30   -> versao exata
# Se algum plugins.json estiver fora da pasta do projeto, informe o caminho:
#   ... -PluginsJson "C:\caminho\repo\plugins.json","C:\Users\David Peter\Desktop\ninja2\plugins.json"

param(
    [int]$Versao = 0,
    [string[]]$PluginsJson = @()
)

$ErrorActionPreference = 'Stop'
$raiz   = $PSScriptRoot
$modulo = 'Ninja2Provider'
$gradle = Join-Path $raiz "$modulo\build.gradle.kts"
$cs3    = Join-Path $raiz "$modulo\build\$modulo.cs3"
$utf8   = New-Object System.Text.UTF8Encoding $false   # UTF-8 sem BOM (JSON com BOM pode quebrar no app)

function Ler([string]$p)              { [System.IO.File]::ReadAllText($p) }
function Gravar([string]$p, [string]$t) { [System.IO.File]::WriteAllText($p, $t, $utf8) }

# 1) versao no build.gradle.kts
$txt = Ler $gradle
$m = [regex]::Match($txt, '(?m)^\s*version\s*=\s*(\d+)')
if (-not $m.Success) { throw "Linha 'version = N' nao encontrada em $gradle" }
$atual = [int]$m.Groups[1].Value
if ($Versao -le 0) { $Versao = $atual + 1 }
Gravar $gradle ([regex]::Replace($txt, '(?m)^(\s*version\s*=\s*)\d+', "`${1}$Versao"))
Write-Host "build.gradle.kts: $atual -> $Versao" -ForegroundColor Cyan

# 2) compilar
Push-Location $raiz
try {
    & .\gradlew.bat ":${modulo}:make"
    if ($LASTEXITCODE -ne 0) { throw "make falhou (codigo $LASTEXITCODE). Versao ja foi para $Versao; rode de novo com -Versao $Versao." }
} finally { Pop-Location }

# 3) tamanho do .cs3 gerado (e copia para a raiz, se ja existir uma copia la)
if (-not (Test-Path $cs3)) { throw "Nao achei $cs3 depois do make" }
$tamanho = (Get-Item $cs3).Length
$cs3Raiz = Join-Path $raiz "$modulo.cs3"
if (Test-Path $cs3Raiz) {
    Copy-Item $cs3 $cs3Raiz -Force
    Write-Host "Copiado para $cs3Raiz" -ForegroundColor Cyan
}
Write-Host "fileSize: $tamanho" -ForegroundColor Cyan

# 4) plugins.json (sem caminhos informados: procura no projeto, fora de build/.gradle)
if ($PluginsJson.Count -eq 0) {
    $PluginsJson = Get-ChildItem $raiz -Recurse -Filter plugins.json -File |
        Where-Object { $_.FullName -notmatch '\\(build|\.gradle|node_modules)\\' } |
        ForEach-Object { $_.FullName }
}
if ($PluginsJson.Count -eq 0) { Write-Warning 'Nenhum plugins.json encontrado. Use -PluginsJson "caminho1","caminho2".' }

foreach ($p in $PluginsJson) {
    $j = Ler $p
    $j = [regex]::Replace($j, '("version"\s*:\s*)\d+',  "`${1}$Versao")
    $j = [regex]::Replace($j, '("fileSize"\s*:\s*)\d+', "`${1}$tamanho")
    Gravar $p $j
    Write-Host "Atualizado: $p (version=$Versao, fileSize=$tamanho)" -ForegroundColor Green
}
