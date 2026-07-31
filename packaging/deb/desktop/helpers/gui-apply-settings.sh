#!/bin/bash
# gui-apply-settings.sh — apply edited node settings, invoked ONCE via pkexec
# from the Settings window. Non-interactive, runs as root.
#
#   gui-apply-settings.sh "KEY=VALUE" "KEY=VALUE" ...
set -u

WYRD=/usr/local/bin/wyrd
for kv in "$@"; do
  [ -z "$kv" ] && continue
  "$WYRD" config set "$kv" >/dev/null 2>&1 || true
done
systemctl restart wyrdsekai 2>/dev/null || true
echo "settings-applied"
exit 0
