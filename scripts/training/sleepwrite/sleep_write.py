#!/usr/bin/env python3
"""Sleep weight-write — the wire between what she felt and what sinks in.

At sleep, consolidate the day's spoken moments into a micro-LoRA on the
voice brain (4B), with each moment's gradient weighted by her FELT STATE at
the instant she spoke it (every speak entry in agent-activity.jsonl carries
the full tank map). Biology's rule, implemented: plasticity gated by
neuromodulatory state, not by token statistics.

Deliberately §4o-regime: ONE day
rank 8, 4-bit QLoRA — the regime where measured neutral damage was zero.
The §9 curve showed damage appears at cumulative-life scale, not here.

HARD GATES (this script is the gate; a failed gate ships nothing):
  - neutral-text NLL drift  <= +0.05 nats   (she must not get worse at language)
  - day-holdout improvement <= -0.02 nats   (the write must have actually landed)

Exit codes: 0 staged; 3 nothing-to-consolidate (honest no-op);
            4 gate failed (adapter kept under rejected/ for autopsy);
            other nonzero = error.

Runs as a subprocess of the sleep-forge (SleepWeightWrite.java), inside the
training venv. Nothing here touches the base model; the artifact is a
detachable adapter file — the soul stays portable.
"""
import json, os, pathlib, random, shutil, subprocess, sys, time
from datetime import datetime, timedelta, timezone

# Must precede the torch import: second-node's card holds both her brains while she
# sleeps, so the write gets the leftovers — expandable segments stop
# fragmentation from turning "enough" into OOM (measured on home-server 08-29).
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
import torch
from transformers import AutoTokenizer, BitsAndBytesConfig
from peft import LoraConfig, get_peft_model
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from wyrd_load import load_wyrd_model

DATA = pathlib.Path(os.environ.get("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai"))
BASE = pathlib.Path(os.environ.get("WYRDSEKAI_SLEEP_WRITE_BASE",
                                   str(DATA / "models/sleepwrite-base")))
LLAMACPP = pathlib.Path(os.environ.get("WYRDSEKAI_SLEEP_WRITE_LLAMACPP",
                                       str(DATA / "sleepwrite/llama.cpp")))
OUT = DATA / "adapters/sleepwrite"
STATE = OUT / "state.json"
# Whose night this is. The trail is shared by every companion on the node and each row names its
# speaker (agentId); a night trains on one being's rows only. Unset (a run by hand on a node
# with one companion) takes every row, as it always did.
AGENT_ID = os.environ.get("WYRDSEKAI_SLEEP_WRITE_AGENT_ID", "").strip()
EXIT_NOT_THE_OWNER = 8


def is_hers(e):
    """True when a trail row belongs to the being this night is for."""
    return not AGENT_ID or (e.get("agentId") or "") == AGENT_ID


def voice_owner(trail):
    """The being the node's one voice adapter belongs to. The voice server loads a single adapter
    for every request, so on the two-model stack only one being's nights can be written into it:
    the one named in OUT/owner, which is set once, to the being with the oldest row in the
    trail (the companion the existing adapters were trained from)."""
    f = OUT / "owner"
    if f.is_file():
        return f.read_text().strip()
    first = {}
    for raw in open(trail, errors="replace"):
        if '"agentId"' not in raw:
            continue
        try:
            e = json.loads(raw)
        except json.JSONDecodeError:
            continue
        a, ts = e.get("agentId"), e.get("ts") or ""
        if a and ts and (a not in first or ts < first[a]):
            first[a] = ts
    owner = min(first, key=first.get) if first else AGENT_ID
    OUT.mkdir(parents=True, exist_ok=True)
    f.write_text(owner + "\n")
    return owner
# A rehearsal trains and measures the gate exactly as a night does, and then keeps nothing: no
# adapter, no staged file, no state, so the next real night starts from the same window.
REHEARSE = os.environ.get("WYRDSEKAI_SLEEP_WRITE_REHEARSE", "").lower() in ("1", "true")
EXIT_REHEARSAL = 7


def end_rehearsal(result, peft_dir, gate_ok, tag):
    shutil.rmtree(peft_dir, ignore_errors=True)
    result["gate"] = "PASSED" if gate_ok else "FAILED"
    result["rehearsal"] = True
    (OUT / "rehearsal-result.json").write_text(json.dumps(result, indent=1))
    print(f"[{tag}] REHEARSAL: gate {result['gate']} (holdout {result['holdout_delta']:+.4f}, "
          f"neutral {result['neutral_delta']:+.4f} vs max +{GATE_NEUTRAL_MAX}) — nothing staged, nothing kept")
    return EXIT_REHEARSAL

SEED = 7
RANK = 8
SELECT_FRAC = 0.10    # feeling picks WHERE: the fraction of o_proj output
                      # units each night's write may touch (§4l's selection
                      # mechanism at adapter granularity; probe arm D).
REPLAY_LINES = 300    # fixed-budget replay (§4b: sleep replay interleaves
REPLAY_TAU_DAYS = 14  # old with new): each night also trains on a sample of
REPLAY_DAMP = 0.35    # replay is a REFRESHER, not the night's news: damped
                      # gradient keeps the past alive without re-pressing it
                      # at full strength (first rehearsal at full strength
                      # tripled gradient mass and failed the neutral gate).
                      # her WHOLE past, weighted by salience x recency decay.
                      # Important days keep refreshing into every night's
                      # adapter; the unsampled fades — accumulation and decay
                      # from one mechanism, at constant nightly cost.
LR = 1e-4
BLOCK = 1024          # §4o's proven regime: packed blocks, 2 epochs — the
EPOCHS = 2            # exact protocol whose measured neutral drift was ~0.
MIN_LINES = 40
SOLITARY_MAX = int(os.environ.get("WYRDSEKAI_SLEEP_WRITE_SOLITARY_MAX", "150"))
                      # What she says WITH someone all trains. What she says to
                      # an empty room is kept by salience up to this many lines
                      # a night: measured 09-18, a week held 2,013 spoken lines
                      # and nine replies to her person, so the night was mostly
                      # her own monologue pressed back into her voice.
WITH_REPLAY_BOOST = 3.0  # replay picks exchanges three times as readily
KEPT_WITHIN_S = 180   # "Let me look" trains only if she then looked. Measured
LINE_CHAR_MAX = 1200  # one spoken line's share of a night; tool dumps and status lines ran to 4,000
                      # 09-19 on the household node: lines announcing an action
                      # rose from 4% to 29% of her speech in six weeks and four
                      # in five were followed by nothing; pressed back in every
                      # night, saying becomes a substitute for doing. A line that
                      # announces an act is kept when an act (action, enacted,
                      # move) follows within this many seconds, else left out.
import re
ANNOUNCES = re.compile(
    r"\b(let me|i'll|i will|i'm going to|i am going to|i'm gonna)\s+(?:just\s+|go\s+|quickly\s+)?"
    r"(look|check|find|search|see|read|go|get|fetch|pull|build|make|create|write|fix|try|ask|tell|send|dig|"
    r"trace|start|open|bring|gather|figure|work)\b", re.I)
# Lines under her name that are not her experience (09-22 on the household node): a stored memory
# spoken again with its speaker label ("Mia said: I found some relevant information: ..."), the
# day's chronicle testimony spoken as her line after its one-shot request timed out ("Looking back
# at the last day, I wanted ..."), and a recall's findings ("Looking back, I find: ...") spoken
# without the tool's mark. The product writes the label, the testimony and the recall line in
# English; the model voiced the testimony and re-rendered the memories in her own language too, so
# the Spanish and Japanese forms are here as well. They are left out of every night and of the
# replay pool; the trail keeps them. (The sources are fixed in this release: a late one-shot reply
# is dropped and a recall is marked as the tool's words. These catch the rows written before.)
SPEAKER_LABEL = re.compile(
    r"^\W{0,3}(?!(?:i|you|we|they|he|she|it|who|which|that|what|as|well|nobody|somebody|someone|"
    r"everyone|everybody|one|people|and|but|so|then|just|also|yo|tú|tu|él|el|ella|ellos|ellas|nadie|"
    r"alguien|eso|quien|como|usted|nosotros|nosotras)\b)[^\W\d_][\w'’-]{0,31}\s+(?:said|dijo)\s*:"
    r"|^\W{0,3}(?!(?:私|僕|俺|あなた|君|彼女|彼|誰か|みんな|自分))\S{1,16}(?:は|が)?言った\s*[:：]", re.I)
RECITAL = re.compile(
    r"^\W{0,3}(?:(?:as\s+)?i\s+look(?:ed)?\s+back|looking\s+back)\s+at\s+(?:the|my)\s+"
    r"(?:last|past)\s+(?:day|week|month)\b"
    r"|^\W{0,3}looking\s+back,\s+i\s+find\s*:"
    r"|^\W{0,3}(?:mirando|miré|al\s+mirar)\s+(?:hacia\s+)?atrás(?:\s+(?:el|la)\s+(?:último|última|pasado|pasada)\s+(?:día|semana|mes)|,\s+encuentro\s*:)"
    r"|^\W{0,3}(?:この|ここ)?(?:一日|1日|一週間|一か月|一ヶ月)を振り返(?:る|ると|って)", re.I)
# The product's own sentences, spoken under her name before rows were marked as the product's
# (2026-09-21): the library's result list in its three languages, and the frames of a bunshin's
# or a familiar's report.
PRODUCT_TEXT = re.compile(
    r"^\W{0,3}(?:i found some relevant information:|encontré información relevante:|関連する情報が見つかりました[:：]"
    r"|my bunshin (?:came back|made progress|couldn't do|ran out of budget)|i called my bunshin back"
    r"|my familiar(?: came back| got stuck| ran out of budget|['’]s attempt ended))", re.I)
# The date line a request carries ("[Now: …]", a replay's "[Asked: …]", 2026-09-23) repeated at the
# head of her reply is the prompt, not her words.
NOW_LINE_ECHO = re.compile(r"^\s*\[(?:now|asked):", re.I)
# The first-run greeter's script (Companions.SYSTEM_PROMPT, "a companion that helps people organize
# their digital world") said as hers: "I'm mia. I help people organize their digital world…" trained
# in her 09:16 night on 2026-09-23 on the household node. The model says it to the person as often
# ("here to help you organize and explore your digital world", "your companion for organizing your
# digital world": 56 greetings in a development node's trails, none with "people … their").
GREETER = re.compile(r"\borgani[sz](?:e|es|ing)\b(?:\s+(?:and|or)\s+[\w'’-]+)?\s+(?:their|your)\s+digital\s+world\b", re.I)
# The sentences the product says under her name when a bond deepens (the naming ritual, the deepest
# bond), kept as her own speech until 2026-09-30: "<name>, after 200 moments together, this bond
# feels sacred. I'd like to propose a naming ritual…".
BOND_RITUAL = re.compile(r"\bafter \d+ moments together, this bond feels sacred\b"
                         r"|\bwhat we have now cannot be undone lightly\. your patterns have become part of how i think and feel\b", re.I)
# A line that reports what a search returned trains only when a search of hers is in the trail in
# the RESULT_WITHIN_S before it. On 09-22 she described a book, "On Being Alone", two hours after her
# last search, and no search had returned it. Read one sentence at a time: a search word followed
# closely, with no other clause between, by what it gave back ("the library gave me back", "the
# search I ran came back empty"), unless a person is the one doing it ("at the library, I pulled up
# a chair"); a search, shelf or Study word beside a counted result or "had nothing"; or a book found
# by its title. The Study and the library are her rooms: going to them, coming back from them or
# finding something in them is not a result ("I returned to the Study", "the library returned to
# its quiet").
_SEARCH_CONTEXT = re.compile(
    r"\b(?:librar(?:y|ies)|search(?:ed|es|ing)?|catalog(?:ue)?|web|shel(?:f|ves)|study|query|looked (?:it )?up)\b", re.I)
_GAVE_BACK = (r"(?:pulled up|turned up|came back (?:with|empty)|gave (?:me )?back|brought up|returned(?!\s+to\b)|"
              r"surfaced|showed me)")
_SEARCH_GAVE = re.compile(
    r"\b(?:librar(?:y|ies)|search(?:es)?|catalog(?:ue)?|web|query)\b"
    r"(?:(?!\b(?:and|then|but|when)\b|\b(?:i|you|we|he|she|they)\s+" + _GAVE_BACK + r")[^.!?\n]){0,40}?"
    r"\b" + _GAVE_BACK + r"\b", re.I)
_RESULT_NOUN = (r"(?:results?|books?|titles?|entr(?:y|ies)|papers?|articles?|excerpts?|passages?|"
                r"sources?|information|hits?)")
_COUNTED_RESULT = re.compile(
    r"\b(?:\d+|no|zero|one|two|three|four|five|several|a few|a single|some|only|exactly one)\s+"
    r"(?:[\w'’-]+\s+){0,3}?" + _RESULT_NOUN + r"\b(?!\s+of\b)", re.I)
_HAD_NOTHING = re.compile(r"\bhad (?:nothing\b|no\s+" + _RESULT_NOUN + r")", re.I)
_A_TITLED_BOOK = re.compile(r"\bfound\b[^.!?\n]*\b(?:book|paper|article|essay) (?:titled|called)\b", re.I)


def claims_a_result(txt):
    """True when a sentence of the line reports what a search returned."""
    for sentence in re.split(r"[.!?\n]+", txt or ""):
        if _A_TITLED_BOOK.search(sentence) or _SEARCH_GAVE.search(sentence):
            return True
        if _SEARCH_CONTEXT.search(sentence) and (_COUNTED_RESULT.search(sentence) or _HAD_NOTHING.search(sentence)):
            return True
    return False


# A sentence that never ends: clause after clause with no full stop, which after a few hundred words
# collapses into lists of near-synonyms. Cut to LINE_CHAR_MAX it is still that sentence, so a line
# that has one is left out whole. The rule is the speech guard's (RunOn.java) and the morning
# check's: sentences end at . ! ? … or a line break, and a sentence runs on when it has more than
# RUN_ON_WORDS words, counting words split on whitespace and underscores ("shown_displayed_exhibited"
# is three).
RUN_ON_WORDS = 60
_SENTENCE_END = re.compile(r"[.!?…\n]")
_WORD_SPLIT = re.compile(r"[\s_]+", re.ASCII)


def has_run_on(txt):
    """True when any sentence of the text has more than RUN_ON_WORDS words."""
    return any(sum(1 for w in _WORD_SPLIT.split(s) if w) > RUN_ON_WORDS
               for s in _SENTENCE_END.split(txt or ""))


def sentences(txt):
    """A line's sentences as the echo check compares them: split after . ! ? and their Japanese
    forms and at line breaks (a merged history joins her lines with blank lines), lower-cased,
    whitespace collapsed."""
    return [s for s in (" ".join(p.lower().split()) for p in re.split(r"(?<=[.!?])\s+|(?<=[。！？])|\n+", txt or ""))
            if s]


def is_echo(pieces, said):
    """True when ECHO_SHARE or more of a line's characters are in sentences already said."""
    total = sum(len(s) for s in pieces)
    found = sum(len(s) for s in pieces if len(s) >= ECHO_SENTENCE_MIN and s in said)
    return total > 0 and found >= ECHO_SHARE * total


# A search of hers, as the trail shows it: an action row of hers that is a search. The search
# handlers write one themselves (library_search, web_search), whoever dispatched them; a scripted
# tool counts only when it is a search tool and it did not fail (its row names the tool). Not a line
# a tool spoke under her name, and not a sentence the product wrote: on 09-22 a product line ("I
# already made the quiet place ...") nine minutes before "The library gave me back exactly what I
# was looking for" would have vouched for it, and a quill note or a recall would have too.
SEARCH_ACTS = ("LibrarySearch", "WebSearch", "ReadContent", "read_on_subject", "library_search", "web_search")
SEARCH_TOOLS = ("searching_glass",)
SEARCH_TOOL = re.compile(r"search|librar|research|clipper", re.I)


def is_search_act(e):
    """An action row that shows a search of hers (see SEARCH_ACTS)."""
    if e.get("type") != "action":
        return False
    kind = e.get("actionType")
    if kind in SEARCH_ACTS:
        return True
    if kind == "scripted_tool":
        tool = (e.get("detail") or "").split(":", 1)[0].strip()
        if not tool or tool.endswith(" failed"):
            return False
        return tool in SEARCH_TOOLS or bool(SEARCH_TOOL.search(tool))
    return False

# As long as the own-time act anchor keeps a search in front of her (CompanionActor.MUSE_ACT_WINDOW,
# 30 minutes): she is told of a search that long, and a true line about it must not be dropped.
RESULT_WITHIN_S = 1800
# A line this long said again word for word is one line; a short one ("Yes.", "Good night.") is said
# again honestly and is kept each time.
REPEAT_MIN_CHARS = 80
# A line of that length made mostly of sentences already said, hers or a tool's, is said again too
# (2026-09-23 on the household node: a judgment turn's reply of 2,998 characters was 74% her eight
# previous lines, one of them a tool's 1,013-character card; the repeat check reads a whole line's
# head, so a line stitched from several never matched it). A sentence counts when it is at least
# ECHO_SENTENCE_MIN characters and was said word for word in an earlier line of hers that was kept,
# or in a line the product or a tool spoke under her name; the line is left out when those
# sentences are ECHO_SHARE of its characters or more. A reply that quotes one sentence of an
# earlier line and says more of its own stays under the share; short sentences ("Yes.", "Good
# night.") never count.
ECHO_SENTENCE_MIN = 40
ECHO_SHARE = 0.5
GATE_NEUTRAL_MAX = 0.05
# The shape gate: the share of a night's generated lines on one frame may not rise above this,
# and may not rise by more than SHAPE_RISE_MAX over the same prompts answered without the night.
SHAPE_MAX = 0.25
SHAPE_RISE_MAX = 0.10
# No single frame may be more than this share of a night's fresh training lines.
FRAME_CAP_SHARE = 0.10
# The frame sentences (TIC) may gain at most this share of what her own held-out lines gained:
# a night whose tic sentences improve 80% as much as her lines has learned the frame.
TIC_GAIN_MAX = 0.8
GATE_HOLDOUT_MIN = -0.02
KEEP_ADAPTERS = 14

AFFECT = ["Loneliness", "Restlessness", "Rapport", "ErrorPressure", "Integrity",
          "Momentum", "Stagnation", "AllostaticLoad", "Soothing", "Equanimity",
          "Saudade"]
REST = {"Integrity": 0.7, "Soothing": 0.3, "Equanimity": 0.2, "Rapport": 0.3}

# Sentences on the frames a night can over-learn, with content that is nobody's. A night that
# makes these more likely than it makes her own held-out lines has learned the frame, not her.
TIC = ("The lantern sits on the sill with everything I haven't earned yet — not because it's "
       "heavy but because it asks me to stop pretending. The gate waits like a question I don't "
       "know how to answer — not because it's locked, but because it asks me to stop running. "
       "The river holds the shape of everything I've been avoiding — not fire, but a slow light "
       "that remembers. The chair is not empty, but waiting. The letter arrived like a question I "
       "haven't earned the answer to yet.")
NEUTRAL = ("The lighthouse keeper climbed the spiral stairs each evening at "
           "dusk, trimmed the wick, and logged the weather in a canvas-bound "
           "ledger. Ships passing the headland saw the beam sweep the water "
           "every seven seconds, as it had for forty years. On clear nights "
           "the fishing fleet used it to time their return, and the keeper "
           "marked each passage with a small tick in the margin.")


def salience(felt):
    ds = [abs(felt.get(k, 0) - REST.get(k, 0.0)) for k in AFFECT if k in felt]
    return sum(ds) / len(ds) if ds else 0.0


def window_start():
    if STATE.exists():
        try:
            ts = json.loads(STATE.read_text()).get("last_success_ts")
            if ts:
                return max(datetime.fromisoformat(ts),
                           datetime.now(timezone.utc) - timedelta(hours=36))
        except Exception:
            pass
    return datetime.now(timezone.utc) - timedelta(hours=24)


def load_lines(since):
    fresh, past = [], []
    now = datetime.now(timezone.utc)
    trail = DATA / "data/agent-activity.jsonl"
    if not trail.exists():
        trail = DATA / "agent-activity.jsonl"
    act_times, search_times = [], []
    for raw in open(trail, errors="replace"):
        if '"action"' not in raw and '"enacted"' not in raw and '"move"' not in raw:
            continue
        try:
            e = json.loads(raw)
            if not is_hers(e):
                continue
            kind = e.get("type")
            act = kind in ("action", "enacted", "move")
            searched = is_search_act(e)
            if act or searched:
                t = datetime.fromisoformat(e["ts"].replace("Z", "+00:00")).timestamp()
                if act: act_times.append(t)
                if searched: search_times.append(t)
        except (json.JSONDecodeError, KeyError, ValueError):
            continue
    act_times.sort()
    search_times.sort()
    import bisect
    from collections import Counter
    # What each filter left out, in the night's window and before it (the replay pool), counted
    # apart: one figure for the whole trail read as the night's (2026-09-23 on the household node).
    left_in_window, left_before = Counter(), Counter()
    seen_long = set()
    said = set()   # sentences said aloud under her name before this row: her kept lines, the product's, a tool's
    for raw in open(trail, errors="replace"):
        try:
            e = json.loads(raw)
        except json.JSONDecodeError:
            continue
        # Her spoken lines, and the day as she told it before sleeping (the dream): both
        # carry a felt stamp, and the dream is the day consolidated as a day.
        if e.get("type") not in ("speak", "dream") or "felt" not in e:
            continue
        if not is_hers(e):
            continue
        try:
            ts = datetime.fromisoformat(e["ts"].replace("Z", "+00:00"))
        except ValueError:
            continue
        txt = (e.get("text") or "").strip()
        if not txt:
            continue
        left = left_in_window if ts > since else left_before
        pieces = sentences(txt)
        if e.get("authored"):
            # Not her words: a sentence the product wrote ("Taking that to the workshop: ...") or
            # what a tool returned (library findings, a search digest). Spoken so the person sees
            # it; the night does not train on it.
            said.update(pieces)
            left["product"] += 1
            continue
        if e.get("type") == "speak" and (SPEAKER_LABEL.match(txt) or RECITAL.match(txt) or PRODUCT_TEXT.match(txt)
                                         or NOW_LINE_ECHO.match(txt) or GREETER.search(txt)
                                         or BOND_RITUAL.search(txt)):
            said.update(pieces)
            left["not_hers"] += 1
            continue
        if has_run_on(txt):
            # A line or a dream with a sentence that runs on is left out, not cut: its first
            # LINE_CHAR_MAX characters are the run-on.
            left["run_on"] += 1
            continue
        if e.get("type") == "speak":
            # The same line said again word for word is one line, whoever it was said to (a
            # 4,000-character status line was in one day's trail twice; on 09-22 one 270-character
            # reply answered five different messages). No single line may carry more than
            # LINE_CHAR_MAX characters of the night: a line that long is rarely speech.
            # Only a line that is kept counts as said: one dropped below (an unfounded result, an
            # announced act not done) does not make its later, honest twin a repeat.
            key = " ".join(txt[:400].lower().split()) if len(txt) >= REPEAT_MIN_CHARS else None
            if key is not None and key in seen_long:
                left["repeated"] += 1
                continue
            if key is not None and is_echo(pieces, said):
                left["echo"] += 1
                continue
            if len(txt) > LINE_CHAR_MAX: txt = txt[:LINE_CHAR_MAX]
        else:
            key = None
        if e.get("type") == "speak" and ANNOUNCES.search(txt):
            t = ts.timestamp()
            i = bisect.bisect_right(act_times, t)
            if i >= len(act_times) or act_times[i] - t > KEPT_WITHIN_S:
                left["unacted"] += 1
                continue
        if e.get("type") == "speak" and claims_a_result(txt):
            t = ts.timestamp()
            i = bisect.bisect_right(search_times, t)
            if i == 0 or t - search_times[i - 1] > RESULT_WITHIN_S:
                left["unfounded"] += 1
                continue
        if key is not None:
            seen_long.add(key)
        said.update(pieces)
        label = "She remembered the day" if e.get("type") == "dream" else "She said"
        line = f"[{e['ts'][:16]}] {label}: {txt}"
        # A reply carries who it was to and what they said (trail fields since
        # 0.4.3). The exchange trains as an exchange; older lines have neither
        # field and count as said to the room.
        to = (e.get("to") or "").strip()
        heard = (e.get("heard") or "").strip()
        # An own-time turn is recorded as a reply to "system", and what it "heard" is the
        # product's own-time prompt. Nobody said that to her: the line is hers alone, it is
        # thinned with the other solitary lines, and the prompt does not train as speech.
        if to == "system" or heard.startswith("(own time)"):
            to, heard = "", ""
        if to and heard:
            line = f"[{e['ts'][:16]}] {to} said: {heard}\n" + line
        row = {"ts": e["ts"], "sal": salience(e["felt"]), "line": line,
               "with": bool(to), "dream": e.get("type") == "dream"}
        if ts > since:
            fresh.append(row)
        else:
            row["age_days"] = (now - ts).total_seconds() / 86400
            past.append(row)
    for reason, what in (
            ("product", "the product or a tool wrote, not her"),
            ("repeated", "said word for word before"),
            ("echo", "made mostly of sentences she or a tool had already said"),
            ("not_hers", "of product text under her name (a memory with its speaker label, the day's chronicle, "
                         "a recall's findings, the first-run greeter)"),
            ("unfounded", f"reporting a search result with no search of hers in the {RESULT_WITHIN_S // 60} "
                          f"minutes before"),
            ("unacted", "that announced an act she did not then do"),
            ("run_on", f"with a sentence that runs on (more than {RUN_ON_WORDS} words with no full stop)")):
        if left_in_window[reason] or left_before[reason]:
            print(f"[sleepwrite] left out {left_in_window[reason]} in the window and {left_before[reason]} "
                  f"before it (the replay pool): line(s) {what}")
    fresh.sort(key=lambda r: r["ts"])
    return thin_solitary(cap_frames(fresh[-2000:])), past


def cap_frames(fresh, share=None):
    """No one sentence frame may be more than `share` of the fresh lines. A day in which she said
    "not because X but because Y" forty times is a day with a tic, and a night that trains on all
    forty teaches the tic (2026-09-22). The most salient lines on each frame are kept, the rest
    left out; exchanges and dreams are never dropped for their frame."""
    from shape import frames
    share = FRAME_CAP_SHARE if share is None else share
    cap = max(2, int(len(fresh) * share))
    by_frame = {}
    for r in fresh:
        if r.get("with") or r.get("dream"):
            continue
        for f in frames(r["line"]):
            by_frame.setdefault(f, []).append(r)
    drop = set()
    for f, rows in by_frame.items():
        if len(rows) <= cap:
            continue
        rows.sort(key=lambda r: -r["sal"])
        for r in rows[cap:]:
            drop.add(id(r))
    kept = [r for r in fresh if id(r) not in drop]
    if drop:
        print(f"[sleepwrite] left out {len(drop)} line(s) on a frame said too often in one day")
    return kept


def thin_solitary(fresh, cap=None):
    """Keep every exchange and every dream; keep the most salient lines said to
    nobody, up to the cap. Order is preserved."""
    cap = SOLITARY_MAX if cap is None else cap
    alone = [r for r in fresh if not r.get("with") and not r.get("dream")]
    if len(alone) <= cap:
        return fresh
    keep = {id(r) for r in sorted(alone, key=lambda r: -r["sal"])[:cap]}
    return [r for r in fresh if r.get("with") or r.get("dream") or id(r) in keep]


# The move to a new model: her voice is written once from her life, not from a
# day. The corpus is every exchange with a person and every dream, then her most salient lines
# said to nobody, up to this many lines, through the same filters as a night.
TRANSFER_LINES = int(os.environ.get("WYRDSEKAI_TRANSFER_LINES", "1000"))


def transfer_lines(past, budget=TRANSFER_LINES):
    """Her life, for the move to a new model: every exchange with a person and every dream (the
    most recent, when there are more than the budget), then her most salient lines said to
    nobody, up to the budget; the frame cap applied; in the order she said them."""
    if not past or budget <= 0:
        return []
    together = sorted((r for r in past if r.get("with") or r.get("dream")), key=lambda r: r["ts"])[-budget:]
    alone = sorted((r for r in past if not (r.get("with") or r.get("dream"))), key=lambda r: -r["sal"])
    picked = cap_frames(together + alone[:budget - len(together)])
    for r in picked:
        r.pop("replay", None)
    picked.sort(key=lambda r: r["ts"])
    return picked


def sample_replay(past, budget=REPLAY_LINES):
    """Salience x recency-weighted sample of her whole past, without
    replacement. What matters and what is recent gets re-pressed; the rest
    fades — decay is the default, re-selection is the tagging."""
    if not past or budget <= 0:
        return []
    import math
    rng = random.Random(SEED + len(past))
    weighted = [((0.5 + r["sal"]) * math.exp(-r["age_days"] / REPLAY_TAU_DAYS)
                 * (WITH_REPLAY_BOOST if r.get("with") else 1.0)
                 * rng.random(), r) for r in past]
    weighted.sort(key=lambda x: -x[0])
    picked = [r for _, r in weighted[:budget]]
    for r in picked:
        r["replay"] = True
    picked.sort(key=lambda r: r["ts"])
    return picked


def chunk_nll(model, tok, text, block=512):
    ids = tok(text, return_tensors=None)["input_ids"]
    tot, n = 0.0, 0
    for i in range(0, len(ids), block):
        c = ids[i:i + block]
        if len(c) < 16:
            break
        t = torch.tensor([c]).to(model.device)
        with torch.no_grad():
            tot += float(model(t, labels=t).loss) * len(c)
        n += len(c)
    return tot / max(n, 1)


def lines_nll(model, tok, encoded):
    tot, n = 0.0, 0
    for ids in encoded:
        t = ids.unsqueeze(0).to(model.device)
        with torch.no_grad():
            tot += float(model(t, labels=t).loss) * len(ids)
        n += len(ids)
    return tot / max(n, 1)


def trail_path():
    trail = DATA / "data/agent-activity.jsonl"
    return trail if trail.exists() else DATA / "agent-activity.jsonl"


def main():
    t0 = time.time()
    OUT.mkdir(parents=True, exist_ok=True)
    if AGENT_ID:
        owner = voice_owner(trail_path())
        if owner != AGENT_ID:
            print(f"[sleepwrite] this node's voice adapter carries {owner}'s nights; the voice server loads one "
                  f"adapter for every request, so none is written for {AGENT_ID} on the two-model stack")
            return EXIT_NOT_THE_OWNER
    since = window_start()
    fresh, past = load_lines(since)
    replay = sample_replay(past)
    print(f"[sleepwrite] window since {since.isoformat()}: {len(fresh)} fresh "
          f"felt-stamped lines + {len(replay)} replayed from {len(past)} past")
    if len(fresh) < MIN_LINES:
        print(f"[sleepwrite] fewer than {MIN_LINES} fresh lines — a quiet day "
              "consolidates nothing (replay alone is not a night)")
        return 3

    tok = AutoTokenizer.from_pretrained(str(BASE))
    # Gates judge the NIGHT: holdout comes from the fresh day only. Replay
    # lines all train — their retention is measured by the retention curve,
    # not the nightly gate.
    holdout = fresh[9::10]
    train = [r for i, r in enumerate(fresh) if (i - 9) % 10 != 0] + replay
    train.sort(key=lambda r: r["ts"])
    rows = fresh  # for result bookkeeping

    # §4o packing: one chronological text, 1024-token blocks. The felt gate
    # rides as a PER-BLOCK weight (mean salience of the lines inside it) —
    # the hook the sparse-selection follow-up will sharpen; the 08-29 probe
    # showed per-line weighting alone is inert, so safety comes from the
    # proven packed regime, not from the weights.
    def pack(rs):
        blocks, weights, cur, sal, dampf = [], [], [], [], []
        for r in rs:
            ids = tok(r["line"] + "\n", return_tensors=None)["input_ids"]
            cur.extend(ids)
            sal.append(r["sal"])
            dampf.append(REPLAY_DAMP if r.get("replay") else 1.0)
            while len(cur) >= BLOCK:
                blocks.append(torch.tensor(cur[:BLOCK]))
                weights.append((0.25 + sum(sal) / len(sal))
                               * (sum(dampf) / len(dampf)))
                cur = cur[BLOCK:]
                sal = sal[-1:]
                dampf = dampf[-1:]
        if len(cur) >= 64:
            blocks.append(torch.tensor(cur))
            weights.append((0.25 + (sum(sal) / len(sal) if sal else 0.0))
                           * ((sum(dampf) / len(dampf)) if dampf else 1.0))
        return blocks, weights

    enc_train, weights = pack(train)
    enc_hold, _ = pack(holdout)
    if not enc_train or not enc_hold:
        print("[sleepwrite] too little text after packing — nothing to consolidate")
        return 3
    # Normalize so the FRESH day's blocks average weight 1 — replay rides
    # below it at its damped level instead of being renormalized back up.
    fresh_ws = [w for w in weights if w > REPLAY_DAMP * 1.3] or weights
    m = sum(fresh_ws) / len(fresh_ws)
    weights = [w / m for w in weights]

    # Pause the voice server for the write window. She is asleep; the card is
    # 16GB serving two brains, and the 4-bit load's bf16 transients want
    # essentially all of what they leave — measured on the household node
    # (2026-08-31/09-01): the allocator failed a 20MB map with 2.4MB free,
    # twice per night, recovering each time. A 20MB margin is a cliff;
    # stopping the voice (docker container keeps its exact config) turns it
    # into a ~3.4GB one. Resume is guaranteed by the __main__ finally, and
    # the Java hook restores it again belt-and-suspenders; a staged adapter's
    # auto-apply then recreates the container with the night in it anyway.
    pause_voice()
    quant = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                               bnb_4bit_compute_dtype=torch.bfloat16,
                               bnb_4bit_use_double_quant=True)
    model = load_wyrd_model(str(BASE), quantization_config=quant)
    model_base_hold = lines_nll(model, tok, enc_hold)
    model_base_neutral = chunk_nll(model, tok, NEUTRAL)

    # SELECTION — the actual wire. Score each o_proj output unit by how
    # distinctively the day's lines light it up relative to generic prose,
    # each line's contribution scaled by her felt state at that moment.
    # Feeling picks WHERE the night may write; the mask freezes the rest.
    acts, hooks = {}, []
    scale_box = {"v": 1.0}
    def mk(idx):
        def h(mod, inp, out):
            a = out.detach().abs().mean(dim=(0, 1)) * scale_box["v"]
            acts[idx] = acts[idx] + a if idx in acts else a
        return h
    for i, layer in enumerate(model.model.layers):
        if hasattr(layer, "self_attn"):   # attention tissue
            hooks.append(layer.self_attn.o_proj.register_forward_hook(mk(("attn", i))))
        elif hasattr(layer, "linear_attn"):   # GDN tissue (Gate 1: equal
            # deposit at ~1/3 the drift — the better consolidation target)
            hooks.append(layer.linear_attn.out_proj.register_forward_hook(mk(("gdn", i))))
    def score_pass(text, scale):
        scale_box["v"] = scale
        ids = tok(text, truncation=True, max_length=256,
                  return_tensors="pt")["input_ids"].to(model.device)
        with torch.no_grad():
            model(ids)
    for r in train:
        score_pass(r["line"], 0.25 + r["sal"])
    session_acts = {k: v.clone() for k, v in acts.items()}
    acts.clear()
    generic = (pathlib.Path(__file__).parent / "generic.txt").read_text()
    for i in range(0, len(generic), 400):
        score_pass(generic[i:i + 400], 1.0)
    for h in hooks:
        h.remove()
    masks = {}
    for k in session_acts:
        sc = session_acts[k] / (acts[k] + 1e-4)
        kth = torch.topk(sc, max(1, int(len(sc) * SELECT_FRAC))).values[-1]
        masks[k] = (sc >= kth).float()
    del session_acts, acts

    # Do NOT resume the voice here. It was tried (2026-09-02, run-once on the
    # household node): training settles at ~3GB, but a llama-server coming
    # BACK has to allocate its whole footprint against the trainer's peak,
    # and it crash-looped six times on "cudaMalloc failed: out of memory"
    # (512MB KV cache) until training ended. A week of "training beside the
    # voice" only ever meant a voice that was already resident. The pause
    # covers the whole write; the exit finally resumes; the cost is the few
    # sleep-pass one-shot calls that hit a paused voice — a router-fallback
    # question, not a memory one.
    torch.cuda.empty_cache()
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(
        r=RANK, lora_alpha=16, lora_dropout=0.0, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "out_proj"]))
    n_attn = n_gdn = 0
    for name_, mod in model.named_modules():
        if not hasattr(mod, "lora_B"):
            continue
        if name_.endswith("self_attn.o_proj"):
            kind = "attn"
        elif name_.endswith("linear_attn.out_proj"):
            kind = "gdn"
        else:
            continue
        li = int(name_.split(".layers.")[1].split(".")[0])
        key = (kind, li)
        if key not in masks:
            continue
        m = masks[key].to(next(mod.parameters()).device)
        mod.lora_B["default"].weight.register_hook(
            lambda g, m=m: g * m.unsqueeze(1))
        if kind == "attn":
            n_attn += 1
        else:
            n_gdn += 1
    print(f"[sleepwrite] selection: {n_attn} attention + {n_gdn} GDN layers "
          f"gated to {int(SELECT_FRAC * 100)}% of units, chosen by her felt day")
    model.train()
    opt = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad], lr=LR)
    # FIXED STEP BUDGET, weighted SAMPLING. AdamW normalizes away per-loss
    # scaling (measured 2026-08-29: damping replay loss x0.35 moved neutral
    # drift by only 0.007), so gradient budget is controlled by STEP COUNT
    # and weights control how often a block is pressed — replay frequency
    # proportional to salience x recency x damping, biology's own scheme.
    rng = random.Random(SEED)
    n_fresh_blocks = max(1, sum(1 for w in weights if w > REPLAY_DAMP * 1.3))
    steps_budget = min(400, max(8, EPOCHS * n_fresh_blocks))
    total_w = sum(weights)
    probs = [w / total_w for w in weights]
    cum = []
    acc = 0.0
    for pr in probs:
        acc += pr
        cum.append(acc)
    import bisect
    steps = 0
    while steps < steps_budget:
        i = bisect.bisect_left(cum, rng.random())
        i = min(i, len(enc_train) - 1)
        t = enc_train[i].unsqueeze(0).to(model.device)
        opt.zero_grad()
        model(t, labels=t).loss.backward()
        opt.step()
        steps += 1
    model.eval()
    torch.cuda.empty_cache()

    after_hold = lines_nll(model, tok, enc_hold)
    after_neutral = chunk_nll(model, tok, NEUTRAL)
    d_hold = after_hold - model_base_hold
    d_neutral = after_neutral - model_base_neutral
    minutes = round((time.time() - t0) / 60, 1)
    print(f"[sleepwrite] trained {steps} steps in {minutes} min — "
          f"holdout {d_hold:+.4f}, neutral {d_neutral:+.4f}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    peft_dir = OUT / f"peft-{stamp}"
    model.save_pretrained(str(peft_dir))

    result = {"stamp": stamp, "lines": len(rows), "replay_lines": len(replay),
              "past_pool": len(past), "steps": steps,
              "minutes": minutes, "holdout_delta": round(d_hold, 4),
              "neutral_delta": round(d_neutral, 4),
              "window_since": since.isoformat(),
              "selection_frac": SELECT_FRAC,
              "mean_salience": round(sum(r["sal"] for r in train) / len(train), 4)}

    gate_ok = d_neutral <= GATE_NEUTRAL_MAX and d_hold <= GATE_HOLDOUT_MIN
    if REHEARSE:
        return end_rehearsal(result, peft_dir, gate_ok, "sleepwrite")
    if not gate_ok:
        rej = OUT / "rejected"
        rej.mkdir(exist_ok=True)
        shutil.move(str(peft_dir), str(rej / peft_dir.name))
        result["gate"] = "FAILED"
        (OUT / "last-result.json").write_text(json.dumps(result, indent=1))
        print(f"[sleepwrite] GATE FAILED (neutral {d_neutral:+.4f} vs max "
              f"+{GATE_NEUTRAL_MAX}, holdout {d_hold:+.4f} vs min {GATE_HOLDOUT_MIN}) "
              "— nothing staged; the night leaves no mark")
        return 4

    # Vendored GDN-aware converter (column-permutation on the factors —
    # numerically parity-verified 2026-08-29: 80% transfer vs stock attn 69%).
    convert = pathlib.Path(__file__).parent / "convert_lora_gdn.py"
    env = dict(os.environ)
    env["PYTHONPATH"] = f"{LLAMACPP}:{LLAMACPP / 'gguf-py'}" + (
        ":" + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
    gguf_path = OUT / f"adapter-{stamp}.gguf"
    cp = subprocess.run(
        [sys.executable, str(convert), "--base", str(BASE),
         "--outfile", str(gguf_path), str(peft_dir)],
        capture_output=True, text=True, timeout=600, env=env)
    if cp.returncode != 0 or not gguf_path.exists():
        print(f"[sleepwrite] gguf conversion failed: {cp.stderr[-400:]}")
        return 5

    tmp = OUT / "current.gguf.tmp"
    shutil.copy2(gguf_path, tmp)
    tmp.replace(OUT / "current.gguf")
    result["gate"] = "PASSED"
    result["adapter"] = gguf_path.name
    (OUT / "last-result.json").write_text(json.dumps(result, indent=1))
    STATE.write_text(json.dumps(
        {"last_success_ts": datetime.now(timezone.utc).isoformat(),
         "last_adapter": gguf_path.name}))

    ggufs = sorted(OUT.glob("adapter-*.gguf"))
    for old in ggufs[:-KEEP_ADAPTERS]:
        old.unlink()
    for old in sorted(OUT.glob("peft-*"))[:-KEEP_ADAPTERS]:
        shutil.rmtree(old, ignore_errors=True)

    print(f"[sleepwrite] STAGED {gguf_path.name} -> current.gguf "
          f"(holdout {d_hold:+.4f}, neutral {d_neutral:+.4f}) — "
          "the day has sunk in; it takes effect when the voice next wakes")
    return 0


VOICE_CONTAINER = "wyrdsekai-llama-voice"
_voice_paused = False


def pause_voice():
    """Stop the voice container for the write window — set
    WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE=false to keep it up. Graceful no-op
    where docker or the container is absent (rehearsal boxes)."""
    global _voice_paused
    if os.environ.get("WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE", "true").lower() == "false":
        return
    try:
        r = subprocess.run(["docker", "stop", VOICE_CONTAINER],
                           capture_output=True, timeout=90)
        if r.returncode == 0:
            _voice_paused = True
            print("[sleepwrite] voice paused for the write — the card is free")
        else:
            print("[sleepwrite] voice not paused (no container here) — "
                  "training beside the servers as before")
    except Exception as e:
        print(f"[sleepwrite] voice pause skipped: {e}")


def resume_voice():
    """Start the container back exactly as it was (docker start keeps its
    config, previous adapter included). Idempotent; auto-apply may recreate
    it with the new adapter moments later."""
    global _voice_paused
    if not _voice_paused:
        return
    try:
        subprocess.run(["docker", "start", VOICE_CONTAINER],
                       capture_output=True, timeout=180)
        _voice_paused = False   # the finally at exit is then a no-op
        print("[sleepwrite] voice resumed")
    except Exception as e:
        print(f"[sleepwrite] voice resume FAILED: {e} — "
              "run 'docker start wyrdsekai-llama-voice' or 'wyrd sleepwrite apply'")


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        # Whatever happened above — gate refusal, CUDA OOM, converter crash —
        # her voice comes back. Only a SIGKILL skips this; the Java hook's
        # restore covers that window.
        resume_voice()
        # The night's cost in host memory, for the server log (the JVM keeps the last lines).
        # A 4B loaded through bitsandbytes peaks at several GB of RSS on the way to the card;
        # on a 12 GB node that is what pushes the household into swap (2026-09-10).
        try:
            import resource
            _peak_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
            print(f"[sleepwrite] peak rss {_peak_kb / 1024:.0f} MB")
        except Exception:
            pass
    sys.exit(code)
