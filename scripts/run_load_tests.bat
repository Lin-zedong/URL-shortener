@echo off
setlocal EnableExtensions
chcp 65001 >nul

rem Запуск полного набора нагрузочных сценариев. Первый аргумент — длительность каждого сценария в секундах.

set "ROOT_DIR=%~dp0.."
for %%I in ("%ROOT_DIR%") do set "ROOT_DIR=%%~fI"
set "DURATION=%~1"
if "%DURATION%"=="" set "DURATION=60"

cd /d "%ROOT_DIR%" || (
    echo Не удалось перейти в корень проекта: %ROOT_DIR%
    exit /b 1
)

call "%ROOT_DIR%\scripts\compile.bat"
if errorlevel 1 exit /b 1

where java >nul 2>nul || (
    echo java не найден. Установите JDK 17 или новее и добавьте каталог bin в PATH.
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "build\classes" urlshortener.loadtest.LoadTestRunner --duration=%DURATION% --list-rps=50
endlocal
