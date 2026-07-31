#!/usr/bin/env bash
# run-node.sh — Start a Wyrdsekai node with role-appropriate configuration.
#
# Usage:
#   ./scripts/run-node.sh <role> [options]
#
# Roles:
#   phone    — Tiny model (0.6B), 2K context, relay to household hub
#   laptop   — Mid model (4B CPU), 4K context, self-sufficient
#   desktop  — Large model (8B GPU), 16K context, household relay target
#   server   — Largest model (30B+ GPU), 32K context, inference hub
#
# Options:
#   --nats-url <url>        NATS URL (default: nats://127.0.0.1:4222)
#   --inference-url <url>   Local inference backend URL
#   --relay-url <url>       Remote inference relay URL (phone/laptop only)
#   --zone-id <id>          Zone ID (default: derived from hostname)
#   --port <port>           HTTP/WS port (default: 7070)
#   --artery-port <port>    Pekko artery port (default: 25520)
#   --nats-auto-start       Auto-start local NATS server (server role only)
#
# Environment variables (override everything):
#   WYRDSEKAI_PORT, WYRDSEKAI_ARTERY_HOST, WYRDSEKAI_ARTERY_PORT,
#   WYRDSEKAI_NATS_URL, WYRDSEKAI_ZONE_ID, WYRDSEKAI_LLAMA_URL, etc.
#
# Examples:
#   # Server node (gpu-host) — runs NATS, big model
#   ./scripts/run-node.sh server --nats-auto-start --inference-url http://localhost:8090
#
#   # Desktop node (home-server) — points to gpu-host NATS
#   ./scripts/run-node.sh desktop --nats-url nats://198.51.100.42:4222 --inference-url http://localhost:8000
#
#   # Laptop node (third-node) — CPU inference, points to gpu-host NATS
#   ./scripts/run-node.sh laptop --nats-url nats://198.51.100.42:4222 --inference-url http://localhost:8080
#
#   # Phone node — local tiny + relay to server
#   ./scripts/run-node.sh phone --nats-url nats://198.51.100.42:4222 \
#       --inference-url http://localhost:8080 --relay-url http://198.51.100.42:8090

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ROLE="${1:-}"
if [[ -z "$ROLE" ]]; then
    echo "Usage: $0 <phone|laptop|desktop|server> [options]"
    exit 1
fi
shift

# Defaults
NATS_URL="${WYRDSEKAI_NATS_URL:-nats://127.0.0.1:4222}"
INFERENCE_URL=""
RELAY_URL=""
ZONE_ID="${WYRDSEKAI_ZONE_ID:-home}"
PORT="${WYRDSEKAI_PORT:-7070}"
ARTERY_PORT="${WYRDSEKAI_ARTERY_PORT:-25520}"
NATS_AUTO_START="false"

# Parse options
while [[ $# -gt 0 ]]; do
    case "$1" in
        --nats-url) NATS_URL="$2"; shift 2 ;;
        --inference-url) INFERENCE_URL="$2"; shift 2 ;;
        --relay-url) RELAY_URL="$2"; shift 2 ;;
        --zone-id) ZONE_ID="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --artery-port) ARTERY_PORT="$2"; shift 2 ;;
        --nats-auto-start) NATS_AUTO_START="true"; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# Detect local IP (prefer non-loopback, non-docker)
LOCAL_IP=""
if command -v ip &>/dev/null; then
    LOCAL_IP=$(ip -4 addr show | grep 'inet ' | grep -v '127.0.0.1' | grep -v 'docker' | head -1 | awk '{print $2}' | cut -d/ -f1)
fi
if [[ -z "$LOCAL_IP" ]]; then
    LOCAL_IP="127.0.0.1"
fi

# Role-specific defaults
case "$ROLE" in
    phone)
        export WYRDSEKAI_MODEL="${WYRDSEKAI_MODEL:-Qwen3-0.6B}"
        LLAMA_PRIORITY=100
        RELAY_PRIORITY=10
        ;;
    laptop)
        export WYRDSEKAI_MODEL="${WYRDSEKAI_MODEL:-Qwen3-4B}"
        LLAMA_PRIORITY=20
        RELAY_PRIORITY=10
        ;;
    desktop)
        export WYRDSEKAI_MODEL="${WYRDSEKAI_MODEL:-Qwen3-8B}"
        LLAMA_PRIORITY=10
        RELAY_PRIORITY=""
        ;;
    server)
        export WYRDSEKAI_MODEL="${WYRDSEKAI_MODEL:-Qwen3-30B-A3B}"
        LLAMA_PRIORITY=10
        RELAY_PRIORITY=""
        NATS_AUTO_START="${NATS_AUTO_START:-true}"
        ;;
    *)
        echo "Unknown role: $ROLE (use phone, laptop, desktop, or server)"
        exit 1
        ;;
esac

# Export common env vars
export WYRDSEKAI_PORT="$PORT"
export WYRDSEKAI_ARTERY_HOST="${WYRDSEKAI_ARTERY_HOST:-$LOCAL_IP}"
export WYRDSEKAI_ARTERY_PORT="$ARTERY_PORT"
# Seed nodes are auto-constructed from artery host/port in Main.java
export WYRDSEKAI_BETWEEN_ENABLED="true"
export WYRDSEKAI_ZONE_ID="$ZONE_ID"
export WYRDSEKAI_ZONE_NAME="${WYRDSEKAI_ZONE_NAME:-$ZONE_ID}"
export WYRDSEKAI_NATS_URL="$NATS_URL"
export WYRDSEKAI_NATS_AUTO_START="$NATS_AUTO_START"
export WYRDSEKAI_HOSTNAME="${WYRDSEKAI_HOSTNAME:-$LOCAL_IP}"

# Configure local inference backend
if [[ -n "$INFERENCE_URL" ]]; then
    export WYRDSEKAI_LLAMA_ENABLED="true"
    export WYRDSEKAI_LLAMA_URL="$INFERENCE_URL"
    export WYRDSEKAI_LLAMA_PRIORITY="$LLAMA_PRIORITY"
fi

# Configure relay backend (phone/laptop → server/desktop)
if [[ -n "$RELAY_URL" && -n "$RELAY_PRIORITY" ]]; then
    # For relay, enable sglang backend as the relay target with higher priority (lower number)
    export WYRDSEKAI_SGLANG_ENABLED="true"
    export WYRDSEKAI_SGLANG_URL="$RELAY_URL"
fi

# JVM heap sizing per role
case "$ROLE" in
    phone)  HEAP="-Xmx512m" ;;
    laptop) HEAP="-Xmx1g" ;;
    desktop) HEAP="-Xmx2g" ;;
    server) HEAP="-Xmx4g" ;;
esac
export JAVA_OPTS="${JAVA_OPTS:-} -Xms256m $HEAP"

echo "=== Wyrdsekai Node ==="
echo "  Role:     $ROLE"
echo "  Zone:     $ZONE_ID"
echo "  Host:     $WYRDSEKAI_ARTERY_HOST"
echo "  Port:     $PORT (artery: $ARTERY_PORT)"
echo "  NATS:     $NATS_URL (auto-start: $NATS_AUTO_START)"
echo "  Model:    $WYRDSEKAI_MODEL"
if [[ -n "$INFERENCE_URL" ]]; then
    echo "  Inference: $INFERENCE_URL (priority: $LLAMA_PRIORITY)"
fi
if [[ -n "$RELAY_URL" ]]; then
    echo "  Relay:    $RELAY_URL (priority: $RELAY_PRIORITY)"
fi
echo "====================="

cd "$PROJECT_DIR"

# Run via Gradle (uses the application plugin's run task)
exec ./gradlew :server:run \
    --args="--cluster" \
    --console=plain \
    -q
