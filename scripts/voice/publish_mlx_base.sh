#!/usr/bin/env bash
# one-shot publish of the wyrdsekai-org
# pinned MLX voice base. Run this on mac-node (or any Apple Silicon host
# with mlx-lm installed) AFTER:
#   1. scp the Qwen3.5-4B BF16 HF source from home-server to local disk:
#      scp -r home-server:/home/you/models/Qwen3.5-4B-hf /tmp/Qwen3.5-4B-hf
#   2. export HF_TOKEN=<token with write access to wyrdsekai-org>
#   3. (optional) huggingface-cli whoami  -- confirm token works.
#
# Then:
#   bash scripts/voice/publish_mlx_base.sh /tmp/Qwen3.5-4B-hf
#
# Idempotent: skips convert if /tmp/Qwen3.5-4B-v10-mlx-4bit already exists.
# Re-runs huggingface_hub upload_folder which itself deduplicates by hash.
#
# Output: huggingface.co/wyrdsekai/companion-3.5-4b-mlx populated.
# Once published, `wyrd setup` Darwin branch on fresh installs pulls
# from there as primary (with mlx-community/Qwen3.5-4B-MLX-4bit as
# fallback — see bin/wyrd Phase 6A).

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]] || [[ "$(uname -m)" != "arm64" ]]; then
    echo "ERROR: this script requires Apple Silicon (mlx_lm.convert needs Metal)." >&2
    echo "       Refusing on $(uname -s) $(uname -m)." >&2
    exit 1
fi

if [[ -z "${1:-}" ]]; then
    echo "Usage: $0 <hf-source-dir>" >&2
    echo "  e.g. $0 /tmp/Qwen3.5-4B-hf" >&2
    exit 2
fi
SRC="$1"
if [[ ! -f "$SRC/config.json" ]]; then
    echo "ERROR: $SRC/config.json not found." >&2
    echo "       scp the HF source from home-server first:" >&2
    echo "         scp -r home-server:/home/you/models/Qwen3.5-4B-hf $SRC" >&2
    exit 3
fi

if [[ -z "${HF_TOKEN:-}" ]]; then
    echo "ERROR: HF_TOKEN env var not set." >&2
    echo "       Create a token at https://huggingface.co/settings/tokens" >&2
    echo "       with write access to wyrdsekai-org, then:" >&2
    echo "         export HF_TOKEN=<token>" >&2
    exit 4
fi

REPO="${WYRD_MLX_PUBLISH_REPO:-wyrdsekai/companion-3.5-4b-mlx}"
OUT="${WYRD_MLX_CONVERT_OUT:-/tmp/Qwen3.5-4B-v10-mlx-4bit}"
VENV_PY="${WYRDSEKAI_VOICE_BACKEND_PYTHON:-$HOME/.wyrdsekai/mlx-venv/bin/python}"

if [[ ! -x "$VENV_PY" ]]; then
    echo "ERROR: mlx-venv python not at $VENV_PY." >&2
    echo "       Run wyrd setup or scripts/mac-node-bootstrap-mlx-trainer.sh first." >&2
    exit 5
fi

# Step 0: rewrite HF config.json if newer transformers tagged it as
# `qwen3_5_text` / `Qwen3_5ForCausalLM` — mlx-lm 0.31.x only knows the
# older `qwen3_5` / `Qwen3_5ForConditionalGeneration` naming. Idempotent
# + reversible (saves config.json.original on first run). The underlying
# tensor layout is identical, including the GatedDeltaNet hybrid path
# used by the 9B (full_attention_interval=4).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$VENV_PY" "$SCRIPT_DIR/mlx_rewrite_qwen35_config.py" "$SRC"

# Step 1: mlx_lm.convert. Idempotent — skip if output exists.
if [[ -f "$OUT/config.json" ]]; then
    echo "[publish-mlx] $OUT already exists; skipping convert."
else
    echo "[publish-mlx] Converting $SRC → $OUT (MLX-4bit, q-bits=4)..."
    "$VENV_PY" -m mlx_lm convert \
        --hf-path "$SRC" \
        --mlx-path "$OUT" \
        -q --q-bits 4 \
        --q-group-size 64
    echo "[publish-mlx] Convert complete."
fi

ls -lh "$OUT" | head -10

# Step 2: upload_folder. huggingface_hub auto-creates the repo if the
# token has access to the org. Allow patterns scope down what we push.
echo "[publish-mlx] Uploading $OUT → $REPO ..."
"$VENV_PY" - <<PY
import os, sys
from huggingface_hub import HfApi, create_repo
api = HfApi(token=os.environ["HF_TOKEN"])
repo = "$REPO"
try:
    api.create_repo(repo, repo_type="model", exist_ok=True)
    print(f"repo ready: {repo}")
except Exception as e:
    print(f"create_repo: {e}", file=sys.stderr)
    raise
api.upload_folder(
    folder_path="$OUT",
    repo_id=repo,
    repo_type="model",
    allow_patterns=["*.safetensors*", "*.json", "tokenizer*", "*.jinja"],
)
print(f"upload complete: huggingface.co/{repo}")
PY

echo "[publish-mlx] Done."
echo "  Verify: huggingface-cli download $REPO --local-dir /tmp/probe-pull"
echo "  Then on a fresh mac-node install: wyrd setup pulls from $REPO first."
