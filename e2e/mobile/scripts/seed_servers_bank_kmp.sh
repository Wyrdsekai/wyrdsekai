#!/usr/bin/env bash
# Seed the KMP app's zone bank + force the "Your servers"
# surface on launch, so a Maestro flow can drive the wired ServersScreen.
#
# Writes wyrdsekai_prefs_seed.xml with:
#   - wyrd_app_mode = servers   (WyrdApp routes to ServersHost)
#   - wyrd_zone_bank = {relays, zones} blob (ZoneBankStore.load reads it)
#
# Env (all optional, sane defaults for a no-infra UI test):
#   ZONE_ID, ZONE_LABEL, RELAY_WS_URL, RELAY_USER, RELAY_PASS, ZONE_USERNAME
#   ZONE_PASSWORD  (if set, also seeds wyrd_zone_passwords so sign-in skips the
#                   prompt and goes straight to the connect attempt)
set -euo pipefail

PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.kmp}"
ZONE_ID="${ZONE_ID:-home-server}"
ZONE_LABEL="${ZONE_LABEL:-$ZONE_ID}"
RELAY_WS_URL="${RELAY_WS_URL:-wss://10.0.2.2:4443}"
RELAY_USER="${RELAY_USER:-phone}"
RELAY_PASS="${RELAY_PASS:-relaypass}"
RELAY_CAFP="${RELAY_CAFP:-}"
ZONE_USERNAME="${ZONE_USERNAME:-}"
ZONE_PASSWORD="${ZONE_PASSWORD:-}"

ADB=(adb)
[ -n "${EMU_SERIAL:-}" ] && ADB=(adb -s "$EMU_SERIAL")

log() { printf '[seed-servers] %s\n' "$*" >&2; }

# Build the bank blob: {"relays":"<relaysJson>","zones":"<zonesJson>"} where the
# inner values are JSON-string-encoded lists (matches ZoneBankStore.Blob).
BANK_BLOB="$(python3 - "$ZONE_ID" "$ZONE_LABEL" "$RELAY_WS_URL" "$RELAY_USER" "$RELAY_PASS" "$RELAY_CAFP" "$ZONE_USERNAME" <<'PY'
import json, sys
zone_id, zone_label, ws, user, pw, cafp, username = sys.argv[1:8]
relays = [{
    "wsUrl": ws, "caFp": (cafp or None), "natsUser": user,
    "natsPass": pw, "label": None, "addedAt": 1,
}]
zones = [{
    "zoneId": zone_id, "displayName": zone_label, "relayUrls": [ws],
    "username": username, "homeZone": False, "addedAt": 1, "lastUsedAt": None,
}]
print(json.dumps({"relays": json.dumps(relays), "zones": json.dumps(zones)}))
PY
)"

# XML-escape for safe embedding in the prefs <string> value.
xml_escape() { python3 -c 'import sys,html; print(html.escape(sys.stdin.read().rstrip("\n")))'; }
BANK_BLOB_XML="$(printf '%s' "$BANK_BLOB" | xml_escape)"

PW_LINE=""
if [ -n "$ZONE_PASSWORD" ]; then
    PW_BLOB="$(python3 -c 'import json,sys; print(json.dumps({sys.argv[1]: sys.argv[2]}))' "$ZONE_ID" "$ZONE_PASSWORD" | xml_escape)"
    PW_LINE="    <string name=\"wyrd_zone_passwords\">$PW_BLOB</string>"
fi

"${ADB[@]}" shell am force-stop "$PHONE_PKG" 2>/dev/null || true

prefs_xml="$(mktemp /tmp/wyrdsekai_prefs_seed.XXXXXX.xml)"
cat > "$prefs_xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="wyrd_app_mode">servers</string>
    <string name="wyrd_locale">en</string>
    <string name="wyrd_zone_bank">$BANK_BLOB_XML</string>
$PW_LINE
</map>
EOF

"${ADB[@]}" push "$prefs_xml" "/data/local/tmp/wyrdsekai_prefs_seed.xml" >/dev/null
"${ADB[@]}" shell "run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/shared_prefs"
"${ADB[@]}" shell "run-as $PHONE_PKG cp /data/local/tmp/wyrdsekai_prefs_seed.xml /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
"${ADB[@]}" shell "run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
rm -f "$prefs_xml"
log "Seeded zone bank for '$ZONE_ID' (mode=servers); launching."
"${ADB[@]}" shell am start -n "$PHONE_PKG/.android.MainActivity" >/dev/null 2>&1 || true
