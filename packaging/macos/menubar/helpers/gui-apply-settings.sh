#!/bin/bash
# gui-apply-settings.sh — apply edited node settings, invoked ONCE with
# administrator privileges from the menu-bar Settings window. Non-interactive.
#
#   gui-apply-settings.sh "KEY=VALUE" "KEY=VALUE" ...
#
# Writes each KEY=VALUE to the system conf via `wyrd config set`, then restarts
# the root LaunchDaemon so changes take effect. Runs as root.
set -u

WYRD=/usr/local/bin/wyrd
SVC=system/com.wyrdsekai.server

for kv in "$@"; do
  [ -z "$kv" ] && continue
  "$WYRD" config set "$kv" >/dev/null 2>&1 || true
done

/bin/launchctl kickstart -k "$SVC" 2>/dev/null || true
echo "settings-applied"
exit 0
