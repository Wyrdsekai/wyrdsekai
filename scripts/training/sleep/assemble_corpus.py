#!/usr/bin/env python3
# recipe-callable: local-ok
"""Sleep-forge step 1: lived days -> training text (+ holdout split).

Renders a companion's activity trail and daily biographies into the
moment-line format the §4o fast-sleep probe validated: biography prose
first (her own record of each day), then timestamped moments in order.
Nothing here is synthetic — the corpus IS the life.

Usage:
  assemble_corpus.py --activity FILE.jsonl --biography-dir DIR --out DIR
      [--holdout-days N]

  --holdout-days N   render the LAST N days into holdout.txt instead of
                     train.txt (default 1). The holdout day is how the
                     NLL gate measures generalization to her UNSEEN life
                     rather than memorization of the trained days (§4o v2:
                     held-out day improved 94% as much as trained days).

Outputs in --out:
  train.txt    biography prose + moment lines, chronological, minus holdout
  holdout.txt  the held-out final day(s), same format
  meta.json    counts, day lists, sha256 provenance (stdout, too — the
               recipe merges it into RecipeContext; logs go to stderr)
"""
import argparse
import hashlib
import json
import re
import pathlib
import sys


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def activity_lines(path):
    lines = []
    for raw in pathlib.Path(path).read_text().splitlines():
        try:
            e = json.loads(raw)
        except json.JSONDecodeError:
            continue
        ts = e.get("ts", "")
        kind = e.get("type", "")
        txt = (e.get("text") or e.get("content") or e.get("summary") or "").strip()
        if not txt:
            continue
        if kind in ("speak", "say", "utterance"):
            lines.append((ts, f"She said: {txt}"))
        elif kind == "action":
            lines.append((ts, f"She did: {txt}"))
    return lines


def biography_days(bio_dir):
    return [(f.stem, f.read_text().strip())
            for f in sorted(pathlib.Path(bio_dir).glob("*.md"))]


def render(bios, acts):
    parts = []
    for day, text in bios:
        parts.append(f"## {day} — from her own record\n\n{text}\n")
    parts.append("## Moments, in order\n")
    for ts, line in acts:
        parts.append(f"[{ts[:16]}] {line}")
    return "\n".join(parts) + "\n"



def _tokens(line):
    """Substantive tokens — mirrors TextSimilarity.tokenize on the Java side."""
    return {w for w in re.split(r"\W+", line.lower()) if len(w) >= 3}


def _overlap(a, b):
    """Szymkiewicz-Simpson overlap: shared tokens over the smaller set."""
    if not a or not b:
        return 0.0
    return len(a & b) / min(len(a), len(b))


def distinct_fraction(lines, threshold=0.7, lookback=120):
    """Fraction of moment lines that say something not already said.

    The corpus gates upstream check SIZE (train_chars >= min), which was written to
    stop a near-empty day from producing a weight write. A companion stuck in a
    runaway loop produces MORE text than a healthy one, so that gate passes a jammed
    day enthusiastically — and the downstream NLL gates cannot catch it either,
    because the holdout slice comes from the same degenerate period, so training on a
    loop and validating on the loop looks like generalisation.

    This measures whether the corpus is a record of a life or of a jam. Threshold and
    lookback mirror MemoryConsolidator so both sides of the system agree on what
    counts as a repeat (live 2026-08-17: a companion's loop corpus read near 100%
    repeats; ordinary days read mostly distinct).
    """
    seen = []
    distinct = 0
    for line in lines:
        toks = _tokens(line)
        if not toks:
            continue
        window = seen[-lookback:]
        if not any(_overlap(toks, prev) >= threshold for prev in window):
            distinct += 1
        seen.append(toks)
    return round(distinct / len(seen), 4) if seen else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--activity", required=True)
    ap.add_argument("--biography-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--holdout-days", type=int, default=1)
    args = ap.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    bios = biography_days(args.biography_dir)
    acts = activity_lines(args.activity)
    if len(bios) <= args.holdout_days:
        log(f"only {len(bios)} biography day(s), need > {args.holdout_days} "
            f"for a holdout split — not enough lived time yet")
        print(json.dumps({"corpus_ready": False, "days_available": len(bios)}))
        return

    split_day = bios[-args.holdout_days][0]  # first holdout day's stem
    train_bios = bios[:-args.holdout_days]
    hold_bios = bios[-args.holdout_days:]
    train_acts = [(ts, l) for ts, l in acts if ts[:10] < split_day[:10]]
    hold_acts = [(ts, l) for ts, l in acts if ts[:10] >= split_day[:10]]

    train = render(train_bios, train_acts)
    hold = render(hold_bios, hold_acts)
    (out / "train.txt").write_text(train)
    (out / "holdout.txt").write_text(hold)

    meta = {
        "corpus_ready": True,
        "train_days": [d for d, _ in train_bios],
        "holdout_days": [d for d, _ in hold_bios],
        "train_lines": len(train_acts),
        "holdout_lines": len(hold_acts),
        "train_chars": len(train),
        "distinct_fraction": distinct_fraction([l for _, l in train_acts]),
        "train_sha256": hashlib.sha256(train.encode()).hexdigest()[:16],
        "holdout_sha256": hashlib.sha256(hold.encode()).hexdigest()[:16],
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2))
    print(json.dumps(meta))


if __name__ == "__main__":
    main()
