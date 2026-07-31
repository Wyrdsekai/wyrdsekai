#!/usr/bin/env bash
# Ember E2E comparison runner for 9B V3 vs V4 vs V5.
#
# Holds 4B voice constant (production base, no vitality), swaps 9B drive.
# Pass-rate diff = 9B contribution.
#
# Each run:
#   1. docker compose --profile drive up — starts 9B drive on :8083 + 4B voice on :8201
#   2. gradle Ember test points at both via WYRDSEKAI_E2E_BACKEND=llama-drive
#   3. Save report
#   4. compose down
#
# Usage:
#   bash scripts/training/vitality/ember_compare_9b.sh                      # Stage 1: EN, all 3 versions
#   bash scripts/training/vitality/ember_compare_9b.sh v4                   # Stage 1: EN, v4 only
#   bash scripts/training/vitality/ember_compare_9b.sh --lang ja            # Stage 2: JA, all 3
#   bash scripts/training/vitality/ember_compare_9b.sh --lang es v5         # Stage 2: ES, v5 only

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

# Parse --lang ja|es flag (default EN = Stage 1)
LANG_CODE="en"
TEST_CLASS="EmberProgressiveTasksE2ETest"
if [[ "${1:-}" == "--lang" ]]; then
    LANG_CODE="${2:?--lang requires ja|es}"
    if [[ "$LANG_CODE" != "ja" && "$LANG_CODE" != "es" ]]; then
        echo "ERROR: --lang must be ja or es (got: $LANG_CODE)" >&2; exit 1
    fi
    TEST_CLASS="EmberProgressiveTasksMultiLangE2ETest"
    shift 2
fi

VERSIONS="${1:-v3 v4 v5}"

# Voice model held constant across 9B comparison runs.
# Default = 4B V8 base (Qwen3.5-4B-Instruct + repeng control vectors loaded
# by docker-compose.e2e.yml: anti_defiance, es_register_hold, refusal_stability).
# This matches the canonical V4 9B drive + V8 4B voice production stack on home-server.
# Override with EMBER_VOICE_MODEL to A/B against another 4B GGUF.
VOICE_MODEL="${EMBER_VOICE_MODEL:-wyrdsekai-3.5-4b-balanced-q4km.gguf}"

RESULTS_DIR="$REPO_ROOT/data/training/vitality/probe_results/ember_9b_compare_${LANG_CODE}_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"
echo "Stage: $([[ "$LANG_CODE" == "en" ]] && echo "1 (EN)" || echo "2 ($LANG_CODE)")"
echo "Test class: $TEST_CLASS"
echo "Versions: $VERSIONS"
echo "Voice (constant): $VOICE_MODEL"

COMPOSE="docker compose -f docker/docker-compose.e2e.yml"

# Auto-detect: if a llama-server is already running on the prod ports
# and serving the right model, reuse it instead of spinning up our own
# containers (which would port-collide with prod and waste GPU). Probe
# returns 0 (use external) when both conditions hold; non-zero otherwise.
#
# Override URLs via EMBER_DETECT_DRIVE_URL / EMBER_DETECT_VOICE_URL.
detect_existing_inference() {
    local want_v="$1"  # drive version, e.g. "v4"
    local drive_url="${EMBER_DETECT_DRIVE_URL:-http://localhost:8200}"
    local voice_url="${EMBER_DETECT_VOICE_URL:-http://localhost:8201}"

    # Drive: model id must contain the version we want (e.g. "9b-v5-q4km.gguf")
    local d_resp=$(curl -sf -m 3 "$drive_url/v1/models" 2>/dev/null) || return 1
    local d_model=$(echo "$d_resp" | python3 -c \
        "import json,sys;d=json.load(sys.stdin);print((d.get('data') or [{}])[0].get('id',''))" \
        2>/dev/null)
    [[ -z "$d_model" ]] && return 1
    case "$d_model" in
        *"vitality-${want_v}"*) ;;  # match
        *) return 1 ;;
    esac

    # Voice: any 4B model is fine (V8 base, balanced, etc.)
    local v_resp=$(curl -sf -m 3 "$voice_url/v1/models" 2>/dev/null) || return 1
    local v_model=$(echo "$v_resp" | python3 -c \
        "import json,sys;d=json.load(sys.stdin);print((d.get('data') or [{}])[0].get('id',''))" \
        2>/dev/null)
    [[ "$v_model" == *"4b"* || "$v_model" == *"4B"* ]] || return 1

    DETECTED_DRIVE_URL="$drive_url"
    DETECTED_VOICE_URL="$voice_url"
    DETECTED_DRIVE_MODEL="$d_model"
    DETECTED_VOICE_MODEL="$v_model"
    return 0
}

run_for_version() {
    local v="$1"
    local model_file="9b-vitality-${v}-q4km.gguf"
    local log_dir="$RESULTS_DIR/$v"
    mkdir -p "$log_dir"

    echo
    echo "═══════════════════════════════════════════════════════════════"
    echo "  9B ${v^^} — Ember E2E"
    echo "═══════════════════════════════════════════════════════════════"
    echo "Model:    $model_file"
    echo "Voice:    $VOICE_MODEL (constant)"
    echo "Results:  $log_dir"
    echo

    # Auto-detect existing inference. If prod drive is already serving the
    # right version + a 4B voice, point the harness at those URLs and skip
    # the docker spin-up entirely. Saves ~5 min boot time per config and
    # avoids port-collisions with running prod (e.g., :8201 voice).
    local using_external=0
    local drive_url="http://localhost:8083"
    local voice_url="http://localhost:8201"
    if detect_existing_inference "$v"; then
        using_external=1
        drive_url="$DETECTED_DRIVE_URL"
        voice_url="$DETECTED_VOICE_URL"
        echo "[$v] Detected existing inference — reusing without docker spin-up"
        echo "  drive: $drive_url ($DETECTED_DRIVE_MODEL)"
        echo "  voice: $voice_url ($DETECTED_VOICE_MODEL)"
        echo "(set EMBER_FORCE_DOCKER=1 to override and use compose anyway)"
    fi
    if [[ "${EMBER_FORCE_DOCKER:-0}" == "1" ]]; then
        using_external=0
        drive_url="http://localhost:8083"
        voice_url="http://localhost:8201"
        echo "[$v] EMBER_FORCE_DOCKER=1 — bypassing auto-detect"
    fi

    if (( using_external == 0 )); then
        # Verify model exists locally — only needed when we'll docker-load it
        if [[ ! -f "$REPO_ROOT/data/models/$model_file" ]]; then
            echo "ERROR: $REPO_ROOT/data/models/$model_file not found" >&2
            return 1
        fi

        # Tear down any previous run
        $COMPOSE --profile drive down 2>/dev/null || true

        # Boot drive + voice
        echo "[$v] Starting llama-drive ($model_file) + llama-voice (4B base)..."
        LLAMA_DRIVE_MODEL="$model_file" \
        LLAMA_VOICE_MODEL="$VOICE_MODEL" \
        LLAMA_DRIVE_GPU_DEVICES="0" \
        LLAMA_VOICE_GPU_DEVICES="0" \
        LLAMA_DRIVE_CTX_SIZE="16384" \
        LLAMA_VOICE_CTX_SIZE="8192" \
        WYRDSEKAI_DATA="$REPO_ROOT/data" \
            $COMPOSE --profile drive up -d llama-drive llama-voice \
            2>&1 | tee "$log_dir/compose-up.log"

        # Wait for both to report ready
        echo "[$v] Waiting for backends to load..."
        local deadline=$(( $(date +%s) + 300 ))  # 5 min
        while true; do
            local now=$(date +%s)
            if (( now >= deadline )); then
                echo "[$v] Timeout waiting for backends" >&2
                $COMPOSE --profile drive logs --tail 50 > "$log_dir/compose-fail.log" 2>&1
                $COMPOSE --profile drive down
                return 1
            fi
            local d_ok=0 v_ok=0
            curl -sf "$drive_url/v1/models" > /dev/null 2>&1 && d_ok=1
            curl -sf "$voice_url/v1/models" > /dev/null 2>&1 && v_ok=1
            if (( d_ok == 1 && v_ok == 1 )); then
                echo "[$v] Both backends healthy."
                break
            fi
            sleep 3
        done

        # Confirm GPU is actually being used (compose's deploy.resources block
        # silently degrades to CPU under some configs — fail loud here so we
        # don't waste 30 min running CPU inference)
        local gpu_mib=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | head -1 | tr -d ' ')
        echo "[$v] GPU memory in use: ${gpu_mib} MiB"
        if (( gpu_mib < 4000 )); then
            echo "[$v] WARN: GPU usage suspiciously low (${gpu_mib} MiB). Expected ~7-9GB for 9B+4B." >&2
            docker logs wyrdsekai-e2e-llama-drive 2>&1 | grep -iE 'cuda|backend|offload' | head -5 || true
        fi
    fi

    # Run Ember E2E
    echo "[$v] Running Ember E2E (14 tests)..."
    local start_ts=$(date +%s)
    set +e
    local lang_env=""
    if [[ "$LANG_CODE" != "en" ]]; then
        lang_env="WYRDSEKAI_E2E_LANG=$LANG_CODE"
    fi
    # NOTE: backend type stays llama-server (not llama-drive) so the EN test
    # class's @EnabledIfEnvironmentVariable regex matches. URLs route to
    # whichever backend was selected (auto-detected prod or compose-managed).
    env $lang_env \
    WYRDSEKAI_E2E_BACKEND=llama-server \
    WYRDSEKAI_INFERENCE_URL="$drive_url" \
    WYRDSEKAI_E2E_VOICE_URL="$voice_url" \
    WYRDSEKAI_MODEL=wyrdsekai-3.5-9b-vitality-${v}-q4km \
        ./gradlew :e2e-test:test \
            -PincludeTags=e2e \
            --tests "*${TEST_CLASS}" \
            --no-daemon \
            --rerun-tasks \
            > "$log_dir/gradle.log" 2>&1
    local exit_code=$?
    set -e
    local end_ts=$(date +%s)
    local elapsed=$(( end_ts - start_ts ))
    echo "[$v] Ember finished in ${elapsed}s (exit=$exit_code)"

    # Save test report
    if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
        cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$log_dir/test-report"
    fi
    if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
        cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$log_dir/junit-xml"
    fi

    # Quick pass/fail summary from junit XML
    if compgen -G "$log_dir/junit-xml/TEST-*.xml" > /dev/null 2>&1; then
        echo
        echo "[$v] Pass/fail by test:"
        for xml in "$log_dir"/junit-xml/TEST-*.xml; do
            python3 -c "
import xml.etree.ElementTree as ET, sys
t = ET.parse('$xml').getroot()
suite_name = t.get('name', '?').split('.')[-1]
for tc in t.findall('testcase'):
    name = tc.get('name', '?')
    if tc.find('failure') is not None or tc.find('error') is not None:
        print(f'  ✗ {suite_name}.{name}')
    elif tc.find('skipped') is not None:
        print(f'  ⊘ {suite_name}.{name} (skipped)')
    else:
        print(f'  ✓ {suite_name}.{name}')
" 2>/dev/null || cat "$xml" | head -20
        done | tee -a "$log_dir/summary.txt"
    fi

    # Tear down — only if we started our own containers. Reused prod
    # inference must stay running; tearing down compose would also kill it
    # if compose somehow attached, but with auto-detect we never started
    # compose, so nothing to do.
    if (( using_external == 0 )); then
        echo "[$v] Stopping backends..."
        $COMPOSE --profile drive down 2>&1 | tail -10
    else
        echo "[$v] Skipping teardown — using external prod inference."
    fi

    echo "[$v] Done. Results: $log_dir"
}

for v in $VERSIONS; do
    run_for_version "$v" || echo "FAILED for $v, continuing..."
done

echo
echo "═══════════════════════════════════════════════════════════════"
echo "  All runs complete."
echo "  Results dir: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"
for v in $VERSIONS; do
    if [[ -f "$RESULTS_DIR/$v/summary.txt" ]]; then
        echo
        echo "── $v ──"
        cat "$RESULTS_DIR/$v/summary.txt"
    fi
done
