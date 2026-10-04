@echo off
rem ===================================================================
rem  Settings for the Paper server pack (Windows)
rem  Edit the values below, save the file, then run start.bat
rem  Full guide (RU): ..\README.md
rem ===================================================================

rem --- Minecraft / Paper version -------------------------------------
rem  26.3 = current game version (Wilderness Bound). Its Paper builds
rem  are on the BETA channel but are usable for a normal server.
rem  Set 26.2 if you need the previous "stable" Paper channel.
set "PAPER_VERSION=26.3"

rem --- Minimum Java version required by this Minecraft version -------
rem  Minecraft 26.1 and newer need Java 25. Do not lower this.
set "MIN_JAVA=25"

rem --- Java executable ----------------------------------------------
rem  "java" works when Java is on PATH (the normal installer default).
rem  Otherwise put the full path to java.exe here, for example:
rem  set "JAVA_CMD=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.9-hotspot\bin\java.exe"
set "JAVA_CMD=java"

rem --- Memory (Java heap) -------------------------------------------
rem  XMS = starting size, XMX = maximum. Keep them equal for stable GC.
rem  2G = a few friends / 4G = small server up to ~20 players / 8G+ = more.
rem  Do not give the server more than half of the PC's RAM.
set "XMS=4G"
set "XMX=4G"

rem --- Core jar file name (paper.jar = the Paper server core) --------
set "SERVER_JAR=paper.jar"

rem --- What to do after the server process stops ---------------------
rem  ask = ask, and auto-answer "no" after 20 seconds   [default]
rem  yes = restart automatically (survives crashes)
rem  no  = close the window
set "AUTO_RESTART=ask"

rem --- JVM flags -----------------------------------------------------
rem  Official Paper-recommended flags for 26.3 (G1 GC tuning).
set "JAVA_FLAGS=-XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1HeapRegionSize=8M -XX:G1HeapWastePercent=5 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCCountTarget=4 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1NewSizePercent=30 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:MaxGCPauseMillis=200 -XX:MaxTenuringThreshold=1 -XX:SurvivorRatio=32 -Dfile.encoding=UTF-8"
