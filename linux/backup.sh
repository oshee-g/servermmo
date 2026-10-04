#!/usr/bin/env bash
#
# Backs up the worlds, plugins and configs into the backups folder.
# Best used while the server is stopped, or right after running
# "save-all" in the server console.
#
set -u
cd "$(cd "$(dirname "$0")" && pwd)"

DEST="backups"
STAMP="$(date +%Y-%m-%d_%H-%M-%S)"

echo "===================================================================="
echo "  Server backup"
echo "===================================================================="
echo
echo "  Worlds, plugins and config files go to: $DEST/"
echo "  Run \"save-all\" in the server console first if the server is up."
echo
printf "  Press Enter to continue (Ctrl+C to abort) ... "
# shellcheck disable=SC2034
read -r _ || true

if ! command -v tar >/dev/null 2>&1; then
    echo "[X] tar is not installed - copy the world folders manually."
    exit 1
fi

items=()
for pattern in 'world*' plugins config server.properties bukkit.yml spigot.yml \
               permissions.yml ops.json whitelist.json banned-players.json banned-ips.json; do
    for match in $pattern; do
        [ -e "$match" ] && items+=("$match")
    done
done

if [ "${#items[@]}" -eq 0 ]; then
    echo "[X] Nothing to back up here."
    echo "    Expected at least a \"world\" folder - start the server once."
    exit 1
fi

mkdir -p "$DEST"
TARGET="$DEST/backup-$STAMP.tar.gz"

echo "  Items: ${items[*]}"
if tar -czf "$TARGET" "${items[@]}"; then
    size="$(du -h "$TARGET" | awk '{print $1}')"
    echo "  Done: $TARGET ($size)"
    echo "  Tip: a backup taken while the server is running may be inconsistent -"
    echo "       run \"save-all\" (or stop the server) before backing up."
else
    echo "[X] Backup failed."
    exit 1
fi
