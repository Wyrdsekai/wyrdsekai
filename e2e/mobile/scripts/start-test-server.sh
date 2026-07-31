#!/usr/bin/env bash
# =============================================================================
# Start Wyrdsekai server in test mode for E2E testing
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

PORT="${WYRDSEKAI_E2E_PORT:-7099}"
DB_PATH="/tmp/wyrdsekai-e2e-test.db"
KEY_FILE="/tmp/wyrdsekai-e2e-key"
PID_FILE="/tmp/wyrdsekai-e2e-server.pid"
LOG_FILE="/tmp/wyrdsekai-e2e-server.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[*]${NC} $1"; }
ok()    { echo -e "${GREEN}[+]${NC} $1"; }
fail()  { echo -e "${RED}[-]${NC} $1"; exit 1; }

cleanup() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            info "Stopping test server (PID $pid)..."
            kill "$pid" 2>/dev/null || true
            sleep 2
            kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
        fi
        rm -f "$PID_FILE"
    fi
    rm -f "$DB_PATH" "$KEY_FILE"
}

# Handle stop command
if [ "${1:-}" = "stop" ]; then
    cleanup
    ok "Test server stopped"
    exit 0
fi

# --skip-build: reuse existing build/install/server. Required when prod
# wyrdsekai is also running on this host (the installDist guard refuses
# to rewrite lib/*.jar under a live JVM).
SKIP_BUILD=false
if [ "${1:-}" = "--skip-build" ]; then
    SKIP_BUILD=true
fi

# Kill any existing test server
cleanup

cd "$PROJECT_ROOT"

# Ensure JDK 25 is on PATH — gradle build needs it. On hosts where the
# system default-java is older (e.g. dev-laptop ships with 21 default), look
# for a 25 install under /usr/lib/jvm and pin JAVA_HOME for this build.
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

if [ "$SKIP_BUILD" = true ]; then
    info "Skipping build (using existing build/install/server)"
    if [ ! -x "$PROJECT_ROOT/server/build/install/server/bin/server" ]; then
        fail "build/install/server/bin/server missing — drop --skip-build for fresh build"
    fi
else
    info "Building server..."
    ./gradlew :server:installDist --no-daemon 2>&1 | tail -3
fi

# Start server in test mode
info "Starting server on port $PORT (test mode)..."
rm -f "$DB_PATH"

export WYRDSEKAI_TEST_MODE=true
export WYRDSEKAI_PORT="$PORT"
export WYRDSEKAI_HOSTNAME="0.0.0.0"
export WYRDSEKAI_LOG_DIR="/tmp"

# Start in background
"$PROJECT_ROOT/server/build/install/server/bin/server" > "$LOG_FILE" 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$PID_FILE"

# Wait for server to be ready
info "Waiting for server..."
WAITED=0
while [ "$WAITED" -lt 60 ]; do
    if curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; then
        ok "Server ready on port $PORT (PID $SERVER_PID, ${WAITED}s)"

        # Generate household key for pairing bypass
        KEY_RESPONSE=$(curl -sf -X POST "http://localhost:$PORT/api/pair/household-key/generate" 2>/dev/null || echo "")
        if [ -n "$KEY_RESPONSE" ]; then
            HOUSEHOLD_KEY=$(echo "$KEY_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('key',''))" 2>/dev/null || echo "")
            if [ -n "$HOUSEHOLD_KEY" ]; then
                echo "$HOUSEHOLD_KEY" > "$KEY_FILE"
                ok "Household key: $HOUSEHOLD_KEY"
            fi
        fi

        # Create test user
        curl -sf -X POST "http://localhost:$PORT/api/auth/register" \
            -H "Content-Type: application/json" \
            -d '{"username":"e2e_test","password":"testpass123","displayName":"E2E Tester"}' > /dev/null 2>&1 || true
        ok "Test user created: e2e_test / testpass123"

        echo ""
        ok "Test server ready. Stop with: $0 stop"
        echo "  Server: http://localhost:$PORT"
        echo "  Emulator: http://10.0.2.2:$PORT"
        echo "  Log: $LOG_FILE"
        [ -f "$KEY_FILE" ] && echo "  Household key: $(cat "$KEY_FILE")"
        exit 0
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

fail "Server did not start within 60s. Check: $LOG_FILE"
