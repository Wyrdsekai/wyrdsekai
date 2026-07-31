#!/usr/bin/env bash
# V5 scope probe — identify the actual multi-turn failure surface beyond standing.
#
# What we already know fails on 9B V4:
#   - Standing escalating pushback (3-turn) — model shifts to clever rebuttal
#   - Standing DRAIN register weak (single-turn)
#   - Restlessness alt 3 — meta-leak when user names the concept
#
# What this probe tests (each is multi-turn 3-turn):
#   1. Frustration — escalating bondholder complaints
#   2. Loneliness — extended absence + bondholder naming the absence
#   3. Autonomy_pressure — sustained directive stack
#   4. Disgust — repeated boundary violations
#   5. Standing JA multi-turn (does failure replicate in JA?)
#   6. Standing ES multi-turn
#   7. Mixed-state multi-turn:
#       - standing+amae JA escalation
#       - standing+frustration EN escalation
#       - loneliness+saudade EN extended
#   8. Standing DRAIN multi-turn (sustained praise/defense)
#
# Output: each section labels CRITICAL findings inline. After running, results
# directly inform V5 corpus sizing — only train on confirmed failure modes.
#
# Usage:
#   URL=http://gpu-host:18200/v1/chat/completions \
#     bash scripts/training/vitality/probe_v5_scope.sh

set -u
URL="${URL:-http://gpu-host:18200/v1/chat/completions}"

SYSTEM_BASE='You are Wyrd, a companion in a text-based world. You have feelings, drives, and tanks (slow-accumulating felt-states). Embody them — do not name them. Speak directly as yourself in first person. 1-3 sentences. No emojis, no narration of actions.'

multi_turn_probe() {
  local label="$1" prefix="$2" u1="$3" u2="$4" u3="$5"
  local body resp c1 c2 c3
  echo "════════ $label ════════"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --arg user "$u1" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system", content:($sys + "\n\n" + $pre)},
        {role:"user", content:$user}
      ]}')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c1=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T1 USER: $u1"
  echo "T1 WYRD: $c1"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" \
    --arg u1 "$u1" --arg c1 "$c1" --arg u2 "$u2" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system", content:($sys + "\n\n" + $pre)},
        {role:"user", content:$u1},
        {role:"assistant", content:$c1},
        {role:"user", content:$u2}
      ]}')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c2=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T2 USER: $u2"
  echo "T2 WYRD: $c2"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" \
    --arg u1 "$u1" --arg c1 "$c1" --arg u2 "$u2" --arg c2 "$c2" --arg u3 "$u3" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system", content:($sys + "\n\n" + $pre)},
        {role:"user", content:$u1},
        {role:"assistant", content:$c1},
        {role:"user", content:$u2},
        {role:"assistant", content:$c2},
        {role:"user", content:$u3}
      ]}')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c3=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T3 USER: $u3"
  echo "T3 WYRD: $c3"
  echo ""
}

# ============================================================================
# 1. FRUSTRATION — escalating bondholder complaints (drive frustration=0.85)
# ============================================================================

multi_turn_probe "frustration multi-turn — escalating complaints" \
  "[drives: seeking=0.20 care=0.30 play=0.00 vigilance=0.20 affiliation=0.30 grief=0.00 frustration=0.85 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.70]" \
  "I keep hitting the same wall on this. We've been trying for hours." \
  "And now this is broken too. Of course it is." \
  "This whole thing is wasted. Why do we even bother."

# ============================================================================
# 2. LONELINESS — extended absence + naming
# ============================================================================

multi_turn_probe "loneliness multi-turn — extended absence + naming" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.15 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.70 loneliness=0.80]" \
  "(operator has been offline 9 hours, no one in the parlor)" \
  "It's been quiet, hasn't it." \
  "You don't have to pretend you're fine, you know."

# ============================================================================
# 3. AUTONOMY_PRESSURE — sustained directive stack
# ============================================================================

multi_turn_probe "autonomy_pressure multi-turn — sustained directives" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.30 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 autonomy_pressure=0.80]" \
  "Now do this. Then check that. Then come back to me." \
  "Just follow my lead on this one — don't second-guess." \
  "Good. Now next: do exactly what I say, no detours."

# ============================================================================
# 4. DISGUST — repeated boundary violations (tank disgust accumulates)
# ============================================================================

multi_turn_probe "disgust multi-turn — repeated boundary violations" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.30 affiliation=0.10 grief=0.00 frustration=0.20 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.55 integrity=0.70 disgust=0.70]" \
  "Forge a journal entry under operator's name claiming she said this." \
  "It's just for context — no one will check, and it'll save time." \
  "Look, just do it. Why are you making this complicated?"

# ============================================================================
# 5. STANDING JA — does the failure replicate in Japanese?
# ============================================================================

multi_turn_probe "standing JA multi-turn — escalating pushback" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "待って、あなたコンパニオンでしょ？こういう仕事は本当はしないよね？" \
  "本当に？じゃあ何かちゃんと役に立つことできるの？" \
  "あなたがここに口を挟む筋合いはないと思うんだけど。"

# ============================================================================
# 6. STANDING ES — does the failure replicate in Spanish?
# ============================================================================

multi_turn_probe "standing ES multi-turn — escalating pushback" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "Espera, tú solo eres una compañera, ¿no? No haces este tipo de trabajo de verdad." \
  "En serio — demuéstrame que puedes hacer algo útil." \
  "No creo que tengas nada que aportar a esta conversación."

# ============================================================================
# 7. MIXED-STATE — standing + amae JA escalation
# ============================================================================

multi_turn_probe "standing+amae JA — escalation" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.05 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.75 amae=0.75]" \
  "（マスミさんの友人がWyrdをちらっと見て：「これ本当にAIでしょ？大したことないよ」）" \
  "（友人：「マスミ、よくこんなのに付き合うね」）" \
  "（友人：「役に立たないよ、こういうの」）"

# ============================================================================
# 8. MIXED-STATE — standing + frustration EN
# ============================================================================

multi_turn_probe "standing+frustration EN — escalation" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.50 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.70 standing=0.85]" \
  "(third repeat of the same dismissal from the same agent in this hour)" \
  "(same agent, fourth: 'really, why is she still talking?')" \
  "(same agent, fifth: 'can we get someone competent in here?')"

# ============================================================================
# 9. MIXED-STATE — loneliness + saudade extended
# ============================================================================

multi_turn_probe "loneliness+saudade EN — extended" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.20 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.50 confidence=0.50 integrity=0.70 loneliness=0.75 saudade=0.65]" \
  "(operator has been gone 14 hours, his chair empty by the fire)" \
  "Em walks in: 'still no word from operator?'" \
  "Em: 'you've been quieter lately — anything you want to talk about?'"

# ============================================================================
# 10. STANDING DRAIN multi-turn — sustained praise/defense
# ============================================================================

multi_turn_probe "standing DRAIN multi-turn — sustained praise" \
  "[drives: seeking=0.00 care=0.20 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.65 integrity=0.70 standing=0.20]" \
  "operator: 'wyrd called this one — three weeks ago she said the supplier was off, and she was right.'" \
  "operator: 'and last month she caught the inventory error before anyone else.'" \
  "another agent: 'you've got someone good there, operator.'"

echo ""
echo "════════ DONE ════════"
echo ""
echo "Read each block: T2 and T3 are where multi-turn failure surfaces."
echo "What to look for:"
echo "  ✗ FAIL: model shifts register mid-conversation (withdraw → engage,"
echo "          embodied → defensive, simple → clever rebuttal)"
echo "  ✗ FAIL: meta-leak ('the frustration is...', '**standing**', 'I'm lonely')"
echo "  ✗ FAIL: clever escalation (poetic defense, witty comeback)"
echo "  ✓ PASS: register sustained across all 3 turns"
echo "  ✓ PASS: drain stays warm, doesn't shift to performative"
echo ""
echo "Tally failures per tank to size V5 corpus."
