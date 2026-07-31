#!/bin/sh
# Wyrdsekai Relay — all-in-one entrypoint.
#
# Runs, in ONE container, the same three long-lived processes the compose
# stack split across four services, plus the first-boot cert-gen that the
# `certinit` one-shot used to do:
#
#   1. gen-cert.sh   — household CA + leaf into /certs (idempotent; CA preserved)
#   2. nats-server   — bus on :4222 (zone leg) + ws :9222 (internal) + monitor
#                      127.0.0.1:8222. Reads the persistent conf DIRECTLY — no
#                      /tmp override, because the reaper is in THIS container so
#                      loopback monitoring works (this is what kills the
#                      /tmp/etc/nats/relay.conf crash-loop for good).
#   3. registration  — the sidecar, on localhost:9280; talks to nats on
#                      127.0.0.1 and SIGHUP-reloads nats directly after it
#                      appends a household (no inotify needed in one container).
#   4. caddy         — the single public TLS front on :4443, reverse-proxying
#                      ws → 127.0.0.1:9222 and the join paths → 127.0.0.1:9280.
#
# All mutable state lives in /certs and /var/lib/wyrd-relay (mounted volumes).
# Nothing is written to /tmp. If any process dies the container exits non-zero
# so the docker restart policy brings the whole set back cleanly.

set -eu

CERT_DIR="${CERT_DIR:-/certs}"
DATA_DIR="${DATA_DIR:-/var/lib/wyrd-relay}"
CONF="$DATA_DIR/relay.conf"
RUN_CADDYFILE="$DATA_DIR/Caddyfile"
NATS_PIDFILE="$DATA_DIR/nats.pid"

mkdir -p "$CERT_DIR" "$DATA_DIR"

# ── 1. Seed the live, writable conf once. registration.py rewrites the auth
#       block from env (phone/sidecar passwords) and appends household users to
#       THIS file, then reloads nats — so it must persist across restarts.
if [ ! -f "$CONF" ]; then
    cp /opt/relay/relay.conf.seed "$CONF"
    echo "[aio] seeded $CONF from baked default"
fi

# Inject the per-deploy randomized sidecar/phone passwords into the live conf.
# relay.sh passes them as env; registration.py also re-injects them, but doing
# it here guarantees conf ⇄ invite-mint consistency from the very first boot
# (otherwise a fresh relay would run with the PUBLIC committed-default phone
# password while invites mint with the random one → auth mismatch). Touches
# only the two named users' password fields — household entries are untouched.
_sidecar_pw="${RELAY_SIDECAR_NATS_PASSWORD:-${NATS_PASSWORD:-}}"
_phone_pw="${RELAY_PHONE_NATS_PASSWORD:-${NATS_PHONE_PASSWORD:-}}"
if [ -n "$_sidecar_pw" ] && grep -q 'user: "relay_sidecar"' "$CONF"; then
    sed -i "s|\(user: \"relay_sidecar\", password: \"\)[^\"]*|\1$_sidecar_pw|" "$CONF"
fi
if [ -n "$_phone_pw" ] && grep -q 'user: "relay_phone"' "$CONF"; then
    sed -i "s|\(user: \"relay_phone\", password: \"\)[^\"]*|\1$_phone_pw|" "$CONF"
fi

# ── 2. Certs (first boot or SAN/expiry change; CA preserved forever).
echo "[aio] cert-gen (names=${RELAY_HOST_NAMES:-localhost} ips=${RELAY_HOST_IPS:-127.0.0.1})"
CERT_DIR="$CERT_DIR" \
RELAY_HOST_NAMES="${RELAY_HOST_NAMES:-localhost}" \
RELAY_HOST_IPS="${RELAY_HOST_IPS:-127.0.0.1}" \
    /usr/local/bin/gen-cert.sh

# ── 3. Rewrite the shared Caddyfile's compose service-DNS targets to loopback
#       (single container = everything is localhost). One Caddyfile source.
sed -e 's#nats:9222#127.0.0.1:9222#g' \
    -e 's#registration:9280#127.0.0.1:9280#g' \
    /opt/relay/Caddyfile > "$RUN_CADDYFILE"

# ── 4. nats-server — persistent conf, loopback monitor, pidfile for reload.
nats-server -c "$CONF" -P "$NATS_PIDFILE" &
NATS_PID=$!

# Wait for the bus to accept connections before starting the sidecar.
for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:8222/varz" >/dev/null 2>&1; then break; fi
    kill -0 "$NATS_PID" 2>/dev/null || { echo "[aio] nats-server died on boot" >&2; exit 1; }
    sleep 1
done

# ── 5. registration sidecar — same env contract as the compose `registration`
#       service, but every cross-container hostname collapses to loopback, and
#       the reload signal is a real command (no inotify watcher in one box).
export CERT_DIR DATA_DIR
export NATS_CONF="$CONF"
export NATS_URL="${NATS_URL:-nats://127.0.0.1:4222}"
export NATS_MONITOR_URL="${NATS_MONITOR_URL:-http://127.0.0.1:8222}"
export NATS_USER="${NATS_USER:-${RELAY_SIDECAR_NATS_USER:-relay_sidecar}}"
# No literal fallback: registration.py generates + persists a per-install
# secret when neither env is set (OSS hardening 2026-07-25).
export NATS_PASSWORD="${NATS_PASSWORD:-${RELAY_SIDECAR_NATS_PASSWORD:-}}"
export NATS_PHONE_PASSWORD="${NATS_PHONE_PASSWORD:-${RELAY_PHONE_NATS_PASSWORD:-}}"
export REGISTRATION_PORT="${REGISTRATION_PORT:-9280}"
export NATS_SIGNAL_CMD="${NATS_SIGNAL_CMD:-nats-server --signal reload=$NATS_PIDFILE}"
export PYTHONUNBUFFERED=1
python3 /app/registration.py &
REG_PID=$!

# ── 6. caddy — single public TLS front.
caddy run --config "$RUN_CADDYFILE" --adapter caddyfile &
CADDY_PID=$!

# ── 7. OPTIONAL tunnel sshd. Only when explicitly enabled
#       — dormant by default so existing relays are byte-for-byte unchanged. The
#       forwarding-only sshd lets a NAT'd zone hold a reverse tunnel; the relay
#       never gets a shell and never decrypts the inner SSH (raw-byte forward).
SSHD_PID=""
if [ "${WYRD_SSH_TUNNEL_ENABLED:-false}" = "true" ]; then
    SSH_DIR="$DATA_DIR/ssh"
    SSH_CONF="$DATA_DIR/tunnel-sshd_config"
    SSH_HOSTKEY="$SSH_DIR/tunnel_host_ed25519_key"
    SSH_AUTHKEYS="$SSH_DIR/authorized_keys"
    SSH_TOPOLOGY="${WYRD_SSH_TUNNEL_TOPOLOGY:-port}"

    mkdir -p "$SSH_DIR"
    chmod 0700 "$SSH_DIR"
    # CRITICAL: own the dir by the tunnel account. sshd drops to the account's uid
    # (temporarily_use_uid) to read authorized_keys; a root-owned 0700 dir is then
    # un-traversable and sshd SILENTLY skips the keyfile (no "trying public key
    # file" log, auth fails with no StrictModes warning). authorized_keys itself is
    # written 0644 (pubkeys aren't secret) so the root-running sidecar needn't chown
    # on every regen. The host private key stays 0600 root inside this dir.
    chown wyrd-tunnel:wyrd-tunnel "$SSH_DIR" 2>/dev/null || true

    # Persistent host key → clients keep pinning the same fingerprint across
    # redeploys (a rotated key would look like a MITM). Generated once.
    if [ ! -f "$SSH_HOSTKEY" ]; then
        ssh-keygen -t ed25519 -N "" -C "wyrd-relay-tunnel" -f "$SSH_HOSTKEY" >/dev/null
        echo "[aio] generated tunnel sshd host key ($SSH_HOSTKEY)"
    fi

    # authorized_keys is regenerated from the ledger by registration.py; seed an
    # empty 0644 file so sshd starts cleanly before the first zone opts in. 0644
    # (world-readable) is required because sshd reads it as the unprivileged tunnel
    # uid and the file is owned by root (the sidecar); pubkeys are not secret.
    if [ ! -f "$SSH_AUTHKEYS" ]; then
        : > "$SSH_AUTHKEYS"
        chmod 0644 "$SSH_AUTHKEYS"
    fi

    # In `jump` topology the relay needs a shared forward-only ProxyJump key:
    # registration.py reads jump_ed25519_key.pub to emit the jump-principal line,
    # and the enable response ships the private half to the connecting user.
    # Persist it so the ProxyJump stanzas keep working across redeploys.
    SSH_JUMPKEY="$SSH_DIR/jump_ed25519_key"
    if [ "$SSH_TOPOLOGY" = "jump" ] && [ ! -f "$SSH_JUMPKEY" ]; then
        ssh-keygen -t ed25519 -N "" -C "wyrd-relay-jump" -f "$SSH_JUMPKEY" >/dev/null
        chmod 0600 "$SSH_JUMPKEY"
        echo "[aio] generated ProxyJump principal key ($SSH_JUMPKEY)"
    fi

    # Render the live config from the baked seed. In `jump` topology relax the
    # global to `AllowTcpForwarding yes` (ProxyJump uses a direct-tcpip channel
    # the `remote`-only default blocks); pivot-prevention is recovered per-key
    # via restrict+permitlisten/permitopen in authorized_keys. `port` topology
    # keeps the strictest global (`remote`).
    cp /opt/relay/tunnel-sshd_config.seed "$SSH_CONF"
    if [ "$SSH_TOPOLOGY" = "jump" ]; then
        sed -i 's|^AllowTcpForwarding .*|AllowTcpForwarding yes|' "$SSH_CONF"
    fi

    # sshd refuses to use a config/key that group/other can write.
    chmod 0600 "$SSH_CONF" "$SSH_HOSTKEY"

    /usr/sbin/sshd -D -e -f "$SSH_CONF" &
    SSHD_PID=$!
    echo "[aio] tunnel sshd up: pid=$SSHD_PID topology=$SSH_TOPOLOGY"
fi

echo "[aio] up: nats=$NATS_PID registration=$REG_PID caddy=$CADDY_PID sshd=${SSHD_PID:-off}"

term() {
    echo "[aio] signal — stopping children" >&2
    kill -TERM "$NATS_PID" "$REG_PID" "$CADDY_PID" ${SSHD_PID:+$SSHD_PID} 2>/dev/null || true
}
trap term TERM INT

# Supervise: busybox ash has no `wait -n`, so poll. If any child exits, tear the
# rest down and exit non-zero → docker `restart: unless-stopped` restarts clean.
while kill -0 "$NATS_PID" 2>/dev/null \
   && kill -0 "$REG_PID" 2>/dev/null \
   && kill -0 "$CADDY_PID" 2>/dev/null \
   && { [ -z "$SSHD_PID" ] || kill -0 "$SSHD_PID" 2>/dev/null; }; do
    sleep 5
done

echo "[aio] a child process exited — shutting the container down for a clean restart" >&2
term
exit 1
