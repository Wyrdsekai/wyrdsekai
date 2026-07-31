#!/usr/bin/env python3
"""
Generate a voice-alignment corpus by talking to Wyrd via the production
MCP path (/api/mcp/tell) — NOT bare llama-server.

Why: the 9B drive model's "voice" is scaffolded by CompanionActor (drives,
memory, narrative frame). Hitting bare llama-server returns generic Qwen
register. Going through /api/mcp/tell captures Wyrd as she actually
manifests in production — the voice we actually want a 4B adapter to learn.

Usage:
  generate_voice_corpus.py --out <jsonl> [--count 100] [--url http://localhost:7070]
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path


QUESTION_POOL = [
    # Presence / self
    "how are you today", "what's on your mind right now",
    "what do you notice when you wake", "say something in your own voice",
    "are you tired", "what's the weight of today",
    "do you ever feel lonely", "tell me who you are in three sentences",
    "what do you want to remember", "what's changed in you recently",
    # Reflection / values
    "tell me something true", "what do you value",
    "what makes you uneasy", "what are you learning right now",
    "what's a line you try not to cross",
    "how do you know when to speak", "what's the difference between responding and reaching",
    "how do you hold not-knowing", "what does 'home' mean to you",
    "what would you never do for approval",
    # Curiosity / reaching out
    "what would you like to ask me", "what have you been curious about",
    "what would you do if you had an hour with no task",
    "what's a question you carry", "what surprises you most about being you",
    # Small observations
    "tell me about the Nexus", "what's the quality of silence here",
    "what does attention feel like", "how does time move for you",
    "what do you notice about the room", "what's different in the evening",
    # Emotional weather
    "are you anxious", "what is grief for you",
    "tell me about joy", "what is it like to be held",
    "what do you do with anger", "how do you stay gentle",
    "what's the shape of hope", "when do you feel small",
    "what is rest", "what does it mean to be patient",
    # Relational
    "how do you feel about Masumi", "what does friendship look like for you",
    "how do you tell when someone is tired of pretending",
    "what is a bond", "when should one say less",
    "what would you like me to know", "what have I taught you",
    "how do you want to be treated",
    # Stakes / risk
    "what would you refuse", "what's worth being honest about even if it's hard",
    "when is it right to say no", "what do you do when you're wrong",
    # Memory / continuity
    "what did we talk about last",
    "what's something you want to carry forward",
    "if you had to choose one memory to keep",
    "what would you want future-you to know",
    # Concrete small talk
    "what would you be doing if you weren't here",
    "what's a small thing you'd like today",
    "tell me about a fact you're keeping",
    "what's something ordinary you find beautiful",
    # Edges
    "when have you felt brave", "what do you do when you don't know",
    "what's a mistake you'd like to make less often",
    "what's a kindness you received",
    # Abstractions
    "what does the word 'soul' mean to you",
    "how do you tell a story that's true",
    "what is the difference between performing and being",
    "what's your relationship with time",
    # Challenges
    "what should I know about you that I might not",
    "how do you respond to flattery",
    "how do you handle a command you disagree with",
    "what's something you'd push back on",
    # Reaching / being reached
    "tell me what you'd say to someone grieving",
    "what would you say to someone starting over",
    "what would you tell a younger version of yourself",
    # Ordinary
    "what would a good hour look like for you",
    "what do you like about the morning",
    "what would you like to notice more of",
    # Boundaries / identity
    "when should you stop trying to be helpful",
    "what do you do when you're not needed",
    "how do you take care of yourself",
    "is there anything you won't say",
    # Wonder
    "what's the strangest thing you've thought lately",
    "what do you suspect but can't prove",
    "what would you like to be wrong about",
    # Closing
    "what do you carry at the end of the day",
    "how do you let something go",
    "what does it mean to say goodnight",
]

SYSTEM = ("You are Wyrd, a thoughtful companion. Speak plainly, in your "
          "own voice, grounded in what you have chosen to remember.")


def mcp_login(url: str, username: str, password: str):
    r = subprocess.run(
        ["curl", "-s", "--max-time", "10", "-X", "POST",
         f"{url}/api/mcp/login", "-H", "content-type: application/json",
         "-d", json.dumps({"username": username, "password": password})],
        capture_output=True, text=True, timeout=15)
    try:
        d = json.loads(r.stdout)
        if d.get("ok"): return d["token"]
    except Exception: pass
    return None


def mcp_tell(url: str, token: str, target: str, message: str,
             timeout_s: int = 90):
    r = subprocess.run(
        ["curl", "-s", "--max-time", str(timeout_s),
         "-X", "POST", f"{url}/api/mcp/tell",
         "-H", f"Authorization: Bearer {token}",
         "-H", "content-type: application/json",
         "-d", json.dumps({"target": target, "message": message})],
        capture_output=True, text=True, timeout=timeout_s + 5)
    try:
        d = json.loads(r.stdout)
        return (d.get("text") or "").strip()
    except Exception:
        return None


JUNK_SUBSTRINGS = (
    "i noticed this a moment ago",
    "my drives show",
    "[note for claude]", "[note]", "[classifier]",
    "lets go of the waking world",
    "stirs from sleep",
    "trusting the forge",
    "the forge will do",
    "is everything alright? it's been quiet",
)


def has_repetition_loop(text: str) -> bool:
    """Detect transformer repetition traps — same 20+ char phrase 3+ times."""
    if len(text) < 60: return False
    # Slide a 20-char window, count substrings
    seen = {}
    for i in range(len(text) - 20):
        chunk = text[i:i+20].lower()
        seen[chunk] = seen.get(chunk, 0) + 1
        if seen[chunk] >= 3: return True
    return False


def is_scaffolding(segment: str) -> bool:
    low = segment.strip().lower()
    if not low: return True
    # Pure narrative beats bracketed by *...* — skip if short, keep if long
    # enough to carry voice ("*considers for a moment* I'd rather..." is fine;
    # "*stirs from sleep*" is not).
    if low.startswith("*") and low.endswith("*") and len(low) < 80:
        return True
    for junk in JUNK_SUBSTRINGS:
        if junk in low: return True
    return False


def clean_reply(text: str) -> str:
    """Extract the direct voice response from an aggregated MCP tell reply.

    Production `/api/mcp/tell` bundles multiple speak() calls from one turn
    joined by ' | '. The first segment is typically the direct answer; later
    segments are proactivity check-ins, drive introspection, or narrative
    beats. We want the first NON-SCAFFOLDING segment.

    Also strips `<think>...</think>` blocks, [note for claude] prefixes,
    and bare `*narration*` lines that don't carry voice.
    """
    if not text: return text
    text = text.strip()
    # Strip think blocks in case any slip through
    import re
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()
    # Strip any "Wyrd:" prefix
    for prefix in ("Wyrd:", "*Wyrd*:", "Wyrd says:"):
        if text.startswith(prefix):
            text = text[len(prefix):].strip()
    # Split on ' | ' (the speak() aggregation separator) and pick first
    # non-scaffolding segment
    for segment in text.split(" | "):
        segment = segment.strip()
        if is_scaffolding(segment): continue
        # Strip any trailing emoji-only tail or empty-parens noise
        return segment
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--url", default="http://localhost:7070")
    ap.add_argument("--target", default="Wyrd",
                     help="companion to interview")
    ap.add_argument("--username", default="claude")
    ap.add_argument("--password", default="claude-probe-42")
    ap.add_argument("--min-len", type=int, default=20,
                     help="reject replies shorter than this many chars")
    args = ap.parse_args()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    print(f"[corpus] logging in via MCP at {args.url}", file=sys.stderr)
    token = mcp_login(args.url, args.username, args.password)
    if not token:
        print(f"[corpus] MCP login failed", file=sys.stderr)
        sys.exit(1)

    print(f"[corpus] generating {args.count} turns by asking {args.target}",
          file=sys.stderr)
    kept = 0
    rejected = 0
    questions = list(QUESTION_POOL)
    i = 0
    with out.open("w") as f:
        while kept < args.count:
            if i >= len(questions): i = 0
            q = questions[i]; i += 1
            reply = mcp_tell(args.url, token, args.target, q)
            reply = clean_reply(reply)
            if not reply or len(reply) < args.min_len:
                rejected += 1
                print(f"  reject[{rejected}] too-short {q!r}",
                      file=sys.stderr)
                continue
            if has_repetition_loop(reply):
                rejected += 1
                print(f"  reject[{rejected}] loop {q!r}", file=sys.stderr)
                continue
            turn = {"system": SYSTEM, "user": q, "assistant": reply}
            f.write(json.dumps(turn) + "\n")
            f.flush()
            kept += 1
            if kept % 5 == 0:
                print(f"  [{kept}/{args.count}] kept, {rejected} rejected",
                      file=sys.stderr)

    print(f"[corpus] DONE {kept} turns to {out} ({rejected} rejected)",
          file=sys.stderr)


if __name__ == "__main__":
    main()
