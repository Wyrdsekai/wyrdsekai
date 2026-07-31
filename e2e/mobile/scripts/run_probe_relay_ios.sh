#!/usr/bin/env bash
# iOS Simulator e2e driver — analog of run_probe_relay.sh.
#
# Differences from the Android runner:
#   • No external seed step (iOS MMKV+Keychain isn't safely writable from the
#     host). Instead the Maestro flow drives the Welcome screen UI.
#   • Maestro flow handles `clearState: true` itself (resets app each run).
#   • Trust pinning: relies on host-installed household CA via
#     `xcrun simctl keychain booted add-root-cert`. Production-iOS still needs
#     a native HouseholdTrustModule.swift (open task).
#
# Env (with defaults):
#   IOS_HOST         mac-node (where Xcode + Simulator live)
#   SIM_DEVICE       wyrd-e2e-ios (UDID/name of booted simulator)
#   ALPHA_HOST       home-server
#   ALPHA_PORT       7070
#   BETA_HOST        test-node
#   RELAY_URL        https://relay-node (override to use IP if DNS not configured)
#   WYRD_SERVER_URL  same as RELAY_URL but explicit
#   PHONE_PKG        org.wyrdsekai.rn
#   TELL_TARGET      beta.probe-beta
set -uo pipefail

IOS_HOST="${IOS_HOST:-mac-node}"
SIM_DEVICE="${SIM_DEVICE:-wyrd-e2e-ios}"
ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_URL="${RELAY_URL:-https://relay-node}"
WYRD_SERVER_URL="${WYRD_SERVER_URL:-$RELAY_URL}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

log() { printf '\n[run-probe-ios] %s\n' "$*" >&2; }

# ── Step 1: ensure Simulator booted ──────────────────────────────────────────
log "1/6 ensure Simulator booted"
ssh "$IOS_HOST" "xcrun simctl boot '$SIM_DEVICE' 2>/dev/null; \
  xcrun simctl list devices booted | head -5" 2>&1 | tail -5

# ── Step 2: seed app via JSON file in Documents/ (mints MCP creds, etc.) ────
log "2/6 seed app via probe_relay_phone_ios.sh"
IOS_HOST="$IOS_HOST" SIM_DEVICE="$SIM_DEVICE" \
  ALPHA_HOST="$ALPHA_HOST" ALPHA_PORT="$ALPHA_PORT" \
  RELAY_URL="$RELAY_URL" PHONE_PKG="$PHONE_PKG" \
  WYRD_USERNAME="$WYRD_USERNAME" WYRD_PASSWORD="$WYRD_PASSWORD" \
  bash "$SCRIPT_DIR/probe_relay_phone_ios.sh" 2>&1 | tail -10

# ── Step 3: start α + β log tails ────────────────────────────────────────────
log "3/6 start α + β log tails"
alpha_log=/tmp/run-probe-ios-alpha.log
beta_log=/tmp/run-probe-ios-beta.log
: > "$alpha_log"
: > "$beta_log"

start_tail() {
  local host="$1" outfile="$2"
  ssh "$host" 'bash -s' >"$outfile" 2>&1 <<'REMOTE' &
if systemctl list-unit-files wyrdsekai.service >/dev/null 2>&1 \
   && systemctl is-active wyrdsekai.service >/dev/null 2>&1; then
  exec sudo -n journalctl -u wyrdsekai --since "10 seconds ago" -f 2>&1
fi
src_log=""
for cand in "$HOME/src/wyrdsekai/logs/wyrdsekai.log" "/var/log/wyrdsekai/wyrdsekai.log"; do
  [ -f "$cand" ] && { src_log="$cand"; break; }
done
[ -z "$src_log" ] && { echo "[tail] no log on $(hostname)" >&2; exit 1; }
exec tail -F -n 0 "$src_log"
REMOTE
  echo $!
}

alpha_pid=$(start_tail "$ALPHA_HOST" "$alpha_log")
beta_pid=$(start_tail "$BETA_HOST" "$beta_log")
sleep 2

# ── Step 4: run Maestro probe flow on iOS Simulator ─────────────────────────
log "4/6 run Maestro probe-relay-tell-ios.yaml on $IOS_HOST/$SIM_DEVICE"
flow="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-tell-ios.yaml"
maestro_log=/tmp/run-probe-ios-maestro.log
scp -q "$flow" "$IOS_HOST:/tmp/probe_flow_ios.yaml"
# Maestro picks up the booted iOS Simulator automatically when no --device given.
# WYRD_SERVER_URL is interpolated into the YAML via Maestro's ${ENV} syntax.
ssh "$IOS_HOST" "WYRD_SERVER_URL='$WYRD_SERVER_URL' /Users/you/.maestro/bin/maestro test /tmp/probe_flow_ios.yaml" \
  > "$maestro_log" 2>&1 \
  || log "WARN: maestro returned non-zero — check $maestro_log"
tail -25 "$maestro_log"

# ── Step 5: pause to let server-side commit ─────────────────────────────────
sleep 5

# ── Step 6: verify server-side ──────────────────────────────────────────────
log "5/6 verify α + β hits"
kill $alpha_pid $beta_pid 2>/dev/null
wait 2>/dev/null

tell_hit=$(grep -ciE "Incoming cross-zone tell from .*@alpha" "$beta_log" || true)
lib_hit=$(grep -ciE 'library_card|\[Library\][[:space:]]+search|library search|Lucene.*query' "$alpha_log" || true)
journal_hit=$(grep -ciE 'journal\.append|journal entry|Journal.*write|JournalService|\[Study\][[:space:]]+journal[[:space:]]+write' "$alpha_log" || true)

echo
echo "──────── iOS Simulator server-side proof tally ────────"
printf '  1. Cross-zone tell on β       : %s\n' "$([ "$tell_hit" -gt 0 ] && echo PASS || echo FAIL)  ($tell_hit hits)"
printf '  2. Library search on α        : %s\n' "$([ "$lib_hit" -gt 0 ] && echo PASS || echo FAIL)  ($lib_hit hits)"
printf '  3. Journal write on α         : %s\n' "$([ "$journal_hit" -gt 0 ] && echo PASS || echo FAIL)  ($journal_hit hits)"
echo
echo "Logs: $alpha_log / $beta_log / $maestro_log"

if [ "$tell_hit" -gt 0 ] && [ "$lib_hit" -gt 0 ] && [ "$journal_hit" -gt 0 ]; then
  exit 0
else
  exit 1
fi
