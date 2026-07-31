#!/usr/bin/env bash
# Full Ember matrix: drive versions × 3 languages × V8 4B voice (constant).
#
# Production lineup (post-2026-05-08): drive=V4 9B vitality, voice=V8 4B
# (Qwen3.5-4B-Instruct base + repeng control vectors). The voice is fixed
# across all configs; only the drive varies. V5/V6/V7 were drive-corpus
# iterations on the 9B; V8 is a separate axis (control-vector steering on
# top of the balanced 4B base).
#
# Voice file: wyrdsekai-3.5-4b-balanced-q4km.gguf (the V8 base).
# Voice steering: data/training/v8/vectors/* applied via docker-compose
# defaults (anti_defiance.gguf:0.15, es_register_hold.gguf:0.20,
# refusal_stability.gguf:0.20).
#
# Configs (in order):
#   1. V5 drive + V8 voice, EN
#   2. V4 drive + V8 voice, EN
#   3. V5 drive + V8 voice, JA
#   4. V4 drive + V8 voice, JA
#   5. V5 drive + V8 voice, ES
#   6. V4 drive + V8 voice, ES
#
# Each config = 1 ember_compare_9b.sh invocation = 14 tests = ~25 min.
# Total ~2.5h.
#
# If a config has already been run (e.g., V5 EN currently in flight when
# this script starts), this will wait for it to finish before queueing.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"
SCRIPT="$REPO_ROOT/scripts/training/vitality/ember_compare_9b.sh"

# V8 = production voice base (Qwen3.5-4B + repeng control vectors).
# Override via EMBER_VOICE_MODEL if A/B'ing a different 4B.
VOICE_FILE="${EMBER_VOICE_MODEL:-wyrdsekai-3.5-4b-balanced-q4km.gguf}"

# Wait for any in-flight gradle/ember run to finish.
wait_for_clear() {
    local label="$1"
    while pgrep -f "GradleWorkerMain\|ember_compare_9b" > /dev/null 2>&1; do
        echo "[matrix] $label — waiting for in-flight run to finish..." >&2
        sleep 30
    done
    docker compose -f "$REPO_ROOT/docker/docker-compose.e2e.yml" --profile drive down 2>/dev/null || true
    sleep 5
}

run_config() {
    local label="$1"; shift
    echo
    echo "════════════════════════════════════════════════════════════════"
    echo "  [$(date +%H:%M:%S)] CONFIG: $label"
    echo "════════════════════════════════════════════════════════════════"
    wait_for_clear "$label"
    EMBER_VOICE_MODEL="$VOICE_FILE" bash "$SCRIPT" "$@"
}

# Skip V5+V8 EN if currently in flight when matrix starts.
echo "[$(date +%H:%M:%S)] Matrix start — waiting for any in-flight run to finish first..."
wait_for_clear "pre-matrix wait"
echo "[$(date +%H:%M:%S)] Cleared. Beginning matrix sweep (voice=$VOICE_FILE)."

# 5 remaining configs (V5+V8 EN may already have been run; will land on disk separately):
run_config "V4+V8 EN" v4
run_config "V5+V8 JA" --lang ja v5
run_config "V4+V8 JA" --lang ja v4
run_config "V5+V8 ES" --lang es v5
run_config "V4+V8 ES" --lang es v4

echo
echo "════════════════════════════════════════════════════════════════"
echo "  [$(date +%H:%M:%S)] MATRIX DONE"
echo "════════════════════════════════════════════════════════════════"
echo "Results dirs:"
ls -1dt "$REPO_ROOT"/data/training/vitality/probe_results/ember_9b_compare_* | head -10
