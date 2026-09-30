"""Routed-expert weights read from the serving GGUF and dequantised on the GPU.

The single-model night's write trains against the experts the companion is actually served
from, so the trainer needs no second copy of them. The serving file stores the expert stacks
in llama.cpp K-quant formats (gate/up Q4_K, down Q5_K or Q6_K). The block layouts and the
arithmetic below follow llama.cpp's reference implementation (gguf-py/gguf/quants.py, MIT
licence), written with torch ops so a whole layer's stack dequantises on the card in
milliseconds. `self_check()` compares them with the reference on real tensors.
"""
import numpy as np
import torch

QK_K = 256
BLOCK_BYTES = {"Q4_K": 144, "Q5_K": 176, "Q6_K": 210}


def _scale_min(scales):
    """The 6-bit scales and mins of the eight 32-value sub-blocks (12 bytes per block)."""
    n = scales.shape[0]
    s = scales.reshape(n, 3, 4)
    d, m, m_d = s[:, 0], s[:, 1], s[:, 2]
    sc = torch.cat([d & 0x3F, (m_d & 0x0F) | ((d >> 2) & 0x30)], dim=-1)
    mn = torch.cat([m & 0x3F, (m_d >> 4) | ((m >> 2) & 0x30)], dim=-1)
    return sc, mn


def _f16(two_bytes):
    return two_bytes.contiguous().view(torch.float16).to(torch.float32)


def dequant_q4_k(blocks):
    n = blocks.shape[0]
    d, dmin, scales, qs = _f16(blocks[:, 0:2]), _f16(blocks[:, 2:4]), blocks[:, 4:16], blocks[:, 16:144]
    sc, mn = _scale_min(scales)
    d = (d * sc.to(torch.float32)).reshape(n, 8, 1)
    dm = (dmin * mn.to(torch.float32)).reshape(n, 8, 1)
    shifts = torch.tensor([0, 4], dtype=torch.uint8, device=blocks.device).reshape(1, 1, 2, 1)
    q = ((qs.reshape(n, 4, 1, 32) >> shifts) & 0x0F).reshape(n, 8, 32).to(torch.float32)
    return (d * q - dm).reshape(n, QK_K)


def dequant_q5_k(blocks):
    n = blocks.shape[0]
    d, dmin, scales = _f16(blocks[:, 0:2]), _f16(blocks[:, 2:4]), blocks[:, 4:16]
    qh, qs = blocks[:, 16:48], blocks[:, 48:176]
    sc, mn = _scale_min(scales)
    d = (d * sc.to(torch.float32)).reshape(n, 8, 1)
    dm = (dmin * mn.to(torch.float32)).reshape(n, 8, 1)
    s2 = torch.tensor([0, 4], dtype=torch.uint8, device=blocks.device).reshape(1, 1, 2, 1)
    s8 = torch.arange(8, dtype=torch.uint8, device=blocks.device).reshape(1, 1, 8, 1)
    ql = ((qs.reshape(n, 4, 1, 32) >> s2) & 0x0F).reshape(n, 8, 32)
    hi = ((qh.reshape(n, 1, 1, 32) >> s8) & 0x01).reshape(n, 8, 32)
    q = (ql | (hi << 4)).to(torch.float32)
    return (d * q - dm).reshape(n, QK_K)


def dequant_q6_k(blocks):
    n = blocks.shape[0]
    ql, qh, scales, d = blocks[:, 0:128], blocks[:, 128:192], blocks[:, 192:208], _f16(blocks[:, 208:210])
    d = (d * scales.contiguous().view(torch.int8).to(torch.float32)).reshape(n, 16, 1)
    s2 = torch.tensor([0, 4], dtype=torch.uint8, device=blocks.device).reshape(1, 1, 2, 1)
    s4 = torch.tensor([0, 2, 4, 6], dtype=torch.uint8, device=blocks.device).reshape(1, 1, 4, 1)
    lo = ((ql.reshape(n, 2, 1, 64) >> s2) & 0x0F).reshape(n, 8, 32)
    hi = ((qh.reshape(n, 2, 1, 32) >> s4) & 0x03).reshape(n, 8, 32)
    q = ((lo | (hi << 4)).to(torch.int16) - 32).reshape(n, 16, 16).to(torch.float32)
    return (d * q).reshape(n, QK_K)


DEQUANT = {"Q4_K": dequant_q4_k, "Q5_K": dequant_q5_k, "Q6_K": dequant_q6_k}


class GgufExperts:
    """The expert stacks of one serving GGUF, by layer. Raw quantised bytes are handed to the
    GPU as they sit in the file; `weights()` returns (gate_up, down) in the layout the HF model
    uses: gate_up [experts, 2*ff, hidden] (gate first), down [experts, hidden, ff]."""

    def __init__(self, gguf_path):
        import gguf                                    # llama.cpp's gguf-py, on PYTHONPATH
        self.reader = gguf.GGUFReader(gguf_path)
        self.index = {}
        for t in self.reader.tensors:
            if "_exps" not in t.name: continue
            layer = int(t.name.split(".")[1]); kind = t.name.split(".")[2]
            qtype = t.tensor_type.name
            if qtype not in DEQUANT: raise ValueError(f"{t.name}: expert format {qtype} is not supported")
            self.index[(layer, kind)] = (t, qtype, tuple(int(x) for x in reversed(t.shape)))
        self.layers = 1 + max(k[0] for k in self.index) if self.index else 0

    def raw(self, layer, device):
        """One layer's three stacks as uint8 tensors on `device` (about 0.47 GB)."""
        out = {}
        for kind in ("ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"):
            t, qtype, shape = self.index[(layer, kind)]
            out[kind] = (torch.from_numpy(np.ascontiguousarray(t.data)).to(device), qtype, shape)
        return out

    @staticmethod
    def dequantise(raw_entry, dtype=torch.bfloat16):
        data, qtype, shape = raw_entry
        blocks = data.reshape(-1, BLOCK_BYTES[qtype])
        return DEQUANT[qtype](blocks).reshape(shape).to(dtype)

    @classmethod
    def gate_up(cls, raw, dtype=torch.bfloat16):
        return torch.cat([cls.dequantise(raw["ffn_gate_exps"], dtype), cls.dequantise(raw["ffn_up_exps"], dtype)], dim=1)

    @classmethod
    def down(cls, raw, dtype=torch.bfloat16):
        return cls.dequantise(raw["ffn_down_exps"], dtype)


def self_check(gguf_path, device="cuda:0", layers=(0, 39)):
    """Compare the torch dequantisers with llama.cpp's reference on real expert tensors."""
    import gguf
    from gguf import quants
    ex = GgufExperts(gguf_path); worst = 0.0; seen = set()
    for layer in layers:
        for kind in ("ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"):
            t, qtype, shape = ex.index[(layer, kind)]
            rows = np.ascontiguousarray(t.data.reshape(-1, t.data.shape[-1])[:64])      # 64 rows is plenty
            ref = quants.dequantize(rows, t.tensor_type).astype(np.float32)
            got = DEQUANT[qtype](torch.from_numpy(rows).to(device).reshape(-1, BLOCK_BYTES[qtype])).reshape(ref.shape).cpu().numpy()
            worst = max(worst, float(np.abs(ref - got).max())); seen.add(qtype)
    return worst, sorted(seen)


if __name__ == "__main__":
    import sys
    worst, seen = self_check(sys.argv[1])
    print(f"formats checked: {seen}; largest difference from the reference: {worst:.3e}")
    sys.exit(0 if worst < 1e-4 else 1)
