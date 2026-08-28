#!/usr/bin/env bash
#
# Cross-project integration tests: Wyrdsekai ↔ CodeZaiku zone bridge.
#
# Tests the full round-trip: player command → Wyrdsekai → zone bridge → CodeZaiku → response.
# Supports local (same machine) and cross-machine (home-server→gpu-host) topologies.
#
# Usage:
#   ./scripts/test-integration.sh --protocol-only
#       Zone bridge protocol tests only (no external services needed)
#
#   ./scripts/test-integration.sh --codezaiku-url=http://gpu-host:8080
#       Full integration against a running CodeZaiku instance
#
#   ./scripts/test-integration.sh --wyrdsekai-url=http://home-server:7070 --codezaiku-url=http://gpu-host:8080
#       Cross-machine: test runs here, Wyrdsekai on home-server, CodeZaiku on gpu-host
#
#   ./scripts/test-integration.sh
#       Auto-detect: local Wyrdsekai (TestServerBootstrap), CodeZaiku at ../codezaiku or ~/src/codezaiku
#
# Environment variables (override flags):
#   WYRDSEKAI_URL       — base URL of running Wyrdsekai server
#   CODEZAIKU_URL       — base URL of running CodeZaiku server
#   CODEZAIKU_DIR       — path to CodeZaiku repo (for auto-start)
#   INTEGRATION_WORKSPACE — workspace path for codezaiku.create tests (default: temp dir)
#
# Requirements:
#   - Java 21+ (for Gradle)
#   - Wyrdsekai built (./gradlew :server:jar) — unless WYRDSEKAI_URL set
#   - CodeZaiku running — unless --protocol-only
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration (env vars as defaults, flags override) ---
WYRDSEKAI_URL="${WYRDSEKAI_URL:-}"
CODEZAIKU_URL="${CODEZAIKU_URL:-}"
CODEZAIKU_DIR="${CODEZAIKU_DIR:-}"
INTEGRATION_WORKSPACE="${INTEGRATION_WORKSPACE:-}"
PROTOCOL_ONLY=false
CODEZAIKU_PID=""

# --- Parse args ---
for arg in "$@"; do
    case "$arg" in
        --wyrdsekai-url=*) WYRDSEKAI_URL="${arg#*=}" ;;
        --codezaiku-dir=*) CODEZAIKU_DIR="${arg#*=}" ;;
        --codezaiku-url=*) CODEZAIKU_URL="${arg#*=}" ;;
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
    if [ -n "$CODEZAIKU_PID" ] && kill -0 "$CODEZAIKU_PID" 2>/dev/null; then
        info "Stopping CodeZaiku (PID $CODEZAIKU_PID)..."
        kill "$CODEZAIKU_PID" 2>/dev/null || true
        wait "$CODEZAIKU_PID" 2>/dev/null || true
        ok "CodeZaiku stopped"
    fi
    # Clean up temp workspace if we created one
    if [ -n "$_TEMP_WORKSPACE" ] && [ -d "$_TEMP_WORKSPACE" ]; then
        rm -rf "$_TEMP_WORKSPACE"
    fi
}
trap cleanup EXIT

# --- Workspace for codezaiku.create tests ---
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
    ok "Protocol-only mode — skipping CodeZaiku integration"
    exit 0
fi

# --- Step 2: Ensure CodeZaiku is available ---
if [ -z "$CODEZAIKU_URL" ]; then
    # Auto-detect CodeZaiku repo
    if [ -z "$CODEZAIKU_DIR" ]; then
        for candidate in "$PROJECT_DIR/../codezaiku" "$HOME/src/codezaiku"; do
            if [ -d "$candidate" ] && [ -f "$candidate/gradlew" ]; then
                CODEZAIKU_DIR="$(cd "$candidate" && pwd)"
                break
            fi
        done
    fi

    if [ -z "$CODEZAIKU_DIR" ]; then
        warn "CodeZaiku not found. Tried ../codezaiku and ~/src/codezaiku"
        warn "Use --codezaiku-url=URL or --codezaiku-dir=PATH"
        warn "Skipping CodeZaiku integration tests"
        exit 0
    fi

    info "Found CodeZaiku at: $CODEZAIKU_DIR"

    # Build if needed
    if ! ls "$CODEZAIKU_DIR"/core/build/libs/*.jar >/dev/null 2>&1; then
        info "Building CodeZaiku..."
        (cd "$CODEZAIKU_DIR" && ./gradlew :core:jar -q 2>&1) || {
            fail "CodeZaiku build failed"
            exit 1
        }
    fi

    # Determine which Wyrdsekai URL CodeZaiku should connect to
    CP_WYRDSEKAI_TARGET="${WYRDSEKAI_URL:-http://localhost:7070}"

    # Start CodeZaiku
    CODEZAIKU_PORT="${CODEZAIKU_PORT:-9090}"
    info "Starting CodeZaiku on port $CODEZAIKU_PORT, zone bridge → $CP_WYRDSEKAI_TARGET..."
    (cd "$CODEZAIKU_DIR" && \
        WYRDSEKAI_URL="$CP_WYRDSEKAI_TARGET" \
        CODEZAIKU_PORT="$CODEZAIKU_PORT" \
        ./gradlew :core:run -q 2>&1 &)
    CODEZAIKU_PID=$!

    CODEZAIKU_URL="http://localhost:$CODEZAIKU_PORT"
    info "Waiting for CodeZaiku health at $CODEZAIKU_URL..."
    for i in $(seq 1 60); do
        if curl -sf "$CODEZAIKU_URL/health" > /dev/null 2>&1; then
            ok "CodeZaiku ready after ${i}s"
            break
        fi
        if [ "$i" -eq 60 ]; then
            fail "CodeZaiku failed to start within 60s"
            exit 1
        fi
        sleep 1
    done
else
    info "Using running CodeZaiku at: $CODEZAIKU_URL"
    if ! curl -sf "$CODEZAIKU_URL/health" > /dev/null 2>&1; then
        fail "CodeZaiku at $CODEZAIKU_URL is not responding"
        exit 1
    fi
    ok "CodeZaiku healthy"
fi

# --- Step 3: Run full integration tests ---
echo ""
echo "============================================"
echo "  Integration Tests: CodeZaiku Round-Trip"
echo "============================================"
echo ""

info "Topology:"
info "  Wyrdsekai:  ${WYRDSEKAI_URL:-local (TestServerBootstrap)}"
info "  CodeZaiku:  $CODEZAIKU_URL"
info "  Workspace:  $INTEGRATION_WORKSPACE"
echo ""

info "Running CodeZaiku integration tests..."
export CODEZAIKU_URL
export INTEGRATION_WORKSPACE
[ -n "$WYRDSEKAI_URL" ] && export WYRDSEKAI_URL

if ./gradlew :e2e-test:test --tests "*ZoneBridgeExternalTest" \
    -PincludeTags=integration-external --rerun -q 2>&1 | tail -15; then
    ok "CodeZaiku integration tests passed"
else
    fail "CodeZaiku integration tests failed"
    exit 1
fi

# --- Report ---
echo ""
echo "============================================"
echo "  All Integration Tests Passed"
echo "============================================"
echo ""
ok "Zone bridge protocol: PASS"
ok "CodeZaiku round-trip: PASS"
info "Topology: ${WYRDSEKAI_URL:-local} ↔ $CODEZAIKU_URL"
echo ""
