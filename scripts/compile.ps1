param()

# Компиляция всех Java-файлов проекта в build/classes.

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RootDir

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw "javac не найден. Установите JDK 17 или новее и добавьте каталог bin в PATH."
}

New-Item -ItemType Directory -Force -Path "build/classes" | Out-Null

$sourceFiles = Get-ChildItem -Path "urlshortener" -Recurse -Filter "*.java" |
    ForEach-Object { Resolve-Path -Relative $_.FullName }

if (-not $sourceFiles -or $sourceFiles.Count -eq 0) {
    throw "Java-файлы не найдены в каталоге urlshortener."
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines((Join-Path $RootDir "build/sources.txt"), $sourceFiles, $utf8NoBom)

& javac -encoding UTF-8 -d "build/classes" "@build/sources.txt"
if ($LASTEXITCODE -ne 0) {
    throw "javac завершился с кодом $LASTEXITCODE"
}

Write-Host "Компиляция завершена: $RootDir\build\classes"
