#!/usr/bin/env bash
# REFERENCE cloud launch script (option c, BYO cloud).
#
# This is an EXAMPLE, not a maintained product. Wyrdsekai does not provision
# cloud for you. If you're a power user who already pays for a GPU market
# (Vast.ai here; RunPod/Lambda/your own box are the same shape), copy this,
# point `recipes.cloud_launch.script` (or $WYRDSEKAI_RECIPE_CLOUD_LAUNCH) at it,
# and tweak the ~20 lines that are yours. The Java seam (CloudRecipeDispatcher)
# only calls this when a heavy recipe is RESOURCE_DENIED locally AND no trusted
# peer could satisfy it — i.e. the last resort before "ask your steward".
#
# CONTRACT
#   Invoked as:  bash vast-launch.sh <jobspec.json>
#   jobspec.json = { recipe, agentDid, params{}, wallClockMin, requires[] }
#   MUST print exactly one JSON object as its LAST stdout line:
#     {"status":"SUCCESS|GATE_FAILED|STEP_FAILED|ERROR","artifact":"<path|uri>","message":"..."}
#   Anything else on stdout is treated as logs.
#
# TWO CONVENTIONS THAT MAKE THIS CHEAP AND SAFE
#   1. Pull the base model FROM HF on the cloud box — never upload 17GB. Only the
#      (small) banks travel. wyrdsekai bases live at hf.co/wyrdsekai-org.
#   2. wallClockMin is a HARD kill-TTL. Arm teardown BEFORE the run starts so a
#      crash/hang/forgotten box can't bleed money. This script destroys the
#      instance on exit (trap) and at TTL (timeout), whichever comes first.
#
# REQUIREMENTS ON THE CONTROLLING MACHINE (yours, not ours): jq, and the vast CLI
# (`pip install vastai`) authenticated (`vast set api-key …`). Swap the rent/ssh/
# destroy lines for RunPod's CLI, `ssh my-box`, slurm `sbatch`, etc. — the
# contract is all that matters.
set -euo pipefail

JOB="${1:?usage: vast-launch.sh <jobspec.json>}"
emit() { printf '{"status":"%s","artifact":"%s","message":"%s"}\n' "$1" "${2:-}" "${3:-}"; }

RECIPE=$(jq -r '.recipe' "$JOB")
WALLCLOCK_MIN=$(jq -r '.wallClockMin // 60' "$JOB")
# The recipe's own params name the banks + (informational) base. Your training
# image's entrypoint reads the same jobspec — keep the contract, not our paths.
ROLLOUT=$(jq -r '.params.rollout_bank // empty' "$JOB")
WARMUP=$(jq -r '.params.warmup_corpus // empty' "$JOB")

# --- what your setup needs to fill in -------------------------------------
HF_BASE="${WYRD_HF_BASE:-wyrdsekai-org/wyrdsekai-3.5-9b}"   # pulled ON the cloud box
GPU_QUERY="${WYRD_VAST_QUERY:-num_gpus>=2 gpu_ram>=48 rentable=true}"
IMAGE="${WYRD_CLOUD_IMAGE:-ghcr.io/wyrdsekai/oracle-train:latest}"  # oracle env baked in
TTL_SEC=$(( WALLCLOCK_MIN * 60 + 1800 ))   # +30m teardown headroom
# --------------------------------------------------------------------------

INSTANCE=""
teardown() { [ -n "$INSTANCE" ] && vast destroy instance "$INSTANCE" >/dev/null 2>&1 || true; }
trap teardown EXIT INT TERM   # convention #2: never leave a box billing

OFFER=$(vast search offers "$GPU_QUERY" -o 'dph' --raw | jq -r '.[0].id // empty')
[ -z "$OFFER" ] && { emit ERROR "" "no rentable GPU offer matched: $GPU_QUERY"; exit 0; }

INSTANCE=$(vast create instance "$OFFER" --image "$IMAGE" --disk 80 --raw | jq -r '.new_contract')
[ -z "$INSTANCE" ] && { emit ERROR "" "vast create failed"; exit 0; }

# Wait for ssh, ship ONLY the small banks (convention #1: base pulls from HF).
for f in "$ROLLOUT" "$WARMUP"; do
  [ -n "$f" ] && [ -f "$f" ] && vast copy "$f" "$INSTANCE:/work/$(basename "$f")" || true
done

# Run the recipe inside the image's entrypoint, bounded by the kill-TTL.
# The image pulls $HF_BASE itself and runs scripts/training/emit_rft/*.
if timeout "${TTL_SEC}s" vast execute "$INSTANCE" \
      "wyrd-train --recipe '$RECIPE' --hf-base '$HF_BASE' --job /work/$(basename "$JOB")"; then
  # Retrieve the merged artifact (your entrypoint writes /work/out/adapter.gguf).
  OUT="${WYRD_CLOUD_OUT:-$PWD/data/training/cloud-out}"; mkdir -p "$OUT"
  vast copy "$INSTANCE:/work/out/" "$OUT/" >/dev/null 2>&1 || true
  emit SUCCESS "$OUT" "trained '$RECIPE' on vast instance $INSTANCE (gate runs locally on return)"
else
  rc=$?
  [ "$rc" = 124 ] && emit STEP_FAILED "" "hit wall-clock kill-TTL (${TTL_SEC}s) — torn down" \
                  || emit STEP_FAILED "" "remote run failed rc=$rc — torn down"
fi
# trap teardown fires here.
