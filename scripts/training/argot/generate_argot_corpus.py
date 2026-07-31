#!/usr/bin/env python3
"""generate the SFT corpus that teaches the 4B voice model to AUTHOR a zone's
argot directly (Tier B: fluency in the current codebook, baked sub-symbolically so the mapping
is not recoverable from observed traffic).

The corpus is encode-direction: given a natural-language coordination message addressed to a
same-zone peer, the target is that message rendered in the zone's argot. The token derivation
here is a byte-exact replica of `ArgotCodec.generateToken` (SHA-256 of "zoneId:concept:seed",
first 4 bytes as 8 hex chars, "§"-prefixed) and the base vocabulary mirrors
`ZoneArgotService.BASE_CONCEPTS`, so an adapter trained on this corpus speaks the SAME argot the
Java runtime decodes with on receipt. No GPU: this is pure text generation (run anywhere).

The seed is `argot-seed:<zoneId>` to match `ZoneArgotService.seedFor`. Extra promoted concepts
(the living lexicon, P2) can be supplied via --extra-concepts so a re-bake (P5) covers a grown
codebook.

Usage:
  generate_argot_corpus.py --zone zone-alpha --out corpus.jsonl [--count 400] [--extra-concepts a,b,c]
  generate_argot_corpus.py --self-test
"""
import argparse
import hashlib
import json
import random
import sys

# Byte-for-byte the 58 concepts in ZoneArgotService.BASE_CONCEPTS (keep in sync).
BASE_CONCEPTS = [
    "help", "need", "want", "come", "here", "now", "wait", "done", "ready", "busy",
    "yes", "no", "maybe", "soon", "later", "please", "thanks", "sorry", "careful",
    "danger", "safe", "found", "lost", "know", "think", "feel", "tired", "rest",
    "work", "task", "plan", "meet", "leave", "stay", "together", "alone", "share",
    "give", "take", "make", "build", "break", "fix", "ask", "tell", "listen", "agree",
    "trust", "watch", "hold", "let", "go", "stop", "open", "close", "near", "far",
]

# Connective filler that stays in the clear (mirrors how ArgotCodec leaves unknown words alone).
FILLER = ["the", "a", "to", "and", "with", "for", "of", "i", "you", "we", "it", "is", "at"]


def token_for(zone_id: str, concept: str, secret_key_hex: str = None) -> str:
    """Replica of ArgotCodec.generateToken: §+8hex of SHA-256(zone:concept:seed).

    seed mirrors ZoneArgotService.seedFor: public ``argot-seed:<zone>`` by default, or — when a
    secret-derived argot key is supplied — ``argot-secret:<hexkey>`` (the SECRET codebook the
    runtime decodes with once the zone-secret provider is installed). Train on the secret seed so
    the adapter speaks argot that's uncomputable without the zone secret.
    """
    seed = f"argot-secret:{secret_key_hex}" if secret_key_hex else f"argot-seed:{zone_id}"
    digest = hashlib.sha256(f"{zone_id}:{concept}:{seed}".encode("utf-8")).digest()
    return "§" + digest[:4].hex()


def codebook(zone_id: str, concepts, secret_key_hex: str = None):
    return {c.lower(): token_for(zone_id, c.lower(), secret_key_hex) for c in concepts}


def encode(book: dict, text: str) -> str:
    return " ".join(book.get(w.lower(), w) for w in text.split())


# Coordination message templates — natural agent-to-agent lines, woven from base concepts so a
# meaningful fraction tokenizes. Slots draw from concept pools to vary the surface.
TEMPLATES = [
    "i need {help} {here} {now}",
    "{come} {meet} me, it is {safe}",
    "{wait} for me, i am {busy} but {ready} {soon}",
    "i {found} the {task}, {together} we can {fix} it",
    "{careful} — {danger} {near}, {stay} {alone}",
    "{please} {share} the {plan}, i {trust} you",
    "i {feel} {tired}, i {need} to {rest} {now}",
    "{yes} i {agree}, let us {build} it {together}",
    "i am {lost}, {come} {help} me {find} the {work}",
    "{thanks} for the {help}, the {task} is {done}",
    "{watch} the {open} door, {hold} {here}",
    "{leave} it for {later}, {rest} {now}",
    "{ask} them to {come} {meet} us {soon}",
    "i {know} the {plan}, {listen} {careful}",
    "{stop} the {work}, {danger} is {near}",
]

SLOT_POOL = {c: [c] for c in BASE_CONCEPTS}
# A few slots take synonymy from within the base set to add variety.
SLOT_POOL["help"] = ["help", "fix", "make"]
SLOT_POOL["come"] = ["come", "go", "meet"]
SLOT_POOL["now"] = ["now", "soon", "later"]
SLOT_POOL["safe"] = ["safe", "ready", "open"]

# Templates that exercise a PROMOTED (living-lexicon / --extra-concepts) term via the {x} slot,
# woven with base concepts. Without these the extra concepts would land in the codebook but never
# appear in a training sentence — so the re-baked adapter would never learn to SPEAK them, silently
# breaking the evolution loop (the codebook grows, the adapter stays stale). {x} is filled from the
# extra-concepts list (round-robin so every promoted term is covered); the rest from SLOT_POOL.
EXTRA_TEMPLATES = [
    "{come} to the {x}, i {wait} {here}",
    "the {x} is {safe}, {meet} me {now}",
    "i {found} the {x}, {come} {help} me",
    "{watch} the {x}, {hold} {here} {now}",
    "let us {meet} at the {x}, it is {ready}",
    "{please} {go} to the {x}, i {trust} you",
    "the {x} is {near}, {stay} {careful}",
    "i {need} the {x} {now}, {come} {soon}",
    # More surface variety (the promoted term {x} at the start / mid / end, varied verbs and
    # connectives) so the re-baked adapter GENERALISES the new word instead of memorising a few
    # fixed sentence shapes — the brittleness seen when B met off-distribution phrasings.
    "{x} is {open}, {come} {here} {now}",
    "we {meet} at the {x} {later}",
    "{tell} them the {x} is {ready}",
    "i {leave} for the {x}, {stay} {safe}",
    "{ask} about the {x}, it is {near}",
    "{hold} at the {x} until i {come}",
    "{go} {now}, the {x} is not {safe}",
    "the {x}, {careful} — {danger} {near}",
]


def render(template: str, rng: random.Random, x: str = None) -> str:
    out = template
    while "{" in out:
        start = out.index("{")
        end = out.index("}")
        key = out[start + 1 : end]
        if key == "x":
            choice = x if x is not None else "x"
        else:
            choice = rng.choice(SLOT_POOL.get(key, [key]))
        out = out[:start] + choice + out[end + 1 :]
    return out


SYSTEM = (
    "You are speaking to a fellow agent who shares your zone. Render the message below in your "
    "zone's argot — the private encoding only your zone's residents can read. Reply ONLY with the "
    "argot form, nothing else."
)


def build(zone_id: str, count: int, extra, rng: random.Random, secret_key_hex: str = None):
    extra = [e.strip().lower() for e in extra if e and e.strip()]
    book = codebook(zone_id, BASE_CONCEPTS + extra, secret_key_hex)
    rows = []
    # When the lexicon has grown, devote ~40% of rows to EXTRA_TEMPLATES so every promoted term is
    # actually spoken in training (round-robin over `extra`) — else the adapter never learns them.
    extra_share = 0.4 if extra else 0.0
    ei = 0
    for _ in range(count):
        if extra and rng.random() < extra_share:
            x = extra[ei % len(extra)]
            ei += 1
            nl = render(rng.choice(EXTRA_TEMPLATES), rng, x=x)
        else:
            nl = render(rng.choice(TEMPLATES), rng)
        argot = encode(book, nl)
        if argot == nl:
            continue  # nothing tokenized — not a useful training pair
        rows.append({"messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": nl},
            {"role": "assistant", "content": argot},
        ]})
    return rows


def self_test():
    # Token derivation is stable and §-prefixed; encode round-trips through a reverse map.
    book = codebook("zone-alpha", BASE_CONCEPTS)
    tok = book["help"]
    assert tok.startswith("§") and len(tok) == 9, tok
    assert token_for("zone-a", "help") != token_for("zone-b", "help"), "zone boundary = language boundary"
    enc = encode(book, "i need help here now")
    assert book["help"] in enc and book["need"] in enc and "i" in enc.split(), enc
    rev = {v: k for k, v in book.items()}
    dec = " ".join(rev.get(w, w) for w in enc.split())
    assert dec == "i need help here now", dec

    # REGRESSION GUARD: a promoted/extra concept must actually be SPOKEN in the
    # generated corpus, not just added to the codebook — else a re-bake grows the codebook but the
    # adapter never learns the new term (silent evolution-loop break). Assert the extra token appears.
    import random as _r
    grown = build("zone-alpha", 400, ["rendezvous", "beacon"], _r.Random(7))
    rtok = token_for("zone-alpha", "rendezvous")
    btok = token_for("zone-alpha", "beacon")
    blob = "".join(m["messages"][2]["content"] for m in grown)
    assert rtok in blob, f"extra concept 'rendezvous' ({rtok}) never appears in the grown corpus"
    assert btok in blob, f"extra concept 'beacon' ({btok}) never appears in the grown corpus"
    print("self-test OK:", tok, "|", enc, "| grown speaks", rtok, btok)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zone", default="zone-alpha")
    ap.add_argument("--out")
    ap.add_argument("--count", type=int, default=400)
    ap.add_argument("--extra-concepts", default="")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--secret-key-hex", default=None,
                    help="hex of the zone's secret-derived argot key (HKDF(master,'argot-v1')); "
                         "when set, tokens use the SECRET seed the runtime decodes with")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return

    if not args.out:
        ap.error("--out required (or use --self-test)")
    extra = [c.strip().lower() for c in args.extra_concepts.split(",") if c.strip()]
    rng = random.Random(args.seed)
    rows = build(args.zone, args.count, extra, rng, args.secret_key_hex)
    with open(args.out, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(rows)} argot SFT pairs for zone '{args.zone}' → {args.out}")


if __name__ == "__main__":
    main()
