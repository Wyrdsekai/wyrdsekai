#!/usr/bin/env bash
# OpenHands E2E runner — analog of run_opencode_e2e.sh for the OpenHands
# V1 Agent Server adapter (; live-verified
# v1.19.1 reconciliation 2026-05-05).
#
# Boots:
#   - llama-drive (9B drive on :8083) + llama-voice (4B on :8201) via
#     docker-compose.e2e.yml --profile drive (matches run_opencode_e2e.sh)
#   - openhands-agent-server (v1.19.1-python on :8002) pointing its LLM
#     env at host's :8083 llama-drive
# Then runs OpenHandsE2ETest with the right env vars set.
#
# Usage:
#   bash scripts/training/coding/run_openhands_e2e.sh                    # default 9B vitality v4
#   bash scripts/training/coding/run_openhands_e2e.sh v6                 # 9B vitality v6
#   OPENHANDS_AGENT_SERVER_TAG=1.20.0-python bash scripts/training/coding/run_openhands_e2e.sh
#
# Env overrides:
#   LLAMA_DRIVE_MODEL              (default: 9b-v5-q4km.gguf)
#   LLAMA_VOICE_MODEL              (default: 4b-vitality-v5-q4km.gguf)
#   OPENHANDS_AGENT_SERVER_TAG     (default: 1.19.1-python — pinned for reproducibility)
#   OPENHANDS_AGENT_SERVER_PORT    (default: 8002 — host port for agent-server)
#   OPENHANDS_LLM_MODEL            (default: openai/9b-v5-q4km — litellm OAI prefix required)
#   WYRDSEKAI_DATA                 (default: $REPO_ROOT/data)
#
# IMPORTANT: this script does NOT touch the live wyrdsekai-llama or
# wyrdsekai-llama-voice prod containers. The e2e compose stack uses
# its own container names (wyrdsekai-e2e-*) on different host ports.
# The user / parent agent is responsible for stopping prod containers
# before running this — see operator notes at the end.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-v4}"
OPENHANDS_AGENT_SERVER_TAG="${OPENHANDS_AGENT_SERVER_TAG:-1.19.1-python}"
OPENHANDS_AGENT_SERVER_PORT="${OPENHANDS_AGENT_SERVER_PORT:-8002}"

DRIVE_MODEL="${LLAMA_DRIVE_MODEL:-9b-vitality-${VERSION}-q4km.gguf}"
VOICE_MODEL="${LLAMA_VOICE_MODEL:-4b-vitality-v5-q4km.gguf}"
WYRD_DATA="${WYRDSEKAI_DATA:-$REPO_ROOT/data}"

# litellm convention: an `openai/` prefix routes through its
# OpenAI-compatible provider (which is what llama-server speaks). Without
# the prefix, litellm tries to resolve the bare name against its own
# provider registry and fails. The model name after the prefix is what
# llama-server actually advertises (the GGUF basename, without the .gguf
# suffix and with `wyrdsekai-3.5-` stripped).
OPENHANDS_LLM_MODEL="${OPENHANDS_LLM_MODEL:-openai/9b-vitality-${VERSION}-q4km}"

RESULTS_DIR="$REPO_ROOT/data/training/coding/openhands_e2e_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  OpenHands E2E runner"
echo "═══════════════════════════════════════════════════════════════"
echo "Drive model:           $DRIVE_MODEL"
echo "Voice model:           $VOICE_MODEL"
echo "Agent-server tag:      $OPENHANDS_AGENT_SERVER_TAG"
echo "Agent-server port:     $OPENHANDS_AGENT_SERVER_PORT (host)"
echo "Agent-server LLM:      $OPENHANDS_LLM_MODEL"
echo "Results dir:           $RESULTS_DIR"
echo

COMPOSE="docker compose -f docker/docker-compose.e2e.yml"

# 0. Verify model files exist on disk before booting anything.
for f in "$DRIVE_MODEL" "$VOICE_MODEL"; do
    if [[ ! -f "$WYRD_DATA/models/$f" ]]; then
        echo "ERROR: $WYRD_DATA/models/$f not found" >&2
        exit 1
    fi
done

# 1. Verify the agent-server image is pullable (or already pulled).
# Check before tear-down so a missing image doesn't leave us with prod
# torn down and nothing booting.
echo "[runner] Checking agent-server image: ghcr.io/openhands/agent-server:${OPENHANDS_AGENT_SERVER_TAG}"
if ! docker image inspect "ghcr.io/openhands/agent-server:${OPENHANDS_AGENT_SERVER_TAG}" >/dev/null 2>&1; then
    echo "[runner] Image not local; pulling (may take a few minutes)..."
    if ! docker pull "ghcr.io/openhands/agent-server:${OPENHANDS_AGENT_SERVER_TAG}" 2>&1 | tee "$RESULTS_DIR/image-pull.log"; then
        echo "ERROR: failed to pull agent-server image" >&2
        exit 1
    fi
fi

# 2. Tear down any stale e2e compose state. Note: this only stops
# the e2e profiles (drive + openhands), NOT prod containers.
$COMPOSE --profile drive --profile openhands down 2>/dev/null || true

# 2a. Clear the openhands workspace bind-mount. The agent-server
# persists conversations under workspace/conversations/<id>/ and
# resumes ALL of them at boot. Without cleanup, a prior run's
# conversations bleed into this one — task2 then fails with
# `409 Conversation already running` (live-observed 2026-05-06).
# Use docker-as-root to delete because the dir is owned by the
# container's UID (10001), not by the host user.
WORKSPACE_DIR="$WYRD_DATA/openhands-workspace"
if [[ -d "$WORKSPACE_DIR" ]]; then
    echo "[runner] Clearing stale agent-server workspace ($WORKSPACE_DIR)..."
    docker run --rm -u root \
        -v "$WORKSPACE_DIR:/ws" \
        busybox sh -c 'rm -rf /ws/* /ws/.??* 2>/dev/null; true'
else
    mkdir -p "$WORKSPACE_DIR"
    docker run --rm -u root \
        -v "$WORKSPACE_DIR:/ws" \
        busybox chown -R 10001:10001 /ws
fi

# 3. Boot drive + voice (same shape as run_opencode_e2e.sh).
echo "[runner] Starting llama-drive + llama-voice..."
LLAMA_DRIVE_MODEL="$DRIVE_MODEL" \
LLAMA_VOICE_MODEL="$VOICE_MODEL" \
LLAMA_DRIVE_GPU_DEVICES="0" \
LLAMA_VOICE_GPU_DEVICES="0" \
LLAMA_DRIVE_CTX_SIZE="32768" \
LLAMA_VOICE_CTX_SIZE="8192" \
WYRDSEKAI_DATA="$WYRD_DATA" \
    $COMPOSE --profile drive up -d llama-drive llama-voice \
    2>&1 | tee "$RESULTS_DIR/compose-drive-up.log"

# 4. Wait for both LLM backends to be healthy.
echo "[runner] Waiting for llama backends to load (max 5min)..."
deadline=$(( $(date +%s) + 300 ))
while true; do
    now=$(date +%s)
    if (( now >= deadline )); then
        echo "[runner] Timeout waiting for llama backends" >&2
        $COMPOSE --profile drive logs --tail 50 > "$RESULTS_DIR/compose-fail.log" 2>&1
        $COMPOSE --profile drive down
        exit 1
    fi
    d_ok=0; v_ok=0
    curl -sf http://localhost:8083/v1/models > /dev/null 2>&1 && d_ok=1
    curl -sf http://localhost:8201/v1/models > /dev/null 2>&1 && v_ok=1
    if (( d_ok == 1 && v_ok == 1 )); then
        echo "[runner] Both llama backends healthy."
        break
    fi
    sleep 3
done

# 5. Boot the agent-server, pointing it at the host's llama-drive.
# We launch via `docker compose --profile openhands` so the service is
# managed alongside drive/voice (one teardown command stops all three).
echo "[runner] Starting openhands-agent-server (port $OPENHANDS_AGENT_SERVER_PORT)..."
OPENHANDS_AGENT_SERVER_TAG="$OPENHANDS_AGENT_SERVER_TAG" \
OPENHANDS_AGENT_SERVER_PORT="$OPENHANDS_AGENT_SERVER_PORT" \
OPENHANDS_LLM_MODEL="$OPENHANDS_LLM_MODEL" \
WYRDSEKAI_DATA="$WYRD_DATA" \
    $COMPOSE --profile openhands up -d openhands-agent-server \
    2>&1 | tee "$RESULTS_DIR/compose-openhands-up.log"

# 6. Wait for agent-server /health to return "OK". Live-verified: V1
# v1.19.1 returns the literal string "OK" with 200 OK on /health (NOT
# /api/health — that's a 404).
echo "[runner] Waiting for agent-server /health (max 3min)..."
deadline=$(( $(date +%s) + 180 ))
while true; do
    now=$(date +%s)
    if (( now >= deadline )); then
        echo "[runner] Timeout waiting for openhands-agent-server" >&2
        $COMPOSE --profile openhands logs --tail 100 \
            > "$RESULTS_DIR/agent-server-fail.log" 2>&1
        $COMPOSE --profile drive --profile openhands down
        exit 1
    fi
    if curl -sf "http://localhost:${OPENHANDS_AGENT_SERVER_PORT}/health" \
            > "$RESULTS_DIR/health-probe.txt" 2>&1; then
        # Body should be the literal string OK; tolerate trailing whitespace.
        body="$(cat "$RESULTS_DIR/health-probe.txt" | tr -d '[:space:]')"
        if [[ "$body" == "OK" || "$body" == '"OK"' ]]; then
            echo "[runner] agent-server /health -> OK"
            break
        fi
        echo "[runner] /health responded but body='$body' (waiting for 'OK')"
    fi
    sleep 3
done

# 7. Quick GPU sanity (same as run_opencode_e2e.sh).
if command -v nvidia-smi >/dev/null 2>&1; then
    gpu_mib=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits | head -1 | tr -d ' ')
    echo "[runner] GPU memory in use: ${gpu_mib} MiB"
    if (( gpu_mib < 4000 )); then
        echo "[runner] WARN: GPU usage suspiciously low (${gpu_mib} MiB). Expected ~7-9GB." >&2
    fi
fi

# 8. Run OpenHandsE2ETest.
echo "[runner] Running OpenHandsE2ETest (10 progressive tasks)..."
start_ts=$(date +%s)
set +e
WYRDSEKAI_E2E_BACKEND=llama-server \
WYRDSEKAI_E2E_OPENHANDS=1 \
WYRDSEKAI_INFERENCE_URL=http://localhost:8083 \
WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
WYRDSEKAI_OPENHANDS_AGENT_SERVER_URL="http://localhost:${OPENHANDS_AGENT_SERVER_PORT}" \
WYRDSEKAI_OPENHANDS_LLM_MODEL="$OPENHANDS_LLM_MODEL" \
WYRDSEKAI_OPENHANDS_WORKSPACE_MOUNT="/workspace:${WYRD_DATA}/openhands-workspace" \
WYRDSEKAI_MODEL=wyrdsekai-3.5-9b-vitality-${VERSION}-q4km \
    ./gradlew :e2e-test:test \
        -PincludeTags=e2e \
        --tests "*OpenHandsE2ETest" \
        --no-daemon \
        --rerun-tasks \
        > "$RESULTS_DIR/gradle.log" 2>&1
exit_code=$?
set -e
end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
echo "[runner] OpenHandsE2ETest finished in ${elapsed}s (exit=$exit_code)"

# 9. Save reports.
if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$RESULTS_DIR/test-report"
fi
if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$RESULTS_DIR/junit-xml"
fi

# 10. Pass/fail summary (parse the OpenHands-specific TEST-*.xml).
if compgen -G "$RESULTS_DIR/junit-xml/TEST-*OpenHands*.xml" > /dev/null 2>&1; then
    echo
    echo "[runner] Pass/fail by test:"
    for xml in "$RESULTS_DIR"/junit-xml/TEST-*OpenHands*.xml; do
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

# 11. Save agent-server logs for debugging (last 200 lines is plenty).
echo "[runner] Capturing agent-server logs..."
$COMPOSE --profile openhands logs --tail 200 openhands-agent-server \
    > "$RESULTS_DIR/agent-server.log" 2>&1 || true

# 12. Tear down e2e profiles. Prod containers are unaffected (different
# container names, different host ports).
echo
echo "[runner] Stopping e2e backends (drive + openhands profiles)..."
$COMPOSE --profile drive --profile openhands down 2>&1 | tail -10

echo
echo "═══════════════════════════════════════════════════════════════"
echo "  OpenHands E2E run complete (exit=$exit_code)"
echo "  Results: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"
echo
echo "  Operator note: this script does NOT touch the live"
echo "  wyrdsekai-llama* prod containers. If you want a 'pure' run"
echo "  against the e2e drive only, stop the prod containers first:"
echo "    docker stop wyrdsekai-llama wyrdsekai-llama-voice"
echo "    bash scripts/training/coding/run_openhands_e2e.sh"
echo "    docker start wyrdsekai-llama wyrdsekai-llama-voice"
echo

exit $exit_code
