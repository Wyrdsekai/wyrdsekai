#!/usr/bin/env bash
# =============================================================================
# Wyrdsekai LOCAL CI — manual run, zero cloud dependency
#
# Runs the full build + UI-test matrix on your own machines. No GitHub, no
# GitHub Actions, no cloud git host. This is a plain script: the canonical CI
# artifact is THIS FILE plus the suites it wraps, so it works identically for
# any clone of this repository, wherever it was obtained.
#
# Stages (default set runs everything that lives on THIS machine = home-server):
#   java          ./gradlew build -x test  +  Tier-0 integration tests
#   rn-android    RN debug APK (self-pins JDK 21 for the CMake native modules)
#   kmp-android   KMP debug APK
#   kmp-desktop   KMP Compose desktop app compiles
#   web           Playwright ct1-smoke + ct2-local (no server, WYRD_SKIP_SERVER)
#   android-e2e   Maestro RN+KMP suite on the emulator (run-all.sh)
#
# Cross-machine stages are OPT-IN (they ssh to other boxes you already use):
#   --ios         build RN iOS on mac-node  (+ simulator Maestro with --ios-e2e)
#   --windows     build the .msi on windows-node
#
# Usage:
#   ./run-local-ci.sh                      # full home-server-local matrix
#   ./run-local-ci.sh --builds-only        # skip the emulator/Maestro stage
#   ./run-local-ci.sh --stages java,web    # just these
#   ./run-local-ci.sh --skip android-e2e   # everything except this
#   ./run-local-ci.sh --suite smoke        # lighter Maestro suite (smoke|quick|full)
#   ./run-local-ci.sh --ios --windows      # add the cross-machine build gates
#
# Exit code is 0 only if every RUN stage passed. SKIPPED stages don't fail it.
# Each stage logs to reports/<timestamp>/<stage>.log and never aborts the rest.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_DIR="$SCRIPT_DIR/reports/$(date +%Y%m%d-%H%M%S)"

# Hosts for the opt-in cross-machine stages (ssh aliases you already use).
MAC_NODE_HOST="${WYRD_CI_MACMINI:-mac-node}"
WINDOWS_NODE_HOST="${WYRD_CI_WINBLOW:-windows-node}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# ── All known stages, in run order ────────────────────────────────────────
ALL_STAGES=(java rn-android kmp-android kmp-desktop web android-e2e ios windows)
# Default: everything local to this machine. ios/windows are opt-in.
DEFAULT_STAGES=(java rn-android kmp-android kmp-desktop web android-e2e)

# ── Defaults ──────────────────────────────────────────────────────────────
SUITE="quick"
SELECTED=()        # explicit --stages list (overrides default)
SKIP=()            # --skip list
WANT_IOS=false
WANT_IOS_E2E=false
WANT_WINDOWS=false
BUILDS_ONLY=false

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${BLUE}[*]${NC} $1"; }
ok()      { echo -e "${GREEN}[+]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
err()     { echo -e "${RED}[-]${NC} $1"; }
section() { echo -e "\n${BOLD}═══ $1 ═══${NC}\n"; }

# ── Parse args ────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --stages)      IFS=',' read -ra SELECTED <<< "$2"; shift 2 ;;
        --skip)        IFS=',' read -ra SKIP <<< "$2"; shift 2 ;;
        --suite)       SUITE="$2"; shift 2 ;;
        --builds-only) BUILDS_ONLY=true; shift ;;
        --ios)         WANT_IOS=true; shift ;;
        --ios-e2e)     WANT_IOS=true; WANT_IOS_E2E=true; shift ;;
        --windows)     WANT_WINDOWS=true; shift ;;
        --help|-h)
            sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) err "Unknown arg: $1"; exit 2 ;;
    esac
done

# ── Resolve the stage list ──────────────────────────────────────────────────
stages=()
if [ ${#SELECTED[@]} -gt 0 ]; then
    stages=("${SELECTED[@]}")
else
    stages=("${DEFAULT_STAGES[@]}")
    $WANT_IOS && stages+=(ios)
    $WANT_WINDOWS && stages+=(windows)
fi
$BUILDS_ONLY && SKIP+=(android-e2e ios-e2e)

is_skipped() { for s in "${SKIP[@]:-}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

# ── Result tracking ─────────────────────────────────────────────────────────
declare -a RESULT_STAGE RESULT_STATUS RESULT_SECS
record() { RESULT_STAGE+=("$1"); RESULT_STATUS+=("$2"); RESULT_SECS+=("$3"); }

# run_stage <name> <command...>  — runs in a subshell, logs, times, records.
run_stage() {
    local name="$1"; shift
    if is_skipped "$name"; then
        warn "SKIP $name (--skip)"; record "$name" SKIP 0; return 0
    fi
    local log="$REPORT_DIR/${name}.log"
    local start; start=$(date +%s)
    info "▶ $name  (log: $log)"
    if ( "$@" ) >"$log" 2>&1; then
        local secs=$(( $(date +%s) - start ))
        ok "✔ $name (${secs}s)"; record "$name" PASS "$secs"; return 0
    else
        local secs=$(( $(date +%s) - start ))
        err "✗ $name FAILED (${secs}s) — tail:"; tail -n 15 "$log" | sed 's/^/    /'
        record "$name" FAIL "$secs"; return 1
    fi
}

# ── Stage implementations ───────────────────────────────────────────────────
# JDK 25 is home-server's default and is correct for Java/KMP/web. build-rn.sh
# self-pins JDK 21 internally for the RN-Android CMake native modules.

stage_java() {
    cd "$PROJECT_ROOT"
    # BUILD GATE: compile every module (incl. e2e-test's test sources, so test
    # compile-breakage is still caught) + run the lightweight per-module unit
    # tests. We EXCLUDE :e2e-test:test — that module is the integration /
    # conformance / live tier (embedded nats, inference, multi-node), much of it
    # infra-coupled and only tagged @Tag("integration") (not needs-*), so it
    # fails/flakes on a host that's also prod (port clashes, awaitility timeouts
    # under load). That tier runs separately (android-e2e here, or a non-prod
    # runner / throwaway-docker context) — NOT this quick build gate.
    ./gradlew build :e2e-test:testClasses -x :e2e-test:test --no-daemon
}

stage_rn_android()  { "$PROJECT_ROOT/e2e/mobile/scripts/build-rn.sh"; }
stage_kmp_android() { "$PROJECT_ROOT/e2e/mobile/scripts/build-kmp.sh"; }

stage_kmp_desktop() {
    cd "$PROJECT_ROOT/clients/kmp"
    # `assemble` compiles the Compose desktop app without bundling a JRE
    # (packageDistributionForCurrentOS is the heavier "produce an installer"
    # task — not needed to gate that it BUILDS).
    ./gradlew :desktopApp:assemble --no-daemon
}

stage_web() {
    cd "$PROJECT_ROOT/clients/rn"
    [ -d node_modules ] || pnpm install
    npx playwright install chromium >/dev/null 2>&1 || true
    # ct1 (smoke) + ct2 (local-mode room) — neither needs the Java server.
    WYRD_SKIP_SERVER=1 npx playwright test \
        --config e2e/web/playwright.config.ts \
        --project ct1-smoke --project ct2-flows
}

stage_android_e2e() {
    # run-all.sh boots the emulator, builds (we --skip-build since the build
    # stages already produced APKs), installs, runs the Maestro suite, cleans up.
    "$PROJECT_ROOT/e2e/mobile/run-all.sh" \
        --platform both --suite "$SUITE" --headless --skip-build
}

stage_ios() {
    # Build RN iOS on mac-node (the iOS app IS the RN client). Optionally run
    # the simulator Maestro probe flows when --ios-e2e is set.
    local remote_cmd='cd ~/src/wyrdsekai/clients/rn/ios && pod install && xcodebuild -workspace Wyrdsekai.xcworkspace -scheme Wyrdsekai -sdk iphonesimulator -configuration Debug build CODE_SIGNING_ALLOWED=NO'
    if $WANT_IOS_E2E; then
        remote_cmd="$remote_cmd && cd ~/src/wyrdsekai && e2e/mobile/scripts/run_probe_relay_ios.sh"
    fi
    ssh "$MAC_NODE_HOST" "$remote_cmd"
}

stage_windows() {
    # Build the .msi on windows-node. git is not installed there — assumes the repo
    # working tree is already synced (scp), per the windows-node build runbook.
    ssh "$WINDOWS_NODE_HOST" 'powershell -NoProfile -Command "cd C:\\Users\\operator\\src\\wyrdsekai; .\\packaging\\windows\\build-msi.ps1 -Version 0.1.2-ci"'
}

# ══════════════════════════════════════════════════════════════════════════
mkdir -p "$REPORT_DIR"
START_TIME=$(date +%s)

section "Wyrdsekai LOCAL CI"
echo "  Stages:   ${stages[*]}"
[ ${#SKIP[@]} -gt 0 ] && echo "  Skip:     ${SKIP[*]}"
echo "  Suite:    $SUITE (android-e2e)"
echo "  Reports:  $REPORT_DIR"
echo "  Host:     $(hostname)  java=$(java -version 2>&1 | head -1 | grep -oE '\"[0-9]+' | tr -d '\"')"
echo ""

for stage in "${stages[@]}"; do
    case "$stage" in
        java)        run_stage java        stage_java ;;
        rn-android)  run_stage rn-android  stage_rn_android ;;
        kmp-android) run_stage kmp-android stage_kmp_android ;;
        kmp-desktop) run_stage kmp-desktop stage_kmp_desktop ;;
        web)         run_stage web         stage_web ;;
        android-e2e) run_stage android-e2e stage_android_e2e ;;
        ios)         run_stage ios         stage_ios ;;
        windows)     run_stage windows     stage_windows ;;
        *)           warn "unknown stage '$stage' — ignored" ;;
    esac
done

# ── Summary ─────────────────────────────────────────────────────────────────
section "Summary"
overall=0
for i in "${!RESULT_STAGE[@]}"; do
    st="${RESULT_STATUS[$i]}"; nm="${RESULT_STAGE[$i]}"; sc="${RESULT_SECS[$i]}"
    case "$st" in
        PASS) printf "  ${GREEN}%-6s${NC} %-14s %ss\n" "PASS" "$nm" "$sc" ;;
        FAIL) printf "  ${RED}%-6s${NC} %-14s %ss\n" "FAIL" "$nm" "$sc"; overall=1 ;;
        SKIP) printf "  ${YELLOW}%-6s${NC} %-14s\n" "SKIP" "$nm" ;;
    esac
done
echo ""
echo "  Total: $(( $(date +%s) - START_TIME ))s   Reports: $REPORT_DIR"
if [ $overall -eq 0 ]; then ok "LOCAL CI GREEN"; else err "LOCAL CI RED"; fi
exit $overall
