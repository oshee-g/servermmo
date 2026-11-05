@echo off
rem ====================================================================
rem  Paper Minecraft server - Windows launcher
rem  Settings: settings.bat        Full guide (RU): ..\README.md
rem
rem  What it does:
rem    1. downloads paper.jar (the Paper core) if it is missing
rem    2. creates eula.txt on first run
rem    3. checks that Java 25+ is installed
rem    4. starts the server and offers to restart it after a stop
rem ====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "settings.bat" goto :no_settings
call "%~dp0settings.bat"

if "%JAVA_CMD%"==""      set "JAVA_CMD=java"
if "%PAPER_VERSION%"=="" set "PAPER_VERSION=26.3"
if "%MIN_JAVA%"==""      set "MIN_JAVA=25"
if "%SERVER_JAR%"==""    set "SERVER_JAR=paper.jar"
if "%XMS%"==""           set "XMS=4G"
if "%XMX%"==""           set "XMX=4G"

echo ====================================================================
echo   Paper %PAPER_VERSION% server
echo   Folder: %CD%
echo   Memory: %XMS% ... %XMX%        Java: %JAVA_CMD%
echo ====================================================================
echo.

echo [1/4] Core file ^("%SERVER_JAR%"^) ...
if exist "%SERVER_JAR%" goto :core_ok
echo       Not found - downloading the latest Paper %PAPER_VERSION% build.
call :get_core
if errorlevel 1 goto :core_failed
:core_ok
echo       OK.
goto :check_eula

:check_eula
echo [2/4] EULA ...
if not exist "eula.txt" goto :write_eula
findstr /i /c:"eula=true" "eula.txt" >nul 2>&1
if errorlevel 1 goto :eula_not_accepted
echo       Accepted.
goto :check_java

:write_eula
echo       eula.txt is missing - creating it with eula=true.
echo       By doing this you accept the Minecraft EULA:
echo       https://aka.ms/MinecraftEULA
(
echo # By changing the setting below to TRUE you are indicating your
echo # agreement to the Minecraft EULA: https://aka.ms/MinecraftEULA
echo eula=true
) > "eula.txt"
goto :check_java

:eula_not_accepted
echo.
echo [STOP] eula.txt exists but does not allow the server to run.
echo        Running a Minecraft server requires accepting the EULA:
echo        https://aka.ms/MinecraftEULA
echo        Open eula.txt, set eula=true, then run start.bat again.
echo.
pause
exit /b 1

:check_java
echo [3/4] Java ^(needs version %MIN_JAVA% or newer^) ...
if not exist "%~dp0scripts\check-java.ps1" goto :java_skipped
where powershell >nul 2>&1
if errorlevel 1 goto :java_skipped
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check-java.ps1" -JavaPath "%JAVA_CMD%" -MinMajor %MIN_JAVA%
set "JAVA_CODE=%ERRORLEVEL%"
if "%JAVA_CODE%"=="0" goto :run
if "%JAVA_CODE%"=="2" goto :java_old
if "%JAVA_CODE%"=="3" goto :java_not_found
echo       [WARN] Could not verify Java ^(exit code %JAVA_CODE%^). Trying to start anyway.
goto :run

:java_skipped
echo       [WARN] PowerShell is not available - skipping the Java check.
goto :run

:java_not_found
echo.
echo ====================================================================
echo   JAVA %MIN_JAVA%+ WAS NOT FOUND
echo ====================================================================
echo  Minecraft %PAPER_VERSION% requires Java %MIN_JAVA% or newer, 64-bit.
echo.
echo  1^) Download Temurin JDK 25 for Windows x64 ^(free, Adoptium^):
echo        https://adoptium.net/temurin/releases/?version=25
echo  2^) Install it and keep the options "Set JAVA_HOME" and
echo     "Add to PATH" enabled.
echo  3^) Close this window and run start.bat again.
echo.
echo  Java already installed elsewhere? Put the full path of java.exe
echo  into settings.bat, for example:
echo     set "JAVA_CMD=C:\Program Files\Java\jdk-25\bin\java.exe"
echo.
pause
exit /b 1

:java_old
echo.
echo ====================================================================
echo   JAVA IS TOO OLD
echo ====================================================================
echo  Minecraft %PAPER_VERSION% needs Java %MIN_JAVA% or newer.
echo  The version found at "%JAVA_CMD%" is older than that.
echo.
echo  Download Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25
echo  Then install it ^(it becomes the default "java"^) or point JAVA_CMD
echo  in settings.bat at the new java.exe.
echo.
pause
exit /b 1

:run
rem --- hint: is the HUD resource pack shared with players? -------------
findstr /b /c:"resource-pack=http" "server.properties" >nul 2>&1
if not errorlevel 1 goto :pack_ready
echo [i] Resource pack is not shared yet, so players will see empty
echo     squares instead of the ServerMMO HUD. The server already
echo     serves the pack itself - run setup-resourcepack.bat once
echo     and restart. Details: ..\PLUGINS.md
echo.
:pack_ready

echo [4/4] Starting the server ...
echo.
echo --------------------------------------------------------------------
echo  Type "stop" in this window to shut the server down safely.
echo  Do not just close the window: the world may get damaged.
echo --------------------------------------------------------------------
echo.
"%JAVA_CMD%" %JAVA_FLAGS% -Xms%XMS% -Xmx%XMX% -jar "%SERVER_JAR%" --nogui
set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo ====================================================================
echo   The server process has stopped ^(exit code %EXIT_CODE%^).
echo ====================================================================
echo.
if /i "%AUTO_RESTART%"=="yes" goto :restart
if /i "%AUTO_RESTART%"=="no" goto :quit
choice /c YN /n /t 20 /d N /m "Restart the server? [Y] yes, [N] no: "
if errorlevel 2 goto :quit
:restart
echo Restarting in 5 seconds - press Ctrl+C to abort ...
timeout /t 5 /nobreak >nul
goto :run

:quit
echo Server stopped.
exit /b %EXIT_CODE%

rem ------------------------------------------------------------------
rem  Helper: download the Paper core into this folder.
rem ------------------------------------------------------------------
:get_core
if not exist "%~dp0scripts\download-paper.ps1" goto :manual_download
where powershell >nul 2>&1
if errorlevel 1 goto :manual_download
echo       Asking PaperMC for the latest Paper %PAPER_VERSION% build ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\download-paper.ps1" -Version "%PAPER_VERSION%" -Destination "%CD%\%SERVER_JAR%"
if errorlevel 1 goto :manual_download
if not exist "%SERVER_JAR%" goto :manual_download
exit /b 0

:manual_download
echo.
echo ====================================================================
echo   AUTOMATIC DOWNLOAD FAILED
echo ====================================================================
echo  Download the core manually:
echo    1^) open https://papermc.io/downloads/paper in a browser
echo    2^) choose Minecraft %PAPER_VERSION% and download the server jar
echo    3^) save it into this folder as "%SERVER_JAR%"
echo    4^) run start.bat again
echo.
pause
exit /b 1

:core_failed
echo.
echo [STOP] There is no server core, so the server cannot start.
pause
exit /b 1

:no_settings
echo [STOP] settings.bat was not found next to start.bat.
echo        Restore it from the server pack and try again.
pause
exit /b 1
