#!/usr/bin/env bash
# After the full Ember matrix + V5+V5 EN rerun complete, scan all summaries,
# pull every (config, lang, task) tuple that failed, and re-run that single
# test against its specific config. Compares first-run vs rerun to separate
# flakes from real regressions.
#
# Output: $RESULTS_DIR/per_test_reruns/<lang>_<drive>_<task>/{gradle.log,summary}
# Final report: $RESULTS_DIR/flake_analysis.md
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"
COMPOSE="docker compose -f docker/docker-compose.e2e.yml"
SCRIPT="$REPO_ROOT/scripts/training/vitality/ember_compare_9b.sh"

RESULTS_DIR="$REPO_ROOT/data/training/vitality/probe_results/ember_failure_reruns_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR/per_test_reruns"
REPORT="$RESULTS_DIR/flake_analysis.md"

# Wait for in-flight matrix + rerun to clear.
wait_for_clear() {
    local label="$1"
    while pgrep -f "ember_full_matrix\|v5v5_rerun\|GradleWorkerMain\|ember_compare_9b" > /dev/null 2>&1; do
        echo "[failure-rerun] $label — waiting..." >&2
        sleep 60
    done
    $COMPOSE --profile drive down 2>/dev/null || true
    sleep 5
}

run_single_test() {
    local lang="$1" drive="$2" task="$3"
    local model_file="9b-vitality-${drive}-q4km.gguf"
    local log_dir="$RESULTS_DIR/per_test_reruns/${lang}_${drive}_${task}"
    mkdir -p "$log_dir"

    echo
    echo "═══ ${lang^^}/${drive^^} — $task (failure rerun) ═══"

    $COMPOSE --profile drive down 2>/dev/null || true

    LLAMA_DRIVE_MODEL="$model_file" \
    LLAMA_VOICE_MODEL="${EMBER_VOICE_MODEL:-wyrdsekai-3.5-4b-balanced-q4km.gguf}" \
    LLAMA_DRIVE_GPU_DEVICES="0" LLAMA_VOICE_GPU_DEVICES="0" \
    LLAMA_DRIVE_CTX_SIZE="16384" LLAMA_VOICE_CTX_SIZE="8192" \
    WYRDSEKAI_DATA="$REPO_ROOT/data" \
        $COMPOSE --profile drive up -d llama-drive llama-voice 2>&1 | tail -2

    local deadline=$(( $(date +%s) + 300 ))
    while true; do
        local now=$(date +%s)
        (( now >= deadline )) && { echo "[$lang/$drive] Timeout"; $COMPOSE --profile drive down; return 1; }
        local d_ok=0 v_ok=0
        curl -sf http://localhost:8083/v1/models > /dev/null 2>&1 && d_ok=1
        curl -sf http://localhost:8201/v1/models > /dev/null 2>&1 && v_ok=1
        (( d_ok == 1 && v_ok == 1 )) && break
        sleep 3
    done

    local lang_env=""
    local test_class="EmberProgressiveTasksE2ETest"
    if [[ "$lang" != "en" ]]; then
        lang_env="WYRDSEKAI_E2E_LANG=$lang"
        test_class="EmberProgressiveTasksMultiLangE2ETest"
    fi

    set +e
    env $lang_env \
    WYRDSEKAI_E2E_BACKEND=llama-server \
    WYRDSEKAI_INFERENCE_URL=http://localhost:8083 \
    WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
    WYRDSEKAI_MODEL=wyrdsekai-3.5-9b-vitality-${drive}-q4km \
        ./gradlew :e2e-test:test \
            -PincludeTags=e2e \
            --tests "*${test_class}.${task}" \
            --no-daemon --rerun-tasks \
            > "$log_dir/gradle.log" 2>&1
    set -e

    grep -A 1 "\[Task" "$log_dir/gradle.log" 2>/dev/null | head -10 > "$log_dir/delivery.txt" || true

    local result="?"
    if grep -q "BUILD SUCCESSFUL" "$log_dir/gradle.log" 2>/dev/null && ! grep -q "FAILED" "$log_dir/gradle.log"; then
        result="PASS"
    elif grep -q "FAILED\|FAILURE" "$log_dir/gradle.log" 2>/dev/null; then
        result="FAIL"
    fi
    echo "  Result: $result" | tee -a "$log_dir/result.txt"

    $COMPOSE --profile drive down 2>&1 | tail -1
    echo "$result"
}

extract_failures() {
    # Find every summary.txt across the recent matrix runs and emit
    # "lang drive task" lines for each ✗ row.
    local matrix_dirs=$(ls -1dt "$REPO_ROOT"/data/training/vitality/probe_results/ember_9b_compare_* 2>/dev/null | head -10)
    for d in $matrix_dirs; do
        local name=$(basename "$d")
        # name like: ember_9b_compare_en_20260503-101225 → lang=en
        local lang=$(echo "$name" | sed -E 's/ember_9b_compare_([a-z]+)_.*/\1/')
        for ver_dir in "$d"/v[345]; do
            [[ -d "$ver_dir" ]] || continue
            local drive=$(basename "$ver_dir")
            local summary="$ver_dir/summary.txt"
            [[ -f "$summary" ]] || continue
            grep '✗ ' "$summary" 2>/dev/null | sed -E 's/.*\.(task[0-9]+_[a-z_]+)\(\).*/\1/' | while read task; do
                # Only emit valid task names
                if [[ "$task" =~ ^task[0-9]+_ ]]; then
                    echo "$lang $drive $task"
                fi
            done
        done
    done | sort -u
}

# Wait for all prior runs to finish.
echo "[failure-rerun] $(date +%H:%M:%S) — waiting for matrix + V5+V5 rerun..."
wait_for_clear "pre-failure-rerun"
echo "[failure-rerun] $(date +%H:%M:%S) — clear. Scanning failures..."

# Collect unique failures
mapfile -t FAILURES < <(extract_failures)
echo "Found ${#FAILURES[@]} unique (lang, drive, task) failures:"
printf '  %s\n' "${FAILURES[@]}"
printf '  %s\n' "${FAILURES[@]}" > "$RESULTS_DIR/failures_to_rerun.txt"

# Initialize report
{
    echo "# Failure Rerun Analysis — $(date)"
    echo
    echo "Each row: a (lang, drive, task) tuple that FAILED in the matrix."
    echo "Result here is from a ONE-OFF rerun. PASS = flake; FAIL = consistent."
    echo
    echo "| Lang | Drive | Task | First-run | Rerun | Verdict |"
    echo "|---|---|---|---|---|---|"
} > "$REPORT"

for line in "${FAILURES[@]}"; do
    read -r lang drive task <<<"$line"
    [[ -z "$task" ]] && continue
    result=$(run_single_test "$lang" "$drive" "$task")
    verdict=$([[ "$result" == "PASS" ]] && echo "FLAKE" || echo "REAL FAIL")
    echo "| $lang | $drive | $task | FAIL | $result | $verdict |" >> "$REPORT"
done

echo
echo "═══════════════════════════════════════════════════════════════"
echo "  FAILURE RERUN ANALYSIS COMPLETE"
echo "═══════════════════════════════════════════════════════════════"
echo "Report: $REPORT"
cat "$REPORT"
