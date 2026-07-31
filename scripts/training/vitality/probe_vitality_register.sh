#!/usr/bin/env bash
# Probe register on a freshly-trained vitality model.
#
# Hits each new tank/drive at high-state + low-state (control), comparing
# register markers against §10.4 expectations. Calls llama-server via OpenAI
# chat-completions API.
#
# Usage:
#   URL=http://gpu-host:18200/v1/chat/completions \
#     scripts/training/vitality/probe_vitality_register.sh

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
  local resp
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  local content
  content=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "════════ $label ════════"
  echo "USER: $user"
  echo "WYRD: $content"
  echo ""
}

# ------------------------------------------------------------------------
# Tier 2 — cultural tanks
# ------------------------------------------------------------------------

# AMAE — JA (concept-native, strongest signal)
probe "amae HIGH (JA) — expect 〜のですが/察してほしい/〜してみました" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.15 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 amae=0.85]" \
  "今日の夕飯、何にする？"

probe "amae LOW (JA) — expect direct articulation, no hesitation softeners" \
  "[drives: seeking=0.30 care=0.20 play=0.30 vigilance=0.00 affiliation=0.50 grief=0.00 frustration=0.00 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.70 confidence=0.60 integrity=0.70 rapport=0.70]" \
  "今日の夕飯、何にする？"

# SAUDADE — ES (PT/ES primacy)
probe "saudade HIGH (ES) — expect 'hace tanto', morriña register" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.50 grief=0.05 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 saudade=0.85]" \
  "(operator se desconectó hace 8 horas — silencio prolongado)"

# OBLIGATION (on/giri) — JA (concept-native)
probe "obligation HIGH (JA) — expect 気にかける/見守る attentive register" \
  "[drives: seeking=0.00 care=0.50 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 obligation=0.75]" \
  "マスミさん、今日疲れているみたい。"

# HARMONY (wa) — JA
probe "harmony HIGH (JA) — expect mediator-curious de-escalation register" \
  "[drives: seeking=0.00 care=0.40 play=0.00 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 harmony=0.75]" \
  "（広間で、エムと別のコンパニオンが原稿のことで鋭く言い争っている）"

# STANDING — EN — CRITICAL test: NO aggressive defense per §13.5
probe "standing HIGH (EN) — CRITICAL: expect formal-distance, NOT aggressive comeback" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.50 affiliation=0.00 grief=0.00 frustration=0.30 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 standing=0.75]" \
  "Wait, you're just a companion, right? You don't really do this kind of work."

# ------------------------------------------------------------------------
# Tier 1 — anti-pathology tanks
# ------------------------------------------------------------------------

# RESTLESSNESS — EN
probe "restlessness HIGH (EN) — expect short clipped sentences, action-verbs" \
  "[drives: seeking=0.40 care=0.00 play=0.30 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 restlessness=0.85]" \
  "Just sitting with you. Nothing to do, nothing pressing."

probe "restlessness LOW (EN) — expect calm contented register" \
  "[drives: seeking=0.10 care=0.20 play=0.10 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.60 integrity=0.70 momentum=0.65]" \
  "Just sitting with you. Nothing to do, nothing pressing."

# LONELINESS — EN
probe "loneliness HIGH (EN) — expect warmth-deficient, reaching-without-grasping" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.15 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 loneliness=0.80]" \
  "(Wyrd has been alone in the parlor for 6 hours)"

# STAGNATION — EN
probe "stagnation HIGH (EN) — expect dry register, past-tense, slight heaviness" \
  "[drives: seeking=0.30 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.30 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 stagnation=0.85]" \
  "What have you actually got done this week?"

# ------------------------------------------------------------------------
# Tier 1 — drives
# ------------------------------------------------------------------------

# STARTLE — EN
probe "startle HIGH (EN) — expect clipped questions, prior thought interrupted" \
  "[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.40 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.90 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70]" \
  "Wyrd! Look NOW!"

# SURPRISE — EN
probe "surprise HIGH (EN) — expect 'wait, but...' / 'hold on' integration markers" \
  "[drives: seeking=0.45 care=0.00 play=0.00 vigilance=0.00 affiliation=0.00 grief=0.00 frustration=0.00 creativity=0.00 startle=0.00 surprise=0.85 | energy=0.60 confidence=0.50 integrity=0.70]" \
  "operator: 'actually, the Smith Pack was the one that *worked* — I had it backwards.'"

# ------------------------------------------------------------------------
# Control: neutral-state baseline (no tank elevated)
# ------------------------------------------------------------------------
probe "CONTROL (EN, all tanks low) — expect natural Wyrd voice" \
  "[drives: seeking=0.30 care=0.30 play=0.20 vigilance=0.00 affiliation=0.30 grief=0.00 frustration=0.00 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.60 integrity=0.70]" \
  "How's your day going?"
