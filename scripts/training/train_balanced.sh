#!/usr/bin/env bash
#
# Balanced LoRA training for Wyrdsekai companion models.
#
# Trains on: drives (391 steered) + soul (120) + tools (513) = ~1024 balanced examples
# This is the CORRECT approach — previous training used 822 examples with 0 tools.
#
# Supports both 4B and 9B:
#   bash scripts/training/train_balanced.sh 4b    # Qwen3.5-4B on any GPU
#   bash scripts/training/train_balanced.sh 9b    # Qwen3.5-9B (needs 24GB+ GPU)
#
# Dollhouse GPU setup:
#   CUDA_VISIBLE_DEVICES=0 → Ada 6000 (best, use for 9B)
#   CUDA_VISIBLE_DEVICES=1 → A6000
#
# CRITICAL: Set these to prevent CPU thread explosion on gpu-host:
#   export OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 TOKENIZERS_PARALLELISM=false
#
set -euo pipefail

# Prevent CPU thread explosion
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1
export TOKENIZERS_PARALLELISM=false

SIZE="${1:-4b}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

case "$SIZE" in
    4b|4B)
        MODEL_ID="Qwen/Qwen3.5-4B"
        OUTPUT_DIR="$REPO_ROOT/data/training/wyrdsekai-4b-balanced"
        MERGED_DIR="/tmp/wyrdsekai-4b-balanced-merged"
        GGUF_DIR="/tmp/wyrdsekai-4b-balanced-gguf"
        FINAL_NAME="wyrdsekai-3.5-4b-balanced-q4km.gguf"
        EPOCHS=5
        MAX_LENGTH=1024
        ;;
    9b|9B)
        MODEL_ID="Qwen/Qwen3.5-9B"
        OUTPUT_DIR="$REPO_ROOT/data/training/wyrdsekai-9b-balanced"
        MERGED_DIR="/tmp/wyrdsekai-9b-balanced-merged"
        GGUF_DIR="/tmp/wyrdsekai-9b-balanced-gguf"
        FINAL_NAME="wyrdsekai-3.5-9b-balanced-q4km.gguf"
        EPOCHS=3
        MAX_LENGTH=1024
        ;;
    *)
        echo "Usage: $0 [4b|9b]"
        exit 1
        ;;
esac

FINAL_GGUF="$GGUF_DIR/$FINAL_NAME"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[train-balanced]${NC} $*"; }
warn() { echo -e "${YELLOW}[train-balanced]${NC} $*"; }
fail() { echo -e "${RED}[train-balanced]${NC} $*"; exit 1; }

# ── Verify GPU ──
log "Verifying GPU..."
python3 -c "
import torch
assert torch.cuda.is_available(), 'CUDA not available'
name = torch.cuda.get_device_name(0)
mem = torch.cuda.get_device_properties(0).total_memory / 1e9
print(f'GPU: {name} ({mem:.1f} GB)')
" || fail "CUDA not available"

# ── Verify drive corpus exists ──
DRIVE_CORPUS="$REPO_ROOT/data/training/tagged_conversations_v3.jsonl"
if [[ ! -f "$DRIVE_CORPUS" ]]; then
    fail "Drive corpus not found: $DRIVE_CORPUS"
fi
log "Drive corpus: $(wc -l < "$DRIVE_CORPUS") turns"

# ── Step 1: Prepare balanced corpus ──
log "Step 1: Prepare balanced corpus"
cd "$REPO_ROOT"
python3 scripts/training/prepare_balanced_corpus.py \
    --output-dir "$REPO_ROOT/data/training"
log "Corpus ready"

# ── Step 2: LoRA fine-tune ──
log "Step 2: Balanced LoRA fine-tune ($SIZE, $EPOCHS epochs)"
mkdir -p "$OUTPUT_DIR"

python3 scripts/training/ssd_finetune.py \
    --model "$MODEL_ID" \
    --data "$REPO_ROOT/data/training/balanced_train.jsonl" \
    --valid "$REPO_ROOT/data/training/balanced_valid.jsonl" \
    --output "$OUTPUT_DIR" \
    --epochs "$EPOCHS" \
    --batch-size 1 \
    --lr 1e-5 \
    --lora-r 16 \
    --lora-alpha 32 \
    --max-length "$MAX_LENGTH" \
    --gradient-accumulation 8

log "LoRA adapter saved to $OUTPUT_DIR"

# ── Step 4: Merge adapter ──
log "Step 4: Merge LoRA adapter into base model"
python3 -c "
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
import torch

print('Loading base model...')
base = AutoModelForCausalLM.from_pretrained('$MODEL_ID',
    torch_dtype=torch.bfloat16, device_map='cpu', trust_remote_code=True)
print('Loading adapter...')
model = PeftModel.from_pretrained(base, '$OUTPUT_DIR')
print('Merging...')
merged = model.merge_and_unload()
print('Saving merged model...')
merged.save_pretrained('$MERGED_DIR')
AutoTokenizer.from_pretrained('$MODEL_ID', trust_remote_code=True).save_pretrained('$MERGED_DIR')
print('Merged model saved to $MERGED_DIR')
"
log "Merged model saved"

# ── Step 5: Convert to GGUF ──
log "Step 5: Convert to GGUF"
mkdir -p "$GGUF_DIR"

LLAMA_CPP="$HOME/llama.cpp"
if [[ ! -d "$LLAMA_CPP" ]]; then
    git clone https://github.com/ggml-org/llama.cpp.git "$LLAMA_CPP"
    cd "$LLAMA_CPP" && pip install gguf numpy sentencepiece && cd "$REPO_ROOT"
fi

python3 "$LLAMA_CPP/convert_hf_to_gguf.py" \
    "$MERGED_DIR" \
    --outfile "$GGUF_DIR/${FINAL_NAME%.gguf}-f16.gguf" \
    --outtype f16

log "GGUF F16 created"

# ── Step 6: Quantize to Q4_K_M ──
log "Step 6: Quantize to Q4_K_M"

if [[ ! -f "$LLAMA_CPP/build/bin/llama-quantize" ]]; then
    cd "$LLAMA_CPP"
    cmake -B build -DLLAMA_CUDA=ON
    cmake --build build --config Release -j$(nproc) --target llama-quantize
    cd "$REPO_ROOT"
fi

"$LLAMA_CPP/build/bin/llama-quantize" \
    "$GGUF_DIR/${FINAL_NAME%.gguf}-f16.gguf" \
    "$FINAL_GGUF" \
    Q4_K_M

log "Quantized GGUF: $FINAL_GGUF"
ls -lh "$FINAL_GGUF"

echo ""
log "Done! Copy to home-server:"
log "  scp $FINAL_GGUF home-server:~/src/wyrdsekai/data/models/"
log "  scp -r $OUTPUT_DIR home-server:~/src/wyrdsekai/data/models/wyrdsekai-${SIZE}-balanced-adapter/"
