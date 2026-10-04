#!/usr/bin/env bash
#
# Tests for the Linux/macOS scripts (start.sh, update.sh, backup.sh).
#
# There is no internet access and no real Java in this environment, so the
# test builds a sandbox with:
#   * a fake `curl` that serves a PaperMC-API-like JSON document and a
#     fake jar (with a correct SHA-256, so the checksum path is exercised)
#   * a fake `java` that reports a configurable version
# and then runs the scripts, checking files and outputs.
#
# Run:  bash tools/test-linux.sh
#
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0

check() {  # check "description" <command...>
    local description="$1"; shift
    if "$@"; then
        printf '    [PASS] %s\n' "$description"
        PASS=$((PASS + 1))
    else
        printf '    [FAIL] %s\n' "$description"
        FAIL=$((FAIL + 1))
    fi
}

# ---------------------------------------------------------------------------
# Sandbox with mocks
# ---------------------------------------------------------------------------
setup_sandbox() {
    SANDBOX="$(mktemp -d)"
    WORK="$SANDBOX/server"
    MOCKDIR="$SANDBOX/bin"
    mkdir -p "$WORK" "$MOCKDIR"
    cp -r "$REPO/linux/." "$WORK/"
    chmod +x "$WORK"/*.sh "$WORK/scripts/core.sh"

    # --- fake jar + matching API answer -----------------------------------
    printf 'FAKE PAPER JAR build 151\n' > "$SANDBOX/fake-paper.jar"
    JAR_SHA="$(sha256sum "$SANDBOX/fake-paper.jar" | awk '{print $1}')"
    JAR_SIZE="$(stat -c %s "$SANDBOX/fake-paper.jar")"
    python3 - "$SANDBOX/api.json" "$JAR_SHA" "$JAR_SIZE" <<'PY'
import json, sys
path, sha, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
doc = {
    "id": 151,
    "time": "2026-10-04T13:36:03Z",
    "channel": "BETA",
    "commits": [{"sha": "6e88e469", "time": "2026-10-04T13:35:49Z", "message": "Fix CME race"}],
    "downloads": {
        "server:default": {
            "name": "paper-26.3-151.jar",
            "checksums": {"sha256": sha},
            "size": size,
            "url": "https://fill-data.papermc.io/v1/objects/" + sha + "/paper-26.3-151.jar",
        }
    },
}
json.dump(doc, open(path, 'w'))
PY

    # --- fake curl ---------------------------------------------------------
    cat > "$MOCKDIR/curl" <<'SH'
#!/usr/bin/env bash
url=""; out=""; args=("$@")
for i in "${!args[@]}"; do
    case "${args[$i]}" in
        http*) url="${args[$i]}" ;;
    esac
    if [ "${args[$i]}" = "-o" ]; then out="${args[$((i+1))]}"; fi
done
[ -n "${MOCK_CURL_FAIL:-}" ] && { echo "curl: (7) could not connect" >&2; exit 7; }
case "$url" in
    */builds/latest)
        [ -n "${MOCK_API_404:-}" ] && { echo "curl: (22) 404" >&2; exit 22; }
        cat "$MOCK_API_JSON" ;;
    *.jar)
        if [ -n "$out" ]; then cp "$MOCK_JAR_SRC" "$out"; else cat "$MOCK_JAR_SRC"; fi ;;
    "") echo "curl: no url" >&2; exit 2 ;;
    *) echo "curl: unknown url $url" >&2; exit 6 ;;
esac
exit 0
SH

    # --- fake java ---------------------------------------------------------
    cat > "$MOCKDIR/java" <<'SH'
#!/usr/bin/env bash
if [ "${1:-}" = "-version" ]; then
    echo "openjdk version \"${MOCK_JAVA_VERSION:-25.0.1}\"" >&2
    echo "OpenJDK Runtime Environment Temurin-${MOCK_JAVA_VERSION:-25.0.1}+8" >&2
    exit 0
fi
echo "Starting minecraft server version 26.3"
echo "Done (1.234s)! For help, type \"help\""
exit "${MOCK_JAVA_EXIT:-0}"
SH
    chmod +x "$MOCKDIR/curl" "$MOCKDIR/java"
    export PATH="$MOCKDIR:$PATH"
    export MOCK_API_JSON="$SANDBOX/api.json"
    export MOCK_JAR_SRC="$SANDBOX/fake-paper.jar"
    export MOCK_JAR_SHA="$JAR_SHA"

    # deterministic, non-interactive settings
    sed -i 's/^AUTO_RESTART=ask/AUTO_RESTART=no/' "$WORK/settings.sh"
    unset MOCK_CURL_FAIL MOCK_API_404 MOCK_JAVA_VERSION MOCK_JAVA_EXIT 2>/dev/null || true
}

teardown() { rm -rf "$SANDBOX"; }

run() {  # run <script> ; prints output, stores exit code in RUN_CODE
    RUN_OUT="$(cd "$WORK" && ./$1 2>&1)"
    RUN_CODE=$?
}

# ---------------------------------------------------------------------------
echo '======================================================================'
echo 'SCENARIO: fresh install on Linux (start.sh)'
echo '======================================================================'
setup_sandbox
run start.sh
printf '%s\n' "$RUN_OUT" | sed 's/^/      | /'
check "exit code is 0"                 test "$RUN_CODE" -eq 0
check "paper.jar was downloaded"       test -f "$WORK/paper.jar"
check "the downloaded jar is intact"   cmp -s "$WORK/paper.jar" "$SANDBOX/fake-paper.jar"
check "no leftover .download file"     test ! -f "$WORK/paper.jar.download"
check "eula.txt created"               grep -qi '^eula=true' "$WORK/eula.txt"
check "SHA-256 was verified"           grep -q 'SHA-256 verified' <<<"$RUN_OUT"
check "the build number was reported"  grep -q 'build 151' <<<"$RUN_OUT"
check "java check passed"              grep -q 'OK: Java 25' <<<"$RUN_OUT"
check "the server was started"         grep -q 'Done (1.234s)' <<<"$RUN_OUT"
teardown

echo '======================================================================'
echo 'SCENARIO: second start re-uses the installed core'
echo '======================================================================'
setup_sandbox
run start.sh >/dev/null 2>&1
run start.sh
check "exit code is 0"                 test "$RUN_CODE" -eq 0
check "no network request for the core" bash -c '! grep -q "Asking PaperMC" <<<"$1"' _ "$RUN_OUT"
check "the installed core is untouched"  cmp -s "$WORK/paper.jar" "$SANDBOX/fake-paper.jar"
check "the server was started"         grep -q 'Done (1.234s)' <<<"$RUN_OUT"
teardown

echo '======================================================================'
echo 'SCENARIO: Java 21 installed (too old)'
echo '======================================================================'
setup_sandbox
export MOCK_JAVA_VERSION="21.0.5"
run start.sh
check "exit code is 1"                 test "$RUN_CODE" -eq 1
check "the error explains Java 25"     grep -q 'older than the required Java 25' <<<"$RUN_OUT"
check "the server was not started"     bash -c '! grep -q "Done (1.234s)" <<<"$1"' _ "$RUN_OUT"
teardown

echo '======================================================================'
echo 'SCENARIO: Java not installed at all'
echo '======================================================================'
setup_sandbox
rm -f "$MOCKDIR/java"
sed -i 's#^JAVA_CMD=.*#JAVA_CMD=java#' "$WORK/settings.sh"
run start.sh
check "exit code is 1"                 test "$RUN_CODE" -eq 1
check "adoptium link is shown"         grep -q 'adoptium.net' <<<"$RUN_OUT"
teardown

echo '======================================================================'
echo 'SCENARIO: eula.txt says false'
echo '======================================================================'
setup_sandbox
printf 'eula=false\n' > "$WORK/eula.txt"
run start.sh
check "exit code is 1"                 test "$RUN_CODE" -eq 1
check "the EULA is explained"          grep -q 'MinecraftEULA' <<<"$RUN_OUT"
check "the server was not started"     bash -c '! grep -q "Done (1.234s)" <<<"$1"' _ "$RUN_OUT"
teardown

echo '======================================================================'
echo 'SCENARIO: API unreachable and no local core'
echo '======================================================================'
setup_sandbox
export MOCK_CURL_FAIL=1
run start.sh
check "exit code is 1"                 test "$RUN_CODE" -eq 1
check "manual download hint is shown"  grep -q 'papermc.io/downloads/paper' <<<"$RUN_OUT"
check "no paper.jar was created"       test ! -f "$WORK/paper.jar"
teardown

echo '======================================================================'
echo 'SCENARIO: corrupted download (SHA mismatch)'
echo '======================================================================'
setup_sandbox
printf 'FAKE PAPER JAR WRONG CONTENT\n' > "$SANDBOX/fake-paper.jar"
run start.sh
check "exit code is 1"                 test "$RUN_CODE" -eq 1
check "the mismatch is reported"       grep -q 'SHA-256 mismatch' <<<"$RUN_OUT"
check "the broken file was discarded"  test ! -f "$WORK/paper.jar"
teardown

echo '======================================================================'
echo 'SCENARIO: update.sh replaces the core and keeps paper.jar.old'
echo '======================================================================'
setup_sandbox
printf 'OLD CORE\n' > "$WORK/paper.jar"
run update.sh
check "exit code is 0"                 test "$RUN_CODE" -eq 0
check "new core installed"             cmp -s "$WORK/paper.jar" "$SANDBOX/fake-paper.jar"
check "old core kept"                  test -f "$WORK/paper.jar.old"
check "the old core is still intact"   grep -q 'OLD CORE' "$WORK/paper.jar.old"
teardown

echo '======================================================================'
echo 'SCENARIO: backup.sh packs worlds and configs'
echo '======================================================================'
setup_sandbox
mkdir -p "$WORK/world/region" "$WORK/world_nether" "$WORK/plugins"
echo "chunk data" > "$WORK/world/region/r.0.0.mca"
echo "plugin"     > "$WORK/plugins/someplugin.jar"
run backup.sh
check "exit code is 0"                 test "$RUN_CODE" -eq 0
ARCHIVE="$(ls "$WORK"/backups/backup-*.tar.gz 2>/dev/null | head -1)"
check "an archive was created"         test -n "$ARCHIVE"
check "the world is in the archive"    bash -c 'tar -tzf "$1" | grep -q "world/region/r.0.0.mca"' _ "$ARCHIVE"
check "the nether is in the archive"   bash -c 'tar -tzf "$1" | grep -q "world_nether"' _ "$ARCHIVE"
check "plugins are in the archive"     bash -c 'tar -tzf "$1" | grep -q "plugins/someplugin.jar"' _ "$ARCHIVE"
rm -rf "$WORK/world" "$WORK/world_nether" "$WORK/plugins"
run backup.sh
check "nothing to back up -> exit 1"   test "$RUN_CODE" -eq 1
teardown

echo
echo '======================================================================'
echo "PASSED: $PASS    FAILED: $FAIL"
echo '======================================================================'
[ "$FAIL" -eq 0 ]
