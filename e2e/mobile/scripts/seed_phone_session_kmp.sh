#!/usr/bin/env bash
# Parallel of seed_phone_session.sh for the KMP client. KMP stores tokens
# in SharedPreferences (wyrdsekai_prefs_seed.xml) rather than RKStorage SQLite,
# so we generate the prefs XML and push it into place.
#
# Env: same as the RN seed script — LLAMA_URL, WYRD_SERVER_URL,
#      WYRD_USERNAME, WYRD_PASSWORD.
set -euo pipefail

PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.kmp}"
LLAMA_URL="${LLAMA_URL:?LLAMA_URL required}"
WYRD_SERVER_URL="${WYRD_SERVER_URL:?WYRD_SERVER_URL required}"
WYRD_USERNAME="${WYRD_USERNAME:?WYRD_USERNAME required}"
WYRD_PASSWORD="${WYRD_PASSWORD:?WYRD_PASSWORD required}"

log() { printf '[seed-kmp] %s\n' "$*" >&2; }

log "Logging in $WYRD_USERNAME @ $WYRD_SERVER_URL..."
LOGIN_RESPONSE="$(curl -fsS -X POST "${WYRD_SERVER_URL%/}/api/mcp/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\": \"$WYRD_USERNAME\", \"password\": \"$WYRD_PASSWORD\"}")"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"

AUTH_RESPONSE="$(curl -fsS -X POST "${WYRD_SERVER_URL%/}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\": \"$WYRD_USERNAME\", \"password\": \"$WYRD_PASSWORD\"}")"
USER_ID="$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')"
log "Got session: token=${TOKEN:0:8}... userId=$USER_ID"

adb shell am force-stop "$PHONE_PKG" 2>/dev/null || true

# KMP's TokenStore reads SharedPreferences at /data/data/<pkg>/shared_prefs/wyrdsekai_prefs_seed.xml.
# Android serializes prefs as a specific XML schema; build it directly so we
# don't depend on the app having written it once. The `mode=local` key is
# what WyrdApp.kt routes off of.
prefs_xml="$(mktemp /tmp/wyrdsekai_prefs_seed.XXXXXX.xml)"
cat > "$prefs_xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="wyrd_app_mode">local</string>
    <string name="wyrd_companion_name">Wyrd</string>
    <string name="wyrd_inference_url">$LLAMA_URL</string>
    <string name="wyrd_server_url">$WYRD_SERVER_URL</string>
    <string name="wyrd_auth_token">$TOKEN</string>
    <string name="wyrd_user_id">$USER_ID</string>
    <string name="wyrd_user_role">member</string>
    <string name="wyrd_username">$WYRD_USERNAME</string>
    <string name="wyrd_locale">en</string>
    <boolean name="first_run_done" value="true" />
</map>
EOF

adb push "$prefs_xml" "/data/local/tmp/wyrdsekai_prefs_seed.xml" >/dev/null 2>&1
adb shell "run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/shared_prefs"
adb shell "run-as $PHONE_PKG cp /data/local/tmp/wyrdsekai_prefs_seed.xml /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
adb shell "run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
rm -f "$prefs_xml"

# Also seed the System property paths used by NodeManager.android.kt so the
# ServerClient probe re-uses these creds (instead of trying to register a
# fresh anonymous account, which fails when openRegistration is closed).
# These are set in-process at app launch via -e KEY=VALUE adb shell args,
# but the simplest way to wire them is via WyrdApp.kt's hydrate step
# which reads from tokenStore. The XML we just wrote covers that path.

adb shell am start -n "$PHONE_PKG/.android.MainActivity" >/dev/null 2>&1
log "Seed complete; KMP app launching."
