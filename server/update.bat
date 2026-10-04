@echo off
rem ====================================================================
rem  Updates the Paper core to the newest build of the version that is
rem  selected in settings.bat (see PAPER_VERSION).
rem
rem  Run this while the server is STOPPED.
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "settings.bat" goto :no_settings
call "%~dp0settings.bat"
if "%PAPER_VERSION%"=="" set "PAPER_VERSION=26.3"
if "%SERVER_JAR%"==""    set "SERVER_JAR=paper.jar"

echo ====================================================================
echo   Paper %PAPER_VERSION% - core update
echo ====================================================================
echo.
echo  Stop the server before updating ^(type "stop" in its window^),
echo  and make a backup with backup.bat if the world matters.
echo.
pause

if not exist "%~dp0scripts\download-paper.ps1" goto :manual
where powershell >nul 2>&1
if errorlevel 1 goto :manual

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\download-paper.ps1" -Version "%PAPER_VERSION%" -Destination "%CD%\%SERVER_JAR%"
if errorlevel 1 goto :manual

echo.
echo  Done. If the core was replaced, the old file is kept as
echo  "%SERVER_JAR%.old" - delete it once the server runs fine.
echo.
pause
exit /b 0

:manual
echo.
echo  Automatic update failed. Download the jar manually from
echo  https://papermc.io/downloads/paper and replace "%SERVER_JAR%".
echo.
pause
exit /b 1

:no_settings
echo [STOP] settings.bat was not found next to update.bat.
pause
exit /b 1
