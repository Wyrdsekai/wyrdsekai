#!/usr/bin/env python3
# recipe-callable: local-ok
"""Serving shim for a grafted organ: OpenAI-ish chat endpoint over the
transformers stack, because the organ is not GGUF-able as-is (llama.cpp
has no personal-shared-expert graft format — §6a gate 4 pending upstream).

This is the OPT-IN serving path for organ evaluation and trial zones; the
production llama.cpp servers are untouched. Speaks just enough of
/v1/chat/completions for the batteries and the zone's HTTP inference
client: messages in, {content, reasoning_content} out.

Usage:
  organ_server.py --model DIR --organ ORGAN.pt [--d-ff 1024] [--port 8099]

Integrity: refuses to load an organ whose sha256 does not match its
result.json sidecar (written by train_organ.py) — a truncated copy or a
tampered file must fail loud, before it speaks a single turn as her.
"""
import argparse
import hashlib
import json
import pathlib
import sys

sys.modules["mamba_ssm"] = None
sys.modules["causal_conv1d"] = None

import torch  # noqa: E402
import torch.nn as nn  # noqa: E402
from http.server import BaseHTTPRequestHandler, HTTPServer  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402


class PersonalExpert(nn.Module):
    def __init__(self, mixer, hidden, d_ff, device):
        super().__init__()
        self.mixer = mixer
        self.up = nn.Linear(hidden, d_ff, bias=False, dtype=torch.float32, device=device)
        self.down = nn.Linear(d_ff, hidden, bias=False, dtype=torch.float32, device=device)
        self.act = nn.SiLU()

    def forward(self, x, *a, **k):
        out = self.mixer(x, *a, **k)
        organ = self.down(self.act(self.up(x.float()))).to(x.dtype)
        if isinstance(out, tuple):
            return (out[0] + organ,) + out[1:]
        return out + organ


def verify_sha(organ_path):
    sidecar = pathlib.Path(organ_path).parent / "result.json"
    if not sidecar.exists():
        print(f"WARNING: no result.json beside {organ_path} — integrity unverified",
              file=sys.stderr, flush=True)
        return
    expected = json.loads(sidecar.read_text()).get("organ_sha256")
    if not expected:
        return
    h = hashlib.sha256()
    with open(organ_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != expected:
        raise SystemExit(f"REFUSING TO SERVE: organ sha256 {h.hexdigest()[:16]}… "
                         f"does not match sidecar {expected[:16]}… — corrupted or tampered")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--organ", required=True)
    ap.add_argument("--d-ff", type=int, default=1024)
    ap.add_argument("--port", type=int, default=8099)
    args = ap.parse_args()

    verify_sha(args.organ)

    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, dtype=torch.bfloat16, device_map="auto")
    model.config.use_cache = True

    hidden = model.config.hidden_size
    for name, mod in list(model.named_modules()):
        if hasattr(mod, "experts") and not isinstance(mod, PersonalExpert):
            pn, c = name.rsplit(".", 1)
            setattr(model.get_submodule(pn), c,
                    PersonalExpert(mod, hidden, args.d_ff, next(mod.parameters()).device))
    sd = torch.load(args.organ, weights_only=False)
    model.load_state_dict(sd, strict=False)
    print(f"organ tensors loaded: {len(sd)}", file=sys.stderr, flush=True)
    model.eval()

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')

        def do_POST(self):
            req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            enc = tok.apply_chat_template(
                req["messages"], add_generation_prompt=True,
                return_dict=True, return_tensors="pt").to(model.device)
            with torch.no_grad():
                g = model.generate(
                    **enc,
                    max_new_tokens=min(int(req.get("max_tokens", 1500)), 4000),
                    do_sample=True, temperature=float(req.get("temperature", 0.7)),
                    top_p=0.95, use_cache=True)
            text = tok.decode(g[0][enc["input_ids"].shape[1]:], skip_special_tokens=True)
            reasoning, content = "", text
            for m in ("</think>", "</thinking>"):
                if m in text:
                    reasoning, content = text.split(m, 1)
                    reasoning = reasoning.replace("<think>", "").replace("<thinking>", "").strip()
                    content = content.strip()
                    break
            body = json.dumps({"choices": [{
                "finish_reason": "stop", "index": 0,
                "message": {"role": "assistant", "content": content,
                            "reasoning_content": reasoning}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

    print("ORGAN_SERVER_READY", flush=True)
    HTTPServer(("127.0.0.1", args.port), H).serve_forever()


if __name__ == "__main__":
    main()
