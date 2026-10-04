#!/usr/bin/env bash
#
# Updates the Paper core to the newest build of the version selected
# in settings.sh (PAPER_VERSION). Run it while the server is STOPPED.
#
set -u
cd "$(cd "$(dirname "$0")" && pwd)"

if [ -f settings.sh ]; then
    # shellcheck disable=SC1091
    . ./settings.sh
fi
: "${PAPER_VERSION:=26.3}"
: "${SERVER_JAR:=paper.jar}"

# shellcheck source=/dev/null
. ./scripts/core.sh

say "${C_BOLD}====================================================================${C_RESET}"
say "  Paper $PAPER_VERSION - core update"
say "${C_BOLD}====================================================================${C_RESET}"
say ""
say "  Stop the server before updating (type \"stop\" in its console),"
say "  and run ./backup.sh if the world matters."
say ""
printf "  Press Enter to continue (Ctrl+C to abort) ... "
# shellcheck disable=SC2034
read -r _ || true

if get_core; then
    say ""
    say "  Done. If the core was replaced, the old file is kept as"
    say "  $SERVER_JAR.old - delete it once the server runs fine."
    exit 0
fi

say ""
say "  Automatic update failed. Download the jar manually from"
say "  $DOWNLOAD_PAGE and replace $SERVER_JAR"
exit 1
