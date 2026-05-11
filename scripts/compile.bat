@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem Компиляция всех Java-файлов проекта в build\classes.

set "ROOT_DIR=%~dp0.."
for %%I in ("%ROOT_DIR%") do set "ROOT_DIR=%%~fI"

cd /d "%ROOT_DIR%" || (
    echo Не удалось перейти в корень проекта: %ROOT_DIR%
    exit /b 1
)

where javac >nul 2>nul || (
    echo javac не найден. Установите JDK 17 или новее и добавьте каталог bin в PATH.
    exit /b 1
)

if not exist "build" mkdir "build"
if not exist "build\classes" mkdir "build\classes"
type nul > "build\sources.txt"

for /R "%ROOT_DIR%\urlshortener" %%F in (*.java) do (
    set "SRC=%%~fF"
    set "SRC=!SRC:%ROOT_DIR%\=!"
    >> "build\sources.txt" echo(!SRC!
)

for %%A in ("build\sources.txt") do (
    if %%~zA EQU 0 (
        echo Java-файлы не найдены в каталоге urlshortener.
        exit /b 1
    )
)

javac -encoding UTF-8 -d "build\classes" @"build\sources.txt"
if errorlevel 1 (
    echo Компиляция завершилась ошибкой.
    exit /b 1
)

echo Компиляция завершена: "%ROOT_DIR%\build\classes"
endlocal
