#!/usr/bin/env bash
# #913 — interventional-SFT A/B at 4B scale on the already-aligned V10 base.
#
# Trains two LoRA adapters on the SAME ReAct agent-transcript corpus, identical
# hyperparams, differing ONLY in which tokens carry loss gradient:
#   standard       : train on the agent's own (assistant) tokens — reproduce policy
#   interventional : mask agent tokens; train on world (system+user) tokens only
# then measures per-token NLL split by role (agent vs world) on a held-out set
# for {base, standard, interventional}.
#
# Hypothesis (de Freitas & Ortega, confirmed on 0.5B in #911):
#   interventional ⇒ WORLD nll ↓ without pathologically inflating its own AGENT
#   confidence; standard ⇒ AGENT nll ↓↓ (memorized policy), WORLD ~flat.
# #913 asks whether that separation survives on an instruct+voice-aligned 4B.
set -euo pipefail

BASE="${BASE:-/home/you/wyrdsekai-4b-v10-merged}"
DATA_DIR="${DATA_DIR:-/tmp/iv913}"
OUT="${OUT:-/tmp/iv913}"
EPOCHS="${EPOCHS:-2}"          # 2-epoch small-model SFT default (memory)
LR="${LR:-1e-5}"              # conservative, matches substrate-v2
LORA_R="${LORA_R:-8}"
MAXLEN="${MAXLEN:-1024}"
FT="${FT:-$HOME/src/wyrdsekai/scripts/training/ssd_finetune.py}"
EVAL="${EVAL:-$HOME/src/wyrdsekai/scripts/training/interventional/eval_logprob_delta.py}"

# Pin to an idle GPU by PCI order (avoids the gpu-host nvidia-smi/PyTorch
# reversal). GPU 2 = idle A6000 (48GB) at survey time. Override with CUDA_GPU.
export CUDA_DEVICE_ORDER=PCI_BUS_ID
export CUDA_VISIBLE_DEVICES="${CUDA_GPU:-2}"

source /tmp/steer-env/bin/activate
echo "=== #913 A/B :: base=$BASE gpu=$CUDA_VISIBLE_DEVICES epochs=$EPOCHS lr=$LR r=$LORA_R ==="

train() {  # $1 = mask-mode  $2 = output suffix
  echo "--- train mask-mode=$1 -> $OUT/$2 ---"
  python "$FT" --model "$BASE" \
    --data "$DATA_DIR/train.jsonl" --valid "$DATA_DIR/heldout.jsonl" \
    --output "$OUT/$2" --mask-mode "$1" \
    --epochs "$EPOCHS" --lr "$LR" --lora-r "$LORA_R" --lora-alpha $((LORA_R*2)) \
    --max-length "$MAXLEN" --batch-size 1 --gradient-accumulation 8
}

train assistant      iv_standard
train interventional iv_interventional

echo "=== EVAL (held-out NLL by role) ==="
RES="$OUT/results.jsonl"; : > "$RES"
python "$EVAL" --model "$BASE" --data "$DATA_DIR/heldout.jsonl" --label base                 --max-length "$MAXLEN" | tee -a "$RES"
python "$EVAL" --model "$BASE" --adapter "$OUT/iv_standard"      --data "$DATA_DIR/heldout.jsonl" --label standard       --max-length "$MAXLEN" | tee -a "$RES"
python "$EVAL" --model "$BASE" --adapter "$OUT/iv_interventional" --data "$DATA_DIR/heldout.jsonl" --label interventional --max-length "$MAXLEN" | tee -a "$RES"

echo "=== DONE — results in $RES ==="
cat "$RES"
