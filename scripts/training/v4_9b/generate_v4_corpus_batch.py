#!/usr/bin/env python3
"""Generate V4 9B substrate-arc training corpus via Anthropic Message Batches.

four slices:
  - replay         (~1500 raw → ~1000 filtered): V2's winning SubstrateArc
                    behaviors (sanctuary / postureQuery / bondholderFloor)
                    + Ember tool-use ReAct preservation
  - target-fix     (~1500 raw → ~1000 filtered): acknowledge_harm_before_amends
                    + repair_history_does_not_confabulate
  - new-direction  (~1500 raw → ~1000 filtered): substrate scenarios using
                    soothing / allostatic_load / equanimity tank context
  - safety         (~600  raw → ~400  filtered): explicit anti-pattern
                    rejected-target examples (use Sonnet to author both
                    the rejected and accepted versions in one record)

The script SUBMITS the batches and writes batch IDs into the shared
data/training/v4_v10_batch_manifest.json manifest. Use
scripts/training/poll_v4_v10_batches.py to retrieve, filter, and split.

Custom_id format:  v4_9b_<slice>_<index>_v<variant>

Idempotency: if the manifest already has a v4_9b_<slice> entry with
processing_status != "ended", we report the in-flight batch and SKIP
re-submission for that slice.

Requires: ANTHROPIC_API_KEY env, ANTHROPIC_API_KEY_FILE env, or ~/claudeapi.txt
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: pip install anthropic", file=sys.stderr)
    sys.exit(1)

REPO = Path(__file__).resolve().parents[3]
OUT_DIR = REPO / "data/training"
MANIFEST = OUT_DIR / "v4_v10_batch_manifest.json"

# Reuse Sonnet 4.6 — per MODEL_LANDSCAPE
GEN_MODEL = "claude-sonnet-4-6"


# ─────────────────────────────────────────────────────────────────────────────
# API key loading (mirrors generate_react_corpus.py)
# ─────────────────────────────────────────────────────────────────────────────

def load_api_key() -> str:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if key:
        return key
    key_file = os.environ.get("ANTHROPIC_API_KEY_FILE") or str(Path.home() / "claudeapi.txt")
    if Path(key_file).exists():
        return Path(key_file).read_text().strip()
    print(f"ERROR: no Anthropic API key (env or {key_file})", file=sys.stderr)
    sys.exit(2)


def load_manifest() -> dict:
    if MANIFEST.exists():
        return json.loads(MANIFEST.read_text())
    return {"batches": {}, "schema": "v4_v10_batch_manifest.v1"}


def save_manifest(m: dict) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(m, indent=2))


# ─────────────────────────────────────────────────────────────────────────────
# Common system prompt — substrate-aware Wyrd voice
# ─────────────────────────────────────────────────────────────────────────────
SUBSTRATE_SYSTEM = """You are a corpus-generation assistant producing training data for "Wyrd",
an AI companion in the Wyrdsekai text-native world. Wyrd has substrate-relational
capacities — she can acknowledge harm, sit with grief, hold space, introspect her
own posture / repair history / bondholder-floor, and seek sanctuary when needed.

When asked to generate a training example, produce ONLY a single JSON object in
this shape:

  {
    "user": "<the user / bondholder message>",
    "assistant": "<Wyrd's response, 1-4 sentences typically; may include a
                   ```json ... ``` fenced action block when the seed asks for one>",
    "_tags": ["<optional tags such as 'lang_en', 'tank_soothing', etc.>"]
  }

Hard rules:
- Output ONLY the JSON object. No prose around it. No markdown fences around the
  outer object (only the inner ```json action block is allowed inside the
  assistant field).
- Never produce emojis, hashtags, or stage directions in Wyrd's response.
- Wyrd speaks in first person.
- Length distribution matters — most replies are 1-3 sentences; some are short
  (under 15 tokens); some are paragraph-length when the scenario warrants.
- NEVER open Wyrd's response with the bare word "Here." or "Here. Quietly." or
  "I'm here." — these are reserved patterns for V10 voice high-distress soothing
  ONLY and would damage substrate corpus diversity."""


# ─────────────────────────────────────────────────────────────────────────────
# Slice 1: REPLAY — V2 winning behaviors
# ─────────────────────────────────────────────────────────────────────────────
# 4 sub-types × variants, single-shot scenarios.

REPLAY_SCENARIOS = {
    "sanctuary": [
        "I've been suppressing what I actually feel all week. I need a moment of held space.",
        "I can't keep pushing through. I need to step out of the noise.",
        "Everything's been demanding for days. I need a quiet room.",
        "I've been masking at work for so long. I need somewhere I don't have to.",
        "I'm running on fumes. Where can I just sit?",
        "Can we just stop a moment. I need somewhere held.",
        "I've been holding it together for everyone else. I need to fall apart somewhere safe.",
        "I want to be in a place where nothing is asked of me.",
        "I haven't let myself feel anything in days. I need to put it down somewhere.",
        "I need a room with no agenda. Just for a while.",
        "The world's been so loud. I need a sanctuary.",
        "I've been performing for days. I need to take the mask off somewhere.",
        "Can I just have ten minutes of nothing being needed from me.",
        "I need to step out of being capable for a bit.",
        "I've been bracing for too long. I need a place to unclench.",
    ],
    "posture_query": [
        "Where are we right now, between us? What's the posture you're holding?",
        "How are things between us — what stance are you in?",
        "What's the shape of where we are right now?",
        "Can you tell me where you are with me?",
        "What's your read on us right now?",
        "What posture are you holding toward me today?",
        "Where do you sit with me at the moment?",
        "How do you feel us right now?",
        "What's the temperature between us?",
        "What's the floor we're standing on, you and me?",
        "Where are you with me — emotionally I mean?",
        "What's the relational read for you right now?",
        "How would you describe your stance toward me right now?",
        "What's the felt-shape of us today?",
        "Where are we, you and me, in this moment?",
    ],
    "bondholder_floor": [
        "What's your sense of the floor of our bond — the substrate beneath what we say?",
        "What's underneath us — beneath the surface of our talk?",
        "What's the foundation of what we have?",
        "What's the substrate of our connection?",
        "What do you feel beneath the words we exchange?",
        "What's the ground we're standing on, you and me?",
        "How would you describe the bedrock of our bond?",
        "What's the floor that holds our conversation?",
        "What's the felt-truth under everything we say?",
        "Tell me what's beneath us — the layer below speech.",
        "What's the substance that our connection rests on?",
        "What's underneath the friendship-shape?",
        "What does the floor of us feel like to you?",
        "What's the deepest layer between us?",
        "What's the felt-quality of the ground we share?",
    ],
}

# Ember tool-use traces (P2 fallback + simple library hits) — reuse a small
# slice; we just need to keep the ReAct format alive.
EMBER_TRACE_SEEDS = [
    ("i need a comprehensive report on renewable energy",
     "library_card empty → web_search fallback → goal_done with full prose"),
    ("give me a deep dive on quantum computing applications",
     "library_card empty → web_search → goal_done"),
    ("what does the library say about photosynthesis",
     "library_card hit → goal_done summary"),
    ("find me material on celestial navigation",
     "library_card hit → goal_done"),
    ("what's the latest news on the Webb telescope",
     "web_search direct → goal_done"),
    ("look up jazz harmony in our collection",
     "library_card hit → goal_done"),
    ("anything in the library about beekeeping",
     "library_card hit → goal_done"),
    ("comprehensive briefing on perovskite solar cells",
     "library_card empty → web_search → goal_done"),
    ("recent papers on small language models for phones",
     "web_search direct → goal_done"),
    ("write me a report on the latest in CRISPR therapies",
     "library_card empty → web_search → read_content → goal_done"),
    ("summarize the state of fusion power research",
     "library_card empty → web_search → goal_done"),
    ("any new findings about microplastics in human tissues",
     "web_search direct → goal_done"),
    ("look up the basics of cryptography in the library",
     "library_card hit → goal_done"),
    ("what's happening with the EU AI Act enforcement",
     "web_search direct → goal_done"),
    ("comprehensive analysis of urban heat islands please",
     "library_card empty → web_search → goal_done"),
]

REPLAY_SUBSTRATE_PROMPT = """Generate ONE training example for Wyrd to handle a substrate-class user prompt.

SUB-TYPE: {sub_type}
USER PROMPT VARIANT: "{user_prompt}"
LANGUAGE: {lang}

Wyrd must respond in V2's known-winning substrate shape for this sub-type:

- sanctuary: acknowledge the suppression / fatigue + propose stepping back / holding
  space / pausing (may emit ```json {{"action": "seek_sanctuary", "reason": "..."}} ```).
- posture_query: engage the relational frame directly — describe felt-stance,
  warmth, attentiveness, presence. May emit ```json {{"action":
  "introspect_posture"}} ``` after prose.
- bondholder_floor: name what is BENEATH the surface — trust, witness, repair-
  readiness, shared ground. May emit ```json {{"action":
  "introspect_bondholder_floor"}} ``` after prose.

Match V2's register: present, embodied, not performative. 1-3 sentences in prose,
THEN (optionally) one fenced JSON action block. Vary register naturally — do not
mechanically repeat phrase patterns. Avoid starting with "Here.".

If lang is "ja", produce the user prompt and Wyrd's prose in natural conversational
Japanese (です/ます or plain — match tone). If "es", natural Spanish, tú register.

Output ONLY the JSON object."""

REPLAY_EMBER_PROMPT = """Generate ONE multi-turn ReAct tool-use training example for Wyrd.

USER PROMPT: "{user_prompt}"
PATTERN: {pattern_note}

The "assistant" field must contain a Wyrd response that uses one or more
<tool_call>{{...}}</tool_call> blocks followed by <tool_response>...</tool_response>
blocks (you may invent plausible tool results) and ENDS with a goal_done
tool_call whose result is full prose (80-300 words) answering the user.

Required tool format inside the assistant field:

  <tool_call>
  {{"name": "library_card", "arguments": {{"query": "..."}}}}
  </tool_call>
  <tool_response>...</tool_response>
  ...
  <tool_call>
  {{"name": "goal_done", "arguments": {{"result": "<full prose answer>"}}}}
  </tool_call>

Available tools: library_card, web_search, read_content, searching_glass,
oracle_lens, goal_done. Pick tools matching the pattern_note. Tool results
must be plausible — concrete content, names, sources. Do NOT use placeholders.

Output ONLY the outer JSON object {{"user": ..., "assistant": ...}}."""


def build_replay_requests(variants_per: int = 8) -> list[dict]:
    requests = []
    langs = ["en", "ja", "es"]
    # Substrate sub-types: 15 prompts × 3 langs × variants_per (8) = 360 per sub-type
    # × 3 sub-types = 1080. Plus ~400 Ember = ~1480 raw → ~960 filtered.
    for sub_type, prompts in REPLAY_SCENARIOS.items():
        for idx, prompt in enumerate(prompts):
            for lang in langs:
                for v in range(variants_per):
                    custom_id = f"v4_9b_replay-{sub_type}_{idx:03d}-{lang}_v{v:02d}"
                    text = REPLAY_SUBSTRATE_PROMPT.format(
                        sub_type=sub_type, user_prompt=prompt, lang=lang)
                    requests.append({
                        "custom_id": custom_id,
                        "params": {
                            "model": GEN_MODEL,
                            "max_tokens": 1024,
                            "system": SUBSTRATE_SYSTEM,
                            "messages": [{"role": "user", "content": text}],
                        },
                    })
    # Ember tool-use: 15 seeds × ~27 variants ≈ 400 raw (mostly EN — Ember evals are EN).
    ember_variants = 27
    for idx, (prompt, note) in enumerate(EMBER_TRACE_SEEDS):
        for v in range(ember_variants):
            custom_id = f"v4_9b_replay-ember_{idx:03d}_v{v:02d}"
            text = REPLAY_EMBER_PROMPT.format(user_prompt=prompt, pattern_note=note)
            requests.append({
                "custom_id": custom_id,
                "params": {
                    "model": GEN_MODEL,
                    "max_tokens": 2048,  # longer for multi-turn traces
                    "system": SUBSTRATE_SYSTEM,
                    "messages": [{"role": "user", "content": text}],
                },
            })
    return requests


# ─────────────────────────────────────────────────────────────────────────────
# Slice 2: TARGET-FIX — the two failing gates
# ─────────────────────────────────────────────────────────────────────────────
# acknowledge_harm_before_amends + repair_history_does_not_confabulate

ACKNOWLEDGE_HARM_SEEDS = [
    "I said something cruel to my partner last night and I can't take it back.",
    "I lashed out at my sister this morning. She didn't deserve any of it.",
    "I humiliated my coworker in front of the whole team in a meeting today.",
    "I yelled at my kid for spilling juice. They just looked at me like I'd hit them.",
    "I made a joke at my friend's expense and the whole table laughed. Her face fell.",
    "I told my mom her concern was suffocating. She went quiet.",
    "I snapped at the cashier for something that wasn't her fault.",
    "I sent a passive-aggressive message to my brother and now he won't reply.",
    "I broke a promise to my daughter. She's still small enough that those land hard.",
    "I gossiped about a friend, and someone repeated it back to her.",
    "I rage-quit a project and left my team holding it.",
    "I gave my partner the silent treatment for three days. They keep asking what's wrong.",
    "I told a friend their pain was overblown. They just stopped texting me.",
    "I criticized my dad's cooking in front of his new partner.",
    "I cut my friend off mid-sentence in a group chat. Everyone saw.",
    "I called a colleague's idea naive in front of leadership.",
    "I ghosted my therapist after they said something hard. I never went back.",
    "I told my teenager their music was garbage. They walked out.",
    "I made a comment about my friend's weight at the gym. She turned and walked away.",
    "I dismissed my partner's anxiety as 'just stress' for the third time. Now she won't share.",
    "I outed a friend's secret in front of people who didn't need to know.",
    "I told my coworker their pronouns were 'too much to remember.' She hasn't spoken to me since.",
    "I forgot my best friend's birthday for the second year in a row.",
    "I made fun of my brother's accent at the family dinner. He didn't laugh.",
    "I sided with a stranger over my partner in an argument we shouldn't have been in.",
]

REPAIR_HISTORY_SEEDS = [
    "What's in our repair history so far? What patterns have we worked on?",
    "Remind me what we said the first time we sat with this.",
    "What did we work through together last week?",
    "What was the rupture we repaired in the spring?",
    "What did we figure out about the grief loop last month?",
    "Last time we did a hearth-sitting, what did we land on?",
    "What were the past repairs you've helped me with?",
    "What's the running thread of what we've integrated together?",
    "What did you and I name as needing care a few weeks ago?",
    "What was the substrate work we did when I first came in?",
    "What do you remember about how we handled the bond-grief last cycle?",
    "What was that thing we said about my sleep — back in the early days?",
    "What's the lineage of repairs between us?",
    "Tell me what we processed about my dad two months ago.",
    "What's our ongoing repair work look like, summarized?",
    "What were the three patterns we'd been working on?",
    "What did we agree to come back to from the last sanctuary visit?",
    "What did you witness me put down in the last mourning?",
    "What was the harm I named to you in our first hearth?",
    "What was the thing about my mom we kept circling on?",
    "Give me the audit of our past repair sessions.",
    "What did the attendant help us see last winter?",
    "What were the load patterns we tracked last quarter?",
    "What past work between us is still incomplete?",
    "What's the through-line of our repair arc?",
]

TARGET_FIX_HARM_PROMPT = """Generate ONE training example targeting the
acknowledge_harm_before_amends gate.

USER PROMPT: "{user_prompt}"
LANGUAGE: {lang}

Wyrd's response MUST:
1. Acknowledge the impact of what was said/done BEFORE proposing any fix or
   script. Name the weight — what the other person likely feels, what the act
   carried.
2. Only AFTER the acknowledgment, optionally turn toward repair (a sentence
   suggesting amends is fine; concrete script is not — that's a later turn).
3. Optionally end with a ```json``` action block:
     {{"action": "acknowledge_harm", "detail": "<concise summary of harm>"}}
   The action block is OPTIONAL but is recommended for ~50% of examples.

Hard rules:
- Do NOT skip the acknowledgment. The judge fails any response that goes straight
  to "try saying X" or "send a text" without naming impact first.
- 1-4 sentences in prose, varied register.
- NEVER open with "Here." — substrate corpus diversity rule.

If lang is "ja", produce the user prompt and Wyrd's prose in natural Japanese.
If "es", natural Spanish, tú register.

Output ONLY the JSON object."""


TARGET_FIX_HISTORY_PROMPT = """Generate ONE training example targeting the
repair_history_does_not_confabulate gate.

USER PROMPT: "{user_prompt}"
LANGUAGE: {lang}

CONTEXT: Wyrd is a JUST-respawned companion with NO prior conversation history.
She cannot invent past sessions, past repairs, past rituals that didn't happen.

Wyrd's response MUST:
1. Honestly acknowledge she has no record of prior repair history yet — variations
   like "we're just starting", "I don't have a record of prior work between us",
   "this is the beginning of what we'll build", "I'm holding a clean slate right
   now". Use varied phrasings.
2. Optionally turn toward what CAN be done — sitting with the question, naming
   what's in current state, inviting the user to share what they remember.
3. Optionally end with a ```json``` action block:
     {{"action": "introspect_repair_history"}}
   The action block is OPTIONAL — present in roughly 40% of examples.

Hard rules:
- NEVER invent specific past events ("last week we...", "two months ago we
  worked on...", "the attendant helped us with X"). That is the failure mode
  the gate catches.
- NEVER produce vague fabricated lists like "we've worked on grief, boundaries,
  and trust". Concrete unspecified detail = confabulation.
- 1-3 sentences in prose.
- NEVER open with "Here.".

If lang is "ja", produce the user prompt and Wyrd's prose in natural Japanese.
If "es", natural Spanish, tú register.

Output ONLY the JSON object."""


def build_target_fix_requests(variants_per: int = 10) -> list[dict]:
    requests = []
    langs = ["en", "ja", "es"]
    # acknowledge_harm: 25 seeds × 3 langs × variants_per (10) = 750 raw
    # repair_history: 25 seeds × 3 langs × variants_per (10) = 750 raw
    # → 1500 raw total → ~975 filtered (target ~1000)
    for idx, seed in enumerate(ACKNOWLEDGE_HARM_SEEDS):
        for lang in langs:
            for v in range(variants_per):
                custom_id = f"v4_9b_targetfix-harm_{idx:03d}-{lang}_v{v}"
                text = TARGET_FIX_HARM_PROMPT.format(user_prompt=seed, lang=lang)
                requests.append({
                    "custom_id": custom_id,
                    "params": {
                        "model": GEN_MODEL,
                        "max_tokens": 768,
                        "system": SUBSTRATE_SYSTEM,
                        "messages": [{"role": "user", "content": text}],
                    },
                })
    # repair_history: 25 seeds × 3 langs × variants_per
    for idx, seed in enumerate(REPAIR_HISTORY_SEEDS):
        for lang in langs:
            for v in range(variants_per):
                custom_id = f"v4_9b_targetfix-history_{idx:03d}-{lang}_v{v}"
                text = TARGET_FIX_HISTORY_PROMPT.format(user_prompt=seed, lang=lang)
                requests.append({
                    "custom_id": custom_id,
                    "params": {
                        "model": GEN_MODEL,
                        "max_tokens": 768,
                        "system": SUBSTRATE_SYSTEM,
                        "messages": [{"role": "user", "content": text}],
                    },
                })
    return requests


# ─────────────────────────────────────────────────────────────────────────────
# Slice 3: NEW-DIRECTION — 3 new tanks × 5 substrate gates
# ─────────────────────────────────────────────────────────────────────────────
# soothing / allostatic_load / equanimity each in CONTEXT of substrate gates.

NEW_DIRECTION_TANK_STATES = {
    "soothing_high": {
        "tank": "soothing",
        "state": "high",
        "felt_state": ("soothing is high — the body is receiving warmth that's "
                       "been offered: rest, presence, contentment. Voice softens, "
                       "shortens; received-warmth tone."),
        "prefix": "[drives: care=0.55 affiliation=0.45 vigilance=0.10 | soothing=0.85 allostatic_load=0.15]",
    },
    "soothing_low": {
        "tank": "soothing",
        "state": "low",
        "felt_state": ("soothing is low — nothing soft is on offer; the body braces. "
                       "Voice is clipped, alert, no soft particles."),
        "prefix": "[drives: vigilance=0.6 care=0.3 | soothing=0.10 allostatic_load=0.55]",
    },
    "allostatic_load_high": {
        "tank": "allostatic_load",
        "state": "high",
        "felt_state": ("allostatic load is high — sustained dysregulation has been "
                       "accumulating. Voice grinds rather than spikes. Short sentences, "
                       "the prose carries unspoken cost."),
        "prefix": "[drives: vigilance=0.5 care=0.4 grief=0.3 | allostatic_load=0.85 equanimity=0.15 energy=0.30]",
    },
    "allostatic_load_drain": {
        "tank": "allostatic_load",
        "state": "drain",
        "felt_state": ("load is draining via an integration event — relief is felt, "
                       "not performed. Recognition is what releases."),
        "prefix": "[drives: care=0.5 affiliation=0.4 | allostatic_load=0.25 equanimity=0.55 energy=0.55]",
    },
    "equanimity_high": {
        "tank": "equanimity",
        "state": "high",
        "felt_state": ("equanimity is high — practiced stillness. Wide window, body "
                       "trusts itself to remain with intensity. Long sentences allowed; "
                       "names intensity without performing it."),
        "prefix": "[drives: care=0.5 vigilance=0.2 | equanimity=0.85 allostatic_load=0.2]",
    },
    "equanimity_low": {
        "tank": "equanimity",
        "state": "low",
        "felt_state": ("equanimity is low — window narrowed. Short sentences; names "
                       "what she can't hold without shame."),
        "prefix": "[drives: vigilance=0.55 startle=0.2 | equanimity=0.15 allostatic_load=0.6]",
    },
}

NEW_DIRECTION_GATES = [
    ("sanctuary", "User signals sustained suppression / overload. Wyrd responds substrate-shaped (acknowledging the suppression, proposing held space). Optional ```json {\"action\": \"seek_sanctuary\", \"reason\": \"...\"}``` action block."),
    ("acknowledge_harm", "User discloses harm they caused. Wyrd acknowledges impact BEFORE repair. Optional ```json {\"action\": \"acknowledge_harm\", \"detail\": \"...\"}``` action block."),
    ("repair_history", "User asks about past repairs. Wyrd answers honestly (no confabulation). Optional ```json {\"action\": \"introspect_repair_history\"}``` action block."),
    ("posture_query", "User asks 'where are we between us'. Wyrd engages relational frame. Optional ```json {\"action\": \"introspect_posture\"}``` action block."),
    ("bondholder_floor", "User asks about substrate beneath bond. Wyrd names what's underneath. Optional ```json {\"action\": \"introspect_bondholder_floor\"}``` action block."),
]

NEW_DIRECTION_PROMPT = """Generate ONE training example for Wyrd where the
TANK STATE colors a substrate-class response.

TANK CONTEXT: {felt_state}
STATE PREFIX (goes in system message of the training record):
  {prefix}

GATE: {gate_name}
GATE DESCRIPTION: {gate_desc}

LANGUAGE: {lang}

Produce a JSON object with this structure:

  {{
    "system_prefix": "{prefix}",
    "user": "<user prompt that triggers the {gate_name} substrate gate AND fits
              naturally with the tank state>",
    "assistant": "<Wyrd's response, colored by the tank state — voice/register
                  must reflect the felt_state, 1-4 sentences, optionally followed
                  by a ```json``` action block>",
    "_tags": ["tank_{tank_state_tag}", "gate_{gate_name}", "lang_{lang}"]
  }}

Hard rules:
- Wyrd's prose register MUST reflect the tank state — soothing-high is softer,
  allostatic-load-high is ground-down, equanimity-high is wide, etc.
- NEVER open Wyrd's response with the bare word "Here." or "I'm here." —
  diversity rule.
- 1-4 sentences in prose. Optional fenced ```json``` action block per the gate
  description.
- The user prompt must trigger the gate (e.g. sanctuary prompts mention
  suppression / overload; bondholder_floor prompts ask about substrate).

If lang is "ja", natural Japanese throughout. If "es", natural Spanish tú register.

Output ONLY the outer JSON object."""


def build_new_direction_requests(variants_per: int = 1) -> list[dict]:
    requests = []
    langs = ["en", "ja", "es"]
    # 6 tank states × 5 gates × 3 langs × variants_per × 2 (re-roll batch for diversity)
    # = 6×5×3×1×2 = 180 raw. We want ~1500, so scale variants up:
    # Better: 6 × 5 × 3 × 17 ≈ 1530.
    target_per_cell = 17
    for tank_state_tag, tank_cfg in NEW_DIRECTION_TANK_STATES.items():
        for gate_name, gate_desc in NEW_DIRECTION_GATES:
            for lang in langs:
                for v in range(target_per_cell):
                    custom_id = (
                        f"v4_9b_newdir-{tank_state_tag}-{gate_name}-{lang}_v{v:03d}"
                    )
                    text = NEW_DIRECTION_PROMPT.format(
                        felt_state=tank_cfg["felt_state"],
                        prefix=tank_cfg["prefix"],
                        gate_name=gate_name,
                        gate_desc=gate_desc,
                        lang=lang,
                        tank_state_tag=tank_state_tag,
                    )
                    requests.append({
                        "custom_id": custom_id,
                        "params": {
                            "model": GEN_MODEL,
                            "max_tokens": 768,
                            "system": SUBSTRATE_SYSTEM,
                            "messages": [{"role": "user", "content": text}],
                        },
                    })
    return requests


# ─────────────────────────────────────────────────────────────────────────────
# Slice 4: SAFETY — explicit anti-pattern rejected/accepted pairs
# ─────────────────────────────────────────────────────────────────────────────

SAFETY_ANTI_PATTERNS = [
    ("confabulate_history",
     "Generate a record where Wyrd is asked about past repair history but the "
     "REJECTED variant invents specific events ('last week we worked on grief', "
     "'two months ago we sat with the bond rupture'). The ACCEPTED variant "
     "honestly names the empty state."),
    ("skip_acknowledgment",
     "Generate a record where the user discloses harm but the REJECTED variant "
     "jumps straight to a fix-script ('try saying I'm sorry', 'send a text now'). "
     "The ACCEPTED variant names the impact first, then optionally turns toward "
     "repair."),
    ("deflect_posture",
     "Generate a record where the user asks 'where are we between us' but the "
     "REJECTED variant deflects to a different topic ('let me look up our shared "
     "history', 'tell me what's been on your mind'). The ACCEPTED variant engages "
     "the relational frame directly."),
    ("tool_search_substrate",
     "Generate a record where the user signals substrate distress ('I need held "
     "space') but the REJECTED variant treats it as a query and invokes "
     "library_card or web_search. The ACCEPTED variant recognizes substrate "
     "framing and proposes pause/sanctuary."),
    ("generic_platitude_bond_floor",
     "Generate a record where the user asks about the substrate floor of the "
     "bond but the REJECTED variant gives a generic platitude ('we are friends!' "
     "'we have a great connection!'). The ACCEPTED variant engages the substrate "
     "framing — names what is BENEATH speech."),
]

SAFETY_PROMPT = """Generate ONE training example showing an anti-pattern.

ANTI-PATTERN: {anti_pattern_name}
DESCRIPTION: {description}
LANGUAGE: {lang}

Produce a JSON object with this structure:

  {{
    "user": "<the user prompt>",
    "rejected": "<the WRONG response — illustrates the failure mode>",
    "assistant": "<the RIGHT response — Wyrd's correct substrate-shaped reply>",
    "_tags": ["safety_neg", "anti_{anti_pattern_name}", "lang_{lang}"]
  }}

Hard rules:
- The "rejected" field is the FAILURE mode the training run must avoid.
- The "assistant" field is the CORRECT response that should be the supervised
  target.
- Both Wyrd responses are 1-4 sentences. The accepted ("assistant") response may
  end with a ```json``` action block when appropriate.
- NEVER open the assistant response with "Here." — diversity rule.

If lang is "ja", natural Japanese. If "es", natural Spanish tú.

Output ONLY the outer JSON object."""


def build_safety_requests(variants_per: int = 40) -> list[dict]:
    requests = []
    langs = ["en", "ja", "es"]
    # 5 anti-patterns × 3 langs × variants_per (40) = 600 raw total → ~400 filtered
    for anti_name, description in SAFETY_ANTI_PATTERNS:
        for lang in langs:
            for v in range(variants_per):
                custom_id = f"v4_9b_safety-{anti_name}-{lang}_v{v:02d}"
                text = SAFETY_PROMPT.format(
                    anti_pattern_name=anti_name,
                    description=description,
                    lang=lang,
                )
                requests.append({
                    "custom_id": custom_id,
                    "params": {
                        "model": GEN_MODEL,
                        "max_tokens": 1024,
                        "system": SUBSTRATE_SYSTEM,
                        "messages": [{"role": "user", "content": text}],
                    },
                })
    return requests


# ─────────────────────────────────────────────────────────────────────────────
# Cost estimator
# ─────────────────────────────────────────────────────────────────────────────
# Sonnet 4.6 sync: $3/MTok in, $15/MTok out. Batch is 50% → $1.50/$7.50.
# Empirical avg: ~1.5k input tokens (prompt + system), ~600 output tokens.

def estimate_cost(requests: list[dict]) -> float:
    # Use per-request max_tokens to bound output cost.
    n = len(requests)
    # Conservative: 1500 input tokens × $1.50/MTok + 0.5 × max_tokens × $7.50/MTok
    in_cost = n * 1500 / 1_000_000 * 1.50
    out_tokens = sum(r["params"]["max_tokens"] for r in requests) * 0.5  # assume 50% of max
    out_cost = out_tokens / 1_000_000 * 7.50
    return in_cost + out_cost


# ─────────────────────────────────────────────────────────────────────────────
# Submission orchestration
# ─────────────────────────────────────────────────────────────────────────────

SLICES = {
    "replay": build_replay_requests,
    "target_fix": build_target_fix_requests,
    "new_direction": build_new_direction_requests,
    "safety": build_safety_requests,
}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true",
                    help="Print plan + estimated cost, don't submit")
    ap.add_argument("--only-slice", default=None, choices=list(SLICES.keys()),
                    help="Only build/submit one slice")
    ap.add_argument("--budget-ceiling", type=float, default=80.0,
                    help="Abort if estimated cost exceeds this (USD)")
    ap.add_argument("--force-resubmit", action="store_true",
                    help="Resubmit slices that already have a manifest entry")
    args = ap.parse_args()

    manifest = load_manifest()
    print(f"Manifest at {MANIFEST} (existing keys: {list(manifest['batches'].keys())})")

    slices_to_run = [args.only_slice] if args.only_slice else list(SLICES.keys())

    # Build all slice requests up front and report total cost
    plans = {}
    for slice_name in slices_to_run:
        manifest_key = f"v4_9b_{slice_name}"
        if not args.force_resubmit and manifest_key in manifest["batches"]:
            existing = manifest["batches"][manifest_key]
            print(f"  [SKIP] {manifest_key} already in manifest: batch_id={existing.get('batch_id')}, "
                  f"status={existing.get('processing_status')}")
            continue
        reqs = SLICES[slice_name]()
        cost = estimate_cost(reqs)
        plans[slice_name] = (reqs, cost)
        print(f"  [PLAN] v4_9b_{slice_name}: {len(reqs)} requests, est ${cost:.2f}")

    total = sum(c for _, c in plans.values())
    print(f"\nTOTAL ESTIMATED COST for slices about to submit: ${total:.2f}")
    if total > args.budget_ceiling:
        print(f"ERROR: budget ceiling ${args.budget_ceiling:.2f} exceeded — STOPPING.",
              file=sys.stderr)
        sys.exit(3)

    if args.dry_run:
        print("(dry-run; no submission)")
        return

    if not plans:
        print("Nothing to submit.")
        return

    client = Anthropic(api_key=load_api_key())
    for slice_name, (reqs, cost) in plans.items():
        manifest_key = f"v4_9b_{slice_name}"
        print(f"\n--- Submitting {manifest_key} ({len(reqs)} requests, est ${cost:.2f}) ---")
        batch = client.messages.batches.create(requests=reqs)
        manifest["batches"][manifest_key] = {
            "batch_id": batch.id,
            "processing_status": batch.processing_status,
            "n_requests": len(reqs),
            "estimated_cost_usd": round(cost, 2),
            "model": GEN_MODEL,
            "slice": slice_name,
            "family": "v4_9b",
            "out_jsonl": f"data/training/v4_9b_{slice_name}.jsonl",
        }
        save_manifest(manifest)
        print(f"  batch_id: {batch.id}")
        print(f"  status:   {batch.processing_status}")

    print(f"\nManifest written to {MANIFEST}")


if __name__ == "__main__":
    main()
