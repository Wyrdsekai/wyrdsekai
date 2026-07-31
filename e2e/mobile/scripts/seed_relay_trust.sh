#!/usr/bin/env bash
# Pre-populate the phone's HouseholdTrustStore with the relay's household CA so
# the first HTTPS request to <relay>/api/* succeeds without a user-confirmation
# prompt. Bypasses the TOFU flow — only safe to do under e2e test conditions
# where we already trust the relay's identity out-of-band.
#
# Companion to seed_phone_session.sh / probe_relay_phone.sh. Run BEFORE app
# launch (the native HouseholdTrustStore.init reads SharedPreferences at
# Application.onCreate, not on every TLS check).
#
# Env:
#   RELAY_HOST       hostname / IP of the relay (e.g. 192.0.2.108)
#   PHONE_PKG        Android package (default org.wyrdsekai.rn)
#   ADB_HOST         where adb runs (default: localhost)
# CA_FILE optional path to the household CA PEM.
#                    single-port relays (P1) have NO :80 / /ca.crt route — the
#                    CA travels in the invite payload (§10.9) — so the caller
#                    must supply it. Unset = legacy :80 fetch (e.g. relay-node).
set -euo pipefail

RELAY_HOST="${RELAY_HOST:?RELAY_HOST required}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
ADB_HOST="${ADB_HOST:-localhost}"

ADB() {
  if [ "$ADB_HOST" = "localhost" ]; then adb "$@"
  else ssh "$ADB_HOST" adb "$@"
  fi
}

log() { printf '[seed-trust] %s\n' "$*" >&2; }

# 1. Obtain the household CA PEM — caller-supplied (single-port relays) or
#    the legacy plain-HTTP /ca.crt bootstrap route (pre-P1 relays only).
ca_tmp="$(mktemp /tmp/wyrd-ca.XXXXXX.crt)"
if [ -n "${CA_FILE:-}" ]; then
  log "Using caller-supplied CA: $CA_FILE"
  cp "$CA_FILE" "$ca_tmp"
else
  log "Fetching /ca.crt from http://$RELAY_HOST/ca.crt"
  curl -fsS "http://$RELAY_HOST/ca.crt" > "$ca_tmp"
fi
[ -s "$ca_tmp" ] || { log "CA cert empty"; exit 1; }
log "Got $(wc -l <"$ca_tmp")-line PEM"

# 2. Build the SharedPreferences XML. Android's PreferenceManager writes a
#    specific schema; build it directly so we don't depend on the app having
#    written it once. The key is the bare hostname (host:port if non-443) —
#    matches what JS-side hostKey() and Kotlin's SSLEngine.peerHost produce.
prefs_xml="$(mktemp /tmp/wyrd_household_trust.XXXXXX.xml)"
# XML-escape the PEM (& < > " ')
pem_escaped="$(python3 -c "import sys,html; print(html.escape(open(sys.argv[1]).read()), end='')" "$ca_tmp")"
cat > "$prefs_xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="$RELAY_HOST">$pem_escaped</string>
</map>
EOF

# 3. ADB push to /data/local/tmp (writable by adb shell), then run-as to copy
#    into the app's private shared_prefs dir. Force-stop the app first so any
#    cached in-memory state is dropped.
log "Pushing prefs XML to $PHONE_PKG..."
ADB shell am force-stop "$PHONE_PKG" 2>/dev/null || true

if [ "$ADB_HOST" = "localhost" ]; then
  adb push "$prefs_xml" /data/local/tmp/wyrd_household_trust.xml >/dev/null 2>&1
else
  scp "$prefs_xml" "$ADB_HOST:/tmp/wyrd_household_trust.xml" >/dev/null 2>&1
  ssh "$ADB_HOST" "adb push /tmp/wyrd_household_trust.xml /data/local/tmp/wyrd_household_trust.xml" >/dev/null 2>&1
fi
ADB shell "run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/shared_prefs"
ADB shell "run-as $PHONE_PKG cp /data/local/tmp/wyrd_household_trust.xml /data/data/$PHONE_PKG/shared_prefs/wyrd_household_trust.xml"
ADB shell "run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/shared_prefs/wyrd_household_trust.xml"

rm -f "$ca_tmp" "$prefs_xml"
log "Seeded household-CA pin for host '$RELAY_HOST'. On next app launch, OkHttp will trust HTTPS to https://$RELAY_HOST."
