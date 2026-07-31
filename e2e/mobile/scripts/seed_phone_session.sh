#!/usr/bin/env bash
# Seed the phone (RN AsyncStorage) with a pre-authenticated wyrdsekai-server
# session so Tier 3 server-backed flows can skip the Welcome wizard.
#
# Reads required configuration from environment:
#   PHONE_PKG          — Android package name (default: org.wyrdsekai.rn)
#   LLAMA_URL          — llama-server URL for companion LLM (e.g. http://home-server:8200)
#   WYRD_SERVER_URL    — wyrdsekai server URL for REST API (e.g. http://home-server:7070)
#   WYRD_USERNAME      — pre-created MCP account username
#   WYRD_PASSWORD      — pre-created MCP account password
#
# Logs in via /api/mcp/login on the wyrdsekai server, then writes the
# session token + URLs + saved-creds into the RN app's RKStorage SQLite
# file using pull → modify → push (sqlite3 isn't in the Android run-as
# PATH on standard emulator images).
#
# Idempotent — safe to re-run between flows. After this, launching the app
# routes mode=local + isLoggedIn=true → Birth → Standalone with a live
# ServerClient session.
set -euo pipefail

PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
LLAMA_URL="${LLAMA_URL:?LLAMA_URL required}"
WYRD_SERVER_URL="${WYRD_SERVER_URL:?WYRD_SERVER_URL required}"
WYRD_USERNAME="${WYRD_USERNAME:?WYRD_USERNAME required}"
WYRD_PASSWORD="${WYRD_PASSWORD:?WYRD_PASSWORD required}"

log() { printf '[seed] %s\n' "$*" >&2; }

# Hit /api/mcp/login on the wyrdsekai server to obtain a fresh session token.
# The token is what the server's resolveSession() looks up for MCP routes;
# without it, /api/mcp/tell and /api/mcp/do return 401.
log "Logging in $WYRD_USERNAME @ $WYRD_SERVER_URL..."
LOGIN_RESPONSE="$(curl -fsS -X POST "${WYRD_SERVER_URL%/}/api/mcp/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\": \"$WYRD_USERNAME\", \"password\": \"$WYRD_PASSWORD\"}")"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
USER_ID="$(printf '%s' "$LOGIN_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("userId", ""))' 2>/dev/null || true)"
# /api/mcp/login response omits userId in current server; the room ack
# includes it via the entities list but we don't need it here. The app
# only checks isLoggedIn = !!authToken && !!userId, so any non-empty
# userId from auth/login is sufficient — fall back to the username if MCP
# didn't echo it.
if [ -z "$USER_ID" ]; then
    log "MCP login didn't echo userId — calling /api/auth/login for one"
    AUTH_RESPONSE="$(curl -fsS -X POST "${WYRD_SERVER_URL%/}/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\": \"$WYRD_USERNAME\", \"password\": \"$WYRD_PASSWORD\"}")"
    USER_ID="$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')"
fi
log "Got session: token=${TOKEN:0:8}... userId=$USER_ID"

# Stop the app so its in-memory state doesn't race with the file we're rewriting.
adb shell am force-stop "$PHONE_PKG" 2>/dev/null || true

# Pull the RKStorage SQLite, write keys, push back. We can't sqlite3 in-place
# under run-as because the standard Android emulator image lacks sqlite3 in
# /system/bin. Local sqlite3 (or apt's) handles the rewrite fine.
local_db="$(mktemp /tmp/RKStorage.XXXXXX)"
adb shell "run-as $PHONE_PKG cat /data/data/$PHONE_PKG/databases/RKStorage" > "$local_db" 2>/dev/null || {
    # First-run case: file doesn't exist yet. RN creates RKStorage on first
    # AsyncStorage.setItem, but before then we need an empty stub the app
    # can append to. Easiest path: launch the app briefly so RN creates
    # the file, then stop and re-pull.
    log "RKStorage missing — launching app briefly so RN creates it"
    adb shell am start -n "$PHONE_PKG/.MainActivity" >/dev/null 2>&1
    sleep 8
    adb shell am force-stop "$PHONE_PKG" 2>/dev/null || true
    adb shell "run-as $PHONE_PKG cat /data/data/$PHONE_PKG/databases/RKStorage" > "$local_db"
}

sqlite3 "$local_db" <<SQL
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_app_mode','local');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_companion_name','Wyrd');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_first_run_complete','true');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_inference_url','$LLAMA_URL');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_server_url','$WYRD_SERVER_URL');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_mcp_username','$WYRD_USERNAME');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_mcp_password','$WYRD_PASSWORD');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_mcp_session_token','$TOKEN');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_auth_token','$TOKEN');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_user_id','$USER_ID');
INSERT OR REPLACE INTO catalystLocalStorage(key,value) VALUES ('@wyrd_user_role','member');
SQL

adb push "$local_db" /data/local/tmp/RKStorage >/dev/null 2>&1
adb shell "run-as $PHONE_PKG cp /data/local/tmp/RKStorage /data/data/$PHONE_PKG/databases/RKStorage"
rm -f "$local_db"

# Launch the app so Maestro can pick up the room state. Don't await here —
# seed-and-connect.yaml's extendedWaitUntil(standalone-send-button) does
# the timing.
adb shell am start -n "$PHONE_PKG/.MainActivity" >/dev/null 2>&1
log "Seed complete; app launching."
