# ===================================================================
#  Settings for the Paper server pack (Linux / macOS)
#  Edit the values below, save the file, then run ./start.sh
#  Full guide (RU): ../README.md
# ===================================================================

# --- Minecraft / Paper version --------------------------------------
#  26.3 = current game version (Wilderness Bound). Its Paper builds
#  are on the BETA channel but are usable for a normal server.
#  Set 26.2 if you need the previous "stable" Paper channel.
PAPER_VERSION=26.3

# --- Minimum Java version required by this Minecraft version --------
#  Minecraft 26.1 and newer need Java 25. Do not lower this.
MIN_JAVA=25

# --- Java executable ------------------------------------------------
#  "java" works when Java is on PATH (the normal package default).
#  Otherwise put an absolute path here, for example:
#  JAVA_CMD=/usr/lib/jvm/temurin-25-jdk/bin/java
JAVA_CMD=java

# --- Memory (Java heap) ---------------------------------------------
#  XMS = starting size, XMX = maximum. Keep them equal for stable GC.
#  2G = a few friends / 4G = small server up to ~20 players / 8G+ = more.
#  Do not give the server more than half of the machine's RAM.
XMS=4G
XMX=4G

# --- Core jar file name (paper.jar = the Paper server core) ---------
SERVER_JAR=paper.jar

# --- What to do after the server process stops ----------------------
#  ask = ask, and auto-answer "no" after 20 seconds   [default]
#  yes = restart automatically (survives crashes)
#  no  = exit
AUTO_RESTART=ask

# --- JVM flags ------------------------------------------------------
#  Official Paper-recommended flags for 26.3 (G1 GC tuning).
JAVA_FLAGS="-XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1HeapRegionSize=8M -XX:G1HeapWastePercent=5 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCCountTarget=4 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1NewSizePercent=30 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:MaxGCPauseMillis=200 -XX:MaxTenuringThreshold=1 -XX:SurvivorRatio=32 -Dfile.encoding=UTF-8"
