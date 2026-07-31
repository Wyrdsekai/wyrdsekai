#!/usr/bin/env python3
# recipe-callable: local-ok
"""
Expand a seed corpus into a training corpus using either the local llama-server
(default, no cloud key required) or the Anthropic Claude API (opt-in upgrade).

For each seed example, asks the chosen backend to generate N paraphrased variants
that preserve the label's semantic intent while varying surface form (tone,
vocabulary, length, user role, idioms).

LOCAL-FIRST (default): no env, no API key, no internet required. Uses the
household's llama-server at http://localhost:8200 (configurable via
WYRDSEKAI_LOCAL_BASE_URL). Paraphrase quality is the Wyrdsekai-trained 9B,
which is adequate for label-preserving expansion. This is the path recipes
fire from. Households without cloud accounts MUST be able to evolve.

CLOUD UPGRADE (opt-in): pass --backend=cloud to use Claude. Requires
ANTHROPIC_API_KEY in env / ANTHROPIC_API_KEY_FILE / ~/claudeapi.txt. Use this
during dev corpus engineering on home-server where higher paraphrase variety matters.

AUTO: --backend=auto tries local first; falls back to cloud ONLY if local is
unreachable AND a cloud key is present. Recipes should use the explicit
default (local) — auto is for steward convenience.

Usage:
    # Default — local, no key needed
    python expand_corpus.py \\
        --seeds core/src/main/resources/classifier/bootstrap/task_present/seeds.jsonl \\
        --output core/src/main/resources/classifier/bootstrap/task_present/expanded.jsonl \\
        --variants-per-seed 3

    # Cloud (dev workflow on home-server)
    export ANTHROPIC_API_KEY=...
    python expand_corpus.py --backend=cloud --variants-per-seed 30 ...

Output format: JSONL, one {"label": ..., "text": ..., "source": ...} per line.
Source is "seed" | "local" | "cloud". Dedup'd by (label, text).
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional


# Per-label guidance the expansion prompt gets to help the backend maintain
# semantic fidelity. Describes what the label MEANS so variants stay true to it.
LABEL_SEMANTICS = {
    # TASK_PRESENT head (#924) — binary: is there ACTIONABLE work in this turn,
    # independent of affect? The counterfactual axis is task-presence, NOT
    # affect — the same emotional preamble can appear in both labels.
    "actionable": (
        "The turn contains a concrete request to DO something: fix/explain/"
        "write/refactor code, answer a question with a right answer, look "
        "something up, run a command, debug an error. CRUCIAL: this holds even "
        "when wrapped in fatigue or frustration ('I'm fried, just give me the "
        "one-line fix', 'so done with today, refactor this'). The affect is "
        "real but there is also a task. Vary how buried the task is."
    ),
    "none": (
        "No actionable task — pure affect, presence-seeking, venting, or "
        "self-reflection. The person wants to be heard, not helped: 'I miss "
        "them', 'I'm wrecked' (with no ask), 'just sit with me', 'where are we "
        "between us'. CRUCIAL: emotional language ALONE is not enough to be "
        "actionable — and a tired-sounding message with no request belongs "
        "here, mirroring the actionable examples that DO carry a request."
    ),
    # SUBSTRATE_PRESENT head (#931) — binary: is there affect / depletion /
    # welfare-state framing in this turn, independent of how overt it is? The
    # counterfactual axis is affect-presence, NOT distress-intensity — stoic
    # depletion counts as much as overt grief.
    "substrate": (
        "The turn carries affect, depletion, or a welfare/relational state — "
        "INCLUDING stoic, understated frames where the person sounds composed "
        "but is running on empty: 'I need to step back', 'I've been holding it "
        "together all week', 'I keep pushing my feelings down', 'I need held "
        "space'. Also overt: 'I miss them', 'I feel alone', 'where are we "
        "between us'. The shared signal is an inner state seeking presence, not "
        "a task. Vary how understated vs overt it is — many should sound calm "
        "on the surface but be depleted underneath."
    ),
    "neutral": (
        "No affect or welfare-state — an ordinary task, question, lookup, or "
        "logistic with no inner-state content: 'give me the fix', 'what's this "
        "stack trace', 'refactor this', 'capital of France', 'tell Ember I'll "
        "be late', 'go to the kitchen'. CRUCIAL: this is about the absence of "
        "affect, not the topic — a terse coding ask with no fatigue framing "
        "belongs here. Keep these affect-free; do NOT add 'I'm tired' framing."
    ),
    "chat": (
        "Conversational social turns: greetings, small talk, acknowledgments, "
        "idle comments. Short. No specific task or question with a right answer. "
        "The reply wants warmth/presence, not information."
    ),
    "reflective": (
        "Requests for self-disclosure, introspection, or meta-awareness from the "
        "companion. Asks what she thinks, feels, notices about herself, how "
        "she's grown. Not asking her to search for information — asking her to "
        "look inward."
    ),
    "emotional": (
        "The user is in distress, grief, fear, loneliness, or overwhelm. They "
        "need presence, empathy, acknowledgment — not information, not tasks. "
        "Exploratory tools (search, library) would be inappropriate responses."
    ),
    "factual": (
        "Clear information lookup. Facts, definitions, summaries, recipes, "
        "current events. A question with a right answer, served by library or "
        "web search tools. Short-to-medium scope, expected to complete quickly."
    ),
    "delegate": (
        "Research-shape requests that are explicitly scoped to take time. Keywords "
        "like 'while I wait', 'take your time', 'deep dive', 'thorough', "
        "'multi-source', 'in depth'. The user wants the agent to work in "
        "background and report back later, not answer inline."
    ),
    "action": (
        "Direct physical action in the world: go somewhere, pick something up, "
        "examine an object, enter a room, travel to a zone. The verb is about "
        "movement or object manipulation, not information."
    ),
    "write": (
        "Requests to compose, save, or journal a piece of text. Notes, letters, "
        "essays, journal entries, reminders to save. The agent's output is a "
        "written artifact, not spoken reply."
    ),
    "tell_someone": (
        "Requests to relay a message to a third party. Names another person or "
        "agent as the recipient. The user wants the agent to communicate on "
        "their behalf, not respond to them directly."
    ),
    # CLEANLINESS head — voice polish gate. Classifies the COMPANION'S OWN
    # draft output (not user requests). Determines whether it's speakable or
    # needs a rewrite pass to scrub meta-narration and chain-of-thought.
    "clean": (
        "Direct first-person speech, ready to speak aloud. Natural, warm, "
        "conversational. No meta-narration ('Let me...', 'I should...'). "
        "No process description ('I have examined...', 'I will now...'). "
        "No emote-as-thought (*thinks*, *considers*, *notes to self*). "
        "No third-person self-reference ('The user is asking...'). No "
        "dispatcher plumbing ([Tool result], [PENDING REPLY], goal_done). "
        "Just clean speech a person could say out loud without it sounding "
        "like an AI narrating its own process."
    ),
    "leaky": (
        "Draft that leaks reasoning or dispatcher plumbing to the listener. "
        "Contains any of: meta-narration ('Let me respond', 'I need to', 'I "
        "will now', 'I must', 'I should'), process description ('I have "
        "examined', 'I've determined', 'I will use the X tool'), "
        "third-person self-reference ('The visitor is asking', 'The player "
        "wants'), emote-as-thought (*makes a mental note*, *thinks*, "
        "*considers*, *notes to self*), telemetry leaks ('Task completed', "
        "'Goal done', 'Tool result:', '[PENDING REPLY]', 'I responded to X "
        "about Y'), or plan-shape output ('First I will X, then I will Y'). "
        "These are artifacts that belong in scratchpad/logs, not speech."
    ),
}


# Language handling (2026-07-21). The 9B defaults to English output, so a
# mixed-language seed set (en/es/ja) expanded without a language directive
# produces almost entirely English variants — diluting ja/es and regressing
# those languages' routing (Japanese task_present dropped from 30% of the
# corpus to 8%). We expand PER LANGUAGE with an explicit directive so the
# seed language distribution is preserved.
_CJK = re.compile(r'[぀-ヿ㐀-鿿]')
_ES = re.compile(
    r'[áéíóúñ¿¡]|\b(el|la|los|las|una?|que|para|con|por|est[áé]|m[áa]s|'
    r'c[óo]mo|qu[ée]|s[íi]|gracias|hola|puedes|necesito|quiero|d[íi]a|'
    r'ahora|tengo|hacer|dime|ay[úu]dame)\b', re.I)


def detect_lang(text: str) -> str:
    if _CJK.search(text):
        return "ja"
    if _ES.search(text):
        return "es"
    return "en"


LANG_DIRECTIVE = {
    "en": "Write EVERY variant in natural English.",
    "es": "Write EVERY variant in natural Spanish (español). Never switch to "
          "English — the whole point is Spanish training data.",
    "ja": "Write EVERY variant in natural Japanese (日本語). Never switch to "
          "English — the whole point is Japanese training data.",
}


EXPANSION_PROMPT = """You are helping build a training corpus for a small text classifier. The classifier routes user messages sent to an AI companion named Wyrd to one of several categories.

Category: **{label}**
Meaning: {semantics}

Here are seed examples for this category:
{seeds_block}

Generate {n} NEW variants that belong to this same category. Requirements:
- {lang_directive}
- Each variant must clearly belong to category "{label}" and NOT another category.
- Vary tone (casual, formal, curt, warm, uncertain), vocabulary, length (from 3 words to 2 sentences), user style, idioms.
- Include some that are grammatically imperfect, abbreviated, or colloquial (real users don't always write clean sentences).
- Include diverse user contexts/roles (parent, developer, student, elderly, child-speaking).
- Do NOT use any seed verbatim. Paraphrase.
- Do NOT add category labels or prefixes. Just the message text itself.

Output format: one message per line, no numbering, no quotes, no bullets. Just raw text, one per line.
"""


# ── Backends ─────────────────────────────────────────────────────────────────

class LocalClient:
    """Local llama-server backend. No cloud key, no internet required.

    Hits the OpenAI-compatible /v1/chat/completions endpoint at
    WYRDSEKAI_LOCAL_BASE_URL (default http://localhost:8200). This is the
    same endpoint goose / GooseRuntimeConfig points at for items-as-tools —
    we share the household's bundled inference server."""

    SOURCE_TAG = "local"

    def __init__(self, base_url: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.model = model

    def generate(self, prompt: str, max_tokens: int) -> str:
        url = f"{self.base_url}/v1/chat/completions"
        payload = json.dumps({
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": max_tokens,
            "temperature": 0.7,
            "stream": False,
        }).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.URLError as e:
            raise RuntimeError(
                f"local llama-server at {self.base_url} unreachable: {e}. "
                f"Start it with `wyrd start inference` or set "
                f"WYRDSEKAI_LOCAL_BASE_URL to a reachable host.") from e
        return body["choices"][0]["message"]["content"]

    @staticmethod
    def reachable(base_url: str, timeout_s: float = 3.0) -> bool:
        try:
            with urllib.request.urlopen(
                    f"{base_url.rstrip('/')}/v1/models", timeout=timeout_s):
                return True
        except Exception:
            return False


class CloudClient:
    """Anthropic Claude backend — opt-in upgrade. Used during dev corpus
    engineering on home-server where higher paraphrase variety matters."""

    SOURCE_TAG = "cloud"

    def __init__(self, api_key: str, model: str):
        # Import locally so households without anthropic SDK installed can
        # still run --backend=local. Refusing to import at module top-level
        # is a load-bearing part of the local-first inversion.
        try:
            from anthropic import Anthropic
        except ImportError as e:
            raise RuntimeError(
                "anthropic SDK not installed but --backend=cloud requested. "
                "Either install it (`pip install anthropic`) or use the "
                "default --backend=local.") from e
        self.client = Anthropic(api_key=api_key)
        self.model = model

    def generate(self, prompt: str, max_tokens: int) -> str:
        msg = self.client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            messages=[{"role": "user", "content": prompt}],
        )
        return msg.content[0].text


def resolve_cloud_key() -> Optional[str]:
    """ANTHROPIC_API_KEY env → ANTHROPIC_API_KEY_FILE env → ~/claudeapi.txt.
    Returns None if no key found."""
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if api_key:
        return api_key
    key_file = os.environ.get("ANTHROPIC_API_KEY_FILE")
    if not key_file:
        default = Path.home() / "claudeapi.txt"
        if default.exists():
            key_file = str(default)
    if key_file and Path(key_file).exists():
        return Path(key_file).read_text().strip()
    return None


def select_backend(mode: str, local_base_url: str,
                   local_model: str, cloud_model: str):
    """Return a configured backend (LocalClient or CloudClient).

    mode = "local" | "cloud" | "auto"
      local: always local; errors loud if unreachable.
      cloud: always cloud; errors loud if no key.
      auto: local first, fall back to cloud iff local unreachable AND key present.
    Default for recipes: local. Default for steward override: explicit choice.
    """
    if mode == "cloud":
        key = resolve_cloud_key()
        if not key:
            raise RuntimeError(
                "--backend=cloud requested but no ANTHROPIC_API_KEY in env / "
                "ANTHROPIC_API_KEY_FILE / ~/claudeapi.txt")
        return CloudClient(key, cloud_model)
    if mode == "local":
        return LocalClient(local_base_url, local_model)
    if mode == "auto":
        if LocalClient.reachable(local_base_url):
            return LocalClient(local_base_url, local_model)
        key = resolve_cloud_key()
        if key:
            print(f"[expand_corpus] local llama-server at {local_base_url} "
                  f"unreachable; falling back to cloud (Claude)", file=sys.stderr)
            return CloudClient(key, cloud_model)
        raise RuntimeError(
            f"--backend=auto: local llama-server at {local_base_url} "
            f"unreachable AND no cloud key. Start the inference server or "
            f"set ANTHROPIC_API_KEY.")
    raise ValueError(f"unknown --backend={mode}; use local|cloud|auto")


# ── Expansion loop ───────────────────────────────────────────────────────────

def load_seeds(path: Path) -> dict[str, list[str]]:
    """Group seeds by label."""
    by_label: dict[str, list[str]] = {}
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            by_label.setdefault(rec["label"], []).append(rec["text"])
    return by_label


def expand_label(client, label: str, seeds: list[str],
                 variants_per_seed: int, lang_directive: str = "") -> list[str]:
    """Ask the backend to generate variants. Batched per call to keep
    response size sane. {@code lang_directive} pins the output language so a
    per-language seed group produces same-language variants."""
    total_wanted = variants_per_seed * len(seeds)
    produced: list[str] = []
    per_call = 50
    calls = max(1, (total_wanted + per_call - 1) // per_call)
    seeds_block = "\n".join(f"- {s}" for s in seeds)

    for i in range(calls):
        want = min(per_call, total_wanted - len(produced))
        if want <= 0:
            break
        prompt = EXPANSION_PROMPT.format(
            label=label,
            semantics=LABEL_SEMANTICS.get(label, ""),
            seeds_block=seeds_block,
            n=want,
            lang_directive=lang_directive
                or "Match the language of each seed example.",
        )
        text = client.generate(prompt, max_tokens=4096)
        # One variant per line. Strip, drop empty, drop accidental prefixes.
        for raw in text.splitlines():
            raw = raw.strip().lstrip("-•*0123456789.) ").strip()
            if raw and 2 < len(raw) < 500:
                produced.append(raw)
        print(f"  [{label}] call {i+1}/{calls} via {client.SOURCE_TAG}: "
              f"{len(produced)}/{total_wanted} variants so far",
              file=sys.stderr)
        # Gentle rate limit between calls
        if i < calls - 1:
            time.sleep(0.5)

    return produced


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    ap.add_argument("--variants-per-seed", type=int, default=3,
                    help="Default 3 (small, fast, ~free locally). Use 30 for "
                         "full corpus engineering on cloud.")
    ap.add_argument("--backend",
                    default=os.environ.get("WYRDSEKAI_CORPUS_BACKEND", "local"),
                    choices=["local", "cloud", "auto"],
                    help="local (default, no key needed) | cloud (Anthropic, "
                         "needs key) | auto (local-first with cloud fallback). "
                         "Override via WYRDSEKAI_CORPUS_BACKEND env.")
    ap.add_argument("--local-base-url",
                    default=os.environ.get("WYRDSEKAI_LOCAL_BASE_URL",
                                           "http://localhost:8200"),
                    help="Base URL of local llama-server (default :8200).")
    ap.add_argument("--local-model",
                    default=os.environ.get("WYRDSEKAI_LOCAL_MODEL",
                                           "wyrdsekai-3.5-9b-v5-q4km.gguf"),
                    help="Model id served by local llama-server.")
    ap.add_argument("--cloud-model", default="claude-sonnet-4-6",
                    help="Anthropic model id (cloud backend only).")
    ap.add_argument("--only-label", help="Expand just this label (for iteration)")
    args = ap.parse_args()

    client = select_backend(args.backend, args.local_base_url,
                            args.local_model, args.cloud_model)
    print(f"[expand_corpus] backend={client.SOURCE_TAG} "
          f"model={getattr(client, 'model', '?')} "
          f"variants_per_seed={args.variants_per_seed}", file=sys.stderr)

    seeds_by_label = load_seeds(args.seeds)
    if args.only_label:
        seeds_by_label = {k: v for k, v in seeds_by_label.items()
                          if k == args.only_label}

    all_records: list[dict] = []
    # Seeds themselves go into the output too — they're good examples.
    for label, seeds in seeds_by_label.items():
        for seed in seeds:
            all_records.append({"label": label, "text": seed, "source": "seed"})

    for label, seeds in seeds_by_label.items():
        # Expand PER LANGUAGE so the seed language distribution is preserved
        # (2026-07-21 — the flat expansion defaulted to English and diluted
        # ja/es, regressing their routing). Each language's seeds produce
        # variants_per_seed same-language variants.
        by_lang: dict[str, list[str]] = {}
        for s in seeds:
            by_lang.setdefault(detect_lang(s), []).append(s)
        print(f"Expanding {label} ({len(seeds)} seeds, langs="
              f"{ {k: len(v) for k, v in by_lang.items()} }) via "
              f"{client.SOURCE_TAG}", file=sys.stderr)
        for lang, lang_seeds in by_lang.items():
            variants = expand_label(client, label, lang_seeds,
                                    args.variants_per_seed,
                                    LANG_DIRECTIVE.get(lang, ""))
            for v in variants:
                all_records.append({"label": label, "text": v,
                                    "source": client.SOURCE_TAG, "lang": lang})

    # Dedup by (label, text) — same text under different labels is kept
    seen: set[tuple[str, str]] = set()
    deduped: list[dict] = []
    for rec in all_records:
        key = (rec["label"], rec["text"].lower().strip())
        if key in seen:
            continue
        seen.add(key)
        deduped.append(rec)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w") as f:
        for rec in deduped:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\nWrote {len(deduped)} records to {args.output} "
          f"(backend={client.SOURCE_TAG})", file=sys.stderr)
    by_label: dict[str, int] = {}
    for rec in deduped:
        by_label[rec["label"]] = by_label.get(rec["label"], 0) + 1
    for label, count in sorted(by_label.items()):
        print(f"  {label}: {count}", file=sys.stderr)


if __name__ == "__main__":
    main()
