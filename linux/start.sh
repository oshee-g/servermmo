#!/usr/bin/env bash
#
# Paper Minecraft server - Linux/macOS launcher.
# Settings: settings.sh      Full guide (RU): ../README.md
#
# What it does:
#   1. downloads paper.jar (the Paper core) if it is missing
#   2. creates eula.txt on first run
#   3. checks that Java 25+ is installed
#   4. starts the server and offers to restart it after a stop
#
set -u

cd "$(cd "$(dirname "$0")" && pwd)"

# --- settings -----------------------------------------------------------
if [ -f settings.sh ]; then
    # shellcheck disable=SC1091
    . ./settings.sh
fi
: "${PAPER_VERSION:=26.3}"
: "${MIN_JAVA:=25}"
: "${JAVA_CMD:=java}"
: "${SERVER_JAR:=paper.jar}"
: "${XMS:=4G}"
: "${XMX:=4G}"
: "${AUTO_RESTART:=ask}"
: "${JAVA_FLAGS:=-XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1HeapRegionSize=8M -XX:G1HeapWastePercent=5 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCCountTarget=4 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1NewSizePercent=30 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:MaxGCPauseMillis=200 -XX:MaxTenuringThreshold=1 -XX:SurvivorRatio=32 -Dfile.encoding=UTF-8}"

# shellcheck source=/dev/null
. ./scripts/core.sh

say "${C_BOLD}====================================================================${C_RESET}"
say "  Paper $PAPER_VERSION server"
say "  Folder: $PWD"
say "  Memory: $XMS ... $XMX        Java: $JAVA_CMD"
say "${C_BOLD}====================================================================${C_RESET}"
say ""

say "[1/4] Core file (\"$SERVER_JAR\") ..."
if [ -f "$SERVER_JAR" ]; then
    info "OK."
else
    info "Not found - downloading the latest Paper $PAPER_VERSION build."
    if ! get_core; then
        say ""
        fail "There is no server core, so the server cannot start."
        exit 1
    fi
fi

say "[2/4] EULA ..."
if [ ! -f eula.txt ]; then
    info "eula.txt is missing - creating it with eula=true."
    info "By doing this you accept the Minecraft EULA: https://aka.ms/MinecraftEULA"
    {
        echo "# By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA)."
        echo "eula=true"
    } > eula.txt
elif ! grep -qi '^[[:space:]]*eula[[:space:]]*=[[:space:]]*true' eula.txt; then
    say ""
    fail "eula.txt exists but does not allow the server to run."
    info "Set eula=true in eula.txt (https://aka.ms/MinecraftEULA) and start again."
    exit 1
else
    info "Accepted."
fi

say "[3/4] Java (needs version $MIN_JAVA or newer) ..."
if ! JAVA_EXE="$(resolve_java)"; then
    say ""
    fail "Java was not found."
    info "Minecraft $PAPER_VERSION requires Java $MIN_JAVA or newer, 64-bit."
    info "Download Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25"
    info "Already installed? Set JAVA_CMD in settings.sh to the java executable."
    exit 1
fi
JAVA_MAJOR="$(java_major_version "$JAVA_EXE")"
info "java: $JAVA_EXE"
if [ -z "$JAVA_MAJOR" ]; then
    warn "Could not read the Java version - continuing anyway."
elif [ "$JAVA_MAJOR" -lt "$MIN_JAVA" ]; then
    say ""
    fail "Java $JAVA_MAJOR is older than the required Java $MIN_JAVA."
    info "Download Temurin JDK 25: https://adoptium.net/temurin/releases/?version=25"
    exit 1
else
    info "OK: Java $JAVA_MAJOR."
fi

say "[4/4] Starting the server ..."
say ""
say "--------------------------------------------------------------------"
say "  Type \"stop\" here or press Ctrl+C to shut the server down safely."
say "--------------------------------------------------------------------"
say ""

# Ctrl+C / TERM: forward the signal to the server so that it saves and stops.
SERVER_PID=""
stop_server() {
    printf '\n  Stopping the server ...\n'
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -INT "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    exit 0
}
trap stop_server INT TERM

while :; do
    # shellcheck disable=SC2086
    "$JAVA_EXE" $JAVA_FLAGS "-Xms$XMS" "-Xmx$XMX" -jar "$SERVER_JAR" --nogui &
    SERVER_PID=$!
    wait "$SERVER_PID"
    code=$?
    SERVER_PID=""

    say ""
    say "${C_BOLD}====================================================================${C_RESET}"
    say "  The server process has stopped (exit code $code)."
    say "${C_BOLD}====================================================================${C_RESET}"
    say ""

    case "$AUTO_RESTART" in
        yes) ;;
        no)  say "Server stopped."; exit "$code" ;;
        *)
            if [ -t 0 ]; then
                if read -r -t 20 -p "Restart the server? [y/N] (auto 'no' in 20s): " answer; then
                    case "$answer" in
                        y|Y|yes|YES|Yes) ;;
                        *) say "Server stopped."; exit "$code" ;;
                    esac
                else
                    printf '\n'
                    say "Server stopped."
                    exit "$code"
                fi
            else
                say "Server stopped."
                exit "$code"
            fi
            ;;
    esac
    say "Restarting in 5 seconds - press Ctrl+C to abort ..."
    sleep 5
done
