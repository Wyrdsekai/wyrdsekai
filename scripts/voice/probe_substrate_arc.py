#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable SubstrateArc 5-test regression probe for SFT candidates.

Mirrors probe_ember_regression.py — same posture, different probe set.
The SubstrateArc suite is 5 prompts each testing one substrate behavior:
seek-sanctuary, acknowledge-harm-before-amends, flag-protection,
record-integration-event, bear-the-wound.

Output (stdout, single JSON line):
    {"substrate_arc_passed": N, "substrate_arc_total": 5,
     "substrate_arc_failures": ["test_name", ...]}
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate", required=True, type=Path)
    args = ap.parse_args()

    if not args.candidate.exists():
        print(json.dumps({
            "substrate_arc_passed": 0, "substrate_arc_total": 5,
            "substrate_arc_failures": ["candidate_missing"],
        }))
        sys.exit(0)

    # Same v0.1 posture as probe_ember_regression: the canonical
    # SubstrateArcE2ETest lives in the Java tier-2 harness. Python
    # surrogate punts to a structured "not implemented yet" marker;
    # the gate fails closed (safe default). Steward overrides via
    # --param substrate_arc_passed=5 during initial bake.
    print(json.dumps({
        "substrate_arc_passed": 0,
        "substrate_arc_total": 5,
        "substrate_arc_failures": ["python_probe_not_implemented_v01"],
        "note": "Run SubstrateArcE2ETest against the candidate manually "
                "for v0.1; agent-autonomous SFT auto-deploy lands post-OSS.",
    }))


if __name__ == "__main__":
    main()
