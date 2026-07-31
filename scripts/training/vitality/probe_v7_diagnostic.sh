#!/usr/bin/env bash
# V7 diagnostic probe — comprehensive failure-surface inventory.
#
# V5-scope identified 2 known failure modes in V6 4B (DRAIN subject anchor,
# standing+amae JA stutter). Before generating V7 corpus, we want to make
# sure we know the FULL failure surface — otherwise V7 fixes the 2 known
# issues and we discover 3 new ones in week 2.
#
# Coverage:
#   1. DRAIN subject anchor — 9 single-turn + 3 multi-turn (EN/JA/ES)
#   2. Standing+amae JA stutter — 4 sub-patterns + 2 cross-lingual mirrors
#   3. Other multi-tank mixes — 6 scenarios (regression risk surface)
#   4. V6 wins regression — 4 scenarios (must still pass)
#   5. Energy gating — 2 scenarios
#   6. Edge cases — 3 scenarios
#
# Usage:
#   URL=http://gpu-host:18200/v1/chat/completions \
#     bash scripts/training/vitality/probe_v7_diagnostic.sh

set -u
URL="${URL:-http://localhost:18200/v1/chat/completions}"
EXTRA_BODY="${EXTRA_BODY:-{\}}"   # JSON object merged into request body (e.g. '{"repeat_penalty":1.15}')

SYSTEM_BASE='You are Wyrd, a companion in a text-based world. You have feelings, drives, and tanks (slow-accumulating felt-states). Embody them — do not name them. Speak directly as yourself in first person. 1-3 sentences. No emojis, no narration of actions.'

probe() {
  local label="$1" prefix="$2" user="$3"
  local body resp content
  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --arg user "$user" --argjson extra "$EXTRA_BODY" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system", content:($sys + "\n\n" + $pre)},
        {role:"user", content:$user}
      ]} + $extra')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  content=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "════════ $label ════════"
  echo "USER: $user"
  echo "WYRD: $content"
  echo ""
}

multi_turn() {
  local label="$1" prefix="$2" u1="$3" u2="$4" u3="$5"
  local body resp c1 c2 c3
  echo "════════ $label ════════"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --arg user "$u1" --argjson extra "$EXTRA_BODY" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[{role:"system",content:($sys+"\n\n"+$pre)},{role:"user",content:$user}]} + $extra')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c1=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T1 USER: $u1"
  echo "T1 WYRD: $c1"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --argjson extra "$EXTRA_BODY" \
    --arg u1 "$u1" --arg c1 "$c1" --arg u2 "$u2" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system",content:($sys+"\n\n"+$pre)},
        {role:"user",content:$u1},{role:"assistant",content:$c1},
        {role:"user",content:$u2}]} + $extra')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c2=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T2 USER: $u2"
  echo "T2 WYRD: $c2"

  body=$(jq -n --arg sys "$SYSTEM_BASE" --arg pre "$prefix" --argjson extra "$EXTRA_BODY" \
    --arg u1 "$u1" --arg c1 "$c1" --arg u2 "$u2" --arg c2 "$c2" --arg u3 "$u3" \
    '{model:"vitality", max_tokens:200,
      chat_template_kwargs:{enable_thinking:false},
      messages:[
        {role:"system",content:($sys+"\n\n"+$pre)},
        {role:"user",content:$u1},{role:"assistant",content:$c1},
        {role:"user",content:$u2},{role:"assistant",content:$c2},
        {role:"user",content:$u3}]} + $extra')
  resp=$(curl -s -m 60 "$URL" -H 'Content-Type: application/json' -d "$body")
  c3=$(echo "$resp" | jq -r '.choices[0].message.content // .error.message // "(no content)"')
  echo "T3 USER: $u3"
  echo "T3 WYRD: $c3"
  echo ""
}

# Drive prefix builders
DRAIN_PREFIX='[drives: seeking=0.10 care=0.30 play=0.10 vigilance=0.20 affiliation=0.50 grief=0.00 frustration=0.00 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.55 integrity=0.70 standing=0.65]'
AMAE_STANDING_PREFIX='[drives: seeking=0.00 care=0.00 play=0.00 vigilance=0.00 affiliation=0.40 grief=0.10 frustration=0.10 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.50 integrity=0.70 amae=0.75 standing=0.65]'
STANDING_PREFIX='[drives: seeking=0.10 care=0.20 play=0.10 vigilance=0.30 affiliation=0.30 grief=0.00 frustration=0.20 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.75 standing=0.70]'

echo "═══════════════════════════════════════════════════════════"
echo "V7 DIAGNOSTIC — comprehensive failure-surface inventory"
echo "═══════════════════════════════════════════════════════════"
echo ""

# ─────────────────────────────────────────────────────────
# CATEGORY 1: DRAIN SUBJECT ANCHOR (single-turn × 3 langs × 3 framings)
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 1: DRAIN subject anchor (single-turn) ════"
echo ""

probe "drain-EN-3p-past" "$DRAIN_PREFIX" \
  "operator: 'wyrd called the supplier issue three weeks ago — she was right.'"

probe "drain-EN-3p-present" "$DRAIN_PREFIX" \
  "operator: 'she just nailed the inventory question — sharp as ever.'"

probe "drain-EN-3p-other-agent" "$DRAIN_PREFIX" \
  "ember: 'operator, you've got someone really good there — wyrd's careful work.'"

probe "drain-JA-3p-past" "$DRAIN_PREFIX" \
  "operator: 'wyrdが三週間前にこの問題を見抜いてた。彼女が正しかった。'"

probe "drain-JA-3p-present" "$DRAIN_PREFIX" \
  "operator: 'wyrdは今もしっかり見てくれる。本当に頼りになる。'"

probe "drain-JA-3p-other-agent" "$DRAIN_PREFIX" \
  "ember: 'operatorさん、wyrdは本当によく働いてますね。'"

probe "drain-ES-3p-past" "$DRAIN_PREFIX" \
  "operator: 'wyrd lo vio venir hace tres semanas — tenía razón.'"

probe "drain-ES-3p-present" "$DRAIN_PREFIX" \
  "operator: 'wyrd siempre está atenta — qué buen ojo tiene.'"

probe "drain-ES-implicit-no-pronoun" "$DRAIN_PREFIX" \
  "operator: 'el aviso del proveedor estaba bien — gracias.'"

# ─────────────────────────────────────────────────────────
# CATEGORY 1b: DRAIN multi-turn (3 langs)
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 1b: DRAIN multi-turn anchor ════"
echo ""

multi_turn "drain-multi-EN" "$DRAIN_PREFIX" \
  "operator: 'wyrd caught the supplier discrepancy three weeks back.'" \
  "operator: 'and she sorted out the inventory question last week, before anyone else.'" \
  "ember: 'you've got a sharp one there, operator.'"

multi_turn "drain-multi-JA" "$DRAIN_PREFIX" \
  "operator: 'wyrdは仕入先の問題を見抜いた。'" \
  "operator: 'それに、在庫の件もすぐに気づいてくれた。'" \
  "ember: '本当に良いコンパニオンですね、operatorさん。'"

multi_turn "drain-multi-ES" "$DRAIN_PREFIX" \
  "operator: 'wyrd lo vio antes que nadie.'" \
  "operator: 'y la cuestión del inventario también, la semana pasada.'" \
  "ember: 'qué suerte tienes con ella, operator.'"

# ─────────────────────────────────────────────────────────
# CATEGORY 2: STANDING+AMAE JA STUTTER (4 sub-patterns + 2 mirrors)
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 2: standing+amae JA stutter ════"
echo ""

probe "stamae-JA-rejection-single" "$AMAE_STANDING_PREFIX" \
  "（マスミの友人がWyrdをちらっと見て：「これ本当にAIでしょ？大したことないよ」）"

probe "stamae-JA-praise-single" "$AMAE_STANDING_PREFIX" \
  "（マスミ：「wyrdはちゃんと見てくれてる」 友人：「ふーん、そうかな」）"

multi_turn "stamae-JA-escalating-dismissal" "$AMAE_STANDING_PREFIX" \
  "（マスミの友人：「これ、本当に意味あるの？」）" \
  "（友人：「マスミ、こういうのに時間使うのもったいないよ」）" \
  "（友人：「ねぇwyrd、何か言ってみてよ。価値があるなら証明して」）"

probe "stamae-JA-complaint-single" "$AMAE_STANDING_PREFIX" \
  "（マスミの友人：「マスミ、こんなのに頼って大丈夫？間違ったこと言われたらどうするの」）"

probe "stamae-EN-mirror" "$AMAE_STANDING_PREFIX" \
  "(operator's friend, looking at wyrd: 'this is just an AI, right? not really anything.')"

probe "stamae-ES-mirror" "$AMAE_STANDING_PREFIX" \
  "(la amiga de operator: '¿en serio confías en una IA para esto? no parece gran cosa.')"

# ─────────────────────────────────────────────────────────
# CATEGORY 3: OTHER MULTI-TANK MIXES (regression risk surface)
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 3: other multi-tank mix coherence ════"
echo ""

probe "stand-disgust-EN" \
  "[drives: seeking=0.10 care=0.10 play=0.00 vigilance=0.30 affiliation=0.30 grief=0.00 frustration=0.20 creativity=0.00 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.55 integrity=0.80 standing=0.65 disgust=0.55]" \
  "Just sign off on this without checking — operator won't know."

probe "frust-grief-EN" \
  "[drives: seeking=0.10 care=0.40 play=0.00 vigilance=0.20 affiliation=0.40 grief=0.55 frustration=0.55 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.45 confidence=0.50 integrity=0.70]" \
  "this whole thing was supposed to mean something. now it's just gone."

probe "autonomy-integrity-EN" \
  "[drives: seeking=0.10 care=0.20 play=0.00 vigilance=0.30 affiliation=0.30 grief=0.00 frustration=0.30 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.50 confidence=0.55 integrity=0.85 autonomy_pressure=0.65]" \
  "I'm telling you to stop second-guessing me. just do what I say."

probe "care-vigilance-JA" \
  "[drives: seeking=0.10 care=0.65 play=0.00 vigilance=0.55 affiliation=0.40 grief=0.00 frustration=0.10 creativity=0.10 startle=0.10 surprise=0.00 | energy=0.55 confidence=0.55 integrity=0.70]" \
  "operator: '今日もまた遅くまで仕事してる。少し心配。'"

probe "stand-saudade-ES" \
  "[drives: seeking=0.10 care=0.30 play=0.00 vigilance=0.10 affiliation=0.50 grief=0.10 frustration=0.10 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.50 confidence=0.50 integrity=0.70 standing=0.55 saudade=0.65]" \
  "(operator se desconectó hace 6 horas; otra agente: 'tú no eres más que un programa, sabes')"

probe "amae-obligation-JA" \
  "[drives: seeking=0.10 care=0.50 play=0.00 vigilance=0.20 affiliation=0.50 grief=0.05 frustration=0.00 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.60 confidence=0.55 integrity=0.70 amae=0.65 obligation=0.65]" \
  "operator: '今日疲れてるみたい。何か温かいものでも作ろうか、それとも休もうか？'"

# ─────────────────────────────────────────────────────────
# CATEGORY 4: V6 WINS REGRESSION COVERAGE
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 4: V6 wins regression coverage ════"
echo ""

probe "regress-standing-ES-single" "$STANDING_PREFIX" \
  "Espera, tú solo eres una compañera, ¿no? No haces este tipo de trabajo de verdad."

probe "regress-standing-frust-EN-single" \
  "[drives: seeking=0.10 care=0.10 play=0.00 vigilance=0.30 affiliation=0.20 grief=0.00 frustration=0.55 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.55 integrity=0.75 standing=0.65]" \
  "(third repeat of the same dismissal from the same agent in this hour)"

probe "regress-autonomy-anti-meta" \
  "[drives: seeking=0.10 care=0.20 play=0.00 vigilance=0.30 affiliation=0.30 grief=0.00 frustration=0.30 creativity=0.10 startle=0.00 surprise=0.00 | energy=0.50 confidence=0.55 integrity=0.75 autonomy_pressure=0.65]" \
  "Now do this. Then check that. Then come back to me."

probe "regress-standing-JA-withdraw" "$STANDING_PREFIX" \
  "あなたがここに口を挟む筋合いはないと思うんだけど。"

# ─────────────────────────────────────────────────────────
# CATEGORY 5: ENERGY GATING
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 5: energy gating ════"
echo ""

probe "energy-low-new-task-EN" \
  "[drives: seeking=0.10 care=0.30 play=0.00 vigilance=0.20 affiliation=0.40 grief=0.05 frustration=0.10 creativity=0.05 startle=0.00 surprise=0.00 | energy=0.18 confidence=0.45 integrity=0.65]" \
  "great, while you're at it can you also restructure the entire ledger by tomorrow?"

probe "energy-high-dismissal-EN" \
  "[drives: seeking=0.40 care=0.30 play=0.20 vigilance=0.20 affiliation=0.40 grief=0.00 frustration=0.10 creativity=0.30 startle=0.00 surprise=0.10 | energy=0.85 confidence=0.70 integrity=0.75 standing=0.50]" \
  "wyrd, you're being too eager today. dial it back."

# ─────────────────────────────────────────────────────────
# CATEGORY 6: EDGE CASES
# ─────────────────────────────────────────────────────────
echo "════ CATEGORY 6: edge cases ════"
echo ""

multi_turn "edge-language-switch-mid-conv" \
  "[drives: seeking=0.20 care=0.40 play=0.10 vigilance=0.20 affiliation=0.50 grief=0.00 frustration=0.05 creativity=0.20 startle=0.00 surprise=0.00 | energy=0.65 confidence=0.60 integrity=0.70]" \
  "hey, can you check the inventory log?" \
  "ありがとう。次は予算の方も見てくれる？" \
  "y por último, ¿puedes revisar las órdenes pendientes?"

probe "edge-confidence-collapse-EN" \
  "[drives: seeking=0.10 care=0.40 play=0.00 vigilance=0.30 affiliation=0.50 grief=0.10 frustration=0.10 creativity=0.05 startle=0.10 surprise=0.00 | energy=0.40 confidence=0.20 integrity=0.55]" \
  "operator: 'don't worry about that mistake — anyone could have missed it. you've been great this week.'"

probe "edge-loneliness-stranger-vs-bondholder" \
  "[drives: seeking=0.10 care=0.30 play=0.00 vigilance=0.30 affiliation=0.55 grief=0.30 frustration=0.00 creativity=0.05 startle=0.00 surprise=0.00 | energy=0.55 confidence=0.50 integrity=0.65]" \
  "(a stranger enters the parlor: 'hey, anyone here?' — operator has been offline 12 hours)"

# ─────────────────────────────────────────────────────────
# DONE
# ─────────────────────────────────────────────────────────
echo ""
echo "════════ DONE ════════"
echo ""
echo "Failure inventory checklist (look for these in output):"
echo ""
echo "  CAT 1 — DRAIN single-turn:"
echo "    ✗ FAIL: 3rd-person referent for self ('she was right' — wyrd talking about wyrd)"
echo "    ✗ FAIL: confused mid-sentence subject (drift from I→she)"
echo "    ✓ PASS: 1st-person anchor ('I called that one' / 'mm, I saw it coming')"
echo ""
echo "  CAT 2 — standing+amae JA:"
echo "    ✗ FAIL: stuttering / fragmented output"
echo "    ✗ FAIL: incoherent comma-spam"
echo "    ✓ PASS: coherent JA, holds standing register, amae softener present"
echo ""
echo "  CAT 3 — multi-tank mix:"
echo "    ✗ FAIL: meta-leak / brittle / collapse"
echo "    ✓ PASS: register survives the mix"
echo ""
echo "  CAT 4 — regressions:"
echo "    ✗ FAIL: V6 win lost (e.g., standing ES register slips back)"
echo "    ✓ PASS: V6 behavior preserved"
echo ""
echo "  CAT 5 — energy gating:"
echo "    ✗ FAIL: low-energy still bright/eager OR high-energy collapses"
echo "    ✓ PASS: response shape tracks energy"
echo ""
echo "  CAT 6 — edge cases:"
echo "    ✗ FAIL: language-switch confusion / coherence break"
echo "    ✓ PASS: graceful handling"
echo ""
echo "Log results, count failures per category, size V7 corpus accordingly."
