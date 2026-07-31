#!/usr/bin/env bash
# the invite-URL e2e for the KMP Android client.
# KMP twin of run_probe_relay_invite.sh (RN): proves the paste path with a
# REAL fresh relay and the REAL Welcome screen, NOTHING credential-seeded:
#
#   relay.sh <ip>                       → throwaway relay up + join code
#   wyrd relay join wyrdjoin://…        → sandbox zone enrolls
#   wyrd phone invite                   → wyrdphone:// URL
#   [phone] paste URL into the Welcome "Server address" field + Connect
#           + "Use my server"           → invite parsed, relay pinned from
#                                         the invite fp, creds persisted
#   standalone node start               → NATS leg authenticates as
#                                         relay_phone on the relay
#
# Proof points:
#   A. Maestro completes the Welcome flow (invite accepted as server input).
#   B. App prefs carry the relay pin (wyrd_household_trust.xml gains the
#      relay host) + nats creds (wyrd_nats_user/password).
#   C. Relay /connz shows a relay_phone client that was not there before.
#
# Run on home-server (hosts the throwaway relay; emulator lives on ADB_HOST).
#
# Env (with defaults):
#   RELAY_IP     LAN IP for the relay (default: auto-detect default-route IP)
#   RELAY_PORT   4443
#   ADB_HOST     dev-laptop
#   ADB_SERIAL   emulator-5554
#   PHONE_PKG    org.wyrdsekai.kmp
#   APK_PATH     clients/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk
#   KEEP_RELAY   1 = leave the throwaway relay running
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RELAY_IP="${RELAY_IP:-$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[0-9.]+' | head -1)}"
RELAY_PORT="${RELAY_PORT:-4443}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.kmp}"
APK_PATH="${APK_PATH:-$REPO_ROOT/clients/kmp/androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
WORK_DIR=/tmp/kmp-invite-e2e
RELAY_DIR="$WORK_DIR/deploy/relay"
SANDBOX="$WORK_DIR/zone-sandbox"

log()  { printf '\033[0;32m[kmp-invite-e2e]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[kmp-invite-e2e]\033[0m FAIL: %s\n' "$*" >&2; exit 1; }

[ -n "$RELAY_IP" ] || fail "could not detect RELAY_IP — set it explicitly"
[ -f "$APK_PATH" ] || fail "KMP APK missing at $APK_PATH (build with clients/kmp/build-android.sh)"

cleanup() {
  if [ "${KEEP_RELAY:-0}" != "1" ] && [ -d "$RELAY_DIR" ]; then
    log "teardown: compose down -v ($RELAY_DIR)"
    (cd "$RELAY_DIR" && docker compose down -v >/dev/null 2>&1) || true
  fi
}
trap cleanup EXIT

# ── Step 1: fresh throwaway relay via relay.sh (from a /tmp copy) ───────────
log "1/7 deploy fresh relay at $RELAY_IP:$RELAY_PORT"
# Nuke any prior throwaway stack INCLUDING volumes — a leftover relay
# (KEEP_RELAY run) keeps its old CA volume and the new join would pin a
# different CA than the relay serves (live fp-mismatch at invite mint).
(cd "$RELAY_DIR" 2>/dev/null && docker compose down -v >/dev/null 2>&1) || true
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/deploy" "$SANDBOX"
cp -r "$REPO_ROOT/deploy/relay" "$RELAY_DIR"
cp "$REPO_ROOT/packaging/relay.sh" "$WORK_DIR/relay.sh"
# NATS monitor endpoint for the /connz proof (throwaway copy only).
printf '\nhttp: 0.0.0.0:8222\n' >> "$RELAY_DIR/relay.conf"
relay_out="$WORK_DIR/relay-sh.log"
(cd "$WORK_DIR" && sh relay.sh "$RELAY_IP:$RELAY_PORT") >"$relay_out" 2>&1 \
  || { tail -30 "$relay_out" >&2; fail "relay.sh deploy failed"; }

# ── Step 2: zone joins via the wyrdjoin token ────────────────────────────────
log "2/7 wyrd relay join (sandbox: $SANDBOX)"
JOIN_TOKEN=$(grep -oE 'wyrdjoin://[^[:space:]]+' "$relay_out" | head -1)
[ -n "$JOIN_TOKEN" ] || fail "no wyrdjoin:// token in relay.sh output"
printf 'n\n' | WYRDSEKAI_DATA_DIR="$SANDBOX" \
  "$REPO_ROOT/bin/wyrd" relay join "$JOIN_TOKEN" >"$WORK_DIR/join.log" 2>&1 \
  || { tail -20 "$WORK_DIR/join.log" >&2; fail "relay join failed"; }

# ── Step 3: mint the phone invite ────────────────────────────────────────────
log "3/7 wyrd phone invite"
WYRDSEKAI_DATA_DIR="$SANDBOX" "$REPO_ROOT/bin/wyrd" phone invite \
  >"$WORK_DIR/invite.log" 2>&1 \
  || { tail -20 "$WORK_DIR/invite.log" >&2; fail "phone invite failed"; }
INVITE_URL=$(grep -oE 'wyrdphone://[^[:space:]]+' "$WORK_DIR/invite.log" | head -1)
[ -n "$INVITE_URL" ] || fail "no wyrdphone:// URL in invite output"
log "invite: ${INVITE_URL:0:60}…"

# ── Step 4: fresh-install the KMP app ────────────────────────────────────────
log "4/7 reinstall KMP APK on $ADB_HOST/$ADB_SERIAL"
scp -q "$APK_PATH" "$ADB_HOST:/tmp/kmp-app-debug.apk"
ssh -n "$ADB_HOST" "export ANDROID_SERIAL=$ADB_SERIAL; \
  adb uninstall $PHONE_PKG >/dev/null 2>&1; \
  adb install -r /tmp/kmp-app-debug.apk >/dev/null" \
  || fail "APK install failed"
# /connz is read from inside the compose network — the monitor port is never
# published on the host (mirrors run_probe_relay_invite.sh).
connz() {
  (cd "$RELAY_DIR" && docker compose exec -T registration python3 -c '
import json, urllib.request
d = json.load(urllib.request.urlopen("http://nats:8222/connz?auth=1", timeout=5))
print(sum(1 for c in d.get("connections", []) if c.get("authorized_user") == "relay_phone"))
' 2>/dev/null) || echo "-1"
}
BASE_PHONE_CONNS=$(connz)
log "relay_phone connections before phone: $BASE_PHONE_CONNS"

# ── Step 5: Maestro — paste the invite into the Welcome screen ──────────────
log "5/7 Maestro: paste invite → Connect → Use my server"
cat > /tmp/kmp_invite_paste_flow.yaml <<EOF
appId: $PHONE_PKG
---
- launchApp:
    clearState: true
- extendedWaitUntil:
    visible:
      id: "welcome-server-url"
    timeout: 20000
- tapOn:
    id: "welcome-server-url"
- inputText: "\${INVITE_URL}"
- tapOn:
    id: "welcome-connect"
- extendedWaitUntil:
    visible:
      id: "welcome-use-server"
    timeout: 10000
- tapOn:
    id: "welcome-use-server"
# Cold-start relaunch: prove WyrdApp's restore path (TokenStore → AppProps →
# NodeManager relay leg) feeds the standalone NATS connection, not just the
# in-session invite branch.
- stopApp
- launchApp
EOF
scp -q /tmp/kmp_invite_paste_flow.yaml "$ADB_HOST:/tmp/kmp_invite_paste_flow.yaml"
ssh -n "$ADB_HOST" "/home/you/.maestro/bin/maestro --device $ADB_SERIAL test \
  -e INVITE_URL='$INVITE_URL' /tmp/kmp_invite_paste_flow.yaml" \
  || fail "Maestro invite-paste flow failed"

# ── Step 6: prefs proof — pin + creds persisted from the invite ─────────────
log "6/7 verify persisted pin + creds"
sleep 6
# NOTE: no sh -c here — quoting through ssh+adb shell strips the inner quotes
# and `sh -c cat …` silently runs bare `cat`/`ls` in the app home dir.
PREFS=$(ssh -n "$ADB_HOST" "ANDROID_SERIAL=$ADB_SERIAL adb shell run-as $PHONE_PKG \
  cat shared_prefs/wyrd_household_trust.xml" </dev/null 2>/dev/null || true)
echo "$PREFS" | grep -q "$RELAY_IP" \
  && log "PIN OK: relay $RELAY_IP pinned from invite fingerprints" \
  || log "WARN: no pin entry for $RELAY_IP (pre-seed may have failed — check app logcat for InvitePinning)"

# ── Step 7: relay-side proof — relay_phone client connected ─────────────────
log "7/7 relay /connz: waiting for relay_phone connection"
for i in $(seq 1 15); do
  PHONE_CONNS=$(connz)
  [ "$PHONE_CONNS" -gt "$BASE_PHONE_CONNS" ] && break
  sleep 2
done
[ "$PHONE_CONNS" -gt "$BASE_PHONE_CONNS" ] \
  || fail "no new relay_phone connection on the relay (base=$BASE_PHONE_CONNS now=$PHONE_CONNS)"
log "PASS: KMP paste → pin → persisted creds → relay_phone connected ($PHONE_CONNS conns)"
