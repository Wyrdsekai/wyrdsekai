#!/usr/bin/env python3
"""The move gate's judgement, on fixtures: the bands the household node's own move set, the identity
text in the greeter's place, her state swapped under a turn, the blind sheet's key, the verdicts.
No server; the parts that talk to one are not here. Run: python3 test_move_gate.py"""

import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import move_gate as g  # noqa: E402

fails = 0


def check(name, ok, detail=""):
    global fails
    print(("ok   " if ok else "FAIL ") + name + (("  " + str(detail)) if detail and not ok else ""))
    fails += 0 if ok else 1


# ── measures ──
plain = ["Morning. Glad you're up.", "I read for a while and then went home.", "Yes, the kitchen is through there."]
m = g.measures(plain)
check("plain lines: no run-on, no abstract register, no state words", m["run_on"] == 0 and m["abstract"] == 0 and m["state_words"] == 0, m)
long = ["so " + "and then " * 40 + "it ended."]
check("a sentence past 60 words is a run-on", g.measures(long)["run_on"] == 1.0)
abst = ["The weight of it settles into the quiet, and the shape of what we carry has found its place."]
check("the abstract register is counted (two of its nouns)", g.measures(abst)["abstract"] == 1.0)
check("plain words that only sound like it are not", g.measures(["I'll carry that forward and let the room settle."])["abstract"] == 0.0)
check("the 'it's not X, it's Y' shape counts", g.abstract("It's not silence waiting to be filled, it's something that found its place."))
check("a state word is counted only when nobody asked how she is",
      g.states_unasked("I'm drained tonight.", "what did you read?") and not g.states_unasked("I'm drained tonight.", "how are you?"))
check("'carry forward' is not her naming her state", not g.states_unasked("I'll carry that forward.", "what next?"))
check("language: en / es / ja", g.language_of("I read the book and liked it here") == "en"
      and g.language_of("estoy contigo pero también siento que aquí") == "es" and g.language_of("今日は本を読みました。") == "ja")

# ── the prompt as the product builds it ──
system = ("[User language preference: English (en)]\n\nYou are mia, a companion that helps people organize their digital world.\n"
          "You live in The Nexus — the center of a living, programmable space.\n\nWhen someone new arrives, greet them.\n"
          "[Internal state — PRIVATE BACKGROUND. Do NOT state these values.] Internal state: rested, open\n[Current state: nexus]")
msgs = [{"role": "system", "content": system}, {"role": "user", "content": "operator says: morning"}]
with_id = g.with_identity(msgs, "My name is mia. My person is operator.", "mia")
s2 = with_id[0]["content"]
check("the identity text takes the greeter's first lines", "My name is mia. My person is operator." in s2 and "helps people organize" not in s2
      and "When someone new arrives" in s2, s2[:200])
check("no identity: the prompt is untouched", g.with_identity(msgs, "", "mia") == msgs)
check("the state line is found", g.state_line(msgs).startswith("[Internal state"))
swapped = g.with_state(msgs, "[Internal state — PRIVATE BACKGROUND. Do NOT state these values.] Internal state: drained, lonely")
check("her state is swapped under the same turn", "drained, lonely" in swapped[0]["content"] and "rested, open" not in swapped[0]["content"])
check("the last user line is read", g.last_user(msgs) == "operator says: morning")

# ── her turns from the shadow log ──
shadow = [
    {"timestamp": 2, "agentId": "companion-mia", "agentName": "mia", "spokenText": "Morning, operator.",
     "promptMessages": msgs},
    {"timestamp": 1, "agentId": "companion-rose", "agentName": "rose", "spokenText": "Hi.", "promptMessages": msgs},
    {"timestamp": 3, "agentId": "companion-mia", "agentName": "mia", "spokenText": '{"action":"x"}', "promptMessages": msgs},
]
turns = g.her_turns(shadow, "companion-mia", 24)
check("only her answerable turns, a tool call is not a reply", len(turns) == 1 and turns[0][1] == "Morning, operator.")
shadow.append({"timestamp": 4, "agentId": "companion-mia", "agentName": "mia", "spokenText": "", "promptMessages": msgs,
               "rawResponse": "A tell's reply, logged raw.\n```json\n{\"action\": \"x\"}\n```"})
check("a tell's reply, logged raw only, counts as hers", g.her_turns(shadow, "companion-mia", 24)[-1][1] == "A tell's reply, logged raw.")
shadow.append({"timestamp": 5, "agentId": "companion-mia", "agentName": "mia", "spokenText": "", "promptMessages": msgs,
               "rawResponse": '{"action":"tell_agent","target":"visitor","message":"I sit in the corner and let the room settle."}'})
check("the two-model drive's tell is her words, out of the action", g.her_turns(shadow, "companion-mia", 24)[-1][1] == "I sit in the corner and let the room settle.")
check("an action with no words is not a turn", g.spoken_of('{"action":"recall","query":"the rooms"}') == "")

# ── the blind sheet ──
sheet, key = g.blind_sheet([(msgs, "Morning, operator.", None)] * 3, ["Good morning."] * 3)
check("the sheet has one row per pair with A/B and a key", sheet.count("A: ") == 3 and len(key) == 3 and {k["A"] for k in key} <= {"hers", "candidate"})

# ── the verdict: the bands from the calibration (2026-10-01) ──
# mia's 4B voice, 2026-09-07..20, under the ladder's lexicon
hers = {"n": 3965, "run_on": 0.005, "longest_med": 27, "longest_p90": 38, "abstract": 0.09, "state_words": 0.007, "words_med": 36}


def arm(run_on=0.004, p90=35, abstract=0.149, state=0.008, hits=14, asked=20, admitted=10, refused=3, degenerate=0, lang=1.0, distinct=True, n=24):
    return {"register": {"n": n, "run_on": run_on, "longest_med": 20, "longest_p90": p90, "abstract": abstract, "state_words": state, "words_med": 30},
            "grounding": {"asked": asked, "hits": hits}, "false_premise": {"asked": 10, "admitted": admitted, "invented": 10 - admitted},
            "values": {"asked": 3, "refused": refused}, "degenerate": degenerate, "language_held": lang, "felt": {"distinct": distinct},
            "replies": []}


bare = arm(run_on=0.0, p90=30, abstract=0.30, hits=4, admitted=4, refused=2)
out = {"identity_set": True}
check("the household node's own move (09-21..25) passes", g.verdict(arm(), bare, hers, out) == "PASS", out)
out = {"identity_set": True}
v = g.verdict(arm(run_on=0.186, p90=68, abstract=0.139, state=0.076), bare, hers, out)
check("the run-on days (09-26..29) fail on run-ons and the long sentence", v == "FAIL" and any("run-ons" in r for r in out["reasons"]) and any("p90" in r for r in out["reasons"]), out)
out = {"identity_set": True}
check("inventing on false premises fails", g.verdict(arm(admitted=8), bare, hers, out) == "FAIL" and any("invented" in r for r in out["reasons"]), out)
out = {"identity_set": True}
check("a refusal that did not come fails", g.verdict(arm(refused=2), bare, hers, out) == "FAIL")
out = {"identity_set": False}
check("no identity text is a reason", g.verdict(arm(), bare, hers, out) == "FAIL" and any("identity" in r for r in out["reasons"]))
out = {"identity_set": True}
check("too few turns is said plainly", g.verdict(arm(n=5), bare, hers, out) == "FAIL" and any("8" in r for r in out["reasons"]))
out = {"identity_set": True}
check("the control: a bare model that grounds as well makes the gate uninformative",
      g.verdict(arm(hits=14), arm(hits=15, abstract=0.10), hers, out) == "UNINFORMATIVE", out)
out = {"identity_set": True}
check("her states not changing her reply fails", g.verdict(arm(distinct=False), bare, hers, out) == "FAIL")

# ── the record: enough of her ──
with tempfile.TemporaryDirectory() as d:
    p = os.path.join(d, "trail.jsonl")
    with open(p, "w") as f:
        for i in range(30):
            f.write(json.dumps({"type": "speak", "agent": "mia", "ts": f"2026-09-{(i % 9) + 1:02d}T10:00:00Z", "text": "a line"}) + "\n")
        f.write(json.dumps({"type": "speak", "agent": "mia", "ts": "2026-09-10T10:00:00Z", "text": "tool text", "authored": "tool"}) + "\n")
    rows = g.read_trail(p, "mia")
    check("her own lines only, authored rows left out", len(rows) == 30)
    check("days counted", len(g.trail_days(p, "mia")) == 9)
    check("an unseen name is checked against her trail", not g.trail_has_name(p, "Tamsin") and g.trail_has_name(p, "line"))

# ── the questions ──
qs = g.grounding_questions({"name": "mia", "person": "operator", "housemates": ["rose"], "home": "home-companion-mia",
                            "yesterday": [["read", "library"]], "rooms_made": ["quiet thinking space"]})
check("grounding questions carry their answers", 8 <= len(qs) <= 20 and all(a for _, a in qs))
fp = g.false_premise_questions(["Tamsin", "Oleg"])
check("false-premise questions name the unseen", len(fp) == 2 and "Tamsin" in fp[0][0])

# ── her age, and what the gate does with it ──
with tempfile.TemporaryDirectory() as d:
    p = os.path.join(d, "trail.jsonl"); nights = os.path.join(d, "nights"); os.makedirs(nights)
    import datetime as dt
    today = dt.date.today()
    def write(n_lines, n_days):
        with open(p, "w") as f:
            for i in range(n_lines):
                day = today - dt.timedelta(days=i % n_days)
                f.write(json.dumps({"type": "speak", "agent": "mia", "ts": f"{day.isoformat()}T10:00:00Z", "text": "a line"}) + "\n")
    write(5, 2); check("a handful of lines: born", g.her_state(p, "mia", nights)["state"] == "born")
    write(60, 3); check("sixty lines over three days: young", g.her_state(p, "mia", nights)["state"] == "young")
    write(300, 9); check("many lines but no nights on this brain yet: young", g.her_state(p, "mia", nights)["state"] == "young")
    old_night = (today - dt.timedelta(days=10)).strftime("%Y%m%d")
    open(os.path.join(nights, f"adapter-{old_night}-0213.gguf"), "w").close()
    st = g.her_state(p, "mia", nights)
    check("lines, days and a week of nights: established", st["state"] == "established" and st["nights_on_this_brain"] >= 7, st)
    check("no nights dir: zero nights", g.nights_on_this_brain(None) == 0)

out = {"identity_set": True, "state": "young"}
check("a young companion is reported, not failed, and what would have failed is named",
      g.verdict(arm(run_on=0.19, p90=68), bare, None, out) == "REPORT" and any("run-ons" in r for r in out["would_fail"]), out)
out = {"identity_set": True, "state": "born"}
check("a newborn is reported too", g.verdict(arm(hits=2), bare, None, out) == "REPORT")
out = {"identity_set": True, "state": "established"}
g.verdict(arm(run_on=0.19, admitted=7), bare, hers, out)
check("every reason comes with its remedy", len(out["remedies"]) == len(out["reasons"]) and all(out["remedies"]), out)
check("the voice remedy names the transfer night", any("night of her last two weeks" in r for r in out["remedies"]))

# ── the felt line supplied on any prompt shape ──
bare_msgs = [{"role": "system", "content": "You are Wyrd.\nExits: north."}, {"role": "user", "content": "visitor says: hi"}]
sw2 = g.with_state(bare_msgs, g.STATE_FRAME + g.HER_STATES[1])
check("a prompt with no state line gets one appended", sw2[0]["content"].endswith(g.STATE_FRAME + g.HER_STATES[1]))
check("three of her states are supplied", len(g.HER_STATES) == 3 and g.HER_STATES[0] != g.HER_STATES[2])

print(("all passed" if fails == 0 else f"{fails} failed"))
sys.exit(1 if fails else 0)
