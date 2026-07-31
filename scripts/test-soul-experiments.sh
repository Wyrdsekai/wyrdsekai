#!/usr/bin/env bash
#
# Run soul experiment live tests against Ollama.
# Handles: Ollama startup, model pulling, env setup, test execution, reporting.
#
# Usage:
#   ./scripts/test-soul-experiments.sh              # Run all Ollama-compatible tests
#   ./scripts/test-soul-experiments.sh --quick       # Core tests only (~10 min)
#   ./scripts/test-soul-experiments.sh --full        # All tests including e2e Ollama (~30 min)
#   ./scripts/test-soul-experiments.sh --skip-pull   # Skip model pull (already have them)
#
# Requirements: Docker (for Ollama if not running natively), Java 21+, Gradle
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration ---
OLLAMA_PORT="${OLLAMA_PORT:-11434}"
OLLAMA_URL="http://localhost:${OLLAMA_PORT}"
PRIMARY_MODEL="qwen2.5:7b"
SECONDARY_MODEL="qwen3:0.6b"
EMBEDDING_MODEL="all-minilm"
CONTAINER_NAME="wyrdsekai-test-ollama"

# --- Parse args ---
QUICK=false
FULL=false
SKIP_PULL=false
for arg in "$@"; do
    case "$arg" in
        --quick)     QUICK=true ;;
        --full)      FULL=true ;;
        --skip-pull) SKIP_PULL=true ;;
        --help|-h)
            echo "Usage: $0 [--quick|--full] [--skip-pull]"
            echo "  --quick      Core soul tests only (~10 min)"
            echo "  --full       All tests including e2e Ollama tier (~30 min)"
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

# --- Track results ---
PASSED=0
FAILED=0
SKIPPED=0
FAILURES=()

run_test() {
    local label="$1"
    shift
    info "Running: $label"
    if "$@" 2>&1 | tail -5; then
        ok "$label"
        ((PASSED++))
    else
        fail "$label"
        FAILURES+=("$label")
        ((FAILED++))
    fi
}

# --- Step 1: Ensure Ollama is running ---
info "Checking Ollama at ${OLLAMA_URL}..."

if curl -sf "${OLLAMA_URL}/api/tags" > /dev/null 2>&1; then
    ok "Ollama already running at ${OLLAMA_URL}"
    OLLAMA_MANAGED=false
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
            info "NVIDIA GPU detected — using GPU acceleration"
        else
            warn "No NVIDIA GPU detected — Ollama will use CPU (slower)"
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

    for model in "$PRIMARY_MODEL" "$SECONDARY_MODEL" "$EMBEDDING_MODEL"; do
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
export SOUL_EXPERIMENT_MODEL_2="$SECONDARY_MODEL"
export SOUL_EXPERIMENT_MODEL_SIZE_B="7.0"
export SOUL_BASELINE_MODEL="$PRIMARY_MODEL"
export SOUL_EMBEDDING_URL="$OLLAMA_URL"
export SOUL_EMBEDDING_MODEL="$EMBEDDING_MODEL"
export SOUL_MODEL="$PRIMARY_MODEL"

info "Environment configured:"
info "  SOUL_EXPERIMENT_URL=${SOUL_EXPERIMENT_URL}"
info "  SOUL_EXPERIMENT_MODEL=${SOUL_EXPERIMENT_MODEL}"
info "  SOUL_EXPERIMENT_MODEL_2=${SOUL_EXPERIMENT_MODEL_2}"
info "  SOUL_EMBEDDING_URL=${SOUL_EMBEDDING_URL}"

echo ""
echo "============================================"
echo "  Soul Experiment Test Suite"
echo "============================================"
echo ""

# --- Step 4: Run tests ---

# Group 1: Core soul tests (always run)
info "=== Group 1: Core Soul Tests ==="

run_test "SeedForge LiveForge" \
    ./gradlew :core:test --tests "*SeedForgeTest\$LiveForge*" --rerun -q

run_test "SoulLifecycle LiveLifecycle" \
    ./gradlew :core:test --tests "*SoulLifecycleTest\$LiveLifecycle*" --rerun -q

if [ "$QUICK" = false ]; then
    # Group 2: Soul experiments (~20 min)
    info ""
    info "=== Group 2: Soul Experiments ==="

    run_test "Experiment 1: Soul Injection" \
        ./gradlew :core:test --tests "*SoulExperimentTest.live_full_experiment" --rerun -q

    run_test "Experiment 1b: Cross-Substrate" \
        ./gradlew :core:test --tests "*SoulExperimentTest.live_cross_substrate" --rerun -q

    run_test "Experiment 2: Bath Modulation" \
        ./gradlew :core:test --tests "*BathExperimentTest.live_bath_experiment" --rerun -q

    run_test "Experiment 4: Combined Bath+Soul" \
        ./gradlew :core:test --tests "*CombinedExperimentTest.live_combined_experiment" --rerun -q

    run_test "Experiment 3: Substrate Sensitivity" \
        ./gradlew :core:test --tests "*SubstrateExperimentTest.live_substrate_experiment" --rerun -q

    # Group 3: Empathy engine
    info ""
    info "=== Group 3: Empathy Engine (MirrorResonance) ==="

    run_test "MirrorResonance: Charge Detection" \
        ./gradlew :core:test --tests "*MirrorResonanceExperimentTest.live_charge_detection" --rerun -q

    run_test "MirrorResonance: Charge -> Behavior" \
        ./gradlew :core:test --tests "*MirrorResonanceExperimentTest.live_charge_behavior" --rerun -q

    run_test "MirrorResonance: Gaming Resistance" \
        ./gradlew :core:test --tests "*MirrorResonanceExperimentTest.live_gaming_resistance" --rerun -q

    run_test "MirrorResonance: Genome Divergence" \
        ./gradlew :core:test --tests "*MirrorResonanceExperimentTest.live_genome_divergence" --rerun -q
fi

if [ "$FULL" = true ]; then
    # Group 4: E2E Ollama tests
    info ""
    info "=== Group 4: E2E Inference (Ollama) ==="

    run_test "InferenceRouter Ollama (fallback, health, routing)" \
        ./gradlew :e2e-test:test --tests "*InferenceRouterOllamaTest" --rerun -q

    # Group 5: Advanced experiments (need multiple models or embedding)
    info ""
    info "=== Group 5: Advanced Experiments ==="

    run_test "Hybrid Soul Retrieval" \
        ./gradlew :core:test --tests "*HybridSoulExperimentTest.live_hybrid_retrieval" --rerun -q

    run_test "Soul Depth Sweep" \
        ./gradlew :core:test --tests "*SoulDepthExperimentTest.live_*" --rerun -q

    run_test "ID-RAG Retrieval" \
        ./gradlew :core:test --tests "*IdRagExperimentTest.live_*" --rerun -q

    # Substrate curve needs SOUL_EXPERIMENT_MODELS
    export SOUL_EXPERIMENT_MODELS="${SECONDARY_MODEL},${PRIMARY_MODEL}"

    run_test "Substrate Curve (multi-model)" \
        ./gradlew :core:test --tests "*SubstrateCurveTest.live_substrate_curve" --rerun -q

    run_test "Full Experiment Suite" \
        ./gradlew :core:test --tests "*FullExperimentSuiteTest.live_full_suite" --rerun -q
fi

# --- Step 5: Report ---
echo ""
echo "============================================"
echo "  Results"
echo "============================================"
echo ""
ok "Passed:  ${PASSED}"
if [ "$FAILED" -gt 0 ]; then
    fail "Failed:  ${FAILED}"
    for f in "${FAILURES[@]}"; do
        fail "  - $f"
    done
else
    ok "Failed:  0"
fi
if [ "$SKIPPED" -gt 0 ]; then
    warn "Skipped: ${SKIPPED}"
fi
echo ""

# Tests that need specialized infrastructure (not runnable with just Ollama)
if [ "$QUICK" = false ]; then
    echo "--- Not runnable with Ollama alone (need specialized infrastructure) ---"
    echo "  Steering vectors:    SteeringExperimentTest, SteeringRobustnessTest, PhoneSteeringExperimentTest"
    echo "  Diffusion:           DiffusionExperimentTest (needs Dream-7B server)"
    echo "  Hybrid architectures: HybridArchExperimentTest (needs Jamba model)"
    echo "  Nemotron:            NemotronExperimentTest (needs 120B model)"
    echo "  LoRA/Persona:        KokoroCoreTest, PersonaVectorExperimentTest, PCLExperimentTest, MoELoRAExperimentTest"
    echo "  Safety:              SafetyRegressionTest (needs qwen2.5-3b-instruct)"
    echo ""
fi

# --- Cleanup ---
if [ "$OLLAMA_MANAGED" = true ]; then
    info "Ollama container '${CONTAINER_NAME}' left running. Stop with: docker stop ${CONTAINER_NAME}"
fi

exit "$FAILED"
