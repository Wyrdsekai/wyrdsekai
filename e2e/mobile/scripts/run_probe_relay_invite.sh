#!/usr/bin/env bash
# the invite-URL e2e. Proves the whole 3-command UX
# chain with a REAL fresh relay and the REAL phone UI:
#
#   relay.sh <ip>                      → throwaway relay up + join code
#   wyrd relay join <ip>:4443 <code>   → sandbox zone enrolls (NKey minted)
#   wyrd phone invite                  → wyrdphone:// URL
#   [phone] paste URL into Connect screen + Log in
#                                      → app parses + persists relay creds
#   [phone] relaunch in local mode     → standalone NATS path consumes the
#                                        creds → CONNECT lands on the relay
#
# Unlike run_probe_relay.sh / run_probe_relay_redeem.sh, NOTHING credential-
# shaped is seeded by the harness: the relay URL, NATS user, password, AND
# the TLS trust pin all come from the pasted invite, exactly as a real
# user's QR scan would deliver them (the paste intercept pins the household
# CA by matching the invite's ca_fp against the served leaf+CA chain). The
# harness seeds only routing keys (first-run-complete + app mode).
#
# Proof points:
#   A. Maestro asserts the "Relay invite accepted" message after the paste.
#   B. App logcat shows setupServerClient consuming the invite relay URL +
#      relay_phone user ("[setup] wss=wss://<ip>:4443 ... user=relay_phone").
#   C. Relay-side /connz shows a NATS client authenticated as relay_phone
#      that was NOT there before the phone connected.
#
# Run on home-server (hosts the throwaway relay; emulator lives on ADB_HOST).
#
# Env (with defaults):
#   RELAY_IP     LAN IP for the relay (default: auto-detect default-route IP)
#   RELAY_PORT   4443
#   ADB_HOST     dev-laptop
#   PHONE_PKG    org.wyrdsekai.rn
#   WORK_DIR     /tmp/wyrd-invite-e2e
#   KEEP_RELAY   1 = leave the throwaway relay running after the test
set -uo pipefail

RELAY_IP="${RELAY_IP:-$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}' | head -1)}"
RELAY_PORT="${RELAY_PORT:-4443}"
ADB_HOST="${ADB_HOST:-dev-laptop}"
PHONE_PKG="${PHONE_PKG:-org.wyrdsekai.rn}"
# The e2e targets the emulator; a physical phone may share the adb host.
ADB_SERIAL="${ADB_SERIAL:-emulator-5554}"
WORK_DIR="${WORK_DIR:-/tmp/wyrd-invite-e2e}"
KEEP_RELAY="${KEEP_RELAY:-0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RELAY_DIR="$WORK_DIR/deploy/relay"
SANDBOX="$WORK_DIR/home"

log() { printf '\n[invite-e2e] %s\n' "$*" >&2; }
fail() { log "FAIL: $*"; exit 1; }

[ -n "$RELAY_IP" ] || fail "could not auto-detect RELAY_IP — set it explicitly"

ADB() { ssh "$ADB_HOST" "ANDROID_SERIAL=$ADB_SERIAL" adb "$@"; }

cleanup() {
  if [ "$KEEP_RELAY" != "1" ] && [ -d "$RELAY_DIR" ]; then
    log "teardown: compose down -v ($RELAY_DIR)"
    (cd "$RELAY_DIR" && docker compose down -v >/dev/null 2>&1) || true
  fi
}
trap cleanup EXIT

# ── Step 1: fresh throwaway relay via relay.sh (from a /tmp copy) ───────────
log "1/8 deploy fresh relay at $RELAY_IP:$RELAY_PORT (work dir: $WORK_DIR)"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/deploy" "$SANDBOX"
cp -r "$REPO_ROOT/deploy/relay" "$RELAY_DIR"
cp "$REPO_ROOT/packaging/relay.sh" "$WORK_DIR/relay.sh"
# Enable the NATS monitor endpoint (docker-network-internal only) so step 8
# can read /connz for the connection proof. Throwaway copy only — the repo
# relay.conf stays canonical.
printf '\nhttp: 0.0.0.0:8222\n' >> "$RELAY_DIR/relay.conf"

relay_out="$WORK_DIR/relay-sh.log"
(cd "$WORK_DIR" && sh relay.sh "$RELAY_IP:$RELAY_PORT") >"$relay_out" 2>&1 \
  || { tail -30 "$relay_out" >&2; fail "relay.sh deploy failed (log: $relay_out)"; }
tail -15 "$relay_out" >&2

# ── Step 2: parse the bootstrap join code ───────────────────────────────────
log "2/8 parse join token from relay.sh output"
# #1227: relay.sh prints a one-token wyrdjoin://host:port/<code>.<ca_fp>
# join URL (the fp rides inside so the zone verifies the relay it redeems
# against). Fall back to the legacy two-arg "join <host> <code>" shape.
JOIN_TOKEN=$(grep -oE 'wyrdjoin://[^[:space:]]+' "$relay_out" | head -1)
JOIN_CODE=$(grep -oE 'wyrd relay join [^ ]+ [a-z2-9]{8}$' "$relay_out" | awk '{print $NF}' | head -1)
[ -n "$JOIN_TOKEN" ] || [ -n "$JOIN_CODE" ] \
  || fail "no wyrdjoin:// token or join code in relay.sh output (log: $relay_out)"
log "join token: ${JOIN_TOKEN:-$RELAY_IP:$RELAY_PORT $JOIN_CODE}"

# ── Step 3: sandbox zone joins (NKey enrollment; no production zone touched) ─
# WYRDSEKAI_DATA_DIR sandboxes bin/wyrd's env persistence to $SANDBOX/env.
# `printf n` declines the restart prompt if a production zone is running on
# this host — enrollment (code redeem + register-nkey + env persist) happens
# before the restart question.
log "3/8 wyrd relay join (sandbox: $SANDBOX)"
join_out="$WORK_DIR/join.log"
if [ -n "$JOIN_TOKEN" ]; then
  set -- "$JOIN_TOKEN"
else
  set -- "$RELAY_IP:$RELAY_PORT" "$JOIN_CODE"
fi
printf 'n\n' | WYRDSEKAI_DATA_DIR="$SANDBOX" \
  "$REPO_ROOT/bin/wyrd" relay join "$@" \
  >"$join_out" 2>&1 \
  || { tail -30 "$join_out" >&2; fail "relay join failed (log: $join_out)"; }
grep -q 'WYRDSEKAI_RELAY_URL=' "$SANDBOX/env" \
  || fail "join did not persist WYRDSEKAI_RELAY_* into $SANDBOX/env"
log "joined: $(grep -c '^WYRDSEKAI_RELAY' "$SANDBOX/env") relay vars persisted"

# ── Step 4: mint the phone invite ───────────────────────────────────────────
log "4/8 wyrd phone invite"
invite_out="$WORK_DIR/invite.log"
WYRDSEKAI_DATA_DIR="$SANDBOX" "$REPO_ROOT/bin/wyrd" phone invite \
  >"$invite_out" 2>&1 \
  || { tail -30 "$invite_out" >&2; fail "phone invite failed (log: $invite_out)"; }
INVITE_URL=$(grep -oE 'wyrdphone://[^[:space:]]+' "$invite_out" | head -1)
[ -n "$INVITE_URL" ] || fail "no wyrdphone:// URL in output (log: $invite_out)"
log "invite minted (${#INVITE_URL} chars): ${INVITE_URL:0:60}…"

# ── Step 5: phase A — paste the invite in the Connect screen ────────────────
log "5/8 phone phase A: pm clear + remote-mode seed + Maestro paste flow"
ADB shell pm clear "$PHONE_PKG" >/dev/null
ADB shell am force-stop org.wyrdsekai.kmp >/dev/null 2>&1 || true

seed_remote="$WORK_DIR/seed-remote.json"
printf '{"@wyrd_first_run_complete":"true","@wyrd_app_mode":"remote"}' >"$seed_remote"
scp -q "$seed_remote" "$ADB_HOST:/tmp/wyrd-seed.json"
ssh "$ADB_HOST" "export ANDROID_SERIAL=$ADB_SERIAL; adb push /tmp/wyrd-seed.json /data/local/tmp/wyrd-seed.json >/dev/null \
  && adb shell run-as $PHONE_PKG mkdir -p /data/data/$PHONE_PKG/files \
  && adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrd-seed.json /data/data/$PHONE_PKG/files/wyrd-seed.json" \
  || fail "remote-mode seed push failed"

flow_a="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-invite-paste.yaml"
scp -q "$flow_a" "$ADB_HOST:/tmp/invite_paste_flow.yaml"
maestro_a="$WORK_DIR/maestro-paste.log"
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro --device $ADB_SERIAL test -e INVITE_URL='$INVITE_URL' /tmp/invite_paste_flow.yaml" \
  >"$maestro_a" 2>&1 \
  || { tail -25 "$maestro_a" >&2; fail "phase A Maestro flow failed (log: $maestro_a)"; }
log "phase A PASS: invite accepted in the UI"
ADB shell am force-stop "$PHONE_PKG" >/dev/null

# ── Step 6: local-mode reseed (relay creds persist from phase A) ────────────
# NO trust seeding: phase A's paste pins the household CA from the invite's
# ca_fp fingerprint (ConnectScreen → trustFromInviteFingerprints → native
# chain grab against Caddy's leaf+CA chain). If that path breaks, phase B's
# TLS handshake fails and proof C goes red — exactly what we want to catch.
log "6/8 local-mode routing keys (trust pinned by the invite itself)"
seed_local="$WORK_DIR/seed-local.json"
printf '{"@wyrd_first_run_complete":"true","@wyrd_app_mode":"local","@wyrd_companion_name":"Wyrd"}' >"$seed_local"
scp -q "$seed_local" "$ADB_HOST:/tmp/wyrd-seed.json"
ssh "$ADB_HOST" "export ANDROID_SERIAL=$ADB_SERIAL; adb push /tmp/wyrd-seed.json /data/local/tmp/wyrd-seed.json >/dev/null \
  && adb shell run-as $PHONE_PKG cp /data/local/tmp/wyrd-seed.json /data/data/$PHONE_PKG/files/wyrd-seed.json" \
  || fail "local-mode seed push failed"

# Baseline relay_phone connection count BEFORE the phone connects (the
# registration sidecar may hold its own NATS connection as relay_sidecar;
# count only relay_phone). /connz is read from inside the compose network —
# the monitor port is never published.
connz() {
  (cd "$RELAY_DIR" && docker compose exec -T registration python3 -c '
import json, urllib.request
d = json.load(urllib.request.urlopen("http://nats:8222/connz?auth=1", timeout=5))
print(sum(1 for c in d.get("connections", []) if c.get("authorized_user") == "relay_phone"))
' 2>/dev/null) || echo "-1"
}
baseline=$(connz)
log "relay_phone connections before phone: $baseline"

# ── Step 7: phase B — relaunch local, standalone path consumes the creds ────
log "7/8 phone phase B: relaunch + standalone room + NATS connect"
ssh "$ADB_HOST" "ANDROID_SERIAL=$ADB_SERIAL adb logcat -c" >/dev/null
flow_b="$REPO_ROOT/e2e/mobile/flows/rn/tier3/probe-relay-invite-connect.yaml"
scp -q "$flow_b" "$ADB_HOST:/tmp/invite_connect_flow.yaml"
maestro_b="$WORK_DIR/maestro-connect.log"
ssh "$ADB_HOST" "/home/you/.maestro/bin/maestro --device $ADB_SERIAL test /tmp/invite_connect_flow.yaml" \
  >"$maestro_b" 2>&1 \
  || { tail -25 "$maestro_b" >&2; fail "phase B Maestro flow failed (log: $maestro_b)"; }
sleep 5

# ── Step 8: server-side + app-side proof ────────────────────────────────────
log "8/8 verify the credential chain"
setup_line=$(ssh "$ADB_HOST" "ANDROID_SERIAL=$ADB_SERIAL adb logcat -d" 2>/dev/null | grep -oE '\[setup\] wss=[^ ]+ zone=[^ ]+ user=[^ ]+' | tail -1)
after=$(connz)

app_proof=FAIL
case "$setup_line" in
  *"wss=wss://$RELAY_IP:$RELAY_PORT"*user=relay_phone*) app_proof=PASS ;;
esac
relay_proof=FAIL
if [ "$after" != "-1" ] && [ "$baseline" != "-1" ] && [ "$after" -gt "$baseline" ]; then
  relay_proof=PASS
fi

echo
echo "──────── invite-chain proof tally ────────"
printf '  A. UI accepted pasted invite      : PASS  (Maestro asserted)\n'
printf '  B. App consumed invite creds      : %s  (%s)\n' "$app_proof" "${setup_line:-no [setup] line in logcat}"
printf '  C. Relay saw relay_phone CONNECT  : %s  (%s → %s connections)\n' "$relay_proof" "$baseline" "$after"
echo
echo "Logs: $WORK_DIR/{relay-sh,join,invite,maestro-paste,maestro-connect}.log"

[ "$app_proof" = PASS ] && [ "$relay_proof" = PASS ]
