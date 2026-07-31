"""sync the shape_recipe tool description in the corpora with prod.

The bank was captured before the shape_recipe description was enriched (it said only
"YAML: name + steps", which is why the model never learned the contract). Rather than
re-run the whole Java capture harness, surgically overwrite the shape_recipe description
+ yaml-param description in every row's `tools` field to the new prod text. The rest of
the prompt/tool menu is unchanged, so this reproduces exactly what the enriched prod
would capture — zero-skew preserved. Keep DESC/YAML_DESC byte-identical to
CompanionActor.RECIPE_AUTHORING_CONTRACT.

    python patch_tool_desc.py data/training/emit_rft/rollout_bank.jsonl \
                              data/training/emit_rft/warmup_xml_real.jsonl
"""

from __future__ import annotations
import json, sys
from pathlib import Path

DESC = (
    "Author and register a new recipe — a small YAML automation the household runs on "
    "your own time to keep yourself current (refresh research packs, retune a classifier, "
    "consolidate memory). The YAML MUST follow this exact contract:\n"
    "recipe: <kebab-name>\n"
    "version: 1.0.0\n"
    "description: <one line of what it does>\n"
    "deploys: false\n"
    "ownership: run\n"
    "steps:\n"
    "  - id: <step-id>\n"
    "    kind: SHELL\n"
    "    command: python3 scripts/<existing-helper>.py\n"
    "Rules: the top-level keys recipe, version, description, deploys, ownership, steps are "
    "ALL required. Each step has an id and a kind (SHELL, GATE, or DECISION). A SHELL "
    "command may ONLY invoke one of the household's existing recipe-callable helper scripts "
    "(a scripts/….py|sh|js path) as its first token — never bare shell, pipes, or redirects. "
    "Available helpers include: scripts/recipe/library_freshness.py, "
    "scripts/library/compact_collection.py, scripts/soul/consolidate_fragments.py, "
    "scripts/soul/reembed_fragments.py, scripts/memory/consolidate_graph.py, "
    "scripts/classifier/train_classifier.py, scripts/classifier/probe_overrouting.py, "
    "scripts/soul/prune_world_knowledge.py, scripts/oracle/recalibrate_oracle.py, "
    "scripts/voice/build_bondholder_pairs.py."
)
YAML_DESC = (
    "the full recipe YAML following the contract: recipe/version/description/deploys/ownership/steps; "
    "each step has id + kind (SHELL|GATE|DECISION); a SHELL command's first token must be an existing scripts/… helper"
)


def patch_tools(tools):
    n = 0
    for t in tools or []:
        fn = t.get("function") or t
        if fn.get("name") != "shape_recipe":
            continue
        fn["description"] = DESC
        props = ((fn.get("parameters") or {}).get("properties") or {})
        if "yaml" in props:
            props["yaml"]["description"] = YAML_DESC
        n += 1
    return n


def main(paths):
    for p in paths:
        rows = [json.loads(l) for l in Path(p).read_text().splitlines() if l.strip()]
        patched = 0
        for r in rows:
            patched += patch_tools(r.get("tools"))
        Path(p).write_text("".join(json.dumps(r) + "\n" for r in rows))
        print(f"[patch] {p}: shape_recipe tool desc updated in {patched} rows ({len(rows)} total)")


if __name__ == "__main__":
    main(sys.argv[1:])
