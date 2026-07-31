#!/bin/sh
# Wyrdsekai relay — one-shot installer.  See docs/RELAY.md.
#
#   sh relay.sh                              # (no args) show this help
#   sh relay.sh deploy                       # deploy; address auto-detected (first LAN IP)
#   sh relay.sh relay.mydomain.com           # docker if present, else native
#   sh relay.sh 203.0.113.7:5000             # any port you like (default 4443)
#   sh relay.sh --native relay.mydomain.com  # force no-docker (systemd) install
#   sh relay.sh list                         # show registrations (+ who's connected)
#   sh relay.sh invite [--ttl SEC]           # mint a fresh single-use join token (no redeploy)
#   sh relay.sh claim-mint                   # mint an owner-claim token (take admin ownership)
#   sh relay.sh remove <key|did>             # operator forced-removal (kick) of a node
#   sh relay.sh update [new-bundle.tgz]      # redeploy with the recorded flags
#   sh relay.sh fingerprint                  # CA fingerprint + how users join
#   sh relay.sh uninstall                    # cleanly remove either install
#
# OPTIONAL argument: the address your devices will use to reach this relay
# (DNS name or IP, optional :port — default 4443). Omit it and the script
# uses the first non-loopback LAN IP — that address only seeds the dial
# default baked into join tokens; the leaf cert covers every interface IP
# and devices pin the household-CA fingerprint, not the hostname, so any
# reachable address works (and a per-token override always wins). TLS is the
# household CA: every device pins the relay from invite material (the join
# token a zone redeems, the wyrdphone:// URL a phone scans), so there is
# nothing to configure, no web PKI, and no port-443 fight with whatever
# web server already lives on this box. Nothing browses a relay.
#
# Two install modes, same relay:
#   docker  — compose stack (default when docker is present)
#   native  — no docker: static nats-server + caddy binaries, a python
#             venv for registration, systemd units (root) or nohup.
#
# Idempotent: re-running upgrades the deployment in place and never rotates
# identity or credentials. `--reset` wipes the relay identity (certs,
# registrations, invite key) and starts fresh. `uninstall` removes it all.
#
# POSIX sh on purpose — this runs on bare VPSes before anything is installed.

set -eu

REPO_URL="${WYRD_REPO_URL:-https://gitlab.com/masmoo/wyrdsekai.git}"
BUNDLE_DIR=""
RESET=0
INVITE_TTL="${WYRD_RELAY_INVITE_TTL:-86400}"
HOST_ARG=""
HOST_EXPLICIT=0                                  # 1 = operator named a host/domain
MODE=""                                          # "" = auto, docker, native
# Docker mode = ONE container (nats + registration sidecar + caddy + first-boot
# cert-gen), built from deploy/relay/Dockerfile. No compose.
AIO_NAME="${WYRD_RELAY_AIO_NAME:-wyrdsekai-relay}"   # container name
AIO_IMAGE="${WYRD_RELAY_AIO_IMAGE:-wyrdsekai-relay:aio}"
AIO_VOL_CERTS="${AIO_NAME}-certs"
AIO_VOL_DATA="${AIO_NAME}-data"
ACTION="deploy"
REMOVE_PUBKEY=""                                 # `remove <pubkey>` target
RESTORE_FILE=""                                  # `restore <archive>` source
BACKUP_OUT=""                                    # `backup --out <file>` override
PREFIX="${WYRD_RELAY_PREFIX:-/opt/wyrdsekai-relay}"
NATS_VERSION="${WYRD_RELAY_NATS_VERSION:-2.11.4}"
CADDY_VERSION="${WYRD_RELAY_CADDY_VERSION:-2.10.0}"

# Co-hosting: a 2nd ("hidden") relay alongside the public one on the SAME box
# needs distinct systemd unit names + distinct backend ports. WYRD_RELAY_INSTANCE
# suffixes the units; WYRD_RELAY_PORT_OFFSET shifts the three backend ports
# (zone-leg 4222, internal ws 9222, internal registration 9280) so they don't
# clash. Pick a distinct public TLS port via the host arg (host:port) too.
INSTANCE="${WYRD_RELAY_INSTANCE:-}"
case "$INSTANCE" in ''|*[!a-z0-9-]*) [ -n "$INSTANCE" ] && { printf 'bad WYRD_RELAY_INSTANCE (use [a-z0-9-])\n' >&2; exit 1; } ;; esac
PORT_OFFSET="${WYRD_RELAY_PORT_OFFSET:-0}"
UNIT_BASE="wyrd-relay${INSTANCE:+-$INSTANCE}"
NATS_PORT=$((4222 + PORT_OFFSET))                # public zone-leg
WS_PORT=$((9222 + PORT_OFFSET))                  # internal NATS ws (caddy → here)
REG_PORT=$((9280 + PORT_OFFSET))                 # internal registration sidecar
MON_PORT=$((8222 + PORT_OFFSET))                 # loopback NATS monitor (reaper polls /connz)
# Discoverability (hidden-SSID model): public = listed/answers directory probes;
# private = answers none (dark to enumeration) but fully usable with the token.
PUBLIC=1
# b — optional deploy-time owner DID (the --owner
# shortcut). Empty = unclaimed; deploy mints an owner-claim token instead.
OWNER_DID="${WYRD_RELAY_OWNER_DID:-}"
# registration mode set at deploy (--mode). Empty
# leaves the relay's existing/default mode (invite-only) untouched. The relay's
# relay-policy.json is the runtime source of truth; this env only SEEDS the
# first read, so a runtime `set-mode` is never silently reverted by a redeploy.
RELAY_MODE="${WYRD_RELAY_MODE:-}"

# the OPTIONAL forwarding-only tunnel sshd that lets
# bare `ssh` reach a NAT'd home zone. Off by default; `--ssh-tunnel[=jump]` turns
# it on. `port` topology (default, household) publishes a per-zone public port
# range; `jump` topology (commons) publishes only the one control port (2222) and
# fans out via ProxyJump. The zone ports live loopback in jump mode.
SSH_TUNNEL="${WYRD_SSH_TUNNEL_ENABLED:+1}"; [ "${SSH_TUNNEL:-}" = "1" ] || SSH_TUNNEL=0
SSH_TUNNEL_TOPOLOGY="${WYRD_SSH_TUNNEL_TOPOLOGY:-port}"
SSH_TUNNEL_CTRL_PORT="${WYRD_SSH_TUNNEL_PORT:-2222}"
SSH_TUNNEL_PORT_BASE="${WYRD_SSH_TUNNEL_PORT_BASE:-7100}"
SSH_TUNNEL_PORT_COUNT="${WYRD_SSH_TUNNEL_PORT_COUNT:-50}"
# off | grant (owner/relay-admin grant only) | open (any registered zone self-serves).
# registration.py seeds relay-policy.json `ssh_tunnel_mode` from this env and gates
# `wyrd relay ssh-enable` on it. Default `grant` (household owner enables zones).
SSH_TUNNEL_MODE="${WYRD_SSH_TUNNEL_MODE:-grant}"

say()  { printf '\033[0;32m[relay]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[relay]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31m[relay]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,33p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//'
    cat <<'EOF'
Options:
  --native           force the no-docker install (binaries + systemd/nohup)
  --docker           force the single-container docker install
  --bundle-dir DIR   use a local relay bundle (default: auto-locate or fetch)
  --reset            wipe relay identity (certs, registrations) before deploy
  --invite-ttl SECS  bootstrap invite lifetime (default 86400 = 24h)
  --private          hidden relay: in no directory, answers no discovery probe
                     (still fully usable with the join token). Default: public.
  --public           discoverable relay (the default).
  --owner DID        record this did:key:… or did:wyrd:… as the relay's admin owner at deploy.
                     Skips the owner-claim step.
                     Find your zone's DID with `wyrd whoami`. Without it, deploy
                     mints a one-time owner-claim token to redeem via
                     `wyrd relay claim <token>`.
  --mode MODE        registration mode (see docs/RELAY.md):
                       invite-only  every join needs an invite (DEFAULT).
                                    Entrants enter at HOUSEHOLD tier.
                       open         invite-less join (LAN/firewalled relays
                                    where the perimeter IS the trust boundary).
                                    Entrants enter at HOUSEHOLD-equivalent.
                       commons      invite-less self-serve join (public
                                    commons). Entrants enter at FLOOR tier;
                                    a verified IdentityOutbox + WoT vouches
                                    promote them. Per-IP rate-limit applies hard.
                     Runtime-changeable via the signed `set-mode` admin op; this
                     flag only seeds the initial value (a runtime change wins).
  --ssh-tunnel[=port|jump]
                     run the OPTIONAL forwarding-only tunnel sshd so bare `ssh`
                     reaches a NAT'd home zone through this relay (off by
                     default). Topology:
                       port   per-zone PUBLIC port (default; household/private
                              relay) — user runs `ssh -p <port> name@relay`.
                              Publishes the control port (2222) + a port range.
                       jump   one ProxyJump port for ALL zones (public/commons,
                              scales without burning N ports) — user runs
                              `ssh <zone>` via an auto-emitted ~/.ssh/config
                              stanza. Publishes ONLY the control port (2222).
                     Per-zone opt-in is still required (a zone runs `wyrd relay
                     ssh-enable`) and is gated by the relay's ssh_tunnel_mode
                     (off/grant/open). The relay forwards raw bytes only — SSH
                     stays end-to-end encrypted; it never gets a shell.

Operator commands (no host arg):
  list               list registrations (pubkey, household, kind, active,
                     last_seen) and mark which are CURRENTLY connected
  invite [--ttl SEC] mint a fresh single-use household-join token from the
                     ALREADY-RUNNING relay (no redeploy). Default TTL 86400
                     (24h); the server caps it at 24h. Prints the
                     `wyrd relay join wyrdjoin://…` line to hand to a zone.
  claim-mint         mint a one-time owner-claim token from the ALREADY-RUNNING
                     relay (no redeploy). Prints the `wyrd relay claim …` line a
                     zone runs to record its DID as this relay's admin owner.
                     Use when the deploy-time claim line was missed.
  fingerprint (fp)   print the relay CA's SHA-256 fingerprint — the value a
                     commons operator publishes on their web page, and the one
                     users pass to `wyrd relay join <host> --fingerprint <fp>`
  update [tgz]       redeploy with the flags recorded at the last deploy —
                     with a bundle path, extracts it and hands off to the NEW
                     relay.sh; identity and registrations are untouched
  remove <key|did>   forcibly remove a registration (kick) — takes the ledger
                     KEY that `list` prints (U… NKey or hh-* household id) or
                     a did:key:… (resolved; ambiguity lists the keys) — no node
                     cooperation/signature needed; you have root on the box.
                     For self-service node-initiated removal use `wyrd relay
                     leave` (signed) instead.
  backup [--out F]   archive the relay IDENTITY (household CA + leaf, invite-key,
                     registrations.json, owner/grants/policy) to a single .tgz
                     (default: relay-identity-<host>-<UTC>.tgz in the cwd). Do
                     this BEFORE a --reset or a box migration — losing the
                     identity forces every paired device to re-pair.
  restore <archive>  restore a backup .tgz onto THIS box (docker or native),
                     overwriting the current identity. The relay must already be
                     deployed here (so the volumes/prefix exist); restore stops
                     the relay, swaps the identity in, and restarts it. Used to
                     move a relay to a new host without re-pairing devices.

Environment:
  WYRD_RELAY_PREFIX        native install root (default /opt/wyrdsekai-relay)
  WYRD_RELAY_INSTANCE      name suffix for co-hosting a 2nd relay on one box
                           (units become wyrd-relay-<name>-*); native mode only
  WYRD_RELAY_PORT_OFFSET   shift the 3 backend ports (zone-leg 4222, ws 9222,
                           registration 9280) by N so a 2nd instance won't clash

Co-host a public + hidden relay on one box (native):
  sudo sh relay.sh relay.example.com                       # public, 4443/4222
  sudo WYRD_RELAY_INSTANCE=hidden \\
       WYRD_RELAY_PREFIX=/opt/wyrdsekai-relay-hidden \\
       WYRD_RELAY_PORT_OFFSET=100 \\
       sh relay.sh relay.example.com:4543 --private        # hidden, 4543/4322
EOF
}

# ── Args ──────────────────────────────────────────────────────────────────
# Bare invocation (no host, no flags, no action) shows help instead of silently
# deploying — least-surprise. To deploy with the auto-detected IP, use the
# explicit `deploy` verb (or pass a host / any flag).
[ $# -eq 0 ] && { usage; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --bundle-dir)   BUNDLE_DIR="${2:?--bundle-dir needs a path}"; shift 2 ;;
        --bundle-dir=*) BUNDLE_DIR="${1#--bundle-dir=}"; shift ;;
        --reset)        RESET=1; shift ;;
        --native)       MODE=native; shift ;;
        --docker)       MODE=docker; shift ;;
        --invite-ttl)   INVITE_TTL="${2:?--invite-ttl needs seconds}"; shift 2 ;;
        --ttl)          INVITE_TTL="${2:?--ttl needs seconds}"; shift 2 ;;
        --private)      PUBLIC=0; shift ;;
        --public)       PUBLIC=1; shift ;;
        --owner)        OWNER_DID="${2:?--owner needs a did:key: or did:wyrd: value}"; shift 2 ;;
        --owner=*)      OWNER_DID="${1#--owner=}"; shift ;;
        --mode)         RELAY_MODE="${2:?--mode needs invite-only|open|commons}"; shift 2 ;;
        --mode=*)       RELAY_MODE="${1#--mode=}"; shift ;;
        --ssh-tunnel)        SSH_TUNNEL=1; shift ;;
        --ssh-tunnel=jump)   SSH_TUNNEL=1; SSH_TUNNEL_TOPOLOGY=jump; shift ;;
        --ssh-tunnel=port)   SSH_TUNNEL=1; SSH_TUNNEL_TOPOLOGY=port; shift ;;
        --ssh-tunnel-jump)   SSH_TUNNEL=1; SSH_TUNNEL_TOPOLOGY=jump; shift ;;
        --ssh-tunnel-mode)   SSH_TUNNEL=1; SSH_TUNNEL_MODE="${2:?--ssh-tunnel-mode needs off|grant|open}"; shift 2 ;;
        --ssh-tunnel-mode=*) SSH_TUNNEL=1; SSH_TUNNEL_MODE="${1#--ssh-tunnel-mode=}"; shift ;;
        --ssh-tunnel=*)      die "unknown --ssh-tunnel topology '${1#--ssh-tunnel=}' (use port|jump)" ;;
        --out)          BACKUP_OUT="${2:?--out needs a file path}"; shift 2 ;;
        --out=*)        BACKUP_OUT="${1#--out=}"; shift ;;
        -h|--help)      usage; exit 0 ;;
        -*)             die "unknown flag '$1' (try --help)" ;;
        --start|start)  shift ;;   # explicit start verb (ACTION already defaults to deploy)
        deploy)         shift ;;   # alias for --start
        uninstall)      ACTION=uninstall; shift ;;
        list|status)    ACTION=list; shift ;;
        invite)         ACTION=invite; shift ;;
        claim-mint|claim-token|owner-mint) ACTION=claim-mint; shift ;;
        remove)         ACTION=remove
                        REMOVE_PUBKEY="${2:-}"
                        [ -n "$REMOVE_PUBKEY" ] || die "remove needs a pubkey: sh relay.sh remove <U…>"
                        shift 2 ;;
        backup)         ACTION=backup; shift ;;
        restore)        ACTION=restore
                        RESTORE_FILE="${2:-}"
                        [ -n "$RESTORE_FILE" ] || die "restore needs an archive: sh relay.sh restore <relay-identity-….tgz>"
                        shift 2 ;;
        update)         ACTION=update
                        UPDATE_TARBALL="${2:-}"
                        if [ -n "$UPDATE_TARBALL" ]; then shift 2; else shift; fi ;;
        fingerprint|fp) ACTION=fingerprint; shift ;;
        *)  [ -n "$HOST_ARG" ] && die "unexpected extra argument '$1'"
            HOST_ARG="$1"; HOST_EXPLICIT=1; shift ;;
    esac
done

# ── update: redeploy with the flags recorded at the last deploy ──────────
# The old update story ("scp a tarball, rm the old dir, re-extract, replay a
# six-flag deploy command from memory") failed live on 2026-07-30: a redeploy
# ran a STALE extracted relay.sh and re-broke the box. `update` kills it:
#   sh relay.sh update <new-bundle.tgz>   # extract + hand off to the NEW code
#   sh relay.sh update                    # replay recorded flags (run from a
#                                         # freshly extracted bundle dir)
# Deploy records its effective flags in $PREFIX/conf/deploy.conf; update
# replays them — nothing to remember, identity untouched.
if [ "$ACTION" = "update" ]; then
    if [ -n "${UPDATE_TARBALL:-}" ]; then
        [ -f "$UPDATE_TARBALL" ] || die "no such bundle: $UPDATE_TARBALL"
        _tmp=$(mktemp -d) || die "mktemp failed"
        tar -xzf "$UPDATE_TARBALL" -C "$_tmp" || die "could not extract $UPDATE_TARBALL"
        _new=$(find "$_tmp" -maxdepth 2 -name relay.sh | head -1)
        [ -n "$_new" ] || die "$UPDATE_TARBALL does not contain relay.sh"
        say "handing off to the new bundle's relay.sh…"
        exec sh "$_new" update
    fi
    DEPLOY_CONF="$PREFIX/conf/deploy.conf"
    [ -f "$DEPLOY_CONF" ] || die "no recorded deploy at $DEPLOY_CONF — this install predates 'update'. Redeploy once with your usual flags; every update after that is just: sh relay.sh update <new-bundle.tgz>"
    # shellcheck disable=SC1090
    . "$DEPLOY_CONF"
    MODE="${WYRD_RELAY_DEPLOY_MODE:-$MODE}"
    PUBLIC="${WYRD_RELAY_DEPLOY_PUBLIC:-$PUBLIC}"
    RELAY_MODE="${WYRD_RELAY_DEPLOY_REG_MODE:-$RELAY_MODE}"
    SSH_TUNNEL="${WYRD_RELAY_DEPLOY_SSH_TUNNEL:-$SSH_TUNNEL}"
    SSH_TUNNEL_TOPOLOGY="${WYRD_RELAY_DEPLOY_SSH_TOPOLOGY:-$SSH_TUNNEL_TOPOLOGY}"
    SSH_TUNNEL_MODE="${WYRD_RELAY_DEPLOY_SSH_MODE:-$SSH_TUNNEL_MODE}"
    OWNER_DID="${WYRD_RELAY_DEPLOY_OWNER_DID:-$OWNER_DID}"
    HOST_ARG="${WYRD_RELAY_DEPLOY_HOST_ARG:-$HOST_ARG}"
    [ -n "$HOST_ARG" ] && HOST_EXPLICIT=1
    say "update: replaying recorded deploy (mode=$MODE public=$PUBLIC reg-mode=${RELAY_MODE:-invite-only} ssh-tunnel=$SSH_TUNNEL/$SSH_TUNNEL_TOPOLOGY/$SSH_TUNNEL_MODE host=${HOST_ARG:-auto})"
    ACTION=deploy
fi

# ── Locate the relay bundle (best-effort; deploy paths require it) ───────
locate_bundle() {
    [ -n "$BUNDLE_DIR" ] && return 0
    # Intentional A && B || C: empty SCRIPT_DIR when cd fails.
    # shellcheck disable=SC2015
    SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" 2>/dev/null && pwd || true)
    for cand in \
        "${SCRIPT_DIR:+$SCRIPT_DIR/../deploy/relay}" \
        "${SCRIPT_DIR:+$SCRIPT_DIR/deploy/relay}" \
        ./deploy/relay \
        /opt/wyrdsekai/deploy/relay \
        /opt/wyrdsekai-relay/deploy/relay; do
        [ -n "$cand" ] && [ -f "$cand/Dockerfile" ] && BUNDLE_DIR="$cand" && return 0
    done
    return 1
}

native_stop_nohup() {
    for svc in ssh-tunnel caddy registration nats; do
        pidfile="$PREFIX/data/$svc.pid"
        if [ -f "$pidfile" ]; then
            kill "$(cat "$pidfile")" 2>/dev/null || true
            rm -f "$pidfile"
        fi
    done
}

native_stop_systemd() {
    # Returns 0 if any unit existed (caller decides on daemon-reload).
    found=1
    for unit in "$UNIT_BASE-ssh-tunnel" "$UNIT_BASE-caddy" "$UNIT_BASE-registration" "$UNIT_BASE-nats"; do
        if [ -f "/etc/systemd/system/$unit.service" ]; then
            systemctl disable --now "$unit" >/dev/null 2>&1 || true
            rm -f "/etc/systemd/system/$unit.service"
            found=0
        fi
    done
    return $found
}

# ── uninstall: remove either install, cleanly ────────────────────────────
if [ "$ACTION" = "uninstall" ]; then
    warn "uninstall removes the relay IDENTITY (household CA) — every invite"
    warn "and device pin issued by this relay dies with it."
    removed=0

    # Docker leg — single container + its volumes.
    if command -v docker >/dev/null 2>&1; then
        if docker ps -aq -f "name=^${AIO_NAME}$" 2>/dev/null | grep -q .; then
            say "docker: removing container $AIO_NAME + volumes ($AIO_VOL_CERTS, $AIO_VOL_DATA)"
            docker rm -f "$AIO_NAME" >/dev/null 2>&1 || true
            docker volume rm "$AIO_VOL_CERTS" "$AIO_VOL_DATA" >/dev/null 2>&1 || true
            removed=1
        fi
        locate_bundle 2>/dev/null && rm -f "$BUNDLE_DIR/.env" 2>/dev/null || true
    fi

    # Native leg: systemd units.
    if command -v systemctl >/dev/null 2>&1 && native_stop_systemd; then
        systemctl daemon-reload 2>/dev/null || true
        say "native: stopped + removed wyrd-relay-* systemd units"
        removed=1
    fi
    # Native leg: nohup pids.
    if [ -d "$PREFIX/data" ]; then
        native_stop_nohup
    fi
    # Native leg: install root. Only when it is actually a native install
    # (marker check) — never delete a repo checkout the bundle came from.
    if [ -x "$PREFIX/bin/nats-server" ] || [ -f "$PREFIX/conf/registration.env" ]; then
        rm -rf "$PREFIX"
        say "native: removed $PREFIX"
        removed=1
    fi

    if [ "$removed" -eq 1 ]; then
        say "uninstall complete."
    else
        say "nothing to remove (no docker stack, no native install at $PREFIX)."
    fi
    exit 0
fi

# ── Operator commands: list / remove (no host arg) ───────────────────────
# Both inspect/mutate the live registration ledger of whichever install is
# running. Detect mode the same way deploy/uninstall do: a docker stack with
# a running `registration` container wins; otherwise the native prefix.

# Resolve the running docker registration container id (empty if none).
op_docker_reg_cid() {
    # Single-container relay: registration.py runs INSIDE the one container, so
    # every `docker exec "$REG_CID" …` op (invite/list/remove/backup) targets it.
    command -v docker >/dev/null 2>&1 || return 1
    docker ps -q -f "name=^${AIO_NAME}$" 2>/dev/null | head -1
}

# Every reachable address this relay advertises (RELAY_PUBLIC_HOSTS), space-
# separated, MINUS $1 (the address already printed). Empty unless multi-homed.
# Lets invite/claim output note "also reachable at: …" so an operator on a
# different subnet knows the token works against any NIC (it validates by
# fingerprint, not host).
op_other_hosts() {
    _primary="$1" _raw=""
    if [ "${OP_MODE:-}" = docker ] && [ -n "${REG_CID:-}" ]; then
        _raw=$(docker exec "$REG_CID" printenv RELAY_PUBLIC_HOSTS 2>/dev/null || true)
    elif [ -f "$PREFIX/conf/registration.env" ]; then
        _raw=$( . "$PREFIX/conf/registration.env" >/dev/null 2>&1; printf '%s' "${RELAY_PUBLIC_HOSTS:-}" )
    fi
    _out=""
    for _h in $(printf '%s' "$_raw" | tr ',' ' '); do
        [ "$_h" = "$_primary" ] && continue
        _out="${_out:+$_out }$_h"
    done
    printf '%s' "$_out"
}

# ── fingerprint: the relay CA's SHA-256 — the commons trust anchor ────────
# This is the value an operator PUBLISHES (on the relay's web page, served
# over ordinary web-PKI HTTPS) and the value users verify against when they
# self-serve join. It was previously only reachable via a raw openssl
# incantation against a root-owned path; the trust anchor deserves a verb.
if [ "$ACTION" = "fingerprint" ]; then
    _ca=""
    if [ -f "$PREFIX/certs/ca.crt" ]; then
        _ca="$PREFIX/certs/ca.crt"
    else
        _cid=$(op_docker_reg_cid 2>/dev/null || true)
        if [ -n "$_cid" ]; then
            _tmpca=$(mktemp)
            docker exec "$_cid" cat /certs/ca.crt > "$_tmpca" 2>/dev/null && _ca="$_tmpca"
        fi
    fi
    [ -n "$_ca" ] || die "no relay CA found (native: $PREFIX/certs/ca.crt; docker: certs volume) — is a relay deployed here?"
    _fp=$(openssl x509 -in "$_ca" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)
    [ -n "$_fp" ] || die "could not compute the CA fingerprint (openssl missing?)"
    _mode="invite-only"
    [ -f "$PREFIX/data/relay-policy.json" ] && \
        _mode=$(python3 -c "import json;print(json.load(open('$PREFIX/data/relay-policy.json')).get('mode') or 'invite-only')" 2>/dev/null || echo "invite-only")
    say "relay CA fingerprint (SHA-256):"
    printf '\n    %s\n\n' "$_fp"
    if [ "$_mode" = "commons" ]; then
        say "this relay is a COMMONS (invite-less). To let people join:"
        echo "    1. Publish the fingerprint above on your relay's web page"
        echo "       (ordinary HTTPS is the out-of-band trust channel)."
        echo "    2. Tell users to run:"
        echo "         wyrd relay join <your-relay-host> --fingerprint $_fp"
        echo "       (interactive runs may omit --fingerprint and confirm the"
        echo "        displayed value against your page instead)"
    else
        say "mode: $_mode — joins need an invite; mint one with: sh relay.sh invite"
        echo "    The invite token embeds this fingerprint; no separate publication needed."
    fi
    exit 0
fi

if [ "$ACTION" = "list" ] || [ "$ACTION" = "remove" ]; then
    # Validate the kick target's shape up front (before mode detection) so a
    # malformed pubkey gets the precise error regardless of relay state.
    if [ "$ACTION" = "remove" ]; then
        # Three addressable record shapes (2026-07-30 — list showed DIDs while
        # remove only took NKeys, so an operator could not get from one to the
        # other; and pw-mode records are keyed hh-*, which the old validation
        # refused outright):
        #   U…      56-char NATS user NKey — the registration ledger key
        #   hh-*    password-mode household record (keyed by household id)
        #   did:…   canonical identity — resolved to its ledger key below;
        #           ambiguity (one DID, several records) is an error that
        #           prints the matching keys to pick from.
        case "$REMOVE_PUBKEY" in
            U*)       [ "${#REMOVE_PUBKEY}" -eq 56 ] || die "bad pubkey: expected 56-char NATS user NKey (U…), got ${#REMOVE_PUBKEY} chars" ;;
            hh-*)     : ;;
            did:key:*) : ;;
            *)        die "bad target: expected a U… NKey, an hh-* household key, or a did:key:… (see relay.sh list)" ;;
        esac
    fi
    REG_CID=""
    OP_MODE=""
    if [ "$MODE" != "native" ]; then
        REG_CID=$(op_docker_reg_cid || true)
        [ -n "$REG_CID" ] && OP_MODE=docker
    fi
    if [ -z "$OP_MODE" ]; then
        if [ -f "$PREFIX/conf/registration.env" ] && [ -x "$PREFIX/venv/bin/python3" ]; then
            OP_MODE=native
        fi
    fi
    [ -n "$OP_MODE" ] || die "no running relay found (no docker 'registration' container, no native install at $PREFIX)."

    if [ "$ACTION" = "list" ]; then
        # Read registrations.json + connz, render a table. The python runs
        # INSIDE the install (registration container or native venv) so it
        # sees the same DATA_DIR / NATS_MONITOR_URL the sidecar uses.
        LIST_PY='
import json, os, urllib.request
from pathlib import Path
data_dir = Path(os.environ.get("DATA_DIR", "/var/lib/wyrd-relay"))
regs_file = data_dir / "registrations.json"
regs = {}
if regs_file.exists():
    try: regs = json.loads(regs_file.read_text())
    except Exception: regs = {}
# Which pubkeys are live right now? (best-effort; None on unreachable monitor)
connected = None
mon = os.environ.get("NATS_MONITOR_URL", "http://127.0.0.1:8222").rstrip("/")
try:
    with urllib.request.urlopen(mon + "/connz?auth=1", timeout=5) as r:
        d = json.loads(r.read().decode())
    connected = {c.get("nkey") for c in d.get("connections", []) if c.get("nkey")}
except Exception:
    connected = None
# surface the registration mode (relay-policy.json).
policy_file = data_dir / "relay-policy.json"
mode = "invite-only"
if policy_file.exists():
    try: mode = json.loads(policy_file.read_text()).get("mode") or mode
    except Exception: pass
print("  mode: %s" % mode)
# open abuse-report count (relay-reports.json).
reports_file = data_dir / "relay-reports.json"
if reports_file.exists():
    try:
        _rs = json.loads(reports_file.read_text())
        _open = sum(1 for r in _rs if isinstance(r, dict) and r.get("status") == "open")
        if _open:
            print("  reports: %d open (use the Warden to review)" % _open)
    except Exception:
        pass
if not regs:
    print("  (no registrations)")
else:
    # DID is the canonical identity (derived
    # relay-side from the pubkey). household_tag is a self-asserted operator
    # HINT only (§2.3), never identity.
    # §3/§2.2 — TIER + IDV (verified IdentityOutbox) columns added in P5.
    # 2026-07-30: each record leads with its FULL ledger key — the exact
    # argument `relay.sh remove` takes. The old table truncated DIDs and
    # never showed the key at all, so list gave an operator nothing that
    # remove would accept.
    print("  KEY (use with: sh relay.sh remove <KEY>)")
    print("  " + "-" * 78)
    for k, v in sorted(regs.items()):
        live = "?" if connected is None else ("yes" if k in connected else "no")
        print("  %s" % k)
        print("    did=%s" % (v.get("did") or "-"))
        print("    tier=%-10s idv=%-3s household=%-12s kind=%-5s active=%-3s live=%-3s last_seen=%s" % (
            str(v.get("tier", "HOUSEHOLD"))[:10],
            "yes" if v.get("identity_verified") else "no",
            str(v.get("household_tag", "-"))[:12],
            str(v.get("kind", "pw"))[:5],
            "yes" if v.get("active", True) else "NO",
            live,
            str(v.get("last_seen") or v.get("registered_at") or "-")[:19]))
    if connected is None:
        print("\n  (monitor unreachable — LIVE column is \"?\")")
'
        say "registrations ($OP_MODE):"
        if [ "$OP_MODE" = docker ]; then
            docker exec "$REG_CID" python3 -c "$LIST_PY" \
                || die "could not read registrations from the registration container"
        else
            ( set -a; . "$PREFIX/conf/registration.env"; set +a
              "$PREFIX/venv/bin/python3" -c "$LIST_PY" ) \
                || die "could not read registrations at $PREFIX/data"
        fi
        exit 0
    fi

    # remove <pubkey> — operator forced-removal (kick). Pubkey shape was
    # validated above. Delete the key from registrations.json and reproject
    # the NATS auth config (registration.update_nats_config) so the kicked
    # node can no longer authenticate. No signature: the operator has root on
    # this box. Distinct from `wyrd relay leave`, which is node-initiated +
    # signed.
    #
    # The reprojection runs through registration.py's own update_nats_config so
    # the kick reuses the exact same auth-config writer the sidecar uses (it
    # preserves system accounts + scopes household perms). Idempotent: an
    # absent pubkey prints "not registered" and exits 0.
    REMOVE_PY='
import importlib.util, json, os, sys
pubkey = sys.argv[1]
spec = importlib.util.spec_from_file_location("registration", os.environ.get("REG_PY", "/app/registration.py"))
reg = importlib.util.module_from_spec(spec); spec.loader.exec_module(reg)
with reg.lock:
    regs = reg.load_registrations()
    if pubkey.startswith("did:"):
        # Resolve a DID to its ledger key(s). One match: remove it. Several
        # (e.g. a zone record + its tunnel record): refuse and show the keys.
        matches = [k for k, v in regs.items() if isinstance(v, dict) and v.get("did") == pubkey]
        if not matches:
            print("absent"); sys.exit(0)
        if len(matches) > 1:
            print("ambiguous:" + ",".join(matches)); sys.exit(0)
        pubkey = matches[0]
    if pubkey not in regs:
        print("absent"); sys.exit(0)
    del regs[pubkey]
    reg.save_registrations(regs)
    try:
        reg.update_nats_config(regs)
    except Exception as e:
        print("removed-warn:" + str(e)); sys.exit(0)
print("removed:" + pubkey)
'
    if [ "$OP_MODE" = docker ]; then
        OUT=$(docker exec -e REG_PY=/app/registration.py "$REG_CID" \
                python3 -c "$REMOVE_PY" "$REMOVE_PUBKEY") \
            || die "remove failed inside the registration container"
    else
        OUT=$( set -a; . "$PREFIX/conf/registration.env"; set +a
               REG_PY="$PREFIX/registration.py" \
               "$PREFIX/venv/bin/python3" -c "$REMOVE_PY" "$REMOVE_PUBKEY" ) \
            || die "remove failed (native)"
    fi
    case "$OUT" in
        absent)        say "$(printf '%.20s' "$REMOVE_PUBKEY")… not registered (nothing to do)." ;;
        ambiguous:*)   warn "that DID has several records — remove each by its exact key:"
                       for _k in $(printf '%s' "${OUT#ambiguous:}" | tr ',' ' '); do
                           printf '    sh relay.sh remove %s\n' "$_k"
                       done ;;
        removed:*)     say "removed ${OUT#removed:} — deleted from the ledger and pulled from the live NATS auth config." ;;
        removed-warn:*) warn "removed $(printf '%.20s' "$REMOVE_PUBKEY")… from the ledger, but the NATS config rewrite warned: ${OUT#removed-warn:}" ;;
        *)             warn "remove returned an unexpected result: $OUT" ;;
    esac
    exit 0
fi

# ── invite: mint a FRESH single-use join token from a running relay ───────
# Fills the gap between deploy (which mints a bootstrap invite) and the manual
# localhost curl. Same /invite endpoint, same parse + render as the deploy
# block below — but reaches into whichever install is running (docker or
# native) the same way list/remove do, and takes host:port from the /invite
# JSON response (the sidecar knows RELAY_PUBLIC_HOST/RELAY_PORT) rather than a
# host arg. Localhost-only mint: the /invite endpoint is never publicly
# proxied, so this opens no new network surface.
if [ "$ACTION" = "invite" ]; then
    REG_CID=""
    OP_MODE=""
    if [ "$MODE" != "native" ]; then
        REG_CID=$(op_docker_reg_cid || true)
        [ -n "$REG_CID" ] && OP_MODE=docker
    fi
    if [ -z "$OP_MODE" ]; then
        if [ -f "$PREFIX/conf/registration.env" ] && [ -x "$PREFIX/venv/bin/python3" ]; then
            OP_MODE=native
        fi
    fi
    [ -n "$OP_MODE" ] || die "no running relay found — deploy one first (no docker 'registration' container, no native install at $PREFIX)."

    # Mint via the loopback /invite endpoint, exactly as the deploy block does.
    if [ "$OP_MODE" = docker ]; then
        INVITE_JSON=$(docker exec "$REG_CID" wget -qO- \
            --post-data="{\"ttl\":${INVITE_TTL}}" \
            --header="Content-Type: application/json" \
            http://127.0.0.1:9280/invite 2>/dev/null) \
            || die "invite mint failed inside the registration container"
    else
        # Native sidecar listens on $REG_PORT; registration.env carries the
        # same REGISTRATION_PORT the running sidecar bound to.
        REG_INVITE_PORT="$REG_PORT"
        if [ -f "$PREFIX/conf/registration.env" ]; then
            # shellcheck disable=SC1091
            ENV_REG_PORT=$( . "$PREFIX/conf/registration.env" >/dev/null 2>&1; printf '%s' "${REGISTRATION_PORT:-}" )
            [ -n "$ENV_REG_PORT" ] && REG_INVITE_PORT="$ENV_REG_PORT"
        fi
        INVITE_JSON=$(curl -sf -X POST -H "Content-Type: application/json" \
            -d "{\"ttl\":${INVITE_TTL}}" \
            "http://127.0.0.1:$REG_INVITE_PORT/invite") \
            || die "invite mint failed (native, port $REG_INVITE_PORT)"
    fi

    # Parse the SAME fields the deploy block parses, plus host/port (the
    # sidecar returns them in the /invite response).
    INVITE_URL=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"invite_url": *"\([^"]*\)".*/\1/p')
    JOIN_CODE=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"join_code": *"\([^"]*\)".*/\1/p')
    CA_FP=$(printf '%s' "$INVITE_JSON" \
        | sed -n 's/.*"ca_fingerprint": *"\([^"]*\)".*/\1/p' \
        | tr -d ':' | tr 'A-F' 'a-f')
    I_HOST=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"host": *"\([^"]*\)".*/\1/p')
    # port is a JSON number (unquoted) but tolerate a quoted form too.
    I_PORT=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"port": *"\{0,1\}\([0-9]*\)"\{0,1\}.*/\1/p')
    [ -n "$INVITE_URL" ] || die "could not parse invite from: $INVITE_JSON"

    if [ "$INVITE_TTL" -ge 3600 ]; then
        TTL_HUMAN="$((INVITE_TTL/3600))h"
    else
        TTL_HUMAN="$((INVITE_TTL/60))min"
    fi

    echo ""
    say "fresh invite minted ($OP_MODE relay) — valid $TTL_HUMAN, single use:"
    echo ""
    if [ -n "$JOIN_CODE" ] && [ -n "$CA_FP" ] && [ -n "$I_HOST" ] && [ -n "$I_PORT" ]; then
        echo "    wyrd relay join wyrdjoin://$I_HOST:$I_PORT/$JOIN_CODE.$CA_FP"
        echo ""
        I_OTHERS=$(op_other_hosts "$I_HOST")
        [ -n "$I_OTHERS" ] && { echo "  (relay also reachable at: $I_OTHERS — swap the host above for any; the join token works against all)"; echo ""; }
        echo "  (or paste the full invite URL with: wyrd relay register '<url>')"
    elif [ -n "$JOIN_CODE" ] && [ -n "$I_HOST" ] && [ -n "$I_PORT" ]; then
        echo "    wyrd relay join $I_HOST:$I_PORT $JOIN_CODE"
        I_OTHERS=$(op_other_hosts "$I_HOST")
        [ -n "$I_OTHERS" ] && echo "  (relay also reachable at: $I_OTHERS — the join token works against any)"
    else
        echo "    wyrd relay register '$INVITE_URL'"
    fi
    echo ""
    exit 0
fi

# ── Operator: mint an owner-claim token (no host arg) ───────────────────
# Mirrors `invite` but binds OWNERSHIP not membership: prints the
# `wyrd relay claim …` line a zone runs to record its DID as this relay's
# admin owner. Fills the gap when the deploy-time claim line was missed (e.g.
# the health wait timed out before printing it) without a redeploy. Same
# localhost-only /claim-owner-mint the deploy uses — opens no network surface.
if [ "$ACTION" = "claim-mint" ]; then
    REG_CID=""
    OP_MODE=""
    if [ "$MODE" != "native" ]; then
        REG_CID=$(op_docker_reg_cid || true)
        [ -n "$REG_CID" ] && OP_MODE=docker
    fi
    if [ -z "$OP_MODE" ]; then
        if [ -f "$PREFIX/conf/registration.env" ] && [ -x "$PREFIX/venv/bin/python3" ]; then
            OP_MODE=native
        fi
    fi
    [ -n "$OP_MODE" ] || die "no running relay found — deploy one first (no docker 'registration' container, no native install at $PREFIX)."

    if [ "$OP_MODE" = docker ]; then
        CLAIM_JSON=$(docker exec "$REG_CID" wget -qO- \
            --post-data="{\"ttl\":${INVITE_TTL}}" \
            --header="Content-Type: application/json" \
            http://127.0.0.1:9280/claim-owner-mint 2>/dev/null) \
            || die "owner-claim mint failed inside the registration container"
    else
        REG_CLAIM_PORT="$REG_PORT"
        if [ -f "$PREFIX/conf/registration.env" ]; then
            # shellcheck disable=SC1091
            ENV_REG_PORT=$( . "$PREFIX/conf/registration.env" >/dev/null 2>&1; printf '%s' "${REGISTRATION_PORT:-}" )
            [ -n "$ENV_REG_PORT" ] && REG_CLAIM_PORT="$ENV_REG_PORT"
        fi
        CLAIM_JSON=$(curl -sf -X POST -H "Content-Type: application/json" \
            -d "{\"ttl\":${INVITE_TTL}}" \
            "http://127.0.0.1:$REG_CLAIM_PORT/claim-owner-mint") \
            || die "owner-claim mint failed (native, port $REG_CLAIM_PORT)"
    fi

    CLAIM_TOKEN=$(printf '%s' "$CLAIM_JSON" | sed -n 's/.*"claim_token": *"\([^"]*\)".*/\1/p')
    C_HOST=$(printf '%s' "$CLAIM_JSON" | sed -n 's/.*"host": *"\([^"]*\)".*/\1/p')
    C_PORT=$(printf '%s' "$CLAIM_JSON" | sed -n 's/.*"port": *"\{0,1\}\([0-9]*\)"\{0,1\}.*/\1/p')
    [ -n "$CLAIM_TOKEN" ] || die "could not parse claim token from: $CLAIM_JSON"
    [ -n "$C_HOST" ] || C_HOST="<relay-host>"
    [ -n "$C_PORT" ] || C_PORT="${PORT:-4443}"

    if [ "$INVITE_TTL" -ge 3600 ]; then TTL_HUMAN="$((INVITE_TTL/3600))h"; else TTL_HUMAN="$((INVITE_TTL/60))min"; fi

    echo ""
    say "owner-claim token minted ($OP_MODE relay) — valid $TTL_HUMAN, single use:"
    echo ""
    echo "    wyrd relay claim $CLAIM_TOKEN --registration-url https://$C_HOST:$C_PORT"
    echo ""
    C_OTHERS=$(op_other_hosts "$C_HOST")
    [ -n "$C_OTHERS" ] && { echo "  (relay also reachable at: $C_OTHERS — swap the --registration-url host for any; the token works against all)"; echo ""; }
    echo "  Run that from your zone to record YOUR DID as this relay's admin owner."
    echo ""
    exit 0
fi

# ── Operator: relay-identity backup / restore (no host arg) ─────────────
# The identity (household CA + leaf, invite-key, registrations.json, owner /
# grants / policy) is the one irreplaceable thing on a relay box: lose it and
# every paired device must re-pin from fresh invites. `backup` archives it to a
# single .tgz; `restore` swaps a .tgz back onto a freshly-deployed box (the
# migration path: same identity, new host, no re-pairing). Both auto-detect
# docker vs native the same way list/remove do.
relay_native_units() {  # $1 = stop|start ; best-effort, systemd only
    command -v systemctl >/dev/null 2>&1 || return 1
    for u in "$UNIT_BASE-nats" "$UNIT_BASE-registration" "$UNIT_BASE-caddy"; do
        systemctl "$1" "$u" 2>/dev/null || true
    done
}

if [ "$ACTION" = "backup" ] || [ "$ACTION" = "restore" ]; then
    OP_MODE=""; REG_CID=""
    if [ "$MODE" != "native" ]; then
        REG_CID=$(op_docker_reg_cid || true)
        [ -n "$REG_CID" ] && OP_MODE=docker
        # restore can target a STOPPED stack — accept a non-running container too.
        if [ -z "$OP_MODE" ] && [ "$ACTION" = restore ] && command -v docker >/dev/null 2>&1; then
            REG_CID=$(docker ps -aq -f "name=^${AIO_NAME}$" 2>/dev/null | head -1)
            [ -n "$REG_CID" ] && OP_MODE=docker
        fi
    fi
    if [ -z "$OP_MODE" ]; then
        if [ "$ACTION" = restore ]; then
            # restore (re)creates data/ + certs/, so only the install root need exist.
            { [ -f "$PREFIX/conf/registration.env" ] || [ -d "$PREFIX" ]; } && OP_MODE=native
        else
            { [ -d "$PREFIX/data" ] || [ -d "$PREFIX/certs" ]; } && OP_MODE=native
        fi
    fi
    [ -n "$OP_MODE" ] || die "no relay install found here (no docker stack, no native $PREFIX) — deploy one before backup/restore."

    if [ "$ACTION" = backup ]; then
        TS=$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || date -u +%s)
        OUT="${BACKUP_OUT:-relay-identity-${INSTANCE:-relay}-$TS.tgz}"
        case "$OUT" in /*) : ;; *) OUT="$PWD/$OUT" ;; esac
        if [ "$OP_MODE" = docker ]; then
            # Stage data+certs at a normalized layout inside the container, then
            # stream the tar out — avoids guessing the compose volume-name prefix.
            docker exec "$REG_CID" sh -c '
                set -e; rm -rf /tmp/_relaybk; mkdir -p /tmp/_relaybk/data /tmp/_relaybk/certs
                cp -a /var/lib/wyrd-relay/. /tmp/_relaybk/data/  2>/dev/null || true
                cp -a /certs/.               /tmp/_relaybk/certs/ 2>/dev/null || true
                tar czf - -C /tmp/_relaybk data certs; rm -rf /tmp/_relaybk' \
                > "$OUT" || die "backup failed (could not read identity from the registration container)"
        else
            tar czf "$OUT" -C "$PREFIX" \
                $([ -d "$PREFIX/data" ] && echo data) \
                $([ -d "$PREFIX/certs" ] && echo certs) \
                || die "backup failed (could not read $PREFIX/{data,certs})"
        fi
        say "relay identity archived → $OUT"
        say "keep it OFFLINE — it carries the household CA + invite key."
        say "restore onto a (freshly-deployed) box with:  sudo sh relay.sh restore $OUT"
        exit 0
    fi

    # ── restore ──
    [ -f "$RESTORE_FILE" ] || die "archive not found: $RESTORE_FILE"
    case "$RESTORE_FILE" in /*) ARCH="$RESTORE_FILE" ;; *) ARCH="$PWD/$RESTORE_FILE" ;; esac
    tar tzf "$ARCH" 2>/dev/null | grep -qE '^(\./)?(data|certs)/' \
        || die "not a relay-identity archive (expected data/ and certs/ members): $ARCH"
    say "restoring relay identity from $ARCH ($OP_MODE) — the current identity here will be OVERWRITTEN."

    if [ "$OP_MODE" = docker ]; then
        VOL_DATA=$(docker inspect "$REG_CID" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/wyrd-relay"}}{{.Name}}{{end}}{{end}}' 2>/dev/null)
        VOL_CERTS=$(docker inspect "$REG_CID" --format '{{range .Mounts}}{{if eq .Destination "/certs"}}{{.Name}}{{end}}{{end}}' 2>/dev/null)
        [ -n "$VOL_DATA" ] && [ -n "$VOL_CERTS" ] || die "could not resolve the certs/data volumes from container $AIO_NAME"
        say "stopping relay container…"
        docker stop "$AIO_NAME" >/dev/null 2>&1 || true
        # Extract into the volumes via python:3.12-alpine (already present from
        # the relay deploy — no bare 'alpine' image needed).
        docker run --rm -v "$VOL_DATA":/data -v "$VOL_CERTS":/certs -v "$ARCH":/in.tgz:ro \
            python:3.12-alpine sh -c '
                set -e; tmp=$(mktemp -d); tar xzf /in.tgz -C "$tmp"
                rm -rf /data/* /certs/* 2>/dev/null || true
                [ -d "$tmp/data" ]  && cp -a "$tmp/data/."  /data/  || true
                [ -d "$tmp/certs" ] && cp -a "$tmp/certs/." /certs/ || true' \
            || die "restore failed while writing into the relay volumes"
        say "starting relay container…"
        docker start "$AIO_NAME" >/dev/null 2>&1 \
            || die "relay container failed to come back up — check 'docker logs $AIO_NAME'"
    else
        relay_native_units stop
        tmp=$(mktemp -d)
        tar xzf "$ARCH" -C "$tmp" || die "could not unpack $ARCH"
        [ -d "$tmp/data" ]  && { rm -rf "$PREFIX/data";  mkdir -p "$PREFIX/data";  cp -a "$tmp/data/."  "$PREFIX/data/"; }
        [ -d "$tmp/certs" ] && { rm -rf "$PREFIX/certs"; mkdir -p "$PREFIX/certs"; cp -a "$tmp/certs/." "$PREFIX/certs/"; }
        rm -rf "$tmp"
        relay_native_units start \
            || say "identity swapped — restart the relay: systemctl restart $UNIT_BASE-nats $UNIT_BASE-registration $UNIT_BASE-caddy (or re-run relay.sh deploy)."
    fi
    say "relay identity restored. Existing device pins stay valid (CA unchanged)."
    exit 0
fi

# Address is optional: it only seeds the dial-address default baked into join
# tokens. The leaf cert covers every interface IP (SANs below), and devices
# pin the CA fingerprint, not the hostname — so any reachable IP works and a
# per-token override (wyrd relay join <ip>:port <code>) always wins. When the
# operator gives nothing, default to the first non-loopback IPv4 so a layman
# deploy is just `sh relay.sh` (one less thing to know).
if [ -z "$HOST_ARG" ]; then
    HOST_ARG=$(hostname -I 2>/dev/null | tr ' ' '\n' \
        | grep -E '^[0-9]+\.' | grep -v '^127\.' | head -n1 || true)
    [ -n "$HOST_ARG" ] || { usage; die "no address given and no non-loopback IPv4 found — pass <domain-or-ip>[:port]"; }
    say "no address given — defaulting dial address to $HOST_ARG (override per join token, or pass one explicitly)"
fi

# Split host[:port]. (IPv4 + hostnames; bracketless IPv6 not supported here.)
HOST="${HOST_ARG%%:*}"
PORT=""
case "$HOST_ARG" in *:*) PORT="${HOST_ARG##*:}" ;; esac

# DNS name or bare IP? (Only affects which SAN list the name lands in.)
IS_IP=0
case "$HOST" in
    *[!0-9.]*) ;;                                   # has non-digit/dot → name
    *.*.*.*)   IS_IP=1 ;;                           # dotted quad
esac

# ONE trust model: household CA on the port of
# your choice (default 4443). Devices pin the relay from invite material —
# the join token, the wyrdphone:// URL — so no public CA is ever involved
# and the relay never competes with a web server for 80/443.
[ -z "$PORT" ] && PORT=4443
say "household CA on :$PORT (devices pin it from their invites)"

# b — validate an --owner DID shape early (a typo here
# would silently leave the relay unclaimed).
if [ -n "$OWNER_DID" ]; then
    # Accept BOTH did:key:z… and did:wyrd:z… — the multibase key body (z…) is the
    # same; only the method label differs. `wyrd whoami` emits did:wyrd:z…, and the
    # old check rejected exactly that while telling the operator to "find yours with
    # wyrd whoami" — a validation that refused its own advice (2026-07-16).
    case "$OWNER_DID" in
        did:key:z*|did:wyrd:z*) say "owner DID: $OWNER_DID (recorded at deploy — no claim step)" ;;
        *) die "--owner expects a did:key:z… or did:wyrd:z… value (got '$OWNER_DID'); find yours with: wyrd whoami" ;;
    esac
fi

# validate --mode shape early.
if [ -n "$RELAY_MODE" ]; then
    case "$RELAY_MODE" in
        invite-only|open|commons) say "registration mode: $RELAY_MODE" ;;
        *) die "--mode expects invite-only|open|commons (got '$RELAY_MODE')" ;;
    esac
fi

# ── Mode select: docker when available, native otherwise ────────────────
if [ -z "$MODE" ]; then
    if command -v docker >/dev/null 2>&1; then
        MODE=docker
    else
        MODE=native
        say "docker not found — using the native (no-docker) install"
    fi
fi
if [ "$MODE" = "docker" ]; then
    command -v docker >/dev/null 2>&1 || die "docker not found — install it (curl -fsSL https://get.docker.com | sh) or re-run with --native."
fi
say "install mode: $MODE"

# ── Preflight: step aside if our default ports are already spoken for ───────
# The relay's zone-leg defaults to 4222. A wyrdsekai ZONE on the same host
# wants that port too, and whoever loses does not lose cleanly: the zone's
# nats-server exits "address in use" and restarts forever, while the zone
# server connects to OUR nats with its own credentials and logs
# "Authorization Violation" on a retry loop — an auth error in a service with
# no auth problem.
#
# Relay-beside-zone is a legitimate topology, so MOVE rather than refuse. The
# offset machinery already exists and is complete end to end: the registration
# response advertises RELAY_NATS_PORT, so a joining zone dials wherever we
# actually landed instead of assuming 4222, and the firewall block below opens
# $NATS_PORT rather than a constant.
port_taken() {
    if command -v ss >/dev/null 2>&1; then
        ss -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
    elif command -v netstat >/dev/null 2>&1; then
        netstat -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
    else
        return 1   # cannot tell — do not block the install on a missing tool
    fi
}
ports_free_at() {
    _o=$1
    for _q in $((4222 + _o)) $((9222 + _o)) $((9280 + _o)) $((8222 + _o)); do
        port_taken "$_q" && return 1
    done
    return 0
}
# REDEPLOY-OVER-SELF (2026-07-30, live on wyrdsekai.org): if THIS instance's
# own units are already running, they hold the very ports we are about to
# probe — the clash logic then "moves" the relay by +100 while the running
# nats-server keeps the old port, and every NATS-touching op (deregister,
# register reload) dials a port nobody listens on. A redeploy restarts the
# units anyway, so stop OUR OWN units before probing; ports held by anything
# else (a co-hosted zone, another instance) still shift the block as designed.
if command -v systemctl >/dev/null 2>&1 \
   && systemctl is-active --quiet "$UNIT_BASE-nats" 2>/dev/null; then
    say "redeploy over a running relay — stopping this instance's own units first"
    relay_native_units stop
    sleep 1
fi

_clash=""
for _p in "$NATS_PORT" "$WS_PORT" "$REG_PORT" "$MON_PORT"; do
    port_taken "$_p" && _clash="$_clash $_p"
done
if [ -n "$_clash" ]; then
    if [ "$MODE" = "docker" ]; then
        # Docker publishes -p 4222:4222 and the offset knob is native-only, so
        # there is nothing to move here — say so plainly instead of failing
        # later with a container that cannot bind.
        warn "port(s) already in use:$_clash"
        warn "  Docker mode publishes fixed ports and cannot shift them."
        warn "  Re-run with --native to have the relay pick a free block, or"
        warn "  stop whatever holds those ports."
        die "refusing to bind over a live service."
    elif [ "$PORT_OFFSET" != 0 ]; then
        # The operator chose this offset explicitly. Do not second-guess it.
        warn "port(s) already in use:$_clash"
        die "WYRD_RELAY_PORT_OFFSET=$PORT_OFFSET still lands on a busy port — pick another."
    else
        _try=100
        while [ "$_try" -le 900 ]; do
            ports_free_at "$_try" && break
            _try=$((_try + 100))
        done
        if [ "$_try" -gt 900 ]; then
            warn "port(s) already in use:$_clash"
            die "no free port block found between offsets 100 and 900 — set WYRD_RELAY_PORT_OFFSET manually."
        fi
        PORT_OFFSET=$_try
        NATS_PORT=$((4222 + PORT_OFFSET))
        WS_PORT=$((9222 + PORT_OFFSET))
        REG_PORT=$((9280 + PORT_OFFSET))
        MON_PORT=$((8222 + PORT_OFFSET))
        say "ports$_clash already in use (a wyrdsekai zone on this host uses 4222)"
        say "moved this relay's backend ports by +$PORT_OFFSET — zone-leg is now $NATS_PORT"
        say "joining zones learn this automatically; nothing for you to configure"
    fi
fi

# Co-hosting (instance suffix / port offset) is a native-mode feature — a 2nd
# docker relay would need its own container name + ports, which we don't wire.
if [ "$MODE" = "docker" ] && { [ -n "$INSTANCE" ] || [ "$PORT_OFFSET" != 0 ]; }; then
    die "WYRD_RELAY_INSTANCE / WYRD_RELAY_PORT_OFFSET (co-hosting a 2nd relay) are native-mode only — re-run with --native."
fi

# ── Locate or fetch the relay bundle ─────────────────────────────────────
if ! locate_bundle; then
    say "no local bundle found — fetching from $REPO_URL"
    command -v git >/dev/null 2>&1 || die "git not found and no local bundle — install git or pass --bundle-dir."
    git clone --depth 1 "$REPO_URL" /opt/wyrdsekai-relay-src \
        || die "clone failed — pass --bundle-dir pointing at a deploy/relay checkout."
    BUNDLE_DIR=/opt/wyrdsekai-relay-src/deploy/relay
fi
BUNDLE_DIR=$(CDPATH='' cd -- "$BUNDLE_DIR" && pwd)
[ -f "$BUNDLE_DIR/Dockerfile" ] || die "no Dockerfile in $BUNDLE_DIR (single-container relay needs deploy/relay/Dockerfile)"
say "bundle: $BUNDLE_DIR"

# ── --reset: wipe identity ────────────────────────────────────────────────
if [ "$RESET" -eq 1 ]; then
    warn "--reset: wiping relay identity (CA, leaf, registrations, invite key)."
    if [ "$MODE" = "docker" ]; then
        docker rm -f "$AIO_NAME" >/dev/null 2>&1 || true
        docker volume rm "$AIO_VOL_CERTS" "$AIO_VOL_DATA" >/dev/null 2>&1 || true
        rm -f "$BUNDLE_DIR/.env"
    else
        if command -v systemctl >/dev/null 2>&1 && native_stop_systemd; then
            systemctl daemon-reload 2>/dev/null || true
        fi
        native_stop_nohup 2>/dev/null || true
        rm -rf "$PREFIX/certs" "$PREFIX/data" \
               "$PREFIX/conf/relay.conf" "$PREFIX/conf/registration.env"
    fi
fi

# ── Credentials (idempotent — existing passwords are preserved) ──────────
# docker keeps them in $BUNDLE_DIR/.env (compose interpolation); native
# keeps them in $PREFIX/conf/registration.env (systemd EnvironmentFile).
SIDECAR_PW=""
PHONE_PW=""
if [ "$MODE" = "docker" ]; then
    ENV_FILE="$BUNDLE_DIR/.env"
    [ -f "$ENV_FILE" ] && SIDECAR_PW=$(sed -n 's/^RELAY_SIDECAR_NATS_PASSWORD=//p' "$ENV_FILE" | head -1)
    [ -f "$ENV_FILE" ] && PHONE_PW=$(sed -n 's/^RELAY_PHONE_NATS_PASSWORD=//p' "$ENV_FILE" | head -1)
else
    REG_ENV="$PREFIX/conf/registration.env"
    [ -f "$REG_ENV" ] && SIDECAR_PW=$(sed -n 's/^NATS_PASSWORD="\(.*\)"$/\1/p' "$REG_ENV" | head -1)
    [ -f "$REG_ENV" ] && PHONE_PW=$(sed -n 's/^NATS_PHONE_PASSWORD="\(.*\)"$/\1/p' "$REG_ENV" | head -1)
fi
# Random per-deploy internal credentials. The
# committed defaults are public knowledge. registration.py reads these back
# from its own env (NATS_PASSWORD / NATS_PHONE_PASSWORD) for BOTH conf
# regen and invite minting, so randomizing here is enough. Idempotent:
# re-running must NOT rotate them, or every previously issued phone invite
# would silently stop authenticating.
[ -z "$SIDECAR_PW" ] && SIDECAR_PW=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
[ -z "$PHONE_PW" ] && PHONE_PW=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')

# SANs for the household-CA leaf: the address devices dial + loopback +
# whatever the host knows about itself.
DETECTED_IPS=$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | paste -sd, - || true)
HOST_NAMES="localhost,$(hostname -s 2>/dev/null || echo relay)"
HOST_IPS="127.0.0.1${DETECTED_IPS:+,$DETECTED_IPS}"
if [ "$IS_IP" -eq 1 ]; then
    case ",$HOST_IPS," in *",$HOST,"*) ;; *) HOST_IPS="$HOST,$HOST_IPS" ;; esac
else
    HOST_NAMES="$HOST,$HOST_NAMES"
fi

# Advertised dial addresses for minted invites (RELAY_PUBLIC_HOSTS). Distinct
# from the cert SANs above: SANs can be generous (loopback, docker0) because
# they only widen what the cert is *valid* for; the advertised list is what a
# zone/phone actually *tries*, so it must be reachable addresses only.
#   - host named explicitly  → advertise exactly that (the operator's choice).
#   - no host (auto)         → advertise EVERY address the relay sees, so a
#                              multi-NIC box just works on any of its LANs with
#                              no IP to pick. The phone's relay list is an
#                              ordered failover — it tries each, keeps the live
#                              one. We drop only docker0's own IP (clients never
#                              reach the relay via the host's docker gateway);
#                              we match that IP exactly, never a 172.x range,
#                              so a real 172.x LAN address is never dropped.
if [ "$HOST_EXPLICIT" -eq 1 ]; then
    HOST_LIST="$HOST"
else
    _docker_ip=$(ip -4 addr show docker0 2>/dev/null \
        | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -n1)
    HOST_LIST=""
    for _ip in $(printf '%s' "$DETECTED_IPS" | tr ',' ' '); do
        [ -n "$_docker_ip" ] && [ "$_ip" = "$_docker_ip" ] && continue
        HOST_LIST="${HOST_LIST:+$HOST_LIST,}$_ip"
    done
    [ -n "$HOST_LIST" ] || HOST_LIST="$HOST"
    say "no host named — invites will advertise all relay addresses: $HOST_LIST"
fi

# The randomized passwords must be IN relay.conf for first boot (regen
# keeps them in sync afterwards — registration.py preserves non-hh users
# and re-injects from NATS_PASSWORD / NATS_PHONE_PASSWORD).
inject_conf_passwords() {
    conf="$1"
    if grep -q 'user: "relay_sidecar"' "$conf"; then
        sed -i.bak "s|\(user: \"relay_sidecar\", password: \"\)[^\"]*|\1$SIDECAR_PW|" "$conf"
        rm -f "$conf.bak"
    fi
    if grep -q 'user: "relay_phone"' "$conf"; then
        sed -i.bak "s|\(user: \"relay_phone\", password: \"\)[^\"]*|\1$PHONE_PW|" "$conf"
        rm -f "$conf.bak"
    fi
}

# ── Deploy: docker ────────────────────────────────────────────────────────
deploy_docker() {
    {
        echo "# Written by relay.sh $(date -u +%Y-%m-%dT%H:%M:%SZ) — re-run relay.sh to update."
        echo "RELAY_PORT=$PORT"
        echo "RELAY_PUBLIC_HOST=$HOST"
        echo "RELAY_PUBLIC_HOSTS=$HOST_LIST"
        echo "RELAY_PUBLIC=$([ "$PUBLIC" = 1 ] && echo true || echo false)"
        echo "RELAY_HOST_NAMES=$HOST_NAMES"
        echo "RELAY_HOST_IPS=$HOST_IPS"
        # Public zone-leg port for joining nodes (offset-aware) — the registration
        # response advertises it so `wyrd relay register` uses the real port.
        echo "RELAY_NATS_PORT=$NATS_PORT"
        echo "RELAY_SIDECAR_NATS_PASSWORD=$SIDECAR_PW"
        echo "RELAY_PHONE_NATS_PASSWORD=$PHONE_PW"
        [ -n "$OWNER_DID" ] && echo "RELAY_OWNER_DID=$OWNER_DID"
        [ -n "$RELAY_MODE" ] && echo "RELAY_MODE=$RELAY_MODE"
        if [ "$SSH_TUNNEL" = 1 ]; then
            echo "WYRD_SSH_TUNNEL_ENABLED=true"
            echo "WYRD_SSH_TUNNEL_MODE=$SSH_TUNNEL_MODE"
            echo "WYRD_SSH_TUNNEL_TOPOLOGY=$SSH_TUNNEL_TOPOLOGY"
            echo "WYRD_SSH_TUNNEL_PORT=$SSH_TUNNEL_CTRL_PORT"
            echo "WYRD_SSH_TUNNEL_PORT_BASE=$SSH_TUNNEL_PORT_BASE"
            echo "WYRD_SSH_TUNNEL_PORT_COUNT=$SSH_TUNNEL_PORT_COUNT"
        fi
    } > "$BUNDLE_DIR/.env"
    say "wrote $BUNDLE_DIR/.env"

    # Password injection happens INSIDE the container on first boot (the
    # aio-entrypoint seeds the conf into the data volume and writes the
    # randomized sidecar/phone passwords from the env we pass below), so we do
    # NOT mutate the bundle's source relay.conf here.

    say "building relay image $AIO_IMAGE…"
    docker build -t "$AIO_IMAGE" "$BUNDLE_DIR" >&2 || die "image build failed — check the output above"

    # Tunnel sshd port publishing. Always publish the
    # control port (2222). In `port` topology ALSO publish the per-zone public
    # range so a bare `ssh -p <port>` reaches the zone; in `jump` topology the
    # zone ports stay loopback inside the container (ProxyJump fans out over
    # 2222), so we publish NOTHING extra — the one-port scaling win.
    SSH_PORT_ARGS=""
    if [ "$SSH_TUNNEL" = 1 ]; then
        SSH_PORT_ARGS="-p ${SSH_TUNNEL_CTRL_PORT}:${SSH_TUNNEL_CTRL_PORT}"
        if [ "$SSH_TUNNEL_TOPOLOGY" = port ]; then
            _range_end=$((SSH_TUNNEL_PORT_BASE + SSH_TUNNEL_PORT_COUNT - 1))
            SSH_PORT_ARGS="$SSH_PORT_ARGS -p ${SSH_TUNNEL_PORT_BASE}-${_range_end}:${SSH_TUNNEL_PORT_BASE}-${_range_end}"
        fi
        say "ssh tunnel: topology=$SSH_TUNNEL_TOPOLOGY publishing $SSH_PORT_ARGS"
    fi

    say "starting relay (single container: $AIO_NAME)…"
    docker rm -f "$AIO_NAME" >/dev/null 2>&1 || true
    # shellcheck disable=SC2086  # SSH_PORT_ARGS is intentionally word-split
    docker run -d --name "$AIO_NAME" --restart unless-stopped \
        --env-file "$BUNDLE_DIR/.env" \
        -p "${PORT}:${PORT}" \
        -p "4222:4222" \
        $SSH_PORT_ARGS \
        -v "$AIO_VOL_CERTS":/certs \
        -v "$AIO_VOL_DATA":/var/lib/wyrd-relay \
        "$AIO_IMAGE" >/dev/null \
        || die "container failed to start — check 'docker logs $AIO_NAME'"
}

# ── Deploy: native (no docker) ───────────────────────────────────────────
deploy_native() {
    for c in curl tar python3 openssl bash; do
        command -v "$c" >/dev/null 2>&1 \
            || die "native mode needs '$c' — e.g.: apt-get install -y curl tar python3 python3-venv openssl bash"
    done

    # python3-venv (ensurepip) is the classic fresh-Debian/Ubuntu gap: python3
    # is present but `python3 -m venv` dies because ensurepip ships as a
    # SEPARATE package. Detect it up front — BEFORE the ~30MB of binary
    # downloads — and auto-install when we can, so we fail fast with a clear
    # fix instead of dying mid-deploy.
    if ! python3 -c 'import ensurepip, venv' >/dev/null 2>&1; then
        pyver=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)
        if [ "$(id -u)" = 0 ] && command -v apt-get >/dev/null 2>&1; then
            say "python3-venv (ensurepip) missing — installing python${pyver:-3}-venv via apt…"
            apt-get update -qq >/dev/null 2>&1 || true
            apt-get install -y "python${pyver}-venv" >/dev/null 2>&1 \
                || apt-get install -y python3-venv >/dev/null 2>&1 \
                || die "auto-install of python3-venv failed — run: apt-get install -y python${pyver:-3}-venv"
        else
            die "native mode needs python3-venv (ensurepip), which python3 lacks here.
  Install it, then re-run this script (it's idempotent):
      sudo apt-get install -y python${pyver:-3}-venv"
        fi
        python3 -c 'import ensurepip, venv' >/dev/null 2>&1 \
            || die "python3-venv still unavailable after install — check: python3 -m venv --help"
    fi

    case "$(uname -m)" in
        x86_64|amd64)  ARCH=amd64 ;;
        aarch64|arm64) ARCH=arm64 ;;
        *) die "unsupported arch '$(uname -m)' for the native install — use --docker" ;;
    esac

    mkdir -p "$PREFIX/bin" "$PREFIX/conf" "$PREFIX/data" "$PREFIX/certs" \
        || die "cannot create $PREFIX (set WYRD_RELAY_PREFIX or run as root)"

    # Static binaries — cached across re-runs.
    if [ ! -x "$PREFIX/bin/nats-server" ]; then
        say "downloading nats-server v$NATS_VERSION ($ARCH)…"
        TMP=$(mktemp -d)
        curl -fsSL "https://github.com/nats-io/nats-server/releases/download/v$NATS_VERSION/nats-server-v$NATS_VERSION-linux-$ARCH.tar.gz" \
            | tar -xz -C "$TMP" || { rm -rf "$TMP"; die "nats-server download failed"; }
        mv "$TMP/nats-server-v$NATS_VERSION-linux-$ARCH/nats-server" "$PREFIX/bin/nats-server"
        rm -rf "$TMP"
    fi
    if [ ! -x "$PREFIX/bin/caddy" ]; then
        say "downloading caddy v$CADDY_VERSION ($ARCH)…"
        TMP=$(mktemp -d)
        curl -fsSL "https://github.com/caddyserver/caddy/releases/download/v$CADDY_VERSION/caddy_${CADDY_VERSION}_linux_$ARCH.tar.gz" \
            | tar -xz -C "$TMP" caddy || { rm -rf "$TMP"; die "caddy download failed"; }
        mv "$TMP/caddy" "$PREFIX/bin/caddy"
        rm -rf "$TMP"
    fi

    # Registration sidecar venv. Recreate if python3 OR pip is missing — a venv
    # whose first `python3 -m venv` died at the ensurepip step (missing
    # python3-venv) leaves a bin/python3 symlink but no pip, and the old
    # python3-only guard would then skip the fix forever.
    if [ ! -x "$PREFIX/venv/bin/python3" ] \
       || ! "$PREFIX/venv/bin/python3" -m pip --version >/dev/null 2>&1; then
        say "creating python venv…"
        rm -rf "$PREFIX/venv"
        python3 -m venv "$PREFIX/venv" \
            || die "python3 -m venv failed — install your distro's python3-venv package"
    fi
    # Ensure deps on EVERY run (idempotent + fast when satisfied), so a re-run
    # heals an older venv missing a dependency. Call pip via `python3 -m pip`
    # (the `pip` console-script isn't always generated). pynacl is REQUIRED for
    # the owner-claim Ed25519 verify (nacl.signing in registration.py) — it is
    # NOT a transitive dep of nkeys, so it must be named explicitly.
    "$PREFIX/venv/bin/python3" -m pip install --quiet nkeys nats-py pynacl \
        || die "pip install nkeys nats-py pynacl failed"

    # Code + config staging. registration.py and the Caddyfile are refreshed
    # every run (that IS the upgrade path); relay.conf is copied ONCE because
    # registration.py appends household users to it.
    cp "$BUNDLE_DIR/registration.py" "$PREFIX/registration.py"
    sed -e "s|registration:9280|127.0.0.1:$REG_PORT|g" \
        -e "s|nats:9222|127.0.0.1:$WS_PORT|g" \
        -e "s|/certs/|$PREFIX/certs/|g" \
        "$BUNDLE_DIR/Caddyfile" > "$PREFIX/conf/Caddyfile"
    if [ ! -f "$PREFIX/conf/relay.conf" ]; then
        # WS listener goes loopback-only: caddy owns all public TLS, exactly
        # like the docker-internal :9222. The zone-leg (4222) + ws (9222) +
        # monitor (8222) ports shift by PORT_OFFSET so a co-hosted 2nd
        # instance doesn't clash. NATIVE mode keeps the monitor on loopback
        # (127.0.0.1) — reaper and nats share the host, so loopback is both
        # reachable and never public (the docker split rebinds it to 0.0.0.0
        # in nats/entrypoint.sh instead). Survives conf regen — registration
        # rewrites only the auth block.
        sed -e "s|listen: 0.0.0.0:4222|listen: 0.0.0.0:$NATS_PORT|" \
            -e "s|listen: \"0.0.0.0:9222\"|listen: \"127.0.0.1:$WS_PORT\"|" \
            -e "s|http: \"127.0.0.1:8222\"|http: \"127.0.0.1:$MON_PORT\"|" \
            "$BUNDLE_DIR/relay.conf" > "$PREFIX/conf/relay.conf"
    fi
    inject_conf_passwords "$PREFIX/conf/relay.conf"

    # Household CA + leaf (same certinit script the docker image runs).
    say "generating/refreshing household CA + leaf…"
    CERT_DIR="$PREFIX/certs" RELAY_HOST_NAMES="$HOST_NAMES" RELAY_HOST_IPS="$HOST_IPS" \
        bash "$BUNDLE_DIR/certinit/gen-cert.sh" >/dev/null

    # Sidecar environment — mirrors the compose `registration` service env.
    umask 077
    {
        echo "# Written by relay.sh $(date -u +%Y-%m-%dT%H:%M:%SZ) — re-run relay.sh to update."
        echo "NATS_CONF=\"$PREFIX/conf/relay.conf\""
        echo "NATS_SIGNAL_CMD=\"$PREFIX/bin/nats-server --signal reload=$PREFIX/data/nats.pid\""
        echo "DATA_DIR=\"$PREFIX/data\""
        echo "REGISTRATION_PORT=\"$REG_PORT\""
        echo "REGISTRATION_BIND=\"127.0.0.1\""
        echo "CERT_DIR=\"$PREFIX/certs\""
        echo "RELAY_PUBLIC_HOST=\"$HOST\""
        echo "RELAY_PUBLIC_HOSTS=\"$HOST_LIST\""
        echo "RELAY_PORT=\"$PORT\""
        echo "RELAY_PUBLIC_PORT=\"$PORT\""
        echo "RELAY_PUBLIC=\"$([ "$PUBLIC" = 1 ] && echo true || echo false)\""
        echo "NATS_URL=\"nats://127.0.0.1:$NATS_PORT\""
        # The PUBLIC zone-leg port a joining node must dial (offset-aware). The
        # registration response advertises this so `wyrd relay register` stops
        # hardcoding 4222 and a co-hosted (offset) relay is reachable.
        echo "RELAY_NATS_PORT=\"$NATS_PORT\""
        # Loopback monitor for the liveness reaper (offset for co-hosting).
        echo "NATS_MONITOR_URL=\"http://127.0.0.1:$MON_PORT\""
        echo "NATS_USER=\"relay_sidecar\""
        echo "NATS_PASSWORD=\"$SIDECAR_PW\""
        echo "NATS_PHONE_PASSWORD=\"$PHONE_PW\""
        [ -n "$OWNER_DID" ] && echo "RELAY_OWNER_DID=\"$OWNER_DID\""
        [ -n "$RELAY_MODE" ] && echo "RELAY_MODE=\"$RELAY_MODE\""
        if [ "$SSH_TUNNEL" = 1 ]; then
            # registration.py derives the ssh/ paths (authorized_keys, jump &
            # host pubkeys) from DATA_DIR, already set above to $PREFIX/data — so
            # only the policy/topology knobs need passing here.
            echo "WYRD_SSH_TUNNEL_ENABLED=\"true\""
            echo "WYRD_SSH_TUNNEL_MODE=\"$SSH_TUNNEL_MODE\""
            echo "WYRD_SSH_TUNNEL_TOPOLOGY=\"$SSH_TUNNEL_TOPOLOGY\""
            echo "WYRD_SSH_TUNNEL_PORT=\"$SSH_TUNNEL_CTRL_PORT\""
            echo "WYRD_SSH_TUNNEL_PORT_BASE=\"$SSH_TUNNEL_PORT_BASE\""
            echo "WYRD_SSH_TUNNEL_PORT_COUNT=\"$SSH_TUNNEL_PORT_COUNT\""
        fi
        echo "PYTHONUNBUFFERED=\"1\""
    } > "$PREFIX/conf/registration.env"
    umask 022
    say "wrote $PREFIX/conf/registration.env"

    [ "$SSH_TUNNEL" = 1 ] && native_provision_ssh_tunnel

    if [ "$(id -u)" = "0" ] && command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
        native_start_systemd
    else
        warn "not root (or no systemd) — starting via nohup; the relay will NOT survive a reboot. Re-run as root for systemd units."
        native_start_nohup
    fi
}

# Provision the OPTIONAL forwarding-only tunnel sshd for a NATIVE install
# Installs openssh-server, creates the shell-less
# wyrd-tunnel account, generates a PERSISTENT host key, and renders the hardened
# config with native absolute paths + the topology-appropriate AllowTcpForwarding.
native_provision_ssh_tunnel() {
    SSH_DIR="$PREFIX/data/ssh"
    SSH_CONF="$PREFIX/conf/tunnel-sshd_config"
    SSH_HOSTKEY="$SSH_DIR/tunnel_host_ed25519_key"
    SSH_AUTHKEYS="$SSH_DIR/authorized_keys"

    # sshd binary (openssh-server). Auto-install best-effort.
    if ! [ -x /usr/sbin/sshd ] && ! command -v sshd >/dev/null 2>&1; then
        say "installing openssh-server for the tunnel sshd…"
        if command -v apt-get >/dev/null 2>&1; then
            apt-get install -y openssh-server >/dev/null 2>&1 || true
        elif command -v apk >/dev/null 2>&1; then
            apk add --no-cache openssh >/dev/null 2>&1 || true
        elif command -v dnf >/dev/null 2>&1; then
            dnf install -y openssh-server >/dev/null 2>&1 || true
        fi
    fi
    SSHD_BIN=/usr/sbin/sshd
    [ -x "$SSHD_BIN" ] || SSHD_BIN=$(command -v sshd 2>/dev/null || echo /usr/sbin/sshd)
    [ -x "$SSHD_BIN" ] || die "openssh-server (sshd) not found — install it and re-run, or omit --ssh-tunnel."

    # Shell-less tunnel account; per-zone isolation is per-KEY. It MUST have a
    # real home dir — sshd `StrictModes yes` validates the home and silently
    # refuses the key if it's missing (so create one: -m, not -M).
    if ! id wyrd-tunnel >/dev/null 2>&1; then
        if command -v useradd >/dev/null 2>&1; then
            useradd -r -m -d /var/lib/wyrd-tunnel-home -s /usr/sbin/nologin wyrd-tunnel 2>/dev/null || true
        elif command -v adduser >/dev/null 2>&1; then
            adduser -D -h /var/lib/wyrd-tunnel-home -s /usr/sbin/nologin wyrd-tunnel 2>/dev/null || true
        fi
        # Both useradd -r and adduser -D leave the shadow password LOCKED ("!"),
        # which sshd rejects even for pubkey auth. Set it to "*" (valid account,
        # no password login possible) so the key-only tunnel can authenticate.
        if command -v usermod >/dev/null 2>&1; then
            usermod -p '*' wyrd-tunnel 2>/dev/null || true
        else
            sed -i 's/^wyrd-tunnel:!/wyrd-tunnel:*/' /etc/shadow 2>/dev/null || true
        fi
    fi

    mkdir -p "$SSH_DIR"; chmod 0700 "$SSH_DIR"
    # CRITICAL: the dir must be OWNED by the tunnel account. sshd drops to that
    # account's uid (temporarily_use_uid) to read authorized_keys; a root-owned
    # 0700 dir is then un-traversable and sshd silently skips the keyfile (no
    # "trying public key file" log → all keys rejected, no StrictModes warning).
    chown wyrd-tunnel:wyrd-tunnel "$SSH_DIR" 2>/dev/null || true
    if [ ! -f "$SSH_HOSTKEY" ]; then
        ssh-keygen -t ed25519 -N "" -C "wyrd-relay-tunnel" -f "$SSH_HOSTKEY" >/dev/null
        say "generated tunnel sshd host key ($SSH_HOSTKEY)"
    fi
    [ -f "$SSH_AUTHKEYS" ] || { : > "$SSH_AUTHKEYS"; }
    # authorized_keys 0644 (world-readable): it is written by root (this script /
    # the sidecar) but read by sshd as the unprivileged tunnel uid; pubkeys aren't
    # secret. Host PRIVATE key stays 0600 (the dir being tunnel-owned doesn't leak
    # it — only its own 0600 mode gates the contents).
    chmod 0644 "$SSH_AUTHKEYS"
    chmod 0600 "$SSH_HOSTKEY"

    # jump topology: shared forward-only ProxyJump key (registration.py reads the
    # .pub to emit the jump-principal line; the enable response ships the private
    # half). Persist across redeploys so installed ~/.ssh/config stanzas keep working.
    SSH_JUMPKEY="$SSH_DIR/jump_ed25519_key"
    if [ "$SSH_TUNNEL_TOPOLOGY" = jump ] && [ ! -f "$SSH_JUMPKEY" ]; then
        ssh-keygen -t ed25519 -N "" -C "wyrd-relay-jump" -f "$SSH_JUMPKEY" >/dev/null
        chmod 0600 "$SSH_JUMPKEY"
        say "generated ProxyJump principal key ($SSH_JUMPKEY)"
    fi

    # Render config: absolute native paths + control port + topology forwarding.
    sed -e "s|^Port .*|Port $SSH_TUNNEL_CTRL_PORT|" \
        -e "s|^HostKey .*|HostKey $SSH_HOSTKEY|" \
        -e "s|^AuthorizedKeysFile .*|AuthorizedKeysFile $SSH_AUTHKEYS|" \
        "$BUNDLE_DIR/tunnel-sshd_config" > "$SSH_CONF"
    if [ "$SSH_TUNNEL_TOPOLOGY" = jump ]; then
        sed -i "s|^AllowTcpForwarding .*|AllowTcpForwarding yes|" "$SSH_CONF"
    fi
    chmod 0600 "$SSH_CONF"
    say "rendered tunnel sshd config ($SSH_CONF, topology=$SSH_TUNNEL_TOPOLOGY, port=$SSH_TUNNEL_CTRL_PORT)"
}

native_start_systemd() {
    say "installing systemd units ($UNIT_BASE-*)…"
    cat > /etc/systemd/system/$UNIT_BASE-nats.service <<EOF
[Unit]
Description=Wyrdsekai relay${INSTANCE:+ [$INSTANCE]} — NATS server
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$PREFIX/bin/nats-server -c $PREFIX/conf/relay.conf -P $PREFIX/data/nats.pid
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
    cat > /etc/systemd/system/$UNIT_BASE-registration.service <<EOF
[Unit]
Description=Wyrdsekai relay${INSTANCE:+ [$INSTANCE]} — registration sidecar
After=$UNIT_BASE-nats.service
Wants=$UNIT_BASE-nats.service

[Service]
EnvironmentFile=$PREFIX/conf/registration.env
ExecStart=$PREFIX/venv/bin/python3 $PREFIX/registration.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
    cat > /etc/systemd/system/$UNIT_BASE-caddy.service <<EOF
[Unit]
Description=Wyrdsekai relay${INSTANCE:+ [$INSTANCE]} — caddy TLS front
After=$UNIT_BASE-registration.service

[Service]
Environment=RELAY_PORT=$PORT
Environment=XDG_DATA_HOME=$PREFIX/caddy
Environment=XDG_CONFIG_HOME=$PREFIX/caddy
ExecStart=$PREFIX/bin/caddy run --config $PREFIX/conf/Caddyfile --adapter caddyfile
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
    SSH_UNITS=""
    if [ "$SSH_TUNNEL" = 1 ]; then
        cat > /etc/systemd/system/$UNIT_BASE-ssh-tunnel.service <<EOF
[Unit]
Description=Wyrdsekai relay${INSTANCE:+ [$INSTANCE]} — tunnel sshd (forwarding only, no shell)
After=$UNIT_BASE-registration.service

[Service]
# sshd needs its privilege-separation dir to exist (Debian default /run/sshd).
ExecStartPre=/bin/mkdir -p /run/sshd
ExecStart=$SSHD_BIN -D -e -f $PREFIX/conf/tunnel-sshd_config
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
        SSH_UNITS="$UNIT_BASE-ssh-tunnel"
    fi
    systemctl daemon-reload
    for unit in "$UNIT_BASE-nats" "$UNIT_BASE-registration" "$UNIT_BASE-caddy" $SSH_UNITS; do
        systemctl enable "$unit" >/dev/null 2>&1
        systemctl restart "$unit"
    done
    say "systemd units running ($UNIT_BASE-nats, $UNIT_BASE-registration, $UNIT_BASE-caddy${SSH_UNITS:+, $SSH_UNITS})"
}

native_start_nohup() {
    native_stop_nohup
    nohup "$PREFIX/bin/nats-server" -c "$PREFIX/conf/relay.conf" -P "$PREFIX/data/nats.pid" \
        > "$PREFIX/data/nats.log" 2>&1 &
    sleep 1
    (
        set -a
        # shellcheck disable=SC1091
        . "$PREFIX/conf/registration.env"
        set +a
        nohup "$PREFIX/venv/bin/python3" "$PREFIX/registration.py" \
            > "$PREFIX/data/registration.log" 2>&1 &
        echo $! > "$PREFIX/data/registration.pid"
    )
    RELAY_PORT="$PORT" XDG_DATA_HOME="$PREFIX/caddy" XDG_CONFIG_HOME="$PREFIX/caddy" \
        nohup "$PREFIX/bin/caddy" run --config "$PREFIX/conf/Caddyfile" --adapter caddyfile \
        > "$PREFIX/data/caddy.log" 2>&1 &
    echo $! > "$PREFIX/data/caddy.pid"
    if [ "$SSH_TUNNEL" = 1 ] && [ -f "$PREFIX/conf/tunnel-sshd_config" ]; then
        mkdir -p /run/sshd 2>/dev/null || true
        nohup "$SSHD_BIN" -D -e -f "$PREFIX/conf/tunnel-sshd_config" \
            > "$PREFIX/data/ssh-tunnel.log" 2>&1 &
        echo $! > "$PREFIX/data/ssh-tunnel.pid"
    fi
    say "started via nohup (logs: $PREFIX/data/*.log)"
}

if [ "$MODE" = "docker" ]; then
    deploy_docker
else
    deploy_native
fi

# ── Health ────────────────────────────────────────────────────────────────
say "waiting for /health on :$PORT…"
i=0
until curl -skf "https://localhost:$PORT/health" >/dev/null 2>&1; do
    i=$((i+1))
    if [ "$i" -ge 60 ]; then
        if [ "$MODE" = "docker" ]; then
            die "relay did not become healthy in 120s — check: docker logs $AIO_NAME"
        else
            die "relay did not become healthy in 120s — check $PREFIX/data/*.log (nohup) or journalctl -u 'wyrd-relay-*'"
        fi
    fi
    sleep 2
done
say "relay healthy."

# ── Firewall: open the ports we just published (host-level ufw) ──────────
# The deploy KNOWS exactly what it published, so open it HERE rather than
# making the operator chase port numbers after every `wyrd relay ssh-enable`.
# This is host-level ufw only — a CLOUD provider firewall (if any) fronts the
# box and we can't reach it from here, so we always print the list to mirror.
FW_PORTS="$PORT/tcp $NATS_PORT/tcp"
if [ "$SSH_TUNNEL" = 1 ]; then
    FW_PORTS="$FW_PORTS $SSH_TUNNEL_CTRL_PORT/tcp"
    if [ "$SSH_TUNNEL_TOPOLOGY" = port ]; then
        _fw_range_end=$((SSH_TUNNEL_PORT_BASE + SSH_TUNNEL_PORT_COUNT - 1))
        FW_PORTS="$FW_PORTS ${SSH_TUNNEL_PORT_BASE}:${_fw_range_end}/tcp"
    fi
fi
FW_OPENED=0
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi "Status: active"; then
    for _p in $FW_PORTS; do
        if ufw allow "$_p" >/dev/null 2>&1; then
            say "firewall: opened $_p"
            FW_OPENED=1
        else
            warn "firewall: could not open $_p — run: ufw allow $_p"
        fi
    done
fi

# ── Record the effective deploy so `sh relay.sh update` can replay it ────
# Native only: the docker path already persists its config in the bundle's
# .env. Written on every deploy, so the record always matches reality.
if [ "$MODE" = "native" ]; then
    mkdir -p "$PREFIX/conf"
    cat > "$PREFIX/conf/deploy.conf" <<DEPLOYCONF
# Written by relay.sh at deploy — 'sh relay.sh update [new-bundle.tgz]'
# replays these. Edit by redeploying with different flags, not by hand.
WYRD_RELAY_DEPLOY_MODE=$MODE
WYRD_RELAY_DEPLOY_PUBLIC=$PUBLIC
WYRD_RELAY_DEPLOY_REG_MODE=${RELAY_MODE:-}
WYRD_RELAY_DEPLOY_SSH_TUNNEL=$SSH_TUNNEL
WYRD_RELAY_DEPLOY_SSH_TOPOLOGY=$SSH_TUNNEL_TOPOLOGY
WYRD_RELAY_DEPLOY_SSH_MODE=$SSH_TUNNEL_MODE
WYRD_RELAY_DEPLOY_OWNER_DID=${OWNER_DID:-}
WYRD_RELAY_DEPLOY_HOST_ARG=${HOST_ARG:-}
DEPLOYCONF
    say "recorded deploy flags → $PREFIX/conf/deploy.conf (next time: sh relay.sh update <new-bundle.tgz>)"
fi

# ── Bootstrap invite ─────────────────────────────────────────────────────
if [ "$MODE" = "docker" ]; then
    REG_CID=$(op_docker_reg_cid)
    [ -n "$REG_CID" ] || die "registration container not found"
    INVITE_JSON=$(docker exec "$REG_CID" wget -qO- \
        --post-data="{\"ttl\":${INVITE_TTL}}" \
        --header="Content-Type: application/json" \
        http://127.0.0.1:9280/invite 2>/dev/null) || die "invite mint failed"
else
    INVITE_JSON=$(curl -sf -X POST -H "Content-Type: application/json" \
        -d "{\"ttl\":${INVITE_TTL}}" \
        http://127.0.0.1:$REG_PORT/invite) || die "invite mint failed"
fi
INVITE_URL=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"invite_url": *"\([^"]*\)".*/\1/p')
JOIN_CODE=$(printf '%s' "$INVITE_JSON" | sed -n 's/.*"join_code": *"\([^"]*\)".*/\1/p')
# CA fingerprint — rides inside the join token so the zone verifies the
# relay it redeems against BEFORE trusting anything it receives. The token
# is the trust decision (same model as the wyrdphone:// invite).
CA_FP=$(printf '%s' "$INVITE_JSON" \
    | sed -n 's/.*"ca_fingerprint": *"\([^"]*\)".*/\1/p' \
    | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$INVITE_URL" ] || die "could not parse invite from: $INVITE_JSON"

echo ""
# Render every advertised address as a wss:// URL (the relay answers on all of
# them; the invite carries the full list so devices fail over automatically).
ADDR_LIST=""
for _ip in $(printf '%s' "$HOST_LIST" | tr ',' ' '); do
    ADDR_LIST="${ADDR_LIST:+$ADDR_LIST, }wss://$_ip:$PORT"
done
[ -n "$ADDR_LIST" ] || ADDR_LIST="wss://$HOST:$PORT"

say "── relay is UP ──────────────────────────────────────────────"
echo "  address : $ADDR_LIST  (household CA — devices pin it from their invites)"
if [ "$FW_OPENED" = 1 ]; then
    echo "  firewall: opened on THIS host → $FW_PORTS"
    echo "            (if a CLOUD firewall fronts this box, allow the same TCP ports there too)"
else
    echo "  firewall: open these TCP ports → $FW_PORTS"
    echo "            (ufw inactive/absent — open them on your host AND any cloud firewall)"
fi
[ "$PUBLIC" = 1 ] || echo "  discoverability: PRIVATE (hidden) — in no directory, answers no enumeration; usable only with the token above"
echo ""
if [ "$INVITE_TTL" -ge 3600 ]; then
    TTL_HUMAN="$((INVITE_TTL/3600))h"
else
    TTL_HUMAN="$((INVITE_TTL/60))min"
fi
echo "  Join your zone to this relay (valid $TTL_HUMAN, single use):"
echo ""
if [ -n "$JOIN_CODE" ] && [ -n "$CA_FP" ]; then
    echo "    wyrd relay join wyrdjoin://$HOST:$PORT/$JOIN_CODE.$CA_FP"
    echo ""
    # The token validates by fingerprint, not by host — the host is just a
    # reachable dial label. On a multi-NIC relay, a zone that can't reach the
    # primary address can swap it for any other and the SAME token still works.
    case "$HOST_LIST" in
        *,*) echo "  (relay also reachable at: $(printf '%s' "$HOST_LIST" | sed "s/^[^,]*,//; s/,/, /g") — swap the host above for any; the token works against all)"
             echo "" ;;
    esac
    echo "  (or paste the full invite URL with: wyrd relay register '<url>')"
elif [ -n "$JOIN_CODE" ]; then
    echo "    wyrd relay join $HOST:$PORT $JOIN_CODE"
else
    echo "    wyrd relay register '$INVITE_URL'"
fi
echo ""

# ── Owner bootstrap (b) ──────────────────────────
# If --owner recorded a DID, the relay is already claimed — say so. Otherwise
# mint a one-time owner-claim token (localhost-only /claim-owner-mint, same
# trust model as /invite) and print the `wyrd relay claim` line. Best-effort:
# a mint hiccup never fails the deploy (the operator can re-run `relay.sh`).
if [ -n "$OWNER_DID" ]; then
    echo "  Admin owner: $OWNER_DID (recorded — self-administers, can delegate)."
    echo ""
else
    if [ "$MODE" = "docker" ]; then
        CLAIM_JSON=$(docker exec "$REG_CID" wget -qO- \
            --post-data="{\"ttl\":${INVITE_TTL}}" \
            --header="Content-Type: application/json" \
            http://127.0.0.1:9280/claim-owner-mint 2>/dev/null || true)
    else
        CLAIM_JSON=$(curl -sf -X POST -H "Content-Type: application/json" \
            -d "{\"ttl\":${INVITE_TTL}}" \
            "http://127.0.0.1:$REG_PORT/claim-owner-mint" 2>/dev/null || true)
    fi
    CLAIM_TOKEN=$(printf '%s' "$CLAIM_JSON" | sed -n 's/.*"claim_token": *"\([^"]*\)".*/\1/p')
    if [ -n "$CLAIM_TOKEN" ]; then
        echo "  Claim ADMIN ownership of this relay from your zone (valid $TTL_HUMAN, single use):"
        echo ""
        echo "    wyrd relay claim $CLAIM_TOKEN --registration-url https://$HOST:$PORT"
        echo ""
        echo "  (or deploy with --owner <did:key:… or did:wyrd:…> to skip this; find your DID via wyrd whoami)"
        echo ""
    else
        warn "could not mint an owner-claim token — re-run relay.sh, or use --owner <did>."
    fi
fi

echo "  Then put the app on your phone with:  wyrd phone invite"
echo "  Remove the relay any time with:       sh relay.sh uninstall"
echo ""
if [ "$MODE" = "docker" ]; then
    echo "  Back up the 'relay_data' + 'relay_certs' docker volumes — they hold your"
    echo "  relay identity (household CA + keys + registrations); lose them and every"
    echo "  device must re-pair. (See deploy/relay/README.md → Back up your relay identity.)"
else
    echo "  Back up $PREFIX/{certs,data} — it holds your relay identity (CA + keys +"
    echo "  registrations); lose it and every device must re-pair."
fi
echo ""
echo "  This relay is yours — nobody else's outage can take it down."
echo "  Every household running its own is one less place the network can break."
