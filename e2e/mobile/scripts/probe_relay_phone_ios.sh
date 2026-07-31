#!/usr/bin/env bash
# iOS Simulator one-shot seeder. Mirrors probe_relay_phone.sh (Android).
# Writes wyrd-seed.json into the app's Documents/ before launch so
# initSecureStorage imports MCP creds + server URL into MMKV and skips the
# Welcome screen.
#
# Env:
#   IOS_HOST         mac-node
#   SIM_DEVICE       wyrd-e2e-ios
#   ALPHA_HOST       home-server
#   ALPHA_PORT       7070
#   RELAY_URL        https://relay-node
#   WYRD_USERNAME    must already exist on α
#   WYRD_PASSWORD    .
#   PHONE_PKG        org.wyrdsekai.rn
set -uo pipefail

IOS_HOST="${IOS_HOST:-mac-node}"
SIM_DEVICE="${SIM_DEVICE:-wyrd-e2e-ios}"
ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
RELAY_URL="${RELAY_URL:-https://relay-node}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
: "${WYRD_USERNAME:?WYRD_USERNAME required}"
: "${WYRD_PASSWORD:?WYRD_PASSWORD required}"

log() { printf '[probe-relay-ios] %s\n' "$*" >&2; }

log "1/5 mint MCP session token + auth userId via α"
TOKEN_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/mcp/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
TOKEN="$(printf '%s' "$TOKEN_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
AUTH_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
USER_ID="$(printf '%s' "$AUTH_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')"
log "token=${TOKEN:0:8}... userId=$USER_ID"

log "2/5 uninstall + install fresh app on $SIM_DEVICE"
APP="${WYRD_APP_PATH:-/Users/you/src/wyrdsekai/clients/rn/ios/build/derived/Build/Products/Release-iphonesimulator/Wyrdsekai.app}"
ssh "$IOS_HOST" "xcrun simctl terminate '$SIM_DEVICE' '$PHONE_PKG' 2>/dev/null; \
  xcrun simctl uninstall '$SIM_DEVICE' '$PHONE_PKG' 2>/dev/null; \
  xcrun simctl install '$SIM_DEVICE' '$APP'" 2>&1 | tail -3

log "3/5 write wyrd-seed.json into app Documents/"
# JSON-seed pattern — secureStorage.initSecureStorage reads
# `<RNFS.DocumentDirectoryPath>/wyrd-seed.json` on first launch, imports each
# key into MMKV, then deletes the file. Same shape as the Android seeder.
local_seed="$(mktemp /tmp/wyrd-seed.XXXXXX.json)"
python3 - "$ALPHA_HOST" "$RELAY_URL" "$WYRD_USERNAME" "$WYRD_PASSWORD" "$TOKEN" "$USER_ID" "$local_seed" <<'PY'
import json, sys
alpha_host, relay_url, username, password, token, user_id, out_path = sys.argv[1:]
seed = {
    "@wyrd_app_mode": "local",
    "@wyrd_companion_name": "Wyrd",
    "@wyrd_first_run_complete": "true",
    "@wyrd_inference_url": f"http://{alpha_host}:8200",
    "@wyrd_server_url": relay_url,
    "@wyrd_mcp_username": username,
    "@wyrd_mcp_password": password,
    "@wyrd_mcp_session_token": token,
    "@wyrd_auth_token": token,
    "@wyrd_user_id": user_id,
    "@wyrd_user_role": "member",
}
with open(out_path, "w") as f: json.dump(seed, f)
PY

scp -q "$local_seed" "$IOS_HOST:/tmp/wyrd-seed.json"
# Get app data container and write the seed into Documents/. The Simulator
# is sandboxed but the host has direct fs access to the container path.
ssh "$IOS_HOST" "CONT=\$(xcrun simctl get_app_container '$SIM_DEVICE' '$PHONE_PKG' data); \
  mkdir -p \"\$CONT/Documents\"; \
  cp /tmp/wyrd-seed.json \"\$CONT/Documents/wyrd-seed.json\"; \
  ls -la \"\$CONT/Documents/wyrd-seed.json\"" 2>&1 | tail -3
rm -f "$local_seed"

log "4/5 install household CA + ensure DNS resolves $RELAY_URL"
# iOS Simulator inherits the host /etc/hosts. Bare hostnames like 'relay-node'
# don't resolve via mDNS on macOS without explicit mapping, and the
# household CA is issued to CN=relay-node (not the IP) so we must reach it by
# name.
#
# Dual-network gotcha: relay-node has multiple interfaces (LAN 192.168.1.x,
# household VLAN 192.168.10.x, docker bridges). mac-node might only be
# reachable on one of them. We can't just pick relay-node's first IP — that
# might be on a subnet mac-node can't route to. Instead, ask mac-node what
# IP it actually uses to reach relay-node by piggy-backing on the existing SSH
# session: ssh from mac-node → relay-node and ask relay-node `who am i` which prints
# the source IP — that's the IP mac-node *can* reach relay-node at. If mac-node
# can't SSH relay-node at all, the rest of the probe is doomed anyway, so
# error out clearly.
RELAY_HOST="${RELAY_URL#https://}"
RELAY_HOST="${RELAY_HOST%%/*}"
# `who am i` prints e.g. "operator pts/0 2026-05-11 19:30 (198.51.100.39)".
# We want what's in parens — that's mac-node's POV of how it reached relay-node.
# Note: extract the source IP that relay-node sees, NOT relay-node's own IP — those
# can differ when NAT/routing is involved.
RELAY_IP=$(ssh "$IOS_HOST" "ssh -o BatchMode=yes -o ConnectTimeout=5 '$RELAY_HOST' 'echo \$SSH_CONNECTION' 2>/dev/null" 2>/dev/null | awk '{print $3}')
if [ -z "$RELAY_IP" ]; then
  log "WARN: could not determine relay-node's IP from mac-node's POV (multi-network?). Falling back to relay-node's first IP."
  RELAY_IP=$(ssh "$RELAY_HOST" "hostname -I 2>/dev/null | awk '{print \$1}'" 2>/dev/null || true)
fi
if [ -n "$RELAY_IP" ]; then
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
ssh "$IOS_HOST" "curl -s http://${RELAY_URL#https://}/ca.crt > /tmp/relay-node-ca.crt && \
  xcrun simctl keychain '$SIM_DEVICE' add-root-cert /tmp/relay-node-ca.crt" 2>&1 | tail -3

log "5/5 launch app"
ssh "$IOS_HOST" "xcrun simctl launch '$SIM_DEVICE' '$PHONE_PKG'" 2>&1 | tail -3
