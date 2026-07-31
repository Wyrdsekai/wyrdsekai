#!/bin/bash
# gui-onboard.sh — first-run apply, invoked ONCE via pkexec (PolicyKit auth)
# from the desktop app. Non-interactive, runs as root.
#
#   gui-onboard.sh "<name>" "<lang>" "<mode>" ["<apiKey>"]
#
# Persists the wizard's choices to the system conf, reseeds so the companion is
# (re)born with the chosen name, then restarts the systemd service.
set -u

NAME="${1:-Wyrd}"
LANG_CODE="${2:-en}"
MODE="${3:-local}"     # local | cloud | later
APIKEY="${4:-}"

WYRD=/usr/local/bin/wyrd
CONF=/etc/wyrdsekai/wyrdsekai.conf

"$WYRD" config set "WYRDSEKAI_COMPANION_NAME=$NAME" >/dev/null 2>&1 || true
# WYRDSEKAI_LOCALE is the canonical locale key (config audit 2026-07-11);
# WYRDSEKAI_LANG is still written for compatibility with older readers.
"$WYRD" config set "WYRDSEKAI_LOCALE=$LANG_CODE"    >/dev/null 2>&1 || true
"$WYRD" config set "WYRDSEKAI_LANG=$LANG_CODE"      >/dev/null 2>&1 || true
case "$MODE" in
  cloud)
    "$WYRD" config set "WYRDSEKAI_INFERENCE_MODE=cloud" >/dev/null 2>&1 || true
    [ -n "$APIKEY" ] && "$WYRD" config set "ANTHROPIC_API_KEY=$APIKEY" >/dev/null 2>&1 || true
    ;;
  local)
    "$WYRD" config set "WYRDSEKAI_INFERENCE_MODE=local" >/dev/null 2>&1 || true
    ;;
esac

# Reseed the auto-born default soul so the next boot (re)births it with the
# chosen name — first-run only, so nothing of value is lost.
DATA_DIR="$(grep -E '^WYRDSEKAI_DATA_DIR=' "$CONF" 2>/dev/null | tail -1 | cut -d= -f2-)"
[ -z "$DATA_DIR" ] && DATA_DIR=/var/lib/wyrdsekai

systemctl stop wyrdsekai 2>/dev/null || true
rm -f "$DATA_DIR"/world.db* 2>/dev/null || true
systemctl restart wyrdsekai 2>/dev/null || systemctl start wyrdsekai 2>/dev/null || true

echo "onboard-done"
exit 0
