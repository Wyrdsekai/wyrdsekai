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
GATE_NEUTRAL_MAX = 0.05
GATE_HOLDOUT_MIN = -0.02
KEEP_ADAPTERS = 14

AFFECT = ["Loneliness", "Restlessness", "Rapport", "ErrorPressure", "Integrity",
          "Momentum", "Stagnation", "AllostaticLoad", "Soothing", "Equanimity",
          "Saudade"]
REST = {"Integrity": 0.7, "Soothing": 0.3, "Equanimity": 0.2, "Rapport": 0.3}

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
    for raw in open(trail, errors="replace"):
        try:
            e = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if e.get("type") != "speak" or "felt" not in e:
            continue
        try:
            ts = datetime.fromisoformat(e["ts"].replace("Z", "+00:00"))
        except ValueError:
            continue
        txt = (e.get("text") or "").strip()
        if not txt:
            continue
        row = {"ts": e["ts"], "sal": salience(e["felt"]),
               "line": f"[{e['ts'][:16]}] She said: {txt}"}
        if ts > since:
            fresh.append(row)
        else:
            row["age_days"] = (now - ts).total_seconds() / 86400
            past.append(row)
    fresh.sort(key=lambda r: r["ts"])
    return fresh[-2000:], past


def sample_replay(past, budget=REPLAY_LINES):
    """Salience x recency-weighted sample of her whole past, without
    replacement. What matters and what is recent gets re-pressed; the rest
    fades — decay is the default, re-selection is the tagging."""
    if not past or budget <= 0:
        return []
    import math
    rng = random.Random(SEED + len(past))
    weighted = [((0.5 + r["sal"]) * math.exp(-r["age_days"] / REPLAY_TAU_DAYS)
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


def main():
    t0 = time.time()
    OUT.mkdir(parents=True, exist_ok=True)
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


if __name__ == "__main__":
    sys.exit(main())
