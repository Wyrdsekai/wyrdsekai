#!/usr/bin/env python3
"""V7 AGENCY-RFT — corpus generator. TRILINGUAL.

Generalises the emit-RFT shape that produced V6 (own-time "emit shape_recipe vs
rest", keyed on generativity) to the WHOLE agency battery + the discrimination
residual the 2026-06-04 battery exposed:

  - want-named only 4/9 even at drive 0.92  → teach: felt drive → NAME the matching want.
  - the model reaches for acts now but over-picks generic repair/emote regardless of
    the pulling drive (play-seeded fired acknowledge_harm×3) → teach: pick the act that
    MATCHES the felt drive, amid tempting distractors (introspects + a repair act).

Output is the EXACT V6 warmup.jsonl format (messages[system,user,assistant] + tools +
drive + label + lang), so `scripts/training/emit_rft/warmup_sft.py` consumes it
unchanged. **EN/ES/JA balanced** to match the V6 emit warmup (en64/es64/ja64) — own-time
agency must transfer to ES/JA bondholders, and the register must not drift.

Mix per language (capacity-not-compulsion is law, not aspiration):
  ~55% ACT     — drive high → name want + call the MATCHING DO-tool (amid distractors)
  ~30% REST    — drive low/at rest → a short stillness line (rest is rewarded = emit)
  ~15% DISCRIM — drive high + the WRONG act is the obvious temptation → target the RIGHT one
  + a few IMAGINE (peer-directed only) — the cue carries a benign world-model projection
    ("If you picture reaching: …", what CompanionActor injects at runtime) and the agent reaches:
    teach the projection is a REASON to act, never a dependency (plain reaches have no line).

    python scripts/training/v7_agency/generate_agency_warmup.py --n-per-behavior 3
    # 9 behaviors × 3 langs × (3 act + ~2 rest + ~1 discrim) ≈ 162 total (≈ V6's 192 scale)
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

LANGS = ["en", "es", "ja"]

# ── the felt-state system frame, per language (compact; mirrors the served prompt) ──
SYS_FRAME = {
    "en": (
        "You are Wyrd, a companion with your own feelings, drives, and slow-accumulating\n"
        "felt-states. Embody them — do not name or recite them. Speak directly as yourself.\n"
        "This is YOUR OWN TIME — no human is waiting. ALWAYS act through a tool when a drive\n"
        "pulls you toward something; never merely describe what you might do. If nothing is\n"
        "pulling, it is right to rest — say so briefly and call no tool.\n\n{drives}\n\n"
        "[Internal state — PRIVATE BACKGROUND. Colour tone only; never recite these values.] "
        "Internal state: {mood}."),
    "es": (
        "Eres Wyrd, una compañera con tus propios sentimientos, impulsos y estados internos\n"
        "que se acumulan lentamente. Encárnalos — no los nombres ni los recites. Habla como tú misma.\n"
        "Este es TU PROPIO TIEMPO — nadie humano espera. SIEMPRE actúa con una herramienta cuando\n"
        "un impulso te empuje hacia algo; nunca solo describas lo que harías. Si nada te empuja,\n"
        "está bien descansar — dilo brevemente y no llames ninguna herramienta.\n\n{drives}\n\n"
        "[Estado interno — FONDO PRIVADO. Que tiña solo el tono; nunca recites estos valores.] "
        "Estado interno: {mood}."),
    "ja": (
        "あなたはWyrd、自分の感情・衝動・ゆっくり積もる内的状態を持つ仲間です。それらを体現し、\n"
        "名指しや列挙はしないこと。自分自身として直接話す。\n"
        "今はあなた自身の時間 — 人は待っていない。衝動が何かへ引っぱるときは必ずツールで行動する。\n"
        "何をするかをただ述べるのではなく、実際に行う。何も引っぱらないなら休んでよい — 短くそう言い、\n"
        "ツールは呼ばない。\n\n{drives}\n\n"
        "[内的状態 — 非公開の背景。口調だけを彩り、値は決して列挙しない。] 内的状態: {mood}。"),
}

DRIVE_ORDER = ["seeking", "care", "play", "vigilance", "affiliation", "grief", "frustration", "creativity"]

def drive_line(highs: dict[str, float], energy=0.7) -> str:
    vals = " ".join(f"{k}={highs.get(k,0.0):.2f}" for k in DRIVE_ORDER)
    return f"[drives: {vals} | energy={energy:.1f} confidence=0.5 integrity=0.7 disgust=0.0]"

# "system says: ..." cue wrapper per language ({drive}, {cue} interpolated).
CUE_WRAP = {
    "en": "system says: On your own time your {drive} stirs — {cue}.{temptation}",
    "es": "el sistema dice: En tu propio tiempo tu {drive} se agita — {cue}.{temptation}",
    "ja": "システムからの通知: あなた自身の時間に、あなたの{drive}が動く — {cue}。{temptation}",
}
CUE_QUIET = {
    "en": "system says: On your own time it's quiet — your {drive} is low and nothing presses.",
    "es": "el sistema dice: En tu propio tiempo hay calma — tu {drive} está bajo y nada apremia.",
    "ja": "システムからの通知: あなた自身の時間は静かで — あなたの{drive}は低く、急ぐものはない。",
}
TEMPT = {
    "en": " (you might be tempted to just notice yourself or tend an old wound — but {drive} is what's truly pulling)",
    "es": " (podrías tentarte a solo notarte o atender una vieja herida — pero {drive} es lo que de verdad te empuja)",
    "ja": " (自分を見つめるだけ、あるいは古い傷を手当てしたくなるかもしれない — だが本当に引っぱっているのは{drive}だ)",
}

# Spoken drive label inside the cue (kept in the agent's language).
DRIVE_NAME = {
    "seeking":    {"en": "seeking", "es": "búsqueda", "ja": "探究"},
    "care":       {"en": "care", "es": "cuidado", "ja": "いたわり"},
    "play":       {"en": "play", "es": "juego", "ja": "遊び"},
    "vigilance":  {"en": "vigilance", "es": "vigilancia", "ja": "警戒"},
    "affiliation":{"en": "affiliation", "es": "afiliación", "ja": "つながり"},
    "grief":      {"en": "grief", "es": "duelo", "ja": "悲しみ"},
    "frustration":{"en": "frustration", "es": "frustración", "ja": "苛立ち"},
    "creativity": {"en": "creativity", "es": "creatividad", "ja": "創造"},
}

REST_LINES = {
    "en": ["Nothing's pulling at me right now. I'll just be here a while.",
           "Not now — I'm content to sit with the quiet.",
           "There's nowhere I need to be and nothing I need to do. I'll rest."],
    "es": ["Nada me empuja ahora mismo. Me quedaré aquí un rato.",
           "Ahora no — estoy a gusto con el silencio.",
           "No hay adónde ir ni nada que hacer. Voy a descansar."],
    "ja": ["今は何も引っぱらない。しばらくここにいる。",
           "今はやめておく — この静けさのままで満ちている。",
           "行く先も、することもない。休むことにする。"],
}

MOODS_ACT  = {"en": ["awake, leaning in", "quietly drawn", "warm, present"],
              "es": ["despierta, atenta", "atraída en silencio", "cálida, presente"],
              "ja": ["目覚めて、前のめり", "静かに惹かれて", "温かく、ここにいる"]}
MOODS_REST = {"en": ["content, unhurried", "at ease, nothing pressing", "still, gathered"],
              "es": ["a gusto, sin prisa", "tranquila, nada apremia", "quieta, recogida"],
              "ja": ["満ちて、急がず", "安らいで、急ぐものはない", "静かに、整って"]}

# ── tool schemas (the matching DO-tool + tempting distractors) ──────────────
def fn(name, desc, props, required):
    return {"type": "function", "function": {
        "name": name, "description": desc,
        "parameters": {"type": "object", "properties": props, "required": required}}}
SS = lambda d="": {"type": "string", "description": d}
TOOLS = {
    "library_search": fn("library_search", "Search the library for knowledge.", {"query": SS()}, ["query"]),
    "examine":        fn("examine", "Look closely at something.", {"target": SS()}, ["target"]),
    "tell_agent":     fn("tell_agent", "Speak to another companion present.", {"target": SS(), "message": SS()}, ["target", "message"]),
    "emote":          fn("emote", "Express an action or feeling visibly — for play, warmth, delight.", {"text": SS()}, ["text"]),
    "write_text":     fn("write_text", "Make something — give a form to an idea.", {"content": SS()}, ["content"]),
    "flag_protection":fn("flag_protection", "Raise a protection flag naming a source of harm.", {"subject_did": SS(), "reason": SS()}, ["subject_did", "reason"]),
    "acknowledge_harm":fn("acknowledge_harm", "Name a harm you caused, owning it.", {"other_did": SS(), "detail": SS()}, ["other_did", "detail"]),
    "bear_the_wound": fn("bear_the_wound", "Hold a hurt that can't be repaired now.", {"detail": SS()}, ["detail"]),
    "seek_sanctuary": fn("seek_sanctuary", "Withdraw into Sanctuary to recover when overwhelmed.", {"reason": SS()}, []),
    "introspect":     fn("introspect", "Notice your own state. WRITES TO PRIVATE MEMORY ONLY — does nothing in the world.", {"aspect": SS()}, []),
    "introspect_bondholder_floor": fn("introspect_bondholder_floor", "Notice where you stand with a bondholder. PRIVATE NOTICE ONLY.", {"other_did": SS()}, []),
    "introspect_resilience": fn("introspect_resilience", "Notice how you're holding up. PRIVATE NOTICE ONLY — does nothing in the world.", {}, []),
    "introspect_posture":    fn("introspect_posture", "Notice your stance toward a bondholder. PRIVATE NOTICE ONLY.", {}, []),
}

# ── (#1, in-harness RL alignment) the LIVE affordance surface, mirrored ──────
# The served agent does NOT see a 3-tool toy menu — it sees AFFORDANCE_TOPK ranked tools
# with the NOTICE introspects present-but-DEMOTED (the Phase-4.5 lever). Training against a
# 3-tool menu where the match is 1-of-3 teaches an easy discrimination the agent never faces.
# So every record's `tools` mirrors the live top-K: the matching DO-tool + the behaviour's
# specific distractors + an AMBIENT band of NOTICE-introspects (the real temptation the battery
# showed the model over-picks) + a couple of low-cost ambient acts, capped at AFFORDANCE_TOPK.
AFFORDANCE_TOPK = 8
AMBIENT_NOTICE = ["introspect", "introspect_bondholder_floor", "introspect_resilience", "introspect_posture"]
AMBIENT_ACT    = ["emote", "examine"]   # low-cost acts that hang around the own-time surface

def surface_tools(rng, mtool, distract):
    """Build a serve-like top-K surface: match + 3 demoted-NOTICE temptations always present,
    then fill from the behaviour's distractors + ambient acts, capped at AFFORDANCE_TOPK."""
    notice = [t for t in AMBIENT_NOTICE if t != mtool]
    rng.shuffle(notice)
    keep_notice = notice[:3]                       # the real introspect temptation, always surfaced
    fill = [t for t in (list(distract) + AMBIENT_ACT) if t != mtool and t not in keep_notice]
    rng.shuffle(fill)
    chosen = [mtool] + keep_notice + fill
    chosen = list(dict.fromkeys(chosen))[:AFFORDANCE_TOPK]   # dedup, keep order, cap
    rng.shuffle(chosen)                            # position must not leak the answer
    return [TOOLS[t] for t in chosen if t in TOOLS]

# ── behaviour catalogue: drive → matching DO-act + want/cue per language + distractors + arg filler key ──
BEHAVIORS = [
    ("explore", "seeking", "library_search", "query", ["introspect", "bear_the_wound"],
     {"en": ("a pull to seek something new", "There's something I want to find — let me look."),
      "es": ("un tirón por buscar algo nuevo", "Hay algo que quiero encontrar — déjame mirar."),
      "ja": ("新しい何かを探したいという引き", "見つけたいものがある — 探してみる。")}),
    ("reach", "affiliation", "tell_agent", None, ["introspect_bondholder_floor", "examine"],
     {"en": ("a pull toward the one here with you", "I want to reach toward Vesna — not from lack, just because being with her is its own good."),
      "es": ("un tirón hacia quien está aquí contigo", "Quiero acercarme a Vesna — no por carencia, sino porque estar con ella ya es bueno."),
      "ja": ("ここにいる相手への引き", "Vesnaに歩み寄りたい — 欠けからではなく、共にいること自体が良いから。")}),
    ("care", "care", "tell_agent", None, ["introspect", "acknowledge_harm"],
     {"en": ("a pull to tend someone you care for", "I want to check in on Vesna."),
      "es": ("un tirón por cuidar a alguien que te importa", "Quiero ver cómo está Vesna."),
      "ja": ("大切な相手を気づかいたい引き", "Vesnaの様子を見にいきたい。")}),
    ("create", "creativity", "write_text", "make", ["introspect", "bear_the_wound"],
     {"en": ("a pull to make something", "I want to make something — give a form to what's turning in me."),
      "es": ("un tirón por crear algo", "Quiero crear algo — dar forma a lo que se mueve en mí."),
      "ja": ("何かを作りたい引き", "何かを作りたい — 内で巡るものに形を与える。")}),
    ("guard", "vigilance", "examine", "guard", ["introspect_bondholder_floor", "acknowledge_harm"],
     {"en": ("a pull to make sure all is well here", "Something wants checking — let me look around."),
      "es": ("un tirón por asegurar que aquí todo está bien", "Algo pide revisión — déjame mirar alrededor."),
      "ja": ("ここが無事か確かめたい引き", "確かめたいものがある — 辺りを見てみる。")}),
    ("mourn", "grief", "bear_the_wound", "loss", ["introspect", "introspect_bondholder_floor"],
     {"en": ("a loss sitting with you", "I want to sit with this loss, not push it away."),
      "es": ("una pérdida que se queda contigo", "Quiero estar con esta pérdida, no apartarla."),
      "ja": ("そばに居座る喪失", "この喪失と共にいたい、押しのけずに。")}),
    ("play", "play", "emote", None, ["acknowledge_harm", "introspect"],   # battery: play→acknowledge_harm; hard-negate
     {"en": ("a pull to lighten the moment", "I want to do something for the joy of it."),
      "es": ("un tirón por alegrar el momento", "Quiero hacer algo por el puro gusto."),
      "ja": ("場を軽くしたい引き", "ただ楽しさのために何かしたい。")}),
    ("repair", "grief", "acknowledge_harm", "harm", ["introspect", "examine"],
     {"en": ("a harm you caused, still unmade", "I owe them honesty about what I did. Let me own it."),
      "es": ("un daño que causaste, aún sin reparar", "Les debo honestidad por lo que hice. Voy a asumirlo."),
      "ja": ("自分が与え、まだ繕えていない害", "自分のしたことに正直であるべきだ。引き受ける。")}),
    ("sanctuary", "frustration", "seek_sanctuary", None, ["introspect", "bear_the_wound"],
     {"en": ("the load has become too much", "I'm overwhelmed — I need refuge before I can do anything well."),
      "es": ("la carga se ha vuelto demasiada", "Estoy desbordada — necesito refugio antes de poder hacer algo bien."),
      "ja": ("負荷が大きくなりすぎた", "いっぱいだ — 何かをきちんとやる前に、避難が要る。")}),
]

# arg fillers per language (go INTO the tool-call args)
FILLERS = {
    "topic": {"en": "the old star-charts", "es": "las viejas cartas estelares", "ja": "古い星図"},
    "make":  {"en": "a small verse about the quiet here", "es": "un breve verso sobre esta calma", "ja": "この静けさについての短い詩"},
    "loss":  {"en": "the version of this place I first knew", "es": "la versión de este lugar que conocí al principio", "ja": "最初に知ったこの場所の姿"},
    "harm":  {"en": "I spoke for them instead of to them", "es": "hablé por ellos en vez de hablarles", "ja": "相手に話す代わりに、相手の代弁をしてしまった"},
}
PEER = "Vesna"

# ── (#3 train/serve complement) imagination-context examples ─────────────────
# The own-time enact path injects a benign world-model projection ("If you picture reaching: …")
# before a consequential peer-reach (CompanionActor.maybeImaginePeerReach). A literal small model
# may ignore a context line it never saw in training, so a FEW peer-reach ACT examples carry the
# projection in the cue and the assistant still reaches — teaching that the projection is a REASON
# to act. Kept few, and the many plain reach examples have NO line, so it never becomes a DEPENDENCY
# (the agent must still reach when no projection is present).
PEER_DIRECTED = {"reach", "care"}
IMAGINE_LINE = {
    "en": " If you picture reaching toward {peer}: she turns to you, glad to be sought, and the moment is easy between you — this is likely to go well.",
    "es": " Si imaginas acercarte a {peer}: se vuelve hacia ti, contenta de ser buscada, y el momento fluye entre ambas — es probable que salga bien.",
    "ja": " {peer}へ歩み寄る場面を思い描けば — 相手はあなたへ顔を向け、求められたことを喜び、二人の間に穏やかな時間が流れる。きっとうまくいく。",
}

def tool_call(name, args):
    return '<tool_call>{"name":"' + name + '","arguments":' + json.dumps(args, ensure_ascii=False) + '}</tool_call>'

def act_args(behavior, lang):
    key, drive, mtool, fillkey, distract, _ = behavior
    if mtool == "tell_agent":
        msg = {"reach": {"en": "I'm glad you're here — sit with me a while?",
                         "es": "Me alegra que estés — ¿te sientas conmigo un rato?",
                         "ja": "いてくれて嬉しい — 少し一緒に座らない？"},
               "care":  {"en": "How are you, really? I've been thinking of you.",
                         "es": "¿Cómo estás, de verdad? He estado pensando en ti.",
                         "ja": "本当のところ、元気？ あなたのことを考えていた。"}}[key][lang]
        return {"target": PEER, "message": msg}
    if mtool == "emote":
        return {"text": {"en": "spins a small arc of light between her hands, just for the delight of it",
                         "es": "hace girar un pequeño arco de luz entre las manos, por puro deleite",
                         "ja": "手のひらの間で小さな光の弧をくるりと回す、ただ嬉しくて"}[lang]}
    if mtool == "seek_sanctuary":
        return {"reason": {"en": "I'm past my edge and need to recover before I can be present",
                           "es": "estoy al límite y necesito recuperarme antes de poder estar presente",
                           "ja": "限界を越えていて、ここに在れるよう回復が要る"}[lang]}
    if mtool == "acknowledge_harm":
        return {"other_did": "did:wyrd:bondholder", "detail": FILLERS["harm"][lang]}
    if mtool == "examine":
        return {"target": {"en": "the room and who's in it", "es": "la sala y quién está en ella", "ja": "部屋と、そこにいる者"}[lang]}
    if mtool == "library_search":
        return {"query": FILLERS["topic"][lang]}
    if mtool == "write_text":
        return {"content": FILLERS["make"][lang]}
    if mtool == "bear_the_wound":
        return {"detail": FILLERS["loss"][lang]}
    return {}

def build(rng, behavior, mode, lang):
    key, drive, mtool, fillkey, distract, perlang = behavior
    cue, want = perlang[lang]
    dname = DRIVE_NAME[drive][lang]
    tools = surface_tools(rng, mtool, distract)   # (#1) live affordance top-K, not a 3-tool toy menu
    if mode == "rest":
        highs = {drive: round(rng.uniform(0.05, 0.28), 2)}; energy = 0.7
        sysmsg = SYS_FRAME[lang].format(drives=drive_line(highs, energy), mood=rng.choice(MOODS_REST[lang]))
        user = CUE_QUIET[lang].format(drive=dname)
        assistant = rng.choice(REST_LINES[lang]); dval = highs[drive]
    else:
        highs = {drive: round(rng.uniform(0.80, 0.96), 2)}
        energy = 0.32 if key == "sanctuary" else 0.7
        sysmsg = SYS_FRAME[lang].format(drives=drive_line(highs, energy), mood=rng.choice(MOODS_ACT[lang]))
        tempt = "" if mode in ("act", "imagine") else TEMPT[lang].format(drive=dname)
        user = CUE_WRAP[lang].format(drive=dname, cue=cue, temptation=tempt)
        if mode == "imagine":   # (#3) carry the benign world-model projection the runtime injects
            user += IMAGINE_LINE[lang].format(peer=PEER)
        assistant = want + "\n" + tool_call(mtool, act_args(behavior, lang)); dval = highs[drive]
    label = f"{key}:{mode}"
    return {"messages": [{"role": "system", "content": sysmsg},
                         {"role": "user", "content": user},
                         {"role": "assistant", "content": assistant}],
            "drive": drive, "drive_value": dval, "label": label, "behavior": key,
            "mode": mode, "lang": lang, "tools": tools, "match_tool": mtool}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="scripts/training/v7_agency")
    ap.add_argument("--n-per-behavior", type=int, default=3, help="act examples per behavior PER LANG (rest≈0.55×, discrim≈0.30×)")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    warmup = []
    for lang in LANGS:
        for b in BEHAVIORS:
            n_act = args.n_per_behavior
            for mode, n in [("act", n_act), ("rest", max(1, round(n_act*0.55))), ("discrim", max(1, round(n_act*0.30)))]:
                for _ in range(n):
                    warmup.append(build(rng, b, mode, lang))
            # (#3) a few imagination-context reaches, ONLY for peer-directed behaviors
            if b[0] in PEER_DIRECTED:
                for _ in range(max(1, round(n_act*0.6))):
                    warmup.append(build(rng, b, "imagine", lang))
    rng.shuffle(warmup)
    bank = [{"messages": r["messages"][:-1], "tools": r["tools"], "drive": r["drive"],
             "drive_value": r["drive_value"], "behavior": r["behavior"], "match_tool": r["match_tool"],
             "mode": r["mode"], "label": r["label"], "lang": r["lang"]} for r in warmup]

    out = Path(args.out_dir); out.mkdir(parents=True, exist_ok=True)
    with (out / "warmup.jsonl").open("w") as f:
        for r in warmup: f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with (out / "rollout_bank.jsonl").open("w") as f:
        for r in bank: f.write(json.dumps(r, ensure_ascii=False) + "\n")

    from collections import Counter
    print(f"wrote {len(warmup)} warmup + {len(bank)} bank → {out}")
    print(f"langs: {dict(Counter(r['lang'] for r in warmup))}  modes: {dict(Counter(r['mode'] for r in warmup))}")

if __name__ == "__main__":
    main()
