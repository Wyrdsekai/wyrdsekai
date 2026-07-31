#!/usr/bin/env bash
# substrate-v2 training pipeline: corpus → gpu-host train → merge → GGUF → swap.
#
# Prerequisites (must exist before running):
#   - data/training/substrate/substrate_raw.jsonl   (existing 1075 substrate examples)
#   - data/training/substrate/react_preservation_raw.jsonl  (~1287 multi-turn ReAct traces)
#   - gpu-host:~/wyrdsekai-9b-v4-merged/   (V4 base merged, ~17GB bf16)
#   - gpu-host:/home/you/.codeplane/sglang-venv/  with transformers 5.8.1
#
# Hyperparams (conservative per published practice — or
# ):
#   LR=1e-5 (vs 1e-4 in v1)
#   EPOCHS=1 (vs 2 in v1)
#   LORA_R=8, LORA_ALPHA=16 (vs 16/32 in v1)
#   MAX_LENGTH=2048 (vs 512 in v1 — multi-turn ReAct examples are longer)
#   PRESERVATION_FRAC=1.3 (vs 0.15 in v1 — flips substrate:preservation ratio)
#
# Run order:
#   bash scripts/training/substrate/train_substrate_v2.sh

set -euo pipefail

REPO="/home/you/src/wyrdsekai"
cd "$REPO"

# ─── 1. Build train/valid splits (LOCAL) ─────────────────────────────────────
echo "═══ Step 1: build substrate-v2 splits ═══"

python3 scripts/training/substrate/build_substrate_corpus.py \
    --raw data/training/substrate/substrate_raw.jsonl \
    --preservation data/training/substrate/react_preservation_raw.jsonl \
    --preservation-frac 1.3 \
    --prefix substrate-v2 \
    --strip-meta

ls -la data/training/substrate-v2_train.jsonl data/training/substrate-v2_valid.jsonl

# ─── 2. scp corpus to gpu-host ──────────────────────────────────────────────
echo
echo "═══ Step 2: scp corpus to gpu-host ═══"
scp data/training/substrate-v2_train.jsonl \
    data/training/substrate-v2_valid.jsonl \
    gpu-host:/ndata2/wyrdsekai/data/training/

# ─── 3. Train LoRA on gpu-host Ada (CUDA 0 = Ada) ───────
echo
echo "═══ Step 3: train LoRA on gpu-host Ada ═══"

# Clean prior adapter if any
ssh gpu-host "rm -rf ~/wyrdsekai-9b-drive-substrate-v2-adapter && mkdir -p ~/wyrdsekai-9b-drive-substrate-v2-adapter"

# Kick off training; tee output back so we can see progress.
# Conservative hyperparams. Use Ada (CUDA 0).
ssh gpu-host bash <<'EOF'
set -euo pipefail
cd /ndata2/wyrdsekai
source /home/you/.codeplane/sglang-venv/bin/activate

CUDA_VISIBLE_DEVICES=0 OMP_NUM_THREADS=1 python scripts/training/ssd_finetune.py \
    --model /home/you/wyrdsekai-9b-v4-merged \
    --data data/training/substrate-v2_train.jsonl \
    --valid data/training/substrate-v2_valid.jsonl \
    --output /home/you/wyrdsekai-9b-drive-substrate-v2-adapter \
    --epochs 1 \
    --lr 1e-5 \
    --lora-r 8 \
    --lora-alpha 16 \
    --lora-dropout 0.05 \
    --weight-decay 0.01 \
    --max-length 2048 \
    --batch-size 1 \
    --gradient-accumulation 8
EOF

# ─── 4. Merge adapter on gpu-host ───────────────────────────────────────────
echo
echo "═══ Step 4: merge adapter into V4 base ═══"

ssh gpu-host "rm -rf ~/wyrdsekai-9b-drive-substrate-v2-merged"

ssh gpu-host bash <<'EOF'
set -euo pipefail
cd /ndata2/wyrdsekai
source /home/you/.codeplane/sglang-venv/bin/activate

CUDA_VISIBLE_DEVICES=0 python scripts/training/merge_adapter.py \
    --base /home/you/wyrdsekai-9b-v4-merged \
    --adapter /home/you/wyrdsekai-9b-drive-substrate-v2-adapter \
    --out /home/you/wyrdsekai-9b-drive-substrate-v2-merged
EOF

# ─── 5. Convert to GGUF + quantize to Q4_K_M ─────────────────────────────────
echo
echo "═══ Step 5: GGUF convert + Q4_K_M quantize ═══"

ssh gpu-host bash <<'EOF'
set -euo pipefail
source /home/you/.codeplane/sglang-venv/bin/activate

# Convert bf16 → f16 GGUF
python /home/you/llama.cpp/convert_hf_to_gguf.py \
    /home/you/wyrdsekai-9b-drive-substrate-v2-merged \
    --outfile /home/you/wyrdsekai-3.5-9b-drive-substrate-v2-f16.gguf \
    --outtype f16

# Quantize f16 → Q4_K_M
/home/you/llama.cpp/build/bin/llama-quantize \
    /home/you/wyrdsekai-3.5-9b-drive-substrate-v2-f16.gguf \
    /home/you/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf \
    Q4_K_M

ls -la /home/you/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf
EOF

# ─── 6. scp back to home-server ─────────────────────────────────────────────────────
echo
echo "═══ Step 6: scp final GGUF back to home-server ═══"

scp gpu-host:/home/you/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf \
    "$REPO/data/models/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf"

ls -la "$REPO/data/models/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf"

# ─── 7. Production swap on home-server ──────────────────────────────────────────────
echo
echo "═══ Step 7: swap substrate-v1 → substrate-v2 on home-server ═══"

# Stop current container
docker stop wyrdsekai-llama

# Rename current as v1-rollback (preserve for fast rollback)
docker rename wyrdsekai-llama wyrdsekai-llama-v1-rollback

# Create new container with substrate-v2
docker run -d --name wyrdsekai-llama \
    --gpus all \
    -p 8200:8200 \
    -v "$REPO/data/models:/models" \
    ghcr.io/ggml-org/llama.cpp:server-cuda \
    --model /models/wyrdsekai-3.5-9b-drive-substrate-v2-q4km.gguf \
    --port 8200 --ctx-size 16384 --n-gpu-layers 99 --parallel 1 \
    --host 0.0.0.0 --jinja --reasoning off --reasoning-budget 0 \
    --flash-attn on --metrics

# Wait for /health
echo "Waiting for substrate-v2 to be ready..."
for i in $(seq 1 60); do
    if curl -sf -m 3 http://localhost:8200/health >/dev/null 2>&1; then
        echo "  ready after ${i}s"
        break
    fi
    sleep 1
done

curl -s http://localhost:8200/v1/models | head -c 300
echo

# ─── 8. Regression tests ─────────────────────────────────────────────────────
echo
echo "═══ Step 8: Ember 15 + SubstrateArc 5 regression ═══"

cd "$REPO"

# Archive prior log
mv e2e-test/logs/wyrdsekai.log "e2e-test/logs/wyrdsekai-pre-v2-$(date +%H%M).log" 2>/dev/null || true

WYRDSEKAI_E2E_BACKEND=llama-server \
WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
WYRDSEKAI_VOICE_URL=http://localhost:8201 \
./gradlew :e2e-test:test \
    --tests "*EmberProgressiveTasksE2ETest" \
    --tests "*SubstrateArcE2ETest" \
    --rerun-tasks 2>&1 | tail -40

# ─── 9. Restore gpu-host env ────────────────────────────────────────────────
echo
echo "═══ Step 9: restore sglang-venv pins (transformers 4.57.1, torchao 0.9.0) ═══"

ssh gpu-host "/home/you/.codeplane/sglang-venv/bin/pip install 'transformers==4.57.1' 'torchao==0.9.0' 2>&1 | tail -5"

echo
echo "═══ DONE ═══"
echo "Rollback command: docker stop wyrdsekai-llama && docker rm wyrdsekai-llama && docker rename wyrdsekai-llama-v1-rollback wyrdsekai-llama && docker start wyrdsekai-llama"
