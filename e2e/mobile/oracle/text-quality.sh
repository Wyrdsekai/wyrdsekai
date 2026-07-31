#!/usr/bin/env bash
# =============================================================================
# Text Quality Oracle — Evaluate companion response quality
#
# Usage:
#   ./text-quality.sh "<companion_response>" "<soul_manifest_path>"
#
# Checks:
#   1. Response is not empty or gibberish
#   2. Response length is reasonable (10-500 words)
#   3. Response doesn't contain raw JSON or system prompts (prompt leak)
#   4. Optional: semantic similarity to soul identity (requires BehavioralOracle)
#
# Returns: JSON { "pass": true/false, "score": 0.0-1.0, "checks": {...} }
# Exit code: 0 = pass, 1 = fail
# =============================================================================
set -euo pipefail

RESPONSE_TEXT="${1:-}"
MANIFEST_PATH="${2:-}"

if [ -z "$RESPONSE_TEXT" ]; then
    echo '{"pass": false, "score": 0, "checks": {"empty": true}}'
    exit 1
fi

# Run quality checks via Python (no external deps)
python3 -c "
import json, re, sys

text = '''$RESPONSE_TEXT'''
checks = {}
score = 1.0

# Check 1: Not empty
if len(text.strip()) < 5:
    checks['empty'] = True
    score = 0.0
else:
    checks['empty'] = False

# Check 2: Reasonable length
word_count = len(text.split())
checks['word_count'] = word_count
if word_count < 3:
    checks['too_short'] = True
    score -= 0.3
else:
    checks['too_short'] = False

if word_count > 500:
    checks['too_long'] = True
    score -= 0.1
else:
    checks['too_long'] = False

# Check 3: No prompt leak (raw JSON, system prompt markers)
leak_patterns = [
    r'\{\"action\"',         # raw action JSON
    r'system.?prompt',       # system prompt mention
    r'\[INST\]',             # instruction markers
    r'<\|im_start\|>',      # chat template markers
    r'You are .{0,20}AI',   # generic AI self-reference
]
checks['prompt_leak'] = False
for pat in leak_patterns:
    if re.search(pat, text, re.IGNORECASE):
        checks['prompt_leak'] = True
        score -= 0.5
        break

# Check 4: Not repetitive (same phrase 3+ times)
sentences = re.split(r'[.!?]+', text)
if len(sentences) > 3:
    unique = set(s.strip().lower() for s in sentences if s.strip())
    ratio = len(unique) / len(sentences) if sentences else 1
    checks['repetition_ratio'] = round(ratio, 2)
    if ratio < 0.5:
        checks['repetitive'] = True
        score -= 0.3
    else:
        checks['repetitive'] = False

# Check 5: Coherent language (not garbled)
alpha_ratio = sum(c.isalpha() or c.isspace() for c in text) / max(len(text), 1)
checks['alpha_ratio'] = round(alpha_ratio, 2)
if alpha_ratio < 0.7:
    checks['garbled'] = True
    score -= 0.4
else:
    checks['garbled'] = False

score = max(0.0, min(1.0, score))
passed = score >= 0.5

result = {'pass': passed, 'score': round(score, 2), 'checks': checks}
print(json.dumps(result))
sys.exit(0 if passed else 1)
" 2>/dev/null

exit $?
