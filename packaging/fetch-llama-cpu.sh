#!/usr/bin/env bash
#
# fetch-llama-cpu.sh — Download the llama-server binary bundled in the Linux
# .deb and .tar.gz packages (the name is historical: it was the CPU-only build).
#
# Since 0.5.8 the archive is llama.cpp's Vulkan build for Linux amd64: the same
# server with the Vulkan backend (libggml-vulkan.so) beside it. ggml loads the
# backends it finds next to the binary; on a host with a GPU and a Vulkan driver
# the layers can go to the GPU, on a host without either the backend fails to
# load and the CPU serves, as before. A household's 4B voice model ran on the
# processor for five weeks beside an idle Radeon (2026-10-19). The CPU archive
# is the fallback (and the only one for arm64). The binary is placed at
# packaging/llama-server and picked up by build-deb.sh alongside the bundled
# nats-server + metasearch binaries.
#
# We pin a specific release for reproducibility. Override LLAMA_CPP_RELEASE
# to grab a different version.
#
# Usage:
#   ./packaging/fetch-llama-cpu.sh
#   LLAMA_CPP_RELEASE=b5000 ./packaging/fetch-llama-cpu.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Pinned to a known-working release. Update after validating a newer build
# ships the same archive layout (bin/libs in one dir) and loads cleanly.
#
# MINIMUM b8838 — this is the floor for serving the wyrdsekai 3.5 (Qwen3.5/qwen35
# arch) GGUFs, incl. the argot Tier-B path (base 4B + MLP-only LoRA). Upstream
# qwen3.5 support landed in #19468 (model+vocab "qwen35" pre-tokenizer), present
# from ~b88xx. Verified 2026-06-08: stock b8838 ubuntu-x64 loads our qwen35 GGUF
# cleanly ( served path). Do NOT regress the pin below b8838 or
# argot/3.5 models fail to load with "unknown model architecture: 'qwen35'".
RELEASE="${LLAMA_CPP_RELEASE:-b8838}"
ARCH="${WYRDSEKAI_ARCH:-$(uname -m)}"
case "$ARCH" in
    x86_64|amd64) ARCH_TAG="x64" ;;
    aarch64|arm64) ARCH_TAG="arm64" ;;
    *) echo "Unsupported arch: $ARCH" >&2; exit 1 ;;
esac

# Recent releases (b88xx+) ship .tar.gz, not .zip. Keep a .zip fallback for
# older pins. curl follows redirects; the GitHub release CDN occasionally
# returns 502/504 — retry the fetch up to 3 times before giving up.
URL_VULKAN="https://github.com/ggml-org/llama.cpp/releases/download/${RELEASE}/llama-${RELEASE}-bin-ubuntu-vulkan-${ARCH_TAG}.tar.gz"
URL_TGZ="https://github.com/ggml-org/llama.cpp/releases/download/${RELEASE}/llama-${RELEASE}-bin-ubuntu-${ARCH_TAG}.tar.gz"
URL_ZIP="https://github.com/ggml-org/llama.cpp/releases/download/${RELEASE}/llama-${RELEASE}-bin-ubuntu-${ARCH_TAG}.zip"
# The Vulkan build first (x64 only; WYRDSEKAI_LLAMA_CPU_ONLY=1 skips it), then the CPU archives.
URLS=()
[[ "$ARCH_TAG" == "x64" && "${WYRDSEKAI_LLAMA_CPU_ONLY:-0}" != "1" ]] && URLS+=("$URL_VULKAN")
URLS+=("$URL_TGZ" "$URL_ZIP")
OUT_DIR="$SCRIPT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

info() { echo -e "\033[36m[fetch]\033[0m $*"; }
ok()   { echo -e "\033[32m[fetch]\033[0m $*"; }
err()  { echo -e "\033[31m[fetch]\033[0m $*" >&2; }

if [[ -f "$OUT_DIR/llama-server" ]] && [[ "${FORCE:-0}" != "1" ]]; then
    ok "packaging/llama-server already present (use FORCE=1 to re-download)"
    "$OUT_DIR/llama-server" --version 2>&1 | head -3 || true
    exit 0
fi

info "Fetching llama.cpp $RELEASE (ubuntu-$ARCH_TAG, Vulkan build first)..."
fetched=""
for url in "${URLS[@]}"; do
    # Preserve compound extensions like .tar.gz.
    base="${url##*/}"
    case "$base" in
        *.tar.gz) suffix="tar.gz" ;;
        *.zip)    suffix="zip" ;;
        *)        suffix="${base##*.}" ;;
    esac
    for attempt in 1 2 3; do
        if curl -fSL --retry 2 --retry-delay 3 -o "$TMP_DIR/llama.$suffix" "$url" 2>&1 | tail -1; then
            fetched="$TMP_DIR/llama.$suffix"
            break 2
        fi
        info "attempt $attempt/3 for $(basename "$url") failed, retrying..."
        sleep 2
    done
done
if [[ -z "$fetched" ]]; then
    err "Download failed from both .tar.gz and .zip sources."
    err "Check https://github.com/ggml-org/llama.cpp/releases for available tags."
    exit 1
fi

info "Extracting $(basename "$fetched")..."
mkdir -p "$TMP_DIR/extracted"
case "$fetched" in
    *.tar.gz) tar xzf "$fetched" -C "$TMP_DIR/extracted/" ;;
    *.zip)    unzip -q "$fetched" -d "$TMP_DIR/extracted/" ;;
esac

# Release layouts vary; find the server binary.
SERVER_BIN=$(find "$TMP_DIR" -name 'llama-server' -type f 2>/dev/null | head -1)
if [[ -z "$SERVER_BIN" ]]; then
    err "llama-server not found in release archive. Layout may have changed."
    find "$TMP_DIR" -maxdepth 4 -type f 2>/dev/null | head -20 >&2
    exit 1
fi

cp "$SERVER_BIN" "$OUT_DIR/llama-server"
chmod +x "$OUT_DIR/llama-server"

# IMPORTANT: llama.cpp's ggml_backend_load_all() searches the exe's directory
# for backend .so files, NOT LD_LIBRARY_PATH. Ship the libs alongside the
# binary (in packaging/) and the .deb copies them into /opt/wyrdsekai/bin/
# together so runtime discovery works without any environment setup.
BIN_DIR=$(dirname "$SERVER_BIN")
lib_count=0
for lib in "$BIN_DIR"/*.so "$BIN_DIR"/*.so.*; do
    [[ -f "$lib" ]] || continue
    cp "$lib" "$OUT_DIR/"
    lib_count=$((lib_count + 1))
done

ok "Wrote $(du -sh "$OUT_DIR/llama-server" | cut -f1) to packaging/llama-server + $lib_count backend .so files"
if [[ -f "$OUT_DIR/libggml-vulkan.so" ]]; then ok "the Vulkan backend is beside it (libggml-vulkan.so)"
else info "no Vulkan backend in this build: the bundled server is CPU-only"; fi
"$OUT_DIR/llama-server" --version 2>&1 | head -3 || \
    echo "(binary staged; runtime needs the bundled .so files adjacent to it)"
