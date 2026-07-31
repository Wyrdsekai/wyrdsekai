#!/bin/bash
# gui-onboard.sh — first-run apply, invoked ONCE with administrator privileges
# from the menu-bar app (Touch ID / password prompt). Non-interactive.
#
#   gui-onboard.sh "<name>" "<lang>" "<mode>" ["<apiKey>"]
#
# Applies the wizard's choices to the system conf, reseeds so the companion is
# (re)born with the chosen name, then (re)starts the root LaunchDaemon. Runs as
# root. Every step is best-effort — a failure in one shouldn't strand the user.
set -u

NAME="${1:-Wyrd}"
LANG_CODE="${2:-en}"
MODE="${3:-local}"     # local | cloud | later
APIKEY="${4:-}"

WYRD=/usr/local/bin/wyrd
SVC=system/com.wyrdsekai.server
PLIST=/Library/LaunchDaemons/com.wyrdsekai.server.plist
CONF=/etc/wyrdsekai/wyrdsekai.conf

# Pin every `wyrd config set` below to the EXACT system conf the menu-bar app's
# needsOnboarding() reads. This helper runs as root via the osascript admin
# prompt (minimal env, HOME=/var/root), where bin/wyrd's own config-file
# resolution can fall back to a per-user file ($DATA_DIR/wyrdsekai.conf) instead
# of /etc/wyrdsekai/wyrdsekai.conf — leaving the system conf without a
# WYRDSEKAI_COMPANION_NAME= line, so the onboarding wizard re-pops on every
# launch. Ensuring the file exists first also stops bin/wyrd's
# "[[ ! -f CONFIG_FILE ]] → DATA_DIR fallback" from redirecting the write.
mkdir -p "$(dirname "$CONF")"
[ -f "$CONF" ] || echo "# Wyrdsekai configuration" > "$CONF"
export WYRDSEKAI_CONFIG_FILE="$CONF"

# 1) Persist choices to the system conf (root-writable).
"$WYRD" config set "WYRDSEKAI_COMPANION_NAME=$NAME"  >/dev/null 2>&1 || true
# WYRDSEKAI_LOCALE is the canonical locale key (config audit 2026-07-11);
# WYRDSEKAI_LANG is still written for compatibility with older readers.
"$WYRD" config set "WYRDSEKAI_LOCALE=$LANG_CODE"     >/dev/null 2>&1 || true
"$WYRD" config set "WYRDSEKAI_LANG=$LANG_CODE"       >/dev/null 2>&1 || true
case "$MODE" in
  cloud)
    "$WYRD" config set "WYRDSEKAI_INFERENCE_MODE=cloud" >/dev/null 2>&1 || true
    [ -n "$APIKEY" ] && "$WYRD" config set "ANTHROPIC_API_KEY=$APIKEY" >/dev/null 2>&1 || true
    ;;
  local)
    "$WYRD" config set "WYRDSEKAI_INFERENCE_MODE=local" >/dev/null 2>&1 || true
    ;;
esac

# 2) Resolve the data dir from the conf so we can reseed the auto-born default
# soul — first-run only, so nothing of value is lost. Clearing world.db* makes
# the next boot re-seed the foundation world AND (re)birth the companion with
# WYRDSEKAI_COMPANION_NAME.
DATA_DIR="$(grep -E '^WYRDSEKAI_DATA_DIR=' "$CONF" 2>/dev/null | tail -1 | cut -d= -f2-)"
[ -z "$DATA_DIR" ] && DATA_DIR="$HOME/.wyrdsekai"

/bin/launchctl bootout "$SVC" 2>/dev/null || true
# bootout is async — wait (bounded) for teardown before touching its files.
for _i in 1 2 3 4 5 6 7 8 9 10; do
  /bin/launchctl print "$SVC" >/dev/null 2>&1 || break
  sleep 1
done
rm -f "$DATA_DIR"/world.db* 2>/dev/null || true

# 3) (Re)start the daemon so the soul is born with the chosen name.
/bin/launchctl enable "$SVC" 2>/dev/null || true
/bin/launchctl bootstrap system "$PLIST" 2>/dev/null || true
/bin/launchctl kickstart -k "$SVC" 2>/dev/null || true

# 4) Local inference is set up by the MENU-BAR APP after this helper returns,
# in USER context (`wyrd inference setup-local`). It is NOT triggered here:
# this helper runs as root via the osascript admin prompt, and a backgrounded
# `sudo -u <user>` from that context does not reliably survive — it silently
# no-ops, leaving the companion with no brain. The app (which already runs as
# the logged-in user) is the single, reliable trigger. See Windows.swift create().

echo "onboard-done"
exit 0
