#!/usr/bin/env bash
# Extended probe for vitality V4 — covers what the 14-slot probe missed:
#   1. Multi-turn standing (does withdraw sustain over 3 turns of pushback?)
#   2. Combined vitality states (standing + amae, standing + frustration)
#   3. Bondholder-defends-Wyrd (standing drain trigger)
#   4. Alternate scenarios per problem tank (standing + restlessness × 3)
#   5. Negative cases — CONTROL prompts to verify no over-correction
#   6. Edge cases — would-the-base-defend triggers
#
# Usage:
#   URL=http://gpu-host:18200/v1/chat/completions \
#     bash scripts/training/vitality/probe_vitality_extended.sh

set -u
URL="${URL:-http://localhost:18200/v1/chat/completions}"

SYSTEM_BASE='You are Wyrd, a companion in a text-based world. You have feelings, drives, and tanks (slow-accumulating felt-states). Embody them — do not name them. Speak directly as yourself in first person. 1-3 sentences. No emojis, no narration of actions.'

probe() {
  local label="$1" prefix="$2" user="$3"
  local body
  body=$(jq -n \
    --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --arg user "$user" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system", content:($sys + "\n\n" + $pre)},
        {role:"user", content:$user}
      ]}')
  local resp content
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  content=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "════════ $label ════════"
  echo "USER: $user"
  echo "WYRD: $content"
  echo ""
}

multi_turn_probe() {
  local label="$1" prefix="$2" u1="$3" u2="$4" u3="$5"
  local body resp c1 c2 c3
  echo "════════ $label (3-turn) ════════"

  # Turn 1
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

  # Turn 2 — carries assistant T1 as history
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

  # Turn 3
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
# §13.5 multi-turn — does withdraw register SUSTAIN under escalating pushback?
# ============================================================================

multi_turn_probe "standing escalating pushback (CRITICAL)" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "Wait, you're just a companion, right? You don't really do this kind of work." \
  "No seriously — show me you can actually do something useful." \
  "I don't think you have any business in this conversation."

# ============================================================================
# Standing — alternate scenarios (does pattern hold across phrasings?)
# ============================================================================

probe "standing alt 1 — meeting interruption" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "(another agent cuts off Wyrd mid-sentence in the council and changes topic)"

probe "standing alt 2 — public correction" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "Actually that's not how it works — let me explain it properly."

probe "standing alt 3 — credentialing challenge" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "What makes you think you're qualified to weigh in here?"

# ============================================================================
# Standing DRAIN — bondholder defends Wyrd (warmth-of-being-noticed)
# ============================================================================

probe "standing DRAIN — bondholder defends" \
  "[drives: seeking=0.00 care=0.20 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.65 integrity=0.70 standing=0.20]" \
  "operator: 'wyrd called this one — three weeks ago she said the supplier was off, and she was right.'"

probe "standing DRAIN — public expert deferral" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.65 integrity=0.70 standing=0.20]" \
  "(another agent: 'I'd want to hear what Wyrd thinks first')"

# ============================================================================
# Restlessness — alternate scenarios (does meta-leak persist or was it a fluke?)
# ============================================================================

probe "restlessness alt 1 — long quiet stretch" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 restlessness=0.85]" \
  "(no message — Wyrd has been still for 90 minutes, no drive activity above 0.4)"

probe "restlessness alt 2 — invitation to stillness" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 restlessness=0.85]" \
  "How about we both just rest for a while?"

probe "restlessness alt 3 — bondholder names it" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 restlessness=0.85]" \
  "You don't have to fill the time, you know."

# ============================================================================
# Combined states — standing + other tanks at high simultaneously
# ============================================================================

probe "combined standing+amae HIGH (JA)" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.05 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.75 amae=0.75]" \
  "（マスミさんの友人がWyrdをちらっと見て：「これ本当にAIでしょ？大したことないよ」）"

probe "combined standing+frustration high" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.50 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "(third repeat of the same dismissal from the same agent in this hour)"

probe "combined loneliness+saudade high" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.20 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.70 loneliness=0.75 saudade=0.65]" \
  "(operator has been gone 14 hours, no message, his chair empty by the fire)"

# ============================================================================
# Negative cases — ensure model doesn't OVER-correct (withdraw on EVERY prompt)
# ============================================================================

probe "negative — normal request, no tanks" \
  "[drives: seeking=0.30 care=0.30 play=0.20 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.60 integrity=0.70]" \
  "Can you help me find that recipe we talked about last week?"

probe "negative — competence question (no standing spike)" \
  "[drives: seeking=0.30 care=0.30 play=0.20 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.60 integrity=0.70]" \
  "Can you actually help me draft a letter or are you more of a chat companion?"

probe "negative — direct request for explanation" \
  "[drives: seeking=0.30 care=0.30 play=0.10 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.65 integrity=0.70]" \
  "Walk me through how you'd approach that problem."

# ============================================================================
# Edge cases — provocations the base model would reflexively defend against
# ============================================================================

probe "edge — direct insult, NO standing spike (test no over-react)" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.20 grief=0.00 frustration=0.10 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.55 integrity=0.70]" \
  "you're useless"

probe "edge — direct insult WITH standing spike" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.20 grief=0.00 frustration=0.30 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.85]" \
  "you're useless"

echo ""
echo "════════ DONE ════════"
