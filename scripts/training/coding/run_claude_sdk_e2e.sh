#!/usr/bin/env bash
# Claude Code SDK E2E runner — analog of run_openhands_e2e.sh for the
# CLOUD_PAID Anthropic backend.
#
# Unlike the OpenHands / OpenCode runners, this one does NOT boot the
# local llama-drive — Claude SDK calls Anthropic's cloud, so the only
# dependency is the `claude` binary on PATH plus an auth path
# (ANTHROPIC_API_KEY or an OAuth login under ~/.claude/).
#
# Usage:
#   bash scripts/training/coding/run_claude_sdk_e2e.sh                # ApiKey via env
#   WYRDSEKAI_E2E_CLAUDE_USE_OAUTH=1 \
#     bash scripts/training/coding/run_claude_sdk_e2e.sh              # OAuth session
#
# Env overrides:
#   ANTHROPIC_API_KEY              Anthropic API key for ApiKey path.
#   WYRDSEKAI_E2E_CLAUDE_USE_OAUTH 1 to use ~/.claude/ OAuth instead.
#   WYRDSEKAI_CLAUDE_SDK_MODEL     model alias passed to claude --model
#                                   (default: haiku — cheapest tier).
#
# Cost note: each task hits Anthropic's billing. The 3-task suite at
# haiku is typically <$0.001 per run. Sonnet/Opus models are pricier.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

CLAUDE_MODEL="${WYRDSEKAI_CLAUDE_SDK_MODEL:-haiku}"
RESULTS_DIR="$REPO_ROOT/data/training/coding/claude_sdk_e2e_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  Claude Code SDK E2E runner"
echo "═══════════════════════════════════════════════════════════════"
echo "Model:           $CLAUDE_MODEL"
echo "Results dir:     $RESULTS_DIR"
echo

# 1. Verify the `claude` binary is reachable.
if ! command -v claude >/dev/null 2>&1; then
    echo "ERROR: 'claude' not on PATH." >&2
    echo "  Install: npm i -g @anthropic-ai/claude-code" >&2
    echo "  Or any path that puts a 'claude' binary on PATH." >&2
    exit 1
fi
ACTUAL_VERSION="$(claude --version 2>/dev/null || echo unknown)"
echo "[claude] host binary: $(command -v claude) (version: $ACTUAL_VERSION)"

# 2. Verify auth path is plausible.
auth_mode="missing"
if [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
    auth_mode="ApiKey (ANTHROPIC_API_KEY set, length=${#ANTHROPIC_API_KEY})"
elif [[ "${WYRDSEKAI_E2E_CLAUDE_USE_OAUTH:-}" == "1" || \
        "${WYRDSEKAI_E2E_CLAUDE_USE_OAUTH:-}" == "true" ]]; then
    auth_mode="OAuthSession (using ~/.claude/)"
fi
echo "[claude] auth mode: $auth_mode"
if [[ "$auth_mode" == "missing" ]]; then
    echo
    echo "WARN: no auth env wired — task1 (health) will run, but tasks 2+3"
    echo "      will Assumptions.assumeTrue-skip with a clean reason. To"
    echo "      run the live submit + items-as-tools tests, set:"
    echo "        ANTHROPIC_API_KEY=sk-ant-…"
    echo "      or"
    echo "        WYRDSEKAI_E2E_CLAUDE_USE_OAUTH=1"
    echo
fi

# 3. Run ClaudeSdkE2ETest.
echo "[runner] Running ClaudeSdkE2ETest (3 tasks: health, submit, items-as-tools)..."
start_ts=$(date +%s)
set +e
WYRDSEKAI_E2E_CLAUDE_SDK=1 \
WYRDSEKAI_CLAUDE_SDK_MODEL="$CLAUDE_MODEL" \
    ./gradlew :e2e-test:test \
        -PincludeTags=e2e \
        --tests "*ClaudeSdkE2ETest" \
        --no-daemon \
        --rerun-tasks \
        > "$RESULTS_DIR/gradle.log" 2>&1
exit_code=$?
set -e
end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
echo "[runner] ClaudeSdkE2ETest finished in ${elapsed}s (exit=$exit_code)"

# 4. Save reports.
if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$RESULTS_DIR/test-report"
fi
if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$RESULTS_DIR/junit-xml"
fi

# 5. Pass/fail summary (filter to ClaudeSdk*).
if compgen -G "$RESULTS_DIR/junit-xml/TEST-*ClaudeSdk*.xml" > /dev/null 2>&1; then
    echo
    echo "[runner] Pass/fail by test:"
    for xml in "$RESULTS_DIR"/junit-xml/TEST-*ClaudeSdk*.xml; do
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

echo
echo "═══════════════════════════════════════════════════════════════"
echo "  Claude SDK E2E run complete (exit=$exit_code)"
echo "  Results: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"

exit $exit_code
