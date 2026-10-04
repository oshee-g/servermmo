@echo off
rem ====================================================================
rem  Backs up the worlds, plugins and configs into the backups folder.
rem  Best used while the server is stopped, or right after running
rem  "save-all" in the server console.
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "%~dp0scripts\backup.ps1" goto :no_script
where powershell >nul 2>&1
if errorlevel 1 goto :no_script

echo ====================================================================
echo   Server backup
echo ====================================================================
echo.
echo  Worlds, plugins and config files go to: backups\
echo  Run "save-all" in the server console first if the server is up.
echo.
pause

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\backup.ps1" -Source "%CD%" -Destination "%CD%\backups"
if errorlevel 1 goto :failed

echo.
echo  Done.
echo.
pause
exit /b 0

:failed
echo.
echo [STOP] The backup failed. Check the messages above.
echo.
pause
exit /b 1

:no_script
echo [STOP] scripts\backup.ps1 not found, or PowerShell is unavailable.
echo        Copy the folder "world" (and "world_nether", "world_the_end")
echo        somewhere safe manually.
pause
exit /b 1
