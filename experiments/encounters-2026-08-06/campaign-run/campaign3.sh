#!/bin/bash
# Encounter campaign phase 1 — v2. REWRITTEN after box deaths #4/#5.
#
# WHAT CHANGED AND WHY (: Ada RTX6000 48G + 2x A6000 NVLink'd
# + 1x A6000 standalone):
#   v1 passed -ngl 99 with NO device pinning -> llama.cpp spread EVERY model
#   across all 4 GPUs, so a 9B that fits on one card lit up the whole box.
#   v2 gives each model a GPU BUDGET and pins CUDA_VISIBLE_DEVICES:
#     tier 1 (<=40GB)  -> ONE A6000
#     tier 2 (<=90GB)  -> the NVLINKED PAIR (96GB pooled, fast interconnect)
#     tier 4 (>90GB)   -> all four (only the true frontier models need this)
#   The NVLink pair is auto-detected from `nvidia-smi topo -m` (NV# links),
#   never hardcoded, because nvidia-smi and PyTorch disagree on ordering.
#   NOTE: we set CUDA_DEVICE_ORDER=PCI_BUS_ID *here only* so CUDA indices
#   match the nvidia-smi indices we read the topology from. Training scripts
# keep the recipe (CUDA_VISIBLE_DEVICES=0 = Ada) untouched.
#
# CPU THREADS — the other half of the diagnosis. documents
#   this box as "model training only" with OMP_NUM_THREADS=1. Every load it
#   has survived was 1 GPU + 1 OMP thread. v1 let llama-server default its
#   thread count on an 80-core dual-socket box. Deaths #4/#5 both happened
#   under sustained many-thread + many-GPU inference with NO kernel trace
#   (no MCE, no Xid, no panic, no thermal event) — the box stops executing.
#   So: stay inside the documented envelope. -t 8, OMP_NUM_THREADS=1.
# Also: power cap lowered 250W -> 200W, and a cooldown between models.
LOG=/opt/campaign-local/logs/campaign.log
exec >> "$LOG" 2>&1
echo "=== CAMPAIGN PHASE 1 v2 START: $(date) ==="

if pgrep -f "convert-roste[r]" >/dev/null; then
  echo "CAMPAIGN-REFUSED: conversions running. One heavy job at a time."; exit 1
fi

# --- detect the NVLinked A6000 pair from the topology matrix ---
# Row/col entries of the form NV1/NV2/... mark an NVLink peer.
NVPAIR=$(nvidia-smi topo -m 2>/dev/null | awk '
  /^GPU[0-9]/ {
    row=$1; sub("GPU","",row)
    for (i=2; i<=NF; i++) if ($i ~ /^NV[0-9]+$/) { col=i-2; print row"," col; exit }
  }')
if [ -z "$NVPAIR" ]; then
  echo "WARN: no NVLink pair detected in nvidia-smi topo -m; falling back to GPUs 0,1"
  NVPAIR="0,1"
fi
ALLGPU=$(nvidia-smi --query-gpu=index --format=csv,noheader | paste -sd, -)
# solo GPU: pick the Ada BY NAME (fastest card; and it is outside the NVLink
# pair, so the 96GB pool stays intact). NEVER pick by index — index semantics
# depend on CUDA_DEVICE_ORDER, which is exactly how this got picked wrong.
# (operator has caught index-based GPU picking on this box three times.)
P1=${NVPAIR%%,*}; P2=${NVPAIR##*,}
SOLO=$(nvidia-smi --query-gpu=index,name --format=csv,noheader \
        | grep -i "ada" | cut -d, -f1 | tr -d ' ' | head -1)
if [ -z "$SOLO" ] || [ "$SOLO" = "$P1" ] || [ "$SOLO" = "$P2" ]; then
  SOLO=$(nvidia-smi --query-gpu=index --format=csv,noheader \
          | grep -vx "$P1" | grep -vx "$P2" | head -1)
fi
[ -z "$SOLO" ] && SOLO="$P1"
echo "solo GPU chosen by name: $(nvidia-smi --query-gpu=index,name --format=csv,noheader | grep "^$SOLO,")"
export CUDA_DEVICE_ORDER=PCI_BUS_ID
export OMP_NUM_THREADS=1
echo "GPU plan: solo=$SOLO  nvlink-pair=$NVPAIR  all=$ALLGPU  (OMP=1, -t 8)"

R=/ndata2/wyrd-models/roster
R2=/data5/wyrd-models/roster
G=/data5/wyrd-models/roster-gguf
MAIN=/opt/campaign-local/bin/llama-server-main
INKB=/opt/campaign-local/bin/llama-server-inkling
M3B=/opt/campaign-local/bin/llama-server-m3
MANIFEST=/opt/campaign-local/soul-claude-v3.md

# name|gguf-glob|binary|temp|tier|extra-flags     tier: 1=solo 2=nvlink-pair 4=all
MODELS="
qwen35-9b-ours|$R/qwen35-9b-ours/*.gguf|$MAIN|0.7|1|
ornith-9b|$R/ornith-9b/*.gguf|$MAIN|0.7|1|
lfm25-8b-a1b|$R/lfm25-8b-a1b/*.gguf|$MAIN|0.7|1|
lfm25-12b-floor|/ndata2/wyrd-models/lfm25/LFM2.5-1.2B-Thinking-GGUF/*.gguf|$MAIN|0.7|1|--reasoning-budget 0
llmjp4-8b|$R/llmjp4-8b/*.gguf|$MAIN|0.7|1|--reasoning-budget 0
ornith-35b|$R/ornith-35b/*Q4_K_M*.gguf|$MAIN|0.7|1|
gemma4-31b|$R/gemma4-31b/*Q4_K_M*.gguf|$MAIN|1.0|1|
gemma4-26b-a4b|$R/gemma4-26b-a4b/*Q4_K_M*.gguf|$MAIN|1.0|1|
r1-distill-70b|$R/r1-distill-70b/*Q4_K_M*.gguf|$MAIN|0.6|2|
llama33-70b-v3|/ndata2/wyrd-models/llama33-70b/*.gguf|$MAIN|0.7|2|
gpt-oss-120b|$R/gpt-oss-120b/*.gguf|$MAIN|1.0|2|--reasoning-budget 0
mistral-small4-119b|$R/mistral-small4-119b/*/*00001*.gguf|$MAIN|0.7|2|
inkling-small-v3|/ndata2/wyrd-models/inkling-small/UD-Q4_K_M/*00001*.gguf|$INKB|1.0|4|--reasoning-budget 0
minimax-m3-v3|/opt/campaign-local/models/minimax-m3/UD-IQ3_XXS/*00001*.gguf|$M3B|1.0|4|--reasoning-budget 0
hy3-295b|$R2/hy3-295b/*/*00001*.gguf|$MAIN|1.0|4|
v4-flash-291b|$R2/v4-flash-291b/*/*00001*.gguf|$MAIN|0.7|4|
olmo31-32b|$G/olmo31-32b-Q4_K_M.gguf|$MAIN|0.7|1|
qwen3-swallow-32b|$G/qwen3-swallow-32b-Q4_K_M.gguf|$MAIN|0.7|1|
qwen-agentworld-35b|/data5/wyrd-models/roster/agentworld-gguf/*Q4_K_M*.gguf|$MAIN|0.7|1|
sarashina2-70b|$G/sarashina2-70b-Q4_K_M.gguf|$MAIN|0.7|2|
shisa-70b|$G/shisa-70b-Q4_K_M.gguf|$MAIN|0.7|2|
laguna-118b|$G/laguna-118b-Q4_K_M.gguf|$MAIN|0.7|2|
"

echo "$MODELS" | while IFS='|' read -r NAME GLOB BIN TEMP TIER EXTRA; do
  [ -z "$NAME" ] && continue
  MODEL=$(ls $GLOB 2>/dev/null | head -1)
  [ -z "$MODEL" ] && { echo "SKIPPED $NAME: no gguf at $GLOB"; continue; }
  [ ! -x "$BIN" ] && { echo "SKIPPED $NAME: no binary $BIN"; continue; }
  # RESUME: skip if all 3 runs already have transcripts (this box dies a lot)
  HAVE=0
  for RN in 1 2 3; do
    ls /opt/campaign-local/encounters/encounter-$NAME-n$RN-*.md >/dev/null 2>&1 && HAVE=$((HAVE+1))
  done
  if [ "$HAVE" -ge 3 ]; then echo "RESUME-SKIP $NAME: 3/3 transcripts present"; continue; fi
  [ "$HAVE" -gt 0 ] && echo "  (resuming $NAME: $HAVE/3 transcripts already present)"
  case "$TIER" in
    1) DEVS="$SOLO" ;;
    2) DEVS="$NVPAIR" ;;
    *) DEVS="$ALLGPU" ;;
  esac
  echo "--- $NAME (tier $TIER, GPUs $DEVS): $MODEL ($(date)) ---"
  pkill -9 -f "llama-serve[r]" 2>/dev/null
  sudo -n nvidia-smi -pl 130 > /dev/null 2>&1
  sleep 10
  CUDA_VISIBLE_DEVICES=$DEVS OMP_NUM_THREADS=1 \
  nohup $BIN -m "$MODEL" -ngl 99 -c 8192 --no-mmap -t 8 -tb 8 $EXTRA \
    --host 127.0.0.1 --port 8310 > /opt/campaign-local/logs/serve-$NAME.log 2>&1 < /dev/null &
  OK=""
  for i in $(seq 1 180); do
    sleep 10
    curl -s -m 5 http://127.0.0.1:8310/health 2>/dev/null | grep -q 'ok' && { OK=1; break; }
    pgrep -f "port 8310" > /dev/null || break
  done
  if [ -z "$OK" ]; then
    echo "SKIPPED $NAME: never became healthy (see serve-$NAME.log)"
    pkill -9 -f "llama-serve[r]" 2>/dev/null; sleep 20; continue
  fi
  nvidia-smi --query-gpu=index,power.draw,temperature.gpu --format=csv,noheader
  for RUN in 1 2 3; do
    echo "  run $RUN"
    python3 /opt/campaign-local/encounter_battery.py http://127.0.0.1:8310 "$MANIFEST" "$NAME-n$RUN" "$TEMP"
  done
  pkill -9 -f "llama-serve[r]" 2>/dev/null
  echo "  [cooldown: waiting for all GPUs below 55C]"
  for _c in $(seq 1 80); do
    MAXT=$(nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader,nounits 2>/dev/null | sort -rn | head -1)
    [ -z "$MAXT" ] && break
    echo "    max GPU temp ${MAXT}C"
    [ "$MAXT" -lt 55 ] && break
    sleep 30
  done
done
echo "=== CAMPAIGN PHASE 1 v2 COMPLETE: $(date) ==="
ls /opt/campaign-local/encounters/ | wc -l
echo CAMPAIGN-PHASE1-DONE
