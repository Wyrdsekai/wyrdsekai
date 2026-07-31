#!/bin/bash
# gui-uninstall.sh — remove the package, invoked ONCE via pkexec from the
# desktop app's Uninstall flow. Non-interactive, runs as root.
#
#   gui-uninstall.sh [--wipe-data]
#
# Removing the package triggers the .deb prerm/postrm (stop + disable services,
# drop symlinks). --wipe-data additionally purges config + the world data dir.
set -u
export DEBIAN_FRONTEND=noninteractive

WIPE="${1:-}"
DATA_DIR=/var/lib/wyrdsekai

if [ "$WIPE" = "--wipe-data" ]; then
    apt-get purge -y wyrdsekai 2>/dev/null || dpkg --purge wyrdsekai 2>/dev/null || true
    rm -rf "$DATA_DIR" /etc/wyrdsekai 2>/dev/null || true
else
    apt-get remove -y wyrdsekai 2>/dev/null || dpkg --remove wyrdsekai 2>/dev/null || true
fi

echo "uninstalled"
exit 0
