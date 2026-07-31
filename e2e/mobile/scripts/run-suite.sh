#!/usr/bin/env bash
# =============================================================================
# Run Maestro E2E test suite for Wyrdsekai mobile apps
#
# Usage:
#   ./run-suite.sh --platform rn --suite smoke
#   ./run-suite.sh --platform kmp --suite quick
#   ./run-suite.sh --platform both --suite full
#   ./run-suite.sh --platform rn --suite smoke --headless
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MOBILE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$MOBILE_DIR/../.." && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$HOME/.maestro/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# Defaults
PLATFORM="rn"
SUITE="smoke"
HEADLESS=false
REPORT_DIR="$MOBILE_DIR/reports"

# Parse args
while [ $# -gt 0 ]; do
    case "$1" in
        --platform) PLATFORM="$2"; shift 2 ;;
        --suite)    SUITE="$2"; shift 2 ;;
        --headless) HEADLESS=true; shift ;;
        --report-dir) REPORT_DIR="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${BLUE}[*]${NC} $1"; }
ok()    { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
fail()  { echo -e "${RED}[-]${NC} $1"; }

mkdir -p "$REPORT_DIR"

# Check Maestro
command -v maestro &>/dev/null || [ -x "$HOME/.maestro/bin/maestro" ] || {
    fail "Maestro not installed. Run: ./scripts/setup-maestro.sh"
    exit 1
}

# Check emulator
if ! adb devices 2>/dev/null | grep -q "emulator\|device"; then
    warn "No emulator/device detected. Starting AVD..."
    if [ "$HEADLESS" = true ]; then
        "$ANDROID_HOME/emulator/emulator" -avd wyrd-e2e-api35 -no-window -no-audio -no-snapshot-load -gpu swiftshader_indirect &
    else
        "$ANDROID_HOME/emulator/emulator" -avd wyrd-e2e-api35 -no-audio -no-snapshot-load -gpu auto &
    fi
    adb wait-for-device
    WAITED=0
    while [ "$WAITED" -lt 120 ]; do
        BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$BOOT" = "1" ] && break
        sleep 2; WAITED=$((WAITED + 2))
    done
    [ "$WAITED" -ge 120 ] && { fail "Emulator boot timeout"; exit 1; }
    ok "Emulator booted"
fi

# Check test server
if ! curl -sf "http://localhost:7070/health" > /dev/null 2>&1; then
    warn "Test server not running. Start with: ./scripts/start-test-server.sh"
    exit 1
fi

# Run for each platform
run_platform() {
    local plat="$1"
    local suite_file="$MOBILE_DIR/suites/${plat}-${SUITE}.yaml"

    if [ ! -f "$suite_file" ]; then
        fail "Suite not found: $suite_file"
        return 1
    fi

    info "Running $plat $SUITE suite..."

    local report_name="${plat}-${SUITE}-$(date +%Y%m%d-%H%M%S)"
    maestro test \
        --format junit \
        --output "$REPORT_DIR/${report_name}.xml" \
        "$suite_file" 2>&1

    local exit_code=$?
    if [ $exit_code -eq 0 ]; then
        ok "$plat $SUITE: ALL PASSED"
    else
        fail "$plat $SUITE: FAILURES (exit $exit_code)"
    fi
    return $exit_code
}

OVERALL=0

if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "rn" ]; then
    run_platform "rn" || OVERALL=1
fi

if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "kmp" ]; then
    run_platform "kmp" || OVERALL=1
fi

echo ""
if [ $OVERALL -eq 0 ]; then
    ok "All suites passed. Reports: $REPORT_DIR/"
else
    fail "Some suites failed. Reports: $REPORT_DIR/"
fi
exit $OVERALL
