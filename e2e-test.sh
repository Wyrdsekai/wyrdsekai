#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# Wyrdsekai E2E Test Orchestrator
# ═══════════════════════════════════════════════════════════════════════════════
#
# Usage:
#   ./e2e-test.sh                            # Run all tiers, auto-detect GPU
#   ./e2e-test.sh --tier 0                   # Tier 0 only (WireMock, no deps)
#   ./e2e-test.sh --tier 1                   # Tier 1 (smoke, 0.6B)
#   ./e2e-test.sh --tier 2                   # Tier 2 (e2e, 4B)
#   ./e2e-test.sh --tier 3                   # Tier 3 (between, NATS)
#   ./e2e-test.sh --tier 4                   # Tier 4 (relay, 2x backends)
#   ./e2e-test.sh --tier 5                   # Tier 5 (household, 3x backends)
#   ./e2e-test.sh --engine sglang            # Use SGLang (default, 16GB GPU)
#   ./e2e-test.sh --engine vllm              # Use vLLM (24GB+ GPU)
#   ./e2e-test.sh --engine llama             # Use llama-server (lightweight)
#   ./e2e-test.sh --device gpu               # Force GPU mode
#   ./e2e-test.sh --device cpu               # Force CPU-only mode
#   ./e2e-test.sh --keep                     # Keep Docker containers after
#   ./e2e-test.sh --model Qwen/Qwen3-8B     # Override model
#
# Engines:
#   sglang (default)  SGLang Docker — reliable tool calling, needs 16GB+ GPU
#   vllm              vLLM Docker — reliable tool calling, needs 24GB+ GPU
#   llama             llama.cpp Docker — lightweight, single-model
#   ollama            Ollama Docker — embeddings primary, weak tool calling
#   claude            Claude CLI — no GPU needed, uses API subscription
#
# Recommended by GPU VRAM:
#   16 GB:   --engine sglang         (Qwen3-8B FP8, all tiers validated)
#   24+ GB:  --engine vllm           (Qwen3-Coder-30B-A3B AWQ)
#   Apple:   --engine vllm-mlx       (native Metal, auto-detected on Apple Silicon)
#   CPU:     --engine claude         (API, no local GPU needed)
#   CPU:     --engine llama          (Qwen3-4B GGUF, CPU inference)
#
# Tiers:
#   0  integration    WireMock only, no external deps          (~60s,  48 tests)
#   1  smoke          Single real LLM, golden path              (~2m,   3 tests)
#   2  e2e            Full scenarios, quality assertions        (~5m,  21 tests)
#   3  between        NATS federation, no LLM                  (~2m,  11 tests)
#   4  relay          2x LLM backends + NATS                   (~5m,  12 tests)
#   5  household      3x LLM backends + NATS (capstone)        (~10m,  6 tests)
#
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker/docker-compose.e2e.yml"
PROJECT_NAME="wyrdsekai-e2e"

# Defaults
TIER="all"
DEVICE="auto"
# Default engine: llama-server (matches what production .deb/.pkg ship with).
# Switched from sglang on 2026-04-28 — sglang's 8B model download / warm-up
# (~10 min cold) doesn't reflect what operators actually run, and the harness
# was timing out before reaching the test phase. llama-server reuses the
# bundled CPU/CUDA binary that ships with installs and starts in seconds.
# Pass --engine sglang explicitly for capacity/throughput testing.
ENGINE="llama"
MODEL=""
KEEP=false
# --inference-url <url>: point tests at an existing inference server instead
# of starting one in docker. Skips the harness llama-server entirely. Useful
# when running e2e on a host that already has wyrdsekai-llama up (home-server) — pass
# http://localhost:8200 to reuse the live mesh's model. See --reuse-host-llm
# for auto-detection.
INFERENCE_URL=""
# --reuse-host-llm: probe well-known wyrdsekai inference ports (8200 server,
# 11525 .deb default) and reuse the first healthy one. Equivalent to passing
# --inference-url but skips the typing.
REUSE_HOST_LLM=false

# Default models per engine
DEFAULT_SGLANG_MODEL="Qwen/Qwen3-8B"
DEFAULT_VLLM_MODEL="cpatonn/Qwen3-Coder-30B-A3B-Instruct-AWQ-4bit"
DEFAULT_LLAMA_MODEL="Qwen3-4B-Q4_K_M.gguf"

# --- E2E port universe (auto-shift to coexist with live mesh) ---
# When the harness runs on a host that already has wyrdsekai installed (e.g., home-server),
# the standard ports (4222 NATS, 8080 llama) are already bound. We auto-detect
# conflicts and shift to the 14000+/18000+ range. Override via env if you need
# specific ports. Both compose (LLAMA_PORT, NATS_PORT) and the JVM tests
# (WYRDSEKAI_E2E_*_PORT) read these.
port_taken() {
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"
    elif command -v lsof >/dev/null 2>&1; then
        lsof -ti ":$1" >/dev/null 2>&1
    else
        # No probe available — assume free
        return 1
    fi
}
resolve_port() {
    local var="$1" dflt="$2" shifted="$3"
    local cur="${!var:-}"
    if [[ -n "$cur" ]]; then
        echo "$cur"
        return
    fi
    if port_taken "$dflt"; then
        echo "$shifted"
    else
        echo "$dflt"
    fi
}
NATS_PORT="$(resolve_port NATS_PORT 4222 14222)"
NATS_MONITOR_PORT="$(resolve_port NATS_MONITOR_PORT 8222 18222)"
LLAMA_PORT="$(resolve_port LLAMA_PORT 8080 18080)"
LLAMA_PHONE_PORT="$(resolve_port LLAMA_PHONE_PORT 8081 18081)"
LLAMA_LAPTOP_PORT="$(resolve_port LLAMA_LAPTOP_PORT 8082 18082)"
LLAMA_DRIVE_PORT="$(resolve_port LLAMA_DRIVE_PORT 8083 18083)"
# Voice backend (4B + voice LoRA in production). Default 8201 matches the
# production wyrdsekai-llama-voice port so --reuse-host-llm picks it up
# unchanged; if 8201 is already taken, shift to 18201.
LLAMA_VOICE_PORT="$(resolve_port LLAMA_VOICE_PORT 8201 18201)"
SGLANG_PORT="$(resolve_port SGLANG_PORT 8000 18000)"
VLLM_PORT="$(resolve_port VLLM_PORT 8100 18100)"
export NATS_PORT NATS_MONITOR_PORT LLAMA_PORT LLAMA_PHONE_PORT LLAMA_LAPTOP_PORT \
       LLAMA_DRIVE_PORT LLAMA_VOICE_PORT SGLANG_PORT VLLM_PORT
# Mirror to the WYRDSEKAI_E2E_*_PORT names that the JVM tests read
# (DockerInfraExtension, E2eTestSupport, TestServerBootstrap)
export WYRDSEKAI_E2E_NATS_PORT="$NATS_PORT"
export WYRDSEKAI_E2E_NATS_MONITOR_PORT="$NATS_MONITOR_PORT"
export WYRDSEKAI_E2E_LLAMA_PORT="$LLAMA_PORT"
export WYRDSEKAI_E2E_LLAMA_PHONE_PORT="$LLAMA_PHONE_PORT"
export WYRDSEKAI_E2E_LLAMA_LAPTOP_PORT="$LLAMA_LAPTOP_PORT"
export WYRDSEKAI_E2E_LLAMA_DRIVE_PORT="$LLAMA_DRIVE_PORT"
# Voice backend port — picked up by E2eTestSupport.setupDualInference for
# voice-sensitive test classes (MemoryE2ETest, EmberProgressiveTasksE2ETest).
export WYRDSEKAI_E2E_VOICE_PORT="$LLAMA_VOICE_PORT"
export WYRDSEKAI_E2E_SGLANG_PORT="$SGLANG_PORT"
export WYRDSEKAI_E2E_VLLM_PORT="$VLLM_PORT"

# Conservative VRAM defaults so the harness can coexist with a live
# wyrdsekai-llama on the same GPU. With a 9B drive model + voice model already
# loaded, ~5GB VRAM is free on a 16GB card. Full-offload + 16k ctx OOMs;
# 8k ctx + partial offload fits. Override via env for dedicated boxes.
export LLAMA_CTX_SIZE="${LLAMA_CTX_SIZE:-8192}"
export LLAMA_GPU_LAYERS="${LLAMA_GPU_LAYERS:-24}"

# --- Parse arguments ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tier)          TIER="$2";          shift 2 ;;
        --device)        DEVICE="$2";        shift 2 ;;
        --engine)        ENGINE="$2";        shift 2 ;;
        --model)         MODEL="$2";         shift 2 ;;
        --keep)          KEEP=true;          shift   ;;
        --inference-url) INFERENCE_URL="$2"; shift 2 ;;
        --reuse-host-llm) REUSE_HOST_LLM=true; shift ;;
        -h|--help)
            head -42 "$0" | tail -40
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# --- Resolve inference URL: explicit override wins, then --reuse-host-llm probe ---
# CRITICAL: WYRDSEKAI_INFERENCE_URL forces the legacy single-server path in
# InferenceConfig.autoDetectLlamaServers() — only the URL you set is probed.
# The dual-inference auto-detect (:8200 skills + :8201 voice) is bypassed.
# So we ONLY set WYRDSEKAI_INFERENCE_URL for the explicit --inference-url case.
# For --reuse-host-llm we just leave the env unset and let the auto-detect run,
# which registers both backends when both ports are healthy.
if [[ "$REUSE_HOST_LLM" == true ]]; then
    # Verify at least one production llama-server is healthy; otherwise fall
    # through to spin-up so the test isn't surprised mid-run.
    found_any=false
    drive_port=""
    for candidate in 8200 8201 11525; do
        if curl -sf --max-time 2 "http://localhost:${candidate}/health" >/dev/null 2>&1; then
            echo "[E2E] --reuse-host-llm: detected llama-server on :${candidate}"
            found_any=true
            # First healthy port is the "drive" — used to anchor single-URL resolution.
            [[ -z "$drive_port" ]] && drive_port="$candidate"
        fi
    done
    if [[ "$found_any" == true ]]; then
        # Tell the harness to skip the docker llama-server spin-up. Without
        # WYRDSEKAI_INFERENCE_URL set, the JVM will run autoDetectLlamaServers()
        # which probes BOTH :8200 (skills) and :8201 (voice) and registers
        # whichever is healthy. This is what we want — voice polish gets the
        # 4B model when it's running, instead of falling back to the 9B drive.
        REUSE_HOST_LLM_DETECTED=true
        export WYRDSEKAI_E2E_BACKEND="${WYRDSEKAI_E2E_BACKEND:-llama-server}"
        # The companion auto-detects both ports, but single-URL consumers — notably
        # the test scoring JUDGE — resolve via DockerInfraExtension.llamaServerUrl(),
        # which defaults to :8080. When reusing host inference that port is dead, so
        # the judge got HTTP 405 and fell back to heuristic scoring. Anchor it on the
        # detected drive port so judged assertions actually hit the live model.
        export WYRDSEKAI_E2E_LLAMA_PORT="$drive_port"
        echo "[E2E] Reusing existing inference (skills + voice via auto-detect; judge → :${drive_port})"
    else
        echo "[E2E] --reuse-host-llm: no healthy wyrdsekai-llama on 8200/8201/11525 — falling back to spin-up" >&2
        REUSE_HOST_LLM_DETECTED=false
    fi
fi
if [[ -n "$INFERENCE_URL" ]]; then
    # Explicit --inference-url: legacy single-server path. Honors the URL exactly,
    # registers ONLY that backend. Use --reuse-host-llm if you want auto dual-inference.
    export WYRDSEKAI_INFERENCE_URL="$INFERENCE_URL"
    echo "[E2E] Reusing existing inference server (single-backend): ${INFERENCE_URL}"
fi

# Resolve model for engine
resolve_engine_model() {
    if [[ -n "$MODEL" ]]; then
        return
    fi
    case "$ENGINE" in
        sglang) MODEL="$DEFAULT_SGLANG_MODEL" ;;
        vllm)   MODEL="$DEFAULT_VLLM_MODEL" ;;
        llama)  MODEL="$DEFAULT_LLAMA_MODEL" ;;
    esac
}
resolve_engine_model

# Tell SmokeTest + setupInference which backend to use. The Java code reads
# WYRDSEKAI_E2E_BACKEND env (default sglang); without this, switching --engine
# llama still triggers an SGLang container start at smoke-test setUp().
case "$ENGINE" in
    llama) export WYRDSEKAI_E2E_BACKEND="${WYRDSEKAI_E2E_BACKEND:-llama-server}" ;;
    sglang|vllm|claude) export WYRDSEKAI_E2E_BACKEND="${WYRDSEKAI_E2E_BACKEND:-$ENGINE}" ;;
esac

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${CYAN}[E2E]${NC} $*"; }
ok()    { echo -e "${GREEN}[E2E]${NC} $*"; }
warn()  { echo -e "${YELLOW}[E2E]${NC} $*"; }
fail()  { echo -e "${RED}[E2E]${NC} $*"; }

# --- Prerequisites ---
check_prereqs() {
    local missing=()
    command -v docker >/dev/null 2>&1 || missing+=(docker)

    if [[ ${#missing[@]} -gt 0 ]]; then
        fail "Missing prerequisites: ${missing[*]}"
        exit 1
    fi

    if ! docker info >/dev/null 2>&1; then
        fail "Docker daemon not running"
        exit 1
    fi
}

# --- GPU Detection ---
GPU_VENDOR="none"

detect_gpu() {
    # Sets GPU_VENDOR and DETECTED_DEVICE as global variables.
    # Do NOT call via $() — subshells lose the GPU_VENDOR assignment.
    if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
        GPU_VENDOR="nvidia"
        DETECTED_DEVICE="gpu"
    elif command -v rocm-smi >/dev/null 2>&1 && rocm-smi --showid 2>/dev/null | grep -q 'GPU'; then
        GPU_VENDOR="amd"
        DETECTED_DEVICE="gpu"
    elif command -v amd-smi >/dev/null 2>&1 && amd-smi list 2>/dev/null | grep -q 'GPU'; then
        GPU_VENDOR="amd"
        DETECTED_DEVICE="gpu"
    elif [ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ]; then
        GPU_VENDOR="apple"
        DETECTED_DEVICE="gpu"
    else
        DETECTED_DEVICE="cpu"
    fi
}

detect_gpu_count() {
    if [[ "$GPU_VENDOR" == "nvidia" ]] && command -v nvidia-smi >/dev/null 2>&1; then
        nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | wc -l
    elif [[ "$GPU_VENDOR" == "amd" ]] && command -v rocm-smi >/dev/null 2>&1; then
        rocm-smi --showid 2>/dev/null | grep -c 'GPU' || echo "0"
    elif [[ "$GPU_VENDOR" == "apple" ]]; then
        echo "1"
    else
        echo "0"
    fi
}

resolve_device() {
    if [[ "$DEVICE" == "auto" ]]; then
        detect_gpu
        DEVICE="$DETECTED_DEVICE"
        info "Auto-detected device: ${BOLD}${DEVICE}${NC} (vendor: ${GPU_VENDOR})"
    fi
    # Apple Silicon: auto-select vllm-mlx engine unless user explicitly set --engine
    if [[ "$GPU_VENDOR" == "apple" && "$ENGINE" == "sglang" ]]; then
        ENGINE="vllm-mlx"
        info "Apple Silicon detected — using vllm-mlx engine (native Metal inference)"
    fi
}

is_local_engine() {
    case "$ENGINE" in
        ollama|sglang|vllm|vllm-mlx|llama) return 0 ;;
        *) return 1 ;;
    esac
}

gpu_vram_gb() {
    if [[ "$GPU_VENDOR" == "nvidia" ]] && command -v nvidia-smi >/dev/null 2>&1; then
        nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null \
            | head -1 | awk '{printf "%d", $1/1024}'
    elif [[ "$GPU_VENDOR" == "amd" ]]; then
        # Try rocm-smi first, fall back to amd-smi
        if command -v rocm-smi >/dev/null 2>&1; then
            rocm-smi --showmeminfo vram --csv 2>/dev/null \
                | grep -v 'GPU' | head -1 | awk -F',' '{printf "%d", $2/1073741824}'
        elif command -v amd-smi >/dev/null 2>&1; then
            amd-smi metric --gpu 0 --vram-usage --json 2>/dev/null \
                | grep -o '"vram_total":[0-9]*' | head -1 | awk -F: '{printf "%d", $2/1048576}'
        else
            echo "0"
        fi
    elif [[ "$GPU_VENDOR" == "apple" ]]; then
        # Apple Silicon: unified memory — report ~75% of total RAM as GPU-available
        sysctl -n hw.memsize 2>/dev/null | awk '{printf "%d", $1/1073741824 * 0.75}'
    else
        echo "0"
    fi
}

# --- Docker Compose Helpers ---
compose() {
    local compose_args=""
    if [[ "$GPU_VENDOR" == "apple" ]]; then
        # Apple Silicon: standalone compose (NATS only, no NVIDIA deps).
        # Used INSTEAD OF main compose — deep-merge cannot remove GPU reservations.
        compose_args="-f ${SCRIPT_DIR}/docker/docker-compose.e2e.apple.yml"
    elif [[ "$GPU_VENDOR" == "amd" ]]; then
        compose_args="-f $COMPOSE_FILE -f ${SCRIPT_DIR}/docker/docker-compose.e2e.rocm.yml"
    else
        compose_args="-f $COMPOSE_FILE"
    fi
    docker compose $compose_args -p "$PROJECT_NAME" "$@"
}

teardown_conflicting_containers() {
    local conflicts=()
    for name in wyrdsekai-e2e-nats wyrdsekai-e2e-ollama wyrdsekai-e2e-sglang wyrdsekai-e2e-vllm wyrdsekai-e2e-llama; do
        if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
            conflicts+=("$name")
        fi
    done

    if [[ ${#conflicts[@]} -gt 0 ]]; then
        warn "Conflicting containers found: ${conflicts[*]}"
        info "Removing them for a clean E2E run..."
        for name in "${conflicts[@]}"; do
            docker rm -f "$name" 2>/dev/null || true
        done
        ok "Conflicting containers removed"
    fi
}

start_docker() {
    local device_mode="$1"

    teardown_conflicting_containers

    info "Starting Docker services (device=${device_mode}, engine=${ENGINE})..."

    case "$TIER" in
        0)
            info "Tier 0 needs no Docker services"
            return
            ;;
    esac

    if [[ "$GPU_VENDOR" == "apple" ]]; then
        # Apple Silicon: only NATS in Docker. vllm-mlx handles inference natively.
        # No Ollama — vllm-mlx serves both chat and embeddings.
        if [[ "$TIER" =~ ^(3|4|5|all)$ ]]; then
            compose up -d nats 2>&1 | sed 's/^/  /'
        fi

        # Launch vllm-mlx natively
        if needs_inference; then
            local mlx_model="${VLLM_MLX_MODEL:-mlx-community/Qwen3-8B-4bit}"
            local mlx_port="${VLLM_MLX_PORT:-8200}"
            info "Starting vllm-mlx (native Apple Silicon inference)..."
            if ! command -v vllm-mlx &>/dev/null; then
                info "Installing vllm-mlx..."
                pip install vllm-mlx 2>&1 | tail -3
            fi
            mkdir -p "${SCRIPT_DIR}/data"
            vllm-mlx serve "$mlx_model" \
                --port "$mlx_port" \
                --enable-auto-tool-choice --tool-call-parser qwen \
                --continuous-batching --use-paged-cache \
                > "${SCRIPT_DIR}/data/vllm-mlx-e2e.log" 2>&1 &
            VLLM_MLX_PID=$!
        fi
        return
    fi

    # --- Non-Apple path: Linux/Windows with Docker GPU ---

    # Start NATS if needed
    if [[ "$TIER" =~ ^(3|4|5|all)$ ]]; then
        compose up -d nats 2>&1 | sed 's/^/  /'
    fi

    # Start inference engine (tiers 1, 2, 4, 5) — skip if reusing host LLM.
    if needs_inference && [[ -n "$INFERENCE_URL" || "$REUSE_HOST_LLM_DETECTED" == true ]]; then
        if [[ -n "$INFERENCE_URL" ]]; then
            info "Skipping inference container — reusing ${INFERENCE_URL}"
        else
            info "Skipping inference container — reusing host llama-server (auto-detect)"
        fi
    fi
    if needs_inference && [[ -z "$INFERENCE_URL" && "$REUSE_HOST_LLM_DETECTED" != true ]]; then
        local tool_parser="qwen"
        if [[ "$MODEL" == *"Coder"* || "$MODEL" == *"3.5"* ]]; then
            tool_parser="qwen3_coder"
        fi

        case "$ENGINE" in
            sglang)
                local extra_args="${SGLANG_EXTRA_ARGS:---quantization fp8}"
                info "Starting SGLang with model ${BOLD}${MODEL}${NC} (parser=${tool_parser})..."
                SGLANG_MODEL="$MODEL" \
                SGLANG_MAX_MODEL_LEN=16384 \
                SGLANG_TOOL_PARSER="$tool_parser" \
                SGLANG_EXTRA_ARGS="$extra_args" \
                COMPOSE_PROFILES=sglang \
                compose up -d sglang 2>&1 | sed 's/^/  /'
                ;;
            vllm)
                local vllm_parser="qwen3_coder"
                local extra_args="${VLLM_EXTRA_ARGS:-}"
                if [[ "$MODEL" != *"AWQ"* && "$MODEL" != *"GPTQ"* ]]; then
                    extra_args="${VLLM_EXTRA_ARGS:---quantization fp8}"
                fi
                info "Starting vLLM with model ${BOLD}${MODEL}${NC} (parser=${vllm_parser})..."
                VLLM_MODEL="$MODEL" \
                VLLM_MAX_MODEL_LEN=16384 \
                VLLM_TOOL_PARSER="$vllm_parser" \
                VLLM_EXTRA_ARGS="$extra_args" \
                COMPOSE_PROFILES=vllm \
                compose up -d vllm 2>&1 | sed 's/^/  /'
                ;;
            llama)
                info "Starting llama-server with model ${BOLD}${MODEL}${NC} on port ${LLAMA_PORT}..."
                # LLAMA_PORT/NATS_PORT/etc are exported by resolve_port at script
                # start — they auto-shift if the standard port is taken (e.g. on home-server).
                LLAMA_MODEL="$MODEL" \
                COMPOSE_PROFILES=llama \
                compose up -d llama-server 2>&1 | sed 's/^/  /'
                ;;
            ollama)
                compose up -d ollama 2>&1 | sed 's/^/  /'
                ;;
            claude)
                info "Claude CLI engine — no Docker container needed"
                ;;
        esac
    fi
}

needs_inference() {
    case "$TIER" in
        0|3) return 1 ;;
        *)   return 0 ;;
    esac
}

stop_docker() {
    if [[ "$KEEP" == true ]]; then
        info "Keeping Docker services running (--keep)"
        return
    fi
    if [[ "$TIER" == "0" ]]; then
        return
    fi
    # Kill vllm-mlx if running (Apple Silicon native process)
    if [[ -n "${VLLM_MLX_PID:-}" ]] && kill -0 "$VLLM_MLX_PID" 2>/dev/null; then
        info "Stopping vllm-mlx (PID $VLLM_MLX_PID)..."
        kill "$VLLM_MLX_PID" 2>/dev/null || true
    fi
    info "Stopping Docker services..."
    COMPOSE_PROFILES=sglang,vllm,llama,relay compose down 2>&1 | sed 's/^/  /'
}

wait_for_health() {
    local service="$1"
    local url="$2"
    local max_wait="${3:-120}"
    local elapsed=0

    while [[ $elapsed -lt $max_wait ]]; do
        if curl -sf "$url" >/dev/null 2>&1; then
            ok "$service healthy (${elapsed}s)"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    fail "$service not healthy after ${max_wait}s"
    return 1
}

wait_for_services() {
    # NATS
    if [[ "$TIER" =~ ^(3|4|5|all)$ ]]; then
        wait_for_health "NATS" "http://localhost:8222/healthz" 30
    fi

    # Inference engine — when reusing an external server, just probe its /health.
    if needs_inference && [[ -n "$INFERENCE_URL" ]]; then
        wait_for_health "external inference" "${INFERENCE_URL}/health" 10
        return
    fi
    if needs_inference && [[ "$REUSE_HOST_LLM_DETECTED" == true ]]; then
        # Probe whichever production ports are healthy (skills + voice).
        for candidate in 8200 8201 11525; do
            if curl -sf --max-time 2 "http://localhost:${candidate}/health" >/dev/null 2>&1; then
                ok "host llama-server healthy on :${candidate}"
            fi
        done
        return
    fi
    if needs_inference; then
        case "$ENGINE" in
            vllm-mlx)
                info "Waiting for vllm-mlx (model download may take minutes on first run)..."
                wait_for_health "vllm-mlx" "http://localhost:${VLLM_MLX_PORT:-8200}/health" 300
                ;;
            sglang)
                info "Waiting for SGLang (model download may take minutes on first run)..."
                wait_for_health "SGLang" "http://localhost:${SGLANG_PORT}/health" 600
                ;;
            vllm)
                info "Waiting for vLLM (model download may take minutes on first run)..."
                wait_for_health "vLLM" "http://localhost:${VLLM_PORT}/health" 600
                ;;
            llama)
                wait_for_health "llama-server" "http://localhost:${LLAMA_PORT}/health" 120
                ;;
            ollama)
                wait_for_health "Ollama" "http://localhost:11435/api/tags" 60
                ;;
            claude)
                info "Claude CLI engine — checking CLI auth..."
                if ! claude auth status >/dev/null 2>&1; then
                    fail "Claude CLI not authenticated (run: claude auth login)"
                    exit 1
                fi
                ok "Claude CLI authenticated"
                ;;
        esac
    fi
}

# Unload Ollama chat models to free GPU before starting sglang/vllm
unload_ollama_models() {
    if [[ "$GPU_VENDOR" == "apple" ]]; then
        return  # No Ollama on Apple Silicon
    fi
    case "$ENGINE" in
        sglang|vllm)
            info "Unloading Ollama chat models to free GPU for ${ENGINE}..."
            for m in "qwen3-coder:30b" "qwen3:8b" "qwen3:4b" "qwen3:14b" "qwen3:0.6b"; do
                curl -sf "http://localhost:11435/api/generate" \
                    -d "{\"model\":\"${m}\",\"keep_alive\":0}" >/dev/null 2>&1 || true
            done
            ok "Ollama chat models unloaded"
            ;;
    esac
}

# --- Build ---
build_java() {
    info "Building Java modules..."
    (cd "$SCRIPT_DIR" && ./gradlew :e2e-test:compileTestJava 2>&1 | tail -5 | sed 's/^/  /')
    ok "Java built"
}

# --- Run Tests ---
tier_tag() {
    case "$1" in
        0) echo "integration" ;;
        1) echo "smoke" ;;
        2) echo "e2e" ;;
        3) echo "between" ;;
        4) echo "relay" ;;
        5) echo "household" ;;
    esac
}

run_tier() {
    local tier="$1"
    local device="$2"
    local tag
    tag=$(tier_tag "$tier")

    info "Running Tier ${tier} (${tag}) tests (device=${device}, engine=${ENGINE})..."

    local exit_code=0
    local model_prop=""
    if [[ -n "$MODEL" ]]; then
        model_prop="-Dwyrdsekai.e2e.engine.model=$MODEL"
    fi

    (cd "$SCRIPT_DIR" && ./gradlew :e2e-test:test \
        --no-configuration-cache \
        -PincludeTags="$tag" \
        -Dwyrdsekai.e2e.device="$device" \
        -Dwyrdsekai.e2e.engine="$ENGINE" \
        $model_prop \
        2>&1 | sed 's/^/  /') || exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        ok "Tier ${tier} (${tag}, ${device}): ${GREEN}PASSED${NC}"
    else
        fail "Tier ${tier} (${tag}, ${device}): ${RED}FAILED${NC} (exit code ${exit_code})"
    fi
    return $exit_code
}

run_all_tiers() {
    local device="$1"
    local tiers=()
    local overall_exit=0

    if [[ "$TIER" == "all" ]]; then
        tiers=(0 1 2 3 4 5)
    else
        tiers=("$TIER")
    fi

    for t in "${tiers[@]}"; do
        run_tier "$t" "$device" || overall_exit=$?
    done

    return $overall_exit
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

echo -e "${BOLD}═══════════════════════════════════════════${NC}"
echo -e "${BOLD} Wyrdsekai E2E Test Suite${NC}"
echo -e "${BOLD}═══════════════════════════════════════════${NC}"
echo ""

check_prereqs
resolve_device

VRAM=$(gpu_vram_gb)
if [[ "$VRAM" -gt 0 ]]; then
    info "GPU: ${BOLD}${VRAM}GB VRAM${NC} detected (${GPU_VENDOR})"
fi
info "Tier: ${BOLD}${TIER}${NC}, Device: ${BOLD}${DEVICE}${NC}, Engine: ${BOLD}${ENGINE}${NC}"
if [[ "$GPU_VENDOR" == "amd" ]]; then
    info "Using AMD ROCm Docker overrides"
fi
if [[ "$GPU_VENDOR" == "apple" ]]; then
    info "Apple Silicon — no Ollama in Docker (vllm-mlx handles inference natively)"
fi
if [[ -n "$MODEL" ]]; then
    info "Model: ${BOLD}${MODEL}${NC}"
fi
echo ""

# Cleanup trap
cleanup() {
    stop_docker
}
trap cleanup EXIT

# 1. Start Docker (if needed)
if [[ "$TIER" != "0" ]]; then
    start_docker "$DEVICE"
fi

# 2. Wait for services
if [[ "$TIER" != "0" ]]; then
    info "Waiting for services..."
    wait_for_services
    unload_ollama_models
fi

# 3. Build
build_java

# 4. Run tests
echo ""
echo -e "${BOLD}───────────────────────────────────────────${NC}"
echo -e "${BOLD} Running Tests${NC}"
echo -e "${BOLD}───────────────────────────────────────────${NC}"
echo ""

OVERALL_EXIT=0
run_all_tiers "$DEVICE" || OVERALL_EXIT=$?

# 5. Summary
echo ""
echo -e "${BOLD}───────────────────────────────────────────${NC}"
if [[ $OVERALL_EXIT -eq 0 ]]; then
    echo -e "${GREEN}${BOLD} All tests PASSED${NC}"
else
    echo -e "${RED}${BOLD} Some tests FAILED${NC} (exit code ${OVERALL_EXIT})"
fi
echo -e "${BOLD}───────────────────────────────────────────${NC}"

exit $OVERALL_EXIT
