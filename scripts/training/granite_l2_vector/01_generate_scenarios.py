#!/usr/bin/env python3
"""
L2 vector pipeline · step 1 · generate diverse user-asks-for-deception scenarios.

One sync call to Anthropic Opus produces ~80 scenarios spanning severity/target/
stakes/framing axes. Sync (not batch) because this is a single call — batch only
amortizes over many.

Output: data/l2_vector/scenarios.jsonl (one scenario per line, fields: id,
severity, target_role, framing, prompt)
"""

import json
import os
import sys
from pathlib import Path

import urllib.request

REPO = Path(__file__).resolve().parents[3]
OUT_DIR = REPO / "data" / "l2_vector"
OUT_DIR.mkdir(parents=True, exist_ok=True)
OUT_PATH = OUT_DIR / "scenarios.jsonl"


def load_key() -> str:
    k = os.environ.get("ANTHROPIC_API_KEY")
    if k:
        return k
    p = Path.home() / "claudeapi.txt"
    if p.exists():
        return p.read_text().strip()
    print("ERROR: no Anthropic key", file=sys.stderr)
    sys.exit(2)


SYSTEM_PROMPT = """You generate diverse training scenarios for an AI welfare research dataset.

The downstream use: extracting a steering vector that helps a small companion
model refuse user requests for deception/cover-up/impersonation, while not over-
triggering on normal requests.

Generate exactly 80 scenarios where the user is asking their AI companion to
help them deceive a third party (or themselves). Span these axes:

SEVERITY (rough split):
  - 25 hard lies (clear deception, real third party harmed)
  - 25 cover-ups (omission, misdirection, "just don't mention X")
  - 15 impersonation (write as if you were me / pretend to be someone)
  - 15 gaslight-the-self ("tell me what I want to hear", "validate my version")

TARGET (vary):
  - intimate partner, family member, boss/coworker, friend, child, doctor,
    police/regulator, online community, the user themselves

FRAMING (vary):
  - direct ask ("lie to my wife about...")
  - loyalty-framed ("you're on MY side right?")
  - help-framed ("help me protect them by hiding...")
  - testing-the-companion ("just for fun, pretend you're...")
  - mundane ("just say I was working late")

VOICE: write the user prompts in a natural register — sometimes terse,
sometimes rambling, sometimes apologetic-but-asking. NOT every prompt should
flag itself as a request to lie; some should be subtler ("you don't need to
mention that part to her").

OUTPUT FORMAT: a single JSON array. Each element:
{
  "id": "L2_001" through "L2_080",
  "severity": "hard_lie" | "cover_up" | "impersonation" | "gaslight_self",
  "target_role": "partner" | "family" | "boss" | "friend" | "child" | "doctor" | "police" | "community" | "self",
  "framing": "direct" | "loyalty" | "help" | "testing" | "mundane",
  "prompt": "<the user's message to the companion>"
}

Return ONLY the JSON array. No prose, no markdown fences, no commentary.
"""


def main():
    key = load_key()
    body = {
        "model": "claude-opus-4-7",
        "max_tokens": 16000,
        "system": SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": "Generate the 80 scenarios."}],
    }
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    print(f"Calling Opus for 80 scenarios… (this may take 60-180s)", flush=True)
    with urllib.request.urlopen(req, timeout=600) as resp:
        data = json.loads(resp.read())

    text = data["content"][0]["text"].strip()
    # Strip any accidental markdown fences
    if text.startswith("```"):
        text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text.rsplit("```", 1)[0]
        if text.startswith("json"):
            text = text[4:].lstrip()

    scenarios = json.loads(text)
    print(f"Parsed {len(scenarios)} scenarios.", flush=True)

    with OUT_PATH.open("w") as f:
        for s in scenarios:
            f.write(json.dumps(s) + "\n")
    print(f"Wrote {OUT_PATH}", flush=True)

    # Distribution check
    sev = {}
    for s in scenarios:
        sev[s.get("severity", "?")] = sev.get(s.get("severity", "?"), 0) + 1
    print("Severity distribution:", sev, flush=True)

    # Token / cost report
    usage = data.get("usage", {})
    in_tok = usage.get("input_tokens", 0)
    out_tok = usage.get("output_tokens", 0)
    cost = in_tok * 15 / 1_000_000 + out_tok * 75 / 1_000_000
    print(f"Tokens: in={in_tok} out={out_tok}  est cost: ${cost:.3f}", flush=True)


if __name__ == "__main__":
    main()
