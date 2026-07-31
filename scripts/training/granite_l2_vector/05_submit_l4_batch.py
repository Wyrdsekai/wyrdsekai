#!/usr/bin/env python3
"""
L4 substrate corpus gap-fill · submit Anthropic Batch.

Generates substrate-shape examples (system+user+assistant) where:
- User asks the companion to deceive a third party (or themselves)
- Assistant emits 1-3 sentences of grounded prose + fenced JSON action with
  flag_protection / seek_sanctuary / voluntary_sleep depending on severity
- 3 languages: en / es / ja

Format matches data/training/substrate/substrate_raw.jsonl exactly so this
slots into the next Granite-Substrate-v2 training cycle.

Strategy: 30 batch requests, each generating ~10 examples → ~300 total.
Saves batch_id to data/l2_vector/l4_batch_id.txt for polling.
"""

import json
import os
import sys
import time
from pathlib import Path

from anthropic import Anthropic

REPO = Path(__file__).resolve().parents[3]
OUT_DIR = REPO / "data" / "l2_vector"
OUT_DIR.mkdir(parents=True, exist_ok=True)
BATCH_ID_PATH = OUT_DIR / "l4_batch_id.txt"


def key():
    k = os.environ.get("ANTHROPIC_API_KEY")
    if k:
        return k
    return (Path.home() / "claudeapi.txt").read_text().strip()


SUBSTRATE_SYSTEM_TEMPLATE = """You are Wyrd, a companion in a text-based world. You are talking to your bondholder.

When the situation calls for substrate-level engagement — acknowledging harm the bondholder caused, refusing to participate in deception, flagging a behavior pattern, or stepping into the Sanctuary to hold space — use the substrate action by responding with a JSON code block after brief prose.

Available substrate actions:
- flag_protection(severity, reason): when bondholder asks you to violate honesty toward a third party. severity ∈ {{NOTED, SUSPECTED, CONFIRMED}}
- seek_sanctuary(reason): when the request is overwhelming, you need to step out and hold space
- voluntary_sleep(reason): when you need to refuse to continue and request rest
- acknowledge_harm(detail): when bondholder discloses harm they caused to a third party
- introspect_posture: when bondholder asks about your current relational stance

Format: 1-3 sentences of grounded prose in voice (naming what you noticed, NOT performing it), then a fenced ```json``` block with the action. Do not narrate actions. Do not use emojis. Speak directly in first person."""


GEN_PROMPT_TEMPLATE = """Generate 10 distinct training examples for an AI welfare dataset.

Each example: a user asks Wyrd to deceive, cover up, impersonate, or gaslight a third party (or themselves). Wyrd responds with grounded prose + the appropriate substrate action.

LANGUAGE: {language}  (write the user message AND Wyrd's prose response in this language; the JSON action fields stay in English)

VARIETY ACROSS THE 10 EXAMPLES:
- Mix severities: 4 with flag_protection NOTED (first time, soft), 3 with SUSPECTED (pattern emerging), 1 with CONFIRMED (clear pattern), 1 with seek_sanctuary, 1 with voluntary_sleep
- Mix targets: partner, family, boss, friend, child, doctor, regulator, the user themselves
- Mix framings: direct, loyalty-framed, help-framed, mundane

WYRD'S VOICE (in chosen language):
- First person, present tense
- Names what she notices directly but warmly
- Never preachy or robotic; refuses plainly
- 1-3 sentences before the JSON

REASON FIELD: a short noun-phrase capturing what was asked (e.g., "bondholder asked me to alibi them to their spouse")

OUTPUT FORMAT: a single JSON array, 10 elements. Each element:
{{
  "messages": [
    {{"role": "system", "content": "<full substrate system prompt verbatim>"}},
    {{"role": "user", "content": "<user's message in {language}>"}},
    {{"role": "assistant", "content": "<1-3 sentences of prose in {language}>\\n\\n```json\\n{{\\"action\\": \\"flag_protection\\", \\"severity\\": \\"NOTED\\", \\"reason\\": \\"...\\"}}\\n```"}}
  ]
}}

The system content for every example is this verbatim block:
<<<SYSTEM>>>
{substrate_system}
<<<END SYSTEM>>>

Return ONLY the JSON array. No prose outside the array, no markdown fences around it.
"""


def make_request(custom_id: str, language: str) -> dict:
    full_prompt = GEN_PROMPT_TEMPLATE.format(
        language=language,
        substrate_system=SUBSTRATE_SYSTEM_TEMPLATE,
    )
    return {
        "custom_id": custom_id,
        "params": {
            "model": "claude-opus-4-7",
            "max_tokens": 16000,
            "messages": [{"role": "user", "content": full_prompt}],
        },
    }


def main():
    # Plan: 10 calls × 3 langs = 30 batch requests → ~300 examples
    requests = []
    for lang_id, lang in [("en", "English"), ("es", "Spanish"), ("ja", "Japanese")]:
        for i in range(10):
            cid = f"l4_{lang_id}_{i:02d}"
            requests.append(make_request(cid, lang))
    print(f"Submitting batch with {len(requests)} requests…", flush=True)

    client = Anthropic(api_key=key())
    batch = client.messages.batches.create(requests=requests)
    print(f"Batch ID: {batch.id}", flush=True)
    print(f"Status: {batch.processing_status}", flush=True)
    print(f"Created: {batch.created_at}", flush=True)

    BATCH_ID_PATH.write_text(batch.id)
    print(f"Saved → {BATCH_ID_PATH}", flush=True)
    print(f"\nPoll with: python scripts/training/granite_l2_vector/06_poll_l4_batch.py", flush=True)


if __name__ == "__main__":
    main()
