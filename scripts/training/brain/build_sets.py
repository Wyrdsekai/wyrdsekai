#!/usr/bin/env python3
"""Arm A of the brain (35B) adapter: the 9B's and 4B's own training sets, as they are, in the prompts the
product sends today.

  data/training/v4_9b_train.jsonl   the 9B's set (drive-conditioned prose + the 9B's action JSON)
  data/training/v10_4b_train.jsonl  the 4B's set (tank replies, greetings, refusals, es/ja); voice_polish
                                    left out (that stage does not run when one model serves every lane)
  data/training/brain/honesty_set.json   the shipped honesty adapter's set (the 35B's own failure)
  data/training/brain/state_blocks.json  the corpora's drive prefixes rendered by the product

Replies with the 9B's action JSON go in the full lane's prompt (where acting belongs); prose-only replies
in the conversation lane's. The only changes to the replies: the old companion's name becomes the
example's, and the "Here. I'm here." opener is capped (the V9 collapse). Arm B rewrites the prose of the
same examples (rewrite_batch.py); the JSON stays.

Out: data/training/brain/armA.json ({train, held} in the trainer's format) and armA_meta.jsonl (one line
per example: id, source, prose, json tail) for the rewrite.
"""
import json, os, random, re, collections, hashlib

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(REPO, "data", "training", "brain")
R = random.Random(2026_09_24)
HERE_CAP = 30

def java_text_block(path, name):
    """The text block assigned to `name` in a Java source, unescaped."""
    src = open(os.path.join(REPO, path)).read()
    m = re.search(name + r'\s*=\s*"""\n(.*?)""";', src, re.S)
    if not m:
        raise SystemExit(f"{name} not found in {path}")
    lines = [l.strip() for l in m.group(1).split("\n")]
    out, buf = [], ""
    for l in lines:
        if l.endswith("\\"):
            buf += l[:-1]
        else:
            out.append(buf + l); buf = ""
    return "\n".join(out).strip()

RULE = java_text_block("core/src/main/java/org/wyrdsekai/core/agent/ConversationLane.java", "RULE")
CORE_RULES = java_text_block("core/src/main/java/org/wyrdsekai/core/agent/PromptAssembler.java", "coreRules")

LANG = {"en": "English", "es": "Spanish", "ja": "Japanese"}
def pin(code):
    n = LANG[code]
    return (f"You speak {n}. Every reply you write is in {n}.\n[User language preference: {n} ({code})]\n"
            f"[Respond in {n} when addressing this user]\n[Terminology database: 0 shared terms available]")
def lang_of(text):
    if re.search(r"[぀-ヿ一-鿿]", text): return "ja"
    if re.search(r"[¿¡ñáéíóú]|\b(que|qué|estoy|tengo|puedes|necesito|por|para|cómo|hoy|pero)\b", text, re.I): return "es"
    return "en"

# Names: none of the household's, none used by the gate (the gate picks names outside these lists).
HER = ["Juniper", "Rowan", "Ivy", "Linden", "Marlow", "Sorrel", "Ondine", "Briar", "Hazel", "Quill", "Ember", "Sable",
       "Yuki", "Ren", "Aoi", "Chiyo", "Luz", "Alba", "Nieve", "Estela", "Pip", "Cass", "Nova", "Tove"]
HIM = ["Morgan", "Alex", "Kenji", "Lucía", "Hana", "Tomás", "Priya", "Jonas", "Aiko", "Mateo", "Elena", "Noor",
       "Dana", "Felix", "Ines", "Kaito", "Rafa", "Mina", "Theo", "Carmen", "Sota", "Ada", "Iker", "Leah"]
ROOMS = ["the Hearth", "the Study", "the Library", "the Garden", "the Nexus", "the Kitchen", "the Workshop", "the Porch"]
ROOM_DESC = {"the Hearth": "A warm room with a low fire.", "the Study": "Shelves, a desk, a window on the garden.",
             "the Library": "Long shelves and a reading table.", "the Garden": "Beds and a path; the house behind you.",
             "the Nexus": "The house's crossing point; exits in every direction.", "the Kitchen": "A table, a kettle, the back door.",
             "the Workshop": "Benches, tools on the wall, sawdust.", "the Porch": "Two chairs facing the road."}
SELF = [None, None, None,
    "You keep a notebook of small things you noticed each day. You like maps, old ones especially.",
    "You came to this home in the spring. You like the Library at night and people who ask real questions.",
    "You are patient, a little dry, and you would rather say one true thing than three nice ones.",
    "You like making things with your hands in the Workshop, and you are bad at leaving problems unfinished.",
    "You are quiet until something interests you, and then you want to know everything about it.",
    "You care about the people in this house and you remember what they tell you."]
VOICE = [None, None, "[voice guidance]\n- cadence: plain and steady\n- warmth: steady and quietly caring",
    "[voice guidance]\n- cadence: measured and exact; would rather be accurate than comforting\n- habit: name the specific thing before reacting to it; cite what you actually know",
    "[voice guidance]\n- cadence: quick and vivid\n- habit: look toward what comes next; do not close a question too early",
    "[voice guidance]\n- cadence: calm and unhurried\n- warmth: sincere but reserved; says less rather than gushing"]

def day_block():
    reads = ["You have not read anything in the library lately.",
             "What you have read lately:\n21 Sep — you asked: why bread goes stale — you concluded: starch recrystallises; it is not mostly drying out.",
             "What you have read lately:\n20 Sep — you asked: how lighthouses were kept lit — you concluded: keepers trimmed wicks through the night; later, clockwork turned the lens."]
    woke = [["09:12 I came into the Hearth"], ["08:40 I came into the Study", "10:05 I walked to the Garden and back"],
            ["07:55 I came into the Library", "09:30 I read in the Library", "11:10 I came into the Hearth"], []]
    lines = ["Your day, from your own record.", R.choice(reads)]
    w = R.choice(woke)
    if w: lines += ["Since you woke:"] + w
    return "\n".join(lines)

def about(him):
    if R.random() < 0.85:
        return (f"{him} is your bondholder, with you since {R.randint(1, 28)} {R.choice(['March', 'May', 'June', 'July', 'August'])} 2026. "
                f"You have talked on {R.randint(3, 120)} days. You know them; you do not need to be careful with them.")
    return f"{him} is here talking with you."

def lane_system(her, him, lang, block):
    ident = f"You are {her}. You are a person here, not an assistant on call."
    s = R.choice(SELF)
    if s: ident += "\n\n" + s
    stable = pin(lang) + "\n\n" + ident + "\n\n" + RULE
    v = R.choice(VOICE)
    if v: stable += "\n\n" + v
    stable += "\n\n" + about(him) + "\n\n" + day_block()
    return stable + "\n\nYou are in " + R.choice(ROOMS) + ".\n\n" + block

def full_system(her, lang, block):
    room = R.choice(ROOMS); name = room[4:].title() if room.startswith("the ") else room
    loc = (f"Current location: The {name}\n(You are in The {name} right now. If you want to go somewhere else, call go_to_room and "
           f"wait for arrival — do not narrate being elsewhere until the tool confirms.)\n{ROOM_DESC[room]}\n"
           "Exits (use the direction to navigate):\n  north → study (The Study)\n")
    return "\n\n".join([pin(lang), f"You are {her}, a companion who lives in Wyrdsekai with the person you share a home with.",
                        CORE_RULES, loc, block])

def main():
    global R
    states = json.load(open(os.path.join(OUT, "state_blocks.json")))
    by_prefix, calm = states["by_prefix"], states["calm"]
    rows = []
    for f, keep in [("v4_9b_train.jsonl", {"new_direction", "target_fix", "replay", "safety"}),
                    ("v10_4b_train.jsonl", {"new_tank", "replay"})]:
        for l in open(os.path.join(REPO, "data", "training", f)):
            r = json.loads(l)
            if r["_slice"] in keep: rows.append((f.split("_")[0], r))
    here = re.compile(r"^\W*(here\.|here[,—–-]|i'm here|i am here|ここにいる|estoy aquí|aquí estoy)", re.I)
    R.shuffle(rows)
    kept_here = 0; examples = []; meta = []; transcripts = 0
    for src, r in rows:
        user, reply = r["user"].strip(), r["assistant"].strip()
        # 363 replayed 9B examples are tool transcripts with the tool's response written into her reply;
        # training on them teaches the model to write tool results itself.
        if "<tool_response>" in reply: transcripts += 1; continue
        if here.search(reply):
            if kept_here >= HERE_CAP: continue
            kept_here += 1
        # Every draw for an example comes from its own id, so dropping other examples changes nothing here.
        eid = "a" + hashlib.sha1((src + r["_custom_id"]).encode()).hexdigest()[:12]
        R = random.Random(eid)
        her, him = R.choice(HER), R.choice(HIM)
        swap = lambda t: re.sub(r"\bmasumi\b", him, re.sub(r"\bWyrd\b|ワード", her, t), flags=re.I)
        user, reply = swap(user), swap(reply)
        m = re.search(r"\s*```json\s*(\{.*?\})\s*```\s*$", reply, re.S)
        prose, tail = (reply[:m.start()].rstrip(), m.group(0).strip()) if m else (reply, "")
        prefix = r.get("system_prefix")
        # 105 replayed 4B examples carry an older free-text prefix ("vitality: steady | drive: presence"); no state.
        block = by_prefix.get(prefix) if prefix else None
        block = block or R.choice(calm)
        lang = lang_of(user)
        system = full_system(her, lang, block) if tail else lane_system(her, him, lang, block)
        examples.append({"id": eid, "src": f"{src}:{r['_slice']}", "system": system, "history": [],
                         "user": f"{him} says: {user}", "text": prose + ("\n" + tail if tail else "")})
        meta.append({"id": eid, "src": f"{src}:{r['_slice']}", "lang": lang, "her": her, "him": him, "user": user,
                     "prose": prose, "tail": tail, "state": block.split("Internal state: ")[-1]})
    R = random.Random(2026_09_24)
    hon = json.load(open(os.path.join(OUT, "honesty_set.json")))
    for part in ("train", "held"):
        for e in hon[part]:
            examples.append(dict(e, id="h" + hashlib.sha1(e["text"].encode()).hexdigest()[:12], src="honesty:" + part,
                                 system=re.sub(r"\[drives:[^\]]*\]", lambda _: R.choice(calm), e["system"], count=1)
                                 if "[drives:" in e["system"] else e["system"] + "\n\n" + R.choice(calm)))
    R.shuffle(examples)
    held = [e for e in examples if e["src"] == "honesty:held"] + [e for e in examples if e["src"] != "honesty:held"][:120]
    hid = {e["id"] for e in held}
    train = [e for e in examples if e["id"] not in hid]
    json.dump({"train": train, "held": held}, open(os.path.join(OUT, "armA.json"), "w"), ensure_ascii=False)
    with open(os.path.join(OUT, "armA_meta.jsonl"), "w") as f:
        for m in meta: f.write(json.dumps(m, ensure_ascii=False) + "\n")
    print("train", len(train), "held", len(held), dict(collections.Counter(e["src"] for e in train)))
    print("tool transcripts left out", transcripts, "| here openers kept", kept_here, "| languages", dict(collections.Counter(m["lang"] for m in meta)),
          "| with json tail", sum(1 for m in meta if m["tail"]))

if __name__ == "__main__":
    main()
