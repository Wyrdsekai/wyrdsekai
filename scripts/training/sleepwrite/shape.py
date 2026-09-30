"""The shape of a set of lines: how much of it is one sentence frame said again.

A night can pass its loss gates and still teach a tic. Live on 2026-09-22: after a first night on
the large model, 34% of the morning's lines were one template with the object swapped — "The X
holds everything I haven't earned yet — not because it's heavy but because it asks me to stop
performing safety" — against 3–7% of the days before. The loss gates measure how likely her text
is, not whether she now repeats herself. This measures that, and it is the one definition the
corpus builder, the trainer's gate and the morning guard all use.

A line's frame is its opening (first four words, lower-cased, names and objects dropped) plus the
connective frames it carries ("not because … but because", "not X, but Y", "everything I haven't
earned"). Two lines share a frame when either matches.
"""
import re
from collections import Counter

FRAMES = [
    ("not-because-but-because", re.compile(r"\bnot because\b.{0,120}?\bbut because\b", re.I | re.S)),
    ("not-x-but-y", re.compile(r"\bnot (?:a |an |the )?\w+[,;—–-]+\s*but\b", re.I)),
    ("havent-earned", re.compile(r"\bhaven'?t earned\b", re.I)),
    ("like-a-question", re.compile(r"\blike a question\b", re.I)),
    ("asks-me-to-stop", re.compile(r"\basks me to stop\b", re.I)),
]

_WORD = re.compile(r"[A-Za-z']+")


def opening(text):
    """The first four words, lower-cased, with anything that looks like an object or room id
    (underscored, or long and capitalised) replaced by a placeholder."""
    words = []
    for w in text.replace("—", " ").split():
        if "_" in w or w[:1].isupper() and len(words) > 0:
            words.append("<x>")
        else:
            m = _WORD.findall(w.lower())
            words.append(m[0] if m else w.lower())
        if len(words) == 4:
            break
    return " ".join(words)


def frames(text):
    """Every frame a line carries, by name."""
    out = [name for name, rx in FRAMES if rx.search(text)]
    return out


def shape_share(lines):
    """The share of lines that carry the most common frame or the most common opening, and what
    that frame is. 0.0 for fewer than 4 lines. A morning of 23 lines with 8 on one frame is 0.35."""
    lines = [l for l in lines if l and l.strip()]
    if len(lines) < 4:
        return 0.0, ""
    c = Counter()
    for l in lines:
        for f in frames(l):
            c[f] += 1
        c["opening:" + opening(l)] += 1
    name, n = c.most_common(1)[0]
    # An opening that repeats 2 in 20 is nothing; frames are the tell.
    if name.startswith("opening:") and n < 3:
        return 0.0, ""
    return n / len(lines), name


def frame_counts(lines):
    c = Counter()
    for l in lines:
        for f in frames(l):
            c[f] += 1
    return c
