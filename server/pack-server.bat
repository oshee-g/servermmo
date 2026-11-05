@echo off
rem ====================================================================
rem  Shares the HUD resource pack with game clients over plain HTTP.
rem
rem  Clients download the pack automatically when server.properties has
rem  resource-pack and resource-pack-sha1 set (setup-resourcepack.bat
rem  writes both for you).
rem
rem  Needs Java 25 - the same JDK the Minecraft server uses.
rem  Settings: settings.bat  (JAVA_CMD, PACK_PORT)
rem  Full guide (RU): ..\README.md
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "settings.bat" goto :no_settings
call "%~dp0settings.bat"

if "%JAVA_CMD%"==""  set "JAVA_CMD=java"
if "%PACK_PORT%"=="" set "PACK_PORT=8080"

echo ====================================================================
echo   Resource pack HTTP server
echo   Folder: %CD%
echo   Port:   %PACK_PORT%
echo ====================================================================
echo.

if not exist "resourcepack\servermmo-hud.zip" goto :no_pack

rem Source-file mode needs the JDK (jdk.compiler), a plain JRE cannot do it.
"%JAVA_CMD%" --list-modules 2>nul | findstr /b /c:"jdk.compiler" >nul
if errorlevel 1 goto :no_jdk

cd /d "%~dp0resourcepack"
echo   Starting ... keep this window open, Ctrl+C stops the server.
echo.
"%JAVA_CMD%" PackServer.java %PACK_PORT% servermmo-hud.zip
set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo   Server stopped ^(exit code %EXIT_CODE%^).
pause
exit /b %EXIT_CODE%

:no_pack
echo [STOP] resourcepack\servermmo-hud.zip was not found.
echo        Rebuild it with: python tools\gen_resourcepack.py
pause
exit /b 1

:no_jdk
echo [STOP] This Java installation has no compiler ^(jdk.compiler^).
echo        Source-file mode needs a full JDK, not a plain JRE.
echo        Install Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25
echo        Then set the full path to java.exe in settings.bat.
pause
exit /b 1

:no_settings
echo [STOP] settings.bat was not found next to pack-server.bat.
pause
exit /b 1
