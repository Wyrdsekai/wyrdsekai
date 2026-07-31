#!/usr/bin/env bash
# End-to-end driver for the public-relay mobile test.
#
#   1. Wipe app data (`pm clear`) — fresh slate, no stale input / DB / prefs.
#   2. Seed RKStorage with a fresh α-minted MCP session token + relay URL.
#   3. Seed the HouseholdTrustStore SharedPreferences with the relay's CA pin.
#   4. Launch the app; wait for the in-app HTTPS probe to mark "connected".
#   5. Tail α + β journalctl in the background.
#   6. Run the probe-relay-tell Maestro flow (tell + library + journal).
#   7. Assert: each of the three commands shows up server-side.
#
# Three independent server-side proofs are checked at the end so a single UI
# tap-miss doesn't masquerade as a passing test.
#
# Env (with defaults):
#   ALPHA_HOST       home-server
#   ALPHA_PORT       7070
#   BETA_HOST        test-node
#   RELAY_HOST_IP    192.0.2.108
#   RELAY_URL        https://$RELAY_HOST_IP
#   ADB_HOST         dev-laptop
#   PHONE_PKG        org.wyrdsekai.rn
#   WYRD_USERNAME    (required — must already exist on α)
#   WYRD_PASSWORD    (required)
#   TELL_TARGET      beta.probe-beta
set -uo pipefail

ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_HOST_IP="${RELAY_HOST_IP:-192.0.2.108}"
RELAY_URL="${RELAY_URL:-https://$RELAY_HOST_IP}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"
: "${WYRD_USERNAME:?WYRD_USERNAME required}"
: "${WYRD_PASSWORD:?WYRD_PASSWORD required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

log() { printf '\n[run-probe] %s\n' "$*" >&2; }

# ── Step 1: pm clear ────────────────────────────────────────────────────────
log "1/7 pm clear $PHONE_PKG on $ADB_HOST"
ssh "$ADB_HOST" "adb shell pm clear $PHONE_PKG" >/dev/null

# ── Step 2: seed RKStorage ──────────────────────────────────────────────────
log "2/7 seed RKStorage via probe_relay_phone.sh"
ALPHA_HOST="$ALPHA_HOST" ALPHA_PORT="$ALPHA_PORT" \
  RELAY_URL="$RELAY_URL" ADB_HOST="$ADB_HOST" PHONE_PKG="$PHONE_PKG" \
  WYRD_USERNAME="$WYRD_USERNAME" WYRD_PASSWORD="$WYRD_PASSWORD" \
  bash "$SCRIPT_DIR/probe_relay_phone.sh" 2>&1 | tail -25
# probe_relay_phone.sh launches the app at the end — force-stop again so we
# can apply trust prefs before the next launch reads them at onCreate.
ssh "$ADB_HOST" "adb shell am force-stop $PHONE_PKG" >/dev/null

# ── Step 3: seed trust prefs ────────────────────────────────────────────────
log "3/7 seed HouseholdTrustStore via seed_relay_trust.sh"
RELAY_HOST="$RELAY_HOST_IP" ADB_HOST="$ADB_HOST" PHONE_PKG="$PHONE_PKG" \
  bash "$SCRIPT_DIR/seed_relay_trust.sh" 2>&1 | tail -10

# ── Step 4: launch fresh + wait for connection ──────────────────────────────
log "4/7 launch app, wait 15s for HTTPS probe to land"
ssh "$ADB_HOST" "adb logcat -c && adb shell am start -n $PHONE_PKG/.MainActivity" >/dev/null
sleep 15

# ── Step 5: start journal tails ─────────────────────────────────────────────
# Two flavors of server install:
#   • Source install (home-server): JVM as foreground process, logs to
#     ~/src/wyrdsekai/logs/wyrdsekai.log. journalctl is empty.
#   • .deb install (test-node): systemd unit, logs to journalctl.
# Try journalctl first; if the unit isn't installed, fall back to tail -F on
# the source-mode log file. Either way, the captured stream feeds the grep
# tally at the end.
log "5/7 start α + β log tails"
alpha_log=/tmp/run-probe-alpha.log
beta_log=/tmp/run-probe-beta.log
: > "$alpha_log"
: > "$beta_log"

start_tail() {
  local host="$1" outfile="$2"
  # `set -e` is intentionally NOT set inside the heredoc — we want to fall
  # through to the source-mode tail if journalctl fails (no unit installed).
  ssh "$host" 'bash -s' >"$outfile" 2>&1 <<'REMOTE' &
if systemctl list-unit-files wyrdsekai.service >/dev/null 2>&1 \
   && systemctl is-active wyrdsekai.service >/dev/null 2>&1; then
  exec sudo -n journalctl -u wyrdsekai --since "10 seconds ago" -f 2>&1
fi
# Source-mode fallback: tail the wyrd log file the source-mode launcher writes.
src_log=""
for cand in \
  "$HOME/src/wyrdsekai/logs/wyrdsekai.log" \
  "/var/log/wyrdsekai/wyrdsekai.log"; do
  [ -f "$cand" ] && { src_log="$cand"; break; }
done
if [ -z "$src_log" ]; then
  echo "[run-probe-tail] no wyrdsekai log found on $(hostname)" >&2
  exit 1
fi
exec tail -F -n 0 "$src_log"
REMOTE
  echo $!
}

alpha_pid=$(start_tail "$ALPHA_HOST" "$alpha_log")
beta_pid=$(start_tail "$BETA_HOST" "$beta_log")
sleep 2

# ── Step 6: run Maestro flow ────────────────────────────────────────────────
log "6/7 run Maestro probe-relay-tell.yaml against $ADB_HOST"
flow="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-tell.yaml"
# Maestro on Linux talks to ADB on $ADB_HOST via TCP. Re-use the same adb
# connection by setting ADB_SERVER_SOCKET to the remote.
maestro_log=/tmp/run-probe-maestro.log
ssh "$ADB_HOST" "rm -f /tmp/probe_flow.yaml" >/dev/null
scp "$flow" "$ADB_HOST:/tmp/probe_flow.yaml" >/dev/null
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro test /tmp/probe_flow.yaml" \
  > "$maestro_log" 2>&1 \
  || log "WARN: maestro on $ADB_HOST returned non-zero — check $maestro_log"
tail -25 "$maestro_log"

# Give the server a moment to process the last command before we read logs
sleep 5

# ── Step 7: verify server-side ──────────────────────────────────────────────
log "7/7 verify α + β journal hits"
kill $alpha_pid $beta_pid 2>/dev/null
wait 2>/dev/null

tell_hit=$(grep -c "Incoming cross-zone tell from ${WYRD_USERNAME}@alpha" "$beta_log" || true)
# Server logs use `[Library] search …` and `[Study] journal write …` with a
# bracketed prefix. The `\[Library\]` pattern matches that literal bracket.
# Keep the alternates for older log shapes (Lucene.*query, JournalService etc.)
# so this script stays compatible with both source-mode and .deb logs.
lib_hit=$(grep -ciE 'library_card|\[Library\][[:space:]]+search|library search|Lucene.*query' "$alpha_log" || true)
journal_hit=$(grep -ciE 'journal\.append|journal entry|Journal.*write|JournalService|\[Study\][[:space:]]+journal[[:space:]]+write' "$alpha_log" || true)

echo
echo "──────── server-side proof tally ────────"
printf '  1. Cross-zone tell on β       : %s\n' "$([ "$tell_hit" -gt 0 ] && echo PASS || echo FAIL)  ($tell_hit hits)"
printf '  2. Library search on α        : %s\n' "$([ "$lib_hit" -gt 0 ] && echo PASS || echo FAIL)  ($lib_hit hits)"
printf '  3. Journal write on α         : %s\n' "$([ "$journal_hit" -gt 0 ] && echo PASS || echo FAIL)  ($journal_hit hits)"
echo
echo "Logs: $alpha_log / $beta_log / $maestro_log"

# Exit code reflects overall pass
if [ "$tell_hit" -gt 0 ] && [ "$lib_hit" -gt 0 ] && [ "$journal_hit" -gt 0 ]; then
  exit 0
else
  exit 1
fi
