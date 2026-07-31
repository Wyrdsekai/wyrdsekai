#!/usr/bin/env bash
#
# Vitality v2 LoRA SSD training for Qwen3.5-{4B,9B}.
#
# Trains on data/training/vitality_v2_{train,valid}.jsonl which combines:
#   - balanced base (drives + tools + soul + energy, 1038/116 turns)
#   - vitality (10 cultural+anti-pathology tanks + 2 new drives, 622/69 turns)
#   - cleaned of 2 meta-leak turns + bolstered with 30 EN-standing withdraw turns
#   - total: 1660 train / 185 valid
#
# Usage:
#   bash scripts/training/train_vitality.sh 9b   # Qwen3.5-9B on Ada (CUDA 0)
#   bash scripts/training/train_vitality.sh 4b   # Qwen3.5-4B on A6000 (CUDA 1)
#
# Both can run in parallel — different GPUs. Set CUDA_VISIBLE_DEVICES at invocation
# to override defaults.
#
# Dollhouse GPU mapping (PyTorch reverses nvidia-smi):
#   CUDA 0 → Ada 6000      (best, default for 9B)
#   CUDA 1/2/3 → A6000 ×3  (use one for 4B)
#
set -euo pipefail

export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1
export TOKENIZERS_PARALLELISM=false

# Activate the shared training venv (torch + transformers + peft preinstalled)
VENV="${TRAIN_VENV:-/tmp/steer-env}"
if [[ -d "$VENV" ]]; then
    source "$VENV/bin/activate"
else
    echo "ERROR: training venv missing at $VENV. Set TRAIN_VENV or create one." >&2
    exit 1
fi

SIZE="${1:-9b}"
# VARIANT env var: pick which vitality flavor (v2, v6, …). Defaults to v2 so
# old invocations keep working. V6 = corpus.
VARIANT="${VARIANT:-v2}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

case "$SIZE" in
    4b|4B)
        MODEL_ID="Qwen/Qwen3.5-4B"
        OUTPUT_DIR="$REPO_ROOT/data/training/wyrdsekai-4b-vitality-${VARIANT}"
        MERGED_DIR="/tmp/wyrdsekai-4b-vitality-${VARIANT}-merged"
        GGUF_DIR="/tmp/wyrdsekai-4b-vitality-${VARIANT}-gguf"
        FINAL_NAME="wyrdsekai-3.5-4b-vitality-${VARIANT}-q4km.gguf"
        EPOCHS="${EPOCHS:-5}"
        MAX_LENGTH=1024
        DEFAULT_CUDA=1
        ;;
    9b|9B)
        MODEL_ID="Qwen/Qwen3.5-9B"
        OUTPUT_DIR="$REPO_ROOT/data/training/wyrdsekai-9b-vitality-${VARIANT}"
        MERGED_DIR="/tmp/wyrdsekai-9b-vitality-${VARIANT}-merged"
        GGUF_DIR="/tmp/wyrdsekai-9b-vitality-${VARIANT}-gguf"
        FINAL_NAME="wyrdsekai-3.5-9b-vitality-${VARIANT}-q4km.gguf"
        EPOCHS="${EPOCHS:-3}"
        MAX_LENGTH=1024
        DEFAULT_CUDA=0
        ;;
    *)
        echo "Usage: $0 [4b|9b]"
        exit 1
        ;;
esac

# Pin to default GPU unless user overrides
export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-$DEFAULT_CUDA}"

FINAL_GGUF="$GGUF_DIR/$FINAL_NAME"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[train-vitality-$SIZE]${NC} $*"; }
warn() { echo -e "${YELLOW}[train-vitality-$SIZE]${NC} $*"; }
fail() { echo -e "${RED}[train-vitality-$SIZE]${NC} $*"; exit 1; }

# ── Verify GPU ──
log "Verifying GPU (CUDA_VISIBLE_DEVICES=$CUDA_VISIBLE_DEVICES)..."
python3 -c "
import torch
assert torch.cuda.is_available(), 'CUDA not available'
name = torch.cuda.get_device_name(0)
mem = torch.cuda.get_device_properties(0).total_memory / 1e9
print(f'GPU: {name} ({mem:.1f} GB)')
" || fail "CUDA not available"

# ── Verify corpus exists ──
# CORPUS_PREFIX env var: which build_vitality_corpus.py output to train on.
# Default vitality_v2 (legacy). For V7 set CORPUS_PREFIX=vitality_v7.
CORPUS_PREFIX="${CORPUS_PREFIX:-vitality_v2}"
TRAIN_DATA="$REPO_ROOT/data/training/${CORPUS_PREFIX}_train.jsonl"
VALID_DATA="$REPO_ROOT/data/training/${CORPUS_PREFIX}_valid.jsonl"
[[ -f "$TRAIN_DATA" ]] || fail "Train corpus missing: $TRAIN_DATA"
[[ -f "$VALID_DATA" ]] || fail "Valid corpus missing: $VALID_DATA"
log "Corpus ($CORPUS_PREFIX): $(wc -l < "$TRAIN_DATA") train / $(wc -l < "$VALID_DATA") valid"

# ── Step 1: LoRA fine-tune ──
log "Step 1: LoRA fine-tune ($SIZE, $EPOCHS epochs)"
mkdir -p "$OUTPUT_DIR"

# RESUME_CHECKPOINT env var: optional. Pass an explicit checkpoint path
# (e.g. "$OUTPUT_DIR/checkpoint-1191") or "true" to use the latest in
# OUTPUT_DIR. Used to swap GPUs mid-run after an epoch boundary.
# Belt-and-suspenders for older bash versions: ensure the var is BOUND
# (set -u trips on `${RESUME_ARG[@]}` for empty arrays in bash <4.4).
RESUME_CHECKPOINT="${RESUME_CHECKPOINT:-}"
RESUME_ARG=()
if [[ -n "$RESUME_CHECKPOINT" ]]; then
    log "Resuming from checkpoint: $RESUME_CHECKPOINT"
    RESUME_ARG=(--resume-from-checkpoint "$RESUME_CHECKPOINT")
fi

# Anti-overfit knobs for behavior-change LoRA. V6 was 0.05/0.0 (defaults);
# V7 bumped to 0.10/0.01 on small models (4B) to address over-memorization.
LORA_DROPOUT="${LORA_DROPOUT:-0.05}"
WEIGHT_DECAY="${WEIGHT_DECAY:-0.0}"
log "LoRA dropout=$LORA_DROPOUT, weight_decay=$WEIGHT_DECAY"

python3 scripts/training/ssd_finetune.py \
    --model "$MODEL_ID" \
    --data "$TRAIN_DATA" \
    --valid "$VALID_DATA" \
    --output "$OUTPUT_DIR" \
    --epochs "$EPOCHS" \
    --batch-size 1 \
    --lr 1e-5 \
    --lora-r 16 \
    --lora-alpha 32 \
    --max-length "$MAX_LENGTH" \
    --gradient-accumulation 8 \
    --lora-dropout "$LORA_DROPOUT" \
    --weight-decay "$WEIGHT_DECAY" \
    ${RESUME_ARG[@]+"${RESUME_ARG[@]}"}

log "LoRA adapter saved → $OUTPUT_DIR"

# ── Step 2: Merge adapter into base ──
log "Step 2: Merge adapter into base"
python3 -c "
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
import torch
print('Loading base...')
base = AutoModelForCausalLM.from_pretrained('$MODEL_ID',
    torch_dtype=torch.bfloat16, device_map='cpu', trust_remote_code=True)
print('Loading adapter...')
model = PeftModel.from_pretrained(base, '$OUTPUT_DIR')
print('Merging...')
merged = model.merge_and_unload()
print('Saving merged...')
merged.save_pretrained('$MERGED_DIR')
AutoTokenizer.from_pretrained('$MODEL_ID', trust_remote_code=True).save_pretrained('$MERGED_DIR')
print('Merged → $MERGED_DIR')
"
log "Merged saved"

# ── Step 3: Convert to GGUF F16 ──
log "Step 3: Convert to GGUF F16"
mkdir -p "$GGUF_DIR"

LLAMA_CPP="$HOME/llama.cpp"
[[ -d "$LLAMA_CPP" ]] || fail "llama.cpp missing — run train_balanced.sh first to clone it"

python3 "$LLAMA_CPP/convert_hf_to_gguf.py" \
    "$MERGED_DIR" \
    --outfile "$GGUF_DIR/${FINAL_NAME%.gguf}-f16.gguf" \
    --outtype f16
log "GGUF F16 created"

# ── Step 4: Quantize Q4_K_M ──
log "Step 4: Quantize to Q4_K_M"
[[ -f "$LLAMA_CPP/build/bin/llama-quantize" ]] \
    || fail "llama-quantize missing — run train_balanced.sh first to build it"

"$LLAMA_CPP/build/bin/llama-quantize" \
    "$GGUF_DIR/${FINAL_NAME%.gguf}-f16.gguf" \
    "$FINAL_GGUF" \
    Q4_K_M
log "Quantized GGUF: $FINAL_GGUF"
ls -lh "$FINAL_GGUF"

echo ""
log "Done. Probe with:"
log "  bash scripts/training/vitality/probe_vitality_register.sh"
log "Copy to home-server (after probe passes):"
log "  scp $FINAL_GGUF home-server:~/src/wyrdsekai/data/models/"
