#!/usr/bin/env bash
#
# deploy-mesh.sh — build installers + roll out across the mesh per-host strategy.
#
# Default mesh layout (override with --hosts):
#   home-server     — source mode, runs ./scripts/deploy.sh locally (no installer)
#   test-node  — Linux .deb installer (built on home-server, scp'd, dpkg installed)
#   mac-node  — macOS .pkg installer (built on home-server, scp'd, installer ran)
#   relay-node    — relay-only (NATS) — skipped by default, pass --include relay-node to deploy
#
# Phases:
#   1. build      — bash packaging/build-all.sh on the build host (home-server by default)
#   2. distribute — scp the matching artifact to each remote host
#   3. install    — run dpkg -i / installer / deploy.sh per host strategy
#   4. restart    — systemctl restart wyrdsekai (or launchd reload on macOS)
#   5. verify     — ping /health (if API URL configured)
#
# Usage:
#   scripts/deploy-mesh.sh                          # default: build+deploy home-server test-node mac-node
#   scripts/deploy-mesh.sh --hosts test-node          # only test-node
#   scripts/deploy-mesh.sh --include relay-node          # add relay-node (relay update)
#   scripts/deploy-mesh.sh --skip-build             # use existing artifacts
#   scripts/deploy-mesh.sh --skip mac-node           # skip mac-node
#   scripts/deploy-mesh.sh --check                  # status across the mesh, no changes
#   scripts/deploy-mesh.sh --build-only             # build artifacts, don't deploy
#
# Per-host SSH expectations:
#   - SSH key auth from this machine to ${SSH_USER}@<host>
#   - sudo on remote works without password (for dpkg / installer)
#
# Environment:
#   SSH_USER         — remote user (default: $USER)
#   WYRD_REPO_PATH   — repo path on each remote (default: ~/src/wyrdsekai)
#
set -uo pipefail

# --- defaults ---
DEFAULT_HOSTS="home-server,test-node,mac-node"
HOSTS="${DEFAULT_HOSTS}"
INCLUDE=""
SKIP=""
MODE="full"           # full | build-only | check | restart-only
SKIP_BUILD=0
BUILD_HOST="${BUILD_HOST:-home-server}"

# --- colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
DIM='\033[0;2m'
BOLD='\033[1m'
NC='\033[0m'

# --- per-host strategy table ---
# Strategy is one of:
#   source — run ./scripts/deploy.sh on the host (build locally)
#   deb    — install built .deb via dpkg
#   pkg    — install built .pkg via macOS installer
#   relay  — NATS relay only, no wyrdsekai service
strategy_for() {
    case "$1" in
        home-server)    echo "source" ;;
        test-node) echo "deb"    ;;
        mac-node) echo "pkg"    ;;
        relay-node)   echo "relay"  ;;
        *)       echo "source" ;;  # safe default
    esac
}

# Service name for systemctl / launchd
service_name="wyrdsekai"
launchd_label="com.wyrdsekai.server"

usage() {
    sed -n '/^#$\|^# /{s/^#\s\?//;p;}' "$0" | head -45
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --hosts)         HOSTS="$2"; shift 2 ;;
        --include)       INCLUDE="${INCLUDE},$2"; shift 2 ;;
        --skip)          SKIP="${SKIP},$2"; shift 2 ;;
        --skip-build)    SKIP_BUILD=1; shift ;;
        --build-only)    MODE="build-only"; shift ;;
        --check)         MODE="check"; shift ;;
        --restart-only)  MODE="restart-only"; shift ;;
        --build-host)    BUILD_HOST="$2"; shift 2 ;;
        --help|-h)       usage ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

SSH_USER="${SSH_USER:-$USER}"
WYRD_REPO_PATH="${WYRD_REPO_PATH:-\$HOME/src/wyrdsekai}"

# Resolve target list
TARGETS=""
IFS=',' read -ra ALL <<< "${HOSTS}${INCLUDE}"
for h in "${ALL[@]}"; do
    [[ -z "$h" ]] && continue
    [[ ",${SKIP}," == *",${h},"* ]] && continue
    TARGETS+="${h} "
done

if [[ -z "$TARGETS" ]]; then
    echo "No targets after skip filter." >&2
    exit 2
fi

# --- helpers ---
log()  { echo -e "${GREEN}▶${NC} $*"; }
warn() { echo -e "${YELLOW}!${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*"; }
ok()   { echo -e "${GREEN}✓${NC} $*"; }
info() { echo -e "  ${DIM}$*${NC}"; }

ssh_run() {
    local host="$1"
    local cmd="$2"
    if [[ "$host" == "$(hostname)" || "$host" == "localhost" ]]; then
        bash -c "$cmd" 2>&1 | sed "s/^/  ${DIM}[${host}]${NC} /"
        return ${PIPESTATUS[0]}
    fi
    ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
        "${SSH_USER}@${host}" "$cmd" 2>&1 | sed "s/^/  ${DIM}[${host}]${NC} /"
    return ${PIPESTATUS[0]}
}

scp_to() {
    local host="$1" src="$2" dst="$3"
    scp -o BatchMode=yes -o ConnectTimeout=5 -q "$src" "${SSH_USER}@${host}:${dst}" \
        || { fail "scp ${src} → ${host}:${dst}"; return 1; }
    info "scp ${src} → ${host}:${dst}"
}

# --- check mode ---
do_check() {
    echo -e "${BOLD}=== mesh status ===${NC}"
    for h in $TARGETS; do
        local strat=$(strategy_for "$h")
        log "${h} (strategy=${strat})"
        case "$strat" in
            relay)
                ssh_run "$h" "systemctl is-active nats-server 2>/dev/null || echo unknown" || true
                ;;
            pkg)
                ssh_run "$h" "launchctl print system/${launchd_label} 2>/dev/null | grep -E 'state|pid' | head -2 || echo not-loaded" || true
                ;;
            *)
                ssh_run "$h" "systemctl is-active ${service_name} 2>/dev/null || (pgrep -af 'wyrdsekai|java.*Main' | head -1) || echo not-running" || true
                ;;
        esac
    done
}

# --- build phase ---
do_build() {
    if [[ $SKIP_BUILD -eq 1 ]]; then
        warn "skipping build (--skip-build)"
        return 0
    fi
    echo -e "${BOLD}=== build installers on ${BUILD_HOST} ===${NC}"
    # Determine which formats we need based on targets
    local need_deb=0 need_pkg=0
    for h in $TARGETS; do
        local s=$(strategy_for "$h")
        [[ "$s" == "deb" ]] && need_deb=1
        [[ "$s" == "pkg" ]] && need_pkg=1
    done
    local formats=""
    [[ $need_deb -eq 1 ]] && formats+=" --deb"
    [[ $need_pkg -eq 1 ]] && formats+=" --pkg"
    if [[ -z "$formats" ]]; then
        info "no .deb / .pkg targets — skipping installer build"
        return 0
    fi
    log "build-all.sh${formats}"
    ssh_run "$BUILD_HOST" "cd ${WYRD_REPO_PATH} && bash packaging/build-all.sh${formats}"
}

# --- per-host deploy ---
deploy_one() {
    local host="$1"
    local strat=$(strategy_for "$host")
    log "${host} (strategy=${strat})"

    case "$strat" in
        source)
            ssh_run "$host" "cd ${WYRD_REPO_PATH} && git fetch --quiet && git pull --ff-only && ./scripts/deploy.sh"
            ;;
        deb)
            local deb_glob="${WYRD_REPO_PATH}/packaging/deb/dist/wyrdsekai_*.deb"
            local deb_path
            deb_path=$(ssh_run "$BUILD_HOST" "ls -1t ${deb_glob} 2>/dev/null | head -1 | awk '{print \$NF}'" | tail -1)
            deb_path=$(echo "$deb_path" | tr -d '[:space:]')
            if [[ -z "$deb_path" ]]; then
                fail "${host}: no .deb artifact found on ${BUILD_HOST} (run --build first)"
                return 1
            fi
            local deb_basename=$(basename "$deb_path")
            local tmp="/tmp/${deb_basename}"
            # Pull deb from build host (if we're on build host, just use local path)
            if [[ "$BUILD_HOST" == "$host" ]]; then
                : # no-op
            elif [[ "$BUILD_HOST" == "$(hostname)" || "$BUILD_HOST" == "localhost" ]]; then
                scp_to "$host" "$deb_path" "$tmp"
            else
                ssh_run "$BUILD_HOST" "scp -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${deb_path} ${SSH_USER}@${host}:${tmp}"
            fi
            ssh_run "$host" "sudo dpkg -i ${tmp} && sudo systemctl restart ${service_name} && systemctl is-active ${service_name}"
            ;;
        pkg)
            local pkg_glob="${WYRD_REPO_PATH}/packaging/dist/Wyrdsekai-*.pkg"
            local pkg_path
            pkg_path=$(ssh_run "$BUILD_HOST" "ls -1t ${pkg_glob} 2>/dev/null | head -1 | awk '{print \$NF}'" | tail -1)
            pkg_path=$(echo "$pkg_path" | tr -d '[:space:]')
            if [[ -z "$pkg_path" ]]; then
                fail "${host}: no .pkg artifact found on ${BUILD_HOST} (run --build first)"
                return 1
            fi
            local pkg_basename=$(basename "$pkg_path")
            local tmp="/tmp/${pkg_basename}"
            if [[ "$BUILD_HOST" == "$(hostname)" || "$BUILD_HOST" == "localhost" ]]; then
                scp_to "$host" "$pkg_path" "$tmp"
            else
                ssh_run "$BUILD_HOST" "scp -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${pkg_path} ${SSH_USER}@${host}:${tmp}"
            fi
            ssh_run "$host" "sudo installer -pkg ${tmp} -target / && sudo launchctl unload /Library/LaunchDaemons/${launchd_label}.plist 2>/dev/null; sudo launchctl load -w /Library/LaunchDaemons/${launchd_label}.plist && launchctl print system/${launchd_label} | grep -E 'state|pid' | head -2"
            ;;
        relay)
            ssh_run "$host" "sudo systemctl restart nats-server && systemctl is-active nats-server"
            ;;
    esac
}

# --- restart-only ---
do_restart() {
    echo -e "${BOLD}=== restart only ===${NC}"
    for h in $TARGETS; do
        local strat=$(strategy_for "$h")
        log "${h} (strategy=${strat})"
        case "$strat" in
            source) ssh_run "$h" "cd ${WYRD_REPO_PATH} && ./scripts/deploy.sh --restart" || EXIT=1 ;;
            deb)    ssh_run "$h" "sudo systemctl restart ${service_name}" || EXIT=1 ;;
            pkg)    ssh_run "$h" "sudo launchctl kickstart -k system/${launchd_label}" || EXIT=1 ;;
            relay)  ssh_run "$h" "sudo systemctl restart nats-server" || EXIT=1 ;;
        esac
    done
}

# --- main ---
echo -e "${DIM}mode=${MODE}, ssh_user=${SSH_USER}, build_host=${BUILD_HOST}${NC}"
echo -e "${DIM}targets: ${TARGETS}${NC}"
for h in $TARGETS; do
    info "  ${h} → $(strategy_for "$h")"
done
echo

EXIT=0

case "$MODE" in
    check)
        do_check
        ;;
    build-only)
        do_build || EXIT=1
        ;;
    restart-only)
        do_restart
        ;;
    full)
        do_build || EXIT=1
        echo
        echo -e "${BOLD}=== deploy phase ===${NC}"
        for h in $TARGETS; do
            deploy_one "$h" || EXIT=1
        done
        ;;
esac

echo
if [[ "$EXIT" -eq 0 ]]; then
    ok "deploy-mesh done"
else
    fail "deploy-mesh had failures — see [host] output above"
fi
exit "$EXIT"
