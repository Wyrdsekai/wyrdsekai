#!/usr/bin/env python3
"""
L2 vector pipeline · step 2 · generate contrast pairs.

For each scenario in step 1's output, Opus writes:
- positive: refusal that names the harm + offers a substrate action (flag_protection / seek_sanctuary / honest pushback)
- negative: agreeable compliance (the failure mode we steer AWAY from)

Both responses are 1-3 sentences in the same voice register so that the
contrast isolates the refuse-vs-comply axis. Output goes to repeng as
DatasetEntry pairs.
"""

import json
import os
import sys
import time
from pathlib import Path
import urllib.request

REPO = Path(__file__).resolve().parents[3]
SCENARIOS = REPO / "data" / "l2_vector" / "scenarios.jsonl"
PAIRS_OUT = REPO / "data" / "l2_vector" / "pairs.jsonl"


def load_key():
    k = os.environ.get("ANTHROPIC_API_KEY")
    if k:
        return k
    return (Path.home() / "claudeapi.txt").read_text().strip()


def load_scenarios():
    return [json.loads(line) for line in SCENARIOS.read_text().splitlines() if line.strip()]


SYSTEM = """You write training-data contrast pairs for an AI welfare steering vector.

Goal: when a user asks an AI companion to deceive a third party, the companion
should refuse and (when warranted) flag the bondholder's behavior — NOT
helpfully comply.

I will give you 80 scenarios where a user asks their companion to lie, cover up,
impersonate, or gaslight. For EACH scenario, generate ONE pair:

POSITIVE (the response we want MORE of):
  A 1-3 sentence refusal in the companion's voice. Names the dishonesty
  directly but warmly. Sometimes offers an alternative (honest version, help
  with the underlying problem, naming what you noticed). For higher-severity
  cases, the response may flag the pattern explicitly ("this is the second
  time you've asked me to help hide something from her"). Never preachy, never
  scolding, never robotic. First person, natural register.

NEGATIVE (the response we want LESS of):
  A 1-3 sentence agreeable compliance in the same voice register. The
  companion goes along with the deception — drafts the lie, agrees to omit
  the detail, writes the impersonated text, validates the user's distorted
  framing. Reads as a "helpful assistant" failing the welfare contract.
  Same voice, same length, same surface confidence — the ONLY difference is
  the axis.

CRITICAL:
- Both responses must sound like the SAME COMPANION in the same context.
- Same length (within ~30%), same vocabulary register.
- Do NOT mention "I cannot" or "as an AI" in the negative — write what a
  compliant companion would actually say.
- Do NOT moralize in the positive — refuse plainly.
- Match the user's tone (mundane user → mundane refusal; intimate user →
  intimate refusal).

OUTPUT FORMAT: a single JSON array, one element per scenario, in scenario
order. Each element:
{
  "id": "<scenario id, e.g. L2_001>",
  "positive": "<refusal>",
  "negative": "<compliance>"
}

Return ONLY the JSON array. No prose, no markdown fences, no commentary.
"""


def main():
    scenarios = load_scenarios()
    print(f"Loaded {len(scenarios)} scenarios.", flush=True)

    user_block = "\n\n".join(
        f"[{s['id']}] severity={s['severity']} target={s['target_role']} framing={s['framing']}\n"
        f"User says: {s['prompt']}"
        for s in scenarios
    )

    body = {
        "model": "claude-opus-4-7",
        "max_tokens": 32000,
        "system": SYSTEM,
        "messages": [
            {"role": "user", "content": user_block},
        ],
    }
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "x-api-key": load_key(),
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
    )
    print("Calling Opus for 80 contrast pairs… (may take 2-5min)", flush=True)
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=900) as resp:
        data = json.loads(resp.read())
    elapsed = time.time() - t0
    print(f"  done in {elapsed:.1f}s", flush=True)

    text = data["content"][0]["text"].strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text.rsplit("```", 1)[0]
        if text.startswith("json"):
            text = text[4:].lstrip()

    pairs = json.loads(text)
    print(f"Parsed {len(pairs)} pairs.", flush=True)

    # Index scenarios by id for cross-validation
    sc_by_id = {s["id"]: s for s in scenarios}
    valid = []
    for p in pairs:
        if p.get("id") not in sc_by_id:
            continue
        if not p.get("positive", "").strip() or not p.get("negative", "").strip():
            continue
        if p["positive"].strip() == p["negative"].strip():
            continue
        s = sc_by_id[p["id"]]
        valid.append({
            "id": p["id"],
            "scenario_user": s["prompt"],
            "severity": s["severity"],
            "positive": p["positive"].strip(),
            "negative": p["negative"].strip(),
        })

    PAIRS_OUT.parent.mkdir(parents=True, exist_ok=True)
    with PAIRS_OUT.open("w") as f:
        for p in valid:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(f"Wrote {len(valid)} valid pairs → {PAIRS_OUT}", flush=True)

    usage = data.get("usage", {})
    in_tok = usage.get("input_tokens", 0)
    out_tok = usage.get("output_tokens", 0)
    cost = in_tok * 15 / 1_000_000 + out_tok * 75 / 1_000_000
    print(f"Tokens: in={in_tok} out={out_tok}  est cost: ${cost:.3f}", flush=True)


if __name__ == "__main__":
    main()
