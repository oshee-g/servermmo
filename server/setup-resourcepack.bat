@echo off
rem ====================================================================
rem  Writes the resource-pack settings into server.properties.
rem
rem  Usage:
rem     setup-resourcepack.bat
rem        asks for the URL, suggests the direct link from the latest release
rem     setup-resourcepack.bat http://example.com/servermmo-hud.zip
rem        uses the given URL without questions
rem     setup-resourcepack.bat http://example.com/servermmo-hud.zip --require true
rem        also forces players to accept the pack
rem
rem  The correct SHA-1 of resourcepack\servermmo-hud.zip is computed for you.
rem  Stop the Minecraft server before changing server.properties.
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "settings.bat" goto :no_settings
call "%~dp0settings.bat"

if "%JAVA_CMD%"==""  set "JAVA_CMD=java"

if not exist "server.properties" goto :no_props
if not exist "resourcepack\servermmo-hud.zip" goto :no_pack

rem Source-file mode needs the JDK (jdk.compiler), a plain JRE cannot do it.
"%JAVA_CMD%" --list-modules 2>nul | findstr /b /c:"jdk.compiler" >nul
if errorlevel 1 goto :no_jdk

cd /d "%~dp0resourcepack"
"%JAVA_CMD%" SetupPack.java %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%EXIT_CODE%"=="0" echo [WARN] server.properties was not updated ^(exit code %EXIT_CODE%^).
pause
exit /b %EXIT_CODE%

:no_props
echo [STOP] server.properties was not found in this folder.
pause
exit /b 1

:no_pack
echo [STOP] resourcepack\servermmo-hud.zip was not found.
pause
exit /b 1

:no_jdk
echo [STOP] This Java installation has no compiler ^(jdk.compiler^).
echo        Source-file mode needs a full JDK, not a plain JRE.
echo        Install Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25
pause
exit /b 1

:no_settings
echo [STOP] settings.bat was not found next to setup-resourcepack.bat.
pause
exit /b 1
