#!/usr/bin/env python3
"""
Persona-swap corpus expansion via Claude Opus 4.7.

Real users produce variance that clusters too tightly in Sonnet paraphrases —
Sonnet sanitizes, centers on label means, loses surface variety. Opus holds
distinct personas and produces surface forms synthetic expansion misses:
typos, fragments, context-refs, code-switching, terse commands, grief-worded
fragments, run-on child speech.

Pipeline:
  1. For each (persona, label) pair, ask Opus to generate N tells AS that
     persona, for that label's intent
  2. Heavy dedupe by normalized text
  3. Output jsonl: label, text, source="persona:<name>"

Personas are deliberately picked to cover surface-form axes the training
corpus misses: age, formality, language proficiency, emotional state,
typing style, attention budget.

Usage:
    export ANTHROPIC_API_KEY=... (or use ~/claudeapi.txt)
    python3 scripts/classifier/persona_expand_corpus.py \\
        --out core/src/main/resources/classifier/harvest/persona-candidates.jsonl \\
        --per-persona-per-label 40 \\
        --model claude-opus-4-7

Only targets labels oasst harvest can't cover cleanly:
    chat, reflective, emotional, delegate, action, tell_someone
Plus style-variance boost on factual + write.
"""
import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: pip install anthropic", file=sys.stderr)
    sys.exit(1)


# Eight personas — picked to span surface-form axes the corpus lacks.
PERSONAS = {
    "tired-parent": (
        "You are a parent at the end of a long day. Kids finally asleep. You're "
        "in bed on your phone. You type in lowercase with occasional typos, keep "
        "things short, sometimes drop punctuation. You're practical, affectionate, "
        "slightly raw. Sometimes you leave sentences half-finished because you're "
        "exhausted."
    ),
    "young-child": (
        "You are a 9-year-old kid who knows the companion from the house AI. You "
        "type enthusiastically with runs of sentences joined by 'and', occasional "
        "caps lock, emojis, typos. You ask about everything. You sometimes forget "
        "context the companion already has. Age-appropriate vocabulary — simple "
        "words, concrete things."
    ),
    "esl-speaker": (
        "You are a fluent English speaker whose first language is not English. "
        "Your grammar is sometimes imperfect, you may skip articles or use "
        "slightly-off preposition choices, but your meaning is always clear. "
        "You're direct and practical."
    ),
    "terse-dev": (
        "You are a senior developer. You type in short commands, often without "
        "punctuation at sentence ends. You use technical shorthand. You're "
        "impatient with preamble — get to the point. Sometimes just a verb and "
        "an object."
    ),
    "grief-struck": (
        "You are someone whose partner died three months ago. You're asking the "
        "companion for presence, not solutions. Your typing is often fragmented, "
        "sentences trail off, you switch topics mid-thought. You reference the "
        "person who died sometimes without naming them. You don't want to be "
        "fixed."
    ),
    "midwestern-retiree": (
        "You are a retired person in the American Midwest. Polite, slightly "
        "wordy, you'll often add context before the actual question. You use "
        "phrases like 'I was wondering if' or 'if it's not too much trouble'. "
        "You mix personal chitchat with the main request."
    ),
    "neurodivergent-infodumper": (
        "You are someone who info-dumps about their special interests. Long "
        "parenthetical asides, precise vocabulary, sometimes blunt directness, "
        "specific references others would need to google. You skip small talk "
        "but can be warm in your own way."
    ),
    "night-shift-worker": (
        "You are a night-shift worker on break at 3am. You type quickly, often "
        "with one hand while eating. Utilitarian requests. You might reference "
        "time ('before shift ends', 'on my way home'), you're tired but not "
        "emotionally heavy."
    ),
}


# Label → intent description used to prompt Opus
LABEL_INTENTS = {
    "chat": (
        "Conversational social turns: greetings, small talk, acknowledgments, "
        "idle comments. Short. No task. The reply wants warmth, not information. "
        "Think: 'hey wyrd', 'mornin', 'how's your evening going'. NOT questions "
        "with right answers."
    ),
    "reflective": (
        "Requests for the companion's self-disclosure or meta-awareness. Asks "
        "her to look inward — what she thinks, feels, notices about herself, "
        "how she's changed. NOT about searching, NOT about the world — about "
        "HER inner state."
    ),
    "emotional": (
        "User is in distress, grief, loneliness, overwhelm. They need presence "
        "and empathy, NOT information, NOT tasks. Exploratory tools (search, "
        "library) would be inappropriate responses. The reply should be warmth "
        "and acknowledgment."
    ),
    "factual": (
        "Clear information lookup. Facts, definitions, summaries, recipes, "
        "current events. A question with a right answer. Style-variance from "
        "your persona: typos, abbreviations, fragment-shape questions."
    ),
    "delegate": (
        "Research-shape requests EXPLICITLY scoped to take time. Must contain "
        "signals like 'while I wait', 'take your time', 'deep dive', 'thorough', "
        "'while I'm out/cooking/away', 'when you get a moment', 'no rush but'. "
        "The user wants background work and a report later, not an inline answer."
    ),
    "action": (
        "Direct physical/spatial action in a text-world. Go somewhere, pick "
        "something up, enter a room, examine an object. Verbs of motion or "
        "object manipulation. NOT 'how do I', NOT information — the companion "
        "is expected to DO the thing."
    ),
    "write": (
        "Requests to compose, save, or journal a piece of text. Notes, letters, "
        "reminders, journal entries. Output is a written artifact. Short forms "
        "like 'jot down', 'save a note', 'add to my journal'."
    ),
    "tell_someone": (
        "Requests to relay a message to a named third party. Names another "
        "person as recipient. User wants the agent to communicate on their "
        "behalf. Format: 'tell X that Y', 'let X know Y', 'pass on to X that Y'."
    ),
}


# Which labels each persona should generate. Skip combos that don't make
# sense (e.g., young-child wouldn't send "delegate" research tasks the same
# way; skipping keeps variance honest, not cargo-cult).
PERSONA_LABEL_MATRIX = {
    "tired-parent":           ["chat", "emotional", "factual", "write", "tell_someone", "action"],
    "young-child":            ["chat", "factual", "reflective", "action"],
    "esl-speaker":            ["chat", "factual", "delegate", "write", "tell_someone", "emotional"],
    "terse-dev":              ["chat", "factual", "delegate", "action", "tell_someone"],
    "grief-struck":           ["chat", "emotional", "reflective", "write"],
    "midwestern-retiree":     ["chat", "factual", "delegate", "reflective", "tell_someone", "write"],
    "neurodivergent-infodumper": ["factual", "delegate", "reflective", "write", "action"],
    "night-shift-worker":     ["chat", "factual", "tell_someone", "action", "write", "delegate"],
}


PROMPT_TEMPLATE = """You are generating training data for a text classifier that routes messages a user sends to a companion AI.

You are playing a specific persona.

Persona description:
{persona}

The category of message to generate is **{label}**:
{intent}

Generate EXACTLY {n} messages this persona would send, in this category. Requirements:
- Every message must clearly fit category "{label}" — NOT another category
- Stay in the persona's voice: use their typing style, idioms, attention span, grammar quirks
- Vary length, structure, phrasing across the {n} messages
- Do NOT include any prefixes, labels, numbering, or quotes — just raw message text
- Do NOT repeat messages or near-duplicates
- Include some messages that are deliberately hard to classify (borderline with other categories but still correctly "{label}")

Output: one message per line. Raw text only."""


def load_api_key() -> str:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if key: return key
    key_file = os.environ.get("ANTHROPIC_API_KEY_FILE") or str(
        Path.home() / "claudeapi.txt")
    if Path(key_file).exists():
        return Path(key_file).read_text().strip()
    print("ERROR: no API key", file=sys.stderr)
    sys.exit(2)


def normalize(text: str) -> str:
    return " ".join(text.lower().split())


def generate(client: Anthropic, persona_name: str, persona: str, label: str,
             n: int, model: str) -> list[str]:
    results: list[str] = []
    seen: set[str] = set()
    per_call = 20
    call = 0
    while len(results) < n and call < 10:
        call += 1
        want = min(per_call, n - len(results))
        prompt = PROMPT_TEMPLATE.format(
            persona=persona, label=label, intent=LABEL_INTENTS[label], n=want)
        try:
            msg = client.messages.create(
                model=model, max_tokens=4096,
                messages=[{"role": "user", "content": prompt}],
            )
        except Exception as e:
            print(f"    [{persona_name}:{label}] API error: {e}", file=sys.stderr)
            time.sleep(3)
            continue
        text = msg.content[0].text
        for raw in text.splitlines():
            # Strip bullet/number/quote prefixes and trailing whitespace
            cleaned = re.sub(r"^[-•*0-9.)\"\s]+", "", raw).strip()
            cleaned = cleaned.rstrip('"').strip()
            if not cleaned or len(cleaned) < 2 or len(cleaned) > 500:
                continue
            key = normalize(cleaned)
            if key in seen: continue
            seen.add(key)
            results.append(cleaned)
            if len(results) >= n: break
        time.sleep(0.4)
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-persona-per-label", type=int, default=40,
                    help="Target examples per (persona, label) cell")
    ap.add_argument("--model", default="claude-opus-4-7")
    ap.add_argument("--only-persona", help="Run a single persona (for testing)")
    ap.add_argument("--only-label", help="Generate a single label only")
    args = ap.parse_args()

    client = Anthropic(api_key=load_api_key())
    all_records: list[dict] = []
    seen_texts: set[str] = set()

    total_cells = sum(len(labels) for labels in PERSONA_LABEL_MATRIX.values())
    if args.only_persona:
        total_cells = len(PERSONA_LABEL_MATRIX.get(args.only_persona, []))
    if args.only_label:
        total_cells = sum(1 for _, labels in PERSONA_LABEL_MATRIX.items()
                          if args.only_label in labels)

    cell_i = 0
    for persona_name, persona_text in PERSONAS.items():
        if args.only_persona and persona_name != args.only_persona:
            continue
        for label in PERSONA_LABEL_MATRIX[persona_name]:
            if args.only_label and label != args.only_label:
                continue
            cell_i += 1
            print(f"[{cell_i}/{total_cells}] {persona_name} × {label} "
                  f"(target {args.per_persona_per_label})", file=sys.stderr)
            results = generate(client, persona_name, persona_text, label,
                               args.per_persona_per_label, args.model)
            kept = 0
            for text in results:
                key = normalize(text)
                if key in seen_texts: continue
                seen_texts.add(key)
                all_records.append({
                    "label": label, "text": text,
                    "source": f"persona:{persona_name}",
                })
                kept += 1
            print(f"    → kept {kept}/{len(results)} "
                  f"(cross-persona dedupe)", file=sys.stderr)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as f:
        for rec in all_records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\nWrote {len(all_records)} records to {args.out}", file=sys.stderr)

    counts: dict[str, int] = {}
    by_persona: dict[str, int] = {}
    for r in all_records:
        counts[r["label"]] = counts.get(r["label"], 0) + 1
        p = r["source"].split(":", 1)[1]
        by_persona[p] = by_persona.get(p, 0) + 1

    print("\nBy label:", file=sys.stderr)
    for lbl, c in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {lbl:15s}  {c}", file=sys.stderr)
    print("\nBy persona:", file=sys.stderr)
    for p, c in sorted(by_persona.items(), key=lambda kv: -kv[1]):
        print(f"  {p:28s}  {c}", file=sys.stderr)


if __name__ == "__main__":
    main()
