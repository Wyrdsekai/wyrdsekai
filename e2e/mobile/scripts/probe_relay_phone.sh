#!/usr/bin/env bash
# One-shot smoke test: seed the RN phone with an HTTPS relay URL pointing
# at relay-node's Caddy proxy, launch the app, and tail logcat for TLS errors.
#
# Validates that the bundled household CA in res/raw/household_ca.crt +
# network_security_config.xml lets the app fetch /api/* over HTTPS through
# the relay. If the cert isn't trusted, RN's fetch() will log a SSLHandshake
# or "trust anchor" error which this script greps for.
#
# Run from anywhere; reads env:
#   ALPHA_HOST       (default: home-server)
#   ALPHA_PORT       (default: 7070)
#   RELAY_URL        (default: https://192.0.2.108)
#   WYRD_USERNAME    (required — must already exist on α)
#   WYRD_PASSWORD    (required)
#   PHONE_PKG        (default: org.wyrdsekai.rn)
set -uo pipefail

ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
RELAY_URL="${RELAY_URL:-https://192.0.2.108}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
: "${WYRD_USERNAME:?WYRD_USERNAME required}"
: "${WYRD_PASSWORD:?WYRD_PASSWORD required}"

# Use the same ADB host that runs the emulator (dev-laptop typically).
ADB_HOST="${ADB_HOST:-dev-laptop}"
ADB="ssh $ADB_HOST adb"

log() { printf '[probe-relay] %s\n' "$*" >&2; }

log "Step 1: mint MCP session token via α (direct, not through relay)"
TOKEN_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/mcp/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
TOKEN="$(printf '%s' "$TOKEN_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
AUTH_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
USER_ID="$(printf '%s' "$AUTH_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')"
log "Got token=${TOKEN:0:8}... userId=$USER_ID"

log "Step 2: stop app, write JSON seed file at <files>/wyrd-seed.json, push back"
$ADB shell am force-stop "$PHONE_PKG" 2>/dev/null || true

# JSON-seed pattern — bypasses AsyncStorage entirely. secureStorage's
# initSecureStorage reads this file on launch and imports each key
# straight into MMKV, then deletes the file. We tried direct sqlite3
# writes to RKStorage but the AsyncStorage RKStorage backend silently
# dropped 6 of 11 keys after app launch (verified 2026-05-11 isolation
# test: sqlite3 SELECT showed all 11 rows post-INSERT, but only the 5
# keys the app actively writes back survived the boot flush). JSON →
# files dir → MMKV is reliable.
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
with open(out_path, "w") as f:
    json.dump(seed, f)
PY

scp -q "$local_seed" "$ADB_HOST:/tmp/wyrd-seed.json"
$ADB shell "run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/files" >/dev/null 2>&1
ssh "$ADB_HOST" "adb push /tmp/wyrd-seed.json /data/local/tmp/wyrd-seed.json && adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrd-seed.json /data/data/$PHONE_PKG/files/wyrd-seed.json && adb shell run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/files/wyrd-seed.json" >/dev/null 2>&1
rm -f "$local_seed"
log "Seeded app via wyrd-seed.json with WYRD_SERVER_URL=$RELAY_URL"

log "Step 3: clear logcat, launch app, capture 25s of output"
$ADB logcat -c
$ADB shell am start -n "$PHONE_PKG/.MainActivity" >/dev/null 2>&1
sleep 25

logfile=/tmp/probe-relay-logcat.txt
$ADB logcat -d > "$logfile" 2>&1
log "Captured $(wc -l <"$logfile") logcat lines → $logfile"

log "Step 4: scan for TLS / network failures"
echo "--- TLS-related lines ---"
grep -iE "ssl|tls|cert|trust anchor|x509|handshake|sslhandshake|certpath" "$logfile" | head -30
echo "--- ServerClient / fetch errors ---"
grep -iE "ServerClient|fetch.*fail|network request fail|TypeError.*Network|sslHandshake" "$logfile" | head -20
echo "--- 200 OK / probe success indicators ---"
grep -iE "probe|/api/auth/status|/api/mcp/(login|tell|do)|hasUsers" "$logfile" | head -20
