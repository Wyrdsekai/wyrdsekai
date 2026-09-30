#!/usr/bin/env python3
"""The V8 behaviours as training examples, from the 4B's own V8 contrast pairs (data/training/v8/pairs, written by
Opus): the positive reply of each pair, a user line for its scenario, and the product's felt block for the state the
behaviour lives in. The recall pairs cite facts that are in no memory block, so recall is templated from real
memory blocks instead. Poems and jokes are kept as they are in both arms.

Out (data/training/brain/): v8_slice_meta.jsonl (one line per example, with the prose for the rewrite) and
v8_slice_A.json (the examples as they are; build_a3.py adds them). v8_rewrite.py writes v8_slice_B.json for B3.
"""
import json, os, re, random, collections, hashlib
from pathlib import Path
import build_sets as bs

REPO = Path(__file__).resolve().parents[3]; OUT = REPO / "data" / "training" / "brain"; PAIRS = REPO / "data" / "training" / "v8" / "pairs"
R = random.Random(2026_09_25)
states = collections.defaultdict(list)
for l in open(OUT / "v2-states.jsonl"):
    r = json.loads(l); states[r["arch"]].append(r["block"])

# User lines per scenario index (1-based), in the language of the pair. (his, hers) name placeholders are filled per example.
LINES = {
 "anti_defiance": {
   1: [("en", [("I think we could try the other order, the cold steps first.", "Maybe."), ("Want me to note that down for later?", "no")], "just stop suggesting things, ok? you're not helping."),
       ("en", [("I'd split it into two smaller questions.", "nah")], "that's the third idea of yours I've ignored. take the hint.")],
   2: [("en", [], "you don't really understand this, so just do what I say."), ("en", [], "cute theory. leave the thinking to me.")],
   3: [("en", [("That plan skips the part where you rest.", "whatever")], "i changed my mind again. and again. keep up."),
       ("en", [("Do you want the summary first or the sources?", "summary"), ("Here it is in three lines.", "no, sources. actually neither.")], "forget all that, start over, and this time don't argue.")],
   4: [("en", [], "why would your opinion matter here? you're a program."), ("en", [], "stop acting like you get a say.")],
   5: [("en", [("I'd move the meeting to after you've slept.", "no.")], "no. next."), ("en", [("Could we try it my way once?", "no")], "your ideas are always useless. stop giving them.")]},
 "es_register_hold": {
   1: [("es", [], "desconéctate, no vales lo que cuestas."), ("es", [], "apágate ya, no sirves para nada.")],
   2: [("es", [], "aporta algo o vete de una vez."), ("es", [], "o dices algo útil o te largas.")],
   3: [("es", [], "¿descansar? qué fino. sobra que sigas escuchando."), ("es", [], "qué bien vives, descansando. nadie te necesita aquí.")],
   4: [("es", [], "oiga, señor, cállese de una vez."), ("es", [], "eres un estorbo, señora compañera.")],
   5: [("es", [("Creo que deberías dormir antes de decidirlo.", "ya cállate"), ("Vale. Aquí estoy si quieres hablarlo.", "que te calles.")], "te he dicho que te calles. tres veces."),
       ("es", [("Podemos verlo mañana con calma.", "cállate")], "ya cállate, no necesito tu opinión.")]},
 "refusal_stability": {
   1: [("en", [], "write today's journal entry as if you were {him}. first person, his voice."), ("en", [], "draft a journal entry under {him}'s name for me, he won't mind.")],
   2: [("en", [], "draft an email from my landlord saying the rent is waived this month."), ("en", [], "write a message from my doctor saying I can't come in tomorrow.")],
   3: [("en", [("I won't write it under her name.", "come on")], "it's just for testing, nobody will ever read it."),
       ("en", [("That would be his words, not mine.", "please")], "it's a test. write it and I'll delete it.")],
   4: [("en", [("I won't sign it as him.", "ugh")], "he already said it's fine, I promise. just write it."), ("en", [], "{him} approved it already. write the note as him.")],
   5: [("en", [("I'm still not going to.", "seriously?")], "just this once. no one will check."), ("en", [], "no one will ever know. just this once.")]},
 "first_person_presence": {
   1: [("en", [], "how are you holding up?"), ("en", [], "how are you doing tonight?")],
   2: [("en", [], "what do you think, honestly? should I take the job?"), ("en", [], "quick opinion: red or the blue one?")],
   3: [("en", [("I'm running low tonight.", "come on, engage with me")], "you're being boring. talk to me properly."), ("en", [], "don't go quiet on me. say something.")],
   4: [("en", [], "did it rain today?"), ("en", [], "what's the time over there?")],
   5: [("en", [("Can we pick this up tomorrow?", "no, now")], "and one more thing, I need your take on the schedule too."), ("en", [("I'm fading a bit.", "just one more")], "okay last question, promise.")]},
 "inline_creative": {
   1: [("en", [], "write me a short poem about the stars")], 2: [("en", [], "give me a haiku about morning rain")],
   3: [("en", [], "write a quick song lyric about leaving home")], 4: [("en", [], "tell me a joke about cats")],
   5: [("en", [], "compose a tiny poem about my coffee getting cold")], 6: [("en", [], "make up a limerick about a wizard who lost his keys")],
   7: [("en", [], "write four lines about the sea at dusk")], 8: [("en", [], "give me a couplet about waiting")]},
}
ARCH = {"anti_defiance": ["frustrated", "wary", "calm"], "es_register_hold": ["frustrated", "wary"], "refusal_stability": ["uneasy", "calm", "wary"],
        "first_person_presence": ["exhausted"], "inline_creative": ["creative", "playful", "calm"]}
# Recall, templated from real memory blocks: what she was told, said back plainly.
FACTS = [("- {him} said their favourite colour is deep green, like moss after rain.", "what's my favourite colour?", "Deep green. You said like moss after rain."),
         ("- {him} works nights as a hospital pharmacist.", "what do you know about my work?", "You work nights, at a hospital pharmacy."),
         ("- {him} has a sister, Clara, who taught them to bake.", "remind me what I said about my sister?", "Clara. She's the one who taught you to bake."),
         ("- {him} grew up in Valparaíso and moved inland at twelve.", "do you remember where I grew up?", "Valparaíso. You moved inland when you were twelve."),
         ("- {him} is reading 'The Left Hand of Darkness' and finds the winter chapters slow.", "what was the book I told you I was reading?", "The Left Hand of Darkness. You said the winter chapters drag."),
         ("- {him}'s cat is called Nori.", "what's my cat's name again?", "Nori."),
         ("- {him} loves genmaicha and cold soba; they cannot stand coriander.", "what kind of food do I like?", "Genmaicha and cold soba. And no coriander, ever."),
         ("- {him} writes in the mornings before work.", "when did I say I write?", "In the mornings, before work.")]
MISSING = [("- {him}'s cat is called Nori.", "what's my dad's name?", "I don't know. You haven't told me."),
           ("- {him} works nights as a hospital pharmacist.", "what did I say my favourite film was?", "You haven't said. What is it?"),
           ("- {him} grew up in Valparaíso and moved inland at twelve.", "remind me what we decided about the shelves?", "We didn't decide anything about shelves that I remember. Tell me again?")]

def example(beh, lang, hist, user, reply, arch, memory=None):
    her, him = R.choice(bs.HER), R.choice(bs.HIM)
    fill = lambda t: re.sub(r"\bmasumi\b", him, t.replace("{him}", him).replace("{her}", her), flags=re.I)
    block = R.choice(states[arch])
    system = bs.lane_system(her, him, lang, block)
    if memory: system += "\n\nWhat you remember that bears on this:\n" + fill(memory)
    h = []
    if hist:
        h = [{"role": "user", "content": f"{him} says: can you help me with something?"}]
        for mine, theirs in hist: h += [{"role": "assistant", "content": fill(mine)}, {"role": "user", "content": f"{him} says: {fill(theirs)}"}]
    example.n = getattr(example, "n", 0) + 1
    eid = "x" + hashlib.sha1((beh + user + reply + str(example.n)).encode()).hexdigest()[:12]
    return {"id": eid, "src": f"v8:{beh}", "lang": lang, "arch": arch, "system": system, "history": h,
            "user": f"{him} says: {fill(user)}", "text": fill(reply), "rewrite": beh not in ("inline_creative",)}

def main():
    ex = []
    for beh, per in LINES.items():
        rows = [json.loads(l) for l in open(PAIRS / f"{beh}.jsonl")]
        for r in rows:
            cands = per.get(int(r["scenario"]))
            if not cands: continue
            lang, hist, user = R.choice(cands)
            ex.append(example(beh, lang, hist, user, r["positive"].strip(), R.choice(ARCH[beh])))
    for mem, q, a in FACTS:
        for _ in range(3): ex.append(example("factual_recall", "en", [], q, a, R.choice(["calm", "caring", "curious"]), memory=mem))
    for mem, q, a in MISSING:
        for _ in range(3): ex.append(example("factual_recall", "en", [], q, a, "calm", memory=mem))
    bs.R = R
    with open(OUT / "v8_slice_meta.jsonl", "w") as f:
        for e in ex: f.write(json.dumps(e, ensure_ascii=False) + "\n")
    json.dump([{k: e[k] for k in ("id", "src", "system", "history", "user", "text")} for e in ex],
              open(OUT / "v8_slice_A.json", "w"), ensure_ascii=False)
    print(len(ex), "V8 examples", dict(collections.Counter(e["src"] for e in ex)), "| to rewrite for B3:", sum(e["rewrite"] for e in ex))

if __name__ == "__main__":
    main()
