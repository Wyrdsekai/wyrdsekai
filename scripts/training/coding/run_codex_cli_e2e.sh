#!/usr/bin/env bash
# OpenAI Codex CLI E2E runner — analog of run_claude_sdk_e2e.sh
#
# Like the Claude runner, this does NOT boot llama-drive — Codex calls
# OpenAI's cloud. Only `codex` (Rust binary, openai/codex GitHub
# releases) plus an auth path is needed.
#
# Usage:
#   bash scripts/training/coding/run_codex_cli_e2e.sh                 # ApiKey via env
#   WYRDSEKAI_E2E_CODEX_USE_OAUTH=1 \
#     bash scripts/training/coding/run_codex_cli_e2e.sh               # OAuth (~/.codex/)
#
# Env overrides:
#   OPENAI_API_KEY                OpenAI API key for ApiKey path.
#   CODEX_API_KEY                 alias upstream `codex exec` honours.
#   WYRDSEKAI_E2E_CODEX_USE_OAUTH 1 to use ~/.codex/auth.json OAuth instead.
#   WYRDSEKAI_CODEX_PROVIDER      provider override (default: codex picks).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"

CODEX_PROVIDER="${WYRDSEKAI_CODEX_PROVIDER:-}"
RESULTS_DIR="$REPO_ROOT/data/training/coding/codex_cli_e2e_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  Codex CLI E2E runner"
echo "═══════════════════════════════════════════════════════════════"
echo "Provider:        ${CODEX_PROVIDER:-<codex default>}"
echo "Results dir:     $RESULTS_DIR"
echo

# 1. Verify the `codex` binary is reachable.
if ! command -v codex >/dev/null 2>&1; then
    echo "ERROR: 'codex' not on PATH." >&2
    echo "  Install: download from https://github.com/openai/codex/releases" >&2
    echo "  (Rust binary, choose the asset matching your platform.)" >&2
    echo
    echo "  Alternatively the npm distribution: 'npm i -g @openai/codex'." >&2
    echo
    echo "  After install, verify with: codex --version" >&2
    exit 1
fi
ACTUAL_VERSION="$(codex --version 2>/dev/null || echo unknown)"
echo "[codex] host binary: $(command -v codex) (version: $ACTUAL_VERSION)"

# 2. Verify auth.
auth_mode="missing"
if [[ -n "${OPENAI_API_KEY:-}" ]]; then
    auth_mode="ApiKey (OPENAI_API_KEY set, length=${#OPENAI_API_KEY})"
elif [[ -n "${CODEX_API_KEY:-}" ]]; then
    auth_mode="ApiKey (CODEX_API_KEY set, length=${#CODEX_API_KEY})"
elif [[ "${WYRDSEKAI_E2E_CODEX_USE_OAUTH:-}" == "1" || \
        "${WYRDSEKAI_E2E_CODEX_USE_OAUTH:-}" == "true" ]]; then
    auth_mode="OAuthSession (using ~/.codex/auth.json)"
fi
echo "[codex] auth mode: $auth_mode"
if [[ "$auth_mode" == "missing" ]]; then
    echo
    echo "WARN: no auth env wired — task1 (health) will run, but tasks 2+3"
    echo "      will Assumptions.assumeTrue-skip with a clean reason. To"
    echo "      run live submit + items-as-tools, set:"
    echo "        OPENAI_API_KEY=sk-…"
    echo "      or"
    echo "        WYRDSEKAI_E2E_CODEX_USE_OAUTH=1"
    echo
fi

# 3. Run CodexCliE2ETest.
echo "[runner] Running CodexCliE2ETest (3 tasks: health, submit, items-as-tools)..."
start_ts=$(date +%s)
set +e
WYRDSEKAI_E2E_CODEX_CLI=1 \
${CODEX_PROVIDER:+WYRDSEKAI_CODEX_PROVIDER=$CODEX_PROVIDER} \
    ./gradlew :e2e-test:test \
        -PincludeTags=e2e \
        --tests "*CodexCliE2ETest" \
        --no-daemon \
        --rerun-tasks \
        > "$RESULTS_DIR/gradle.log" 2>&1
exit_code=$?
set -e
end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
echo "[runner] CodexCliE2ETest finished in ${elapsed}s (exit=$exit_code)"

# 4. Save reports.
if [[ -d "$REPO_ROOT/e2e-test/build/reports/tests/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/reports/tests/test" "$RESULTS_DIR/test-report"
fi
if [[ -d "$REPO_ROOT/e2e-test/build/test-results/test" ]]; then
    cp -r "$REPO_ROOT/e2e-test/build/test-results/test" "$RESULTS_DIR/junit-xml"
fi

# 5. Pass/fail summary.
if compgen -G "$RESULTS_DIR/junit-xml/TEST-*CodexCli*.xml" > /dev/null 2>&1; then
    echo
    echo "[runner] Pass/fail by test:"
    for xml in "$RESULTS_DIR"/junit-xml/TEST-*CodexCli*.xml; do
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
echo "  Codex CLI E2E run complete (exit=$exit_code)"
echo "  Results: $RESULTS_DIR"
echo "═══════════════════════════════════════════════════════════════"

exit $exit_code
