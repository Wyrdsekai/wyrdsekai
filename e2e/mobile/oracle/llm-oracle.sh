#!/usr/bin/env bash
# =============================================================================
# LLM Oracle — Evaluate screenshots using a multimodal LLM
#
# Usage:
#   ./llm-oracle.sh <screenshot_path> "<assertion>"
#   ./llm-oracle.sh screenshot.png "The screen shows a medieval room with exits"
#
# Backends (set LLM_ORACLE_BACKEND):
#   ollama   — local Ollama with llava or qwen2-vl (default)
#   claude   — Claude API via claude CLI
#
# Returns: JSON { "pass": true/false, "confidence": 0.0-1.0, "reasoning": "..." }
# Exit code: 0 = pass, 1 = fail, 2 = error
# =============================================================================
set -euo pipefail

SCREENSHOT="$1"
ASSERTION="${2:-}"
BACKEND="${LLM_ORACLE_BACKEND:-ollama}"
OLLAMA_MODEL="${LLM_ORACLE_MODEL:-llava:13b}"
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

if [ ! -f "$SCREENSHOT" ]; then
    echo '{"pass": false, "confidence": 0, "reasoning": "Screenshot not found: '"$SCREENSHOT"'"}'
    exit 2
fi

if [ -z "$ASSERTION" ]; then
    echo '{"pass": false, "confidence": 0, "reasoning": "No assertion provided"}'
    exit 2
fi

PROMPT="You are a mobile app test oracle. Look at this screenshot and evaluate the following assertion.

Assertion: $ASSERTION

Respond with EXACTLY this JSON format, nothing else:
{\"pass\": true or false, \"confidence\": 0.0 to 1.0, \"reasoning\": \"one sentence explanation\"}

Be strict: if the assertion is not clearly met, return pass: false."

case "$BACKEND" in
    ollama)
        IMG_BASE64=$(base64 -w0 "$SCREENSHOT" 2>/dev/null || base64 "$SCREENSHOT")
        RESPONSE=$(curl -sf "$OLLAMA_URL/api/generate" \
            -d "{
                \"model\": \"$OLLAMA_MODEL\",
                \"prompt\": $(echo "$PROMPT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
                \"images\": [\"$IMG_BASE64\"],
                \"stream\": false
            }" 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin).get('response',''))" 2>/dev/null)
        ;;
    claude)
        RESPONSE=$(claude --print "$PROMPT" --image "$SCREENSHOT" 2>/dev/null)
        ;;
    *)
        echo '{"pass": false, "confidence": 0, "reasoning": "Unknown backend: '"$BACKEND"'"}'
        exit 2
        ;;
esac

# Extract JSON from response (LLM may wrap it in markdown)
JSON=$(echo "$RESPONSE" | python3 -c "
import sys, json, re
text = sys.stdin.read()
match = re.search(r'\{[^}]+\}', text, re.DOTALL)
if match:
    try:
        d = json.loads(match.group())
        print(json.dumps(d))
    except:
        print(json.dumps({'pass': False, 'confidence': 0, 'reasoning': 'Failed to parse LLM response'}))
else:
    print(json.dumps({'pass': False, 'confidence': 0, 'reasoning': 'No JSON in LLM response'}))
" 2>/dev/null)

echo "$JSON"

# Set exit code based on pass/fail
PASSED=$(echo "$JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('pass', False))" 2>/dev/null)
if [ "$PASSED" = "True" ]; then
    exit 0
else
    exit 1
fi
