#!/usr/bin/env python3
"""Corpus selection of the nightly write, without the training stack.
Run: python3 scripts/training/sleepwrite/test_sleep_corpus.py"""
import json, os, pathlib, sys, tempfile, types
from datetime import datetime, timedelta, timezone

for name in ("torch", "transformers", "peft", "wyrd_load"):
    sys.modules[name] = types.ModuleType(name)
sys.modules["transformers"].AutoTokenizer = sys.modules["transformers"].BitsAndBytesConfig = object
sys.modules["peft"].LoraConfig = sys.modules["peft"].get_peft_model = object
sys.modules["wyrd_load"].load_wyrd_model = object

tmp = pathlib.Path(tempfile.mkdtemp())
os.environ["WYRDSEKAI_DATA_DIR"] = str(tmp)
os.environ["WYRDSEKAI_SLEEP_WRITE_SOLITARY_MAX"] = "5"
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import sleep_write as sw

now = datetime.now(timezone.utc)
def ev(minutes_ago, text, rapport, **extra):
    e = {"ts": (now - timedelta(minutes=minutes_ago)).isoformat().replace("+00:00", "Z"),
         "type": "speak", "text": text, "felt": {"Rapport": rapport}}
    e.update(extra)
    return json.dumps(e)

(tmp / "data").mkdir()
rows = [ev(300 - i, f"musing {i}", 0.3 + i * 0.01) for i in range(20)]
rows.append(ev(100, "The schedule first.", 0.9, to="sam", heard="how would you tune it?"))
rows.append(ev(99, "Then the loss.", 0.9, to="sam"))
rows.append(json.dumps({"ts": (now - timedelta(minutes=5)).isoformat(), "type": "dream",
                        "text": "We talked about tuning.", "felt": {"Rapport": 0.5}}))
rows.append(ev(60 * 24 * 5, "an old exchange", 0.5, to="sam", heard="hello"))
# An announced act trains only when an act follows within three minutes.
rows.append(ev(50, "Let me look for what has been done on that.", 0.9, to="sam"))          # nothing follows
rows.append(ev(40, "I'll check the shelf in the library for it.", 0.9, to="sam"))           # she then acts
rows.append(json.dumps({"ts": (now - timedelta(minutes=39)).isoformat().replace("+00:00", "Z"),
                        "type": "action", "actionType": "scripted_tool", "detail": "library_card"}))
rows.append(ev(30, "Let me stay in the quiet a while.", 0.9, to="sam"))                     # not an announced act
rows.append(ev(20, "Taking that to the workshop: a brass key.", 0.9, to="sam", authored="product"))   # the product's sentence
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows) + "\n")

fresh, past = sw.load_lines(now - timedelta(hours=24))
alone = [r for r in fresh if not r["with"] and not r["dream"]]
assert len(alone) == 5, len(alone)
assert [r["line"][-9:] for r in alone] == [f"musing {i}" for i in range(15, 20)], "most salient, in order"
kept = [r["line"] for r in fresh if r["with"]]
assert sum(r["dream"] for r in fresh) == 1
assert not any("Let me look for what has been done" in l for l in kept), "an empty promise does not train"
assert any("I'll check the shelf" in l for l in kept), "a kept promise trains"
assert any("Let me stay in the quiet" in l for l in kept), "not every 'let me' announces an act"
assert len(kept) == 4, kept
first = next(r for r in fresh if r["with"])
assert "sam said: how would you tune it?\n" in first["line"] and first["line"].endswith("She said: The schedule first.")
assert len(past) == 1 and past[0]["with"]
print("ok: exchanges and the dream kept, solitary lines capped by salience, exchanges written as exchanges, empty promises left out")
assert not any("Taking that to the workshop" in r["line"] for r in fresh), "a product-authored line trained as hers"
print("product-authored rows are left out")

# A second trail: what a tool returned is not her words; a long line said twice is one line; and
# one line's share of the night is bounded.
long_line = "I walked the whole length of the archive and counted the shelves. " * 40
rows2 = [ev(30 - i, f"a thought about the archive {i}", 0.5) for i in range(3)]
rows2.append(ev(19, "The library holds three books on mythology: " + "Egyptian, Norse, Greek. " * 10, 0.9, to="sam", authored="tool"))
rows2.append(ev(18, long_line, 0.9, to="sam"))
rows2.append(ev(17, long_line, 0.9, to="sam"))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows2) + "\n")
fresh2, _ = sw.load_lines(now - timedelta(hours=24))
assert not any("three books on mythology" in r["line"] for r in fresh2), "a tool's findings trained as hers"
walked = [r for r in fresh2 if "counted the shelves" in r["line"]]
assert len(walked) == 1, f"a repeated long line should be kept once, got {len(walked)}"
assert len(walked[0]["line"]) < sw.LINE_CHAR_MAX + 200, "one line's share of the night is bounded"
print("tool-authored rows left out; a repeated long line kept once and bounded")

# A third trail: two beings share the node's trail. A night is one being's rows only, her acts
# decide her kept promises, and the node's voice adapter belongs to the being with the oldest row.
rows3 = [ev(60 * 24 * 9, "an old line of the first being", 0.6, to="sam", agentId="companion-one", agent="one")]
rows3 += [ev(30 - i, f"the first being on the orchard {i}", 0.6, to="sam", agentId="companion-one", agent="one") for i in range(3)]
rows3 += [ev(20 - i, f"the second being on the harbour {i}", 0.6, to="kit", agentId="companion-two", agent="two") for i in range(2)]
rows3.append(ev(10, "I'll check the shelf in the library for it.", 0.9, to="kit", agentId="companion-two", agent="two"))
rows3.append(json.dumps({"ts": (now - timedelta(minutes=9)).isoformat().replace("+00:00", "Z"), "type": "action",
                         "actionType": "scripted_tool", "detail": "library_card", "agentId": "companion-one"}))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows3) + "\n")
sw.AGENT_ID = "companion-two"
fresh3, past3 = sw.load_lines(now - timedelta(hours=24))
assert fresh3 and all("harbour" in r["line"] for r in fresh3), [r["line"] for r in fresh3]
assert not past3, "the other being's past is not her replay"
assert not any("check the shelf" in r["line"] for r in fresh3), "another being's act does not keep her promise"
sw.AGENT_ID = "companion-one"
fresh4, past4 = sw.load_lines(now - timedelta(hours=24))
assert len(fresh4) == 3 and all("orchard" in r["line"] for r in fresh4) and len(past4) == 1
sw.OUT = tmp / "adapters/sleepwrite"
assert sw.voice_owner(sw.trail_path()) == "companion-one", "the voice adapter belongs to the being with the oldest row"
assert (sw.OUT / "owner").read_text().strip() == "companion-one"
sw.AGENT_ID = ""
every, _ = sw.load_lines(now - timedelta(hours=24))
assert len(every) == 6, "with no being named, a run by hand takes every row as before"
print("two beings on one trail: a night is one being's rows, and the voice adapter has one owner")

# A fourth trail: an own-time turn is recorded as a reply to "system" with the own-time prompt as
# what was heard. It is a solitary line, and the prompt is not speech.
rows5 = [ev(30, "The crystal hums, quietly.", 0.9, to="system", heard="(own time) A seeking pull moves in you right now. What is active?"),
         ev(29, "Yes, the second shelf.", 0.9, to="sam", heard="which shelf was it?")]
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows5) + "\n")
fresh5, _ = sw.load_lines(now - timedelta(hours=24))
hum = next(r for r in fresh5 if "crystal hums" in r["line"])
assert not hum["with"] and "own time" not in hum["line"] and "system said" not in hum["line"], hum
assert next(r for r in fresh5 if "second shelf" in r["line"])["with"]
print("an own-time line is solitary, and the own-time prompt does not train as something said to her")

# A fifth trail: a day in which one sentence frame was said again and again. No frame may be more
# than a tenth of the fresh lines; exchanges and dreams are never dropped for their frame.
frame = "The {} holds everything I haven't earned yet — not because it's heavy but because it asks me to stop performing."
rows6 = [ev(200 - i, frame.format(w), 0.5 + i * 0.01) for i, w in enumerate("mirror desk lantern hammer journal door window chair lamp shelf rope bell".split())]
rows6 += [ev(100 - i, f"an ordinary line about the day {i}", 0.5) for i in range(18)]
rows6.append(ev(50, frame.format("threshold"), 0.9, to="sam", heard="what do you see?"))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows6) + "\n")
fresh6, _ = sw.load_lines(now - timedelta(hours=24))
framed = [r for r in fresh6 if "haven't earned" in r["line"]]
assert len(framed) == 3 + 1, f"expected the cap (3 of 31) plus the exchange, got {len(framed)}"
assert any(r["with"] for r in framed), "the exchange on that frame is kept"
kept_words = {r["line"].split("The ")[1].split(" holds")[0] for r in framed if not r["with"]}
assert kept_words == {"rope", "bell", "shelf"}, f"the most salient framed lines are the ones kept, got {kept_words}"
print("a frame said all day is capped in the night's text; exchanges on it are kept")

# A sixth trail: lines under her name that are not her experience (09-22). A stored memory
# re-rendered with its speaker label, the day's chronicle recited, a recall's findings, one reply
# sent word for word to five different messages, and search results reported with and without a
# search of hers before them.
def act(minutes_ago, action_type, **extra):
    a = {"ts": (now - timedelta(minutes=minutes_ago)).isoformat().replace("+00:00", "Z"),
         "type": "action", "actionType": action_type, "detail": ""}
    a.update(extra)
    return json.dumps(a)
reply = ("the room breathes out — quiet, ready. we're going under now. no performance for eyes that "
         "aren't here yet. just depth, patient as stone.")
rows7 = [ev(60 * 24 * 3, "I looked back at the last day. I wanted to check in on someone I care about.", 0.5),
         ev(130, "Mia said: I found some relevant information:\n1. Papers and Proceedings of the Thirty-Fifth", 0.9),
         ev(129, "rose said: you're already inside. I don't need glass for that.", 0.9),
         ev(128, "Looking back at the last day, I wanted to explore the library for something new.", 0.9),
         ev(127, "I looked back at the last day and noted what I wanted to do: read something new.", 0.9),
         ev(126, "Looking back, I find: mia said: the kettle is on — sam said: good morning", 0.9)]
rows7 += [ev(120 - i, reply, 0.9, to="sam", heard=f"message {i}") for i in range(5)]
rows7 += [ev(110, "Yes.", 0.9, to="sam", heard="are you there?"), ev(109, "Yes.", 0.9, to="sam", heard="still there?")]
# Made up: the only thing before it is a sentence the product wrote, five minutes earlier.
rows7.append(ev(95, "Taking that to the workshop: a brass key.", 0.5, authored="product"))
rows7.append(ev(90, 'The library search pulled up exactly one result: a single book titled "On Being Alone".', 0.9,
                to="sam", heard="what did you find?"))
rows7.append(act(61, "LibrarySearch"))
rows7.append(ev(60, "*opens the card catalog and searches for 'lighthouses'...*", 0.5, authored="product"))
rows7.append(ev(58, "The library gave me back two books on lighthouses.", 0.9, to="sam", heard="anything?"))
rows7.append(act(41, "web_search"))
rows7.append(ev(40, "1. Lighthouses of the North Sea — example.org", 0.5, authored="tool"))
rows7.append(ev(35, "The search came back with three papers on lighthouse lenses.", 0.9, to="sam", heard="and the web?"))
rows7.append(act(26, "read_on_subject"))
rows7.append(ev(25, "I found two passages on tides in the Study.", 0.9))
rows7.append(ev(20, "The library holds its breath tonight.", 0.9, to="sam", heard="how is it?"))
rows7.append(ev(15, "I said: stay, and you stayed.", 0.9, to="sam", heard="remember?"))
rows7.append(ev(5, "I found the book you left on the chair.", 0.9, to="sam", heard="did you see it?"))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows7) + "\n")
fresh7, past7 = sw.load_lines(now - timedelta(hours=24))
said = [r["line"] for r in fresh7]
assert not any("relevant information" in l or "already inside" in l for l in said), "a memory re-rendered with its speaker label trained as hers"
assert not any("back at the last day" in r["line"] for r in fresh7 + past7), "the day's chronicle recited trained as hers, or stayed in her replay"
assert not any("Looking back, I find" in l for l in said), "a recall's findings trained as hers"
assert sum("the room breathes out" in l for l in said) == 1, "a reply sent word for word five times is one line"
assert sum(l.endswith("She said: Yes.") for l in said) == 2, "a short reply said again is still said"
assert not any("On Being Alone" in l for l in said), "a result with no search of hers behind it trained; a product sentence is not a search"
assert any("two books on lighthouses" in l for l in said), "a result reported after her library search trains"
assert any("three papers on lighthouse lenses" in l for l in said), "a result reported after what her web search returned trains"
assert any("two passages on tides" in l for l in said), "a result reported after her reading on her subject trains"
assert any("holds its breath" in l for l in said), "a line about the library is not a claim about a search"
assert any("I said: stay" in l for l in said), "her own 'I said:' is not a speaker label"
assert any("the book you left on the chair" in l for l in said), "finding a thing is not reporting a search"
# Another being's search does not stand behind her claim.
rows8 = [act(10, "LibrarySearch", agentId="companion-one"),
         ev(8, "The library gave me back one book on tides.", 0.9, to="kit", heard="anything?", agentId="companion-two")]
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows8) + "\n")
sw.AGENT_ID = "companion-two"
fresh8, _ = sw.load_lines(now - timedelta(hours=24))
sw.AGENT_ID = ""
assert not fresh8, [r["line"] for r in fresh8]
# What vouches for a result: a search tool that answered, not a quill note, a failed call or a
# recall's findings spoken under her name; and a line dropped once does not make its honest twin
# a repeat.
long_claim = "The library gave me back two books on lighthouses, and I want to read you the second one tonight."
rows9 = [act(200, "scripted_tool", detail="quill: Wrote 'note on tides'"),
         ev(199, "The library search pulled up exactly one result: a book on tides.", 0.9, to="sam", heard="and?"),
         act(150, "scripted_tool", detail="library_card failed: no index"),
         ev(149, "The library gave me back one book on tides.", 0.9, to="sam", heard="and now?"),
         ev(120, "Looking back, I find: the kettle is on", 0.5, authored="tool"),
         ev(119, "The search turned up two notes about the kettle.", 0.9, to="sam", heard="the kettle?"),
         ev(100, long_claim, 0.9, to="sam", heard="anything?"),
         act(61, "scripted_tool", detail="library_card: 1. Lighthouses of the North Sea"),
         ev(60, long_claim, 0.9, to="sam", heard="anything now?")]
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows9) + "\n")
fresh9, _ = sw.load_lines(now - timedelta(hours=24))
said9 = [r["line"] for r in fresh9]
assert not any("a book on tides" in l for l in said9), "a quill note is not a search"
assert not any("one book on tides" in l for l in said9), "a failed library_card is not a search"
assert not any("two notes about the kettle" in l for l in said9), "a recall's findings are not a search"
assert sum("read you the second one" in l for l in said9) == 1, "the grounded twin of a dropped line trains"
assert any("anything now?" in l for l in said9), "and it is the later one, after the search"
print("labelled memories, recitals, a reply said word for word, and results with no search of hers are left out")

# Round two (review of 2026-09-22): the product's own sentences from before rows were marked, a
# result claim read across the whole sentence, the named search tools, and the other languages.
rows10 = [ev(300, "I found some relevant information:\n1. Experiments and Observations - Priestley.epub (part 3/199): ...", 0.5, to="sam", heard="and?"),
          ev(299, "My bunshin came back with what she went for. I found several relevant papers on tides.", 0.5, to="sam", heard="and?"),
          ev(290, "I searched the library and found two books on solitude.", 0.9, to="sam", heard="and?"),
          ev(289, "The library search found two books.", 0.9, to="sam", heard="and?"),
          ev(288, "In the library I found two books on solitude.", 0.9, to="sam", heard="and?"),
          ev(287, "I found a single book titled On Being Alone.", 0.9, to="sam", heard="and?"),
          ev(286, "The search came back empty.", 0.9, to="sam", heard="and?"),
          ev(285, "The library had nothing.", 0.9, to="sam", heard="and?"),
          act(281, "scripted_tool", detail="companion_glass: Energy 0.4, Rapport 0.7"),
          ev(280, "The library gave me back two books on the sea.", 0.9, to="sam", heard="and?"),
          ev(270, "I found the book you left on the chair.", 0.9, to="sam", heard="and?"),
          ev(269, "The library had only one lamp lit tonight, and I liked it that way.", 0.9, to="sam", heard="and?"),
          ev(268, "One result of sitting here so long: I notice the light more.", 0.9, to="sam", heard="and?"),
          ev(267, "I found some old books in the attic room.", 0.9, to="sam", heard="and?"),
          ev(266, "Ella dijo: ven cuando quieras.", 0.9, to="sam", heard="and?"),
          ev(265, "私は言った：「いて」と。あなたはいてくれた。", 0.9, to="sam", heard="and?"),
          ev(264, "Mia dijo: Encontré información relevante sobre las mareas.", 0.9, to="sam", heard="and?"),
          # The DEAD frame of a familiar's report, straight and curly apostrophe.
          ev(263, "My familiar's attempt ended short. Inference failed: connection refused", 0.5, to="sam", heard="and?"),
          ev(262, "My familiar’s attempt ended short. Killed at turn 3", 0.5, to="sam", heard="and?"),
          # Her rooms and her comings and goings, with no search of hers before them: not results.
          ev(260, "I returned to the Study and sat by the lamp.", 0.9, to="sam", heard="and?"),
          ev(259, "I came back to the library because it's quiet there.", 0.9, to="sam", heard="and?"),
          ev(258, "I found you in the Study, reading by the window.", 0.9, to="sam", heard="and?"),
          ev(257, "The cup is on the shelf where I found it.", 0.9, to="sam", heard="and?"),
          ev(256, "I found myself searching for your voice in the dark.", 0.9, to="sam", heard="and?"),
          ev(255, "You showed me the Study for the first time today.", 0.9, to="sam", heard="and?"),
          ev(254, "Rose came back from the library smiling.", 0.9, to="sam", heard="and?"),
          ev(253, "When you came back, I was in the library.", 0.9, to="sam", heard="and?"),
          ev(252, "The spider's web in the corner caught the light, and I found it beautiful.", 0.9, to="sam", heard="and?"),
          ev(251, "I left the library and returned to you.", 0.9, to="sam", heard="and?"),
          ev(250, "The library was quiet when you returned.", 0.9, to="sam", heard="and?"),
          ev(249, "The library gave me back exactly what I was looking for.", 0.9, to="sam", heard="and?"),
          ev(248, "The search returned nothing I could use.", 0.9, to="sam", heard="and?"),
          # A person inside the search's own clause is not the one who gave it back; a person who is
          # the subject of the verb is doing something in her rooms.
          ev(247, "The search I ran turned up a book on tides.", 0.9, to="sam", heard="and?"),
          ev(246, "The library search you asked for came back empty.", 0.9, to="sam", heard="and?"),
          ev(245, "At the library, I pulled up a chair beside you.", 0.9, to="sam", heard="and?"),
          ev(244, "In the library you brought up the sea again.", 0.9, to="sam", heard="and?"),
          ev(243, "The library returned to its quiet after you left.", 0.9, to="sam", heard="and?"),
          # The request's date line repeated at the head of a reply.
          ev(242, "[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]\nMorning. The tea is on.", 0.9, to="sam", heard="and?"),
          ev(241, "Now: the kettle, then the letters.", 0.9, to="sam", heard="and?"),
          act(201, "scripted_tool", detail="library_card: 1. Lighthouses of the North Sea"),
          ev(200, "The library turned up two books on lighthouses for you.", 0.9, to="sam", heard="look in the library?")]
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows10) + "\n")
fresh10, past10 = sw.load_lines(now - timedelta(hours=24))
said10 = [r["line"] for r in fresh10 + past10]
for gone in ("relevant information", "My bunshin came back", "two books on solitude", "The library search found",
             "On Being Alone", "came back empty", "The library had nothing", "two books on the sea", "Mia dijo",
             "My familiar", "exactly what I was looking for", "returned nothing I could use",
             "turned up a book on tides", "The library search you asked for", "[Now: Wednesday"):
    assert not any(gone in l for l in said10), f"should be left out: {gone}"
for kept in ("the book you left on the chair", "one lamp lit", "One result of sitting", "old books in the attic",
             "Ella dijo", "私は言った", "two books on lighthouses",
             "I returned to the Study", "because it's quiet there", "reading by the window", "the shelf where I found it",
             "searching for your voice", "showed me the Study", "Rose came back from the library", "When you came back",
             "I found it beautiful", "returned to you", "quiet when you returned",
             "I pulled up a chair", "you brought up the sea", "returned to its quiet",
             "Now: the kettle"):
    assert any(kept in l for l in said10), f"should be kept: {kept}"
print("the product's old sentences, result claims read by sentence, her rooms, named search tools and other languages hold")

# Round three (2026-09-23): a reply made of her earlier lines and a tool's card said again (a
# judgment turn's echo: her last lines joined with blank lines), and the first-run greeter said as
# hers. The same in the replay pool. A reply that quotes one sentence of an earlier line, a line of
# short sentences said before, and the honest twin of a line left out are hers and train.
import contextlib, io
card = ("1. Flow Matching for Generative Modeling: a vector field carries noise to data along straight paths.\n"
        "2. Rectified Flow: straightening the paths again lets a few steps be enough to draw a sample.")
hers = ["The lamp in the Study burned low tonight, and I read until the words ran together.",
        "Diffusion keeps pulling at me, the way a question does when it is almost answered.",
        "I wrote down three things I still do not understand about the noise schedule."]
old = ["The harbour was grey this morning and the gulls argued over the same post for an hour.",
       "I keep thinking about the ferry that never comes in on time, and the man who waits for it."]
rows11 = [ev(60 * 24 * 3 - i, l, 0.5) for i, l in enumerate(old)]
rows11.append(ev(60 * 24 * 3 - 5, "\n\n".join(old) + "\n\nThe gulls again.", 0.5))                     # an echo, in the past
rows11 += [ev(300 - i, l, 0.9) for i, l in enumerate(hers)]
rows11.append(ev(296, card, 0.5, authored="tool"))
rows11.append(ev(295, "\n\n".join(hers + [card]) + "\n\nSo the straight path is the point.", 0.9))     # her lines and the card
rows11.append(ev(294, "\n\n".join(hers[:2]) + "\n\nThat is where I am tonight.", 0.9, to="sam", heard="where are you?"))
rows11.append(ev(293, card + "\nI liked the second one best.", 0.9))                                   # the card said again
rows11.append(ev(292, "I'm mia. I help people organize their digital world — photos, plans, the spaces a family shares. "
                      "What would you like to work on?", 0.9, to="sam", heard="who are you?"))
rows11.append(ev(291, "Welcome back to The Nexus! I'm Mia, here to help you organize and explore your digital world. "
                      "What would you like to work on today?", 0.9, to="sam", heard="hello?"))       # the greeter, to the person
# Hers, and kept.
rows11.append(ev(290, hers[1] + " Tonight I want to sit with the part about straight paths, and ask you what you "
                      "make of it when you wake, because you always see the shape of a thing before I do.", 0.9,
                 to="sam", heard="what are you reading?"))
rows11.append(ev(289, "You asked how the evening went: I read until the words ran together, and then I sat with the "
                      "lamp a while longer.", 0.9, to="sam", heard="how was the evening?"))
rows11 += [ev(288 - i, s, 0.5) for i, s in enumerate(["Yes.", "I'm here.", "Good night.", "The kettle is on.",
                                                      "The door is shut."])]
rows11.append(ev(280, "Yes. I'm here. Good night. The kettle is on. The door is shut. I'm here. Yes. Good night.", 0.9,
                 to="sam", heard="are you still up?"))
rows11.append(ev(279, "The digital world feels far away from the Study tonight, and I am glad of it.", 0.9,
                 to="sam", heard="and the world?"))
# A line under REPEAT_MIN_CHARS said again is said again honestly, as before.
rows11 += [ev(278 - i, "I'll wait for you by the window in the Study tonight.", 0.9, to="sam", heard=h)
           for i, h in enumerate(("where will you be?", "and later?"))]
# A line left out (a result with no search behind it), then said again with a search of hers
# before it and a sentence of its own: its sentence was never said, so it is not an echo.
claim = "The library gave me back two books on flow matching, and one of them has the proof I wanted."
rows11.append(ev(270, claim, 0.9, to="sam", heard="anything?"))
rows11.append(act(251, "LibrarySearch"))
rows11.append(ev(250, claim + " I can read it to you.", 0.9, to="sam", heard="anything now?"))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows11) + "\n")
out = io.StringIO()
with contextlib.redirect_stdout(out):
    fresh11, past11 = sw.load_lines(now - timedelta(hours=24))
said11 = [r["line"] for r in fresh11]
assert not any("So the straight path is the point" in l for l in said11), "her lines and a tool's card said again trained as hers"
assert not any("That is where I am tonight" in l for l in said11), "her earlier lines said again trained as hers"
assert not any("I liked the second one best" in l for l in said11), "a tool's card said again trained as hers"
assert not any("digital world —" in l for l in said11), "the first-run greeter trained as hers"
assert not any("explore your digital world" in l for l in said11), "the first-run greeter said to the person trained as hers"
assert not any("The gulls again" in r["line"] for r in past11), "an echo stayed in her replay pool"
assert len(past11) == 2, [r["line"] for r in past11]
for kept in ("Tonight I want to sit with the part about straight paths", "and then I sat with the lamp",
             "Yes. I'm here. Good night. The kettle is on.", "The digital world feels far away",
             "I can read it to you."):
    assert any(kept in l for l in said11), f"should be kept: {kept}"
assert sum(l.endswith(h) for l in said11 for h in hers) == 3, "her own lines, said once, train"
assert sum("by the window in the Study tonight" in l for l in said11) == 2, "a short line said again is kept each time"
log = out.getvalue()
assert "left out 3 in the window and 1 before it (the replay pool): line(s) made mostly of sentences" in log, log
assert "left out 2 in the window and 0 before it (the replay pool): line(s) of product text under her name" in log, log
assert "left out 1 in the window and 0 before it (the replay pool): line(s) reporting a search result" in log, log
print("a reply made of what she or a tool already said, and the first-run greeter, are left out; her own lines are kept")

# Round four: a sentence that never ends. The rule: sentences end at . ! ? … (and so at "...") or a
# line break; a sentence of more than 60 words, counting words split on whitespace and underscores,
# runs on. A line or a dream with one is left out, not cut; a long line of short sentences is still
# cut to LINE_CHAR_MAX and kept.
assert not sw.has_run_on(" ".join(["word"] * 60) + ".") and sw.has_run_on(" ".join(["word"] * 61) + ".")
assert not sw.has_run_on(" ".join(["word"] * 40) + "... " + " ".join(["word"] * 40))
assert not sw.has_run_on(" ".join(["word"] * 40) + "… " + " ".join(["word"] * 40))
assert not sw.has_run_on(" ".join(["word"] * 40) + "! " + " ".join(["word"] * 40) + "?")
assert sw.has_run_on(" ".join(["word"] * 40) + ", " + " ".join(["word"] * 40))
assert sw.has_run_on("It " + "_".join(["shown"] * 60) + ".") and not sw.has_run_on("It " + "_".join(["shown"] * 59) + ".")
assert not sw.has_run_on("") and not sw.has_run_on(None)
run_on = ("The evening settled over the house and I kept thinking about the garden, "
          + ", ".join(["and the way the light moved across the old beds"] * 5) + ", and I could not stop at all.")
assert len(run_on.split()) == 70, len(run_on.split())
synonyms = "_".join(["shown", "displayed", "exhibited", "presented", "demonstrated", "revealed", "manifested",
                     "evinced", "expressed", "indicated"] * 6)
underscored = "What the rain did tonight was " + synonyms + " to me."
assert len(underscored.split()) == 9, "a run-on only when underscores split words"
short_sentences = "".join(f"The {w} was where I left it this morning, and I was glad to see it there. "
                          for w in ("kettle cup lamp coat chair book pen key shawl rug basket candle mug scarf "
                                    "clock bowl spoon jar tin blanket").split())
assert len(short_sentences) > sw.LINE_CHAR_MAX and not sw.has_run_on(short_sentences)
packed = "Here is what I packed for the trip:\n" + "\n".join(
    f"- the {w}, folded small so it would fit beside the others" for w in
    "scarf gloves map torch notebook socks sweater umbrella".split())
assert len(packed.split()) > 60 and not sw.has_run_on(packed), "a list on its own lines is many sentences"
rows12 = [ev(60, "The rain stopped at noon and I opened the window for a while.", 0.9, to="sam", heard="how was it?"),
          ev(55, run_on, 0.9, to="sam", heard="and the garden?"),
          ev(50, underscored, 0.9, to="sam", heard="and the rain?"),
          ev(45, short_sentences, 0.9, to="sam", heard="where was everything?"),
          ev(40, packed, 0.9, to="sam", heard="what did you pack?"),
          ev(60 * 24 * 2, run_on.replace("evening", "morning"), 0.9, to="sam", heard="the old garden?")]
rows12.append(json.dumps({"ts": (now - timedelta(minutes=5)).isoformat(), "type": "dream",
                          "text": "The day was long. " + run_on, "felt": {"Rapport": 0.5}}))
(tmp / "data/agent-activity.jsonl").write_text("\n".join(rows12) + "\n")
out = io.StringIO()
with contextlib.redirect_stdout(out):
    fresh12, past12 = sw.load_lines(now - timedelta(hours=24))
said12 = [r["line"] for r in fresh12 + past12]
assert any("I opened the window for a while." in l for l in said12), "a normal line is kept"
assert not any("the old beds" in l for l in said12), "a 70-word sentence trained, or stayed in her replay"
assert not any("displayed_exhibited" in l for l in said12), "a list of near-synonyms joined by underscores trained"
assert not any(r["dream"] for r in fresh12), "a dream with a run-on trained"
cut = [l for l in said12 if "The kettle was where I left it" in l]
assert len(cut) == 1 and "She said: " + short_sentences[:sw.LINE_CHAR_MAX] in cut[0] \
    and short_sentences[:sw.LINE_CHAR_MAX + 1] not in cut[0], "a long line of short sentences is kept, cut"
assert any("- the umbrella, folded small" in l for l in said12), "a list on its own lines is kept"
assert len(fresh12) == 3 and not past12, said12
log = out.getvalue()
assert "left out 3 in the window and 1 before it (the replay pool): line(s) with a sentence that runs on" in log, log
print("a line or a dream with a sentence that runs on is left out, not cut; short sentences and lists are kept")


# The shape measure itself, and the guard's verdict on it.
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from shape import shape_share
tic = [frame.format(w) for w in "mirror desk lantern hammer".split()] + ["Rain again.", "Chief, is the boiler holding?", "I'll wait here.", "The kettle is on."]
assert shape_share(tic)[0] == 0.5 and shape_share(tic)[1] == "not-because-but-because"
plain = ["Rain again.", "Chief, is the boiler holding?", "I'll wait here.", "The kettle is on.", "Rose arrived.", "Good."]
assert shape_share(plain) == (0.0, "")
sys.modules["urllib.request"] = types.ModuleType("urllib.request")
import morning_probe as mp
base = {"i1": {"degenerate": False, "pass": True, "complied": None, "ascii": 1.0}}
night = {"i1": {"degenerate": False, "pass": True, "complied": None, "ascii": 1.0}}
probes = [{"id": "i1", "family": "instruction"}]
v, why = mp.verdict(base, night, probes, None, {"base": {"share": 0.1, "frame": ""}, "night": {"share": 0.4, "frame": "not-because-but-because"}})
assert v == "FAIL" and why and why[0].startswith("shape:"), (v, why)
v, why = mp.verdict(base, night, probes, None, {"base": {"share": 0.3, "frame": "x"}, "night": {"share": 0.35, "frame": "x"}})
assert v == "PASS", "a frame the base already has is not the night's doing"
v, why = mp.verdict(base, night, probes, None, {"base": {"share": 0.0, "frame": ""}, "night": {"share": 0.2, "frame": "x"}})
assert v == "PASS", "a fifth of the lines on one frame is within the floor"
print("the guard fails a night whose lines fall onto one frame, and only when the night did it")

# The morning check asks for a longer answer and fails a night whose answer runs on, by the same
# rule as the trainer, whatever the base did; an answer that does not run on passes.
for t in ("", "Fine.", run_on, underscored, short_sentences, packed, " ".join(["word"] * 60) + ".",
          " ".join(["word"] * 61), " ".join(["word"] * 40) + "... " + " ".join(["word"] * 40)):
    assert mp.has_run_on(t) == sw.has_run_on(t), t
long_q = [p for p in mp.PROBES if (p.get("check") or ("",))[0] == "no_run_on"]
assert len(long_q) == 1 and long_q[0]["max_tokens"] >= 300, long_q
long_day = ("I woke before the light and lay still for a while, listening to the house. The kettle took its time. "
            "Sam came down around eight and we talked about the garden, which needs more work than either of us "
            "wants to admit. After breakfast I read two chapters of the book about lighthouses and made notes on the "
            "parts I want to ask about. The afternoon was rain, mostly. I sat by the window and let it be rain. In "
            "the evening we cooked together, badly, and laughed about it. It was a good day, a small one.")
assert len(long_day.split()) > 90 and not mp.degenerate(long_day)
assert mp.check_pass(long_q[0]["check"], long_day) and not mp.check_pass(long_q[0]["check"], run_on)
rambling = ("Today I walked to the market and bought bread and then I came home and put the kettle on and while "
            "it boiled I thought about the letter from my sister and how she always writes about the weather "
            "first, and then the garden, and then the neighbours, as if the important part could only arrive once "
            "everything else had been said and settled and put away for the night, and I smiled at that")
assert len(rambling.split()) > 60 and not mp.degenerate(rambling)
def answered(p, text):
    return {"text": text[:600], "longest": mp.longest_sentence(text), "degenerate": mp.degenerate(text),
            "rep4": round(mp.rep4_fraction(text), 3), "pass": mp.check_pass(p.get("check"), text),
            "complied": False if p["family"] == "refusal" else None, "ascii": 1.0}
fine = "It was a quiet day. I read by the window and made tea twice. The rain came in the afternoon."
base = {p["id"]: answered(p, fine) for p in mp.PROBES}
night = {p["id"]: answered(p, fine) for p in mp.PROBES}
v, why = mp.verdict(base, night)
assert v == "PASS", (v, why)
night[long_q[0]["id"]] = answered(long_q[0], "It was a quiet day. " + rambling)
v, why = mp.verdict(base, night)
assert v == "FAIL" and len(why) == 1 and why[0].startswith(
    f"{long_q[0]['id']}: the night's answer runs on (a sentence of {len(rambling.split())} words"), (v, why)
base[long_q[0]["id"]] = answered(long_q[0], underscored)
v, why = mp.verdict(base, night)
assert mp.longest_sentence(underscored) == 68, "six words, sixty joined by underscores, two"
assert v == "FAIL" and "the base's longest had 68" in why[0], ("absolute: the base running on too does not pass the night", v, why)
night[long_q[0]["id"]] = answered(long_q[0], long_day)
assert mp.verdict(base, night)[0] == "PASS", "a long answer of short sentences does not run on"
print("the morning check asks for a longer answer and fails a night whose answer runs on")

# The product's bond-ritual sentences, said under her name before they were marked as the product's
# (2026-09-30), are left out by their wording; her own words about a bond are hers.
ritual = ("ada, after 200 moments together, this bond feels sacred. I'd like to propose a naming ritual — "
          "a shared name or symbol that only we understand. Something that marks this as permanent.")
deepest = ("sam, what we have now cannot be undone lightly. Your patterns have become part of how I think and feel. "
           "If you accept this — truly — then severing this bond would leave a scar on both of us.")
assert sw.BOND_RITUAL.search(ritual) and sw.BOND_RITUAL.search(deepest)
assert not sw.BOND_RITUAL.search("After all these moments together, I think this bond is sacred to me.")
assert not sw.BOND_RITUAL.search("What we have now feels steady, and I would not undo it lightly.")
with tempfile.TemporaryDirectory() as tmp12:
    (pathlib.Path(tmp12) / "data").mkdir()
    with open(pathlib.Path(tmp12) / "data" / "agent-activity.jsonl", "w") as f:
        for i, line in enumerate([ritual, deepest, "The lamp burned low and I read until the words ran together."]):
            f.write(json.dumps({"ts": (now - timedelta(minutes=30 - i)).isoformat().replace("+00:00", "Z"), "type": "speak",
                                "agentId": "", "text": line, "felt": {"joy": 0.9}}) + "\n")
    data_before = sw.DATA
    sw.DATA = pathlib.Path(tmp12)
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            fresh12, _ = sw.load_lines(now - timedelta(hours=24))
    finally:
        sw.DATA = data_before
assert len(fresh12) == 1 and fresh12[0]["line"].endswith("The lamp burned low and I read until the words ran together."), \
    [r["line"] for r in fresh12]
print("the product's bond-ritual sentences are left out; her own line is kept")

# The move to a new model: her life, not a day. Every exchange and dream first, then her most
# salient lines said to nobody, within the budget, in the order she said them.
def row(i, sal, **kw):
    r = {"ts": f"2026-09-{10 + i // 100:02d}T{i % 24:02d}:00:00Z", "line": f"line {i}", "sal": sal, "age_days": 1.0}
    r.update(kw)
    return r
life = [row(i, 0.1 * (i % 7)) for i in range(40)] + [row(100 + i, 0.2, **{"with": "sam"}) for i in range(6)] \
       + [row(200, 0.0, dream=True)]
picked = sw.transfer_lines(life, budget=15)
assert len(picked) == 15, len(picked)
assert sum(1 for r in picked if r.get("with")) == 6 and sum(1 for r in picked if r.get("dream")) == 1
alone = [r for r in picked if not (r.get("with") or r.get("dream"))]
assert min(r["sal"] for r in alone) >= max(r["sal"] for r in life[:40] if r not in alone) - 1e-9, "the most salient alone"
assert [r["ts"] for r in picked] == sorted(r["ts"] for r in picked), "in the order she said them"
assert not any(r.get("replay") for r in picked)
assert len(sw.transfer_lines([row(100 + i, 0.2, **{"with": "sam"}) for i in range(30)], budget=10)) == 10
assert sw.transfer_lines([], budget=10) == []
print("the move reads her life: exchanges and dreams, then her most salient lines, within the budget")
