#!/usr/bin/env bash
# Build the all-in-one docker image (see packaging/docker-aio/Dockerfile).
#   ./packaging/build-aio.sh            # CPU variant  -> wyrdsekai/wyrdsekai:cpu
#   ./packaging/build-aio.sh cuda       # CUDA variant -> wyrdsekai/wyrdsekai:cuda
#   SKIP_DIST=1 ./packaging/build-aio.sh   # reuse existing dist tree
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT="${1:-cpu}"
case "$VARIANT" in
    cpu)  LLAMA_BASE="ghcr.io/ggml-org/llama.cpp:server" ;;
    cuda) LLAMA_BASE="ghcr.io/ggml-org/llama.cpp:server-cuda" ;;
    *) echo "usage: $0 [cpu|cuda]"; exit 1 ;;
esac

if [[ "${SKIP_DIST:-0}" != "1" ]]; then
    echo "[aio] building dist tree..."
    ./packaging/build-dist.sh
fi

DIST_DIR=$(ls -dt build/dist/wyrdsekai-*/ | head -1)
DIST_DIR="${DIST_DIR%/}"
echo "[aio] using dist: $DIST_DIR"

docker build \
    -f packaging/docker-aio/Dockerfile \
    --build-arg LLAMA_BASE="$LLAMA_BASE" \
    --build-arg DIST_DIR="$DIST_DIR" \
    -t "wyrdsekai/wyrdsekai:$VARIANT" \
    .

echo "[aio] built wyrdsekai/wyrdsekai:$VARIANT"
echo "[aio] run:  docker run -d --name wyrdsekai $([[ $VARIANT == cuda ]] && echo '--gpus all ')-v wyrd-data:/data -p 7070:7070 -p 7022:7022 wyrdsekai/wyrdsekai:$VARIANT"
echo "[aio] invite code appears in:  docker logs -f wyrdsekai"
