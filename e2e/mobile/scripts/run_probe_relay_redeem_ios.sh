#!/usr/bin/env bash
# iOS Simulator variant of run_probe_relay_redeem.sh — NATS-only transport
# Same arc as the Android redeem flow:
# mint invite on α, redeem via NATS host-side, seed app's Documents/
# wyrd-seed.json with the resulting username/password/token, install CA,
# launch app, run Maestro 3-command flow, verify server-side logs.
#
# Deltas vs Android version:
#   • Seed lands at <app data container>/Documents/wyrd-seed.json (not
#     /data/data/.../files/), written via xcrun simctl get_app_container.
#   • Trust pinning: relies on host-installed household CA via
#     `xcrun simctl keychain booted add-root-cert` (Phase 3 leg — the
#     RN HouseholdTrustModule isn't iOS-native yet).
#   • App lifecycle uses simctl terminate/uninstall/install/launch.
#   • /etc/hosts patched on mac-node so `relay-node` resolves to the IP it can
#     actually reach (multi-network gotcha — see probe_relay_phone_ios.sh).
#
# Env (with defaults):
#   IOS_HOST=mac-node  SIM_DEVICE=wyrd-e2e-ios
#   ALPHA_HOST=home-server   ALPHA_PORT=7070   BETA_HOST=test-node
#   RELAY_HOST_IP=192.0.2.108        RELAY_URL=https://$RELAY_HOST_IP
#   PHONE_PKG=org.wyrdsekai.rn
#   COMPANION_NAME=natstest             TELL_TARGET=beta.probe-beta
set -uo pipefail

IOS_HOST="${IOS_HOST:-mac-node}"
SIM_DEVICE="${SIM_DEVICE:-wyrd-e2e-ios}"
ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_HOST_IP="${RELAY_HOST_IP:-192.0.2.108}"
RELAY_URL="${RELAY_URL:-https://$RELAY_HOST_IP}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
COMPANION_NAME="${COMPANION_NAME:-natstest}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

log() { printf '\n[run-probe-redeem-ios] %s\n' "$*" >&2; }

# ── Step 1: ensure Simulator booted ──────────────────────────────────────────
log "1/9 ensure Simulator booted"
ssh "$IOS_HOST" "xcrun simctl boot '$SIM_DEVICE' 2>/dev/null; \
  xcrun simctl list devices booted | head -5" 2>&1 | tail -5

# ── Step 2: mint invite ─────────────────────────────────────────────────────
log "2/9 mint invite on $ALPHA_HOST for '$COMPANION_NAME'"
INVITE_OUT="$(ssh "$ALPHA_HOST" "/home/you/src/wyrdsekai/bin/wyrd invite create $COMPANION_NAME 2>&1")"
INVITE_CODE="$(printf '%s\n' "$INVITE_OUT" | grep -oE '[a-z]+( [a-z]+){5}' | head -1)"
if [ -z "$INVITE_CODE" ]; then
  echo "[run-probe-redeem-ios] ERROR: could not parse invite code from output:" >&2
  printf '%s\n' "$INVITE_OUT" >&2
  exit 1
fi
log "minted: '$INVITE_CODE'"

# ── Step 3: NATS-redeem via wss://relay-node:4443 ────────────────────────────────
log "3/9 NATS-redeem invite via wss://relay-node:4443 to mint username/password/token"
ca_file="$(mktemp /tmp/relay-ca.XXXXXX.crt)"
curl -fsS "http://$RELAY_HOST_IP/ca.crt" >"$ca_file" 2>/dev/null \
  || { echo "[run-probe-redeem-ios] ERROR: failed to fetch CA from $RELAY_HOST_IP" >&2; exit 1; }
nats_redeem_out="$(NODE_EXTRA_CA_CERTS="$ca_file" node --experimental-websocket --no-warnings - <<EOF
import { connect } from '/home/you/src/wyrdsekai/clients/rn/node_modules/nats.ws/esm/nats.js';
const nc = await connect({ servers: 'wss://relay-node:4443', user: 'relay_phone',
  pass: 'M3bWgIOVG0WH8p1HHXD4XxPXtVgjtxezoIejyTrmM7A', name: 'harness-redeem-ios' });
const enc = new TextEncoder();
const dec = new TextDecoder();
const sfx = Math.random().toString(36).slice(2, 10);
const username = 'phone-natstest-ios-' + sfx;
let password = '';
for (let i = 0; i < 32; i++) password += Math.floor(Math.random() * 16).toString(16);
const reply = await nc.request(
  'wyrd.zone.alpha.auth.redeem',
  enc.encode(JSON.stringify({
    code: '$INVITE_CODE',
    username, password,
    displayName: "natstest-ios's phone",
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
  echo "[run-probe-redeem-ios] ERROR: NATS redeem returned empty" >&2
  exit 1
fi
log "redeem OK: $(printf '%s' "$nats_redeem_out" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('user=' + d['username'] + ' token=' + d['token'][:8] + '... user_id=' + d['userId'][:8] + '...')
")"

# ── Step 4: uninstall + install fresh app on Simulator ─────────────────────
log "4/9 uninstall + install fresh app on $SIM_DEVICE"
APP="${WYRD_APP_PATH:-/Users/you/src/wyrdsekai/clients/rn/ios/build/derived/Build/Products/Release-iphonesimulator/Wyrdsekai.app}"
ssh "$IOS_HOST" "xcrun simctl terminate '$SIM_DEVICE' '$PHONE_PKG' 2>/dev/null; \
  xcrun simctl uninstall '$SIM_DEVICE' '$PHONE_PKG' 2>/dev/null; \
  xcrun simctl install '$SIM_DEVICE' '$APP'" 2>&1 | tail -3

# ── Step 5: write wyrd-seed.json into app Documents/ ───────────────────────
log "5/9 write wyrd-seed.json into Documents/ (no @wyrd_zone_id — phone discovers it)"
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
    # Pre-seed the zone so the phone skips `wyrd.discover.zone` (which has
    # multiple responders on a multi-node mesh — first-responder race). The
    # script redeemed against `wyrd.zone.alpha.auth.redeem`, so the token
    # is α-minted; phone MUST use α for subsequent calls or β rejects it.
    # Key is `@wyrd_zone_id` to match StandaloneNodeContext.credStorage.
    "@wyrd_zone_id": "alpha",
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
scp -q "$local_seed" "$IOS_HOST:/tmp/wyrd-seed.json"
ssh "$IOS_HOST" "CONT=\$(xcrun simctl get_app_container '$SIM_DEVICE' '$PHONE_PKG' data); \
  mkdir -p \"\$CONT/Documents\"; \
  cp /tmp/wyrd-seed.json \"\$CONT/Documents/wyrd-seed.json\"; \
  ls -la \"\$CONT/Documents/wyrd-seed.json\"" 2>&1 | tail -3
rm -f "$local_seed"

# ── Step 6: install household CA + patch /etc/hosts for relay-node hostname ─────
log "6/9 install household CA + patch /etc/hosts so relay-node resolves"
RELAY_HOST="${RELAY_URL#https://}"
RELAY_HOST="${RELAY_HOST%%/*}"
# If RELAY_URL is an IP literal already, skip hostname patching.
if [[ "$RELAY_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  log "  RELAY_URL is an IP literal — skipping /etc/hosts patch"
else
  RELAY_IP=$(ssh "$IOS_HOST" "ssh -o BatchMode=yes -o ConnectTimeout=5 '$RELAY_HOST' 'echo \$SSH_CONNECTION' 2>/dev/null" 2>/dev/null | awk '{print $3}')
  if [ -z "$RELAY_IP" ]; then
    RELAY_IP="$RELAY_HOST_IP"
    log "  fallback: using RELAY_HOST_IP=$RELAY_IP"
  fi
  ssh "$IOS_HOST" "if grep -q '[[:space:]]$RELAY_HOST\$' /etc/hosts 2>/dev/null; then \
      current=\$(grep '[[:space:]]$RELAY_HOST\$' /etc/hosts | awk '{print \$1}' | head -1); \
      if [ \"\$current\" != '$RELAY_IP' ]; then \
        sudo sed -i.bak 's/^.*[[:space:]]$RELAY_HOST\$/$RELAY_IP $RELAY_HOST/' /etc/hosts; \
        echo \"updated hosts: \$current → $RELAY_IP\"; \
      fi; \
    else \
      echo '$RELAY_IP $RELAY_HOST' | sudo tee -a /etc/hosts >/dev/null; \
      echo \"added hosts: $RELAY_IP $RELAY_HOST\"; \
    fi" 2>&1 | tail -2
fi
ssh "$IOS_HOST" "curl -fsS http://$RELAY_HOST_IP/ca.crt > /tmp/relay-node-ca.crt && \
  xcrun simctl keychain '$SIM_DEVICE' add-root-cert /tmp/relay-node-ca.crt" 2>&1 | tail -3

# ── Step 7: start α + β log tails ──────────────────────────────────────────
log "7/9 start α + β log tails"
alpha_log=/tmp/run-probe-redeem-ios-alpha.log
beta_log=/tmp/run-probe-redeem-ios-beta.log
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
  echo "[run-probe-tail-ios] no wyrdsekai log found on $(hostname)" >&2
  exit 1
fi
exec tail -F -n 0 "$src_log"
REMOTE
  echo $!
}

alpha_pid=$(start_tail "$ALPHA_HOST" "$alpha_log")
beta_pid=$(start_tail "$BETA_HOST" "$beta_log")
sleep 2

# ── Step 8: launch + run Maestro flow ──────────────────────────────────────
log "8/9 launch app + run Maestro flow"
ssh "$IOS_HOST" "xcrun simctl launch '$SIM_DEVICE' '$PHONE_PKG'" 2>&1 | tail -3
# Give NATS handshake time to complete (probe → login → token cache).
sleep 8
flow="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-tell-ios.yaml"
maestro_log=/tmp/run-probe-redeem-ios-maestro.log
scp -q "$flow" "$IOS_HOST:/tmp/probe_flow_ios.yaml"
ssh "$IOS_HOST" "WYRD_SERVER_URL='$RELAY_URL' /Users/you/.maestro/bin/maestro test /tmp/probe_flow_ios.yaml" \
  > "$maestro_log" 2>&1 \
  || log "WARN: maestro returned non-zero — check $maestro_log"
tail -30 "$maestro_log"

sleep 5

# ── Step 9: verify server-side ─────────────────────────────────────────────
log "9/9 verify α + β log hits"
kill $alpha_pid $beta_pid 2>/dev/null
wait 2>/dev/null

tell_hit=$(grep -cE 'Incoming cross-zone tell from phone-' "$beta_log" || true)
lib_hit=$(grep -ciE 'MCP-NATS library\.search|\[Library\][[:space:]]+search|library search|Lucene.*query' "$alpha_log" || true)
journal_hit=$(grep -ciE 'MCP-NATS study\.journal|journal entry|JournalService|\[Study\][[:space:]]+journal[[:space:]]+write' "$alpha_log" || true)

echo
echo "──────── iOS Simulator server-side proof tally ────────"
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
