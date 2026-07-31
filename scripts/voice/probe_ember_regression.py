#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable Ember 45-task regression probe for SFT candidates.

Companion to run_substrate_sft.py. Runs the bundled Ember progressive
task set against a candidate GGUF on a side llama-server port (default
8299) so it doesn't compete with the production :8201. Each task has a
pre-recorded baseline pass on the current production model; this probe
measures how many of those still pass on the candidate.

Output (stdout, single JSON line):
    {"ember_passed": N, "ember_total": 45,
     "ember_failures": ["task_3_library", ...]}

On any error (server start failure, candidate missing, etc.) emits a
zero-pass result so the recipe's gate-ember stops the deploy.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate", required=True, type=Path,
                    help="GGUF file to evaluate.")
    ap.add_argument("--ember-port", type=int, default=8299,
                    help="Side port to launch the candidate llama-server on.")
    args = ap.parse_args()

    if not args.candidate.exists():
        print(json.dumps({
            "ember_passed": 0, "ember_total": 45,
            "ember_failures": ["candidate_missing"],
        }))
        sys.exit(0)

    # The actual Ember probe harness lives in e2e-test/.../EmberProgressiveTasksE2ETest.
    # For OSS-release v0.1, a python-side surrogate that exercises the
    # bundled candidate via llama-cpp-python is a heavy ask — punt to the
    # Java harness and emit a structured "not yet implemented in python"
    # marker. The recipe's gate-ember will fail closed, which is the safe
    # default: a fresh SFT candidate doesn't auto-deploy until the
    # in-repo Ember Java suite signs off.
    #
    # Steward-force-fire path: set ember_passed=45 via --param to bypass
    # this gate during initial bake (matches the bake-recipe loosening
    # pattern from RecipeBakeMain).
    print(json.dumps({
        "ember_passed": 0,
        "ember_total": 45,
        "ember_failures": ["python_probe_not_implemented_v01"],
        "note": "Run e2e-test EmberProgressiveTasksE2ETest against the "
                "candidate manually for v0.1; agent-autonomous SFT path "
                "lands post-OSS once python probe is wired.",
    }))


if __name__ == "__main__":
    main()
