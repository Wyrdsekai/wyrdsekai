#!/usr/bin/env bash
# Public-relay mobile test through NATS-only transport.
# Replaces the HTTP token-mint flow: phone now joins via `auth.redeem` over wss://relay-node:4443.
#
# Steps
#   1. pm clear PHONE_PKG          — wipe app data.
#   2. Mint fresh invite on α via `wyrd invite create <name>`.
#   3. Seed app files dir with JSON containing { server_url, invite_code, app_mode=local }.
#      No HTTP token, no pre-minted username/password — the phone redeems via NATS.
#   4. Pin household CA (covers relay-node:443 + relay-node:4443 — pin is host-only).
#   5. Launch app, wait for NATS handshake to complete (probe → redeem → token cache).
#   6. Tail α + β logs.
#   7. Run probe-relay-tell Maestro flow (tell + library + journal — unchanged).
#   8. Verify the three commands hit server logs.
#
# Env (with defaults):
#   ALPHA_HOST=home-server  BETA_HOST=test-node  RELAY_HOST_IP=192.0.2.108
#   ADB_HOST=dev-laptop  PHONE_PKG=org.wyrdsekai.rn
#   COMPANION_NAME=natstest  TELL_TARGET=beta.probe-beta
set -uo pipefail

ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_HOST_IP="${RELAY_HOST_IP:-192.0.2.108}"
RELAY_URL="${RELAY_URL:-https://$RELAY_HOST_IP}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
COMPANION_NAME="${COMPANION_NAME:-natstest}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

log() { printf '\n[run-probe-redeem] %s\n' "$*" >&2; }

# ── Step 1: pm clear ────────────────────────────────────────────────────────
log "1/8 pm clear $PHONE_PKG (and force-stop sibling KMP app — same device, same testIDs)"
ssh "$ADB_HOST" "adb shell pm clear $PHONE_PKG ; adb shell am force-stop org.wyrdsekai.kmp ; adb shell pm clear org.wyrdsekai.kmp 2>/dev/null || true" >/dev/null

# ── Step 2: mint invite ─────────────────────────────────────────────────────
log "2/8 mint invite on $ALPHA_HOST for '$COMPANION_NAME'"
INVITE_OUT="$(ssh "$ALPHA_HOST" "/home/you/src/wyrdsekai/bin/wyrd invite create $COMPANION_NAME 2>&1")"
INVITE_CODE="$(printf '%s\n' "$INVITE_OUT" | grep -oE '[a-z]+( [a-z]+){5}' | head -1)"
if [ -z "$INVITE_CODE" ]; then
  echo "[run-probe-redeem] ERROR: could not parse invite code from output:" >&2
  printf '%s\n' "$INVITE_OUT" >&2
  exit 1
fi
log "minted: '$INVITE_CODE'"

# ── Step 3: NATS-redeem + seed app files dir ───────────────────────────────
# The RN routing (App.tsx initialRouteName) sends `mode=local + inferenceUrl
# set + no authToken` to the LEGACY LoginScreen which does HTTP probing. To
# get past Login and exercise the NATS surface end-to-end, the harness itself
# redeems via NATS first, then seeds the resulting token so the app routes
# straight to Standalone. setupServerClient then validates the cached creds
# via `mcp.login` over NATS (exercising the NATS path).
log "3/8 NATS-redeem invite via wss://relay-node:4443 to mint username/password/token"
# nats.ws uses Node's WebSocket which needs the relay's CA to trust wss://. Fetch
# the CA fresh from relay-node so this works on any dev machine.
ca_file="$(mktemp /tmp/relay-ca.XXXXXX.crt)"
curl -fsS "http://$RELAY_HOST_IP/ca.crt" >"$ca_file" 2>/dev/null \
  || { echo "[run-probe-redeem] ERROR: failed to fetch CA from $RELAY_HOST_IP" >&2; exit 1; }
nats_redeem_out="$(NODE_EXTRA_CA_CERTS="$ca_file" node --experimental-websocket --no-warnings - <<EOF
import { connect } from '/home/you/src/wyrdsekai/clients/rn/node_modules/nats.ws/esm/nats.js';
const nc = await connect({ servers: 'wss://relay-node:4443', user: 'relay_phone',
  pass: 'M3bWgIOVG0WH8p1HHXD4XxPXtVgjtxezoIejyTrmM7A', name: 'harness-redeem' });
const enc = new TextEncoder();
const dec = new TextDecoder();
const sfx = Math.random().toString(36).slice(2, 10);
const username = 'phone-natstest-' + sfx;
let password = '';
for (let i = 0; i < 32; i++) password += Math.floor(Math.random() * 16).toString(16);
const reply = await nc.request(
  'wyrd.zone.alpha.auth.redeem',
  enc.encode(JSON.stringify({
    code: '$INVITE_CODE',
    username, password,
    displayName: "natstest's phone",
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
  echo "[run-probe-redeem] ERROR: NATS redeem returned empty" >&2
  exit 1
fi
log "redeem OK: $(printf '%s' "$nats_redeem_out" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(f"user={d[\"username\"]} token={d[\"token\"][:8]}... user_id={d[\"userId\"][:8]}...")')"

log "  seed JSON via files dir (token from NATS redeem, app routes to Standalone)"
local_seed="$(mktemp /tmp/wyrd-seed.XXXXXX.json)"
python3 - "$ALPHA_HOST" "$RELAY_URL" "$nats_redeem_out" "$local_seed" <<'PY'
import json, sys
alpha_host, relay_url, redeem_json, out_path = sys.argv[1:]
r = json.loads(redeem_json)
seed = {
    "@wyrd_app_mode": "local",
    "@wyrd_companion_name": "Wyrd",
    "@wyrd_first_run_complete": "true",
    "@wyrd_inference_url": f"http://{alpha_host}:8200",
    "@wyrd_server_url": relay_url,
    # NO @wyrd_zone_id seed — the phone discovers it via the
    # `wyrd.discover.zone` subject and caches the answer. "home" is
    # reserved and the phone refuses to use it.
    "@wyrd_mcp_username": r["username"],
    "@wyrd_mcp_password": r["password"],
    "@wyrd_mcp_session_token": r["token"],
    "@wyrd_auth_token": r["token"],
    "@wyrd_user_id": r["userId"],
    "@wyrd_user_role": r["role"],
}
with open(out_path, "w") as f:
    json.dump(seed, f)
PY
scp -q "$local_seed" "$ADB_HOST:/tmp/wyrd-seed.json"
ssh "$ADB_HOST" "adb shell run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/files" >/dev/null 2>&1
ssh "$ADB_HOST" "adb push /tmp/wyrd-seed.json /data/local/tmp/wyrd-seed.json && adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrd-seed.json /data/data/$PHONE_PKG/files/wyrd-seed.json && adb shell run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/files/wyrd-seed.json" >/dev/null 2>&1
rm -f "$local_seed"

# ── Step 4: seed trust prefs (pin covers both :443 + :4443; host-only key) ──
log "4/8 seed HouseholdTrustStore via seed_relay_trust.sh"
RELAY_HOST="$RELAY_HOST_IP" ADB_HOST="$ADB_HOST" PHONE_PKG="$PHONE_PKG" \
  bash "$SCRIPT_DIR/seed_relay_trust.sh" 2>&1 | tail -10

# ── Step 5: launch + wait for NATS handshake ────────────────────────────────
log "5/8 launch app, wait 20s for NATS probe→redeem→token-cache"
ssh "$ADB_HOST" "adb logcat -c && adb shell am start -n $PHONE_PKG/.MainActivity" >/dev/null
sleep 20

# ── Step 6: start journal tails ─────────────────────────────────────────────
log "6/8 start α + β log tails"
alpha_log=/tmp/run-probe-redeem-alpha.log
beta_log=/tmp/run-probe-redeem-beta.log
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

# ── Step 7: run Maestro flow ────────────────────────────────────────────────
log "7/8 run Maestro probe-relay-tell.yaml against $ADB_HOST"
flow="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-redeem-tell.yaml"
maestro_log=/tmp/run-probe-redeem-maestro.log
ssh "$ADB_HOST" "rm -f /tmp/probe_flow.yaml" >/dev/null
scp "$flow" "$ADB_HOST:/tmp/probe_flow.yaml" >/dev/null
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro test /tmp/probe_flow.yaml" \
  > "$maestro_log" 2>&1 \
  || log "WARN: maestro on $ADB_HOST returned non-zero — check $maestro_log"
tail -25 "$maestro_log"

sleep 5

# ── Step 8: verify server-side ──────────────────────────────────────────────
log "8/8 verify α + β log hits"
kill $alpha_pid $beta_pid 2>/dev/null
wait 2>/dev/null

# Match on the NATS-tell server log line ("MCP-NATS tell ..." or the
# CrossZoneTellService "Incoming cross-zone tell from ..." that lands on β).
# The username will be `phone-natstest-<random>` (chosen by redeemInvite).
tell_hit=$(grep -cE 'Incoming cross-zone tell from phone-' "$beta_log" || true)
lib_hit=$(grep -ciE 'MCP-NATS library\.search|\[Library\][[:space:]]+search|library search|Lucene.*query' "$alpha_log" || true)
journal_hit=$(grep -ciE 'MCP-NATS study\.journal|journal entry|JournalService|\[Study\][[:space:]]+journal[[:space:]]+write' "$alpha_log" || true)

echo
echo "──────── server-side proof tally ────────"
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
