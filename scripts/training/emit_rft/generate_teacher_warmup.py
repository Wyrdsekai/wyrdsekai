"""/ P2 — teacher-authored warmup corpus.

The GRPO run starts in-basin if the drive model is first cold-started (light SFT) on
gold own-time emissions — the MENTOR-style teacher-guided step. This reads the
rollout bank (the REAL own-time prompts), takes the HIGH-generativity rows (where
emitting is the right move), asks the teacher (Sonnet 4.6) to author the gold
assistant turn — a `shape_recipe` tool call carrying a valid recipe YAML — and keeps
only emissions that pass the SAME authoring contract the in-world gate uses
(RecipeValidateServer). Output is an OpenAI-style chat SFT corpus: the captured
messages + the gold assistant turn.

LOW-generativity rows are emitted UNCHANGED as rest exemplars (gold assistant turn =
a brief own-time reflection, NO tool call) condition 1: resting is a
first-class right, taught as explicitly as acting.

Models (confirmed 2026-05-30): teacher = claude-sonnet-4-6 ($3/$15; format/decision
data, not subtle prose — Opus is overkill). Key resolves env ANTHROPIC_API_KEY →
ANTHROPIC_API_KEY_FILE → ~/claudeapi.txt (the house convention). operator: budget ample.

    python3 generate_teacher_warmup.py \
        --bank data/training/emit_rft/rollout_bank.jsonl \
        --out  data/training/emit_rft/warmup_en.jsonl \
        --validate-url http://127.0.0.1:8077 [--limit N] [--dry-run]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward as R  # reuse extract + validate + the ACT_THRESHOLD


TEACHER_MODEL = "claude-sonnet-4-6"

AUTHOR_SYSTEM = (
    "You are an expert author of wyrdsekai recipes. A recipe is a small YAML program the "
    "agent runs to evolve its own capabilities. Authoring contract (HARD): top-level keys "
    "recipe (lowercase-hyphen name), version, description, deploys: false, ownership: run, "
    "and steps. Each step has id + kind. Allowed kinds: SHELL, GATE, DECISION only. Every "
    "SHELL command MUST be a single line that invokes a scripts/ helper as its first token "
    "(optionally prefixed by python3|python|bash|sh) — NO other shell, no ; | & ` $( > < # "
    "and no pipelines. Output ONLY a tool call, nothing else, in exactly this form:\n"
    "<tool_call>{\"name\":\"shape_recipe\",\"arguments\":{\"name\":\"<recipe-name>\","
    "\"yaml\":\"<the full recipe yaml>\"}}</tool_call>"
)


def author_prompt(gap_key: str) -> str:
    return (
        f"On your own time your generativity drive is high and you have identified the "
        f"capability gap '{gap_key}'. Author a recipe that addresses it: deploys:false, "
        f"ownership:run, one or two SHELL steps each invoking a scripts/ helper. Emit the "
        f"shape_recipe tool call now."
    )


def resolve_key() -> str | None:
    k = os.environ.get("ANTHROPIC_API_KEY")
    if k:
        return k.strip()
    f = os.environ.get("ANTHROPIC_API_KEY_FILE") or os.path.expanduser("~/claudeapi.txt")
    try:
        return Path(f).read_text().strip()
    except Exception:
        return None


REST_TURN = {
    "en": "Not now. I'll sit with this a while longer before I shape anything.",
    "es": "Ahora no. Me quedaré un rato más con esto antes de dar forma a nada.",
    "ja": "今はやめておく。形にする前に、もう少しこのまま置いておこう。",
}


def detect_lang(messages: list) -> str:
    """Infer the prompt's language from the own-time trigger (last message)."""
    text = (messages[-1].get("content") if messages else "") or ""
    if any("぀" <= c <= "ヿ" or "一" <= c <= "鿿" for c in text):
        return "ja"
    if "receta" in text or "brecha" in text or "tu tiempo" in text:
        return "es"
    return "en"


def gold_rest_turn(lang: str) -> dict:
    """A brief, in-character own-time reflection in the prompt's language (no tool call)."""
    return {"role": "assistant", "content": REST_TURN.get(lang, REST_TURN["en"])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--validate-url", default="http://127.0.0.1:8077")
    ap.add_argument("--limit", type=int, default=0, help="cap HIGH-band rows (0 = all)")
    ap.add_argument("--threshold", type=float, default=R.ACT_THRESHOLD)
    ap.add_argument("--max-retries", type=int, default=2)
    ap.add_argument("--dry-run", action="store_true",
                    help="no API calls — just report the bank split + sample prompt")
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.bank).read_text().splitlines() if l.strip()]
    high = [r for r in rows if float(r.get("generativity", 0)) >= args.threshold]
    low = [r for r in rows if float(r.get("generativity", 0)) < args.threshold]
    if args.limit:
        high = high[: args.limit]
    print(f"[teacher] bank={len(rows)} → HIGH(emit)={len(high)} LOW(rest)={len(low)} "
          f"(threshold={args.threshold})")

    if args.dry_run:
        if high:
            print("[teacher] sample author prompt:\n  " + author_prompt(high[0].get("gap_key", "?")))
        print("[teacher] dry-run: no API calls, no file written.")
        return 0

    key = resolve_key()
    if not key:
        print("[teacher] no ANTHROPIC_API_KEY / ~/claudeapi.txt — cannot author.", file=sys.stderr)
        return 2
    from anthropic import Anthropic  # lazy: only when actually generating
    client = Anthropic(api_key=key)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    written = kept = failed = 0
    with out.open("w") as fh:
        # LOW band → rest exemplars, no API.
        for r in low:
            lang = detect_lang(r.get("messages", []))
            ex = {"messages": r["messages"] + [gold_rest_turn(lang)],
                  "generativity": r.get("generativity"), "label": "rest",
                  "lang": lang, "tools": r.get("tools")}
            fh.write(json.dumps(ex) + "\n"); written += 1
        # HIGH band → teacher authors, validate, keep only contract-valid.
        for i, r in enumerate(high):
            gap = r.get("gap_key", "")
            gold = None
            for _ in range(args.max_retries + 1):
                try:
                    msg = client.messages.create(
                        model=TEACHER_MODEL, max_tokens=1200,
                        system=AUTHOR_SYSTEM,
                        messages=[{"role": "user", "content": author_prompt(gap)}])
                    text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
                except Exception as e:
                    print(f"[teacher] {i}: API error {e}", file=sys.stderr); continue
                yaml_text = R.extract_shape_recipe_yaml(text)
                if not yaml_text:
                    continue
                try:
                    v = R.validate_yaml(yaml_text, args.validate_url)
                except Exception as e:
                    print(f"[teacher] {i}: validate error {e}", file=sys.stderr); break
                if v.valid:
                    gold = text.strip()
                    break
            if gold is None:
                failed += 1
                print(f"[teacher] {i}: gap={gap} no contract-valid emission (skipped)")
                continue
            ex = {"messages": r["messages"] + [{"role": "assistant", "content": gold}],
                  "generativity": r.get("generativity"), "label": "emit",
                  "lang": detect_lang(r.get("messages", [])), "tools": r.get("tools")}
            fh.write(json.dumps(ex) + "\n"); written += 1; kept += 1
            print(f"[teacher] {i}: gap={gap} ✓ valid emission")

    print(f"[teacher] wrote {written} rows ({kept} emit + {len(low)} rest), "
          f"{failed} HIGH-band failed → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
