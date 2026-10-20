#!/usr/bin/env python3
"""Does this machine's GPU do the night's arithmetic right? Run before the first night on a new
GPU, and before every night on a ROCm one.

The night's write (brain_write.py) trains in bf16 on whatever PyTorch calls `cuda`: an NVIDIA
card, or an AMD or Intel GPU through ROCm. On the AMD chips households buy for this (Ryzen AI
MAX, gfx1151) the PyTorch builds are young, and people have found bf16 results that were wrong
without any error (ROCm issue 6034, 2026). A write that trains on wrong arithmetic passes no
gate it should and fails ones it shouldn't, and nothing says why. So the machine proves itself
first: the same small forward and backward in bf16 on the GPU and in fp32 on the CPU, the
attention kernel the model uses, a LoRA-shaped step, the GGUF dequantiser against llama.cpp's
reference on the served file, and the room on the card. The verdict is written beside her
adapters (trainer-check.json) and shown by `wyrd brain status`; brain_write.py reads it on ROCm.

Exit codes: 0 passed, 1 failed (the file says what), 6 no GPU here. --cpu runs the GPU side on
the CPU in bf16 too (tests on a box without a GPU); its verdict is never written as a pass.
"""
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DATA = Path(os.environ.get("WYRDSEKAI_DATA_DIR", "/var/lib/wyrdsekai"))
OUT = DATA / "adapters" / "brainwrite" / "trainer-check.json"
SERVING_GGUF = Path(os.environ.get("WYRDSEKAI_BRAIN_WRITE_GGUF",
                    str(DATA / "models" / os.environ.get("LLAMA_BRAIN_MODEL", "Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"))))
LLAMACPP = Path(os.environ.get("WYRDSEKAI_SLEEP_WRITE_LLAMACPP", str(DATA / "sleepwrite" / "llama.cpp")))
MIN_FREE_VRAM_MIB = int(os.environ.get("WYRDSEKAI_BRAIN_WRITE_MIN_VRAM_MIB", "13500"))

# bf16 keeps about three significant digits; a healthy kernel lands well inside these.
FORWARD_REL_MAX = 2.5e-2
BACKWARD_REL_MAX = 4.0e-2
SDPA_REL_MAX = 2.5e-2
GGUF_ABS_MAX = 1e-4


def rel(a, b):
    """Relative difference of two tensors, as one number: |a-b| against the scale of b."""
    import torch
    a = a.detach().float().cpu(); b = b.detach().float().cpu()
    return float((a - b).norm() / (b.norm() + 1e-12))


def main():
    cpu_only = "--cpu" in sys.argv
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    result = {"when": datetime.now(timezone.utc).isoformat(), "torch": torch.__version__,
              "hip": getattr(torch.version, "hip", None), "cuda": getattr(torch.version, "cuda", None),
              "device": None, "checks": {}, "reasons": [], "ok": False}
    reasons = result["reasons"]

    if cpu_only:
        dev = torch.device("cpu")
        result["device"] = "cpu (--cpu: the GPU side run on the CPU)"
    elif torch.cuda.is_available():
        dev = torch.device("cuda:0")
        result["device"] = torch.cuda.get_device_name(0)
        free, total = torch.cuda.mem_get_info(0)
        result["checks"]["free_mib"] = free // 2**20
        result["checks"]["total_mib"] = total // 2**20
        # Room is a note, not a verdict: the write stops the served model before it starts, and
        # brain_write.py measures the room again then. A check run beside a running brain still passes.
        if free // 2**20 < MIN_FREE_VRAM_MIB:
            result["checks"]["room"] = (f"{free // 2**20} MiB free now, the write needs {MIN_FREE_VRAM_MIB} "
                                        f"with the served model stopped (or WYRDSEKAI_BRAIN_WRITE_LAYERS=8)")
    else:
        print("[trainer-check] no GPU that PyTorch can use here (torch.cuda.is_available() is false)")
        result["reasons"].append("no GPU")
        write(result, as_pass=False)
        return 6

    torch.manual_seed(7)
    # 1. A forward the model's layers are made of: linear, gated activation, an RMS norm, a residual.
    d, h, n = 512, 2048, 96
    x = torch.randn(n, d)
    w1 = torch.randn(h, d) * 0.02; w2 = torch.randn(h, d) * 0.02; w3 = torch.randn(d, h) * 0.02
    g = torch.ones(d) + 0.1 * torch.randn(d)

    def block(x, w1, w2, w3, g):
        y = F.linear(F.silu(F.linear(x, w1)) * F.linear(x, w2), w3)
        y = x + y
        return y * torch.rsqrt(y.pow(2).mean(-1, keepdim=True) + 1e-6) * g

    ref = block(x, w1, w2, w3, g)
    bf = lambda t: t.to(dev, torch.bfloat16)
    t0 = time.time()
    got = block(bf(x), bf(w1), bf(w2), bf(w3), bf(g))
    if dev.type == "cuda": torch.cuda.synchronize()
    fwd = rel(got, ref); result["checks"]["bf16_forward_rel"] = round(fwd, 5)
    result["checks"]["forward_ms"] = round((time.time() - t0) * 1000, 1)
    if not (fwd == fwd) or fwd > FORWARD_REL_MAX:
        reasons.append(f"bf16 forward differs from fp32 by {fwd:.3g} (limit {FORWARD_REL_MAX})")

    # 2. The attention kernel the model uses, in bf16, with a causal mask.
    B, H, L, Dh = 1, 8, 256, 64
    q = torch.randn(B, H, L, Dh); k = torch.randn(B, H, L, Dh); v = torch.randn(B, H, L, Dh)
    ref_a = F.scaled_dot_product_attention(q, k, v, is_causal=True)
    got_a = F.scaled_dot_product_attention(bf(q), bf(k), bf(v), is_causal=True)
    sd = rel(got_a, ref_a); result["checks"]["sdpa_rel"] = round(sd, 5)
    if not (sd == sd) or sd > SDPA_REL_MAX:
        reasons.append(f"bf16 attention differs from fp32 by {sd:.3g} (limit {SDPA_REL_MAX})")

    # 3. A LoRA-shaped step: frozen weight, trained low-rank pair, a loss, one backward, one update.
    r = 16
    # Both halves non-zero, so both gradients are real numbers to compare; with the zero half a
    # LoRA starts from, the other half's gradient is exactly zero and proves nothing.
    A0 = torch.randn(r, d) * 0.02; B0 = torch.randn(d, r) * 0.02; W = torch.randn(d, d) * 0.02
    target = torch.randn(n, d)

    def lora_loss(x, W, A, B, target):
        y = F.linear(x, W) + F.linear(F.linear(x, A), B) * (2.0)
        return F.mse_loss(y.float(), target.float())

    A_ref = A0.clone().requires_grad_(True); B_ref = B0.clone().requires_grad_(True)
    lora_loss(x, W, A_ref, B_ref, target).backward()
    A_g = bf(A0).requires_grad_(True); B_g = bf(B0).requires_grad_(True)
    lora_loss(bf(x), bf(W), A_g, B_g, bf(target)).backward()
    if dev.type == "cuda": torch.cuda.synchronize()
    bwd = max(rel(A_g.grad, A_ref.grad), rel(B_g.grad, B_ref.grad)); result["checks"]["bf16_backward_rel"] = round(bwd, 5)
    if not (bwd == bwd) or bwd > BACKWARD_REL_MAX:
        reasons.append(f"bf16 backward differs from fp32 by {bwd:.3g} (limit {BACKWARD_REL_MAX})")
    if float(A_ref.grad.norm()) == 0.0 or float(B_ref.grad.norm()) == 0.0:
        reasons.append("the reference gradients came out zero; the check itself is wrong")
    with torch.no_grad():
        before = float(lora_loss(bf(x), bf(W), A_g, B_g, bf(target)))
        A_g -= 0.5 * A_g.grad; B_g -= 0.5 * B_g.grad
        after = float(lora_loss(bf(x), bf(W), A_g, B_g, bf(target)))
    result["checks"]["loss_before"] = round(before, 5); result["checks"]["loss_after"] = round(after, 5)
    if not after < before:
        reasons.append(f"a gradient step did not lower the loss ({before:.4g} -> {after:.4g})")

    # 4. The served experts' dequantiser against llama.cpp's reference, on the real file.
    if SERVING_GGUF.is_file() and (LLAMACPP / "gguf-py").is_dir():
        try:
            sys.path.insert(0, str(LLAMACPP / "gguf-py")); sys.path.insert(0, str(Path(__file__).parent))
            from gguf_experts import self_check
            worst, seen = self_check(str(SERVING_GGUF), device=str(dev))
            result["checks"]["gguf_worst_abs"] = float(f"{worst:.3e}"); result["checks"]["gguf_formats"] = seen
            if worst > GGUF_ABS_MAX:
                reasons.append(f"the GGUF dequantiser differs from the reference by {worst:.3g} on this GPU")
        except Exception as e:
            result["checks"]["gguf"] = f"not checked: {e}"
    else:
        result["checks"]["gguf"] = "not checked: no served file or no llama.cpp checkout here"

    # 5. What is here, for the record: the linear-attention kernels, or their fallback.
    try:
        import fla  # noqa: F401
        result["checks"]["fla"] = getattr(fla, "__version__", "present")
    except Exception as e:
        result["checks"]["fla"] = f"absent ({type(e).__name__}); the model's torch fallback is used, slower"

    result["ok"] = not reasons and not cpu_only
    write(result, as_pass=result["ok"])
    print(f"[trainer-check] {result['device']} | torch {result['torch']}"
          + (f" | ROCm {result['hip']}" if result["hip"] else f" | CUDA {result['cuda']}"))
    for k in ("free_mib", "room", "bf16_forward_rel", "sdpa_rel", "bf16_backward_rel", "loss_before", "loss_after",
              "gguf_worst_abs", "gguf", "fla"):
        if k in result["checks"]: print(f"[trainer-check]   {k}: {result['checks'][k]}")
    if cpu_only:
        print("[trainer-check] run on the CPU: the arithmetic checks hold, no GPU verdict is written")
        return 0 if not reasons else 1
    if reasons:
        print("[trainer-check] FAILED:"); [print(f"[trainer-check]   - {r}") for r in reasons]
        print(f"[trainer-check] the night's write will not run on this GPU until this passes ({OUT})")
        return 1
    print(f"[trainer-check] passed — the night's write may run on this GPU ({OUT})")
    return 0


def write(result, as_pass):
    try:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        result["ok"] = bool(as_pass)
        OUT.write_text(json.dumps(result, indent=1))
    except Exception as e:
        print(f"[trainer-check] could not write {OUT}: {e}")


def passed_here(torch_version, hip):
    """For brain_write.py: a recorded pass on this same PyTorch build and ROCm; "" when it stands, else why not."""
    try:
        d = json.loads(OUT.read_text())
    except Exception:
        return f"no trainer check has passed on this machine yet — run: wyrd brain check ({OUT})"
    if not d.get("ok"):
        return f"the last trainer check failed here ({'; '.join(d.get('reasons') or ['no reason recorded'])}) — run: wyrd brain check"
    if d.get("torch") != torch_version or d.get("hip") != hip:
        return (f"the trainer check passed on torch {d.get('torch')} / ROCm {d.get('hip')}, this is torch {torch_version} / "
                f"ROCm {hip} — run: wyrd brain check")
    return ""


if __name__ == "__main__":
    sys.exit(main())
