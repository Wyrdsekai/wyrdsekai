#!/usr/bin/env bash
#
# Cross-project integration tests: Wyrdsekai ↔ CodePlane zone bridge.
#
# Tests the full round-trip: player command → Wyrdsekai → zone bridge → CodePlane → response.
# Supports local (same machine) and cross-machine (home-server→gpu-host) topologies.
#
# Usage:
#   ./scripts/test-integration.sh --protocol-only
#       Zone bridge protocol tests only (no external services needed)
#
#   ./scripts/test-integration.sh --codeplane-url=http://gpu-host:8080
#       Full integration against a running CodePlane instance
#
#   ./scripts/test-integration.sh --wyrdsekai-url=http://home-server:7070 --codeplane-url=http://gpu-host:8080
#       Cross-machine: test runs here, Wyrdsekai on home-server, CodePlane on gpu-host
#
#   ./scripts/test-integration.sh
#       Auto-detect: local Wyrdsekai (TestServerBootstrap), CodePlane at ../codeplane or ~/src/codeplane
#
# Environment variables (override flags):
#   WYRDSEKAI_URL       — base URL of running Wyrdsekai server
#   CODEPLANE_URL       — base URL of running CodePlane server
#   CODEPLANE_DIR       — path to CodePlane repo (for auto-start)
#   INTEGRATION_WORKSPACE — workspace path for codeplane.create tests (default: temp dir)
#
# Requirements:
#   - Java 21+ (for Gradle)
#   - Wyrdsekai built (./gradlew :server:jar) — unless WYRDSEKAI_URL set
#   - CodePlane running — unless --protocol-only
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration (env vars as defaults, flags override) ---
WYRDSEKAI_URL="${WYRDSEKAI_URL:-}"
CODEPLANE_URL="${CODEPLANE_URL:-}"
CODEPLANE_DIR="${CODEPLANE_DIR:-}"
INTEGRATION_WORKSPACE="${INTEGRATION_WORKSPACE:-}"
PROTOCOL_ONLY=false
CODEPLANE_PID=""

# --- Parse args ---
for arg in "$@"; do
    case "$arg" in
        --wyrdsekai-url=*) WYRDSEKAI_URL="${arg#*=}" ;;
        --codeplane-dir=*) CODEPLANE_DIR="${arg#*=}" ;;
        --codeplane-url=*) CODEPLANE_URL="${arg#*=}" ;;
        --workspace=*)     INTEGRATION_WORKSPACE="${arg#*=}" ;;
        --protocol-only)   PROTOCOL_ONLY=true ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^#//' | sed 's/^ //'
            exit 0
            ;;
        *) echo "Unknown arg: $arg"; exit 1 ;;
    esac
done

# --- Colors ---
if [ -t 1 ]; then
    RED='\033[0;31m' GREEN='\033[0;32m' YELLOW='\033[1;33m' CYAN='\033[0;36m' NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' CYAN='' NC=''
fi

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

cleanup() {
    if [ -n "$CODEPLANE_PID" ] && kill -0 "$CODEPLANE_PID" 2>/dev/null; then
        info "Stopping CodePlane (PID $CODEPLANE_PID)..."
        kill "$CODEPLANE_PID" 2>/dev/null || true
        wait "$CODEPLANE_PID" 2>/dev/null || true
        ok "CodePlane stopped"
    fi
    # Clean up temp workspace if we created one
    if [ -n "$_TEMP_WORKSPACE" ] && [ -d "$_TEMP_WORKSPACE" ]; then
        rm -rf "$_TEMP_WORKSPACE"
    fi
}
trap cleanup EXIT

# --- Workspace for codeplane.create tests ---
if [ -z "$INTEGRATION_WORKSPACE" ]; then
    _TEMP_WORKSPACE="$(mktemp -d)"
    INTEGRATION_WORKSPACE="$_TEMP_WORKSPACE"
    info "Created temp workspace: $INTEGRATION_WORKSPACE"
else
    _TEMP_WORKSPACE=""
fi

# --- Step 1: Protocol-only tests (always run) ---
echo ""
echo "============================================"
echo "  Integration Tests: Zone Bridge Protocol"
echo "============================================"
echo ""

# Pass WYRDSEKAI_URL if set (test uses remote server instead of TestServerBootstrap)
GRADLE_ENV=""
[ -n "$WYRDSEKAI_URL" ] && GRADLE_ENV="WYRDSEKAI_URL=$WYRDSEKAI_URL"

info "Running zone bridge protocol tests..."
if env $GRADLE_ENV ./gradlew :e2e-test:test --tests "*ZoneBridgeExternalTest" \
    -PincludeTags=integration-external --rerun -q 2>&1 | tail -10; then
    ok "Zone bridge protocol tests passed"
else
    fail "Zone bridge protocol tests failed"
    exit 1
fi

if [ "$PROTOCOL_ONLY" = true ]; then
    echo ""
    ok "Protocol-only mode — skipping CodePlane integration"
    exit 0
fi

# --- Step 2: Ensure CodePlane is available ---
if [ -z "$CODEPLANE_URL" ]; then
    # Auto-detect CodePlane repo
    if [ -z "$CODEPLANE_DIR" ]; then
        for candidate in "$PROJECT_DIR/../codeplane" "$HOME/src/codeplane"; do
            if [ -d "$candidate" ] && [ -f "$candidate/gradlew" ]; then
                CODEPLANE_DIR="$(cd "$candidate" && pwd)"
                break
            fi
        done
    fi

    if [ -z "$CODEPLANE_DIR" ]; then
        warn "CodePlane not found. Tried ../codeplane and ~/src/codeplane"
        warn "Use --codeplane-url=URL or --codeplane-dir=PATH"
        warn "Skipping CodePlane integration tests"
        exit 0
    fi

    info "Found CodePlane at: $CODEPLANE_DIR"

    # Build if needed
    if ! ls "$CODEPLANE_DIR"/core/build/libs/*.jar >/dev/null 2>&1; then
        info "Building CodePlane..."
        (cd "$CODEPLANE_DIR" && ./gradlew :core:jar -q 2>&1) || {
            fail "CodePlane build failed"
            exit 1
        }
    fi

    # Determine which Wyrdsekai URL CodePlane should connect to
    CP_WYRDSEKAI_TARGET="${WYRDSEKAI_URL:-http://localhost:7070}"

    # Start CodePlane
    CODEPLANE_PORT="${CODEPLANE_PORT:-9090}"
    info "Starting CodePlane on port $CODEPLANE_PORT, zone bridge → $CP_WYRDSEKAI_TARGET..."
    (cd "$CODEPLANE_DIR" && \
        WYRDSEKAI_URL="$CP_WYRDSEKAI_TARGET" \
        CODEPLANE_PORT="$CODEPLANE_PORT" \
        ./gradlew :core:run -q 2>&1 &)
    CODEPLANE_PID=$!

    CODEPLANE_URL="http://localhost:$CODEPLANE_PORT"
    info "Waiting for CodePlane health at $CODEPLANE_URL..."
    for i in $(seq 1 60); do
        if curl -sf "$CODEPLANE_URL/health" > /dev/null 2>&1; then
            ok "CodePlane ready after ${i}s"
            break
        fi
        if [ "$i" -eq 60 ]; then
            fail "CodePlane failed to start within 60s"
            exit 1
        fi
        sleep 1
    done
else
    info "Using running CodePlane at: $CODEPLANE_URL"
    if ! curl -sf "$CODEPLANE_URL/health" > /dev/null 2>&1; then
        fail "CodePlane at $CODEPLANE_URL is not responding"
        exit 1
    fi
    ok "CodePlane healthy"
fi

# --- Step 3: Run full integration tests ---
echo ""
echo "============================================"
echo "  Integration Tests: CodePlane Round-Trip"
echo "============================================"
echo ""

info "Topology:"
info "  Wyrdsekai:  ${WYRDSEKAI_URL:-local (TestServerBootstrap)}"
info "  CodePlane:  $CODEPLANE_URL"
info "  Workspace:  $INTEGRATION_WORKSPACE"
echo ""

info "Running CodePlane integration tests..."
export CODEPLANE_URL
export INTEGRATION_WORKSPACE
[ -n "$WYRDSEKAI_URL" ] && export WYRDSEKAI_URL

if ./gradlew :e2e-test:test --tests "*ZoneBridgeExternalTest" \
    -PincludeTags=integration-external --rerun -q 2>&1 | tail -15; then
    ok "CodePlane integration tests passed"
else
    fail "CodePlane integration tests failed"
    exit 1
fi

# --- Report ---
echo ""
echo "============================================"
echo "  All Integration Tests Passed"
echo "============================================"
echo ""
ok "Zone bridge protocol: PASS"
ok "CodePlane round-trip: PASS"
info "Topology: ${WYRDSEKAI_URL:-local} ↔ $CODEPLANE_URL"
echo ""
