#!/usr/bin/env bash
# Goose E2E runner — sibling of run_opencode_e2e.sh for the second of the
# Phase 2d trio.
#
# Boots llama-drive (9B drive on :8083) + llama-voice (4B on :8201), verifies
# `goose` is reachable on the host (curl-installer path; see comment block in
# docker/docker-compose.e2e.yml goose service for the alternative containerised
# path), then runs GooseE2ETest with the right env vars set.
#
# Goose's `local` provider points at any OpenAI-compatible endpoint via
# OPENAI_HOST + OPENAI_API_KEY env vars. The wyrdsekai GooseBackend adapter
# does NOT inject those itself — they travel through process inheritance from
# this shell into the gradle JVM into the spawned subprocess. The runner sets
# them explicitly below so the test JVM is self-contained.
#
# Usage:
#   bash scripts/training/coding/run_goose_e2e.sh                      # default 9B vitality v6
#   bash scripts/training/coding/run_goose_e2e.sh v5                   # 9B vitality v5
#   GOOSE_INSTALL_DIR=/opt/goose bash scripts/training/coding/run_goose_e2e.sh
#
# Env overrides:
#   LLAMA_DRIVE_MODEL  (default: 9b-vitality-v6 GGUF)
#   LLAMA_VOICE_MODEL  (default: 4b-balanced base GGUF)
#   GOOSE_PROVIDER     (default: openai — local llama-server speaks OAI shim)
#   GOOSE_MODEL        (default: 9b-vitality-${VERSION}-q4km — model name
#                       sent to llama-server's /v1/chat/completions)
#   GOOSE_INSTALL_DIR  (default: empty — assume goose on PATH; if set, install
#                       Goose to that dir via the curl-installer if missing)
#   WYRDSEKAI_DATA     (default: $REPO_ROOT/data)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-v4}"

DRIVE_MODEL="${LLAMA_DRIVE_MODEL:-9b-vitality-${VERSION}-q4km.gguf}"
VOICE_MODEL="${LLAMA_VOICE_MODEL:-4b-vitality-v5-q4km.gguf}"
WYRD_DATA="${WYRDSEKAI_DATA:-$REPO_ROOT/data}"

# Goose-specific defaults. Local llama-server speaks OpenAI-compatible
# /v1/chat/completions, so the upstream-canonical "openai" provider works
# unchanged with API_KEY=not-required.
GOOSE_PROVIDER_DEFAULT="${GOOSE_PROVIDER:-openai}"
GOOSE_MODEL_DEFAULT="${GOOSE_MODEL:-wyrdsekai-3.5-9b-vitality-${VERSION}-q4km}"
GOOSE_INSTALL_DIR="${GOOSE_INSTALL_DIR:-}"

RESULTS_DIR="$REPO_ROOT/data/training/coding/goose_e2e_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  Goose E2E runner"
echo "═══════════════════════════════════════════════════════════════"
echo "Drive model:     $DRIVE_MODEL"
echo "Voice model:     $VOICE_MODEL"
echo "Goose provider:  $GOOSE_PROVIDER_DEFAULT"
echo "Goose model:     $GOOSE_MODEL_DEFAULT"
echo "Results dir:     $RESULTS_DIR"
echo

COMPOSE="docker compose -f docker/docker-compose.e2e.yml"

# 0. Verify model files exist on disk before booting anything.
for f in "$DRIVE_MODEL" "$VOICE_MODEL"; do
    if [[ ! -f "$WYRD_DATA/models/$f" ]]; then
        echo "ERROR: $WYRD_DATA/models/$f not found" >&2
        exit 1
    fi
done

# 1. Verify Goose is on the host PATH (or in GOOSE_INSTALL_DIR). The E2E
# test gates on WYRDSEKAI_E2E_GOOSE=1 — set only after confirming the
# binary resolves. Don't auto-install behind the operator's back; the
# upstream curl-installer touches ~/.local/bin and configures shells.
if [[ -n "$GOOSE_INSTALL_DIR" ]] && [[ ! -x "$GOOSE_INSTALL_DIR/goose" ]]; then
    echo "[goose] $GOOSE_INSTALL_DIR/goose not found — installing via upstream curl-installer..."
    mkdir -p "$GOOSE_INSTALL_DIR"
    GOOSE_BIN_DIR="$GOOSE_INSTALL_DIR" CONFIGURE=false \
        bash -c "$(curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh)" \
        2>&1 | tee "$RESULTS_DIR/goose-install.log"
fi
if [[ -n "$GOOSE_INSTALL_DIR" ]] && [[ -x "$GOOSE_INSTALL_DIR/goose" ]]; then
    export PATH="$GOOSE_INSTALL_DIR:$PATH"
fi

if ! command -v goose >/dev/null 2>&1; then
    echo "ERROR: 'goose' not on PATH." >&2
    echo "  Install with: " >&2
    echo "    mkdir -p \$HOME/.local/bin && \\" >&2
    echo "    GOOSE_BIN_DIR=\$HOME/.local/bin CONFIGURE=false \\" >&2
    echo "      bash -c \"\$(curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh)\"" >&2
    echo "  Or rerun with GOOSE_INSTALL_DIR=/some/dir to auto-install." >&2
    exit 1
fi
ACTUAL_VERSION="$(goose --version 2>/dev/null || echo unknown)"
echo "[goose] host binary: $(command -v goose) (version: $ACTUAL_VERSION)"

# 2. Tear down any stale compose state.
$COMPOSE --profile drive down 2>/dev/null || true

# 3. Boot drive + voice (same shape as run_opencode_e2e.sh).
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

# 5. Quick GPU sanity (same as run_opencode_e2e.sh).
if command -v nvidia-smi >/dev/null 2>&1; then
    gpu_mib=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | head -1 | tr -d ' ')
    echo "[runner] GPU memory in use: ${gpu_mib} MiB"
    if (( gpu_mib < 4000 )); then
        echo "[runner] WARN: GPU usage suspiciously low (${gpu_mib} MiB). Expected ~7-9GB." >&2
    fi
fi

# 6. Run GooseE2ETest.
#
# Env wiring contract (subprocess inherits everything below):
#   WYRDSEKAI_E2E_BACKEND=llama-server   — selects the dual-inference shape
#   WYRDSEKAI_E2E_GOOSE=1                — passes the test class's gate
#   WYRDSEKAI_INFERENCE_URL              — drive endpoint (8083)
#   WYRDSEKAI_E2E_VOICE_URL              — voice endpoint (8201)
#   WYRDSEKAI_MODEL                      — model id used for direct
#                                          InferenceClient warmup
#   GOOSE_PROVIDER / GOOSE_MODEL         — read by the upstream Goose
#                                          binary's run-mode flags
#   OPENAI_HOST / OPENAI_API_KEY         — Goose's openai-provider env
#                                          contract; llama-server ignores
#                                          the key but Goose validates
#                                          it's non-empty
echo "[runner] Running GooseE2ETest (10 progressive tasks)..."
start_ts=$(date +%s)
set +e
WYRDSEKAI_E2E_BACKEND=llama-server \
WYRDSEKAI_E2E_GOOSE=1 \
WYRDSEKAI_INFERENCE_URL=http://localhost:8083 \
WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
WYRDSEKAI_MODEL="$GOOSE_MODEL_DEFAULT" \
GOOSE_PROVIDER="$GOOSE_PROVIDER_DEFAULT" \
GOOSE_MODEL="$GOOSE_MODEL_DEFAULT" \
OPENAI_HOST=http://localhost:8083/v1 \
OPENAI_API_KEY=not-required \
    ./gradlew :e2e-test:test \
        -PincludeTags=e2e \
        --tests "*GooseE2ETest" \
        --no-daemon \
        --rerun-tasks \
        > "$RESULTS_DIR/gradle.log" 2>&1
exit_code=$?
set -e
end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
echo "[runner] GooseE2ETest finished in ${elapsed}s (exit=$exit_code)"

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
echo "  Goose E2E run complete (exit=$exit_code)"
echo "  Results: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"

exit $exit_code
