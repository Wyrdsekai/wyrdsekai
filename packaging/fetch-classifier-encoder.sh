#!/usr/bin/env bash
#
# fetch-classifier-encoder.sh — put the SetFit routing encoder in the source
# tree so the build can bundle it.
#
# WHY THIS EXISTS: the encoder is 113MB of build output, gitignored by
# `core/src/main/resources/models/*.onnx`. build-dist.sh hard-refuses to make a
# release without it. Together those two facts meant a clone of the public
# repository could not build an installer at all — the build stopped with an
# instruction to copy the file off a machine the reader does not have. The
# download page offers "build from source" as the answer to "a checksum does
# not prove the host was not compromised", so that promise was not true.
#
# Resolution order matches fetch-embedding-models.sh: anything already on disk
# beats the network, and HuggingFace is tried before our own host so a busy
# week does not land on the box that also serves the website.
#
#   1. already in the source tree      → nothing to do
#   2. ~/.wyrdsekai/models/            → a node on this machine already has it
#   3. HuggingFace                     → global CDN, costs us nothing
#   4. wyrdsekai.org                   → fallback if HF is unreachable
#
# The sha256 in models-index.json is verified whichever source answered. With
# more than one origin, "the download worked" stops implying "from the source
# we meant", and the hash is what closes that gap.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
INDEX="$PROJECT_DIR/models-index.json"
MODEL_ID="classifier-setfit-encoder"

info() { echo -e "\033[36m[fetch-enc]\033[0m $*"; }
ok()   { echo -e "\033[32m[fetch-enc]\033[0m $*"; }
warn() { echo -e "\033[33m[fetch-enc]\033[0m $*" >&2; }
err()  { echo -e "\033[31m[fetch-enc]\033[0m $*" >&2; }

# sha256, portably. `sha256sum` is GNU coreutils and is NOT on a stock macOS —
# only `shasum` and `openssl` are guaranteed there. Hardcoding sha256sum made
# these scripts Linux-only, which defeats the point: the whole reason they exist
# is so that someone who is not us can build a package.
_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    else
        echo "no sha256 tool available (need sha256sum, shasum or openssl)" >&2
        return 1
    fi
}

[[ -f "$INDEX" ]] || { err "models-index.json not found at $INDEX"; exit 1; }

read -r FILE SHA SIZE HF_URL FALLBACK_URL <<EOF
$(python3 - "$INDEX" "$MODEL_ID" <<'PY'
import json, sys
idx, mid = sys.argv[1], sys.argv[2]
for m in json.load(open(idx)).get("models", []):
    if m.get("id") != mid:
        continue
    hf = ""
    if m.get("hf_repo") and m.get("hf_revision") and m.get("published_file"):
        hf = f"https://huggingface.co/{m['hf_repo']}/resolve/{m['hf_revision']}/{m['published_file']}"
    print(m["local_file"], m.get("sha256", "-"), m.get("size", 0), hf or "-", m.get("url", "-"))
    break
PY
)
EOF

DEST="$PROJECT_DIR/core/src/main/resources/models/$FILE"

verify() {  # verify <path> — true if it matches the pinned hash
    [[ -f "$1" ]] || return 1
    [[ "$SHA" == "-" ]] && return 0
    local got; got=$(_sha256 "$1")
    [[ "$got" == "$SHA" ]]
}

if verify "$DEST"; then
    ok "already present and verified: $FILE"
    exit 0
fi
[[ -f "$DEST" ]] && warn "existing copy fails the pinned checksum — refetching"

CACHE="$HOME/.wyrdsekai/models/$FILE"
if verify "$CACHE"; then
    mkdir -p "$(dirname "$DEST")"
    cp "$CACHE" "$DEST"
    ok "restored from local node cache: $CACHE"
    exit 0
fi

mkdir -p "$(dirname "$DEST")"
for url in "$HF_URL" "$FALLBACK_URL"; do
    [[ "$url" == "-" || -z "$url" ]] && continue
    info "fetching from ${url#https://}"
    # --http1.1 and -C - mirror _download in bin/wyrd: HTTP/2 streams drop on
    # large files, and a retry should resume rather than restart.
    if curl -fL --http1.1 --progress-bar --retry 5 --retry-delay 3 \
            --retry-all-errors -C - -o "$DEST.part" "$url"; then
        if verify "$DEST.part"; then
            mv "$DEST.part" "$DEST"
            ok "fetched and verified ($(du -h "$DEST" | cut -f1))"
            exit 0
        fi
        warn "checksum mismatch from this mirror — discarding, trying next"
    else
        warn "unreachable: ${url#https://}"
    fi
    rm -f "$DEST.part"
done

err "Could not obtain $FILE from any mirror."
err "Regenerate it with the retrain-classifier-head recipe"
err "(scripts/classifier/train_setfit.py + export_setfit_encoder_onnx.py),"
err "or set WYRDSEKAI_ALLOW_MISSING_SETFIT=1 to build a dev package with"
err "degraded routing accuracy."
exit 1
