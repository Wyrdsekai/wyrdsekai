#!/usr/bin/env python3
"""The night's write for the single-model serving profile on Apple Silicon: the same small
adapter as brain_write.py, trained with MLX instead of CUDA.

Same night as brain_write.py — the same window, the same felt-stamped corpus, the same replay,
the same block packing and weighted sampling, the same two-delta gate, the same result files
under adapters/brainwrite/ — with these differences:

  * the model is an MLX 4-bit conversion of the same base (WYRDSEKAI_BRAIN_WRITE_MLX_MODEL,
    default <data>/models/brain-mlx), loaded whole into unified memory. There is no phase 1 /
    phase 2 split: every step runs the full forward, and the backward pass stops at the lowest
    adapted layer because nothing below it is trainable;
  * the LoRA sits on the attention projections and the linear-attention output projection of
    the top WYRDSEKAI_BRAIN_WRITE_LAYERS layers only. The routed experts, the router and the
    other linear-attention projections are never adapted;
  * the adapter is saved in mlx-lm's format (peft-<stamp>/mlx/), bridged to PEFT with
    scripts/training/mlx_adapter_to_peft.py, then to GGUF with convert_lora_gdn.py. Of the
    training bundle only <bundle>/hf/config.json is needed here. convert_lora_gdn.py imports
    torch and transformers (CPU is enough); when the MLX environment has no torch, point
    WYRDSEKAI_BRAIN_WRITE_CONVERT_PYTHON at an interpreter that does.

The serving process on a Mac is a native llama-server, not a container. A 48 GB Mac cannot hold
the served model and this trainer's copy in unified memory together, so the script asks the
launcher to stop that server before the write and to start it again afterwards
(`wyrd brain stop-server` / `wyrd brain serve`). WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE=false leaves
the server running, for a machine with memory to spare. A staged adapter is loaded by
`wyrd sleepwrite --brain apply`, which restarts the server.

Exit codes (the launcher reads them): 0 staged, 3 nothing to consolidate, 4 gate failed,
5 adapter conversion failed, 6 this machine or its files cannot run the write, other = error.
"""
import os
# An open mlx-lm issue: LoRA training on this model family runs into a Metal resource limit
# when the graph is compiled. Must be set before mlx is imported.
os.environ.setdefault("MLX_DISABLE_COMPILE", "1")

import bisect
import importlib
import inspect
import json
import math
import platform
import random
import re
import shutil
import subprocess
import sys
import time
import types
from datetime import datetime, timezone
from pathlib import Path

# sleep_write.py imports its CUDA training stack at module level. An MLX environment usually has
# none of it, and this trainer only needs the corpus half of that module, so each missing piece
# is replaced by an empty stub — only when it really cannot be imported.
sys.path.insert(0, str(Path(__file__).parent))
for _name, _attrs in (("torch", ()), ("transformers", ("AutoTokenizer", "BitsAndBytesConfig")),
                      ("peft", ("LoraConfig", "get_peft_model")), ("wyrd_load", ("load_wyrd_model",))):
    try:
        _mod = importlib.import_module(_name)
        for _a in _attrs:
            getattr(_mod, _a)
    except Exception:
        _stub = types.ModuleType(_name)
        for _a in _attrs:
            setattr(_stub, _a, object)
        sys.modules[_name] = _stub

import sleep_write as sw          # corpus, replay, window, gate constants — one source for every trainer

DATA = Path(os.environ.get("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai"))
MLX_MODEL = Path(os.environ.get("WYRDSEKAI_BRAIN_WRITE_MLX_MODEL", str(DATA / "models" / "brain-mlx")))
BUNDLE = Path(os.environ.get("WYRDSEKAI_BRAIN_WRITE_BUNDLE", str(DATA / "models" / "brain-bundle")))
CONVERT_PY = os.environ.get("WYRDSEKAI_BRAIN_WRITE_CONVERT_PYTHON") or sys.executable
MLX_TO_PEFT = Path(__file__).resolve().parent.parent / "mlx_adapter_to_peft.py"
# One directory per being: her window, her results, her adapter. The served model loads each
# being's current.gguf as its own adapter, so a night never writes into another's.
OUT = DATA / "adapters" / "brainwrite" / sw.AGENT_ID if sw.AGENT_ID else DATA / "adapters" / "brainwrite"
STATE = OUT / "state.json"
LAYERS_LAST = int(os.environ.get("WYRDSEKAI_BRAIN_WRITE_LAYERS", "16"))
RANK = 16
SCALE = 2.0                       # mlx-lm multiplies the update by this directly; PEFT's alpha = SCALE * RANK
LR = 1e-4
EPOCHS = 2
MAX_STEPS = 400
MIN_FREE_DISK_MIB = 2048
KEEP_ADAPTERS = 14
LORA_KEYS = ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj",
             "linear_attn.out_proj"]
PEFT_KEY = re.compile(r"^base_model\.model\.model\.layers\.(\d+)\."
                      r"(self_attn\.[qkvo]_proj|linear_attn\.out_proj)\.lora_[AB]\.weight$")


def precheck():
    """The night must not start a write it cannot finish: the right machine, the model, both
    converters and what they need, room on the disk for the result."""
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        print(f"[brainwrite-mlx] this trainer runs on Apple Silicon only (here: {platform.system()} {platform.machine()})")
        return False
    if not (MLX_MODEL / "config.json").is_file():
        print(f"[brainwrite-mlx] MLX model not found at {MLX_MODEL} (set WYRDSEKAI_BRAIN_WRITE_MLX_MODEL)")
        return False
    if not MLX_TO_PEFT.is_file():
        print(f"[brainwrite-mlx] adapter bridge not found at {MLX_TO_PEFT}")
        return False
    if not (BUNDLE / "hf" / "config.json").is_file():
        print(f"[brainwrite-mlx] {BUNDLE}/hf/config.json is missing — the GGUF converter reads the base "
              f"model's shape from it (run: wyrd brain setup --trainer, or copy that one file)")
        return False
    if not (Path(sw.LLAMACPP) / "gguf-py").is_dir():
        print(f"[brainwrite-mlx] no llama.cpp checkout at {sw.LLAMACPP} — the GGUF converter needs it")
        return False
    try:
        probe = subprocess.run([CONVERT_PY, "-c", "import torch, transformers, safetensors"],
                               capture_output=True, text=True, timeout=180)
        if probe.returncode != 0:
            print(f"[brainwrite-mlx] {CONVERT_PY} cannot import torch/transformers/safetensors, which the GGUF "
                  f"converter needs: pip install torch there, or set WYRDSEKAI_BRAIN_WRITE_CONVERT_PYTHON")
            return False
    except Exception as e:
        print(f"[brainwrite-mlx] cannot run the converter's interpreter {CONVERT_PY}: {e}")
        return False
    free_disk = shutil.disk_usage(OUT.parent if OUT.parent.exists() else DATA).free // 2**20
    if free_disk < MIN_FREE_DISK_MIB:
        print(f"[brainwrite-mlx] {free_disk} MiB free on the data disk; the write needs {MIN_FREE_DISK_MIB}")
        return False
    return True


def main():
    t0 = time.time()
    OUT.mkdir(parents=True, exist_ok=True)
    sw.OUT, sw.STATE = OUT, STATE               # this trainer's own window and result files
    if not precheck():
        return 6
    try:
        import mlx.core as mx
        import mlx.nn as nn
        import mlx.optimizers as optim
        from mlx.utils import tree_flatten
        from mlx_lm import load
        from mlx_lm.tuner.utils import linear_to_lora_layers
    except Exception as e:
        print(f"[brainwrite-mlx] mlx / mlx_lm cannot be imported in this environment: {e}")
        return 6

    since = sw.window_start()
    fresh, past = sw.load_lines(since)
    replay = sw.sample_replay(past)
    print(f"[brainwrite-mlx] window since {since.isoformat()}: {len(fresh)} fresh felt-stamped lines "
          f"+ {len(replay)} replayed from {len(past)} past")
    if len(fresh) < sw.MIN_LINES:
        print(f"[brainwrite-mlx] fewer than {sw.MIN_LINES} fresh lines — a quiet day consolidates nothing")
        return 3

    model, tok = load(str(MLX_MODEL))
    holdout = fresh[9::10]
    train = [r for i, r in enumerate(fresh) if (i - 9) % 10 != 0] + replay
    train.sort(key=lambda r: r["ts"])

    def pack(rs):                                # brain_write.py's packing, token lists instead of tensors
        blocks, weights, cur, sal, damp = [], [], [], [], []
        for r in rs:
            cur.extend(tok.encode(r["line"] + "\n"))
            sal.append(r["sal"]); damp.append(sw.REPLAY_DAMP if r.get("replay") else 1.0)
            while len(cur) >= sw.BLOCK:
                blocks.append(list(cur[:sw.BLOCK]))
                weights.append((0.25 + sum(sal) / len(sal)) * (sum(damp) / len(damp)))
                cur, sal, damp = cur[sw.BLOCK:], sal[-1:], damp[-1:]
        if len(cur) >= 64:
            blocks.append(list(cur))
            weights.append((0.25 + (sum(sal) / len(sal) if sal else 0.0)) * ((sum(damp) / len(damp)) if damp else 1.0))
        return blocks, weights

    enc_train, weights = pack(train)
    enc_hold, _ = pack(holdout)
    if not enc_train or not enc_hold:
        print("[brainwrite-mlx] too little text after packing — nothing to consolidate")
        return 3
    fresh_ws = [w for w in weights if w > sw.REPLAY_DAMP * 1.3] or weights
    mean_w = sum(fresh_ws) / len(fresh_ws)
    weights = [w / mean_w for w in weights]
    enc_neutral = [list(tok.encode(sw.NEUTRAL))]
    n_tr = len(enc_train)

    # The adapter: top layers only, attention projections and the linear-attention output only.
    layers = getattr(model, "layers", None)
    if not layers:
        print("[brainwrite-mlx] this mlx_lm model exposes no .layers — cannot place the adapter")
        return 6
    n_layers = min(LAYERS_LAST, len(layers)); cut = len(layers) - n_layers
    params = list(inspect.signature(linear_to_lora_layers).parameters)
    if len(params) < 3 or params[2] != "config":
        print(f"[brainwrite-mlx] mlx_lm.tuner.utils.linear_to_lora_layers has an unexpected signature {params}; "
              f"this trainer expects (model, num_layers, config)")
        return 6
    model.freeze()
    try:
        linear_to_lora_layers(model, n_layers, {"rank": RANK, "scale": SCALE, "dropout": 0.0, "keys": LORA_KEYS})
    except (TypeError, KeyError, ValueError) as e:
        print(f"[brainwrite-mlx] the installed mlx_lm could not place the adapter: {e}")
        return 6
    names = [k for k, _ in tree_flatten(model.trainable_parameters())]
    stray = [k for k in names
             if not any(f".{key}.lora_" in k for key in LORA_KEYS)
             or not (m := re.search(r"layers\.(\d+)\.", k)) or int(m.group(1)) < cut]
    if not names or stray:
        print(f"[brainwrite-mlx] the adapter did not land where it should ({len(names)} trainable tensors"
              f"{', e.g. ' + stray[0] if stray else ''}) — nothing trained")
        return 6
    try:                                                     # recompute activations in the backward pass
        from mlx_lm.tuner.trainer import grad_checkpoint
        grad_checkpoint(layers[0])
    except Exception:
        pass

    def loss_fn(m, ids):
        logits = m(ids[:, :-1])
        if isinstance(logits, (tuple, list)):
            logits = logits[0]
        return nn.losses.cross_entropy(logits.astype(mx.float32), ids[:, 1:], reduction="mean")

    def nll(blocks):
        tot = cnt = 0
        model.eval()
        for ids in blocks:
            loss = loss_fn(model, mx.array(ids)[None]); mx.eval(loss)
            n = len(ids) - 1
            tot += loss.item() * n; cnt += n
        model.train()
        return tot / max(cnt, 1)

    # Before the first step lora_b is all zeros, so this is the base model's NLL.
    base_hold, base_neutral = nll(enc_hold), nll(enc_neutral)
    opt = optim.Adam(learning_rate=LR)
    loss_and_grad = nn.value_and_grad(model, loss_fn)
    n_fresh_blocks = sum(1 for w in weights if w > sw.REPLAY_DAMP * 1.3) or n_tr
    steps = min(MAX_STEPS, max(8, EPOCHS * n_fresh_blocks))
    rng = random.Random(sw.SEED); cum = []; acc = 0.0
    for w in weights: acc += w; cum.append(acc)
    model.train()
    for step in range(steps):
        k = min(bisect.bisect_left(cum, rng.random() * acc), n_tr - 1)
        loss, grads = loss_and_grad(model, mx.array(enc_train[k])[None])
        if hasattr(optim, "clip_grad_norm"):
            grads, _ = optim.clip_grad_norm(grads, 1.0)
        opt.update(model, grads)
        mx.eval(loss, model.trainable_parameters(), opt.state)
        if not math.isfinite(loss.item()):
            print(f"[brainwrite-mlx] the loss stopped being a number at step {step} — nothing staged")
            return 1
    d_hold, d_neutral = nll(enc_hold) - base_hold, nll(enc_neutral) - base_neutral
    minutes = (time.time() - t0) / 60
    peak_fn = getattr(mx, "get_peak_memory", None) or getattr(getattr(mx, "metal", None), "get_peak_memory", None)
    peak = (peak_fn() / 2**30) if peak_fn else 0.0
    print(f"[brainwrite-mlx] {steps} steps, {minutes:.1f} min, peak {peak:.1f} GiB: holdout {d_hold:+.4f} "
          f"(base {base_hold:.4f}), neutral {d_neutral:+.4f} (base {base_neutral:.4f})")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    peft_dir = OUT / f"peft-{stamp}"; mlx_dir = peft_dir / "mlx"; mlx_dir.mkdir(parents=True, exist_ok=True)
    mx.save_safetensors(str(mlx_dir / "adapters.safetensors"),
                        {k: v.astype(mx.float32) for k, v in tree_flatten(model.trainable_parameters())})
    # mlx-lm's own fields first (mlx_lm.load(adapter_path=...) reads these); the top-level r / alpha /
    # dropout / target_modules are what mlx_adapter_to_peft.py copies into the PEFT config.
    mlx_cfg = {"fine_tune_type": "lora", "num_layers": n_layers, "model": str(MLX_MODEL),
               "lora_parameters": {"rank": RANK, "scale": SCALE, "dropout": 0.0, "keys": LORA_KEYS},
               "r": RANK, "alpha": SCALE * RANK, "dropout": 0.0,
               "target_modules": ["q_proj", "k_proj", "v_proj", "o_proj", "out_proj"]}
    (mlx_dir / "adapter_config.json").write_text(json.dumps(mlx_cfg, indent=1))
    result = {"stamp": stamp, "trainer": "home-sparse-mlx", "layers": n_layers, "lines": len(fresh),
              "replay_lines": len(replay), "past_pool": len(past), "steps": steps, "minutes": round(minutes, 1),
              "peak_vram_gib": round(peak, 1), "holdout_delta": round(d_hold, 4), "neutral_delta": round(d_neutral, 4),
              "window_since": since.isoformat(), "base_holdout_nll": round(base_hold, 4), "mlx_model": MLX_MODEL.name}
    model = layers = opt = loss_and_grad = grads = None   # the converters below want the memory back
    if hasattr(mx, "clear_cache"):
        mx.clear_cache()

    if sw.REHEARSE:
        return sw.end_rehearsal(result, peft_dir, d_neutral <= sw.GATE_NEUTRAL_MAX and d_hold <= sw.GATE_HOLDOUT_MIN,
                                "brainwrite-mlx")
    if not (d_neutral <= sw.GATE_NEUTRAL_MAX and d_hold <= sw.GATE_HOLDOUT_MIN):
        (OUT / "rejected").mkdir(exist_ok=True)
        shutil.move(str(peft_dir), str(OUT / "rejected" / peft_dir.name))
        result["gate"] = "FAILED"
        (OUT / "last-result.json").write_text(json.dumps(result, indent=1))
        print("[brainwrite-mlx] gate FAILED — nothing staged")
        return 4

    # mlx → PEFT. The bridge works in place and renames the mlx config aside; afterwards the PEFT
    # pair moves up into peft-<stamp>/ and the mlx directory gets its own config back.
    bridge = subprocess.run([sys.executable, str(MLX_TO_PEFT), str(mlx_dir)], capture_output=True, text=True, timeout=600)
    if bridge.returncode != 0 or not (mlx_dir / "adapter_model.safetensors").is_file():
        print("[brainwrite-mlx] mlx → PEFT conversion failed:", (bridge.stdout + bridge.stderr)[-600:])
        return 5
    shutil.move(str(mlx_dir / "adapter_model.safetensors"), str(peft_dir / "adapter_model.safetensors"))
    peft_cfg = json.loads((mlx_dir / "adapter_config.json").read_text())
    peft_cfg["layers_to_transform"] = list(range(cut, cut + n_layers))
    (peft_dir / "adapter_config.json").write_text(json.dumps(peft_cfg, indent=1))
    (mlx_dir / "adapter_config.json").write_text(json.dumps(mlx_cfg, indent=1))
    (mlx_dir / "adapter_config.mlx.json").unlink(missing_ok=True)
    from safetensors import safe_open
    with safe_open(str(peft_dir / "adapter_model.safetensors"), framework="np") as f:
        bad = [k for k in f.keys() if not PEFT_KEY.match(k)]
    if bad:
        print(f"[brainwrite-mlx] mlx → PEFT conversion left {len(bad)} tensor names the GGUF converter "
              f"cannot place, e.g. {bad[0]}")
        return 5

    gguf = OUT / f"adapter-{stamp}.gguf"
    env = dict(os.environ, PYTHONPATH=f"{sw.LLAMACPP}:{sw.LLAMACPP}/gguf-py")
    conv = subprocess.run([CONVERT_PY, str(Path(__file__).with_name("convert_lora_gdn.py")), "--base", str(BUNDLE / "hf"),
                           "--outtype", "f16", "--outfile", str(gguf), str(peft_dir)],
                          capture_output=True, text=True, timeout=600, env=env)
    if conv.returncode != 0 or not gguf.is_file():
        print("[brainwrite-mlx] gguf conversion failed:", (conv.stdout + conv.stderr)[-600:])
        return 5
    tmp = OUT / "current.gguf.tmp"; shutil.copyfile(gguf, tmp); tmp.replace(OUT / "current.gguf")
    result.update(gate="PASSED", adapter=gguf.name)
    (OUT / "last-result.json").write_text(json.dumps(result, indent=1))
    STATE.write_text(json.dumps({"last_success_ts": datetime.now(timezone.utc).isoformat(), "last_adapter": gguf.name}))
    for pattern in ("adapter-*.gguf", "peft-*"):
        for old in sorted(OUT.glob(pattern))[:-KEEP_ADAPTERS]:
            shutil.rmtree(old) if old.is_dir() else old.unlink()
    print(f"[brainwrite-mlx] staged {gguf.name}")
    return 0


def _wyrd():
    """The launcher, for stopping and starting the single model's native server."""
    import shutil as _sh
    for c in (os.environ.get("WYRDSEKAI_WYRD_BIN"), _sh.which("wyrd"), "/usr/local/wyrdsekai/bin/wyrd",
              str(Path(__file__).resolve().parents[3] / "bin" / "wyrd")):
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def _server(verb):
    """`wyrd brain stop-server` before the write, `wyrd brain serve` after it. A 48 GB Mac cannot
    hold the served model and the trainer's copy at once. WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE=false
    leaves the server alone (a machine with memory to spare)."""
    if os.environ.get("WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE", "true").lower() in ("0", "false"):
        return
    wyrd = _wyrd()
    if wyrd is None:
        print(f"[brainwrite-mlx] launcher not found; the model server was not asked to {verb}")
        return
    try:
        subprocess.run([wyrd, "brain", verb], capture_output=True, text=True, timeout=300)
        print(f"[brainwrite-mlx] wyrd brain {verb}")
    except Exception as e:
        print(f"[brainwrite-mlx] wyrd brain {verb} failed: {e}")


if __name__ == "__main__":
    code = 1
    try:
        _server("stop-server")
        code = main()
    finally:
        _server("serve")
    sys.exit(code)
