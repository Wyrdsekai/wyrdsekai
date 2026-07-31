#!/usr/bin/env bash
#
# Bootstrap mac-node as a wyrdsekai MLX peer-training host.
#
# Run this AFTER the .pkg has been installed and the server is up. It:
#   1. Installs Python build deps (Homebrew python@3.12 if missing).
#   2. Creates ~/.wyrdsekai/mlx-venv with mlx-lm + huggingface-hub.
#   3. Smoke-tests `python -m mlx_lm.lora --help` to confirm the CLI shape
#      VoiceAligner.runMlxFineTune emits is still valid.
#   4. Pre-pulls the Qwen3-1.7B base in HF format (small model, fits in
#      memory and matches what test-node has — proves the pipeline before
#      we push 9B work here).
#   5. Stamps WYRDSEKAI_VOICE_BACKEND=mlx + WYRDSEKAI_PEER_TRAINING_HOST=1
#      into ~/.wyrdsekai/env so the mac-node server picks them up on next
#      restart.
#
# Idempotent. Safe to re-run.
#
# proposer + #429 Phase 3 PeerDelegated together
# need at least one node in the household that can train. mac-node is the
# closest candidate now that gpu-host is unreachable.

set -euo pipefail

# ── 0. Sanity ────────────────────────────────────────────────────────────
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: this script is for macOS (mac-node). Refusing on $(uname -s)." >&2
    exit 1
fi
if [[ "$(uname -m)" != "arm64" ]]; then
    echo "WARN: not Apple Silicon ($(uname -m)) — MLX won't accelerate. Continuing anyway." >&2
fi

WYRDSEKAI_HOME="${WYRDSEKAI_HOME:-$HOME/.wyrdsekai}"
VENV="$WYRDSEKAI_HOME/mlx-venv"
ENV_FILE="$WYRDSEKAI_HOME/env"
mkdir -p "$WYRDSEKAI_HOME"

# ── 1. Python via Homebrew ───────────────────────────────────────────────
# When invoked from the .pkg postinstall (`sudo -u operator -H bash ...`),
# PATH is reset to a minimal default that excludes /opt/homebrew/bin. Prepend
# the standard Apple-Silicon and Intel brew locations so we find brew if it
# was installed by an earlier session.
for brew_prefix in /opt/homebrew/bin /usr/local/bin; do
    if [[ -x "$brew_prefix/brew" ]] && [[ ":$PATH:" != *":$brew_prefix:"* ]]; then
        export PATH="$brew_prefix:$PATH"
    fi
done
if ! command -v brew >/dev/null 2>&1; then
    echo "Homebrew not found — installing non-interactively..."
    if ! NONINTERACTIVE=1 /bin/bash -c \
        "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" >&2; then
        echo "ERROR: Homebrew install failed. Install manually from https://brew.sh, " >&2
        echo "       then re-run this script." >&2
        exit 1
    fi
    # Re-prepend (the new install just placed it at one of these)
    for brew_prefix in /opt/homebrew/bin /usr/local/bin; do
        if [[ -x "$brew_prefix/brew" ]] && [[ ":$PATH:" != *":$brew_prefix:"* ]]; then
            export PATH="$brew_prefix:$PATH"
        fi
    done
fi
PYTHON_BIN="$(brew --prefix)/opt/python@3.12/bin/python3.12"
if [[ ! -x "$PYTHON_BIN" ]]; then
    echo "Installing python@3.12 via brew..."
    brew install python@3.12
fi
echo "Using Python: $PYTHON_BIN ($($PYTHON_BIN --version))"

# nats-server is required for the Between cross-node bridge. The .deb bundles
# a Linux binary; on macOS we install via brew since there's no equivalent
# bundled-binary story (the bootstrap is the canonical macOS install path).
if ! command -v nats-server >/dev/null 2>&1; then
    echo "Installing nats-server via brew..."
    brew install nats-server
fi
echo "Using nats-server: $(nats-server --version 2>&1 | head -1)"

# ── 2. Venv + mlx-lm ─────────────────────────────────────────────────────
if [[ ! -d "$VENV" ]]; then
    echo "Creating MLX venv at $VENV..."
    "$PYTHON_BIN" -m venv "$VENV"
fi
# shellcheck source=/dev/null
source "$VENV/bin/activate"
pip install --upgrade pip wheel >/dev/null
# pin mlx-lm range. 0.31.x was validated
# end-to-end in Phase 1 (qwen3_5 hybrid DeltaNet + LoRA adapter + V8
# vectors). Allow patch/minor uplifts but cap below the next major to
# catch breaking API changes in the test suite before stewards upgrade.
echo "Installing mlx-lm (pinned >=0.31,<0.40) + huggingface-hub..."
pip install --upgrade "mlx-lm>=0.31,<0.40" huggingface-hub

# ── 3. Smoke-test the CLI surface ────────────────────────────────────────
echo "Smoke-testing mlx_lm.lora CLI..."
if ! python -m mlx_lm.lora --help >/dev/null 2>&1; then
    echo "ERROR: mlx_lm.lora CLI not invokable. Install bug?" >&2
    exit 1
fi
# Confirm the flags VoiceAligner emits all exist:
HELP="$(python -m mlx_lm.lora --help 2>&1)"
for flag in "--model" "--data" "--train" "--iters" "--adapter-path"; do
    if ! grep -q -- "$flag" <<<"$HELP"; then
        echo "ERROR: mlx_lm.lora --help missing flag $flag — VoiceAligner.runMlxFineTune will fail." >&2
        echo "Inspect: python -m mlx_lm.lora --help" >&2
        exit 1
    fi
done
echo "  all 5 expected flags present (--model, --data, --train, --iters, --adapter-path)."

# ── 3a. mlx_lm.convert pre-flight ────────────────────────────────────────
# Stewards converting their own BF16 HF base into MLX-4bit (e.g. a freshly-
# trained V11/V6+ checkpoint) will hit a `qwen3_5_text not supported` error
# from mlx-lm if HF transformers labelled the config with the newer
# `qwen3_5_text` / `Qwen3_5ForCausalLM` naming. The rewrite helper at
# scripts/voice/mlx_rewrite_qwen35_config.py bridges to the older
# `qwen3_5` / `Qwen3_5ForConditionalGeneration` naming mlx-lm 0.31.x
# expects. Tensor layout is identical including GatedDeltaNet hybrid.
#
# Direct invocation:
#   python -m scripts.voice.mlx_rewrite_qwen35_config <hf-dir>
#   python -m mlx_lm convert --hf-path <hf-dir> -q --q-bits 4 --mlx-path <out>
#
# publish_mlx_base.sh already invokes the rewrite before convert. If you
# wire a new convert path, call the rewrite first.

# ── 4. Pre-pull a small base for warm-up ─────────────────────────────────
# Qwen3-1.7B in HF format is ~3.5GB; matches what test-node has so we don't
# bloat the model store unnecessarily. Skip if cache hit.
#
# the .pkg postinstall invokes us with
# WYRD_PHASE4_SKIP_BASE_PULL=1 so install-time stays small. The MLX voice
# base belongs to `wyrd setup` (Phase 4D), not this bootstrap. Manual
# invocations (no env var) still pull the training base.
BASE_MODEL="${BASE_MODEL:-Qwen/Qwen3-1.7B}"
if [[ "${WYRD_PHASE4_SKIP_BASE_PULL:-0}" = "1" ]]; then
    echo "Skipping training-base pre-pull (WYRD_PHASE4_SKIP_BASE_PULL=1)."
elif [[ ! -d "$HOME/.cache/huggingface/hub/models--${BASE_MODEL//\//--}" ]]; then
    echo "Pre-pulling $BASE_MODEL into HuggingFace cache..."
    python -c "from huggingface_hub import snapshot_download; \
        snapshot_download('$BASE_MODEL', allow_patterns=['*.safetensors','*.json','tokenizer*'])"
else
    echo "Base $BASE_MODEL already cached."
fi

# ── 5. Wire into wyrdsekai env ───────────────────────────────────────────
touch "$ENV_FILE"
upsert() {
    local key="$1" val="$2"
    if grep -qE "^${key}=" "$ENV_FILE"; then
        # macOS sed needs '' after -i
        sed -i '' -E "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
    else
        echo "${key}=${val}" >>"$ENV_FILE"
    fi
}
upsert WYRDSEKAI_VOICE_BACKEND mlx
upsert WYRDSEKAI_PEER_TRAINING_HOST 1
upsert WYRDSEKAI_VOICE_BACKEND_PYTHON "$VENV/bin/python"
upsert WYRDSEKAI_PEER_TRAINING_BASE_MODEL "$BASE_MODEL"

echo
echo "Stamped into $ENV_FILE:"
echo "    WYRDSEKAI_VOICE_BACKEND=mlx"
echo "    WYRDSEKAI_PEER_TRAINING_HOST=1"
echo "    WYRDSEKAI_VOICE_BACKEND_PYTHON=$VENV/bin/python"
echo "    WYRDSEKAI_PEER_TRAINING_BASE_MODEL=$BASE_MODEL"

# ── 6. Reload the LaunchDaemon so it picks up the new env ────────────────
PLIST=/Library/LaunchDaemons/com.wyrdsekai.server.plist
if [[ -f "$PLIST" ]]; then
    echo
    echo "Reloading LaunchDaemon to apply env (needs sudo)..."
    if sudo launchctl unload "$PLIST" 2>/dev/null; sudo launchctl load "$PLIST"; then
        echo "  ✓ LaunchDaemon loaded — wyrdsekai-server is starting."
    else
        echo "  ✗ Could not reload LaunchDaemon. Run manually:"
        echo "      sudo launchctl unload $PLIST"
        echo "      sudo launchctl load   $PLIST"
    fi
else
    echo
    echo "Note: $PLIST not found. The .pkg postinstall installs it at install time."
fi

echo
echo "✅ mac-node MLX trainer bootstrap complete."
echo "Verify: tail /tmp/wyrdsekai-server.log"
