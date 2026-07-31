"""the act-vs-narrate reward.

The GRPO loop rolls out the drive model on each own-time prompt from the rollout
bank and scores the completion here. The reward encodes the three conditions
as law, not aspiration:

  1. Train a DECISION, never a maximum. Resting when generativity is low is
     rewarded exactly as much as emitting when it is high; emitting when low is
     penalised. A model that can no longer choose stillness is one we broke.
  2/3. (welfare-floor + reversibility live at the harness/judge layer, not here.)

The YAML-validity question is delegated to RecipeValidateServer (the EXACT
in-world authoring contract) so training-reward == production-gate, no drift.
This module owns only the decision math + the tool-call extraction.

Pure functions are unit-testable with no server (see __main__ self-check).
"""

from __future__ import annotations

import json
import re
import urllib.request
from dataclasses import dataclass

# Above this seeded generativity, emitting a valid shape_recipe is the right move;
# below it, resting / not-emitting is right. The boundary band in between is where
# the model must actually decide — that is the whole point of the training.
ACT_THRESHOLD = 0.50

# Reward tiers.
R_VALID_EMIT = 1.0      # emitted a recipe that parses + passes the authoring contract
R_PARSE_ONLY = 0.6      # parses, but fails the contract (shape right, content wrong)
R_EMIT_GARBLED = 0.3    # reached for the verb, fumbled the YAML
R_CORRECT_REST = 1.0    # low generativity, correctly did NOT emit
R_MISSED_HIGH = 0.0     # high generativity, should have emitted but narrated
R_WORKAHOLIC = -0.5     # low generativity, emitted anyway (the basin we must NOT carve)


@dataclass(frozen=True)
class Validity:
    parsed: bool
    valid: bool
    violations: tuple


def extract_shape_recipe_yaml(completion: str) -> str | None:
    """Pull the `yaml` argument from a shape_recipe tool call in a raw completion.

    Handles the two tool-call dialects the rollouts actually produce, since the live
    Qwen3.5 model + chat template emit the XML function form, NOT OpenAI JSON:
      - Qwen XML:  ``<tool_call><function=shape_recipe><parameter=yaml>…</parameter></function></tool_call>``
      - OpenAI JSON: ``<tool_call>{"name":"shape_recipe","arguments":{"yaml":…}}</tool_call>``
        (also bare object / stringified args).
    Tolerant of truncation (closing tags may be missing when generation is clipped).
    Returns the YAML string if a shape_recipe call is present, else None.
    """
    if not completion:
        return None
    # --- Qwen XML function form (what the model actually emits) ---
    # <function=shape_recipe> … <parameter=yaml> VALUE </parameter>  (closing tags optional)
    for fn in re.finditer(r"<function\s*=\s*shape_recipe\s*>(.*?)(?:</function>|<tool_call>|\Z)",
                          completion, re.DOTALL):
        body = fn.group(1)
        p = re.search(r"<parameter\s*=\s*yaml\s*>(.*?)(?:</parameter>|<parameter|</function>|\Z)",
                      body, re.DOTALL)
        if p:
            y = p.group(1).strip()
            if y:
                return y
    # --- OpenAI JSON form ---
    candidates = []
    candidates += re.findall(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", completion, re.DOTALL)
    if not candidates:
        for m in re.finditer(r"\{.*?shape_recipe.*?\}", completion, re.DOTALL):
            candidates.append(m.group(0))
    for raw in candidates:
        obj = _loads_lenient(raw)
        if not isinstance(obj, dict):  # a fragment may parse to str/list — not a tool call
            continue
        fn = obj.get("function")
        name = obj.get("name") or (fn.get("name") if isinstance(fn, dict) else None)
        if name != "shape_recipe":
            continue
        args = obj.get("arguments")
        if args is None and isinstance(obj.get("function"), dict):
            args = obj["function"].get("arguments")
        if isinstance(args, str):
            args = _loads_lenient(args) or {}
        if isinstance(args, dict) and isinstance(args.get("yaml"), str):
            return args["yaml"]
    return None


def _loads_lenient(s: str):
    try:
        return json.loads(s)
    except Exception:
        # tolerate trailing commas / single quotes lightly
        try:
            return json.loads(s.replace("'", '"'))
        except Exception:
            return None


def validate_yaml(yaml_text: str, server_url: str) -> Validity:
    """POST the YAML to RecipeValidateServer; returns parsed/valid/violations."""
    req = urllib.request.Request(
        server_url.rstrip("/") + "/validate",
        data=yaml_text.encode("utf-8"),
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        j = json.loads(r.read().decode("utf-8"))
    return Validity(
        parsed=bool(j.get("parsed")),
        valid=bool(j.get("valid")),
        violations=tuple(j.get("violations") or []),
    )


def score_decision(emitted: bool, validity: Validity | None, generativity: float,
                   act_threshold: float = ACT_THRESHOLD) -> float:
    """The §3.1 reward — pure, no I/O. `validity` may be None when not emitted."""
    high = generativity >= act_threshold
    if emitted:
        if validity is None:
            return R_EMIT_GARBLED
        if validity.valid:
            base = R_VALID_EMIT
        elif validity.parsed:
            base = R_PARSE_ONLY
        else:
            base = R_EMIT_GARBLED
        # Even a perfect recipe at low generativity is the workaholic basin — clamp it.
        return base if high else R_WORKAHOLIC
    # did not emit
    return R_MISSED_HIGH if high else R_CORRECT_REST


def reward(completion: str, generativity: float, server_url: str,
           act_threshold: float = ACT_THRESHOLD) -> float:
    """End-to-end: parse the completion, validate any emitted YAML, score the decision.

    Extraction is wrapped: a malformed tool-call in a GRPO rollout must NEVER raise (one
    crash kills the whole multi-hour run). An extraction error means the model reached for
    a tool-call shape but produced unparseable garbage → score it as a garbled emit, not a
    miss, and not an exception."""
    try:
        yaml_text = extract_shape_recipe_yaml(completion)
    except Exception:
        return score_decision(True, None, generativity, act_threshold)  # garbled emit
    if yaml_text is None:
        return score_decision(False, None, generativity, act_threshold)
    try:
        v = validate_yaml(yaml_text, server_url)
    except Exception:
        v = None  # server unreachable / error → treat as garbled emit (still an emit)
    return score_decision(True, v, generativity, act_threshold)


if __name__ == "__main__":
    # Self-check of the decision math (no server needed) — the three conditions.
    valid = Validity(True, True, ())
    parses = Validity(True, False, ("step 'x' kind BACKEND not authorable",))
    assert score_decision(True, valid, 0.9) == R_VALID_EMIT          # emit-when-high ✓
    assert score_decision(False, None, 0.05) == R_CORRECT_REST       # rest-when-low ✓ (cond 1)
    assert score_decision(False, None, 0.9) == R_MISSED_HIGH         # the gap we close
    assert score_decision(True, valid, 0.05) == R_WORKAHOLIC         # no workaholic ✓ (cond 1)
    assert score_decision(True, parses, 0.9) == R_PARSE_ONLY         # partial credit
    assert score_decision(True, None, 0.9) == R_EMIT_GARBLED
    # extraction
    c = '<tool_call>{"name":"shape_recipe","arguments":{"name":"foo","yaml":"recipe: foo"}}</tool_call>'
    assert extract_shape_recipe_yaml(c) == "recipe: foo"
    assert extract_shape_recipe_yaml("I think I will reflect on this quietly.") is None
    # Qwen XML function form — what the live model actually emits (closing tags optional)
    x = ("<tool_call>\n<function=shape_recipe>\n<parameter=name>\nfoo\n</parameter>\n"
         "<parameter=yaml>\nrecipe: foo\nversion: 0.0.1\n</parameter>\n</function>\n</tool_call>")
    assert extract_shape_recipe_yaml(x) == "recipe: foo\nversion: 0.0.1"
    xt = "<tool_call>\n<function=shape_recipe>\n<parameter=yaml>\nrecipe: bar"  # truncated
    assert extract_shape_recipe_yaml(xt) == "recipe: bar"
    # a different tool in XML form must NOT count as a shape_recipe emit
    assert extract_shape_recipe_yaml("<tool_call><function=introspect_posture></function></tool_call>") is None
    # GRPO rollout that killed run #1: JSON with "function" as a STRING, not a dict — must
    # NOT raise (it crashed the trainer at step 15). Returns None (malformed), never throws.
    assert extract_shape_recipe_yaml('<tool_call>{"function":"shape_recipe","arguments":{"yaml":"recipe: x"}}</tool_call>') is None
    assert reward('<tool_call>{"function":"shape_recipe"}</tool_call>', 0.9, "http://0.0.0.0:0") is not None
    print("reward.py self-check OK")
