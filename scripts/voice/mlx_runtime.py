#!/usr/bin/env python3
# recipe-callable: local-ok
"""Wyrdsekai macOS voice runtime — mlx_lm.server with V8 control vectors.

Phase 1B of. Wraps `mlx_lm.server.main()` and
monkey-patches `Qwen3_5TextModel.__call__` so that after each
transformer block's residual sum, the per-layer V8 vector deltas are
added to the residual stream. Mirrors llama.cpp's
`--control-vector-scaled` semantics on the home-server :8201 path.

Vector application:
    For MLX layer index `i` (0-based) and loaded vector V with tensor
    `direction.{i}` at scale `alpha`, the runtime adds
        hidden_states_after_layer_i += alpha * V[direction.{i}]
    The N in `direction.N` matches MLX's 0-based loop index, identical
    to llama.cpp's convention. Layer 0 typically has no direction tensor
    so it gets no intervention; vectors live for layers 1..31 on
    Qwen3.5-4B (hidden_size=2560, num_hidden_layers=32).

Invocation:
    python -m scripts.voice.mlx_runtime \\
        --vectors-dir data/training/v8/mlx \\
        --vectors anti_defiance:0.15,es_register_hold:0.20,refusal_stability:0.20,first_person_presence:0.15 \\
        -- \\
        --model <mlx-base-dir> --adapter-path <adapter-dir> \\
        --port 8201 --chat-template-args '{"enable_thinking": false}'

Args before `--` are wyrdsekai-specific (vector configuration). Args
after `--` pass through unchanged to `mlx_lm.server.main`.

If `--vectors` is empty (or none of the named vectors are loadable),
the runtime patches no layers and falls through to vanilla
mlx_lm.server. This is the safety net for early Phase 1 verification
where you may want a baseline run without vectors.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import threading
from pathlib import Path
from typing import Optional

logger = logging.getLogger("wyrd.voice.mlx_runtime")

# Per-request steering channel (individuality V2). mlx_lm.server (0.31.3) runs
# token generation on a DIFFERENT worker thread than the one that parsed the
# request — verified live — so a threading.local set on the handler thread is
# invisible to the forward pass. We carry the request's combined per-layer deltas
# through a module global instead, and hold `_GEN_LOCK` for the whole generation
# so concurrent requests serialize cleanly (MLX already serializes the single
# model's compute, so this costs no real throughput). `deltas is None` → fall
# back to the static (V8, all-requests) deltas, preserving prior global behavior.
_CURRENT = {"deltas": None}
_GEN_LOCK = threading.Lock()

# Safety clamp on any per-request scale, regardless of caller — the live probe on
# the V10 4B showed coherence collapses well before ±2.0; the seed-derived map
# already clamps to ±0.55, this is just belt-and-suspenders against a bad request.
_MAX_REQUEST_SCALE = 0.6


def _parse_vectors_arg(spec: str) -> list[tuple[str, float]]:
    """`name:scale,name:scale,...` -> [(name, scale), ...].
    Mirrors the env format used by bin/wyrd for WYRDSEKAI_V8_VECTORS,
    except names here have no `.gguf` extension (we resolve to
    `.safetensors` under --vectors-dir)."""
    out: list[tuple[str, float]] = []
    for pair in spec.split(","):
        pair = pair.strip()
        if not pair:
            continue
        if ":" not in pair:
            raise SystemExit(
                f"[mlx_runtime] bad --vectors entry {pair!r}: expected name:scale")
        name, scale_s = pair.rsplit(":", 1)
        name = name.strip()
        if name.endswith(".gguf"):
            name = name[:-5]
        if name.endswith(".safetensors"):
            name = name[: -len(".safetensors")]
        try:
            scale = float(scale_s.strip())
        except ValueError:
            raise SystemExit(
                f"[mlx_runtime] bad scale {scale_s!r} in {pair!r}")
        out.append((name, scale))
    return out


def _load_layer_deltas(vectors_dir: Path,
                       vectors: list[tuple[str, float]]) -> Optional[dict]:
    """Return {layer_idx: mx.array(hidden_size,)} of summed deltas, or
    None if no vectors loaded.

    Sums `alpha * vec` across vectors per layer index so the patched
    forward pass does a single add per layer (cheaper than N adds)."""
    try:
        import mlx.core as mx
        from safetensors.numpy import load_file
        import numpy as np
    except ImportError as e:
        raise SystemExit(
            f"[mlx_runtime] missing mlx/safetensors/numpy: {e}")

    if not vectors:
        return None
    if not vectors_dir.exists():
        logger.warning(f"vectors-dir {vectors_dir} missing — running without V8")
        return None

    summed: dict[int, np.ndarray] = {}
    loaded = []
    for name, scale in vectors:
        p = vectors_dir / f"{name}.safetensors"
        if not p.exists():
            logger.warning(f"vector {name} missing at {p} — skipping")
            continue
        tensors = load_file(str(p))
        for key, arr in tensors.items():
            if not key.startswith("direction."):
                continue
            try:
                idx = int(key.split(".", 1)[1])
            except ValueError:
                continue
            contribution = (arr.astype(np.float32) * float(scale))
            if idx in summed:
                summed[idx] = summed[idx] + contribution
            else:
                summed[idx] = contribution
        loaded.append((name, scale, len(tensors)))

    if not summed:
        logger.warning("no V8 vectors loaded — running without control vectors")
        return None

    deltas = {idx: mx.array(arr) for idx, arr in summed.items()}
    logger.info(
        f"V8 vectors loaded: "
        + ", ".join(f"{n}:{s}({k})" for n, s, k in loaded)
        + f" -> {len(deltas)} layer deltas")
    return deltas


def _load_basis(vectors_dir: Path,
                names: list[str]) -> dict:
    """Return {basis_name: {layer_idx: mx.array(hidden_size,)}} — the register
    basis vectors kept SEPARATE (not summed), so each request can mix them at its
    own per-agent scales. Individuality V2: the seed-derived `registerMix()` names
    map to these files (`register_warmth.safetensors`, …)."""
    try:
        import mlx.core as mx
        from safetensors.numpy import load_file
        import numpy as np
    except ImportError as e:
        raise SystemExit(f"[mlx_runtime] missing mlx/safetensors/numpy: {e}")

    out: dict[str, dict[int, "object"]] = {}
    if not names:
        return out
    if not vectors_dir.exists():
        logger.warning(f"register-basis dir {vectors_dir} missing — no per-agent voice")
        return out
    for name in names:
        name = name.strip()
        if not name:
            continue
        p = vectors_dir / f"{name}.safetensors"
        if not p.exists():
            logger.warning(f"register basis {name} missing at {p} — skipping")
            continue
        layers: dict[int, "object"] = {}
        for key, arr in load_file(str(p)).items():
            if not key.startswith("direction."):
                continue
            try:
                idx = int(key.split(".", 1)[1])
            except ValueError:
                continue
            layers[idx] = mx.array(arr.astype(np.float32))
        if layers:
            out[name] = layers
    if out:
        logger.info(f"register basis loaded: {', '.join(f'{n}({len(v)})' for n, v in out.items())}")
    return out


def _compute_request_deltas(static_deltas: dict, basis: dict, mix: dict) -> dict:
    """Per-layer combined delta for ONE request: the static (V8) base plus
    `Σ scale·basis[name]` over the request's mix. Computed once per request (cheap
    — a handful of small per-layer vectors), then read each forward step."""
    out = dict(static_deltas)  # shallow copy; values are mx arrays (immutable to us)
    for name, raw in mix.items():
        layers = basis.get(name)
        if not layers:
            continue
        try:
            scale = float(raw)
        except (TypeError, ValueError):
            continue
        scale = max(-_MAX_REQUEST_SCALE, min(_MAX_REQUEST_SCALE, scale))
        if scale == 0.0:
            continue
        for idx, vec in layers.items():
            contribution = scale * vec
            out[idx] = (out[idx] + contribution) if idx in out else contribution
    # Materialize NOW, on this (handler) thread. mlx_lm runs the forward pass on a
    # different worker thread; handing it a lazy `scale*vec` graph to evaluate there
    # produces corrupt activations (empty generations). Eager eval pins concrete
    # values that cross the thread boundary cleanly.
    if out:
        import mlx.core as mx
        mx.eval(list(out.values()))
    return out


def _install_patch(static_deltas: dict, basis: dict) -> None:
    """Monkey-patch `Qwen3_5TextModel.__call__` to add per-layer deltas after each
    layer's forward. Uses the current request's combined deltas (`_REQ.deltas`)
    when set — per-agent voice — else the static V8 deltas (global, prior behavior)."""
    from mlx_lm.models import qwen3_5 as q35
    from mlx_lm.models.base import create_attention_mask, create_ssm_mask

    def patched_call(self, inputs, cache=None, input_embeddings=None):
        if input_embeddings is not None:
            hidden_states = input_embeddings
        else:
            hidden_states = self.embed_tokens(inputs)

        if cache is None:
            cache = [None] * len(self.layers)

        fa_mask = create_attention_mask(hidden_states, cache[self.fa_idx])
        ssm_mask = create_ssm_mask(hidden_states, cache[self.ssm_idx])

        req_deltas = _CURRENT["deltas"]
        active = req_deltas if req_deltas is not None else static_deltas

        for i, (layer, c) in enumerate(zip(self.layers, cache)):
            mask = ssm_mask if layer.is_linear else fa_mask
            hidden_states = layer(hidden_states, mask=mask, cache=c)
            delta = active.get(i)
            if delta is not None:
                hidden_states = hidden_states + delta

        return self.norm(hidden_states)

    q35.Qwen3_5TextModel.__call__ = patched_call
    setattr(q35.Qwen3_5TextModel, "_wyrd_v8_patched", True)
    logger.info(
        f"patched Qwen3_5TextModel.__call__ — static={len(static_deltas)} layer deltas, "
        f"per-agent basis={list(basis.keys()) or 'none'}")


def _install_request_hook(static_deltas: dict, basis: dict) -> None:
    """Wrap `mlx_lm.server.APIHandler.handle_completion` (the generation method,
    which has the parsed `self.body` and spans the whole decode) so a request body
    field `register_mix: {basis_name: scale}` becomes this request's per-layer
    deltas on `_REQ`. Absent/empty → falls back to the static V8 deltas. Clears on
    the way out so a pooled handler thread never leaks one agent's voice to the next."""
    if not basis:
        return
    from mlx_lm import server as s

    orig = s.APIHandler.handle_completion

    def wrapped(self, request, stop_words):
        mix = None
        try:
            body = getattr(self, "body", None)
            if isinstance(body, dict):
                m = body.get("register_mix")
                if isinstance(m, dict) and m:
                    mix = m
        except Exception:
            mix = None
        with _GEN_LOCK:
            _CURRENT["deltas"] = _compute_request_deltas(static_deltas, basis, mix) if mix else None
            try:
                return orig(self, request, stop_words)
            finally:
                _CURRENT["deltas"] = None

    s.APIHandler.handle_completion = wrapped
    logger.info("installed per-request register_mix hook on APIHandler.handle_completion")


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    # Two-phase parsing: peel off our flags up to `--`, leave the rest
    # for mlx_lm.server.main. argparse parse_known_args won't work
    # cleanly because mlx_lm.server has its own --model flag conflicting
    # with our positional split semantics.
    argv = sys.argv[1:]
    our_argv: list[str] = []
    rest_argv: list[str] = []
    if "--" in argv:
        sep = argv.index("--")
        our_argv = argv[:sep]
        rest_argv = argv[sep + 1:]
    else:
        # No separator: everything is for us (unusual; produces error
        # since --model is required by mlx_lm.server). Allow it for
        # --help testing.
        our_argv = argv

    ap = argparse.ArgumentParser(
        description="Wyrdsekai macOS voice runtime — mlx_lm.server + V8 vectors",
        add_help=True)
    ap.add_argument("--vectors-dir", type=Path,
                    default=Path("data/training/v8/mlx"),
                    help="Dir containing <name>.safetensors V8 vectors.")
    ap.add_argument("--vectors", type=str, default="",
                    help="Comma-separated name:scale pairs (without .gguf "
                         "or .safetensors extension). Empty disables V8.")
    ap.add_argument("--register-basis", type=str, default="",
                    help="Comma-separated basis-vector names (no extension) loaded "
                         "for PER-REQUEST mixing — each request's body "
                         "`register_mix:{name:scale}` steers per-agent voice "
                         "(individuality V2). Empty disables per-agent voice.")
    ap.add_argument("--register-basis-dir", type=Path, default=None,
                    help="Dir holding the register basis .safetensors "
                         "(defaults to --vectors-dir).")
    ap.add_argument("--log-level", default="INFO",
                    choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    args = ap.parse_args(our_argv)
    logging.getLogger().setLevel(args.log_level)

    vectors = _parse_vectors_arg(args.vectors)
    static_deltas = _load_layer_deltas(args.vectors_dir, vectors) or {}
    basis = _load_basis(
        args.register_basis_dir or args.vectors_dir,
        [n for n in args.register_basis.split(",") if n.strip()])
    if static_deltas or basis:
        _install_patch(static_deltas, basis)
        _install_request_hook(static_deltas, basis)
    else:
        logger.info(
            "no V8 deltas / register basis active — Qwen3_5TextModel unpatched, "
            "vanilla mlx_lm.server")

    # Replay rest_argv as if invoked directly: `mlx_lm.server [rest]`.
    sys.argv = ["mlx_lm.server"] + rest_argv

    from mlx_lm.server import main as server_main
    return server_main()


if __name__ == "__main__":
    sys.exit(main() or 0)
