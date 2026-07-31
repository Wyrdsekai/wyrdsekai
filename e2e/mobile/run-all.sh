#!/usr/bin/env bash
# =============================================================================
# Wyrdsekai Mobile E2E — Full automated run
#
# Boots emulator, starts test server, builds apps, installs, runs Maestro suites.
# Cleans up everything on exit.
#
# Usage:
#   ./run-all.sh                          # Both platforms, quick suite
#   ./run-all.sh --suite smoke            # Smoke only
#   ./run-all.sh --suite full             # Full regression
#   ./run-all.sh --platform rn            # RN only
#   ./run-all.sh --platform kmp           # KMP only
#   ./run-all.sh --headless               # No emulator window
#   ./run-all.sh --skip-build             # Skip APK build (reuse existing)
#   ./run-all.sh --skip-server            # Server already running
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export DISPLAY="${DISPLAY:-:0}"
export PATH="$HOME/.maestro/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

# ── Defaults ──────────────────────────────────
PLATFORM="both"
SUITE="quick"
HEADLESS=false
SKIP_BUILD=false
SKIP_SERVER=false
REPORT_DIR="$SCRIPT_DIR/reports/$(date +%Y%m%d-%H%M%S)"
SERVER_PORT=7070
# AVD: prefer env override, else default to api36 (current dev-laptop AVD).
# Fallback to api35 for legacy hosts via --avd flag.
AVD_NAME="${WYRD_E2E_AVD:-wyrd-e2e-api36}"
# Test server host: where the WYRDSEKAI_TEST_MODE server runs. The Maestro
# yamls bake in 192.0.2.105:7099 (home-server's wired IP) because dev-laptop has
# no GPU — inference offloads to home-server's prod llama-server on :8200/:8201.
# When orchestrating from dev-laptop, use --skip-server and start the server
# manually on home-server via: ssh home-server 'cd ~/src/wyrdsekai && e2e/mobile/scripts/start-test-server.sh'
TEST_SERVER_HOST="${WYRD_E2E_SERVER_HOST:-192.0.2.105}"
TEST_SERVER_PORT="${WYRD_E2E_SERVER_PORT:-7099}"
EMU_PID=""
SERVER_PID_FILE="/tmp/wyrdsekai-e2e-server.pid"

# ── Parse args ────────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        --platform)     PLATFORM="$2"; shift 2 ;;
        --suite)        SUITE="$2"; shift 2 ;;
        --avd)          AVD_NAME="$2"; shift 2 ;;
        --headless)     HEADLESS=true; shift ;;
        --skip-build)   SKIP_BUILD=true; shift ;;
        --skip-server)  SKIP_SERVER=true; shift ;;
        --help|-h)
            echo "Usage: $0 [--platform rn|kmp|both] [--suite smoke|quick|full] [--avd <name>] [--headless] [--skip-build] [--skip-server]"
            echo ""
            echo "  --avd          \$WYRD_E2E_AVD or wyrd-e2e-api36 (default)"
            echo "  --skip-server  expect test server already running at \$WYRD_E2E_SERVER_HOST:\$WYRD_E2E_SERVER_PORT"
            echo "                 (default: 192.0.2.105:7099 = home-server's wired IP)"
            echo ""
            echo "Topology when running from dev-laptop:"
            echo "  1. ssh home-server 'cd ~/src/wyrdsekai && e2e/mobile/scripts/start-test-server.sh'"
            echo "  2. $0 --skip-server [...]"
            echo ""
            echo "Lain hosts the test server (it has the GPU + llama-server). dev-laptop"
            echo "runs the emulator + Maestro orchestrator. The emulator reaches home-server's"
            echo "test server over the LAN at 192.0.2.105:7099."
            exit 0 ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac
done

# ── Colors ────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${BLUE}[*]${NC} $1"; }
ok()      { echo -e "${GREEN}[+]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
fail()    { echo -e "${RED}[-]${NC} $1"; }
section() { echo -e "\n${BOLD}═══ $1 ═══${NC}\n"; }

# ── Cleanup on exit ───────────────────────────
cleanup() {
    echo ""
    section "Cleanup"
    if [ -n "$EMU_PID" ] && kill -0 "$EMU_PID" 2>/dev/null; then
        info "Stopping emulator (PID $EMU_PID)..."
        adb -s emulator-5554 emu kill 2>/dev/null || kill "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
    fi
    if [ "$SKIP_SERVER" = false ] && [ -f "$SERVER_PID_FILE" ]; then
        info "Stopping test server..."
        "$SCRIPT_DIR/scripts/start-test-server.sh" stop 2>/dev/null || true
    fi
    ok "Cleanup complete"
}
trap cleanup EXIT INT TERM

# ── Timer ─────────────────────────────────────
START_TIME=$(date +%s)
elapsed() { echo "$(( $(date +%s) - START_TIME ))s"; }

# ══════════════════════════════════════════════
section "Wyrdsekai Mobile E2E — $PLATFORM / $SUITE"
echo "  Platform:    $PLATFORM"
echo "  Suite:       $SUITE"
echo "  AVD:         $AVD_NAME"
echo "  Test server: http://$TEST_SERVER_HOST:$TEST_SERVER_PORT $([ "$SKIP_SERVER" = true ] && echo "(remote — must already be running)" || echo "(will be started locally)")"
echo "  Headless:    $HEADLESS"
echo "  Reports:     $REPORT_DIR"
echo ""

# Reachability preflight when using a remote test server.
if [ "$SKIP_SERVER" = true ]; then
    if ! curl -sf --max-time 5 "http://$TEST_SERVER_HOST:$TEST_SERVER_PORT/health" >/dev/null 2>&1; then
        echo -e "${RED}[-]${NC} Cannot reach test server at http://$TEST_SERVER_HOST:$TEST_SERVER_PORT/health"
        echo -e "${YELLOW}[!]${NC} Start it on the server host first:"
        echo -e "${YELLOW}    ssh $TEST_SERVER_HOST 'cd ~/src/wyrdsekai && e2e/mobile/scripts/start-test-server.sh'${NC}"
        exit 1
    fi
    echo -e "${GREEN}[+]${NC} Remote test server is reachable"
    echo ""
fi

# Pin JDK 25 for the rest of this run if available — :server:installDist
# needs it. Children (start-test-server.sh, build-kmp.sh, gradlew) inherit
# JAVA_HOME + PATH from here.
if [ -z "${JAVA_HOME:-}" ] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE 'version "25'; then
    for cand in /usr/lib/jvm/java-25-openjdk-amd64 /usr/lib/jvm/java-1.25.0-openjdk-amd64 /usr/lib/jvm/openjdk-25; do
        if [ -x "$cand/bin/java" ]; then
            export JAVA_HOME="$cand"
            export PATH="$JAVA_HOME/bin:$PATH"
            info "Using JDK 25 at $JAVA_HOME"
            break
        fi
    done
fi

mkdir -p "$REPORT_DIR"

# ══════════════════════════════════════════════
section "1/6 — Prerequisites"

# Maestro
if ! command -v maestro &>/dev/null && [ ! -x "$HOME/.maestro/bin/maestro" ]; then
    fail "Maestro not installed. Run: ./scripts/setup-maestro.sh"
    exit 1
fi
ok "Maestro $(maestro --version 2>/dev/null | tail -1)"

# Java
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '"(\d+)' | tr -d '"')
ok "Java $JAVA_VER"

# adb
adb version >/dev/null 2>&1 || { fail "adb not found"; exit 1; }
ok "adb ready"

# ══════════════════════════════════════════════
section "2/6 — Emulator"

if adb devices 2>/dev/null | grep -q "emulator.*device"; then
    ok "Emulator already running"
    # Dismiss lock screen / notification panel
    adb shell input keyevent KEYCODE_MENU 2>/dev/null || true
    adb shell input keyevent KEYCODE_HOME 2>/dev/null || true
    adb shell wm dismiss-keyguard 2>/dev/null || true
else
    info "Booting $AVD_NAME..."
    # Use swiftshader on Linux (gpu auto can crash with multi-GPU setups)
    gpu_flag="swiftshader_indirect"
    if [ "$(uname -s)" = "Darwin" ]; then gpu_flag="auto"; fi

    if [ "$HEADLESS" = true ]; then
        "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" \
            -no-window -no-audio -no-snapshot-load -gpu "$gpu_flag" &
    else
        "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" \
            -no-audio -no-snapshot-load -gpu "$gpu_flag" &
    fi
    EMU_PID=$!

    info "Waiting for boot..."
    adb wait-for-device
    WAITED=0
    while [ "$WAITED" -lt 180 ]; do
        BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$BOOT" = "1" ]; then
            break
        fi
        sleep 2; WAITED=$((WAITED + 2))
    done
    if [ "$WAITED" -ge 180 ]; then
        fail "Emulator boot timeout (180s)"
        exit 1
    fi
    # Dismiss any system dialogs
    sleep 3
    adb shell input keyevent KEYCODE_HOME 2>/dev/null || true
    ok "Emulator booted (${WAITED}s)"
fi

# Pin all subsequent adb / maestro commands to the emulator. On hosts that
# have a physical phone attached at the same time as the emulator, raw
# `adb` calls hit "more than one device/emulator" and silently split between
# devices — installs / launches end up on the phone, maestro then sees an
# empty/blank screen on the emulator. ANDROID_SERIAL is honored by adb and
# by maestro (via the underlying adb invocation).
EMULATOR_SERIAL="$(adb devices 2>/dev/null | awk '/^emulator-/{print $1; exit}')"
if [ -n "$EMULATOR_SERIAL" ]; then
    export ANDROID_SERIAL="$EMULATOR_SERIAL"
    ok "Pinned ANDROID_SERIAL=$ANDROID_SERIAL"
else
    warn "No emulator serial detected — adb commands may be ambiguous if a phone is attached"
fi

# ══════════════════════════════════════════════
section "3/6 — Test Server"

if [ "$SKIP_SERVER" = true ]; then
    if curl -sf "http://localhost:$SERVER_PORT/health" >/dev/null 2>&1; then
        ok "Server already running (skipped)"
    else
        warn "Server not running but --skip-server set"
    fi
else
    info "Starting test server..."
    "$SCRIPT_DIR/scripts/start-test-server.sh" &
    SERVER_SETUP_PID=$!
    wait "$SERVER_SETUP_PID" || { fail "Test server failed to start"; exit 1; }
    ok "Test server on port $SERVER_PORT"
fi

# ══════════════════════════════════════════════
section "4/6 — Build APKs"

RN_APK=""
KMP_APK=""

if [ "$SKIP_BUILD" = true ]; then
    warn "Skipping builds (--skip-build)"
    # Try to find existing APKs
    RN_APK=$(find "$PROJECT_ROOT/clients/rn/android" -name "*.apk" -path "*/debug/*" 2>/dev/null | head -1)
    KMP_APK=$(find "$PROJECT_ROOT/clients/kmp/androidApp/build" -name "*.apk" -path "*/debug/*" 2>/dev/null | head -1)
else
    if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "rn" ]; then
        info "Building RN APK..."
        RN_APK=$("$SCRIPT_DIR/scripts/build-rn.sh" 2>&1 | tail -1)
        ok "RN APK: $(basename "$RN_APK" 2>/dev/null || echo 'built')"
    fi

    if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "kmp" ]; then
        info "Building KMP APK..."
        KMP_APK=$("$SCRIPT_DIR/scripts/build-kmp.sh" 2>&1 | tail -1)
        ok "KMP APK: $(basename "$KMP_APK" 2>/dev/null || echo 'built')"
    fi
fi

# ══════════════════════════════════════════════
section "5/6 — Install & Run Tests"

OVERALL=0

run_platform() {
    local plat="$1"
    local apk="$2"
    local app_id="$3"
    local suite_file="$SCRIPT_DIR/suites/${plat}-${SUITE}.yaml"

    if [ ! -f "$suite_file" ]; then
        fail "Suite not found: $suite_file"
        return 1
    fi

    # Uninstall + reinstall for clean first-run state.
    # `pm clear` is not enough on RN new-arch builds — llama.rn's JSI install()
    # flow leaves stale state across data-clears and the next app launch hits
    # a "TypeError: Cannot read property 'install' of null" red screen. A fresh
    # install of the APK avoids the issue and is what a real first-run user sees.
    if [ -n "$apk" ] && [ -f "$apk" ]; then
        info "Uninstalling $plat (for clean first-run state)..."
        adb uninstall "$app_id" 2>/dev/null || true
        info "Installing $plat APK..."
        adb install -r -t "$apk" 2>&1 | tail -1
    else
        warn "No APK path for $plat — assuming already installed; falling back to pm clear"
        adb shell pm clear "$app_id" 2>/dev/null || true
    fi

    # Pre-launch the app so maestro can attach to a live process. RN flows
    # MUST be `am start`-launched once (no `launchApp` step in flows) because
    # any subsequent activity restart wedges llama.rn's JSI re-init
    # ("Cannot read property 'install' of null"). Critically we do NOT do a
    # KEYCODE_HOME first — pressing HOME after `adb install` on a fresh APK
    # then `am start` puts the activity through a sleep/wake cycle that also
    # trips the same race. Direct install → am start is the only sequence
    # that lands cleanly on Welcome on this build.
    if [[ "$plat" == "rn" ]]; then
        info "Pre-launching RN app..."
        adb shell am start -n "$app_id/.MainActivity" 2>&1 | tail -1
        sleep 12  # JS bundle needs ~10s to load on slow emulator before WelcomeScreen renders
    else
        adb shell input keyevent KEYCODE_HOME 2>/dev/null || true
    fi

    sleep 1

    info "Running $plat $SUITE suite..."
    local report_file="$REPORT_DIR/${plat}-${SUITE}"

    # Capture pre-maestro screen for debugging blank-screen issues.
    adb shell screencap -p /sdcard/_pre_maestro.png 2>/dev/null
    adb pull /sdcard/_pre_maestro.png "$REPORT_DIR/${plat}-pre-maestro.png" 2>/dev/null
    info "Pre-maestro screenshot: $REPORT_DIR/${plat}-pre-maestro.png"

    # Maestro doesn't honor ANDROID_SERIAL — needs its own --device flag.
    # Without it, on hosts with a phone *and* the emulator attached, maestro
    # fails with "Unable to launch app" because it picks the phone (which
    # doesn't have our APK installed).
    local maestro_device_flag=()
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        maestro_device_flag=(--device "$ANDROID_SERIAL")
    fi

    # RN suites need per-flow process resets between flows so each flow lands
    # on a fresh wizard (each tests a different mutually-exclusive onboarding
    # decision: enter-server-URL vs no-server vs standalone). `am force-stop +
    # am start` resets in-memory React state without `pm clear`, which would
    # trigger the llama.rn install race. KMP suites use the normal maestro
    # suite runner — KMP doesn't have llama and `pm clear` would be fine
    # there too if we needed it.
    # Both platforms now use the per-flow runner so each runFlow entry gets
    # its own pass/fail line + screenshot on failure. Reporting one
    # aggregate "1/1" line for KMP hid which sub-flow actually broke.
    run_rn_suite_per_flow "$plat" "$app_id" "$suite_file" "$report_file" \
        "${maestro_device_flag[@]}"
    return $?
}

# Iterate the suite YAML, run each `runFlow` entry as its own maestro
# invocation, and force-stop + restart the app between flows.
run_rn_suite_per_flow() {
    local plat="$1" app_id="$2" suite_file="$3" report_file="$4"
    shift 4
    local maestro_args=("$@")

    local suite_dir
    suite_dir="$(dirname "$suite_file")"

    # Extract flow paths (resolving `../` relative to suite dir).
    local flow_paths=()
    while IFS= read -r flow_rel; do
        local abs="$suite_dir/$flow_rel"
        flow_paths+=("$(realpath -m "$abs")")
    done < <(grep -E '^\s*-\s*runFlow:' "$suite_file" | sed -E 's/.*runFlow:\s*//')

    if [ ${#flow_paths[@]} -eq 0 ]; then
        fail "No runFlow entries in $suite_file"
        return 1
    fi

    # Extract suite-level `env:` block as -e KEY=VAL flags for maestro test.
    # Without this, ${SERVER_URL} etc. resolve to literal "undefined" when
    # running individual flow files (suite-level env only applies to the
    # suite itself, not when each flow is invoked separately).
    local env_flags=()
    while IFS=$'\t' read -r key val; do
        [ -z "$key" ] && continue
        env_flags+=(-e "${key}=${val}")
    done < <(python3 -c "
import re, sys
in_env = False
with open('$suite_file') as f:
    for line in f:
        if line.startswith('env:'):
            in_env = True
            continue
        if in_env and re.match(r'^[a-zA-Z]', line):
            in_env = False
        if in_env:
            m = re.match(r'^\s+([A-Z_]+):\s*\"?([^\"]*?)\"?\s*\$', line)
            if m:
                print(m.group(1) + '\t' + m.group(2))
")
    info "Suite env flags: ${env_flags[*]}"

    local total=${#flow_paths[@]}
    local pass=0 fail_count=0
    local i=0
    : > "${report_file}.log"

    # Locate APK once for per-flow reinstall.
    local apk_path
    apk_path="$(find "$SCRIPT_DIR/../../clients/rn/android" -name '*.apk' -path '*/debug/*' 2>/dev/null | head -1)"
    if [ -z "$apk_path" ] || [ ! -f "$apk_path" ]; then
        fail "Cannot find RN APK for per-flow reinstall"
        return 1
    fi
    info "Per-flow APK: $apk_path"

    for flow in "${flow_paths[@]}"; do
        i=$((i + 1))
        local flow_name
        flow_name="$(basename "$flow" .yaml)"
        info "[$i/$total] $flow_name"

        # Full uninstall + reinstall is the only reliable per-flow reset:
        # `am force-stop` doesn't clear AsyncStorage so onboarding-completed
        # flags survive and subsequent flows see post-wizard state. `pm clear`
        # would do it but trips the llama.rn install race. Uninstall+install
        # is ~10s per flow, but it's the bulletproof clean-room each test
        # needs. Skip the reinstall for the first flow (suite-level setup
        # already installed it).
        if [ "$i" -gt 1 ]; then
            # `pm clear` wipes AsyncStorage / app data without re-installing —
            # avoids the post-install ActivityManager race where Maestro's
            # `launchApp` (KMP) or `am start` (RN) gets "Unable to launch
            # app" because the just-installed activity isn't yet fully
            # registered. The earlier llama.rn objection to pm clear was a
            # mid-inference race; these tests never trigger inference.
            adb shell am force-stop "$app_id" 2>/dev/null
            adb shell pm clear "$app_id" >/dev/null 2>&1
        fi
        # RN: pre-launch via `am start` because RN flows had to drop their own
        # `launchApp` step (Maestro's launchApp on RN Fabric wipes the React
        # tree to a blank screen). KMP flows still use `launchApp: clearState`
        # so we let Maestro handle the launch — pre-starting here triggers a
        # state collision.
        if [[ "$plat" == "rn" ]]; then
            adb shell am start -n "$app_id/.MainActivity" >/dev/null 2>&1
            sleep 18  # JS bundle reload time on slow emulator
        fi

        local flow_log="${report_file}-${i}-${flow_name}.log"
        if maestro "${maestro_args[@]}" test "${env_flags[@]}" "$flow" 2>&1 | tee -a "${report_file}.log" > "$flow_log"; then
            ok "[$i/$total] $flow_name: PASS"
            pass=$((pass + 1))
        else
            fail "[$i/$total] $flow_name: FAIL"
            fail_count=$((fail_count + 1))
            adb shell screencap -p /sdcard/_fail.png 2>/dev/null
            adb pull /sdcard/_fail.png "${report_file}-${i}-${flow_name}-fail.png" 2>/dev/null
        fi
    done

    if [ "$fail_count" -eq 0 ]; then
        ok "$plat $SUITE: ALL PASSED ($pass/$total)"
        return 0
    else
        fail "$plat $SUITE: $fail_count/$total FAILED ($pass passed)"
        return 1
    fi
}

if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "rn" ]; then
    run_platform "rn" "$RN_APK" "org.wyrdsekai.rn" || OVERALL=1
fi

if [ "$PLATFORM" = "both" ] || [ "$PLATFORM" = "kmp" ]; then
    run_platform "kmp" "$KMP_APK" "org.wyrdsekai.kmp" || OVERALL=1
fi

# ══════════════════════════════════════════════
section "6/6 — Results"

# Copy screenshots if any
if ls /tmp/maestro_screenshots_* 2>/dev/null | head -1 >/dev/null 2>&1; then
    cp /tmp/maestro_screenshots_*/* "$REPORT_DIR/" 2>/dev/null || true
fi

# List all reports
echo "Reports:"
ls -la "$REPORT_DIR/" 2>/dev/null

ELAPSED=$(elapsed)
echo ""
if [ $OVERALL -eq 0 ]; then
    ok "ALL SUITES PASSED ($ELAPSED)"
else
    fail "SOME SUITES FAILED ($ELAPSED)"
fi
echo "  Reports: $REPORT_DIR/"
echo ""

exit $OVERALL
