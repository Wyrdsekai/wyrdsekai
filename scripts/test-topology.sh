#!/usr/bin/env bash
#
# Topology E2E tests — multi-node room gossip, failover, presence via real NATS.
#
# Handles Docker NATS lifecycle automatically. Tests skip gracefully if neither
# Docker nor nats-server binary is available.
#
# Usage:
#   ./scripts/test-topology.sh          # Run all topology tests
#   ./scripts/test-topology.sh --skip-nats-cleanup   # Keep NATS running after tests
#   ./scripts/test-topology.sh --verbose              # Show full Gradle output
#
# Environment:
#   WYRDSEKAI_E2E_NATS=docker   Force Docker mode for NATS
#   WYRDSEKAI_E2E_NATS=local    Force local nats-server binary
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration ---
SKIP_NATS_CLEANUP=false
VERBOSE=false
NATS_CONTAINER_NAME="wyrdsekai-e2e-nats-topo"
NATS_PORT=""
NATS_MONITOR_PORT=""
NATS_PID=""
STARTED_DOCKER=false
STARTED_LOCAL=false

# --- Parse args ---
for arg in "$@"; do
    case "$arg" in
        --skip-nats-cleanup) SKIP_NATS_CLEANUP=true ;;
        --verbose)           VERBOSE=true ;;
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
    if [ "$SKIP_NATS_CLEANUP" = true ]; then
        info "Skipping NATS cleanup (--skip-nats-cleanup)"
        return
    fi

    if [ "$STARTED_DOCKER" = true ]; then
        info "Stopping Docker NATS container ($NATS_CONTAINER_NAME)..."
        docker rm -f "$NATS_CONTAINER_NAME" 2>/dev/null || true
        ok "Docker NATS stopped"
    fi

    if [ "$STARTED_LOCAL" = true ] && [ -n "$NATS_PID" ]; then
        info "Stopping local nats-server (PID $NATS_PID)..."
        kill "$NATS_PID" 2>/dev/null || true
        wait "$NATS_PID" 2>/dev/null || true
        ok "Local nats-server stopped"
    fi
}
trap cleanup EXIT

# --- Step 1: Ensure NATS is available ---
echo ""
echo "============================================"
echo "  Topology E2E Tests"
echo "============================================"
echo ""

NATS_MODE="${WYRDSEKAI_E2E_NATS:-auto}"

start_docker_nats() {
    # Allocate ephemeral ports
    NATS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()' 2>/dev/null || echo "4222")
    NATS_MONITOR_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()' 2>/dev/null || echo "8222")

    info "Starting Docker NATS on ports $NATS_PORT/$NATS_MONITOR_PORT..."
    docker rm -f "$NATS_CONTAINER_NAME" 2>/dev/null || true
    docker run -d --name "$NATS_CONTAINER_NAME" \
        -p "$NATS_PORT:4222" \
        -p "$NATS_MONITOR_PORT:8222" \
        nats:latest \
        --http_port 8222 --jetstream >/dev/null

    STARTED_DOCKER=true

    # Wait for health
    for i in $(seq 1 30); do
        if curl -sf "http://127.0.0.1:$NATS_MONITOR_PORT/healthz" > /dev/null 2>&1; then
            ok "Docker NATS ready after ${i}s"
            return 0
        fi
        sleep 1
    done
    fail "Docker NATS failed to start within 30s"
    return 1
}

start_local_nats() {
    NATS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()' 2>/dev/null || echo "4222")
    NATS_MONITOR_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()' 2>/dev/null || echo "8222")

    info "Starting local nats-server on ports $NATS_PORT/$NATS_MONITOR_PORT..."
    nats-server --port "$NATS_PORT" --http_port "$NATS_MONITOR_PORT" --jetstream &
    NATS_PID=$!
    STARTED_LOCAL=true

    for i in $(seq 1 15); do
        if curl -sf "http://127.0.0.1:$NATS_MONITOR_PORT/healthz" > /dev/null 2>&1; then
            ok "Local nats-server ready after ${i}s"
            return 0
        fi
        sleep 1
    done
    fail "Local nats-server failed to start within 15s"
    return 1
}

NATS_AVAILABLE=false

case "$NATS_MODE" in
    docker)
        if docker info >/dev/null 2>&1; then
            start_docker_nats && NATS_AVAILABLE=true
        else
            fail "Docker not available but WYRDSEKAI_E2E_NATS=docker was set"
            exit 1
        fi
        ;;
    local)
        if command -v nats-server >/dev/null 2>&1; then
            start_local_nats && NATS_AVAILABLE=true
        else
            fail "nats-server not on PATH but WYRDSEKAI_E2E_NATS=local was set"
            exit 1
        fi
        ;;
    auto)
        # Let the test framework handle NATS via NatsServerFixture.
        # The tests use assumeAvailable() which checks both Docker and local.
        # We still try to pre-start NATS here for better error reporting.
        if docker info >/dev/null 2>&1; then
            start_docker_nats && NATS_AVAILABLE=true
        elif command -v nats-server >/dev/null 2>&1; then
            start_local_nats && NATS_AVAILABLE=true
        else
            warn "Neither Docker nor nats-server available"
            warn "Tests will be skipped via JUnit assumptions"
            NATS_AVAILABLE=false
        fi
        ;;
esac

if [ "$NATS_AVAILABLE" = true ]; then
    info "NATS available at nats://127.0.0.1:$NATS_PORT (monitor: $NATS_MONITOR_PORT)"
    export WYRDSEKAI_E2E_NATS="${WYRDSEKAI_E2E_NATS:-docker}"
fi

# --- Step 2: Run topology tests ---
echo ""
info "Running topology E2E tests..."
echo ""

GRADLE_OPTS=""
if [ "$VERBOSE" = false ]; then
    GRADLE_OPTS="-q"
fi

TEST_EXIT=0
if ./gradlew :e2e-test:test -PincludeTags=topology --rerun $GRADLE_OPTS 2>&1; then
    TEST_EXIT=0
else
    TEST_EXIT=$?
fi

# --- Step 3: Report results ---
echo ""
echo "============================================"

# Check for test results XML
RESULTS_DIR="$PROJECT_DIR/e2e-test/build/test-results/test"
PASSED=0
FAILED=0
SKIPPED=0

if [ -d "$RESULTS_DIR" ]; then
    # Parse JUnit XML results
    for xml in "$RESULTS_DIR"/TEST-*.xml; do
        [ -f "$xml" ] || continue
        # Extract test counts from JUnit XML attributes
        tests_attr=$(grep -oP 'tests="\K[0-9]+' "$xml" 2>/dev/null | head -1 || echo "0")
        failures_attr=$(grep -oP 'failures="\K[0-9]+' "$xml" 2>/dev/null | head -1 || echo "0")
        skipped_attr=$(grep -oP 'skipped="\K[0-9]+' "$xml" 2>/dev/null | head -1 || echo "0")
        PASSED=$((PASSED + tests_attr - failures_attr - skipped_attr))
        FAILED=$((FAILED + failures_attr))
        SKIPPED=$((SKIPPED + skipped_attr))
    done
fi

if [ "$TEST_EXIT" -eq 0 ]; then
    echo -e "  ${GREEN}Topology Tests: PASSED${NC}"
else
    echo -e "  ${RED}Topology Tests: FAILED${NC}"
fi

echo "============================================"
echo ""
info "Results: $PASSED passed, $FAILED failed, $SKIPPED skipped"

if [ "$NATS_AVAILABLE" = true ]; then
    info "NATS: nats://127.0.0.1:$NATS_PORT"
fi

echo ""
exit "$TEST_EXIT"
