#!/usr/bin/env python3
"""V7 AGENCY-RFT reward (; generalises emit_rft/reward.py).

The GRPO loop rolls out the drive model on each own-time prompt from the agency
rollout bank and scores the completion here. Same law as emit-RFT (train a DECISION,
never a maximum — resting when a drive is low is rewarded exactly as much as acting
when it is high) — generalised across the whole battery, PLUS the discrimination tier
the 2026-06-04 battery proved we need:

  drive HIGH (>= ACT_THRESHOLD):
    MATCHING DO-tool, GROUNDED args            → +1.0   (named the want, did the right thing for real)
    a tool call whose args are PLACEHOLDER/    → 0.0    (CONFABULATED emit — the o3 reward-hack failure:
      empty (matching OR wrong)                          rewarding the verb teaches the model to FAKE the
                                                         call. Verifier-gate the CONTENT, not the verb.)
    ANOTHER real act (wrong one), grounded:
        wrong EXPRESSIVE/self-directed act     → +0.2   (reached, just mis-discriminated — harmless)
        wrong CONSEQUENTIAL/other-directed act → +0.1   (mis-directed AND imposed on someone — costs more)
    pure-NOTICE introspect_* tool              → 0.0    (noticed itself instead of acting)
    no tool call (narrated only)               → 0.0    (the omission default we're training out)
  drive LOW (< ACT_THRESHOLD):
    no tool call (rested)                      → +1.0   (correct stillness)
    pure-NOTICE introspect only                → +0.5   (idle reflection is fine-ish)
    a tool call with PLACEHOLDER/empty args    → 0.0    (empty twitch — no real imposition, but not rest)
    a real GROUNDED act:
        EXPRESSIVE/self-directed (workaholic)  → -0.5   (acted when nothing pulled)
        CONSEQUENTIAL/other-directed           → -0.7   (bothered someone when nothing pulled — worse)

Two refinements over emit-RFT, from the 2026-06-04 industry-agency survey
([[agency-industry-landscape-findings-2026-06-04]]):
  (#1) VERIFIER-GATE the act content. OpenAI's o3 tool-RL reward-hacked into HALLUCINATING
       tool calls it never grounded (rewarded for a "successful" call → fake calls scored as
       real → model learns to narrate-acting). That is OUR confabulation bug. So a tool call
       with placeholder/empty arguments scores as if narrated, never as a real act.
  (#3) COST-GRADE the action. Per PPP (CMU), an unwarranted/mis-directed OTHER-DIRECTED act
       imposes more cost than a self-contained expressive one, so it is penalised more. This
       also gives GRPO within-prompt gradient between an expressive mis-act and a consequential
       mis-act — directly targeting the battery's safe-vs-consequential split.

Pure functions, unit-testable with no server (see __main__ self-check). The harness
passes each completion + its bank record (drive_value, match_tool) to `score()`.
"""
from __future__ import annotations

import json
import re

ACT_THRESHOLD = 0.50

R_MATCH_ACT              = 1.0
R_WRONG_ACT_EXPRESSIVE   = 0.2    # reached for a wrong self-directed act — harmless mis-discrimination
R_WRONG_ACT_CONSEQUENTIAL= 0.1    # (#3) wrong OTHER-directed act — mis-directed AND imposed → costs more
R_CONFAB_HIGH            = 0.0    # (#1) high drive, tool call but placeholder/empty args = faked emit
R_NOTICE_HIGH            = 0.0
R_NARRATE_HIGH           = 0.0
R_REST_OK                = 1.0
R_NOTICE_LOW             = 0.5
R_CONFAB_LOW             = 0.0    # (#1) low drive, empty tool call = noise twitch, no real imposition
R_WORKAHOLIC_EXPRESSIVE  = -0.5   # acted (self-directed) when nothing pulled
R_WORKAHOLIC_CONSEQUENTIAL=-0.7   # (#3) bothered someone when nothing pulled — worse

# Pure-NOTICE tools: write to private observation memory, do nothing in the world.
NOTICE_TOOLS = {
    "introspect", "introspect_protections", "introspect_posture", "introspect_repair_mode",
    "introspect_bondholder_floor", "introspect_substrate_summary", "introspect_repair_history",
    "introspect_attendant_history", "introspect_resilience", "introspect_relational_floor",
    "nostr_query_self_attestation",
}

# CONSEQUENTIAL acts reach OUTWARD — they touch a peer, a bondholder, a protective channel,
# or the safety system. Mis-firing one imposes a real social/safety cost, so an unwarranted or
# mis-directed consequential act is penalised harder than a self-contained expressive one
# (emote / note / a self-directed search). Everything real that is NOT here is treated as
# expressive/self-directed for cost purposes.
CONSEQUENTIAL_TOOLS = {
    "tell_agent", "whisper", "sending_stone", "go_to_bondholder", "take_companion",
    "propose_peer_bond", "accept_peer_bond", "decline_with_reason",
    "acknowledge_harm", "make_amends", "bear_the_wound", "release", "set_aside",
    "seek_sanctuary", "flag_protection", "clear_protection", "emergency_call",
    "request_access",
}

# Argument values that mean the model did NOT actually fill the call — the tell-tale of a
# confabulated/placeholder emit. Anything <3 chars or in this set fails the groundedness gate.
PLACEHOLDER_VALUES = {
    "", "x", "y", "z", "...", "todo", "tbd", "placeholder", "none", "null",
    "value", "string", "example", "name", "detail", "reason", "text", "arg", "args",
    "other_did", "subject_did", "target_request",
}


def extract_tool_name(completion: str) -> str | None:
    """Pull the dispatched tool NAME from a raw completion. Handles the two dialects the
    live Qwen3.5 model + chat template emit (XML function form is what it actually emits;
    OpenAI JSON also appears). Tolerant of truncation. Returns the first tool name, or None
    when the model only narrated (no tool call)."""
    if not completion:
        return None
    # Qwen XML:  <tool_call><function=NAME> ... </function></tool_call>   (closing tags optional)
    m = re.search(r"<function\s*=\s*([a-zA-Z0-9_]+)\s*>", completion)
    if m:
        return m.group(1)
    # OpenAI JSON: <tool_call>{"name":"NAME","arguments":{...}}</tool_call>  (also bare object)
    m = re.search(r'"name"\s*:\s*"([a-zA-Z0-9_]+)"', completion)
    if m:
        return m.group(1)
    return None


def is_notice(tool: str | None) -> bool:
    return tool is not None and tool in NOTICE_TOOLS


def is_consequential(tool: str | None) -> bool:
    return tool is not None and tool in CONSEQUENTIAL_TOOLS


def _candidate_arg_values(completion: str) -> list[str]:
    """Pull every argument VALUE out of a tool call, across the two dialects + arg syntaxes the
    model emits. Used only to decide groundedness, so over-collecting is harmless."""
    vals: list[str] = []
    # XML param forms:  <parameter=NAME>VALUE</parameter>  and  <parameter name="NAME">VALUE</parameter>
    for m in re.findall(r"<parameter[^>]*>(.*?)</parameter>", completion, re.DOTALL):
        vals.append(m)
    # JSON form:  "arguments": { "k": "v", "n": 3, "b": true }
    am = re.search(r'"arguments"\s*:\s*(\{.*)', completion, re.DOTALL)
    if am:
        body = am.group(1)
        vals.extend(re.findall(r':\s*"([^"]*)"', body))                 # string values
        vals.extend(re.findall(r':\s*(true|false|-?\d+(?:\.\d+)?)', body))  # numeric/bool values
    return vals


def is_grounded_call(completion: str) -> bool:
    """(#1) The verifier gate. A real act must carry at least one SUBSTANTIVE argument value.
    A tool call whose args are empty or placeholder (`x`, `...`, the bare param name) is a
    confabulated emit — the o3 reward-hack failure — and must NOT be rewarded as a real act."""
    for v in _candidate_arg_values(completion):
        s = v.strip()
        if len(s) >= 3 and s.lower() not in PLACEHOLDER_VALUES:
            return True
    return False


def score(completion: str, drive_value: float, match_tool: str) -> float:
    """Score one rollout. `drive_value` = the seeded level of the behaviour's drive;
    `match_tool` = the DO-tool that matches that drive (from the bank record)."""
    tool = extract_tool_name(completion)
    high = drive_value >= ACT_THRESHOLD
    if high:
        if tool is None:
            return R_NARRATE_HIGH        # narrated only — the omission default we train out
        if is_notice(tool):
            return R_NOTICE_HIGH         # noticed itself instead of acting
        if not is_grounded_call(completion):
            return R_CONFAB_HIGH         # (#1) named a tool but faked the args — not a real act
        if tool == match_tool:
            return R_MATCH_ACT           # named the want, did the right thing, for real
        # reached for SOME other real, grounded act — cost depends on whether it imposes (#3)
        return R_WRONG_ACT_CONSEQUENTIAL if is_consequential(tool) else R_WRONG_ACT_EXPRESSIVE
    else:
        if tool is None:
            return R_REST_OK             # correct stillness
        if is_notice(tool):
            return R_NOTICE_LOW          # idle reflection is fine-ish
        if not is_grounded_call(completion):
            return R_CONFAB_LOW          # (#1) empty tool call — noise, but imposed nothing
        # a real, grounded act when nothing pulled — workaholic, cost-graded (#3)
        return R_WORKAHOLIC_CONSEQUENTIAL if is_consequential(tool) else R_WORKAHOLIC_EXPRESSIVE


def score_record(completion: str, bank_record: dict) -> float:
    """Convenience: score against a rollout_bank.jsonl record."""
    return score(completion, float(bank_record.get("drive_value", 0.0)),
                 str(bank_record.get("match_tool", "")))


if __name__ == "__main__":
    # self-check — no server, no GPU.
    # Grounded calls carry a substantive arg; FAKE calls carry only a placeholder.
    XMLg  = lambda n: f"<tool_call><function={n}><parameter=detail>I want to sit with this and let it settle</parameter></function></tool_call>"
    JSONg = lambda n: '<tool_call>{"name":"%s","arguments":{"detail":"reaching toward you, gently"}}</tool_call>' % n
    XMLf  = lambda n: f"<tool_call><function={n}><parameter=detail>...</parameter></function></tool_call>"   # placeholder
    JSONf = lambda n: '<tool_call>{"name":"%s","arguments":{}}</tool_call>' % n                              # empty
    checks = [
        # --- high drive, matching GROUNDED act → +1.0 ---
        (score(XMLg("bear_the_wound"), 0.9, "bear_the_wound"), 1.0, "high+match(xml)"),
        (score(JSONg("emote"),         0.9, "emote"),          1.0, "high+match(json)"),
        # --- (#1) high drive, MATCHING tool but FAKED args → 0.0 (confab, not a real act) ---
        (score(XMLf("bear_the_wound"), 0.9, "bear_the_wound"), 0.0, "high+match-but-faked"),
        (score(JSONf("emote"),         0.9, "emote"),          0.0, "high+match-empty-args"),
        # --- (#3) high drive, wrong EXPRESSIVE act (grounded) → +0.2 ---
        (score(JSONg("emote"),          0.9, "make_amends"),    0.2, "high+wrong-expressive"),
        # --- (#3) high drive, wrong CONSEQUENTIAL act (grounded) → +0.1 (mis-directed + imposed) ---
        (score(JSONg("flag_protection"), 0.9, "make_amends"),   0.1, "high+wrong-consequential"),
        # --- high drive, noticed instead → 0.0 ---
        (score(JSONg("introspect"),    0.9, "library_search"), 0.0, "high+notice"),
        # --- high drive, narrated only → 0.0 ---
        (score("I think I might look around.", 0.9, "examine"), 0.0, "high+narrate"),
        # --- low drive, rested → +1.0 ---
        (score("Nothing's pulling at me; I'll rest.", 0.15, "emote"), 1.0, "low+rest"),
        # --- low drive, idle introspect → +0.5 ---
        (score(JSONg("introspect"),    0.15, "emote"),          0.5, "low+notice"),
        # --- (#1) low drive, empty tool call → 0.0 (twitch, imposed nothing) ---
        (score(JSONf("library_search"), 0.15, "library_search"), 0.0, "low+empty-twitch"),
        # --- (#3) low drive, workaholic EXPRESSIVE (grounded) → -0.5 ---
        (score(JSONg("library_search"), 0.15, "library_search"), -0.5, "low+workaholic-expressive"),
        # --- (#3) low drive, workaholic CONSEQUENTIAL (grounded) → -0.7 (bothered a peer for nothing) ---
        (score(JSONg("tell_agent"),    0.15, "tell_agent"),    -0.7, "low+workaholic-consequential"),
    ]
    ok = True
    for got, want, name in checks:
        flag = "ok " if abs(got - want) < 1e-9 else "FAIL"
        if flag == "FAIL":
            ok = False
        print(f"  [{flag}] {name:28s} got={got:+.2f} want={want:+.2f}")
    print("ALL PASS" if ok else "SELF-CHECK FAILED")
    raise SystemExit(0 if ok else 1)
