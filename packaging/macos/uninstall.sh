#!/usr/bin/env bash
#
# uninstall.sh — Remove Wyrdsekai from macOS
#
# Usage: sudo ./uninstall.sh
#
# Stops + removes the system daemons (server, oracle), the per-user GUI agents
# (menu-bar app, MLX voice), the menu-bar app in /Applications, the CLI symlinks,
# /usr/local/wyrdsekai, and the package receipt. User data in ~/.wyrdsekai is KEPT.
#
set -uo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Run with sudo: sudo $0"
    exit 1
fi

# The real (non-root) user whose GUI agents + home we touch.
REAL_USER="${SUDO_USER:-}"
if [[ -n "$REAL_USER" ]]; then
    REAL_UID="$(id -u "$REAL_USER" 2>/dev/null || echo "")"
    REAL_HOME="$(eval echo "~$REAL_USER")"
else
    REAL_UID=""
    REAL_HOME="$HOME"
fi

echo "Removing Wyrdsekai..."

# Stop + remove the per-user GUI agents (menu-bar app + MLX voice).
if [[ -n "$REAL_UID" ]]; then
    for agent in com.wyrdsekai.menubar com.wyrdsekai.mlx-voice; do
        launchctl bootout "gui/$REAL_UID/$agent" 2>/dev/null || true
    done
fi
rm -f "$REAL_HOME/Library/LaunchAgents/com.wyrdsekai.menubar.plist" \
      "$REAL_HOME/Library/LaunchAgents/com.wyrdsekai.mlx-voice.plist"

# Stop + remove the system daemons (node server + oracle).
for svc in com.wyrdsekai.server com.wyrdsekai.oracle org.wyrdsekai.server; do
    launchctl bootout "system/$svc" 2>/dev/null || true
done
rm -f /Library/LaunchDaemons/com.wyrdsekai.server.plist \
      /Library/LaunchDaemons/com.wyrdsekai.oracle.plist \
      /Library/LaunchDaemons/org.wyrdsekai.server.plist   # written by pre-0.2.2 `wyrd start`

# Kill any still-running processes. Booting out a launchd job does NOT terminate a process
# that is already running (e.g. the menu-bar app, or a server that respawned just before the
# plist was removed), so finish them off explicitly.
pkill -9 -f "com.wyrdsekai"     2>/dev/null || true   # daemons
pkill -9 -f "Wyrdsekai.app"     2>/dev/null || true   # menu-bar app
pkill -9 -f "wyrdsekai/server"  2>/dev/null || true   # server jar
pkill -9 -f "mlx_lm.server"     2>/dev/null || true   # MLX inference

# Remove the menu-bar app.
rm -rf /Applications/Wyrdsekai.app

# Remove CLI symlinks.
rm -f /usr/local/bin/wyrd \
      /usr/local/bin/wyrdsekai-server \
      /usr/local/bin/wyrdsekai-cli \
      /usr/local/bin/wyrdsekai-daemon

# Remove the application payload.
rm -rf /usr/local/wyrdsekai

# Forget the package receipt.
pkgutil --forget org.wyrdsekai.app 2>/dev/null || true

# The server ran as a root LaunchDaemon (LNP bypass), so files it created in
# the user's ~/.wyrdsekai are root-owned. The daemon repairs ownership at
# start/stop, but after THIS point no daemon will ever run again — hand the
# kept data dir back to its owner so "rm -rf ~/.wyrdsekai" (and everyday use)
# works without sudo. (Observed 2026-07-30: skills/ + vault/ left root-owned.)
if [[ -n "$REAL_USER" && -d "$REAL_HOME/.wyrdsekai" ]]; then
    chown -R "$REAL_USER" "$REAL_HOME/.wyrdsekai" 2>/dev/null || true
fi

echo ""
echo "Wyrdsekai removed."
echo ""
echo "User data at ~/.wyrdsekai was NOT removed."
echo "To remove it: rm -rf ~/.wyrdsekai"
