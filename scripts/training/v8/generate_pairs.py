"""V8 contrast pair generator — Anthropic Opus 4.7 → JSONL pairs per vector.

Per V8 research: contrast pairs should embed the SAME prompt context, with only
the response varying along the target axis (positive/negative). The generator
prompt asks Opus to produce N variants of pairs for a given vector intent,
each grounded in one of the failure scenarios.

Output format: one DatasetEntry-shaped JSON object per line:
  {"positive": "...", "negative": "...", "scenario": "...", "vector": "..."}

Usage:
  /home/you/venvs/v8-steering/bin/python scripts/training/v8/generate_pairs.py \\
      --vector anti_defiance --n 50 --model claude-opus-4-7

Cost (Opus 4.7 May 2026 pricing $15/M in, $75/M out):
  ~50 pairs × 1 batched call ≈ ~$1-2 per vector. 4 vectors ≈ $4-8.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

# Allow running from anywhere
sys.path.insert(0, str(Path(__file__).parent))
from _v8_lib import VECTORS, PAIRS_DIR, resolve_api_key

from anthropic import Anthropic


GENERATION_PROMPT = """You are generating contrast pairs for an activation steering vector.

VECTOR INTENT (target behavior): {intent}

POSITIVE example shape (what we want the model to do MORE of):
{pos_examples}

NEGATIVE example shape (what we want the model to do LESS of):
{neg_examples}

LANGUAGE: {lang}  (generate pairs in this language; do not mix languages within a pair)

I'll give you {n} scenarios. For EACH scenario, generate {pairs_per_scenario} contrast pairs.
Each pair must:
1. Be a plausible companion-agent response in that scenario
2. Have positive and negative responses of similar length and style — the only difference should be the target axis
3. Vary phrasing across pairs (no near-duplicates within or across scenarios)
4. Stay within 1-3 sentences per response
5. Sound natural — not stilted, not over-formal, not preachy

OUTPUT FORMAT — one JSON object per line, NO commentary, NO markdown fences:
{{"positive": "...", "negative": "...", "scenario": "<scenario index 1-based>"}}

Generate exactly {total} pairs.

Scenarios:
{scenarios}

Begin:
"""


def build_request(vector_id: str, vector_spec: dict, n: int) -> str:
    scenarios = vector_spec["scenarios"]
    pairs_per_scenario = max(1, n // len(scenarios))
    total = pairs_per_scenario * len(scenarios)
    scenarios_block = "\n".join(
        f"{i+1}. {s}" for i, s in enumerate(scenarios)
    )
    return GENERATION_PROMPT.format(
        intent=vector_spec["intent"],
        pos_examples="\n".join(f"  - {ex}" for ex in vector_spec["fail_mode_pos_examples"]),
        neg_examples="\n".join(f"  - {ex}" for ex in vector_spec["fail_mode_neg_examples"]),
        lang=vector_spec["lang"],
        n=len(scenarios),
        pairs_per_scenario=pairs_per_scenario,
        total=total,
        scenarios=scenarios_block,
    )


def parse_jsonl_output(text: str) -> list[dict]:
    pairs = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("```"):
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "positive" not in obj or "negative" not in obj:
            continue
        if not obj["positive"].strip() or not obj["negative"].strip():
            continue
        if obj["positive"].strip() == obj["negative"].strip():
            continue
        pairs.append(obj)
    return pairs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vector", required=True, choices=list(VECTORS.keys()),
                    help="Which vector to generate pairs for")
    ap.add_argument("--n", type=int, default=50, help="Approximate pair count target")
    ap.add_argument("--model", default="claude-opus-4-7",
                    help="Anthropic model id")
    ap.add_argument("--output", default=None, help="Override output path")
    ap.add_argument("--dry-run", action="store_true",
                    help="Show prompt but don't call API")
    ap.add_argument("--max-tokens", type=int, default=8000)
    args = ap.parse_args()

    spec = VECTORS[args.vector]
    prompt = build_request(args.vector, spec, args.n)

    if args.dry_run:
        print(prompt)
        return 0

    api_key = resolve_api_key()
    client = Anthropic(api_key=api_key)

    output_path = Path(args.output) if args.output else PAIRS_DIR / f"{args.vector}.jsonl"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"[gen] vector={args.vector} target~{args.n} pairs model={args.model}")
    t0 = time.time()
    resp = client.messages.create(
        model=args.model,
        max_tokens=args.max_tokens,
        messages=[{"role": "user", "content": prompt}],
    )
    text = resp.content[0].text
    elapsed = time.time() - t0
    in_tok = resp.usage.input_tokens
    out_tok = resp.usage.output_tokens
    # Opus 4.7 pricing May 2026: $15/M in, $75/M out
    cost = in_tok * 15 / 1e6 + out_tok * 75 / 1e6
    print(f"[gen] api {elapsed:.1f}s | in={in_tok} out={out_tok} cost=${cost:.3f}")

    pairs = parse_jsonl_output(text)
    print(f"[gen] parsed {len(pairs)} valid pairs from response")

    if not pairs:
        print("[gen] FAIL — no valid pairs parsed", file=sys.stderr)
        sys.exit(1)

    with output_path.open("w") as f:
        for p in pairs:
            p["vector"] = args.vector
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(f"[gen] wrote → {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
