#!/usr/bin/env bash
# KMP variant of run_probe_relay_redeem.sh — NATS-only transport
# Mints invite on α, redeems via NATS
# host-side, seeds wyrdsekai_prefs_seed.xml on dev-laptop with the resulting
# username/password/token (NO @wyrd_zone_id — phone discovers via
# wyrd.discover.zone), launches, runs 3-command Maestro flow, verifies.
#
# Deltas vs RN-redeem version:
#   • Bundle id: org.wyrdsekai.kmp (KMP) — not org.wyrdsekai.rn
#   • Seed lands as shared_prefs/wyrdsekai_prefs_seed.xml (plaintext, imported
#     into EncryptedSharedPreferences on first launch then deleted).
#   • setupNatsServerClient() in NodeManager.android.kt drives the flow:
#     deriveRelayWss → discoverZone → TokenStore login → publish.
#
# Env (with defaults):
#   ALPHA_HOST=home-server  BETA_HOST=test-node  RELAY_HOST_IP=192.0.2.108
#   LLAMA_URL=http://home-server:8200   ADB_HOST=dev-laptop
#   PHONE_PKG=org.wyrdsekai.kmp  COMPANION_NAME=natstest-kmp
#   TELL_TARGET=beta.probe-beta
set -uo pipefail

ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_HOST_IP="${RELAY_HOST_IP:-192.0.2.108}"
RELAY_URL="${RELAY_URL:-https://$RELAY_HOST_IP}"
LLAMA_URL="${LLAMA_URL:-http://home-server:8200}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.kmp}"
COMPANION_NAME="${COMPANION_NAME:-natstest-kmp}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APK_REL="clients/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

log() { printf '\n[run-probe-redeem-kmp] %s\n' "$*" >&2; }

# ── Step 1: reinstall APK clean ─────────────────────────────────────────────
log "1/8 reinstall $PHONE_PKG on $ADB_HOST (clean state)"
ssh "$ADB_HOST" "adb shell am force-stop $PHONE_PKG; adb uninstall $PHONE_PKG" >/dev/null 2>&1 || true
# scp dev-box APK to $ADB_HOST first — dev-laptop has its own (often stale) checkout.
# Without this, the `adb install` below would install dev-laptop's local copy, not
# the APK we just rebuilt on the dev box.
scp -q "$REPO_ROOT/$APK_REL" "$ADB_HOST:$REPO_ROOT/$APK_REL" >/dev/null
ssh "$ADB_HOST" "adb install -r -t $REPO_ROOT/$APK_REL" 2>&1 | tail -3

# ── Step 2: mint invite ─────────────────────────────────────────────────────
log "2/8 mint invite on $ALPHA_HOST for '$COMPANION_NAME'"
INVITE_OUT="$(ssh "$ALPHA_HOST" "/home/you/src/wyrdsekai/bin/wyrd invite create $COMPANION_NAME 2>&1")"
INVITE_CODE="$(printf '%s\n' "$INVITE_OUT" | grep -oE '[a-z]+( [a-z]+){5}' | head -1)"
if [ -z "$INVITE_CODE" ]; then
  echo "[run-probe-redeem-kmp] ERROR: could not parse invite code:" >&2
  printf '%s\n' "$INVITE_OUT" >&2
  exit 1
fi
log "minted: '$INVITE_CODE'"

# ── Step 3: NATS-redeem via wss://relay-node:4443 ────────────────────────────────
log "3/8 NATS-redeem via wss://relay-node:4443"
ca_file="$(mktemp /tmp/relay-ca.XXXXXX.crt)"
curl -fsS "http://$RELAY_HOST_IP/ca.crt" >"$ca_file" 2>/dev/null \
  || { echo "[run-probe-redeem-kmp] ERROR: failed to fetch CA" >&2; exit 1; }
nats_redeem_out="$(NODE_EXTRA_CA_CERTS="$ca_file" node --experimental-websocket --no-warnings - <<EOF
import { connect } from '/home/you/src/wyrdsekai/clients/rn/node_modules/nats.ws/esm/nats.js';
const nc = await connect({ servers: 'wss://relay-node:4443', user: 'relay_phone',
  pass: 'M3bWgIOVG0WH8p1HHXD4XxPXtVgjtxezoIejyTrmM7A', name: 'harness-redeem-kmp' });
const enc = new TextEncoder();
const dec = new TextDecoder();
const sfx = Math.random().toString(36).slice(2, 10);
const username = 'phone-natstest-kmp-' + sfx;
let password = '';
for (let i = 0; i < 32; i++) password += Math.floor(Math.random() * 16).toString(16);
const reply = await nc.request(
  'wyrd.zone.alpha.auth.redeem',
  enc.encode(JSON.stringify({
    code: '$INVITE_CODE',
    username, password,
    displayName: "natstest-kmp's phone",
  })),
  { timeout: 8000 });
const parsed = JSON.parse(dec.decode(reply.data));
if (!parsed.ok) { console.error('REDEEM FAILED:', dec.decode(reply.data)); process.exit(1); }
process.stdout.write(JSON.stringify({
  username, password,
  token: parsed.token, userId: parsed.userId, role: parsed.role || 'member'
}));
await nc.drain();
EOF
)"
rm -f "$ca_file"
if [ -z "$nats_redeem_out" ]; then
  echo "[run-probe-redeem-kmp] ERROR: NATS redeem returned empty" >&2
  exit 1
fi
log "redeem OK: $(printf '%s' "$nats_redeem_out" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('user=' + d['username'] + ' token=' + d['token'][:8] + '... user_id=' + d['userId'][:8] + '...')
")"

# ── Step 4: seed wyrdsekai_prefs_seed.xml on dev-laptop ───────────────────────
log "4/8 seed wyrdsekai_prefs_seed.xml (no zone — phone discovers via NATS)"
local_xml="$(mktemp /tmp/wyrdsekai_prefs_seed.XXXXXX.xml)"
python3 - "$LLAMA_URL" "$RELAY_URL" "$nats_redeem_out" "$local_xml" <<'PY'
import json, sys
llama_url, relay_url, redeem_json, out_path = sys.argv[1:]
r = json.loads(redeem_json)
xml = f'''<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="wyrd_app_mode">local</string>
    <string name="wyrd_companion_name">Wyrd</string>
    <string name="wyrd_inference_url">{llama_url}</string>
    <string name="wyrd_server_url">{relay_url}</string>
    <string name="wyrd_auth_token">{r["token"]}</string>
    <string name="wyrd_user_id">{r["userId"]}</string>
    <string name="wyrd_user_role">{r["role"]}</string>
    <string name="wyrd_username">{r["username"]}</string>
    <string name="wyrd_mcp_username">{r["username"]}</string>
    <string name="wyrd_mcp_password">{r["password"]}</string>
    <string name="wyrd_mcp_session_token">{r["token"]}</string>
    <string name="wyrd_nats_zone">alpha</string>
    <string name="wyrd_locale">en</string>
    <boolean name="first_run_done" value="true" />
</map>
'''
with open(out_path, "w") as f:
    f.write(xml)
PY
scp -q "$local_xml" "$ADB_HOST:/tmp/wyrdsekai_prefs_seed.xml" >/dev/null
ssh "$ADB_HOST" "adb push /tmp/wyrdsekai_prefs_seed.xml /data/local/tmp/wyrdsekai_prefs_seed.xml" >/dev/null 2>&1
ssh "$ADB_HOST" "adb shell run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/shared_prefs && \
  adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrdsekai_prefs_seed.xml /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml && \
  adb shell run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml" >/dev/null 2>&1
rm -f "$local_xml"

# ── Step 5: seed HouseholdTrustStore prefs ──────────────────────────────────
log "5/8 seed HouseholdTrustStore (pin covers :443 + :4443; host-only key)"
RELAY_HOST="$RELAY_HOST_IP" ADB_HOST="$ADB_HOST" PHONE_PKG="$PHONE_PKG" \
  bash "$SCRIPT_DIR/seed_relay_trust.sh" 2>&1 | tail -10

# ── Step 6: launch + wait for NATS handshake ────────────────────────────────
log "6/8 launch app, wait 18s for NATS probe→login→token-cache"
ssh "$ADB_HOST" "adb logcat -c && adb shell am start -n $PHONE_PKG/org.wyrdsekai.app.android.MainActivity" >/dev/null 2>&1
sleep 18

# ── Step 7: tails + Maestro ────────────────────────────────────────────────
log "7/8 start α + β log tails + run Maestro flow"
alpha_log=/tmp/run-probe-redeem-kmp-alpha.log
beta_log=/tmp/run-probe-redeem-kmp-beta.log
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
for cand in \
  "$HOME/.wyrdsekai/.server.log" \
  "$HOME/src/wyrdsekai/logs/wyrdsekai.log" \
  "/var/log/wyrdsekai/wyrdsekai.log"; do
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

flow=/tmp/probe_kmp_redeem_flow.yaml
cat > /tmp/probe_kmp_redeem_flow.local.yaml <<EOF
appId: $PHONE_PKG
---
- extendedWaitUntil:
    visible:
      id: "standalone-input"
    timeout: 60000
- tapOn:
    id: "standalone-input"
- inputText: "tell $TELL_TARGET hello from kmp via relay-node"
- pressKey: "Enter"
- waitForAnimationToEnd:
    timeout: 5000
- tapOn:
    id: "standalone-input"
- inputText: "library search rivers"
- pressKey: "Enter"
- waitForAnimationToEnd:
    timeout: 5000
- tapOn:
    id: "standalone-input"
- inputText: "/journal kmp field note from relay test"
- pressKey: "Enter"
- waitForAnimationToEnd:
    timeout: 5000
EOF
scp -q /tmp/probe_kmp_redeem_flow.local.yaml "$ADB_HOST:$flow" >/dev/null
maestro_log=/tmp/run-probe-redeem-kmp-maestro.log
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro test $flow" > "$maestro_log" 2>&1 \
  || log "WARN: maestro returned non-zero (see $maestro_log)"
tail -30 "$maestro_log"
sleep 5

# ── Step 8: verify ──────────────────────────────────────────────────────────
log "8/8 verify α + β log hits"
kill $alpha_pid $beta_pid 2>/dev/null
wait 2>/dev/null

tell_hit=$(grep -cE 'Incoming cross-zone tell from phone-natstest-kmp-' "$beta_log" || true)
lib_hit=$(grep -ciE 'MCP-NATS library\.search|\[Library\][[:space:]]+search|library search|Lucene.*query' "$alpha_log" || true)
journal_hit=$(grep -ciE 'MCP-NATS study\.journal|journal entry|JournalService|\[Study\][[:space:]]+journal[[:space:]]+write' "$alpha_log" || true)

echo
echo "──────── KMP server-side proof tally ────────"
printf '  1. Cross-zone tell on β       : %s  (%s hits)\n' "$([ "$tell_hit" -gt 0 ] && echo PASS || echo FAIL)" "$tell_hit"
printf '  2. Library search on α        : %s  (%s hits)\n' "$([ "$lib_hit" -gt 0 ] && echo PASS || echo FAIL)" "$lib_hit"
printf '  3. Journal write on α         : %s  (%s hits)\n' "$([ "$journal_hit" -gt 0 ] && echo PASS || echo FAIL)" "$journal_hit"
echo
echo "Logs: $alpha_log / $beta_log / $maestro_log"

if [ "$tell_hit" -gt 0 ] && [ "$lib_hit" -gt 0 ] && [ "$journal_hit" -gt 0 ]; then
  exit 0
else
  exit 1
fi
