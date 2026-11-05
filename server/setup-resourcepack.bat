@echo off
rem ====================================================================
rem  Writes the resource-pack settings into server.properties.
rem
rem  Usage:
rem     setup-resourcepack.bat
rem        asks for the URL, suggests http://^<your-ip^>:8080/servermmo-hud.zip
rem     setup-resourcepack.bat http://example.com/servermmo-hud.zip
rem        uses the given URL without questions
rem
rem  It always writes the correct SHA-1 of resourcepack\servermmo-hud.zip.
rem  Stop the Minecraft server before changing server.properties.
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "server.properties" goto :no_props
if not exist "resourcepack\servermmo-hud.zip" goto :no_pack
where powershell >nul 2>&1
if errorlevel 1 goto :no_ps

if "%PACK_PORT%"=="" set "PACK_PORT=8080"

set "PACK_URL=%~1"
if "%PACK_URL%"=="" goto :ask

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\resourcepack.ps1" -Url "%PACK_URL%" -Port %PACK_PORT%
set "PS_CODE=%ERRORLEVEL%"
goto :done

:ask
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\resourcepack.ps1" -Port %PACK_PORT%
set "PS_CODE=%ERRORLEVEL%"

:done
echo.
if not "%PS_CODE%"=="0" echo [WARN] server.properties was not updated ^(exit code %PS_CODE%^).
pause
exit /b %PS_CODE%

:no_props
echo [STOP] server.properties was not found in this folder.
pause
exit /b 1

:no_pack
echo [STOP] resourcepack\servermmo-hud.zip was not found.
pause
exit /b 1

:no_ps
echo [STOP] PowerShell is required to update server.properties.
pause
exit /b 1
