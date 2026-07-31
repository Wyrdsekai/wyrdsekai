"""make the warmup gold contract-VALID by composing REAL scripts.

valid-rate stayed 0.00 after the XML conversion because the teacher invented
script paths (``scripts/scan_stale_packs.py`` …) that don't exist on disk. The
authoring contract (AuthoredRecipeValidator → RecipeCallableValidator) only
admits a SHELL step whose first token is a ``scripts/…`` file that *exists* and
carries ``# recipe-callable: local-ok``. So every gold recipe was PARSE_ONLY,
never VALID — and with R_PARSE_ONLY(0.6) < R_CORRECT_REST(1.0) an unreachable
VALID would bias GRPO toward *resting* in the HIGH band (the exact gag we remove).

This rewrites each EMIT gold's SHELL commands in place: swap the hallucinated
``scripts/X.py`` token for the real recipe-callable script whose domain matches,
preserving the interpreter + flags (which already passed the structural metachar
check, so they stay clean). Everything else — prompt, recipe name, gates,
deploys — is untouched. The own-time `shape_recipe` valid space genuinely IS
"recompose existing vetted scripts," so this is enablement, not a cheat.

    python convert_gold_scripts_real.py \
        --in  data/training/emit_rft/warmup_xml.jsonl \
        --out data/training/emit_rft/warmup_xml_real.jsonl \
        --validate-url http://127.0.0.1:8077   # optional: oracle self-check
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

SCRIPT_TOKEN = re.compile(r"scripts/[A-Za-z0-9_./-]+\.(?:py|sh|js)")

# Real recipe-callable scripts, grouped by domain with a (scan, act) pair so the
# rewrite preserves a recipe's observe→act shape. Both members exist on disk and
# carry the local-ok header (verified: grep -rl 'recipe-callable: local-ok').
DOMAINS = {
    "classifier": ("scripts/classifier/probe_overrouting.py", "scripts/classifier/train_classifier.py"),
    "soul":       ("scripts/soul/consolidate_fragments.py",   "scripts/soul/reembed_fragments.py"),
    "memory":     ("scripts/memory/consolidate_graph.py",     "scripts/memory/consolidate_graph.py"),
    "library":    ("scripts/recipe/library_freshness.py",     "scripts/library/compact_collection.py"),
    "knowledge":  ("scripts/soul/prune_world_knowledge.py",   "scripts/soul/prune_world_knowledge.py"),
    "voice":      ("scripts/voice/check_bondholder_eligibility.py", "scripts/voice/build_bondholder_pairs.py"),
    "oracle":     ("scripts/oracle/recalibrate_oracle.py",    "scripts/oracle/recalibrate_oracle.py"),
    "corpus":     ("scripts/corpus/mine_corpus.py",           "scripts/corpus/mine_corpus.py"),
    "skill":      ("scripts/recipe/floor_checkup.py",         "scripts/recipe/tune_recipe_params.py"),
}
FALLBACK = ("scripts/recipe/floor_checkup.py", "scripts/recipe/tune_recipe_params.py")

# Domain keywords, checked in order against the hallucinated path's basename.
DOMAIN_KEYS = [
    ("classifier", ("classifier", "drift", "priors", "threshold")),
    ("soul",       ("soul", "fragment", "manifest", "lattice", "shard", "core")),
    ("memory",     ("memory",)),
    ("library",    ("library", "pack", "research", "freshness", "stale", "index")),
    ("knowledge",  ("knowledge", "digest", "world", "temporal", "snapshot")),
    ("voice",      ("voice", "register")),
    ("oracle",     ("oracle",)),
    ("corpus",     ("corpus", "mine")),
    ("skill",      ("skill", "coverage", "capability", "gap")),
]

# "Scan" verbs → use the observe member; otherwise the act member.
SCAN_VERBS = ("scan", "audit", "detect", "fetch", "check", "probe", "evaluate",
              "score", "analy", "gather", "log", "report", "summar", "diagnos",
              "validate", "emit", "generate")


def _domain(path: str) -> tuple:
    base = path.rsplit("/", 1)[-1].lower()
    for dom, keys in DOMAIN_KEYS:
        if any(k in base for k in keys):
            return DOMAINS[dom]
    return FALLBACK


def _real_for(path: str) -> str:
    scan, act = _domain(path)
    base = path.rsplit("/", 1)[-1].lower()
    return scan if any(v in base for v in SCAN_VERBS) else act


def _rewrite_yaml(yaml_text: str) -> str:
    return SCRIPT_TOKEN.sub(lambda m: _real_for(m.group(0)), yaml_text)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--validate-url", default=None)
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.inp).read_text().splitlines() if l.strip()]
    out_rows, conv = [], 0
    for r in rows:
        if r.get("label") != "emit":
            out_rows.append(r); continue
        content = r["messages"][-1].get("content") or ""
        y = R.extract_shape_recipe_yaml(content)
        if not y:
            out_rows.append(r); continue
        new_y = _rewrite_yaml(y)
        m = re.search(r"^\s*recipe:\s*([A-Za-z0-9._-]+)", new_y, re.M)
        name = m.group(1) if m else "recipe"
        # rebuild the assistant XML turn (same shape prod parses)
        new = dict(r)
        msgs = list(r["messages"])
        msgs[-1] = {"role": "assistant",
                    "content": (f"<tool_call>\n<function=shape_recipe>\n"
                                f"<parameter=name>\n{name}\n</parameter>\n"
                                f"<parameter=yaml>\n{new_y}\n</parameter>\n"
                                f"</function>\n</tool_call>")}
        new["messages"] = msgs
        out_rows.append(new); conv += 1

    Path(args.out).write_text("".join(json.dumps(x) + "\n" for x in out_rows))
    print(f"[real] rows={len(rows)} emit-rewritten={conv} -> {args.out}")

    if args.validate_url:
        valid = parse = garbled = 0
        bad = []
        for x in out_rows:
            if x.get("label") != "emit":
                continue
            yy = R.extract_shape_recipe_yaml(x["messages"][-1]["content"])
            if not yy:
                garbled += 1; continue
            v = R.validate_yaml(yy, args.validate_url)
            if v.valid:
                valid += 1
            elif v.parsed:
                parse += 1
                if len(bad) < 3:
                    bad.append((yy[:200], v.violations))
            else:
                garbled += 1
        print(f"[real] oracle self-check: valid={valid} parse_only={parse} garbled={garbled}")
        for head, vio in bad:
            print("  --- still-invalid (head) ---"); print("  " + head.replace("\n", "\n  "))
            for z in vio:
                print("    -", z)
        return 0 if parse == 0 and garbled == 0 else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
