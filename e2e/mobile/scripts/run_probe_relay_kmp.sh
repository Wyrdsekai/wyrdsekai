#!/usr/bin/env bash
# Public-relay e2e driver for the KMP Android client.
#
# Mirrors run_probe_relay.sh for the KMP build:
#   1. Reinstall APK (clean wipe of app data is implicit on fresh install).
#   2. Seed wyrdsekai_prefs.xml with relay URL + α-minted session token.
#   3. Seed HouseholdTrustStore prefs with the relay's household CA pin.
#   4. Launch the app, wait for the auto-connect/handshake.
#   5. Tail β's journal in background.
#   6. Drive the standalone tell input via Maestro with pressKey Enter.
#   7. Verify test-node saw `Incoming cross-zone tell from probe1@alpha`.
#
# Env (mostly same as the RN driver):
#   ALPHA_HOST       home-server
#   ALPHA_PORT       7070   (server REST + auth)
#   BETA_HOST        test-node
#   RELAY_HOST_IP    192.0.2.108
#   RELAY_URL        https://$RELAY_HOST_IP
#   LLAMA_URL        http://home-server:8200      (KMP wants this in prefs)
#   ADB_HOST         dev-laptop
#   PHONE_PKG        org.wyrdsekai.kmp
#   APK_PATH         clients/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk
#   TELL_TARGET      beta.probe-beta
#   WYRD_USERNAME    probe1
#   WYRD_PASSWORD    (required)
set -uo pipefail

ALPHA_HOST="${ALPHA_HOST:-home-server}"
ALPHA_PORT="${ALPHA_PORT:-7070}"
BETA_HOST="${BETA_HOST:-test-node}"
RELAY_HOST_IP="${RELAY_HOST_IP:-192.0.2.108}"
RELAY_URL="${RELAY_URL:-https://$RELAY_HOST_IP}"
LLAMA_URL="${LLAMA_URL:-http://home-server:8200}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.kmp}"
TELL_TARGET="${TELL_TARGET:-beta.probe-beta}"
: "${WYRD_USERNAME:?WYRD_USERNAME required}"
: "${WYRD_PASSWORD:?WYRD_PASSWORD required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APK_REL="clients/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

log() { printf '\n[run-probe-kmp] %s\n' "$*" >&2; }

# ── Step 1: stop + reinstall APK ────────────────────────────────────────────
log "1/7 reinstall $PHONE_PKG on $ADB_HOST (clean state)"
ssh "$ADB_HOST" "adb shell am force-stop $PHONE_PKG; adb uninstall $PHONE_PKG" >/dev/null 2>&1 || true
# Push the APK from dev-laptop's own checkout (we just synced it via build-kmp.sh).
ssh "$ADB_HOST" "adb install -r -t /home/you/src/wyrdsekai/$APK_REL" 2>&1 | tail -3

# ── Step 2: seed session prefs (login via α direct, seed prefs targeting relay) ──
# seed_phone_session_kmp.sh tries to login at the relay URL directly — that
# requires the test runner to trust the household CA, which dev-laptop doesn't.
# Mint the token via α's local :7070 (no TLS) instead, then write the prefs
# with WYRD_SERVER_URL=<relay> so the phone hits the relay.
log "2/7 mint session via α direct + seed wyrdsekai_prefs.xml"
TOKEN_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/mcp/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
TOKEN="$(printf '%s' "$TOKEN_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
AUTH_RESP="$(ssh "$ALPHA_HOST" "curl -fsS -X POST http://localhost:$ALPHA_PORT/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"username\":\"$WYRD_USERNAME\",\"password\":\"$WYRD_PASSWORD\"}'")"
USER_ID="$(printf '%s' "$AUTH_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')"
log "  got token=${TOKEN:0:8}… userId=$USER_ID"

ssh "$ADB_HOST" "adb shell am force-stop $PHONE_PKG" >/dev/null 2>&1 || true

# The real store is now EncryptedSharedPreferences (wyrdsekai_prefs_enc) —
# the on-disk XML is opaque ciphertext, so we can't write it directly. Instead
# we drop a plaintext SEED file (wyrdsekai_prefs_seed.xml) which the app's
# debug build imports into the encrypted store on launch, then deletes.
prefs_xml="$(mktemp /tmp/wyrdsekai_prefs_seed.XXXXXX.xml)"
cat > "$prefs_xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="wyrd_app_mode">local</string>
    <string name="wyrd_companion_name">Wyrd</string>
    <string name="wyrd_inference_url">$LLAMA_URL</string>
    <string name="wyrd_server_url">$RELAY_URL</string>
    <string name="wyrd_auth_token">$TOKEN</string>
    <string name="wyrd_user_id">$USER_ID</string>
    <string name="wyrd_user_role">member</string>
    <string name="wyrd_username">$WYRD_USERNAME</string>
    <string name="wyrd_mcp_username">$WYRD_USERNAME</string>
    <string name="wyrd_mcp_password">$WYRD_PASSWORD</string>
    <string name="wyrd_locale">en</string>
    <boolean name="first_run_done" value="true" />
</map>
EOF

if [ "$ADB_HOST" = "localhost" ]; then
  adb push "$prefs_xml" /data/local/tmp/wyrdsekai_prefs_seed.xml >/dev/null 2>&1
else
  scp "$prefs_xml" "$ADB_HOST:/tmp/wyrdsekai_prefs_seed.xml" >/dev/null 2>&1
  ssh "$ADB_HOST" "adb push /tmp/wyrdsekai_prefs_seed.xml /data/local/tmp/wyrdsekai_prefs_seed.xml" >/dev/null 2>&1
fi
ssh "$ADB_HOST" "adb shell run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/shared_prefs"
ssh "$ADB_HOST" "adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrdsekai_prefs_seed.xml /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
ssh "$ADB_HOST" "adb shell run-as $PHONE_PKG chmod 660 /data/data/$PHONE_PKG/shared_prefs/wyrdsekai_prefs_seed.xml"
rm -f "$prefs_xml"
log "  prefs seeded via debug seed-file; server_url=$RELAY_URL"

# ── Step 3: seed trust prefs ────────────────────────────────────────────────
log "3/7 seed HouseholdTrustStore via seed_relay_trust.sh"
RELAY_HOST="$RELAY_HOST_IP" ADB_HOST="$ADB_HOST" PHONE_PKG="$PHONE_PKG" \
  bash "$SCRIPT_DIR/seed_relay_trust.sh" 2>&1 | tail -10

# ── Step 4: launch fresh + wait for auto-connect ────────────────────────────
log "4/7 launch app, wait 12s for handshake"
ssh "$ADB_HOST" "adb logcat -c && adb shell am start -n $PHONE_PKG/org.wyrdsekai.app.android.MainActivity" >/dev/null 2>&1
sleep 12

# ── Step 5: start journal tail on β ─────────────────────────────────────────
log "5/7 tail β journalctl"
beta_log=/tmp/run-probe-kmp-beta.log
: > "$beta_log"
ssh "$BETA_HOST" 'sudo -n journalctl -u wyrdsekai --since "10 seconds ago" -f 2>&1' > "$beta_log" &
beta_pid=$!
sleep 2

# ── Step 6: drive Maestro flow ──────────────────────────────────────────────
log "6/7 Maestro flow on $ADB_HOST"
flow=/tmp/probe_kmp_flow.yaml
cat > /tmp/probe_kmp_flow.local.yaml <<EOF
appId: $PHONE_PKG
---
- extendedWaitUntil:
    visible:
      id: "standalone-input"
    timeout: 60000
- tapOn:
    id: "standalone-input"
- inputText: "tell $TELL_TARGET hello from KMP via relay-node"
- pressKey: "Enter"
- waitForAnimationToEnd:
    timeout: 5000
EOF
scp /tmp/probe_kmp_flow.local.yaml "$ADB_HOST:$flow" >/dev/null
maestro_log=/tmp/run-probe-kmp-maestro.log
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro test $flow" > "$maestro_log" 2>&1 \
  || log "WARN: maestro returned non-zero (see $maestro_log)"
tail -15 "$maestro_log"
sleep 5

# ── Step 7: verify ──────────────────────────────────────────────────────────
log "7/7 verify β saw the tell"
kill $beta_pid 2>/dev/null; wait 2>/dev/null
tell_hit=$(grep -c "Incoming cross-zone tell from $WYRD_USERNAME@alpha" "$beta_log" || true)

echo
echo "──────── KMP cross-zone tell ────────"
printf '  β test-node journal hits: %s\n' "$tell_hit"
echo
if [ "$tell_hit" -gt 0 ]; then
  grep -iE "Incoming cross-zone tell|probe-beta|hello from KMP" "$beta_log" | head -5
  echo
  echo "RESULT: PASS"
  exit 0
else
  echo "RESULT: FAIL — no tell on β"
  echo "Tail of β log:"; tail -10 "$beta_log"
  exit 1
fi
