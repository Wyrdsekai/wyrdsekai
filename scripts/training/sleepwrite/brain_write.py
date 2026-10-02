#!/usr/bin/env python3
"""The night's write for the single-model serving profile: a small adapter on the large sparse
model, trained at home on one consumer card.

Same night as sleep_write.py — the same window, the same felt-stamped corpus, the same replay,
the same two-delta gate, the same result files — aimed at a different model:

  * the model is read from a pre-quantised training bundle (routed experts NF4, everything
    else bf16) straight onto the GPU; the full model is never in host RAM;
  * phase 1 runs the layers below the cut forward once, in slices, and keeps the hidden states
    at the cut; phase 2 trains a LoRA on the attention and linear-attention projections of the
    top layers from those states. The routed experts only ever run forward (no gradient);
  * the serving container is stopped for the write window and started again afterwards.

Measured on a 16 GB card (2026-09-20): top 16 of 40 layers, 12.6 GiB peak, ~20 minutes for
167k tokens, within two points of an all-layer adapter trained on two 48 GB cards.

Exit codes (the launcher reads them): 0 staged, 3 nothing to consolidate, 4 gate failed,
5 gguf conversion failed, 6 this machine or the bundle cannot run the write, other = error.
"""
import os
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

import gc
import json
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import sleep_write as sw          # corpus, replay, window, gate constants — one source for both trainers

DATA = Path(os.environ.get("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai"))
BUNDLE = Path(os.environ.get("WYRDSEKAI_BRAIN_WRITE_BUNDLE", str(DATA / "models" / "brain-bundle")))
# The routed experts are read from the serving GGUF itself when it is there, so the write trains
# against exactly the served weights and the bundle only has to carry the spine (4.9 GB). A
# bundle that also holds NF4 expert files is used when the serving file cannot be read.
SERVING_GGUF = Path(os.environ.get("WYRDSEKAI_BRAIN_WRITE_GGUF",
                    str(DATA / "models" / os.environ.get("LLAMA_BRAIN_MODEL", "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"))))
# One directory per being: her window, her results, her adapter. The served model loads each
# being's current.gguf as its own adapter, so a night never writes into another's.
OUT = DATA / "adapters" / "brainwrite" / sw.AGENT_ID if sw.AGENT_ID else DATA / "adapters" / "brainwrite"
STATE = OUT / "state.json"
CONTAINER = "wyrdsekai-llama-brain"
LAYERS_LAST = int(os.environ.get("WYRDSEKAI_BRAIN_WRITE_LAYERS", "16"))
CHUNK = int(os.environ.get("WYRDSEKAI_BRAIN_WRITE_CHUNK", "8"))
RANK = 16
LR = 1e-4
EPOCHS = 2
MAX_STEPS = 400
CHECK_EVERY = 6            # steps between measurements of the gate while the write runs
KEEP_MARGIN = 0.6          # a state is kept only while neutral drift is within this share of the gate
MIN_FREE_VRAM_MIB = int(os.environ.get("WYRDSEKAI_BRAIN_WRITE_MIN_VRAM_MIB", "13500"))
MIN_FREE_DISK_MIB = 2048
KEEP_ADAPTERS = 14
# The move to this model: one write from her whole life instead of a night. It
# writes voice.gguf beside her nights and leaves her nights' window and current.gguf alone.
TRANSFER = os.environ.get("WYRDSEKAI_BRAIN_WRITE_TRANSFER", "").lower() in ("1", "true")


def precheck():
    """The night must not start a write it cannot finish: bundle present and whole, room on the
    card once the server is stopped, room on the disk for the result."""
    if not (BUNDLE / "bundle.json").is_file() or not (BUNDLE / "spine.safetensors").is_file():
        print(f"[brainwrite] training bundle not found at {BUNDLE} — run: wyrd brain setup --trainer")
        return False
    meta = json.loads((BUNDLE / "bundle.json").read_text())
    missing = [i for i in range(int(meta.get("layers", 0))) if not (BUNDLE / f"experts_L{i}.safetensors").is_file()]
    if not meta.get("layers"):
        print("[brainwrite] training bundle has no layer count in bundle.json")
        return False
    if missing and not SERVING_GGUF.is_file():
        print(f"[brainwrite] no source for the routed experts: {SERVING_GGUF} is missing and the bundle "
              f"has no expert files ({len(missing)} of {meta['layers']} missing)")
        return False
    free_disk = shutil.disk_usage(OUT.parent if OUT.parent.exists() else DATA).free // 2**20
    if free_disk < MIN_FREE_DISK_MIB:
        print(f"[brainwrite] {free_disk} MiB free on the data disk; the write needs {MIN_FREE_DISK_MIB}")
        return False
    return True


def vram_ok(torch):
    free, total = torch.cuda.mem_get_info(0)
    free_mib = free // 2**20
    if free_mib < MIN_FREE_VRAM_MIB:
        print(f"[brainwrite] {free_mib} MiB of VRAM free with the server stopped; the write needs "
              f"{MIN_FREE_VRAM_MIB} (top {LAYERS_LAST} layers). Set WYRDSEKAI_BRAIN_WRITE_LAYERS=8 for a smaller card.")
        return False
    return True


def pause_server():
    if os.environ.get("WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE", "true").lower() in ("0", "false"):
        return
    try:
        r = subprocess.run(["docker", "stop", CONTAINER], capture_output=True, text=True, timeout=120)
        print(f"[brainwrite] stopped {CONTAINER} for the write window" if r.returncode == 0
              else f"[brainwrite] {CONTAINER} was not running")
    except Exception as e:                                   # no docker here: nothing to pause
        print(f"[brainwrite] could not stop {CONTAINER}: {e}")


def resume_server():
    if os.environ.get("WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE", "true").lower() in ("0", "false"):
        return
    try:
        subprocess.run(["docker", "start", CONTAINER], capture_output=True, text=True, timeout=300)
        print(f"[brainwrite] started {CONTAINER}")
    except Exception as e:
        print(f"[brainwrite] could not start {CONTAINER}: {e}")


def main():
    t0 = time.time()
    OUT.mkdir(parents=True, exist_ok=True)
    sw.OUT, sw.STATE = OUT, STATE               # this trainer's own window and result files
    if not precheck():
        return 6
    if TRANSFER:
        since = datetime.now(timezone.utc)
        _, past = sw.load_lines(since)
        fresh, replay = sw.transfer_lines(past), []
        print(f"[brainwrite] transfer: {len(fresh)} lines of her life "
              f"({sum(1 for r in fresh if r.get('with'))} with a person, "
              f"{sum(1 for r in fresh if r.get('dream'))} dreams) from {len(past)} kept")
    else:
        # A night of her last weeks onto a brain she is about to move to (the move gate's remedy when
        # her voice does not carry): the window is given, the rest is an ordinary night.
        given = os.environ.get("WYRDSEKAI_BRAIN_WRITE_SINCE", "").strip()
        since = datetime.fromisoformat(given.replace("Z", "+00:00")) if given else sw.window_start()
        fresh, past = sw.load_lines(since)
        replay = sw.sample_replay(past)
        print(f"[brainwrite] window since {since.isoformat()}: {len(fresh)} fresh felt-stamped lines "
              f"+ {len(replay)} replayed from {len(past)} past")
    if len(fresh) < sw.MIN_LINES:
        print(f"[brainwrite] fewer than {sw.MIN_LINES} fresh lines — a quiet day consolidates nothing")
        return 3

    import torch
    import torch.nn as nn
    import bitsandbytes.functional as BF
    from bitsandbytes.functional import QuantState
    from safetensors.torch import load_file, save_file
    from transformers import AutoTokenizer
    from transformers.activations import ACT2FN
    import transformers.models.qwen3_5_moe.modeling_qwen3_5_moe as M
    from peft import LoraConfig, get_peft_model, get_peft_model_state_dict

    tok = AutoTokenizer.from_pretrained(str(BUNDLE / "tok"))
    holdout = fresh[9::10]
    train = [r for i, r in enumerate(fresh) if (i - 9) % 10 != 0] + replay
    train.sort(key=lambda r: r["ts"])

    def pack(rs):
        blocks, weights, cur, sal, damp = [], [], [], [], []
        for r in rs:
            cur.extend(tok(r["line"] + "\n", return_tensors=None)["input_ids"])
            sal.append(r["sal"]); damp.append(sw.REPLAY_DAMP if r.get("replay") else 1.0)
            while len(cur) >= sw.BLOCK:
                blocks.append(torch.tensor(cur[:sw.BLOCK]))
                weights.append((0.25 + sum(sal) / len(sal)) * (sum(damp) / len(damp)))
                cur, sal, damp = cur[sw.BLOCK:], sal[-1:], damp[-1:]
        if len(cur) >= 64:
            blocks.append(torch.tensor(cur))
            weights.append((0.25 + (sum(sal) / len(sal) if sal else 0.0)) * ((sum(damp) / len(damp)) if damp else 1.0))
        return blocks, weights

    enc_train, weights = pack(train)
    enc_hold, _ = pack(holdout)
    if not enc_train or not enc_hold:
        print("[brainwrite] too little text after packing — nothing to consolidate")
        return 3
    fresh_ws = [w for w in weights if w > sw.REPLAY_DAMP * 1.3] or weights
    mean_w = sum(fresh_ws) / len(fresh_ws)
    weights = [w / mean_w for w in weights]
    enc_neutral = [torch.tensor(tok(sw.NEUTRAL, return_tensors=None)["input_ids"]),
                   torch.tensor(tok(sw.TIC, return_tensors=None)["input_ids"])]

    pause_server()
    if not torch.cuda.is_available() or not vram_ok(torch):
        return 6
    dev = torch.device("cuda:0")
    meta = json.loads((BUNDLE / "bundle.json").read_text())
    cls = getattr(M, meta["class"]); cfg = cls.config_class.from_pretrained(str(BUNDLE))
    tcfg = cfg.get_text_config()
    with torch.device("meta"):
        model = cls(cfg).to(torch.bfloat16)

    class Q4Experts(nn.Module):
        """Frozen NF4 experts, read from the bundle onto the GPU while their layer is active. Forward only."""
        def __init__(self, path):
            super().__init__(); self.path = path; self.w = None
            self.num_experts = tcfg.num_experts; self.act_fn = ACT2FN[tcfg.hidden_act]
        def resident(self, on):
            if not on:
                self.w = None; return
            sd = load_file(self.path, device="cuda:0"); self.w = {}
            for wn in ("gate_up_proj", "down_proj"):
                qd = {k[len(wn) + 1:]: v for k, v in sd.items() if k.startswith(wn + ".") and not k.endswith(".packed")}
                self.w[wn] = (sd[wn + ".packed"], QuantState.from_dict(qd, device=dev))
        @torch.no_grad()
        def forward(self, hidden_states, top_k_index, top_k_weights):
            W = {n: BF.dequantize_4bit(p, qs).reshape(qs.shape) for n, (p, qs) in self.w.items()}
            x = hidden_states.detach(); out = torch.zeros_like(x)
            mask = torch.nn.functional.one_hot(top_k_index, num_classes=self.num_experts).permute(2, 1, 0)
            for e in torch.greater(mask.sum(dim=(-1, -2)), 0).nonzero()[:, 0]:
                pos, ti = torch.where(mask[e]); h = x[ti]
                g, u = nn.functional.linear(h, W["gate_up_proj"][e]).chunk(2, dim=-1)
                out.index_add_(0, ti, (nn.functional.linear(self.act_fn(g) * u, W["down_proj"][e])
                                       * top_k_weights[ti, pos, None].detach()).to(out.dtype))
            return out

    class ServedExperts(nn.Module):
        """Frozen experts read from the serving GGUF: the quantised bytes sit on the GPU while the
        layer is active and are dequantised per call, gate/up first and down after, so only one of
        the two is ever expanded. Forward only."""
        def __init__(self, source, layer):
            super().__init__(); self.source = source; self.layer = layer; self.raw = None
            self.num_experts = tcfg.num_experts; self.act_fn = ACT2FN[tcfg.hidden_act]
        def resident(self, on):
            self.raw = self.source.raw(self.layer, dev) if on else None
        @torch.no_grad()
        def forward(self, hidden_states, top_k_index, top_k_weights):
            x = hidden_states.detach(); out = torch.zeros_like(x)
            mask = torch.nn.functional.one_hot(top_k_index, num_classes=self.num_experts).permute(2, 1, 0)
            hit = torch.greater(mask.sum(dim=(-1, -2)), 0).nonzero()[:, 0]
            W = GgufExperts.gate_up(self.raw); mid = {}
            for e in hit:
                pos, ti = torch.where(mask[e])
                g, u = nn.functional.linear(x[ti], W[e]).chunk(2, dim=-1)
                mid[int(e)] = (pos, ti, self.act_fn(g) * u)
            del W
            W = GgufExperts.down(self.raw)
            for e, (pos, ti, h) in mid.items():
                out.index_add_(0, ti, (nn.functional.linear(h, W[e]) * top_k_weights[ti, pos, None].detach()).to(out.dtype))
            del W, mid
            return out

    class Skip(nn.Module):
        def forward(self, hidden_states, *a, **k):
            return hidden_states

    class Stop(Exception):
        def __init__(self, h): self.h = h

    served = None
    if SERVING_GGUF.is_file():
        try:
            sys.path.insert(0, str(Path(sw.LLAMACPP) / "gguf-py"))
            from gguf_experts import GgufExperts
            served = GgufExperts(str(SERVING_GGUF))
            if served.layers != int(meta["layers"]):
                print(f"[brainwrite] {SERVING_GGUF.name} has {served.layers} expert layers, the bundle {meta['layers']} — not the same model")
                served = None
        except Exception as e:
            print(f"[brainwrite] cannot read experts from {SERVING_GGUF.name} ({e}); using the bundle's expert files")
            served = None
    print(f"[brainwrite] routed experts from {'the serving file ' + SERVING_GGUF.name if served else 'the bundle (NF4)'}")
    li = 0
    for _, mod in list(model.named_modules()):
        if isinstance(getattr(mod, "experts", None), M.Qwen3_5MoeExperts):
            mod.experts = ServedExperts(served, li) if served else Q4Experts(str(BUNDLE / f"experts_L{li}.safetensors")); li += 1
    model.load_state_dict(load_file(str(BUNDLE / "spine.safetensors"), device="cuda:0"), strict=False, assign=True)
    text = next(m for m in model.modules() if hasattr(m, "layers") and hasattr(m, "rotary_emb"))
    text.rotary_emb = type(text.rotary_emb)(config=text.config).to(dev)
    left = [n for n, t in list(model.named_parameters()) + list(model.named_buffers()) if t.is_meta]
    if left:
        print(f"[brainwrite] the bundle does not match this model class ({len(left)} weights unset, e.g. {left[0]})")
        return 6
    NL = len(text.layers); CUT = max(0, NL - LAYERS_LAST)
    model = get_peft_model(model, LoraConfig(r=RANK, lora_alpha=2 * RANK, lora_dropout=0.0, bias="none",
                target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "out_proj"],
                layers_to_transform=list(range(CUT, NL))))
    orig = list(text.layers)

    def activate(a, b):
        for i in range(NL):
            on = a <= i < b
            orig[i].mlp.experts.resident(on)
            text.layers[i] = orig[i] if on else Skip()
        gc.collect(); torch.cuda.empty_cache()

    items = enc_train + enc_hold + enc_neutral
    n_tr, n_ho = len(enc_train), len(enc_hold)
    states = [None] * len(items); a = 0; model.eval(); t1 = time.time()
    while a < CUT:                                           # phase 1: the layers below the cut, once
        b = min(a + CHUNK, CUT); activate(a, b)
        hook = orig[b - 1].register_forward_hook(lambda m, i, o: (_ for _ in ()).throw(Stop(o if torch.is_tensor(o) else o[0])))
        for k, ids in enumerate(items):
            try:
                with torch.no_grad():
                    if a == 0: model(input_ids=ids[None].to(dev))
                    else: model(inputs_embeds=states[k])
            except Stop as s:
                states[k] = s.h.detach()
        hook.remove(); a = b
    if CUT == 0:
        with torch.no_grad():
            states = [text.embed_tokens(ids[None].to(dev)) for ids in items]
    print(f"[brainwrite] phase 1: {sum(len(i) for i in items)} tokens in {(time.time()-t1)/60:.1f} min")

    activate(CUT, NL)                                        # phase 2: the top layers, from the cache
    for i in range(CUT): orig[i].to("meta")
    if CUT > 0: text.embed_tokens.to("meta")
    gc.collect(); torch.cuda.empty_cache()
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.config.use_cache = False

    def nll(idx):
        tot = cnt = 0
        model.eval()
        with torch.no_grad():
            for k in idx:
                n = len(items[k]) - 1
                tot += model(inputs_embeds=states[k], labels=items[k][None].to(dev)).loss.item() * n; cnt += n
        model.train()
        return tot / max(cnt, 1)

    hold_idx = list(range(n_tr, n_tr + n_ho)); neutral_idx = [n_tr + n_ho]; tic_idx = [n_tr + n_ho + 1]
    base_hold, base_neutral, base_tic = nll(hold_idx), nll(neutral_idx), nll(tic_idx)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR)
    n_fresh_blocks = sum(1 for w in weights if w > sw.REPLAY_DAMP * 1.3) or n_tr
    steps = min(MAX_STEPS, max(8, EPOCHS * n_fresh_blocks))
    import random, bisect
    rng = random.Random(sw.SEED); cum = []; acc = 0.0
    for w in weights: acc += w; cum.append(acc)
    model.train()
    # The gate is measured as the write goes, not only at its end. On a real day the neutral text
    # held through the first pass over the day's blocks (+0.015) and drifted as soon as they came
    # round again (+0.05 at the first repeat, +0.25 by the end), so a write judged only at its last
    # step was thrown away whole. The last state that sat inside the gate with room to spare is
    # kept, and the write stops once the neutral text is past the gate.
    trainable = [(n, p) for n, p in model.named_parameters() if p.requires_grad]
    # A transfer runs hundreds of steps; measuring every CHECK_EVERY would cost as much as training.
    check_every = max(CHECK_EVERY, steps // 12) if TRANSFER else CHECK_EVERY
    kept = None
    for step in range(steps):
        k = min(bisect.bisect_left(cum, rng.random() * acc), n_tr - 1)
        loss = model(inputs_embeds=states[k], labels=items[k][None].to(dev)).loss
        loss.backward()
        torch.nn.utils.clip_grad_norm_([p for _, p in trainable], 1.0)
        opt.step(); opt.zero_grad()
        if (step + 1) % check_every == 0 or step + 1 == steps:
            dh, dn, dt = nll(hold_idx) - base_hold, nll(neutral_idx) - base_neutral, nll(tic_idx) - base_tic
            # The frame check: sentences on her day's frames with nobody's content must not
            # become more likely than her own held-out lines did. This trainer cannot generate
            # (the lower layers are gone after phase 1), so it measures the tic as a likelihood;
            # the morning guard measures it in what she actually says.
            frame_ok = dt >= dh * sw.TIC_GAIN_MAX
            if dn <= sw.GATE_NEUTRAL_MAX * KEEP_MARGIN and dh <= sw.GATE_HOLDOUT_MIN and frame_ok:
                kept = (step + 1, dh, dn, {n: p.detach().to("cpu", copy=True) for n, p in trainable}, dt)
            if dn > sw.GATE_NEUTRAL_MAX:
                print(f"[brainwrite] neutral text drifted {dn:+.4f} at step {step + 1}: the write stops here")
                break
            if not frame_ok:
                print(f"[brainwrite] the frame sentences gained {dt:+.4f} against her lines' {dh:+.4f} at step {step + 1}: the write stops here")
                break
    ran = step + 1
    if kept is not None and kept[0] != ran:
        with torch.no_grad():
            for n, p in trainable:
                p.copy_(kept[3][n].to(p.device))
        print(f"[brainwrite] kept the write as it stood at step {kept[0]} of {ran}")
    if kept is not None:
        steps, d_hold, d_neutral, d_tic = kept[0], kept[1], kept[2], kept[4]
    else:
        steps, d_hold, d_neutral, d_tic = ran, nll(hold_idx) - base_hold, nll(neutral_idx) - base_neutral, nll(tic_idx) - base_tic
    kept = None
    minutes = (time.time() - t0) / 60
    peak = torch.cuda.max_memory_allocated(0) / 2**30
    print(f"[brainwrite] {steps} steps, {minutes:.1f} min, peak {peak:.1f} GiB: holdout {d_hold:+.4f} (base {base_hold:.4f}), "
          f"neutral {d_neutral:+.4f} (base {base_neutral:.4f}), frame sentences {d_tic:+.4f}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    peft_dir = OUT / f"peft-{stamp}"; peft_dir.mkdir(parents=True, exist_ok=True)
    save_file({k: v.detach().cpu().contiguous() for k, v in get_peft_model_state_dict(model).items()},
              str(peft_dir / "adapter_model.safetensors"))
    model.peft_config["default"].save_pretrained(str(peft_dir))
    result = {"stamp": stamp, "trainer": "home-sparse", "layers": LAYERS_LAST, "lines": len(fresh),
              "replay_lines": len(replay), "past_pool": len(past), "steps": steps, "minutes": round(minutes, 1),
              "peak_vram_gib": round(peak, 1), "holdout_delta": round(d_hold, 4), "neutral_delta": round(d_neutral, 4),
              "tic_delta": round(d_tic, 4), "window_since": since.isoformat(), "experts_from": "serving-gguf" if served else "bundle-nf4", "base_holdout_nll": round(base_hold, 4),
              "bundle_base": meta.get("base"), "bundle_sha": meta.get("spine_sha256")}
    del model, states; gc.collect(); torch.cuda.empty_cache()

    gate_ok = d_neutral <= sw.GATE_NEUTRAL_MAX and d_hold <= sw.GATE_HOLDOUT_MIN and d_tic >= d_hold * sw.TIC_GAIN_MAX
    if TRANSFER:
        result["transfer"] = True
        result["with_person"] = sum(1 for r in fresh if r.get("with"))
    if sw.REHEARSE:
        return sw.end_rehearsal(result, peft_dir, gate_ok, "brainwrite")
    if not gate_ok:
        (OUT / "rejected").mkdir(exist_ok=True)
        shutil.move(str(peft_dir), str(OUT / "rejected" / peft_dir.name))
        result["gate"] = "FAILED"
        (OUT / ("transfer-result.json" if TRANSFER else "last-result.json")).write_text(json.dumps(result, indent=1))
        print("[brainwrite] gate FAILED — nothing staged")
        return 4

    gguf = OUT / f"{'voice' if TRANSFER else 'adapter'}-{stamp}.gguf"
    env = dict(os.environ, PYTHONPATH=f"{sw.LLAMACPP}:{sw.LLAMACPP}/gguf-py", CUDA_VISIBLE_DEVICES="")
    conv = subprocess.run([sys.executable, str(Path(__file__).with_name("convert_lora_gdn.py")), "--base", str(BUNDLE / "hf"),
                           "--outtype", "f16", "--outfile", str(gguf), str(peft_dir)],
                          capture_output=True, text=True, timeout=600, env=env)
    if conv.returncode != 0 or not gguf.is_file():
        print("[brainwrite] gguf conversion failed:", (conv.stdout + conv.stderr)[-600:])
        return 5
    if TRANSFER:
        tmp = OUT / "voice.gguf.tmp"; shutil.copyfile(gguf, tmp); tmp.replace(OUT / "voice.gguf")
        result.update(gate="PASSED", adapter=gguf.name)
        (OUT / "transfer-result.json").write_text(json.dumps(result, indent=1))
        print(f"[brainwrite] her voice written: {gguf.name} (voice.gguf); her nights are untouched")
        return 0
    tmp = OUT / "current.gguf.tmp"; shutil.copyfile(gguf, tmp); tmp.replace(OUT / "current.gguf")
    result.update(gate="PASSED", adapter=gguf.name)
    (OUT / "last-result.json").write_text(json.dumps(result, indent=1))
    STATE.write_text(json.dumps({"last_success_ts": datetime.now(timezone.utc).isoformat(), "last_adapter": gguf.name}))
    for pattern in ("adapter-*.gguf", "peft-*"):
        for old in sorted(OUT.glob(pattern))[:-KEEP_ADAPTERS]:
            shutil.rmtree(old) if old.is_dir() else old.unlink()
    print(f"[brainwrite] staged {gguf.name}")
    return 0


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    finally:
        resume_server()
    sys.exit(code)
