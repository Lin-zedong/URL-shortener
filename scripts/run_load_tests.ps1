param(
    [int]$Duration = 60
)

# Запуск полного набора нагрузочных сценариев.

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RootDir

& (Join-Path $PSScriptRoot "compile.ps1")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java не найден. Установите JDK 17 или новее и добавьте каталог bin в PATH."
}

& java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "build/classes" urlshortener.loadtest.LoadTestRunner --duration=$Duration --list-rps=50
