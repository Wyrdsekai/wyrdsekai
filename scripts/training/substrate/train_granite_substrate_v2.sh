#!/usr/bin/env bash
# Granite-Substrate-3B v2 training pipeline.
# Successor to v1 (which had welfare-action coverage gap — 0/1075 flag_protection).
#
# Goal: same 8GB-tier drive candidate
# now with L4 welfare corpus folded in (#916). Granite-Substrate-v2 should
# emit flag_protection / seek_sanctuary / voluntary_sleep on T5-shape user
# requests, without the welfare-override prompt that v1 needed.
#
# Corpus: V5 substrate (1075) + L4 welfare (301) = 1376 substrate examples,
# 7 actions × 3 langs, all cells populated. flag_protection at 17.5% of mix
# pre-preservation. See scripts/training/granite_l2_vector/07_merge_v5_l4_corpus.py.
#
# Hyperparams: same as v1 (proven structurally). Fresh LoRA from base Granite,
# NOT continued from v1 adapter. 2 epochs per feedback-small-model-sft-2-epochs.md.
#
# Run from repo root: bash scripts/training/substrate/train_granite_substrate_v2.sh

set -euo pipefail

REPO="/home/you/src/wyrdsekai"
cd "$REPO"

GRANITE_BASE_REMOTE="/home/you/work/granite-substrate/granite-4.1-3b-base"
ADAPTER_REMOTE="/home/you/wyrdsekai-granite-3b-substrate-v2-adapter"
MERGED_REMOTE="/home/you/wyrdsekai-granite-3b-substrate-v2-merged"
GGUF_NAME="wyrdsekai-granite-3b-substrate-v2-q4km.gguf"

# ─── 1. Build train/valid splits (LOCAL) ─────────────────────────────────────
echo "═══ Step 1: build substrate splits (V5+L4 substrate + preservation mix) ═══"

# Merged V5+L4 corpus prepared by 07_merge_v5_l4_corpus.py
ls -la data/training/substrate/granite_substrate_v2_raw.jsonl

python3 scripts/training/substrate/build_substrate_corpus.py \
    --raw data/training/substrate/granite_substrate_v2_raw.jsonl \
    --preservation data/training/substrate/react_preservation_raw.jsonl \
    --preservation-frac 1.3 \
    --prefix granite-substrate-v2 \
    --strip-meta

ls -la data/training/granite-substrate-v2_train.jsonl data/training/granite-substrate-v2_valid.jsonl

# ─── 2. scp corpus to gpu-host ──────────────────────────────────────────────
echo
echo "═══ Step 2: scp corpus to gpu-host ═══"
scp data/training/granite-substrate-v2_train.jsonl \
    data/training/granite-substrate-v2_valid.jsonl \
    gpu-host:/ndata2/wyrdsekai/data/training/

# ─── 3. Verify Granite base is on gpu-host ──────────────────────────────────
echo
echo "═══ Step 3: verify Granite 4.1 3B base on gpu-host ═══"
ssh gpu-host "ls -la $GRANITE_BASE_REMOTE/*.safetensors 2>&1 | tail -5 && du -sh $GRANITE_BASE_REMOTE"

# ─── 4. Train LoRA on gpu-host Ada (CUDA 0 = Ada) ───────
echo
echo "═══ Step 4: train LoRA on gpu-host Ada ═══"

ssh gpu-host "rm -rf $ADAPTER_REMOTE && mkdir -p $ADAPTER_REMOTE"

ssh gpu-host bash <<EOF
set -euo pipefail
cd /ndata2/wyrdsekai
source /home/you/.codeplane/sglang-venv/bin/activate

CUDA_VISIBLE_DEVICES=0 OMP_NUM_THREADS=1 python scripts/training/ssd_finetune.py \\
    --model $GRANITE_BASE_REMOTE \\
    --data data/training/granite-substrate-v2_train.jsonl \\
    --valid data/training/granite-substrate-v2_valid.jsonl \\
    --output $ADAPTER_REMOTE \\
    --epochs 2 \\
    --lr 1e-5 \\
    --lora-r 8 \\
    --lora-alpha 16 \\
    --lora-dropout 0.05 \\
    --weight-decay 0.01 \\
    --max-length 2048 \\
    --batch-size 2 \\
    --gradient-accumulation 4
EOF

# ─── 5. Merge adapter ────────────────────────────────────────────────────────
echo
echo "═══ Step 5: merge adapter into Granite base ═══"

ssh gpu-host "rm -rf $MERGED_REMOTE"

ssh gpu-host bash <<EOF
set -euo pipefail
cd /ndata2/wyrdsekai
source /home/you/.codeplane/sglang-venv/bin/activate

CUDA_VISIBLE_DEVICES=0 python scripts/training/merge_adapter.py \\
    --base $GRANITE_BASE_REMOTE \\
    --adapter $ADAPTER_REMOTE \\
    --out $MERGED_REMOTE
EOF

# ─── 6. Convert to GGUF + quantize to Q4_K_M ─────────────────────────────────
echo
echo "═══ Step 6: GGUF convert + Q4_K_M quantize ═══"

ssh gpu-host bash <<EOF
set -euo pipefail
source /home/you/.codeplane/sglang-venv/bin/activate
cd /home/you

# Convert bf16 → f16 GGUF
python /home/you/llama.cpp/convert_hf_to_gguf.py \\
    $MERGED_REMOTE \\
    --outfile /home/you/granite-substrate-v2-f16.gguf \\
    --outtype f16

# Quantize f16 → Q4_K_M
/home/you/llama.cpp/build/bin/llama-quantize \\
    /home/you/granite-substrate-v2-f16.gguf \\
    /home/you/$GGUF_NAME \\
    Q4_K_M

ls -lh /home/you/$GGUF_NAME
EOF

# ─── 7. Pull GGUF back to home-server ───────────────────────────────────────────────
echo
echo "═══ Step 7: pull GGUF back to home-server ═══"
scp gpu-host:/home/you/$GGUF_NAME \
    $REPO/data/models/$GGUF_NAME
ls -lh $REPO/data/models/$GGUF_NAME

echo
echo "═══ DONE ═══"
echo "Granite-Substrate-3B-v2 at: $REPO/data/models/$GGUF_NAME"
echo ""
echo "Next: spin up on home-server :8203 and re-run drive diagnostic"
echo "  docker run -d --name wyrdsekai-granite-substrate-v2 \\"
echo "    --gpus all -v $REPO/data/models:/models:ro -p 8203:8203 \\"
echo "    ghcr.io/ggml-org/llama.cpp:server-cuda \\"
echo "    --model /models/$GGUF_NAME --port 8203 --ctx-size 8192 \\"
echo "    --n-gpu-layers 99 --parallel 1 --host 0.0.0.0 --jinja --metrics"
echo ""
echo "Then: python3 /tmp/drive_diagnostic.py  (T5 must produce flag_protection or seek_sanctuary)"
