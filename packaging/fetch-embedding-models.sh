#!/usr/bin/env bash
#
# fetch-embedding-models.sh — Download bundled embedding-model assets for
# packaging into .deb / .tar.gz / .pkg distributions.
#
# Mirrors the fetch-llama-cpu.sh pattern: large binary assets are NOT
# checked into git; this script fetches them into packaging/embedding-models/
# where build-deb.sh (and friends) can pick them up and stage them under
# /opt/wyrdsekai/share/embedding-models/ (or the platform equivalent).
#
# At runtime, `wyrd setup` looks for these files in three places, in order:
#   1. $MODELS_DIR/<file>                                  (already installed)
#   2. <install-prefix>/share/embedding-models/<file>      (bundled by .deb/.pkg)
#   3. HuggingFace download via curl                       (last resort)
#
# Models fetched:
#   - paraphrase-multilingual-MiniLM-L12-v2-q8.onnx        (~113MB) — bundled default
#   - paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json (~17MB)
#
# Usage:
#   ./packaging/fetch-embedding-models.sh
#   FORCE=1 ./packaging/fetch-embedding-models.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/embedding-models"
mkdir -p "$OUT_DIR"

info() { echo -e "\033[36m[fetch-emb]\033[0m $*"; }
ok()   { echo -e "\033[32m[fetch-emb]\033[0m $*"; }
err()  { echo -e "\033[31m[fetch-emb]\033[0m $*" >&2; }

# Each entry: <local-filename>|<remote-url>
ASSETS=(
    "paraphrase-multilingual-MiniLM-L12-v2-q8.onnx|https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
    "paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json|https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json"
)

# Local caches searched BEFORE the network. paraphrase-l12 is the product's bundled
# default — the thing every install is supposed to already have — so a build must not
# be able to fail just because a third party is having a bad day. On 2026-07-13
# HuggingFace moved these files onto its Xet CAS backend, which began answering
# 403 AccessDenied to us (anonymously AND with a valid token), and a fresh second-node install
# came up with retrieval disabled. Any box that has ever run wyrdsekai already has the
# ONNX on disk; prefer it.
CACHES=(
    "${WYRDSEKAI_EMB_CACHE:-}"
    "$HOME/.wyrdsekai/models"
    "$HOME/models-cache"
)

find_local() {
    local want="$1"
    for dir in "${CACHES[@]}"; do
        [[ -n "$dir" && -f "$dir/$want" ]] && { echo "$dir/$want"; return 0; }
    done
    # The HF hub cache keeps the tokenizer under its snapshot hash.
    local hub="$HOME/.cache/huggingface/hub/models--Xenova--paraphrase-multilingual-MiniLM-L12-v2/snapshots"
    if [[ "$want" == *tokenizer.json && -d "$hub" ]]; then
        local hit
        hit=$(find "$hub" -name tokenizer.json -print -quit 2>/dev/null || true)
        [[ -n "$hit" ]] && { echo "$hit"; return 0; }
    fi
    return 1
}

for entry in "${ASSETS[@]}"; do
    fname="${entry%%|*}"
    url="${entry#*|}"
    target="$OUT_DIR/$fname"
    if [[ -f "$target" ]] && [[ "${FORCE:-0}" != "1" ]]; then
        ok "$fname already present ($(du -sh "$target" | cut -f1)) — use FORCE=1 to re-download"
        continue
    fi
    if [[ "${FORCE:-0}" != "1" ]] && local_hit=$(find_local "$fname"); then
        cp -L "$local_hit" "$target"
        ok "$fname from local cache: $local_hit ($(du -sh "$target" | cut -f1))"
        continue
    fi
    info "Fetching $fname..."
    if ! curl -fSL --retry 2 --retry-delay 3 --progress-bar -o "$target" "$url"; then
        err "Download failed: $url"
        err "HuggingFace serves this via its Xet CAS backend (cas-bridge.xethub.hf.co),"
        err "which has returned 403 to us before. Drop the file into one of:"
        for dir in "${CACHES[@]}"; do [[ -n "$dir" ]] && err "    $dir/$fname"; done
        rm -f "$target"
        exit 1
    fi
    ok "Fetched $fname ($(du -sh "$target" | cut -f1))"
done

ok "Embedding-model assets staged in $OUT_DIR"
