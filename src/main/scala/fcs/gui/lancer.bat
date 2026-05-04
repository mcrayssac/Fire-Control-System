@echo off
:: Console Launcher
title SBT Console

:: Vérifier que java est disponible
where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERREUR] Java n'est pas trouve dans le PATH.
    echo Installez Java depuis https://adoptium.net/ puis relancez.
    echo.
    pause
    exit /b 1
)

:: Lancer l'application
java ^
  -Dfile.encoding=UTF-8 ^
  -Dsun.stdout.encoding=UTF-8 ^
  -Dapp.workdir="%~dp0..\..\..\..\.." ^
  -jar "%~dp0CommandConsoleWin.jar"
