#!/bin/bash
# gui-uninstall.sh — full teardown, invoked ONCE with administrator privileges
# from the menu-bar Uninstall flow. Non-interactive (the macOS .pkg has no
# uninstaller; `wyrd uninstall` is interactive, so the GUI uses this instead).
#
#   gui-uninstall.sh [--wipe-data]
#
# Boots out the daemons, removes the program + plists + symlinks + the .app,
# forgets the receipt, and — only with --wipe-data — deletes the user's world
# data. Runs as root. The caller (the app) removes its own per-user LaunchAgents
# afterward, in user context.
set -u

WIPE="${1:-}"
CONF=/etc/wyrdsekai/wyrdsekai.conf

# Resolve the invoking (non-root) user — needed for the world-data path AND to
# bootout the per-user GUI agents from this root context. We run as root, so
# $HOME is root's; never trust it.
REAL_USER="${SUDO_USER:-$(/usr/bin/stat -f %Su /dev/console 2>/dev/null)}"
[ "$REAL_USER" = "root" ] && REAL_USER=""
REAL_UID=""
[ -n "$REAL_USER" ] && REAL_UID="$(/usr/bin/id -u "$REAL_USER" 2>/dev/null)"

DATA_DIR="$(grep -E '^WYRDSEKAI_DATA_DIR=' "$CONF" 2>/dev/null | tail -1 | cut -d= -f2-)"
# Fallback when the conf doesn't pin a data dir (or is already gone). Without
# this, --wipe-data would silently skip the wipe and leave root-owned data.
[ -z "$DATA_DIR" ] && [ -n "$REAL_USER" ] && DATA_DIR="/Users/$REAL_USER/.wyrdsekai"

# ── Stop every managed service: current (com.*) AND legacy (org.*) labels ──
# The daemon label was renamed com.↔org. across versions; an uninstaller that
# knew only one prefix left the other loaded forever (the "sticky daemon" bug).
for label in com.wyrdsekai.server com.wyrdsekai.oracle \
             org.wyrdsekai.server org.wyrdsekai.oracle; do
    /bin/launchctl bootout "system/$label" 2>/dev/null || true
done
# Per-user GUI agents (menu-bar app + MLX voice). The app clears these in user
# context too, but do it here so a root-only uninstall is also complete.
if [ -n "$REAL_UID" ]; then
    for label in com.wyrdsekai.menubar com.wyrdsekai.mlx-voice \
                 org.wyrdsekai.menubar org.wyrdsekai.mlx-voice; do
        /bin/launchctl bootout "gui/$REAL_UID/$label" 2>/dev/null || true
    done
fi

# ── Kill local-inference backends NOT managed by launchd ──
# `wyrd setup-local` / the daemon spawn the MLX drive+voice (mlx_runtime.py /
# mlx_lm.server) and the CPU-fallback llama-server directly — booting the daemon
# does NOT reap them, so they orphan and keep listening on :8200/:8201. Kill by
# pattern, then sweep the ports for any survivor.
for pat in mlx_runtime.py mlx_lm.server llama-server oracle-server \
           "wyrdsekai/server" snapshot_download; do
    /usr/bin/pkill -9 -f "$pat" 2>/dev/null || true
done
for port in 8200 8201 7073; do
    for pid in $(/usr/sbin/lsof -ti :$port 2>/dev/null); do kill -9 "$pid" 2>/dev/null || true; done
done

# ── Remove system files (both label prefixes) ──
rm -f /Library/LaunchDaemons/com.wyrdsekai.*.plist \
      /Library/LaunchDaemons/org.wyrdsekai.*.plist
rm -f /usr/local/bin/wyrd /usr/local/bin/wyrdsekai-server \
      /usr/local/bin/wyrdsekai-cli /usr/local/bin/wyrdsekai-daemon \
      /usr/local/bin/wyrdsekai-*
rm -rf /usr/local/wyrdsekai
rm -rf /Applications/Wyrdsekai.app
# Forget ALL wyrdsekai pkg receipts (product id has been org.* and may vary).
for rcpt in $(pkgutil --pkgs 2>/dev/null | grep -i wyrdsekai); do
    pkgutil --forget "$rcpt" 2>/dev/null || true
done
pkgutil --forget org.wyrdsekai.app 2>/dev/null || true

# Optional world-data wipe (opt-in, irreversible).
if [ "$WIPE" = "--wipe-data" ] && [ -n "$DATA_DIR" ] && [ -d "$DATA_DIR" ]; then
    rm -rf "$DATA_DIR"
fi
# Leave /etc/wyrdsekai/wyrdsekai.conf untouched unless wiping (preserves config
# across reinstall, matching the .deb/.pkg convention).
if [ "$WIPE" = "--wipe-data" ]; then
    rm -rf /etc/wyrdsekai
fi

echo "uninstalled"
exit 0
