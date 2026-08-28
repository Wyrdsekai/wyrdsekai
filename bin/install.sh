#!/usr/bin/env bash
# Wyrdsekai - Universal Installer (BUILDS FROM SOURCE)
#
#   ./install.sh        (from a repo checkout)
#
# NOT the one-liner. `curl -fsSL https://wyrdsekai.org/install | bash` serves
# site/install, which downloads a PREBUILT release and verifies it against the
# release's SHA256SUMS. This script is the from-source path: it compiles, so it
# needs a JDK and a toolchain. They are different installers on purpose; this
# header used to claim the one-liner ran this file, which it never did.
#
# Options:
#   --version VERSION    Install a specific version (default: latest)
#   --dir PATH           Install directory (default: ~/.wyrdsekai)
#   --port PORT          Default port (default: 7070)
#   --with-codezaiku     Also install CodeZaiku ML infrastructure
#   --with-rendezvous    Enable zone directory aggregator service (Linux only)
#   --no-inference       Skip inference backend setup
#   --no-service         Skip systemd/launchd service setup
#   --uninstall          Remove Wyrdsekai cleanly
#   --help               Show this help
#
# Environment:
#   WYRDSEKAI_HOME       Override install directory
#   WYRDSEKAI_PORT       Override default port
#   GITHUB_TOKEN         For private repos / rate limit avoidance

set -euo pipefail

# --- Constants ---------------------------------------------------------------

REPO="wyrdsekai/wyrdsekai"
CODEZAIKU_REPO="Wyrdsekai/codezaiku"
DEFAULT_DIR="$HOME/.wyrdsekai"
DEFAULT_PORT=7070
MIN_JAVA=21
TEMURIN_MAJOR=25

# Colors (disabled if not a terminal)
if [ -t 1 ]; then
    BOLD='\033[1m' DIM='\033[2m'
    GREEN='\033[0;32m' YELLOW='\033[1;33m' RED='\033[0;31m'
    CYAN='\033[0;36m' BLUE='\033[0;34m' NC='\033[0m'
else
    BOLD='' DIM='' GREEN='' YELLOW='' RED='' CYAN='' BLUE='' NC=''
fi

info()  { echo -e "${BLUE}[info]${NC} $*"; }
ok()    { echo -e "${GREEN}[ok]${NC} $*"; }
warn()  { echo -e "${YELLOW}[warn]${NC} $*"; }
err()   { echo -e "${RED}[error]${NC} $*" >&2; }
fatal() { err "$@"; exit 1; }

# --- Platform detection ------------------------------------------------------

detect_platform() {
    case "$(uname -s)" in
        Linux*)  PLATFORM_OS="linux" ;;
        Darwin*) PLATFORM_OS="darwin" ;;
        MINGW*|MSYS*|CYGWIN*) PLATFORM_OS="windows" ;;
        *) fatal "Unsupported OS: $(uname -s)" ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)   PLATFORM_ARCH="amd64" ;;
        aarch64|arm64)  PLATFORM_ARCH="arm64" ;;
        *) fatal "Unsupported architecture: $(uname -m)" ;;
    esac

    info "Platform: ${PLATFORM_OS}-${PLATFORM_ARCH}"
}

# --- Java detection + install ------------------------------------------------

java_version() {
    "$1" -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/' || echo "0"
}

check_java() {
    # Bundled JRE
    if [ -x "${INSTALL_DIR}/jre/bin/java" ]; then
        JAVA_CMD="${INSTALL_DIR}/jre/bin/java"
        ok "Java (bundled): $(java_version "$JAVA_CMD")"
        return 0
    fi

    # JAVA_HOME
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        local ver
        ver=$(java_version "${JAVA_HOME}/bin/java")
        if [ "$ver" -ge "$MIN_JAVA" ] 2>/dev/null; then
            JAVA_CMD="${JAVA_HOME}/bin/java"
            ok "Java ${ver} (JAVA_HOME)"
            return 0
        fi
    fi

    # PATH
    if command -v java &>/dev/null; then
        local ver
        ver=$(java_version java)
        if [ "$ver" -ge "$MIN_JAVA" ] 2>/dev/null; then
            JAVA_CMD="java"
            ok "Java ${ver}"
            return 0
        fi
        warn "Java $ver found, but $MIN_JAVA+ required."
    fi

    return 1
}

install_java_system() {
    info "Installing Java ${MIN_JAVA}+..."

    if [ "$PLATFORM_OS" = "darwin" ]; then
        if command -v brew &>/dev/null; then
            brew install --cask temurin 2>&1 | tail -5
        else
            fatal "Homebrew not found. Install Java manually: https://adoptium.net"
        fi
    elif command -v apt-get &>/dev/null; then
        sudo apt-get update -qq
        sudo apt-get install -y -qq openjdk-${MIN_JAVA}-jdk 2>&1 | tail -3
    elif command -v dnf &>/dev/null; then
        sudo dnf install -y java-${MIN_JAVA}-openjdk-devel 2>&1 | tail -3
    elif command -v pacman &>/dev/null; then
        sudo pacman -S --noconfirm jdk${MIN_JAVA}-openjdk 2>&1 | tail -3
    else
        fatal "No supported package manager. Install Java ${MIN_JAVA}+ from https://adoptium.net"
    fi

    check_java || fatal "Java installation failed."
}

install_java_bundled() {
    info "Downloading Eclipse Temurin ${TEMURIN_MAJOR} JRE..."

    local api_os="$PLATFORM_OS" api_arch="$PLATFORM_ARCH"
    [ "$api_os" = "darwin" ] && api_os="mac"
    [ "$api_arch" = "amd64" ] && api_arch="x64"

    local url="https://api.adoptium.net/v3/binary/latest/${TEMURIN_MAJOR}/ga/${api_os}/${api_arch}/jre/hotspot/normal/eclipse?project=jdk"
    local archive
    archive=$(mktemp)

    curl -fSL -o "$archive" "$url" || wget -q -O "$archive" "$url" || {
        rm -f "$archive"
        return 1
    }

    mkdir -p "${INSTALL_DIR}/jre"

    if [ "$PLATFORM_OS" = "darwin" ]; then
        local tmpdir
        tmpdir=$(mktemp -d)
        tar xzf "$archive" -C "$tmpdir"
        # macOS Temurin has Contents/Home structure
        local home_dir
        home_dir=$(find "$tmpdir" -maxdepth 3 -name "release" -type f -exec dirname {} \; | head -1)
        if [ -n "$home_dir" ]; then
            cp -R "$home_dir"/* "${INSTALL_DIR}/jre/"
        else
            tar xzf "$archive" -C "${INSTALL_DIR}/jre" --strip-components=1
        fi
        rm -rf "$tmpdir"
    else
        tar xzf "$archive" -C "${INSTALL_DIR}/jre" --strip-components=1
    fi

    rm -f "$archive"
    JAVA_CMD="${INSTALL_DIR}/jre/bin/java"
    ok "JRE installed to ${INSTALL_DIR}/jre"
}

# --- GPU + hardware detection ------------------------------------------------

detect_gpu() {
    GPU_VENDOR="none"
    TOTAL_VRAM_GB=0

    if command -v nvidia-smi &>/dev/null && nvidia-smi --query-gpu=name --format=csv,noheader &>/dev/null; then
        GPU_VENDOR="nvidia"
        local gpu_info sum_mib
        gpu_info=$(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null | head -1 || true)
        sum_mib=$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null \
                  | awk '{s+=$1} END {print s+0}')
        TOTAL_VRAM_GB=$(( ${sum_mib:-0} / 1024 ))
        ok "GPU: NVIDIA — $gpu_info"
    elif command -v rocm-smi &>/dev/null && (rocm-smi --showid 2>/dev/null | grep -q 'GPU' || false); then
        GPU_VENDOR="amd"
        local sum_mib
        sum_mib=$(rocm-smi --showmeminfo vram 2>/dev/null \
                  | awk -F'[: ]+' '/Total VRAM/ {s+=$NF} END {print s+0}')
        TOTAL_VRAM_GB=$(( ${sum_mib:-0} / 1024 / 1024 ))
        ok "GPU: AMD (ROCm) — ${TOTAL_VRAM_GB}GB VRAM"
    elif [ "$PLATFORM_OS" = "darwin" ] && [ "$PLATFORM_ARCH" = "arm64" ]; then
        GPU_VENDOR="apple"
        local chip ram_gb
        chip=$(system_profiler SPDisplaysDataType 2>/dev/null | grep -E 'Chipset Model:|Chip:' | head -1 | cut -d: -f2 | xargs 2>/dev/null || true)
        chip="${chip:-Apple Silicon}"
        ram_gb=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0f", $1/1073741824}')
        # Apple unified memory — Metal addresses ~75% of system RAM but for
        # tier-detection we treat the full RAM number (consistent with bin/wyrd).
        TOTAL_VRAM_GB="$ram_gb"
        ok "GPU: ${chip} (${ram_gb}GB unified, Metal)"
    else
        info "GPU: None detected (CPU-only mode)"
    fi
}

# / OPEN-23 — tier detection mirrors bin/wyrd.
# Sets RECOMMENDED_TIER to one of {16gb-full, 8gb-companion, cpu-only}.
# Depends on TOTAL_VRAM_GB set by detect_gpu().
detect_tier() {
    if [ "${TOTAL_VRAM_GB:-0}" -ge 14 ]; then
        RECOMMENDED_TIER="16gb-full"
        info "Tier: 16GB-Full (V10 4B voice + V5 9B drive/πdev) — ${TOTAL_VRAM_GB}GB VRAM"
    elif [ "${TOTAL_VRAM_GB:-0}" -ge 6 ]; then
        RECOMMENDED_TIER="8gb-companion"
        info "Tier: 8GB-Companion (V10 4B voice + Granite-Sub-v2 3B drive/πdev) — ${TOTAL_VRAM_GB}GB VRAM"
    else
        RECOMMENDED_TIER="cpu-only"
        info "Tier: CPU-only (V10 4B as voice+drive — no GPU detected)"
    fi
}

detect_ram() {
    local ram_gb=0
    if [ "$PLATFORM_OS" = "linux" ]; then
        ram_gb=$(awk '/MemTotal/ {printf "%.0f", $2/1024/1024}' /proc/meminfo)
    elif [ "$PLATFORM_OS" = "darwin" ]; then
        ram_gb=$(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0f", $1/1073741824}')
    fi
    info "RAM: ${ram_gb}GB"

    # CPU flag detection — AVX-512 roughly doubles llama.cpp CPU throughput,
    # so an AVX-512 CPU box with enough RAM can handle 9B comfortably even
    # without a GPU. AVX2 is the 4B threshold.
    local flags=""
    if [ "$PLATFORM_OS" = "linux" ]; then
        flags=$(grep -m1 -oE 'avx512f|avx2|avx ' /proc/cpuinfo 2>/dev/null | tr '\n' ' ' || echo "")
    elif [ "$PLATFORM_OS" = "darwin" ]; then
        local leaf7
        leaf7=$(sysctl -n machdep.cpu.leaf7_features 2>/dev/null || echo "")
        if echo "$leaf7" | grep -qi 'AVX512'; then flags="$flags avx512f"; fi
        if echo "$leaf7" | grep -qi 'AVX2'; then flags="$flags avx2"; fi
    fi
    local cores
    if command -v nproc &>/dev/null; then
        cores=$(nproc)
    elif command -v sysctl &>/dev/null; then
        cores=$(sysctl -n hw.ncpu 2>/dev/null || echo 1)
    else
        cores=1
    fi
    info "CPU: ${cores} cores, flags:${flags:- none detected}"

    # Model recommendation weighs GPU presence, AVX-512/AVX2, core count, RAM.
    # With a GPU, VRAM is the ceiling — default to 9B and trust the user to
    # override. CPU-only hosts benefit from AVX-512.
    if [ "$GPU_VENDOR" != "none" ] && [ "$ram_gb" -ge 16 ]; then
        RECOMMENDED_MODEL="Qwen3.5-9B"
        info "Recommended: Qwen3.5-9B (GPU-accelerated)"
    elif echo "$flags" | grep -q 'avx512f' && [ "$cores" -ge 8 ] && [ "$ram_gb" -ge 32 ]; then
        RECOMMENDED_MODEL="Qwen3.5-9B"
        info "Recommended: Qwen3.5-9B (AVX-512 CPU — expect 4-8 tok/s)"
    elif echo "$flags" | grep -qE 'avx2|avx512f' && [ "$ram_gb" -ge 8 ]; then
        RECOMMENDED_MODEL="Qwen3.5-4B"
        info "Recommended: Qwen3.5-4B (llama-server) — 2.6GB model"
    else
        RECOMMENDED_MODEL="Qwen3.5-0.8B"
        info "Recommended: Qwen3.5-0.8B (llama-server) — lightweight"
    fi
}

# --- Apple Silicon: llama-server (Metal) --------------------------------------

setup_apple_silicon() {
    [ "$GPU_VENDOR" != "apple" ] && return

    if command -v llama-server &>/dev/null; then
        ok "llama-server already installed"
        return
    fi

    info "Apple Silicon detected — installing llama-server for native Metal inference."

    if [ ! -t 0 ]; then
        info "Install llama-server: brew install llama.cpp"
        return
    fi

    if ! command -v brew &>/dev/null; then
        warn "Homebrew not found. Install from https://brew.sh then run: brew install llama.cpp"
        return
    fi

    info "Installing llama.cpp via Homebrew (includes llama-server with Metal)..."
    if brew install llama.cpp 2>&1 | tail -5; then
        ok "llama-server installed"
    else
        warn "llama.cpp install failed. Try manually: brew install llama.cpp"
    fi
}

# --- nats-server (cross-node bridge) ------------------------------------------
# Required for the Between layer. The .deb bundles nats-server natively; this
# install path covers macOS + tar.gz Linux installs that don't pull in the .deb.

setup_nats_server() {
    if command -v nats-server &>/dev/null; then
        ok "nats-server already installed"
        return
    fi

    case "$PLATFORM_OS" in
        darwin)
            if ! command -v brew &>/dev/null; then
                warn "Homebrew not found. Install from https://brew.sh then run: brew install nats-server"
                return
            fi
            info "Installing nats-server via Homebrew..."
            brew install nats-server 2>&1 | tail -3 && ok "nats-server installed" \
                || warn "nats-server install failed. Try manually: brew install nats-server"
            ;;
        linux)
            # The .deb path bundles a binary at /opt/wyrdsekai/bin/nats-server. For
            # tar.gz / curl-installs we need an alternative. apt has it on recent
            # Ubuntu/Debian; otherwise download from GitHub releases.
            if command -v apt-get &>/dev/null; then
                info "Installing nats-server via apt..."
                sudo apt-get install -y nats-server 2>&1 | tail -3 && ok "nats-server installed" \
                    || warn "apt install failed. Download manually: https://github.com/nats-io/nats-server/releases"
            else
                warn "nats-server not found. Download from https://github.com/nats-io/nats-server/releases"
                warn "or install via your package manager."
            fi
            ;;
        *)
            warn "nats-server install not automated for $PLATFORM_OS. Without it, Between (cross-node) is disabled."
            ;;
    esac
}

# --- Inference backend -------------------------------------------------------
#
# Default: llama-server (llama.cpp) — works on CPU, NVIDIA CUDA, Apple Metal.
# Optional: SGLang for NVIDIA GPU users (higher throughput, per-request LoRA).
# Ollama is not installed by default — llama-server covers all its use cases.

GGUF_REPO="wyrdsekai/companion-3.5-4b-gguf"
GGUF_FALLBACK_URL="https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"

setup_inference() {
    echo -e "${BOLD}Inference backend:${NC}"

    # Check for existing llama-server
    if command -v llama-server &>/dev/null; then
        ok "llama-server already installed"
        LLAMA_SERVER_CMD="llama-server"
    elif [ -x "${INSTALL_DIR}/bin/llama-server" ]; then
        ok "llama-server already installed (bundled)"
        LLAMA_SERVER_CMD="${INSTALL_DIR}/bin/llama-server"
    fi

    # Docker-based setup for systems without native llama-server
    if [ -z "${LLAMA_SERVER_CMD:-}" ]; then
        if [ "$GPU_VENDOR" = "apple" ]; then
            # Apple Silicon — use brew
            setup_apple_silicon
            LLAMA_SERVER_CMD="llama-server"
        elif command -v docker &>/dev/null; then
            info "Docker detected — will use llama-server Docker image"
            LLAMA_SERVER_CMD="docker"
            docker pull ghcr.io/ggml-org/llama.cpp:server-cuda 2>&1 | tail -3 || \
                docker pull ghcr.io/ggml-org/llama.cpp:server 2>&1 | tail -3 || \
                warn "Docker pull failed — download manually"
        else
            warn "No Docker and no llama-server binary found."
            info "Install Docker: https://docs.docker.com/get-docker/"
            info "Or install llama.cpp: https://github.com/ggml-org/llama.cpp"
            return
        fi
    fi

    # Download model
    download_model

    # Offer SGLang for NVIDIA GPU users
    if [ "$GPU_VENDOR" = "nvidia" ] && command -v docker &>/dev/null; then
        offer_sglang
    fi

    ok "Inference ready"
}

download_model() {
    local models_dir="${INSTALL_DIR}/models"
    mkdir -p "$models_dir"

    # Embedding model (MiniLM-L6-v2 int8, 22MB) — always-on semantic search
    local embed_file="${models_dir}/minilm-l6-v2-q8.onnx"
    if [ ! -f "$embed_file" ]; then
        info "Downloading embedding model (MiniLM-L6-v2, ~22MB)..."
        curl -fSL --progress-bar -o "$embed_file" \
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" 2>/dev/null \
            || warn "Embedding model download failed — semantic search will use BM25 fallback"
    fi
    local tokenizer_file="${models_dir}/minilm-tokenizer.json"
    if [ ! -f "$tokenizer_file" ]; then
        curl -fSL -o "$tokenizer_file" \
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json" 2>/dev/null \
            || warn "Tokenizer download failed"
    fi

    # Companion model (Qwen3.5-4B SSD-trained GGUF, ~2.6GB)
    local model_file="${models_dir}/wyrdsekai-3.5-4b-v10-q4km.gguf"
    if [ -f "$model_file" ]; then
        ok "Model already downloaded: $(basename "$model_file")"
        return
    fi

    info "Downloading companion model (Qwen3.5-4B Q4_K_M, ~2.6GB)..."

    # Try wyrdsekai org repo first, fall back to unsloth
    local url
    url="https://huggingface.co/${GGUF_REPO}/resolve/main/wyrdsekai-3.5-4b-v10-q4km.gguf"
    if ! curl -fSL --progress-bar -o "$model_file" "$url" 2>/dev/null; then
        info "Falling back to base model (no SSD training)..."
        url="$GGUF_FALLBACK_URL"
        if ! curl -fSL --progress-bar -o "$model_file" "$url" 2>/dev/null; then
            warn "Model download failed. Download manually:"
            info "  curl -fSL -o ${model_file} ${url}"
            return
        fi
    fi

    local size_mb
    size_mb=$(du -m "$model_file" 2>/dev/null | cut -f1)
    ok "Model downloaded: $(basename "$model_file") (${size_mb}MB)"
}

offer_sglang() {
    if [ ! -t 0 ]; then
        info "SGLang available for NVIDIA GPU: docker compose --profile sglang up -d"
        return
    fi

    echo ""
    echo "  SGLang provides higher throughput and per-request LoRA support."
    echo "  Recommended for multi-companion setups or development."
    read -rp "  Also install SGLang? [y/N] " answer
    case "${answer:-N}" in
        [Yy]*)
            info "Pulling SGLang image (may take a few minutes)..."
            docker pull lmsysorg/sglang:v0.5.10.post1-cu130 2>&1 | tail -3
            {
                echo ""
                echo "# SGLang (optional — higher throughput for NVIDIA GPU)"
                echo "WYRDSEKAI_SGLANG_ENABLED=true"
                echo "WYRDSEKAI_SGLANG_URL=http://localhost:8000"
            } >> "${INSTALL_DIR}/.env"
            ok "SGLang installed. Start with: docker compose --profile sglang up -d"
            ;;
        *) info "Skipped SGLang (llama-server will be used)" ;;
    esac
}

# --- Relay (remote access) ---------------------------------------------------

offer_relay() {
    if [ ! -t 0 ]; then
        info "Relay: configure remote access in ${INSTALL_DIR}/.env"
        return
    fi

    echo -e "${BOLD}Remote access (relay):${NC}"
    echo "  A relay lets your phone connect to your home server from outside the LAN."
    echo "  1. Community relay (relay.wyrdsekai.org) — free, shared"
    echo "  2. Self-hosted relay (enter your own URL)"
    echo "  3. Skip (LAN only — phone must be on home network)"
    read -rp "Choice [3]: " relay_choice

    local env_file="${INSTALL_DIR}/.env"

    case "${relay_choice:-3}" in
        1)
            local relay_token
            relay_token=$(head -c 32 /dev/urandom | base64 | tr -d '=/+' | head -c 43)
            {
                echo ""
                echo "# Relay — remote access"
                echo "WYRDSEKAI_RELAY_ENABLED=true"
                echo "WYRDSEKAI_RELAY_URL=nats://relay.wyrdsekai.org:4222"
                echo "WYRDSEKAI_RELAY_TOKEN=${relay_token}"
            } >> "$env_file"
            ok "Community relay configured"
            info "Relay token: ${relay_token}"
            info "Register your household at https://relay.wyrdsekai.org (or ask the relay admin)"
            ;;
        2)
            read -rp "Relay NATS URL (e.g. nats://relay.example.com:4222): " relay_url
            if [ -z "$relay_url" ]; then
                info "Skipped relay setup"
                return
            fi
            read -rp "Relay token: " relay_token
            {
                echo ""
                echo "# Relay — remote access"
                echo "WYRDSEKAI_RELAY_ENABLED=true"
                echo "WYRDSEKAI_RELAY_URL=${relay_url}"
                echo "WYRDSEKAI_RELAY_TOKEN=${relay_token}"
            } >> "$env_file"
            ok "Self-hosted relay configured: ${relay_url}"
            ;;
        *)
            info "Relay skipped — phone will only work on LAN"
            ;;
    esac
}

# --- GitHub release download -------------------------------------------------

latest_version() {
    local repo="$1"
    local url="https://api.github.com/repos/${repo}/releases/latest"
    local auth=()
    [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: token ${GITHUB_TOKEN}")

    curl -fsSL "${auth[@]}" "$url" 2>/dev/null \
        | grep '"tag_name"' | sed 's/.*"v\?\([^"]*\)".*/\1/' \
        || echo ""
}

download_release() {
    local repo="$1" version="$2" name="$3" dest="$4"
    local artifact="${name}-${version}-${PLATFORM_OS}-${PLATFORM_ARCH}.tar.gz"
    local url="https://github.com/${repo}/releases/download/v${version}/${artifact}"

    info "Downloading ${artifact}..."

    local archive
    archive=$(mktemp)

    if ! curl -fSL -o "$archive" "$url" 2>/dev/null; then
        rm -f "$archive"
        return 1
    fi

    mkdir -p "$dest"
    tar xzf "$archive" -C "$dest" --strip-components=1
    rm -f "$archive"
    return 0
}

# --- Launcher wrapper (PID file management) ----------------------------------

create_launcher() {
    local dest="$1"
    mkdir -p "${dest}/bin"
    cat > "${dest}/bin/wyrdsekai" << 'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="${APP_HOME}/server.pid"

# Stop a running instance — by PID file first, then by process name as fallback.
# Ensures no stale server processes survive across restarts or reboots.
stop_server() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "Stopping Wyrdsekai server (PID $pid)..."
            kill "$pid" 2>/dev/null || true
            local waited=0
            while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 10 ]; do
                sleep 1
                waited=$((waited + 1))
            done
            if kill -0 "$pid" 2>/dev/null; then
                kill -9 "$pid" 2>/dev/null || true
            fi
        fi
        rm -f "$PID_FILE"
    fi
    # Kill any remaining server processes (stale from crash/reboot/lost PID file)
    local stale
    stale=$(pgrep -f 'org.wyrdsekai.server.Main' 2>/dev/null || true)
    if [ -n "$stale" ]; then
        echo "Killing stale Wyrdsekai process(es): $stale"
        echo "$stale" | xargs kill 2>/dev/null || true
        sleep 2
        stale=$(pgrep -f 'org.wyrdsekai.server.Main' 2>/dev/null || true)
        if [ -n "$stale" ]; then
            echo "$stale" | xargs kill -9 2>/dev/null || true
        fi
    fi
}

case "${1:-}" in
    stop)
        stop_server
        # Also stop llama-server if we started it
        if [ -f "${APP_HOME}/llama-server.pid" ]; then
            VLLM_PID=$(cat "${APP_HOME}/llama-server.pid" 2>/dev/null)
            if [ -n "$VLLM_PID" ] && kill -0 "$VLLM_PID" 2>/dev/null; then
                echo "Stopping llama-server (PID $VLLM_PID)..."
                kill "$VLLM_PID" 2>/dev/null || true
            fi
            rm -f "${APP_HOME}/llama-server.pid"
        fi
        echo "Server stopped."
        exit 0
        ;;
    status)
        if [ -f "$PID_FILE" ]; then
            pid=$(cat "$PID_FILE" 2>/dev/null)
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                echo "Wyrdsekai server is running (PID $pid)"
                exit 0
            else
                echo "Wyrdsekai server is not running (stale PID file)"
                rm -f "$PID_FILE"
                exit 1
            fi
        else
            echo "Wyrdsekai server is not running"
            exit 1
        fi
        ;;
    backup)
        INSTALL_DIR="${WYRDSEKAI_HOME:-$HOME/.wyrdsekai}"
        case "${2:-now}" in
            now)
                echo "Creating backup (DB + search indexes)..."
                curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/backup/snapshot" 2>/dev/null | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    bid = data.get('backupId', '?')
    size = data.get('sizeBytes', 0)
    mb = size / (1024*1024)
    print(f'Backup created: {bid} ({mb:.1f} MB)')
    print(f'  Location: {data.get(\"location\", \"?\")}')
except:
    print('Backup created (check server logs for details)')
" 2>/dev/null || {
                    # Fallback: manual file copy if server not running
                    TIMESTAMP=$(date +%Y%m%d-%H%M%S)
                    BACKUP_DIR="$INSTALL_DIR/backups"
                    mkdir -p "$BACKUP_DIR"
                    if [ -f "$INSTALL_DIR/world.db" ]; then
                        cp "$INSTALL_DIR/world.db" "$BACKUP_DIR/world.db.$TIMESTAMP.bak"
                        echo "DB backup: $BACKUP_DIR/world.db.$TIMESTAMP.bak"
                    fi
                    if [ -d "$INSTALL_DIR/data/search" ]; then
                        cp -r "$INSTALL_DIR/data/search" "$BACKUP_DIR/search.$TIMESTAMP"
                        echo "Search backup: $BACKUP_DIR/search.$TIMESTAMP"
                    fi
                    echo "Backup complete."
                }
                ;;
            list)
                echo "Backups in $INSTALL_DIR/backups/:"
                ls -lhtr "$INSTALL_DIR/backups/" 2>/dev/null | tail -20 || echo "  (none)"
                ;;
            *)
                echo "Usage: wyrdsekai backup [command]"
                echo ""
                echo "Commands:"
                echo "  now       Create backup immediately (default)"
                echo "  list      List available backups"
                ;;
        esac
        exit 0
        ;;

    pair-code)
        curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/pair/code" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(f'Pairing code: {data[\"code\"]} (expires at {data[\"expiresAt\"]})')
except:
    print('No pending pairing code.')
"
        exit 0
        ;;
    household-key)
        if [ "${2:-}" = "generate" ]; then
            curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/pair/household-key/generate" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(f'Household key: {data[\"key\"]}')
print('Use this to pair devices without codes:')
print(f'  wyrdsekai join --key {data[\"key\"]}')
"
        else
            curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/pair/household-key" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(f'Household key: {data[\"key\"]}')
except:
    print('No household key. Generate one: wyrdsekai household-key generate')
"
        fi
        exit 0
        ;;
    devices)
        curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/pair/devices" | python3 -c "
import json, sys
devices = json.load(sys.stdin)
if not devices:
    print('No paired devices.')
else:
    for d in devices:
        status = 'REVOKED' if d.get('revoked') else 'active'
        print(f'  {d[\"name\"]} ({d[\"type\"]}) -- {status}, paired {d[\"pairedAt\"]}')
"
        exit 0
        ;;
    mcp-serve)
        # Launch MCP server for Claude Code integration.
        # Communicates via JSON-RPC on stdio.
        SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
        MCP_SCRIPT=""
        for candidate in \
            "$APP_HOME/scripts/mcp/wyrdsekai_mcp.py" \
            "$SCRIPT_DIR/../scripts/mcp/wyrdsekai_mcp.py" \
            "$SCRIPT_DIR/../../scripts/mcp/wyrdsekai_mcp.py"; do
            if [ -f "$candidate" ]; then
                MCP_SCRIPT="$candidate"
                break
            fi
        done
        if [ -z "$MCP_SCRIPT" ]; then
            echo "MCP server script not found." >&2
            exit 1
        fi
        export WYRDSEKAI_URL="http://localhost:${WYRDSEKAI_PORT:-7070}"
        exec python3 "$MCP_SCRIPT"
        ;;
    revoke-device)
        if [ -z "${2:-}" ]; then
            echo "Usage: wyrdsekai revoke-device <device-id>"
            exit 1
        fi
        curl -s -X DELETE "http://localhost:${WYRDSEKAI_PORT:-7070}/api/pair/devices/$2"
        echo "Device revoked."
        exit 0
        ;;
    library)
        SUBCMD="${2:-}"
        case "$SUBCMD" in
            list-packs|packs)
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/packs" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    packs = data.get('packs', [])
    if not packs:
        print('No knowledge packs installed.')
    else:
        print(f'{len(packs)} pack(s) installed:')
        for p in packs:
            print(f'  {p[\"name\"]:30s}  {p[\"chunks\"]:>8} chunks')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            search)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai library search <query>"
                    exit 1
                fi
                QUERY=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$3'))")
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/search?q=${QUERY}&limit=10" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    results = data.get('results', [])
    if not results:
        print(f'No results for: {data.get(\"query\",\"?\")}')
    else:
        print(f'{data.get(\"count\",0)} result(s) for: {data.get(\"query\",\"?\")}')
        for r in results:
            title = r.get('metadata', {}).get('title', r.get('source', ''))
            snippet = (r.get('content', '')[:120] + '...') if len(r.get('content', '')) > 120 else r.get('content', '')
            score = r.get('score', 0)
            print(f'  [{score:.3f}] {title}')
            print(f'          {snippet}')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            install)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai library install <pack-name>"
                    exit 1
                fi
                curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/install" \
                    -H "Content-Type: application/json" \
                    -d "{\"pack\":\"$3\"}" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    status = data.get('status', 'unknown')
    if status == 'not_yet_implemented':
        print(f'Pack install not yet available: {data.get(\"message\",\"\")}')
    elif 'error' in data:
        print(f'Error: {data[\"error\"]}')
    else:
        print(f'Pack \"{data.get(\"pack\",\"?\")}\" installed: {data.get(\"chunksIndexed\",0)} chunks')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            remove)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai library remove <pack-name>"
                    exit 1
                fi
                curl -s -X DELETE "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/packs/$3" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    if 'error' in data:
        print(f'Error: {data[\"error\"]}')
    else:
        print(f'Removed pack \"{data.get(\"pack\",\"?\")}\": {data.get(\"chunksDeleted\",0)} chunks deleted')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            status)
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/status" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(f'Knowledge base status:')
    print(f'  Total chunks:  {data.get(\"totalChunks\",0):>10}')
    print(f'  Total packs:   {data.get(\"totalPacks\",0):>10}')
    print(f'  LCSH terms:    {data.get(\"lcshTerms\",0):>10}')
    packs = data.get('packs', {})
    if packs:
        print(f'  Packs:')
        for name, count in packs.items():
            print(f'    {name:30s}  {count:>8} chunks')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            info)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai library info <pack-name>"
                    exit 1
                fi
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/library/packs/$3" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    if 'error' in data:
        print(f'Error: {data[\"error\"]}')
    else:
        print(f'Pack: {data.get(\"name\",\"?\")}')
        print(f'  Chunks: {data.get(\"chunks\",0)}')
except Exception as e:
    print(f'Error: {e}')
"
                ;;
            *)
                echo "Usage: wyrdsekai library <command>"
                echo ""
                echo "Commands:"
                echo "  packs              List installed knowledge packs"
                echo "  search <query>     Search the knowledge base"
                echo "  install <pack>     Install a knowledge pack"
                echo "  remove <pack>      Remove a knowledge pack"
                echo "  status             Knowledge base statistics"
                echo "  info <pack>        Info about a specific pack"
                ;;
        esac
        exit 0
        ;;
    adduser)
        if [ -z "${2:-}" ]; then
            echo "Usage: wyrdsekai adduser <username>"
            exit 1
        fi
        read -rsp "Password: " password; echo
        read -rsp "Confirm: " confirm; echo
        if [ "$password" != "$confirm" ]; then
            echo "Passwords do not match."
            exit 1
        fi
        # First check if any users exist
        STATUS=$(curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/auth/status")
        HAS_USERS=$(echo "$STATUS" | python3 -c "import json,sys; print(json.load(sys.stdin).get('hasUsers',False))" 2>/dev/null)
        if [ "$HAS_USERS" = "False" ]; then
            # No users — create first user (becomes steward)
            curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/auth/register" \
                -H "Content-Type: application/json" \
                -d "{\"username\":\"$2\",\"password\":\"$password\",\"displayName\":\"$2\"}" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'Created steward: {d.get(\"username\",\"?\")} (role: {d.get(\"role\",\"?\")})')
"
        else
            # Users exist — need steward credentials
            read -rp "Steward username: " admin_user
            read -rsp "Steward password: " admin_pass; echo
            # Login as steward
            TOKEN=$(curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/auth/login" \
                -H "Content-Type: application/json" \
                -d "{\"username\":\"$admin_user\",\"password\":\"$admin_pass\"}" | python3 -c "import json,sys; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
            if [ -z "$TOKEN" ]; then
                echo "Steward login failed."
                exit 1
            fi
            curl -s -X POST "http://localhost:${WYRDSEKAI_PORT:-7070}/api/auth/adduser" \
                -H "Content-Type: application/json" \
                -H "Authorization: Bearer $TOKEN" \
                -d "{\"username\":\"$2\",\"password\":\"$password\",\"displayName\":\"$2\"}" | python3 -c "
import json, sys
d = json.load(sys.stdin)
if 'error' in d:
    print(f'Error: {d[\"error\"]}')
else:
    print(f'Created user: {d.get(\"username\",\"?\")} (role: {d.get(\"role\",\"?\")})')
"
        fi
        exit 0
        ;;
    study)
        SUBCMD="${2:-}"
        PORT="${WYRDSEKAI_PORT:-7070}"
        case "$SUBCMD" in
            add)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai study add <path> [--collection <name>] [--user <did>]"
                    exit 1
                fi
                DOC_PATH="${3}"
                COLLECTION="${5:-$(basename "$DOC_PATH")}"
                USER_DID="${7:-default}"
                # Shift to find --collection and --user flags
                shift 2
                while [ $# -gt 0 ]; do
                    case "$1" in
                        --collection) COLLECTION="$2"; shift 2 ;;
                        --user) USER_DID="$2"; shift 2 ;;
                        *) DOC_PATH="$1"; shift ;;
                    esac
                done
                echo "Indexing documents from: $DOC_PATH (collection: $COLLECTION)"
                curl -s -X POST "http://localhost:${PORT}/api/study/add" \
                    -H "Content-Type: application/json" \
                    -d "{\"user\":\"$USER_DID\",\"path\":\"$DOC_PATH\",\"collection\":\"$COLLECTION\"}" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'Status: {d.get(\"status\", \"?\")}')
print(f'Collection: {d.get(\"collection\", \"?\")}')
if 'message' in d: print(d['message'])
"
                ;;
            search)
                if [ -z "${3:-}" ]; then
                    echo "Usage: wyrdsekai study search <query> [--user <did>]"
                    exit 1
                fi
                QUERY="${3}"
                USER_DID="${5:-default}"
                curl -s "http://localhost:${PORT}/api/study/search?q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$QUERY'))")&user=$USER_DID" | python3 -c "
import json, sys
d = json.load(sys.stdin)
count = d.get('count', 0)
print(f'{count} results:')
for r in d.get('results', []):
    meta = r.get('metadata', {})
    title = meta.get('title', '')
    itype = meta.get('item_type', '')
    content = r.get('content', '')[:100]
    print(f'  [{itype}] {title}: {content}...')
"
                ;;
            journal)
                USER_DID="${4:-default}"
                curl -s "http://localhost:${PORT}/api/study/journal?user=$USER_DID" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for e in d.get('entries', []):
    meta = e.get('metadata', {})
    content = e.get('content', '')[:120]
    print(f'  {content}')
    print()
"
                ;;
            status)
                USER_DID="${3:-default}"
                curl -s "http://localhost:${PORT}/api/study/status?user=$USER_DID" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'Total items: {d.get(\"totalItems\", 0)}')
"
                ;;
            *)
                echo "Usage: wyrdsekai study <command>"
                echo ""
                echo "Commands:"
                echo "  add <path> [--collection <name>]  — Index documents into your Study"
                echo "  search <query>                     — Search your Study"
                echo "  journal                            — List recent journal entries"
                echo "  status                             — Study statistics"
                ;;
        esac
        exit 0
        ;;

    update)
        case "${2:-status}" in
            status)
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/update/status" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    ver = data.get('version', 'unknown')
    build = data.get('buildHash', 'unknown')
    wire = data.get('wireProtocol', '?')
    ts = data.get('buildTimestamp', '')
    print(f'Wyrdsekai v{ver} (build {build}, {ts[:10] if ts else \"?\"})')
    print(f'Wire protocol: {wire}')
    print()
    cfg = data.get('config', {})
    channel = cfg.get('channel', '')
    policy = cfg.get('policy', 'unknown')
    role = cfg.get('nodeRole', 'secondary')
    enabled = cfg.get('enabled', False)
    print(f'Update policy: {policy}')
    print(f'Node role: {role}')
    if channel:
        print(f'Update channel: {channel}')
    else:
        print(f'Update channel: (none configured)')
    ch = data.get('channel', {})
    if ch:
        last = ch.get('lastCheck', 'never')
        err = ch.get('lastError')
        latest = ch.get('latestVersion')
        avail = ch.get('updateAvailable', False)
        print(f'Last check: {last}')
        if err:
            print(f'Last error: {err}')
        if latest:
            flag = ' (UPDATE AVAILABLE)' if avail else ' (up to date)'
            print(f'Channel latest: v{latest}{flag}')
    pin = cfg.get('pinnedVersion')
    if pin:
        print(f'PINNED to v{pin}')
except Exception as e:
    print(f'Error: {e}', file=sys.stderr)
    sys.exit(1)
" 2>/dev/null || echo "Server not running or not reachable"
                ;;
            --check|check)
                curl -s "http://localhost:${WYRDSEKAI_PORT:-7070}/api/update/status" | python3 -c "
import sys, json
data = json.load(sys.stdin)
ch = data.get('channel', {})
if ch and ch.get('latestVersion'):
    v = ch['latestVersion']
    avail = ch.get('updateAvailable', False)
    if avail:
        brk = ' (BREAKING)' if ch.get('breaking') else ''
        cl = ch.get('changelog', '')
        print(f'Update available: v{v}{brk}')
        if cl: print(f'  {cl}')
    else:
        print(f'Up to date (channel: v{v}, current: v{data.get(\"version\", \"?\")})')
else:
    print('No channel configured or no manifest available')
    print('Configure with WYRDSEKAI_UPDATE_CHANNEL in ~/.wyrdsekai/.env')
" 2>/dev/null || echo "Server not running or not reachable"
                ;;
            --force|force)
                echo "Forcing update..."
                # Build from source, package, and swap
                cd "$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")" 2>/dev/null || cd ~/src/wyrdsekai
                if [ -f "gradlew" ]; then
                    ./gradlew installDist 2>&1 | tail -5
                    echo "Update applied. Restart with: wyrdsekai"
                else
                    echo "Not in source directory. Use: cd ~/src/wyrdsekai && git pull && ./install.sh"
                fi
                ;;
            --publish|publish)
                echo "Building and publishing update package..."
                cd "$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")" 2>/dev/null || cd ~/src/wyrdsekai
                if [ -f "gradlew" ]; then
                    ./gradlew installDist 2>&1 | tail -5
                    # Restart to advertise new version via heartbeat
                    echo "Package built. Restarting to advertise to mesh..."
                    "$INSTALL_DIR/bin/wyrdsekai" stop 2>/dev/null || true
                    "$INSTALL_DIR/bin/wyrdsekai" &
                    echo "Server restarting. Mesh peers will see new version within 30s."
                else
                    echo "Not in source directory."
                fi
                ;;
            *)
                echo "Usage: wyrdsekai update [command]"
                echo ""
                echo "Commands:"
                echo "  status      Show current version and peer versions (default)"
                echo "  check       Check release channel for updates"
                echo "  force       Bypass policy and update immediately"
                echo "  publish     Build, package, restart, and advertise to mesh"
                ;;
        esac
        exit 0
        ;;

    rollback)
        INSTALL_DIR="${WYRDSEKAI_HOME:-$HOME/.wyrdsekai}"
        LIB_DIR="$INSTALL_DIR/lib"
        LIB_PREV="$INSTALL_DIR/lib.prev"

        if [ ! -d "$LIB_PREV" ]; then
            echo "No previous version available (lib.prev/ not found)"
            exit 1
        fi

        echo "Rolling back to previous version..."
        # Stop server
        "$INSTALL_DIR/bin/wyrdsekai" stop 2>/dev/null || true

        # Swap: lib → lib.failed, lib.prev → lib
        rm -rf "$INSTALL_DIR/lib.failed"
        if [ -d "$LIB_DIR" ]; then
            mv "$LIB_DIR" "$INSTALL_DIR/lib.failed"
        fi
        mv "$LIB_PREV" "$LIB_DIR"

        echo "Rollback complete. Start with: wyrdsekai"
        exit 0
        ;;
esac

# Stop any existing instance before starting
stop_server

# Also kill anything holding our port (belt + suspenders)
PORT="${WYRDSEKAI_PORT:-7070}"
PORT_PID=$(lsof -ti :"$PORT" 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
    echo "Port $PORT still held by PID $PORT_PID — killing..."
    kill -9 $PORT_PID 2>/dev/null || true
    sleep 1
fi

# Clean stale Lucene write locks from previous crash/kill
find "${APP_HOME}/search" -name 'write.lock' -delete 2>/dev/null || true

# Clean up PID files on exit (server + llama-server)
cleanup() {
    rm -f "$PID_FILE"
    if [ -f "${APP_HOME}/llama-server.pid" ]; then
        VLLM_PID=$(cat "${APP_HOME}/llama-server.pid" 2>/dev/null)
        if [ -n "$VLLM_PID" ] && kill -0 "$VLLM_PID" 2>/dev/null; then
            kill "$VLLM_PID" 2>/dev/null || true
        fi
        rm -f "${APP_HOME}/llama-server.pid"
    fi
}
trap cleanup EXIT INT TERM

# Source .env if it exists (export all variables for HOCON substitution)
if [ -f "${APP_HOME}/.env" ]; then
    set -a
    # shellcheck source=/dev/null
    . "${APP_HOME}/.env"
    set +a
fi

# Auto-start llama-server and supervise it.
# Works on Apple Silicon (Metal), Linux (CUDA/CPU), Windows (CPU).
# Model path: use WYRDSEKAI_MODEL_PATH if set, or default GGUF location.
LLAMA_PORT="${WYRDSEKAI_LLAMA_PORT:-11525}"
LLAMA_CMD=""
if command -v llama-server > /dev/null 2>&1; then
    LLAMA_CMD="llama-server"
elif [ -x "/opt/homebrew/bin/llama-server" ]; then
    LLAMA_CMD="/opt/homebrew/bin/llama-server"
fi
MODEL_PATH="${WYRDSEKAI_MODEL_PATH:-${APP_HOME}/models/wyrdsekai-3.5-4b-v10-q4km.gguf}"
LLAMA_CTX="${WYRDSEKAI_LLAMA_CTX:-16384}"
LLAMA_GPU_LAYERS="${WYRDSEKAI_GPU_LAYERS:-99}"

# --- how many requests can this seat serve at once? ---
# Measured on a staging node 2026-08-22: with one slot, two callers are serialized —
# 3.9s alone became 2.7s + 5.9s concurrent. That is the whole problem. The coding backend
# and the companion share this seat, and authoring a tool is minutes of generation, so
# every "hello" she is asked during a build waits for the build to finish.
#
# llama-server splits the window across slots (n_ctx_slot = n_ctx / n_parallel), so a
# second slot is only safe if the TOTAL context rises with it — a tool-authoring turn was
# measured at ~3.3k tokens before repair rounds, and a halved window truncates it silently.
# That costs VRAM, so we take a second slot only when the card demonstrably has room:
# the model itself (GGUF size is a good proxy), both slots, and a gigabyte spare.
LLAMA_PARALLEL="${WYRDSEKAI_LLAMA_PARALLEL:-}"
if [ -z "$LLAMA_PARALLEL" ]; then
    LLAMA_PARALLEL=1
    free_mib=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>/dev/null \
        | sort -n | tail -1 || echo 0)
    model_mib=0
    [ -f "$MODEL_PATH" ] && model_mib=$(du -m "$MODEL_PATH" 2>/dev/null | cut -f1)
    per_slot_mib=$(( 1536 * LLAMA_CTX / 16384 ))   # matches GpuProbe.suggestParallelSlots
    if [ "${free_mib:-0}" -gt $(( model_mib + per_slot_mib * 2 + 1024 )) ]; then
        LLAMA_PARALLEL=2
    fi
fi
LLAMA_TOTAL_CTX=$(( LLAMA_CTX * LLAMA_PARALLEL ))
echo "llama-server: ${LLAMA_PARALLEL} slot(s) x ${LLAMA_CTX} ctx (total ${LLAMA_TOTAL_CTX})"

if [ -n "$LLAMA_CMD" ] && [ -f "$MODEL_PATH" ]; then

    if curl -sf "http://127.0.0.1:${LLAMA_PORT}/health" > /dev/null 2>&1; then
        # Adopt the existing process — record its PID so the watchdog can supervise it
        EXISTING_PID=$(lsof -ti :"${LLAMA_PORT}" 2>/dev/null | head -1)
        if [ -n "$EXISTING_PID" ]; then
            echo "$EXISTING_PID" > "${APP_HOME}/llama-server.pid"
            echo "llama-server already running on port ${LLAMA_PORT} (adopted PID ${EXISTING_PID})"
        else
            echo "llama-server already running on port ${LLAMA_PORT} (PID unknown)"
        fi
    elif [ -n "$LLAMA_CMD" ]; then
        echo "Starting llama-server ($(basename "$MODEL_PATH")) on port ${LLAMA_PORT}..."
        mkdir -p "${APP_HOME}/logs"
        nohup "$LLAMA_CMD" \
            --model "$MODEL_PATH" \
            --port "$LLAMA_PORT" \
            -ngl "$LLAMA_GPU_LAYERS" \
            --flash-attn on \
            --jinja \
            --parallel "$LLAMA_PARALLEL" \
            -c "$LLAMA_TOTAL_CTX" \
            > "${APP_HOME}/logs/llama-server.log" 2>&1 &
        LLAMA_PID=$!
        echo "$LLAMA_PID" > "${APP_HOME}/llama-server.pid"
        # Wait for health (up to 120s — first launch downloads ~5GB GGUF)
        echo -n "Waiting for llama-server..."
        WAITED=0
        while [ "$WAITED" -lt 120 ]; do
            if curl -sf "http://127.0.0.1:${LLAMA_PORT}/health" > /dev/null 2>&1; then
                echo " ready (${WAITED}s)"
                break
            fi
            sleep 2
            WAITED=$((WAITED + 2))
            echo -n "."
        done
        if [ "$WAITED" -ge 120 ]; then
            echo " timeout (check ${APP_HOME}/logs/llama-server.log)"
        fi
    fi

    # Background watchdog: restart llama-server if it crashes
    if [ -f "${APP_HOME}/llama-server.pid" ] && [ -n "$LLAMA_CMD" ]; then
        (
            while true; do
                sleep 30
                if [ ! -f "${APP_HOME}/llama-server.pid" ]; then
                    break  # PID file removed — server stopped, exit watchdog
                fi
                if curl -sf "http://127.0.0.1:${LLAMA_PORT}/health" > /dev/null 2>&1; then
                    continue  # healthy
                fi
                WPID=$(cat "${APP_HOME}/llama-server.pid" 2>/dev/null)
                if [ -n "$WPID" ] && kill -0 "$WPID" 2>/dev/null; then
                    continue  # process alive but health flaky — give it time
                fi
                echo "[watchdog] llama-server died — restarting..." >> "${APP_HOME}/logs/llama-server.log"
                nohup "$LLAMA_CMD" \
                    --model "$MODEL_PATH" \
                    --port "$LLAMA_PORT" \
                    -ngl "$LLAMA_GPU_LAYERS" \
                    --flash-attn on \
                    --jinja \
                    --parallel "$LLAMA_PARALLEL" \
                    -c "$LLAMA_TOTAL_CTX" \
                    >> "${APP_HOME}/logs/llama-server.log" 2>&1 &
                NEW_PID=$!
                echo "$NEW_PID" > "${APP_HOME}/llama-server.pid"
                echo "[watchdog] llama-server restarted (PID $NEW_PID)" >> "${APP_HOME}/logs/llama-server.log"
            done
        ) &
    fi
fi

# Launch the server (Gradle-generated script)
"${SCRIPT_DIR}/server" "$@" &
SERVER_PID=$!
echo "$SERVER_PID" > "$PID_FILE"
echo "Wyrdsekai server started (PID $SERVER_PID)"

# Wait for the server process — if it exits, the trap cleans up the PID file
wait "$SERVER_PID"
LAUNCHER
    chmod +x "${dest}/bin/wyrdsekai"
}

# --- Build from source -------------------------------------------------------

build_from_source() {
    local repo_dir="$1" dest="$2"

    info "Building from source..."
    # GRADLE_OPTS targets the launcher JVM — suppresses restricted-method warnings
    # from native-platform on Java 24+ (Gradle issue #31625)
    export GRADLE_OPTS="${GRADLE_OPTS:-} --enable-native-access=ALL-UNNAMED"
    (cd "$repo_dir" && ./gradlew :server:clean :server:installDist -q --no-daemon)

    local dist="${repo_dir}/server/build/install/server"
    [ ! -d "$dist" ] && fatal "Build failed — server/build/install/server not found"

    mkdir -p "$dest"
    # Clean stale jars/scripts before copying — version upgrades leave old artifacts
    rm -rf "$dest/lib" "$dest/bin"
    cp -R "$dist"/* "$dest/"

    # Bundle room scripts
    if [ -d "${repo_dir}/scripts/rooms" ]; then
        mkdir -p "$dest/scripts"
        cp -R "${repo_dir}/scripts/rooms" "$dest/scripts/"
    fi
    if [ -d "${repo_dir}/scripts/between" ]; then
        cp -R "${repo_dir}/scripts/between" "$dest/scripts/"
    fi
    if [ -d "${repo_dir}/scripts/i18n" ]; then
        cp -R "${repo_dir}/scripts/i18n" "$dest/scripts/"
    fi

    # Create wrapper launcher with PID file management
    create_launcher "$dest"

    ok "Built and installed from source"
}

# --- Detect mode: source or release ------------------------------------------

detect_mode() {
    # Are we running from the repo root?
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)" || script_dir=""

    if [ -n "$script_dir" ] && [ -f "$script_dir/gradlew" ] && [ -f "$script_dir/server/build.gradle.kts" ]; then
        MODE="source"
        REPO_DIR="$script_dir"
    else
        MODE="release"
        REPO_DIR=""
    fi
}

# --- PATH setup --------------------------------------------------------------

setup_path() {
    local bin_dir="${INSTALL_DIR}/bin"
    local shell_rc

    if [ -f "$HOME/.zshrc" ]; then
        shell_rc="$HOME/.zshrc"
    elif [ -f "$HOME/.bashrc" ]; then
        shell_rc="$HOME/.bashrc"
    else
        shell_rc="$HOME/.profile"
    fi

    if grep -qF "$bin_dir" "$shell_rc" 2>/dev/null; then
        ok "$bin_dir already in PATH"
        return
    fi

    echo "" >> "$shell_rc"
    echo "# Wyrdsekai" >> "$shell_rc"
    echo "export PATH=\"${bin_dir}:\$PATH\"" >> "$shell_rc"
    ok "Added ${bin_dir} to PATH in ${shell_rc}"
    info "Run: source ${shell_rc}  (or open a new terminal)"
}

# --- Write env config --------------------------------------------------------

write_env() {
    local env_file="${INSTALL_DIR}/.env"
    [ -f "$env_file" ] && return  # don't overwrite existing

    cat > "$env_file" << EOF
# Wyrdsekai configuration — edit and restart
WYRDSEKAI_PORT=${PORT}
WYRDSEKAI_DATA_DIR=${INSTALL_DIR}/data
WYRDSEKAI_SCRIPTS_DIR=${INSTALL_DIR}/scripts

# Inference — llama-server is the default backend.
# Auto-detection will find llama-server on port 11525 or 8200.
# Override with explicit config:
WYRDSEKAI_LLAMA_ENABLED=true
WYRDSEKAI_MODEL_PATH=${INSTALL_DIR}/models/wyrdsekai-3.5-4b-v10-q4km.gguf

# SGLang — optional high-throughput backend for NVIDIA GPU.
# Start via: docker compose --profile sglang up -d
# WYRDSEKAI_SGLANG_ENABLED=true
# WYRDSEKAI_SGLANG_URL=http://localhost:8000

# SSH (secure remote access)
# WYRDSEKAI_SSH_ENABLED=true
# WYRDSEKAI_SSH_PORT=7022

# TLS (optional)
# WYRDSEKAI_TLS_ENABLED=true
# WYRDSEKAI_TLS_PORT=7443

# Between clustering (optional)
# WYRDSEKAI_BETWEEN_ENABLED=true
# WYRDSEKAI_ZONE_ID=home

# Relay — remote access from outside the LAN (optional)
# WYRDSEKAI_RELAY_ENABLED=false
# WYRDSEKAI_RELAY_URL=
# WYRDSEKAI_RELAY_TOKEN=

# Federation auto-accept: trust any zone that reaches you over the relay.
# Fit only for test meshes and single-owner households. Leave commented
# for anything that talks to strangers — default is manual approval via
# 'wyrd federate accept <zone>'.
# WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true
EOF
}

# --- PID file management -----------------------------------------------------

PID_FILE="${WYRDSEKAI_HOME:-$HOME/.wyrdsekai}/server.pid"

# Stop a running server instance via PID file.
# Returns 0 if stopped (or wasn't running), 1 if failed to stop.
stop_running_instance() {
    local pid_file="${1:-$PID_FILE}"
    if [ ! -f "$pid_file" ]; then
        return 0
    fi

    local pid
    pid=$(cat "$pid_file" 2>/dev/null)
    if [ -z "$pid" ]; then
        rm -f "$pid_file"
        return 0
    fi

    if kill -0 "$pid" 2>/dev/null; then
        info "Stopping running Wyrdsekai server (PID $pid)..."
        kill "$pid" 2>/dev/null || true
        # Wait up to 10 seconds for graceful shutdown
        local waited=0
        while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 10 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            warn "Server did not stop gracefully, sending SIGKILL..."
            kill -9 "$pid" 2>/dev/null || true
            sleep 1
        fi
        ok "Stopped previous server (PID $pid)"
    else
        info "Stale PID file (process $pid not running), cleaning up"
    fi

    rm -f "$pid_file"
    return 0
}

# Wait for a port to become free (up to 10 seconds).
wait_for_port_free() {
    local port="$1"
    local waited=0
    while [ "$waited" -lt 10 ]; do
        if ! (echo >/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    warn "Port $port still in use after 10 seconds"
    return 1
}

# --- Uninstall ---------------------------------------------------------------

do_uninstall() {
    info "Uninstalling Wyrdsekai from ${INSTALL_DIR}..."

    # Stop running instance via PID file
    stop_running_instance "${INSTALL_DIR}/server.pid"

    # Stop and remove service
    if [ "$(uname)" = "Linux" ]; then
        sudo systemctl stop wyrdsekai 2>/dev/null || true
        sudo systemctl disable wyrdsekai 2>/dev/null || true
        sudo rm -f /etc/systemd/system/wyrdsekai.service
        sudo systemctl stop wyrdsekai-rendezvous 2>/dev/null || true
        sudo systemctl disable wyrdsekai-rendezvous 2>/dev/null || true
        sudo rm -f /etc/systemd/system/wyrdsekai-rendezvous.service
        sudo systemctl daemon-reload 2>/dev/null || true
    elif [ "$(uname)" = "Darwin" ]; then
        local plist="$HOME/Library/LaunchAgents/dev.wyrdsekai.server.plist"
        launchctl unload "$plist" 2>/dev/null || true
        rm -f "$plist"
    fi

    # Remove PID file
    rm -f "${INSTALL_DIR}/server.pid"

    # Clean PATH from shell rc files
    for rc in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
        if [ -f "$rc" ]; then
            # Remove the PATH line and the comment above it
            sed -i.bak "/# Wyrdsekai/d;$(echo "${INSTALL_DIR}/bin" | sed 's/[\/&]/\\&/g')d" "$rc" 2>/dev/null || true
            rm -f "${rc}.bak"
        fi
    done

    if [ -d "${INSTALL_DIR}/data" ]; then
        warn "Preserving world data at ${INSTALL_DIR}/data"
        # Remove everything except data/
        find "${INSTALL_DIR}" -mindepth 1 -maxdepth 1 -not -name data -exec rm -rf {} +
        ok "Wyrdsekai uninstalled. Data preserved."
        info "To remove everything: rm -rf ${INSTALL_DIR}"
    else
        rm -rf "${INSTALL_DIR}"
        ok "Wyrdsekai uninstalled."
    fi
}

# --- CodeZaiku add-on --------------------------------------------------------

install_codezaiku() {
    echo ""
    info "Installing CodeZaiku..."

    local cz_dir="${INSTALL_DIR}/codezaiku"
    local installed=false

    # Try release download
    if [ "$MODE" = "release" ] && [ -n "$VERSION" ]; then
        if download_release "$CODEZAIKU_REPO" "$VERSION" "codezaiku" "$cz_dir"; then
            installed=true
        fi
    fi

    # Try build from source (sibling repo). The marker is build.gradle.kts:
    # core/build.gradle has not existed for a long time, so the old check for
    # it meant this branch never ran and the failure was silent -- the script
    # simply reported CodeZaiku "not found" and moved on.
    if ! $installed; then
        local cz_sources=("${REPO_DIR:+${REPO_DIR}/../codezaiku}" "$HOME/src/codezaiku" "../codezaiku")
        for dir in "${cz_sources[@]}"; do
            [ -z "$dir" ] && continue
            if [ -f "$dir/gradlew" ] && [ -f "$dir/core/build.gradle.kts" ]; then
                info "Building CodeZaiku from source ($dir)..."
                (cd "$dir" && GRADLE_OPTS="${GRADLE_OPTS:-} --enable-native-access=ALL-UNNAMED" \
                    ./gradlew :core:clean :core:installDist -q --no-daemon)
                # installDist names the tree after `applicationName`, which is
                # `codezaiku` -- not the module name.
                local cz_dist="$dir/core/build/install/codezaiku"
                if [ -d "$cz_dist" ]; then
                    mkdir -p "$cz_dir"
                    rm -rf "$cz_dir/lib" "$cz_dir/bin"
                    cp -R "$cz_dist"/* "$cz_dir/"
                    installed=true
                    break
                fi
            fi
        done
    fi

    if $installed; then
        ok "CodeZaiku installed to ${cz_dir}"
        # Delegate to the launcher installDist already wrote. The previous
        # version hand-rolled one with `-cp lib/* org.codeplane.core.Main`,
        # naming a class that had already been renamed upstream -- so it would
        # have died with ClassNotFoundException. Their own launcher tracks
        # their main class; we should not restate it.
        mkdir -p "${INSTALL_DIR}/bin"
        cat > "${INSTALL_DIR}/bin/codezaiku" << 'CZLAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/../codezaiku" && pwd)"
if [ -x "${SCRIPT_DIR}/../jre/bin/java" ]; then
    export JAVA_HOME="$(cd "${SCRIPT_DIR}/../jre" && pwd)"
fi
exec "${APP_HOME}/bin/codezaiku" "$@"
CZLAUNCHER
        chmod +x "${INSTALL_DIR}/bin/codezaiku"
    else
        warn "CodeZaiku not found. Install separately: curl -fsSL https://codezaiku.org/install | bash"
    fi
}

# --- Main --------------------------------------------------------------------

# --- Service setup -----------------------------------------------------------

setup_service() {
    if [ "$PLATFORM_OS" = "linux" ]; then
        local service_file="/etc/systemd/system/wyrdsekai.service"
        if [ -f "$service_file" ]; then
            ok "systemd service already exists"
            return
        fi

        info "Setting up systemd service..."
        sudo tee "$service_file" > /dev/null << SVCEOF
[Unit]
Description=Wyrdsekai Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$(whoami)
WorkingDirectory=${INSTALL_DIR}
EnvironmentFile=${INSTALL_DIR}/.env
ExecStart=${INSTALL_DIR}/bin/wyrdsekai
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SVCEOF
        sudo systemctl daemon-reload
        sudo systemctl enable wyrdsekai
        ok "systemd service installed and enabled (starts on boot)"
        info "  Start now:  sudo systemctl start wyrdsekai"
        info "  Logs:       journalctl -u wyrdsekai -f"

    elif [ "$PLATFORM_OS" = "darwin" ]; then
        local plist="$HOME/Library/LaunchAgents/dev.wyrdsekai.server.plist"
        if [ -f "$plist" ]; then
            ok "launchd agent already exists"
            return
        fi

        info "Setting up launchd agent..."
        sudo chown "$(whoami)" "$HOME/Library/LaunchAgents" 2>/dev/null || true
        mkdir -p "$HOME/Library/LaunchAgents"
        mkdir -p "${INSTALL_DIR}/logs"
        tee "$plist" > /dev/null << PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>dev.wyrdsekai.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>${INSTALL_DIR}/bin/wyrdsekai</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${INSTALL_DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
    <key>StandardOutPath</key>
    <string>${INSTALL_DIR}/logs/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>${INSTALL_DIR}/logs/stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>WYRDSEKAI_HOME</key>
        <string>${INSTALL_DIR}</string>
    </dict>
</dict>
</plist>
PLISTEOF
        launchctl load "$plist" 2>/dev/null || true
        ok "launchd agent installed (starts on login)"
        info "  Start now:  launchctl start dev.wyrdsekai.server"
        info "  Logs:       ${INSTALL_DIR}/logs/"
    fi
}

# Zone directory rendezvous aggregator.
# Runs as its own systemd unit so a scaling hiccup here does not take
# down the main node. Linux-only; macOS operators run `wyrd rendezvous start`
# ad-hoc or via their own launchd plist.
setup_rendezvous_service() {
    if [ "$PLATFORM_OS" != "linux" ]; then
        warn "Rendezvous auto-service is Linux-only — run 'wyrd rendezvous start' manually on macOS/Windows"
        return
    fi

    local service_file="/etc/systemd/system/wyrdsekai-rendezvous.service"
    if [ -f "$service_file" ]; then
        ok "wyrdsekai-rendezvous.service already exists"
        return
    fi

    if [ ! -x "${INSTALL_DIR}/bin/wyrd-rendezvous" ]; then
        warn "wyrd-rendezvous launcher not found at ${INSTALL_DIR}/bin/wyrd-rendezvous — skipping service setup"
        return
    fi

    info "Setting up wyrdsekai-rendezvous systemd service..."
    sudo tee "$service_file" > /dev/null << RDVZEOF
[Unit]
Description=Wyrdsekai Zone Directory Rendezvous
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$(whoami)
WorkingDirectory=${INSTALL_DIR}
Environment=WYRDSEKAI_RENDEZVOUS_PORT=7071
ExecStart=${INSTALL_DIR}/bin/wyrd-rendezvous
Restart=on-failure
RestartSec=5
MemoryMax=512M

[Install]
WantedBy=multi-user.target
RDVZEOF
    sudo systemctl daemon-reload
    sudo systemctl enable wyrdsekai-rendezvous
    ok "wyrdsekai-rendezvous service installed and enabled"
    info "  Start now:  sudo systemctl start wyrdsekai-rendezvous"
    info "  Logs:       journalctl -u wyrdsekai-rendezvous -f"
    info "  Port:       7071 (zone directory aggregator)"
}

main() {
    local version="" with_codezaiku=false with_rendezvous=false uninstall=false skip_inference=false skip_service=false port=""

    while [ $# -gt 0 ]; do
        case "$1" in
            --version)        version="$2"; shift 2 ;;
            --dir)            DEFAULT_DIR="$2"; shift 2 ;;
            --port)           port="$2"; shift 2 ;;
            --with-codezaiku) with_codezaiku=true; shift ;;
            --with-rendezvous) with_rendezvous=true; shift ;;
            --no-inference)   skip_inference=true; shift ;;
            --no-ollama)      skip_inference=true; shift ;;  # compat
            --no-service)     skip_service=true; shift ;;
            --uninstall)      uninstall=true; shift ;;
            --no-java)        shift ;;  # compat with old installer
            --no-docker)      shift ;;  # compat
            --no-build)       shift ;;  # compat
            --help|-h)
                head -18 "$0" | tail -14 | sed 's/^# \?//'
                exit 0
                ;;
            *) shift ;;
        esac
    done

    INSTALL_DIR="${WYRDSEKAI_HOME:-$DEFAULT_DIR}"
    PORT="${port:-${WYRDSEKAI_PORT:-$DEFAULT_PORT}}"

    if $uninstall; then
        do_uninstall
        exit 0
    fi

    # Banner
    echo ""
    echo -e "  ${BOLD}╔══════════════════════════════════════╗${NC}"
    echo -e "  ${BOLD}║         Wyrdsekai Installer          ║${NC}"
    echo -e "  ${BOLD}║    Distributed Text-Native World     ║${NC}"
    echo -e "  ${BOLD}╚══════════════════════════════════════╝${NC}"
    echo ""

    detect_platform
    detect_mode

    # ── 1. Install app ──
    if [ "$MODE" = "source" ]; then
        info "Mode: build from source (${REPO_DIR})"

        # Java required for build
        if ! check_java; then
            install_java_system
        fi

        build_from_source "$REPO_DIR" "$INSTALL_DIR"
    else
        info "Mode: release download"

        # Resolve version
        if [ -z "$version" ]; then
            info "Checking latest release..."
            version=$(latest_version "$REPO")
            [ -z "$version" ] && version="0.1.0"
        fi
        VERSION="$version"
        info "Version: ${version}"

        if ! download_release "$REPO" "$version" "wyrdsekai" "$INSTALL_DIR"; then
            warn "Release not found — looking for local source..."
            local search_dirs=("$HOME/src/wyrdsekai" "$HOME/wyrdsekai" "$(pwd)")
            for dir in "${search_dirs[@]}"; do
                if [ -f "$dir/gradlew" ] && [ -f "$dir/server/build.gradle.kts" ]; then
                    MODE="source"
                    REPO_DIR="$dir"
                    if ! check_java; then install_java_system; fi
                    build_from_source "$dir" "$INSTALL_DIR"
                    break
                fi
            done
            [ "$MODE" = "release" ] && fatal "No release or source found. See https://github.com/${REPO}"
        fi
    fi

    # Create launcher wrapper (idempotent — also created by build_from_source)
    create_launcher "$INSTALL_DIR"

    # Stop existing running instance before proceeding
    stop_running_instance "${INSTALL_DIR}/server.pid"

    # Wait for HTTP port to free if still in use
    wait_for_port_free "$PORT" || true

    # ── 2. Java runtime ──
    if ! check_java; then
        # For release installs, bundle a JRE rather than requiring system install
        if [ "$MODE" = "release" ]; then
            install_java_bundled || install_java_system
        else
            install_java_system
        fi
    fi

    # ── 3. Config + data ──
    write_env
    mkdir -p "${INSTALL_DIR}/data"

    # ── 4. PATH ──
    setup_path

    # ── 5. Hardware ──
    echo ""
    info "Hardware:"
    detect_gpu
    detect_tier
    detect_ram

    # ── 6. Inference backend (llama-server + model download) ──
    if ! $skip_inference; then
        echo ""
        setup_inference
    fi

    # ── 7. nats-server (Between cross-node bridge) ──
    # Required for federation + cross-node room state. Without it Between
    # falls back to single-node mode and TrainingPeerService can't subscribe.
    echo ""
    setup_nats_server

    # ── 8. Relay (optional remote access) ──
    echo ""
    offer_relay

    # ── 9. Auto-start service ──
    if ! $skip_service; then
        echo ""
        setup_service
    fi

    # ── 9b. Rendezvous directory aggregator (optional, Linux only) ──
    if $with_rendezvous; then
        echo ""
        setup_rendezvous_service
    fi

    # ── 10. CodeZaiku (optional) ──
    if $with_codezaiku; then
        install_codezaiku
    fi

    # ── Done ──
    echo ""
    echo -e "${GREEN}${BOLD}Wyrdsekai installed!${NC}"
    echo ""
    echo "  Start server:     ${BOLD}wyrdsekai${NC}    (or: ${INSTALL_DIR}/bin/wyrdsekai)"
    echo "  Stop server:      ${BOLD}wyrdsekai stop${NC}"
    echo "  Server status:    ${BOLD}wyrdsekai status${NC}"
    echo "  Connect (SSH):    ${BOLD}ssh -p 7022 user@localhost${NC}"
    echo "  Connect (Telnet): ${BOLD}telnet localhost $((PORT + 1))${NC}"
    echo "  Browser:          ${BOLD}http://localhost:${PORT}${NC}"
    echo "  Config:           ${INSTALL_DIR}/.env"
    echo "  World data:       ${INSTALL_DIR}/data"
    echo "  PID file:         ${INSTALL_DIR}/server.pid"
    echo ""
    echo "  Uninstall:        ${BOLD}${BASH_SOURCE[0]:-install.sh} --uninstall${NC}"
    if [ "$MODE" = "release" ]; then
        echo "  Or:               ${BOLD}curl -fsSL https://wyrdsekai.org/install | bash -s -- --uninstall${NC}"
    fi
    echo ""
}

main "$@"
