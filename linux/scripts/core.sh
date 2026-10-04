#!/usr/bin/env bash
# Shared helpers for start.sh / update.sh / backup.sh (Linux/macOS).
# Sourced, not executed: . scripts/core.sh

have() { command -v "$1" >/dev/null 2>&1; }

if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_RED=$'\033[31m'; C_YEL=$'\033[33m'
else
    C_RESET=""; C_BOLD=""; C_RED=""; C_YEL=""
fi

say()  { printf '%s\n' "$*"; }
info() { printf '      %s\n' "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$C_YEL" "$C_RESET" "$*"; }
fail() { printf '%s[X]%s %s\n' "$C_RED" "$C_RESET" "$*"; }

# --- tiny JSON reader ---------------------------------------------------
# Prefers python3; falls back to grep/sed, which works for the flat keys
# used by the PaperMC API responses.
#   json_value '<json>' '["downloads"]["server:default"]["url"]' '"url":"'
json_value() {
    local doc="$1" path="$2" fallback_key="$3"
    if have python3; then
        printf '%s' "$doc" | python3 -c 'import json,sys
try:
    data = json.load(sys.stdin)
    value = eval("data" + sys.argv[1])
    if value is not None:
        print(value)
except Exception:
    pass' "$path" 2>/dev/null && return 0
    fi
    if [ -n "$fallback_key" ]; then
        printf '%s' "$doc" | grep -o "${fallback_key}[^\",}]*" | head -1 | sed "s/^${fallback_key}//"
    fi
}

sha256_of() {
    if have sha256sum; then sha256sum "$1" | awk '{print $1}';
    elif have shasum;  then shasum -a 256 "$1" | awk '{print $1}';
    else echo ""; fi
}

# Like curl/wget without caring which one is installed.
http_get() {  # http_get <url> <output-file|->
    local url="$1" out="${2:--}"
    if have curl; then
        if [ "$out" = "-" ]; then curl -fsSL --max-time 60 -A "servermmo-server-pack/1.0" "$url"
        else curl -fL --retry 3 --retry-delay 2 --max-time 900 -A "servermmo-server-pack/1.0" -o "$out" "$url"; fi
    elif have wget; then
        if [ "$out" = "-" ]; then wget -qO- --timeout=60 "$url"
        else wget -q --tries=3 -O "$out" "$url"; fi
    else
        return 127
    fi
}

# --- Java ---------------------------------------------------------------
resolve_java() {   # echoes the java executable, returns 1 when not found
    if [ -n "${JAVA_CMD:-}" ]; then
        if have "$JAVA_CMD"; then command -v "$JAVA_CMD"; return 0; fi
        if [ -x "$JAVA_CMD" ]; then printf '%s\n' "$JAVA_CMD"; return 0; fi
    fi
    if have java; then command -v java; return 0; fi
    return 1
}

java_major_version() {   # echoes the major version (25, 21, ...) or nothing
    local java="$1" raw ver major
    raw="$("$java" -version 2>&1 | head -1)"
    ver="$(printf '%s' "$raw" | sed -n 's/.*version "\([0-9][0-9]*\(\.[0-9][0-9]*\)*\).*/\1/p')"
    major="${ver%%.*}"
    if [ "$major" = "1" ]; then major="$(printf '%s' "$ver" | cut -d. -f2)"; fi
    printf '%s' "$major"
}

# --- Paper core ---------------------------------------------------------
DOWNLOAD_PAGE="https://papermc.io/downloads/paper"

# Downloads the newest Paper build of $PAPER_VERSION into $SERVER_JAR.
# Keeps the previous core as $SERVER_JAR.old. Returns 0 on success.
get_core() {
    local api build build_id channel url sha size tmp size_mb
    api="https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest"

    if ! have curl && ! have wget; then
        fail "Neither curl nor wget is installed - cannot download the core automatically."
        return 1
    fi

    info "Asking PaperMC for the latest Paper $PAPER_VERSION build ..."
    build="$(http_get "$api" - 2>/dev/null)" || build=""
    if [ -z "$build" ]; then
        fail "Could not reach the PaperMC API."
        info "Manual download: $DOWNLOAD_PAGE"
        return 1
    fi

    build_id="$(json_value "$build" '["id"]' '"id":')"
    channel="$(json_value "$build" '["channel"]' '"channel":"')"
    url="$(json_value "$build" '["downloads"]["server:default"]["url"]' '"url":"')"
    sha="$(json_value "$build" '["downloads"]["server:default"]["checksums"]["sha256"]' '"sha256":"')"
    size="$(json_value "$build" '["downloads"]["server:default"]["size"]' '"size":')"
    sha="$(printf '%s' "$sha" | tr 'A-Z' 'a-z')"

    if [ -z "$url" ]; then
        fail "The PaperMC API returned no server jar for $PAPER_VERSION."
        info "Manual download: $DOWNLOAD_PAGE"
        return 1
    fi

    size_mb="?"
    case "$size" in ''|*[!0-9]*) ;; *) size_mb=$(( size / 1024 / 1024 )) ;; esac
    info "Latest build: ${build_id:-?} [${channel:-?}] - $(basename "$url") (${size_mb} MB)"

    if [ -f "$SERVER_JAR" ] && [ -n "$sha" ]; then
        if [ "$(sha256_of "$SERVER_JAR")" = "$sha" ]; then
            info "The installed core is already the latest build. Nothing to do."
            return 0
        fi
        info "A different core build is installed - replacing it."
    fi

    tmp="$SERVER_JAR.download"
    rm -f "$tmp"
    info "Downloading $url"
    if ! http_get "$url" "$tmp" || [ ! -s "$tmp" ]; then
        rm -f "$tmp"
        fail "Download failed."
        info "Manual download: $DOWNLOAD_PAGE"
        return 1
    fi

    if [ -n "$sha" ]; then
        local got; got="$(sha256_of "$tmp")"
        if [ "$got" != "$sha" ]; then
            rm -f "$tmp"
            fail "SHA-256 mismatch (expected $sha, got ${got:-none}). The file was discarded."
            return 1
        fi
        info "SHA-256 verified."
    else
        warn "The API returned no checksum - skipping verification."
    fi

    if [ -f "$SERVER_JAR" ]; then
        rm -f "$SERVER_JAR.old"
        mv -f "$SERVER_JAR" "$SERVER_JAR.old"
        info "Previous core kept as $SERVER_JAR.old"
    fi
    mv -f "$tmp" "$SERVER_JAR"
    info "Done: $SERVER_JAR = Paper $PAPER_VERSION build ${build_id:-?}."
    return 0
}
