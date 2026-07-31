#!/usr/bin/env bash
#
# Full training pipeline for Qwen3.5-9B SSD fine-tune on gpu-host.
#
# Steps:
# 1. Setup venv + install deps (if not done)
# 2. Download base model (if not cached)
# 3. Prepare training corpus
# 4. Run LoRA fine-tune
# 5. Merge adapter + base model
# 6. Convert to GGUF
# 7. Quantize to Q4_K_M
#
# Usage:
#   bash scripts/training/train_9b.sh
#
set -euo pipefail

# Use GPU 0 (RTX 6000 Ada Generation) — best GPU on gpu-host
# Note: nvidia-smi shows Ada as index 3, but CUDA enumerates it as 0
export CUDA_VISIBLE_DEVICES="${CUDA_VISIBLE_DEVICES:-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VENV="$SCRIPT_DIR/.venv"
MODEL_ID="Qwen/Qwen3.5-9B"
MODEL_CACHE="$HOME/.cache/huggingface/hub"
OUTPUT_DIR="/tmp/wyrdsekai-9b-ssd"
MERGED_DIR="/tmp/wyrdsekai-9b-merged"
GGUF_DIR="/tmp/wyrdsekai-9b-gguf"
FINAL_GGUF="$GGUF_DIR/wyrdsekai-3.5-9b-ssd-q4km.gguf"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[train]${NC} $*"; }
warn() { echo -e "${YELLOW}[train]${NC} $*"; }

# ── Step 1: Environment ──
log "Step 1: Environment setup"
if [[ ! -d "$VENV" ]]; then
    python3 -m venv "$VENV"
    source "$VENV/bin/activate"
    pip install --upgrade pip
    pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
    pip install transformers peft accelerate bitsandbytes datasets
    pip install sentencepiece protobuf  # for tokenizer
    log "Environment created"
else
    source "$VENV/bin/activate"
    log "Using existing environment"
fi

# Verify CUDA
python3 -c "import torch; assert torch.cuda.is_available(), 'CUDA not available'; print(f'CUDA OK: {torch.cuda.device_count()} GPUs')"

# ── Step 2: Download model ──
log "Step 2: Download base model ($MODEL_ID)"
python3 -c "
from transformers import AutoModelForCausalLM, AutoTokenizer
import os
# This will download and cache if not present
print('Downloading tokenizer...')
AutoTokenizer.from_pretrained('$MODEL_ID', trust_remote_code=True)
print('Downloading model weights (this may take a while)...')
AutoModelForCausalLM.from_pretrained('$MODEL_ID', trust_remote_code=True,
    torch_dtype='auto', device_map={'': 'cpu'})
print('Model cached.')
"
log "Model downloaded"

# ── Step 3: Prepare corpus ──
log "Step 3: Prepare training corpus"
cd "$REPO_ROOT"
python3 scripts/training/prepare_9b_corpus.py
log "Corpus ready"

# ── Step 4: LoRA fine-tune ──
log "Step 4: LoRA fine-tune"
mkdir -p "$OUTPUT_DIR"

python3 scripts/training/ssd_finetune.py \
    --model "$MODEL_ID" \
    --data /tmp/wyrdsekai_9b_train.jsonl \
    --valid /tmp/wyrdsekai_9b_valid.jsonl \
    --output "$OUTPUT_DIR" \
    --epochs 3 \
    --batch-size 1 \
    --lr 1e-5 \
    --lora-r 16 \
    --lora-alpha 32 \
    --max-length 1024 \
    --gradient-accumulation 8

log "LoRA adapter saved to $OUTPUT_DIR"

# ── Step 5: Merge adapter ──
log "Step 5: Merge LoRA adapter into base model"
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

# ── Step 6: Convert to GGUF ──
log "Step 6: Convert to GGUF"
mkdir -p "$GGUF_DIR"

# Clone llama.cpp if needed
if [[ ! -d "$HOME/llama.cpp" ]]; then
    git clone https://github.com/ggml-org/llama.cpp.git "$HOME/llama.cpp"
    cd "$HOME/llama.cpp"
    pip install -r requirements.txt 2>/dev/null || pip install gguf numpy sentencepiece
    cd "$REPO_ROOT"
fi

python3 "$HOME/llama.cpp/convert_hf_to_gguf.py" \
    "$MERGED_DIR" \
    --outfile "$GGUF_DIR/wyrdsekai-3.5-9b-ssd-f16.gguf" \
    --outtype f16

log "GGUF F16 created"

# ── Step 7: Quantize to Q4_K_M ──
log "Step 7: Quantize to Q4_K_M"

# Build llama-quantize if needed
if [[ ! -f "$HOME/llama.cpp/build/bin/llama-quantize" ]]; then
    cd "$HOME/llama.cpp"
    cmake -B build -DLLAMA_CUDA=ON
    cmake --build build --config Release -j$(nproc) --target llama-quantize
    cd "$REPO_ROOT"
fi

"$HOME/llama.cpp/build/bin/llama-quantize" \
    "$GGUF_DIR/wyrdsekai-3.5-9b-ssd-f16.gguf" \
    "$FINAL_GGUF" \
    Q4_K_M

log "Quantized GGUF: $FINAL_GGUF"
ls -lh "$FINAL_GGUF"

echo ""
log "Done! Copy to home-server:"
log "  scp $FINAL_GGUF home-server:~/src/wyrdsekai/data/models/"
