#!/usr/bin/env bash
#
# Autonomy live tests -- requires real inference backend (Ollama).
# Handles Ollama lifecycle, model pulling, and graceful skip.
#
# Usage:
#   ./scripts/test-autonomy-live.sh              # Auto-detect Ollama
#   ./scripts/test-autonomy-live.sh --skip-pull   # Skip model pull
#
# Requirements: Docker (for Ollama if not running), Java 21+, Gradle

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration ---
OLLAMA_PORT="${OLLAMA_PORT:-11434}"
OLLAMA_URL="http://localhost:${OLLAMA_PORT}"
PRIMARY_MODEL="qwen2.5:7b"
EMBEDDING_MODEL="all-minilm"
CONTAINER_NAME="wyrdsekai-test-ollama"

# --- Parse args ---
SKIP_PULL=false
for arg in "$@"; do
    case "$arg" in
        --skip-pull) SKIP_PULL=true ;;
        --help|-h)
            echo "Usage: $0 [--skip-pull]"
            echo "  --skip-pull  Skip model pulling (if models already available)"
            exit 0
            ;;
        *) echo "Unknown arg: $arg"; exit 1 ;;
    esac
done

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

# --- Step 1: Ensure Ollama is running ---
info "Checking Ollama at ${OLLAMA_URL}..."

OLLAMA_MANAGED=false
if curl -sf "${OLLAMA_URL}/api/tags" > /dev/null 2>&1; then
    ok "Ollama already running at ${OLLAMA_URL}"
else
    info "Ollama not running. Starting Docker container..."
    if ! command -v docker &> /dev/null; then
        fail "Docker not found. Install Docker or start Ollama manually."
        exit 1
    fi

    # Check if container exists but stopped
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        docker start "$CONTAINER_NAME" > /dev/null 2>&1 || true
    else
        # Detect GPU
        GPU_FLAG=""
        if command -v nvidia-smi &> /dev/null && nvidia-smi > /dev/null 2>&1; then
            GPU_FLAG="--gpus all"
            info "NVIDIA GPU detected -- using GPU acceleration"
        else
            warn "No NVIDIA GPU detected -- Ollama will use CPU (slower)"
        fi

        docker run -d \
            --name "$CONTAINER_NAME" \
            ${GPU_FLAG} \
            -p "${OLLAMA_PORT}:11434" \
            -v ollama-test-data:/root/.ollama \
            ollama/ollama:latest > /dev/null
    fi

    OLLAMA_MANAGED=true

    # Wait for Ollama to be ready
    info "Waiting for Ollama to start..."
    for i in $(seq 1 30); do
        if curl -sf "${OLLAMA_URL}/api/tags" > /dev/null 2>&1; then
            ok "Ollama ready after ${i}s"
            break
        fi
        if [ "$i" -eq 30 ]; then
            fail "Ollama failed to start within 30s"
            docker logs "$CONTAINER_NAME" 2>&1 | tail -10
            exit 1
        fi
        sleep 1
    done
fi

# --- Step 2: Pull required models ---
if [ "$SKIP_PULL" = false ]; then
    info "Ensuring models are available..."

    for model in "$PRIMARY_MODEL" "$EMBEDDING_MODEL"; do
        # Check if model exists
        if curl -sf "${OLLAMA_URL}/api/tags" | python3 -c "
import sys, json
models = [m['name'] for m in json.load(sys.stdin).get('models', [])]
sys.exit(0 if '$model' in models or '${model}:latest' in models else 1)
" 2>/dev/null; then
            ok "Model $model already available"
        else
            info "Pulling $model (this may take a few minutes)..."
            if [ "$OLLAMA_MANAGED" = true ]; then
                docker exec "$CONTAINER_NAME" ollama pull "$model"
            else
                curl -sf "${OLLAMA_URL}/api/pull" -d "{\"name\": \"$model\"}" \
                    --no-buffer 2>&1 | python3 -c "
import sys, json
for line in sys.stdin:
    try:
        d = json.loads(line)
        if 'status' in d:
            s = d['status']
            if 'total' in d and 'completed' in d:
                pct = int(100 * d['completed'] / d['total'])
                print(f'\r  {s} {pct}%', end='', flush=True)
            elif s == 'success':
                print(f'\r  {s}           ')
    except: pass
print()
"
            fi
            ok "Pulled $model"
        fi
    done
else
    warn "Skipping model pull (--skip-pull)"
fi

# --- Step 3: Export env vars ---
export SOUL_EXPERIMENT_URL="${OLLAMA_URL}/v1"
export SOUL_EXPERIMENT_MODEL="$PRIMARY_MODEL"
export SOUL_EMBEDDING_URL="$OLLAMA_URL"
export SOUL_EMBEDDING_MODEL="$EMBEDDING_MODEL"

info "Environment configured:"
info "  SOUL_EXPERIMENT_URL=${SOUL_EXPERIMENT_URL}"
info "  SOUL_EXPERIMENT_MODEL=${SOUL_EXPERIMENT_MODEL}"
info "  SOUL_EMBEDDING_URL=${SOUL_EMBEDDING_URL}"

echo ""
echo "============================================"
echo "  Autonomy Live E2E Test Suite"
echo "============================================"
echo ""

# --- Step 4: Run tests ---
info "Running autonomy-live tests..."

TEST_EXIT=0
./gradlew :e2e-test:test -PincludeTags=autonomy-live --rerun 2>&1 | tee /tmp/autonomy-live-output.txt || TEST_EXIT=$?

# --- Step 5: Report ---
echo ""
echo "============================================"
echo "  Results"
echo "============================================"
echo ""

# Extract test counts from Gradle output
TESTS_RUN=$(grep -oP '\d+ tests?' /tmp/autonomy-live-output.txt 2>/dev/null | head -1 || echo "unknown")
TESTS_FAILED=$(grep -oP '\d+ failed' /tmp/autonomy-live-output.txt 2>/dev/null | head -1 || echo "0 failed")
TESTS_SKIPPED=$(grep -oP '\d+ skipped' /tmp/autonomy-live-output.txt 2>/dev/null | head -1 || echo "0 skipped")

if [ "$TEST_EXIT" -eq 0 ]; then
    ok "All tests passed ($TESTS_RUN)"
else
    fail "Some tests failed ($TESTS_FAILED of $TESTS_RUN)"
fi

if echo "$TESTS_SKIPPED" | grep -qP '[1-9]'; then
    warn "Skipped: $TESTS_SKIPPED"
fi

# --- Cleanup info ---
if [ "$OLLAMA_MANAGED" = true ]; then
    info "Ollama container '${CONTAINER_NAME}' left running. Stop with: docker stop ${CONTAINER_NAME}"
fi

rm -f /tmp/autonomy-live-output.txt
exit "$TEST_EXIT"
