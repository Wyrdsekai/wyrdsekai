#!/usr/bin/env bash
#
# deploy.sh — Build, install, and restart Wyrdsekai on the current machine.
#
# Usage:
#   ./scripts/deploy.sh              # full deploy (stop → build → install → start)
#   ./scripts/deploy.sh --build-only # build and install, don't restart
#   ./scripts/deploy.sh --restart    # restart without rebuilding
#
# Environment variables (set in ~/.wyrdsekai/env or export before running):
#   ORACLE_URL          — oracle-core server URL (default: http://localhost:7073)
#   WYRDSEKAI_LLAMA_URL — inference backend URL (default: http://localhost:8080)
#   WYRDSEKAI_LLAMA_ENABLED — enable inference (default: true)
#
set -euo pipefail

INSTALL_DIR="${HOME}/.wyrdsekai"
ENV_FILE="${INSTALL_DIR}/env"
LOG_DIR="/tmp"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[deploy]${NC} $*"; }
warn() { echo -e "${YELLOW}[deploy]${NC} $*"; }
err()  { echo -e "${RED}[deploy]${NC} $*" >&2; }

# ── Load env file if present ──────────────────────────────────────────
if [[ -f "${ENV_FILE}" ]]; then
    log "Loading environment from ${ENV_FILE}"
    set -a
    source "${ENV_FILE}"
    set +a
fi

# Defaults
: "${ORACLE_URL:=http://localhost:7073}"
: "${WYRDSEKAI_LLAMA_URL:=http://localhost:8080}"
: "${WYRDSEKAI_LLAMA_ENABLED:=true}"

# ── Functions ─────────────────────────────────────────────────────────

stop_server() {
    local pid
    pid=$(pgrep -f 'org.wyrdsekai.server.Main' 2>/dev/null || true)
    if [[ -n "${pid}" ]]; then
        log "Stopping server (PID ${pid})..."
        kill "${pid}"
        # Wait for clean shutdown (up to 10s)
        for i in $(seq 1 10); do
            if ! kill -0 "${pid}" 2>/dev/null; then
                log "Server stopped."
                return 0
            fi
            sleep 1
        done
        warn "Server didn't stop cleanly, forcing..."
        kill -9 "${pid}" 2>/dev/null || true
    else
        log "Server not running."
    fi
}

check_oracle() {
    if curl -sf "${ORACLE_URL}/health" >/dev/null 2>&1; then
        log "Oracle: ✓ running at ${ORACLE_URL}"
        return 0
    else
        warn "Oracle: not running at ${ORACLE_URL}"
        return 1
    fi
}

check_searxng() {
    local url="${WYRDSEKAI_SEARXNG_URL:-http://localhost:8888}"
    if curl -sf "${url}/healthz" >/dev/null 2>&1; then
        log "Searxng: ✓ running at ${url}"
        return 0
    else
        warn "Searxng: not running (web search will use DuckDuckGo fallback)"
        warn "  Start: docker run -d --name searxng -p 8888:8080 -v ./docker/searxng-settings.yml:/etc/searxng/settings.yml:ro searxng/searxng"
        return 1
    fi
}

check_inference() {
    if curl -sf "${WYRDSEKAI_LLAMA_URL}/health" >/dev/null 2>&1; then
        log "Inference: ✓ running at ${WYRDSEKAI_LLAMA_URL}"
        return 0
    elif curl -sf "${WYRDSEKAI_LLAMA_URL}/v1/models" >/dev/null 2>&1; then
        log "Inference: ✓ running at ${WYRDSEKAI_LLAMA_URL}"
        return 0
    else
        warn "Inference: not running at ${WYRDSEKAI_LLAMA_URL}"
        return 1
    fi
}

start_oracle() {
    if check_oracle; then return 0; fi
    log "Starting oracle-core server..."
    local oracle_dir="${HOME}/src/oracle-core"
    if [[ ! -d "${oracle_dir}" ]]; then
        err "oracle-core not found at ${oracle_dir}"
        return 1
    fi
    cd "${oracle_dir}"
    nohup uv run oracle-server --port 7073 --data-dir ~/.oracle-core > "${LOG_DIR}/oracle-server.log" 2>&1 &
    sleep 2
    if check_oracle; then
        log "Oracle started."
    else
        warn "Oracle may still be starting — check ${LOG_DIR}/oracle-server.log"
    fi
}

start_inference() {
    if check_inference; then return 0; fi
    # Try Ollama first (stable, works everywhere)
    if command -v ollama >/dev/null 2>&1 || [[ -f /usr/local/bin/ollama ]]; then
        local ollama_cmd="ollama"
        [[ -f /usr/local/bin/ollama ]] && ollama_cmd="/usr/local/bin/ollama"
        log "Starting Ollama..."
        nohup "${ollama_cmd}" serve > "${LOG_DIR}/ollama.log" 2>&1 &
        sleep 3
        # Ensure model is available
        if ! "${ollama_cmd}" list 2>/dev/null | grep -q "qwen3"; then
            log "Pulling qwen3:8b model..."
            "${ollama_cmd}" pull qwen3:8b
        fi
        if check_inference; then
            log "Ollama started."
        else
            warn "Ollama may still be starting — check ${LOG_DIR}/ollama.log"
        fi
        return 0
    fi
    warn "No inference backend found. Install Ollama: curl -fsSL https://ollama.com/install.sh | sh"
}

build_server() {
    log "Building server..."
    cd "${PROJECT_DIR}"
    ./gradlew :server:installDist 2>&1 | tail -3
    if [[ $? -ne 0 ]]; then
        err "Build failed!"
        exit 1
    fi
    log "Build successful."
}

install_server() {
    log "Installing to ${INSTALL_DIR}..."
    mkdir -p "${INSTALL_DIR}"
    cp -r "${PROJECT_DIR}/server/build/install/server/"* "${INSTALL_DIR}/"
    log "Installed."
}

start_server() {
    log "Starting Wyrdsekai server..."
    export ORACLE_URL WYRDSEKAI_LLAMA_URL WYRDSEKAI_LLAMA_ENABLED
    cd "${INSTALL_DIR}"
    nohup bin/server > "${LOG_DIR}/wyrdsekai-server.log" 2>&1 &
    sleep 3
    if pgrep -f 'org.wyrdsekai.server.Main' >/dev/null 2>&1; then
        local pid
        pid=$(pgrep -f 'org.wyrdsekai.server.Main')
        log "Server started (PID ${pid})."
        log "  HTTP:   http://localhost:7070"
        log "  Telnet: localhost:7071"
        log "  SSH:    localhost:7022"
        log "  Log:    ${LOG_DIR}/wyrdsekai-server.log"
    else
        err "Server failed to start! Check ${LOG_DIR}/wyrdsekai-server.log"
        tail -20 "${LOG_DIR}/wyrdsekai-server.log"
        exit 1
    fi
}

health_check() {
    log "Running health check..."
    sleep 2
    local health
    health=$(curl -sf http://localhost:7070/health 2>/dev/null || echo '{"error":"unreachable"}')
    echo "${health}" | python3 -m json.tool 2>/dev/null || echo "${health}"
}

# ── Main ──────────────────────────────────────────────────────────────

case "${1:-full}" in
    --build-only)
        build_server
        install_server
        log "Build and install complete. Run './scripts/deploy.sh --restart' to restart."
        ;;
    --restart)
        stop_server
        start_oracle
        start_inference
        start_server
        health_check
        ;;
    --stop)
        stop_server
        ;;
    --install-service)
        if [[ "$(uname)" == "Darwin" ]]; then
            local plist="${PROJECT_DIR}/scripts/com.wyrdsekai.server.plist"
            local dest="${HOME}/Library/LaunchAgents/com.wyrdsekai.server.plist"
            cp "${plist}" "${dest}"
            # Substitute home dir
            sed -i '' "s|/Users/you|${HOME}|g" "${dest}"
            launchctl load "${dest}"
            log "macOS LaunchAgent installed. Server will auto-restart on crash and start on login."
            log "  Uninstall: $0 --uninstall-service"
        else
            local service="${PROJECT_DIR}/scripts/wyrdsekai.service"
            sudo cp "${service}" /etc/systemd/system/wyrdsekai@.service
            sudo systemctl daemon-reload
            sudo systemctl enable "wyrdsekai@${USER}"
            sudo systemctl start "wyrdsekai@${USER}"
            log "systemd service installed. Server will auto-restart on crash."
            log "  Status: sudo systemctl status wyrdsekai@${USER}"
            log "  Uninstall: $0 --uninstall-service"
        fi
        ;;
    --uninstall-service)
        if [[ "$(uname)" == "Darwin" ]]; then
            local dest="${HOME}/Library/LaunchAgents/com.wyrdsekai.server.plist"
            launchctl unload "${dest}" 2>/dev/null
            rm -f "${dest}"
            log "macOS LaunchAgent removed."
        else
            sudo systemctl stop "wyrdsekai@${USER}" 2>/dev/null
            sudo systemctl disable "wyrdsekai@${USER}" 2>/dev/null
            sudo rm -f /etc/systemd/system/wyrdsekai@.service
            sudo systemctl daemon-reload
            log "systemd service removed."
        fi
        ;;
    --status)
        check_oracle || true
        check_inference || true
        check_searxng || true
        if pgrep -f 'org.wyrdsekai.server.Main' >/dev/null 2>&1; then
            log "Server: ✓ running (PID $(pgrep -f 'org.wyrdsekai.server.Main'))"
            health_check
        else
            warn "Server: not running"
        fi
        ;;
    full)
        stop_server
        build_server
        install_server
        check_searxng || true
        start_oracle
        start_inference
        start_server
        health_check
        log "Deploy complete."
        ;;
    --help|-h|"")
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  full              Stop → build → install → start (full deploy)"
        echo "  --build-only      Build and install, don't restart"
        echo "  --restart         Restart without rebuilding"
        echo "  --stop            Stop the server"
        echo "  --status          Show server, oracle, and inference status"
        echo "  --install-service Install systemd/launchd service for auto-restart"
        echo "  --uninstall-service  Remove auto-restart service"
        echo ""
        echo "Examples:"
        echo "  $0 full                  # Full deploy"
        echo "  $0 --restart             # Quick restart"
        echo "  $0 --status              # Check what's running"
        echo "  $0 --install-service     # Enable crash recovery"
        exit 0
        ;;
    *)
        echo "Unknown command: $1"
        echo "Run '$0 --help' for usage."
        exit 1
        ;;
esac
