#!/usr/bin/env bash
# Re-run V3 task9 + V4 task8 to check for flakes.
#
# Each version: boot drive+voice, run single test method, tear down.
# Outputs per-version PASS/FAIL summary. Same dual-inference setup as
# ember_compare_9b.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"
COMPOSE="docker compose -f docker/docker-compose.e2e.yml"
RESULTS_DIR="$REPO_ROOT/data/training/vitality/probe_results/ember_9b_rerun_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

run_single() {
    local v="$1" task="$2"
    local model_file="9b-vitality-${v}-q4km.gguf"
    local log_dir="$RESULTS_DIR/${v}_${task}"
    mkdir -p "$log_dir"
    echo
    echo "═══ ${v^^} — $task (re-run) ═══"

    $COMPOSE --profile drive down 2>/dev/null || true
    LLAMA_DRIVE_MODEL="$model_file" \
    LLAMA_VOICE_MODEL="wyrdsekai-3.5-4b-balanced-q4km.gguf" \
    LLAMA_DRIVE_GPU_DEVICES="0" LLAMA_VOICE_GPU_DEVICES="0" \
    LLAMA_DRIVE_CTX_SIZE="16384" LLAMA_VOICE_CTX_SIZE="8192" \
    WYRDSEKAI_DATA="$REPO_ROOT/data" \
        $COMPOSE --profile drive up -d llama-drive llama-voice 2>&1 \
        | tee "$log_dir/compose-up.log" | tail -3

    local deadline=$(( $(date +%s) + 300 ))
    while true; do
        local now=$(date +%s)
        (( now >= deadline )) && { echo "[$v] Timeout"; $COMPOSE --profile drive down; return 1; }
        local d_ok=0 vo_ok=0
        curl -sf http://localhost:8083/v1/models > /dev/null 2>&1 && d_ok=1
        curl -sf http://localhost:8201/v1/models > /dev/null 2>&1 && vo_ok=1
        (( d_ok == 1 && vo_ok == 1 )) && break
        sleep 3
    done
    echo "[$v] Backends healthy. GPU: $(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | head -1) MiB"

    local start_ts=$(date +%s)
    set +e
    WYRDSEKAI_E2E_BACKEND=llama-server \
    WYRDSEKAI_INFERENCE_URL=http://localhost:8083 \
    WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
    WYRDSEKAI_MODEL=wyrdsekai-3.5-9b-vitality-${v}-q4km \
        ./gradlew :e2e-test:test \
            -PincludeTags=e2e \
            --tests "*EmberProgressiveTasksE2ETest.${task}" \
            --no-daemon --rerun-tasks \
            > "$log_dir/gradle.log" 2>&1
    local exit_code=$?
    set -e
    local elapsed=$(( $(date +%s) - start_ts ))
    echo "[$v] $task done in ${elapsed}s (exit=$exit_code)"

    if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
        cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$log_dir/test-report"
    fi
    if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
        cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$log_dir/junit-xml"
    fi

    # Pull the actual delivered text from gradle log
    grep -A 1 "\[Task" "$log_dir/gradle.log" | head -10 | tee "$log_dir/delivery.txt"

    # Pass/fail
    if grep -q "FAILURE\|FAILED" "$log_dir/gradle.log" 2>/dev/null; then
        echo "  RESULT: ✗ FAIL"
    elif grep -q "SUCCESS" "$log_dir/gradle.log" 2>/dev/null; then
        echo "  RESULT: ✓ PASS"
    else
        echo "  RESULT: ? unclear"
    fi

    $COMPOSE --profile drive down 2>&1 | tail -3
}

run_single v3 task9_create_book_from_template
run_single v4 task8_discover_workshop_catalog
# V5 had 3 fails: task5 (oracle timeout), task8 (workshop timeout), task9 (engaged conversationally).
# Rerun all three to be fair — confirm if any are flakes.
run_single v5 task5_oracle_query
run_single v5 task8_discover_workshop_catalog
run_single v5 task9_create_book_from_template

echo
echo "═══ Summary ═══"
echo "Results: $RESULTS_DIR"
for d in "$RESULTS_DIR"/*/; do
    name=$(basename "$d")
    if grep -q "SUCCESS" "$d/gradle.log" 2>/dev/null; then
        echo "  ✓ $name (was FAIL on first run, PASSED re-run → flake)"
    elif grep -q "FAILURE\|FAILED" "$d/gradle.log" 2>/dev/null; then
        echo "  ✗ $name (consistent fail — real issue)"
    fi
done
