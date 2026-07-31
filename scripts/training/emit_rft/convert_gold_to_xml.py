"""convert the teacher-gold EMIT turns from OpenAI-JSON to Qwen XML.

The completion-only warmup taught the model to emit, but valid-rate stayed 0: the gold
recipes were wrapped as ``<tool_call>{"name":"shape_recipe","arguments":{...}}</tool_call>``
(OpenAI JSON), while the live Qwen3.5 model emits — and prod parses — the XML function
form ``<tool_call><function=shape_recipe><parameter=yaml>…</parameter></function></tool_call>``.
So the model learned "emit" but reproduced the *wrong channel* with freeform YAML.

This rewrites each EMIT gold assistant turn into the model's native XML channel (same shape
ActionParser.extractXmlToolCalls + reward.extract_shape_recipe_yaml consume), preserving the
contract-valid YAML content. REST turns pass through unchanged. Zero API cost.

    python convert_gold_to_xml.py \
        --in data/training/emit_rft/warmup.jsonl \
        --out data/training/emit_rft/warmup_xml.jsonl
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reward as R


def _tool_call_obj(content: str):
    """Pull the JSON shape_recipe tool-call object out of a gold EMIT assistant turn."""
    blocks = re.findall(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", content, re.DOTALL)
    if not blocks:
        blocks = re.findall(r"\{.*?shape_recipe.*?\}", content, re.DOTALL)
    for raw in blocks:
        obj = R._loads_lenient(raw)
        if isinstance(obj, dict) and (obj.get("name") == "shape_recipe"
                                      or (obj.get("function") or {}).get("name") == "shape_recipe"):
            return obj
    return None


def _to_xml(name: str, yaml_text: str) -> str:
    return ("<tool_call>\n<function=shape_recipe>\n"
            f"<parameter=name>\n{name}\n</parameter>\n"
            f"<parameter=yaml>\n{yaml_text}\n</parameter>\n"
            "</function>\n</tool_call>")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.inp).read_text().splitlines() if l.strip()]
    conv = skipped = passthrough = 0
    out_rows = []
    for r in rows:
        if r.get("label") != "emit":
            out_rows.append(r); passthrough += 1; continue
        content = (r["messages"][-1].get("content") or "")
        obj = _tool_call_obj(content)
        args_obj = (obj or {}).get("arguments")
        if isinstance(args_obj, str):
            args_obj = R._loads_lenient(args_obj) or {}
        yaml_text = (args_obj or {}).get("yaml") if isinstance(args_obj, dict) else None
        name = (args_obj or {}).get("name") if isinstance(args_obj, dict) else None
        if not (isinstance(yaml_text, str) and yaml_text.strip()):
            # couldn't parse — keep the row as-is rather than drop a training example
            out_rows.append(r); skipped += 1; continue
        if not name:
            m = re.search(r"^\s*(?:recipe|name)\s*:\s*([A-Za-z0-9._-]+)", yaml_text, re.MULTILINE)
            name = m.group(1) if m else "recipe"
        new = dict(r)
        new_msgs = list(r["messages"])
        new_msgs[-1] = {"role": "assistant", "content": _to_xml(name, yaml_text)}
        new["messages"] = new_msgs
        out_rows.append(new); conv += 1

    Path(args.out).write_text("".join(json.dumps(x) + "\n" for x in out_rows))
    print(f"[convert] rows={len(rows)} emit->xml={conv} rest-passthrough={passthrough} "
          f"unparsed-kept={skipped} -> {args.out}")
    # sanity: the converted gold must round-trip through the same extractor prod uses
    ok = 0
    for x in out_rows:
        if x.get("label") == "emit":
            y = R.extract_shape_recipe_yaml(x["messages"][-1]["content"])
            ok += bool(y)
    print(f"[convert] self-check: {ok}/{conv} converted EMIT turns re-extract a yaml")
    return 0 if ok == conv else 1


if __name__ == "__main__":
    raise SystemExit(main())
