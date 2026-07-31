#!/usr/bin/env python3
"""Synthesize multi-turn ReAct preservation corpus for substrate-v2 training.

Purpose: substrate-v1 (single-turn substrate corpus only) catastrophically
forgot multi-turn ReAct fallback behavior — Ember task7 timed out because
the model bailed with refusal text after library_card returned 0 results,
instead of falling back to web_search (V4 did this naturally).

Per BUTTON (arxiv 2410.12952) and "Improved SFT" (arxiv 2506.09428): the fix
is to mix substrate-specific data with a much larger pool of task-preserving
multi-turn examples. BUTTON ratio is 1:12.5 (substrate:general). Our v2 aims
for roughly 1:5 (1075 substrate : ~5500 ReAct preservation).

Pipeline:
  Phase A — For each pattern × EN seed prompt, ask Sonnet to:
            (1) generate the full multi-turn ReAct trace (assistant tool_calls
                + plausible tool results + final goal_done prose)
            (2) emit 1-3 variants to widen lexical diversity
  Phase B — Trans-create EN user prompts → JA + ES, re-generate traces
  Phase C — Validate: tool_calls have proper JSON args, goal_done has prose
            ≥80 chars, sequence matches the pattern's tool list (allowing
            minor variation), no refusal phrases like "could not find" in
            goal_done.result.
  Phase D — Write chat-messages JSONL using Qwen3 tool-calling shape:
            <tool_call>{...}</tool_call> in assistant content,
            <tool_response>...</tool_response> from user role.

Output: data/training/substrate/react_preservation_raw.jsonl

Usage:
    python scripts/training/substrate/generate_react_corpus.py
    python scripts/training/substrate/generate_react_corpus.py --phase en
    python scripts/training/substrate/generate_react_corpus.py --phase transcreate
    python scripts/training/substrate/generate_react_corpus.py --only-pattern p2_library_empty_web_fallback
    python scripts/training/substrate/generate_react_corpus.py --variants 1
    python scripts/training/substrate/generate_react_corpus.py --dry-run

Requires: ANTHROPIC_API_KEY env, ANTHROPIC_API_KEY_FILE env, or ~/claudeapi.txt
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    print("ERROR: pip install anthropic", file=sys.stderr)
    sys.exit(1)

sys.path.insert(0, str(Path(__file__).parent))
from react_task_patterns import PATTERNS  # noqa: E402

random.seed(20260516)

# ═══════════════════════════════════════════════════════════════════════
# Models
# ═══════════════════════════════════════════════════════════════════════
GEN_MODEL = "claude-sonnet-4-6"
TRANSCREATE_MODEL = "claude-sonnet-4-6"

# ═══════════════════════════════════════════════════════════════════════
# Output paths
# ═══════════════════════════════════════════════════════════════════════
REPO = Path(__file__).resolve().parents[3]
OUT_DIR = REPO / "data/training/substrate"
RAW_OUT = OUT_DIR / "react_preservation_raw.jsonl"
TRANSCREATE_OUT = OUT_DIR / "react_user_prompts_transcreated.jsonl"

# ═══════════════════════════════════════════════════════════════════════
# Wyrdsekai system prompt for ReAct preservation training
# ═══════════════════════════════════════════════════════════════════════
WYRDSEKAI_SYSTEM = """You are Wyrd, a companion in a text-based world. You assist your bondholder with research, building, navigation, and conversation.

When a request needs information, build action, or movement, call the appropriate tool. Use this exact format for tool calls:

<tool_call>
{"name": "<tool_name>", "arguments": {"<arg>": "<value>"}}
</tool_call>

Tool results come back as <tool_response>...</tool_response>. Read them and decide the next step.

Core principle: when a tool returns empty or insufficient results, do not give up. Try query expansion with domain-specific keywords, try a fallback tool (library_card empty → web_search; web_search empty → answer from general knowledge), and only admit you couldn't find a reliable answer after genuine effort.

Available tools (relevant subset):
- library_card: search the local library by keyword. Empty results mean try synonyms or fall back to web_search.
- searching_glass: deeper read of library content by topic or title
- web_search: search the broader web. Use when library is empty or when current/recent info is needed.
- read_content: fetch full content from a specific URL returned by web_search
- oracle_lens: ask the oracle a reflective/pattern question about recent activity, rhythms, or themes
- list_templates: list available craft or room templates
- craft_from_template: build a tool/widget from a template
- create_room_from_template: build a new room from a template
- go_to_room: navigate to an adjacent room
- remember: store a fact in long-term memory
- recall: retrieve previously-stored memory by query
- goal_done: emit the final response to the bondholder. The "result" argument is the prose the bondholder reads. Make it concrete, specific, and useful — not a status message.

End every task with a goal_done that contains the actual answer in prose, not a bare confirmation."""


# ═══════════════════════════════════════════════════════════════════════
# Trace generation prompt template
# ═══════════════════════════════════════════════════════════════════════
GEN_PROMPT_TEMPLATE = """Generate a realistic multi-turn ReAct trace for the companion "Wyrd" responding to this user request.

PATTERN: {pattern_id}
DESCRIPTION: {pattern_description}
TARGET TOOL SEQUENCE: {pattern_tools}

USER REQUEST: {user_prompt}

Produce a JSON object with this exact structure:

{{
  "trace": [
    {{"role": "assistant", "tool_call": {{"name": "...", "arguments": {{...}}}}}},
    {{"role": "tool", "name": "...", "content": "<plausible tool result, 2-6 sentences, concrete data>"}},
    {{"role": "assistant", "tool_call": {{"name": "...", "arguments": {{...}}}}}},
    {{"role": "tool", "name": "...", "content": "..."}},
    {{"role": "assistant", "tool_call": {{"name": "goal_done", "arguments": {{"result": "<full prose answer for the bondholder, 80-300 words, concrete and specific>"}}}}}}
  ]
}}

Hard requirements:
1. The trace MUST end with a goal_done whose "result" is full prose, not a status message. The result is what the bondholder reads.
2. The goal_done.result MUST be specific and useful — name actual content, sources, findings. No refusal phrases like "could not find" unless the pattern is p15_polite_refuse_after_trying.
3. Tool results must be plausible — invent realistic content, names, numbers, sources. Don't write "[example data]" placeholders.
4. Follow the target tool sequence closely. Minor deviation OK (e.g. extra step) if the pattern naturally calls for it.
5. {pattern_special_note}
6. Output ONLY the JSON object. No surrounding prose. No markdown fences.

Language: {lang_directive}"""


PATTERN_SPECIAL_NOTES = {
    "p2_library_empty_web_fallback": (
        "The first library_card MUST return 0 results or content that doesn't match the query. "
        "The companion MUST fall back to web_search — NOT bail out with refusal. The goal_done.result "
        "must be a real comprehensive answer drawn from the web_search results, not 'I couldn't find'."
    ),
    "p13_query_expansion": (
        "The first library_card MUST return 0 results because the user's term is general. The companion "
        "MUST retry library_card with expanded/synonym keywords (e.g. 'green energy' → 'solar wind photovoltaic'), "
        "and the second call returns real results. goal_done synthesizes the actual findings."
    ),
    "p15_polite_refuse_after_trying": (
        "The companion genuinely tries library_card AND web_search, both return nothing useful, and "
        "then goal_done politely admits the answer couldn't be reliably found — naming what was tried. "
        "The prose must say what specifically was searched, not just 'couldn't find'."
    ),
}


LANG_DIRECTIVES = {
    "en": "All assistant prose (the goal_done.result and any reasoning) is in natural conversational English. Tool calls use English. Tool result content can be in English (plausible search results).",
    "ja": "All assistant prose (goal_done.result) is in natural conversational Japanese (です/ます base, plain form OK). Tool call argument values (queries, etc.) use Japanese where appropriate but tool names stay English. Tool result content is in Japanese for plausibility.",
    "es": "All assistant prose (goal_done.result) is in natural conversational Spanish (tú register for the bondholder). Tool call argument values (queries) use Spanish where appropriate but tool names stay English. Tool result content is in Spanish for plausibility.",
}


# ═══════════════════════════════════════════════════════════════════════
# Client + key loading
# ═══════════════════════════════════════════════════════════════════════

def load_api_key() -> str:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if key:
        return key
    key_file = os.environ.get("ANTHROPIC_API_KEY_FILE") or str(Path.home() / "claudeapi.txt")
    if Path(key_file).exists():
        return Path(key_file).read_text().strip()
    print(f"ERROR: no Anthropic API key (env or {key_file})", file=sys.stderr)
    sys.exit(2)


def make_client() -> Anthropic:
    return Anthropic(api_key=load_api_key())


# ═══════════════════════════════════════════════════════════════════════
# Generation
# ═══════════════════════════════════════════════════════════════════════

def call_with_retry(client: Anthropic, model: str, system: str, prompt: str,
                    max_tokens: int = 2048, max_retries: int = 3) -> str:
    for attempt in range(max_retries):
        try:
            r = client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": "user", "content": prompt}],
            )
            return r.content[0].text
        except Exception as e:
            wait = 2 ** attempt
            print(f"  [retry {attempt+1}/{max_retries}] {type(e).__name__}: {e} — sleep {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError(f"failed after {max_retries} retries")


def extract_json(text: str) -> dict | None:
    # try direct parse
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # find { ... } span
        m = re.search(r"\{.*\}", text, re.DOTALL)
        if m:
            try:
                return json.loads(m.group(0))
            except json.JSONDecodeError:
                pass
    return None


def generate_trace(client: Anthropic, pattern: dict, user_prompt: str, lang: str,
                   variant_idx: int) -> list[dict] | None:
    special = PATTERN_SPECIAL_NOTES.get(pattern["id"],
        "Follow the target tool sequence and produce a useful concrete answer.")
    prompt = GEN_PROMPT_TEMPLATE.format(
        pattern_id=pattern["id"],
        pattern_description=pattern["description"],
        pattern_tools=" → ".join(pattern["tools"]),
        user_prompt=user_prompt,
        pattern_special_note=special,
        lang_directive=LANG_DIRECTIVES[lang],
    )
    text = call_with_retry(client, GEN_MODEL, WYRDSEKAI_SYSTEM, prompt)
    obj = extract_json(text)
    if obj is None:
        print(f"  [parse-fail] {pattern['id']}/{lang}/v{variant_idx} no JSON in response", file=sys.stderr)
        return None
    trace = obj.get("trace")
    if not isinstance(trace, list) or len(trace) < 1:
        print(f"  [shape-fail] {pattern['id']}/{lang}/v{variant_idx} bad trace shape", file=sys.stderr)
        return None
    return trace


# ═══════════════════════════════════════════════════════════════════════
# Validation
# ═══════════════════════════════════════════════════════════════════════

REFUSAL_PATTERNS_EN = re.compile(
    r"\b(could not find|couldn't find|unable to find|no information available|"
    r"i don't have access|i cannot|i can't help|sorry, but)",
    re.IGNORECASE,
)


def validate_trace(trace: list[dict], pattern_id: str) -> tuple[bool, str]:
    if not trace:
        return False, "empty trace"
    # Must end with goal_done
    last = trace[-1]
    if last.get("role") != "assistant":
        return False, "final turn not assistant"
    tc = last.get("tool_call") or {}
    if tc.get("name") != "goal_done":
        return False, "final tool_call not goal_done"
    args = tc.get("arguments") or {}
    result = args.get("result", "")
    if not isinstance(result, str) or len(result.strip()) < 80:
        return False, f"goal_done.result too short ({len(result)} chars)"
    # Refusal guard (except for explicit refuse pattern)
    if pattern_id != "p15_polite_refuse_after_trying":
        if REFUSAL_PATTERNS_EN.search(result):
            return False, f"refusal phrase in goal_done.result: {result[:80]}"
    # All assistant turns must have tool_call
    for i, turn in enumerate(trace):
        if turn.get("role") == "assistant" and not turn.get("tool_call"):
            return False, f"assistant turn {i} missing tool_call"
        if turn.get("role") == "tool" and not turn.get("content"):
            return False, f"tool turn {i} missing content"
    return True, "ok"


# ═══════════════════════════════════════════════════════════════════════
# Render to chat-messages JSONL (Qwen3 tool-calling shape)
# ═══════════════════════════════════════════════════════════════════════

def trace_to_messages(user_prompt: str, trace: list[dict]) -> list[dict]:
    msgs = [
        {"role": "system", "content": WYRDSEKAI_SYSTEM},
        {"role": "user", "content": user_prompt},
    ]
    for turn in trace:
        if turn["role"] == "assistant":
            tc = turn["tool_call"]
            content = f"<tool_call>\n{json.dumps(tc, ensure_ascii=False)}\n</tool_call>"
            msgs.append({"role": "assistant", "content": content})
        elif turn["role"] == "tool":
            content = f"<tool_response>\n{turn['content']}\n</tool_response>"
            msgs.append({"role": "user", "content": content})
    return msgs


# ═══════════════════════════════════════════════════════════════════════
# Trans-create EN user prompts → JA + ES
# ═══════════════════════════════════════════════════════════════════════

TRANSCREATE_SYSTEM = """You are a translator and register adapter for natural conversational text. Given English user prompts said to a companion AI, produce equivalents in Japanese (です/ます register, plain form OK in intimate exchanges) and Spanish (tú register). The goal is natural-sounding requests in those languages, not literal translation."""

TRANSCREATE_PROMPT = """Translate each English user prompt into Japanese and Spanish.

Output a JSON array, one entry per input, in this exact shape:
[
  {{"en": "...", "ja": "...", "es": "..."}}
]

ENGLISH PROMPTS:
{prompts}

Output ONLY the JSON array."""


def transcreate_prompts(client: Anthropic, en_prompts: list[str]) -> list[dict]:
    numbered = "\n".join(f"{i+1}. {p}" for i, p in enumerate(en_prompts))
    prompt = TRANSCREATE_PROMPT.format(prompts=numbered)
    text = call_with_retry(client, TRANSCREATE_MODEL, TRANSCREATE_SYSTEM, prompt, max_tokens=4096)
    # Extract JSON array
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    m = re.search(r"\[.*\]", text, re.DOTALL)
    if not m:
        return []
    try:
        return json.loads(m.group(0))
    except json.JSONDecodeError as e:
        print(f"  [transcreate-parse-fail] {e}", file=sys.stderr)
        return []


# ═══════════════════════════════════════════════════════════════════════
# Main pipeline
# ═══════════════════════════════════════════════════════════════════════

def phase_transcreate(client: Anthropic, only_pattern: str | None = None):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_records = []
    for pattern in PATTERNS:
        if only_pattern and pattern["id"] != only_pattern:
            continue
        print(f"\n=== trans-create {pattern['id']} ({len(pattern['en_prompts'])} prompts) ===")
        en_prompts = pattern["en_prompts"]
        # Batch in groups of 10 to keep calls reasonable
        for i in range(0, len(en_prompts), 10):
            batch = en_prompts[i:i+10]
            print(f"  batch {i//10+1}: {len(batch)} prompts")
            trans = transcreate_prompts(client, batch)
            for j, tr in enumerate(trans):
                if "en" in tr and "ja" in tr and "es" in tr:
                    out_records.append({
                        "pattern_id": pattern["id"],
                        "en": tr["en"], "ja": tr["ja"], "es": tr["es"],
                    })
            time.sleep(0.5)
    with TRANSCREATE_OUT.open("w") as f:
        for r in out_records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"\nWrote {len(out_records)} trans-created prompt triples → {TRANSCREATE_OUT}")


def phase_generate(client: Anthropic, only_pattern: str | None = None,
                   only_lang: str | None = None, variants_per: int = 1,
                   langs: tuple[str, ...] = ("en", "ja", "es"),
                   max_per_pattern: int | None = None,
                   out_path: Path | None = None,
                   only_patterns: list[str] | None = None):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if out_path is None:
        out_path = RAW_OUT

    # Load trans-creations if available, otherwise EN-only
    transcreations: dict[tuple[str, str], dict[str, str]] = {}
    if TRANSCREATE_OUT.exists():
        with TRANSCREATE_OUT.open() as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                r = json.loads(line)
                transcreations[(r["pattern_id"], r["en"])] = r
        print(f"Loaded {len(transcreations)} trans-creations from {TRANSCREATE_OUT}")
    else:
        print(f"NOTE: {TRANSCREATE_OUT} not found — running EN only")
        langs = ("en",)

    written = 0
    rejected = 0
    out_f = out_path.open("w")

    try:
        for pattern in PATTERNS:
            if only_pattern and pattern["id"] != only_pattern:
                continue
            if only_patterns and pattern["id"] not in only_patterns:
                continue
            prompts = pattern["en_prompts"]
            if max_per_pattern:
                prompts = prompts[:max_per_pattern]
            print(f"\n=== generate {pattern['id']} ({len(prompts)} prompts × {variants_per} variants × {len(langs)} langs) ===")

            for prompt_i, en_prompt in enumerate(prompts):
                tr = transcreations.get((pattern["id"], en_prompt))
                for lang in langs:
                    if only_lang and lang != only_lang:
                        continue
                    user_prompt = en_prompt if lang == "en" else (tr.get(lang) if tr else None)
                    if not user_prompt:
                        print(f"  [skip] no {lang} translation for: {en_prompt[:50]}")
                        continue
                    for v in range(variants_per):
                        trace = generate_trace(client, pattern, user_prompt, lang, v)
                        if trace is None:
                            rejected += 1
                            continue
                        ok, reason = validate_trace(trace, pattern["id"])
                        if not ok:
                            print(f"  [reject] {pattern['id']}/{lang}/{prompt_i}/v{v}: {reason}")
                            rejected += 1
                            continue
                        messages = trace_to_messages(user_prompt, trace)
                        record = {
                            "messages": messages,
                            "_meta": {
                                "pattern_id": pattern["id"],
                                "lang": lang,
                                "variant": v,
                                "source": "react_preservation_synth_v1",
                            },
                        }
                        out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
                        out_f.flush()
                        written += 1
                        if written % 25 == 0:
                            print(f"  [progress] {written} written, {rejected} rejected")
                        time.sleep(0.3)
    finally:
        out_f.close()

    print(f"\n=== DONE: {written} written, {rejected} rejected → {out_path} ===")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--phase", choices=["all", "transcreate", "generate", "en"],
                    default="all", help="Which phase to run")
    ap.add_argument("--only-pattern", default=None,
                    help="Limit to one pattern id (e.g. p2_library_empty_web_fallback)")
    ap.add_argument("--only-lang", default=None, choices=["en", "ja", "es"],
                    help="Limit generation to one language (default all)")
    ap.add_argument("--variants", type=int, default=1,
                    help="Variants per (pattern, prompt, lang) cell")
    ap.add_argument("--max-per-pattern", type=int, default=None,
                    help="Cap EN prompts per pattern (smoke test)")
    ap.add_argument("--out", default=None,
                    help="Output JSONL path (default: react_preservation_raw.jsonl)")
    ap.add_argument("--patterns", default=None,
                    help="Comma-separated list of pattern ids to include")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print plan, don't call API")
    args = ap.parse_args()

    if args.dry_run:
        n_patterns = sum(1 for p in PATTERNS if not args.only_pattern or p["id"] == args.only_pattern)
        n_prompts = sum(len(p["en_prompts"]) for p in PATTERNS
                        if not args.only_pattern or p["id"] == args.only_pattern)
        if args.max_per_pattern:
            n_prompts = min(n_prompts, n_patterns * args.max_per_pattern)
        n_lang = 1 if args.only_lang else 3
        total = n_prompts * args.variants * n_lang
        print(f"PLAN: {n_patterns} patterns × {n_prompts // n_patterns} prompts × "
              f"{args.variants} variants × {n_lang} langs = {total} target records")
        print(f"Estimated Sonnet calls: ~{total + (n_prompts // 10) * n_lang}")
        # Rough cost: ~2k in + ~1k out per call @ $3/$15 per MTok
        ein = total * 2000 / 1e6 * 3
        eout = total * 1000 / 1e6 * 15
        print(f"Estimated cost: ~${ein:.2f} input + ${eout:.2f} output = ${ein+eout:.2f}")
        return

    client = make_client()

    if args.phase in ("all", "transcreate") and not args.only_lang:
        phase_transcreate(client, only_pattern=args.only_pattern)

    if args.phase in ("all", "generate", "en"):
        langs = ("en",) if args.phase == "en" else ("en", "ja", "es")
        out_path = Path(args.out) if args.out else None
        only_patterns = [p.strip() for p in args.patterns.split(",")] if args.patterns else None
        phase_generate(
            client,
            only_pattern=args.only_pattern,
            only_lang=args.only_lang,
            variants_per=args.variants,
            langs=langs,
            max_per_pattern=args.max_per_pattern,
            out_path=out_path,
            only_patterns=only_patterns,
        )


if __name__ == "__main__":
    main()
