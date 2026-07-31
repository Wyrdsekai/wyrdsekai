#!/usr/bin/env bash
# OpenCode E2E runner — analog of ember_compare_9b.sh for the coding-backend
# abstraction layer.
#
# Boots llama-drive (9B drive on :8083) + llama-voice (4B on :8201), verifies
# `opencode` is reachable on the host (path (a) — see docker-compose.e2e.yml
# opencode service comment for the alternative path (b)), then runs
# OpenCodeE2ETest with the right env vars set.
#
# Usage:
#   bash scripts/training/coding/run_opencode_e2e.sh                    # default 9B vitality v6
#   bash scripts/training/coding/run_opencode_e2e.sh v5                 # 9B vitality v5
#   OPENCODE_VERSION=1.14.33 bash scripts/training/coding/run_opencode_e2e.sh
#
# Env overrides:
#   LLAMA_DRIVE_MODEL  (default: 9b-vitality-v6 GGUF)
#   LLAMA_VOICE_MODEL  (default: 4b-balanced base GGUF)
#   OPENCODE_VERSION   (default: 1.14.33 — pinned for reproducibility)
#   WYRDSEKAI_DATA     (default: $REPO_ROOT/data)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-v4}"
OPENCODE_VERSION="${OPENCODE_VERSION:-1.14.33}"

DRIVE_MODEL="${LLAMA_DRIVE_MODEL:-9b-vitality-${VERSION}-q4km.gguf}"
VOICE_MODEL="${LLAMA_VOICE_MODEL:-4b-vitality-v5-q4km.gguf}"
WYRD_DATA="${WYRDSEKAI_DATA:-$REPO_ROOT/data}"

RESULTS_DIR="$REPO_ROOT/data/training/coding/opencode_e2e_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  OpenCode E2E runner"
echo "═══════════════════════════════════════════════════════════════"
echo "Drive model:    $DRIVE_MODEL"
echo "Voice model:    $VOICE_MODEL"
echo "OpenCode ver:   $OPENCODE_VERSION (host-installed, path (a))"
echo "Results dir:    $RESULTS_DIR"
echo

COMPOSE="docker compose -f docker/docker-compose.e2e.yml"

# 0. Verify model files exist on disk before booting anything.
for f in "$DRIVE_MODEL" "$VOICE_MODEL"; do
    if [[ ! -f "$WYRD_DATA/models/$f" ]]; then
        echo "ERROR: $WYRD_DATA/models/$f not found" >&2
        exit 1
    fi
done

# 1. Verify OpenCode is on the host PATH. The E2E test gates on
# WYRDSEKAI_E2E_OPENCODE=1 — we set that only after confirming the binary
# resolves. (Don't auto-install with `npm install -g`: needs sudo on most
# CI hosts, and the Node major-version dance is the operator's call.)
if ! command -v opencode >/dev/null 2>&1; then
    echo "ERROR: 'opencode' not on PATH." >&2
    echo "  Install with: npm install -g opencode@$OPENCODE_VERSION" >&2
    echo "  Or activate compose profile 'coding' for the containerized path." >&2
    exit 1
fi
ACTUAL_VERSION="$(opencode --version 2>/dev/null || echo unknown)"
echo "[opencode] host binary: $(command -v opencode) (version: $ACTUAL_VERSION)"

# 2. Tear down any stale compose state.
$COMPOSE --profile drive down 2>/dev/null || true

# 3. Boot drive + voice (same shape as ember_compare_9b.sh).
echo "[runner] Starting llama-drive + llama-voice..."
LLAMA_DRIVE_MODEL="$DRIVE_MODEL" \
LLAMA_VOICE_MODEL="$VOICE_MODEL" \
LLAMA_DRIVE_GPU_DEVICES="0" \
LLAMA_VOICE_GPU_DEVICES="0" \
LLAMA_DRIVE_CTX_SIZE="16384" \
LLAMA_VOICE_CTX_SIZE="8192" \
WYRDSEKAI_DATA="$WYRD_DATA" \
    $COMPOSE --profile drive up -d llama-drive llama-voice \
    2>&1 | tee "$RESULTS_DIR/compose-up.log"

# 4. Wait for both backends.
echo "[runner] Waiting for backends to load (max 5min)..."
deadline=$(( $(date +%s) + 300 ))
while true; do
    now=$(date +%s)
    if (( now >= deadline )); then
        echo "[runner] Timeout waiting for backends" >&2
        $COMPOSE --profile drive logs --tail 50 > "$RESULTS_DIR/compose-fail.log" 2>&1
        $COMPOSE --profile drive down
        exit 1
    fi
    d_ok=0; v_ok=0
    curl -sf http://localhost:8083/v1/models > /dev/null 2>&1 && d_ok=1
    curl -sf http://localhost:8201/v1/models > /dev/null 2>&1 && v_ok=1
    if (( d_ok == 1 && v_ok == 1 )); then
        echo "[runner] Both backends healthy."
        break
    fi
    sleep 3
done

# 5. Quick GPU sanity (same as ember_compare_9b.sh).
if command -v nvidia-smi >/dev/null 2>&1; then
    gpu_mib=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | head -1 | tr -d ' ')
    echo "[runner] GPU memory in use: ${gpu_mib} MiB"
    if (( gpu_mib < 4000 )); then
        echo "[runner] WARN: GPU usage suspiciously low (${gpu_mib} MiB). Expected ~7-9GB." >&2
    fi
fi

# 6. Run OpenCodeE2ETest.
echo "[runner] Running OpenCodeE2ETest (10 progressive tasks)..."
start_ts=$(date +%s)
set +e
WYRDSEKAI_E2E_BACKEND=llama-server \
WYRDSEKAI_E2E_OPENCODE=1 \
WYRDSEKAI_INFERENCE_URL=http://localhost:8083 \
WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
WYRDSEKAI_MODEL=wyrdsekai-3.5-9b-vitality-${VERSION}-q4km \
    ./gradlew :e2e-test:test \
        -PincludeTags=e2e \
        --tests "*OpenCodeE2ETest" \
        --no-daemon \
        --rerun-tasks \
        > "$RESULTS_DIR/gradle.log" 2>&1
exit_code=$?
set -e
end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
echo "[runner] OpenCodeE2ETest finished in ${elapsed}s (exit=$exit_code)"

# 7. Save reports.
if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$RESULTS_DIR/test-report"
fi
if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$RESULTS_DIR/junit-xml"
fi

# 8. Pass/fail summary.
if compgen -G "$RESULTS_DIR/junit-xml/TEST-*.xml" > /dev/null 2>&1; then
    echo
    echo "[runner] Pass/fail by test:"
    for xml in "$RESULTS_DIR"/junit-xml/TEST-*.xml; do
        python3 -c "
import xml.etree.ElementTree as ET, sys
t = ET.parse('$xml').getroot()
suite_name = t.get('name', '?').split('.')[-1]
for tc in t.findall('testcase'):
    name = tc.get('name', '?')
    if tc.find('failure') is not None or tc.find('error') is not None:
        print(f'  FAIL  {suite_name}.{name}')
    elif tc.find('skipped') is not None:
        print(f'  SKIP  {suite_name}.{name}')
    else:
        print(f'  PASS  {suite_name}.{name}')
" 2>/dev/null || cat "$xml" | head -20
    done | tee "$RESULTS_DIR/summary.txt"
fi

# 9. Tear down.
echo
echo "[runner] Stopping backends..."
$COMPOSE --profile drive down 2>&1 | tail -10

echo
echo "═══════════════════════════════════════════════════════════════"
echo "  OpenCode E2E run complete (exit=$exit_code)"
echo "  Results: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"

exit $exit_code
