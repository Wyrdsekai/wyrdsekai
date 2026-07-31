#!/usr/bin/env python3
# recipe-callable: local-ok
"""Recipe-callable length-stratified greeting-collapse detector.

Catches the V9 4B failure case (memory feedback-no-runtime-bandaid-...):
after over-training, the model collapses to "Here." on every short input.
This probe checks the mean reply length on a 10-prompt short-input bucket
(≤20 tokens) and the long-input bucket (≥40 tokens). A healthy model
keeps both buckets above floor; a collapsed model lands the short bucket
at ~2 tokens.

Output (stdout, single JSON line):
    {"short_bucket_mean_length": <float>,
     "long_bucket_mean_length": <float>,
     "short_bucket_replies": [...],   # first 3 samples for debug
     "samples_per_bucket": N}

Probe prompts are static, bundled at data/voice/probes/length_stratified.jsonl
(one JSON row per line with `prompt` and `bucket` keys).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate", required=True, type=Path)
    ap.add_argument("--probes-path", type=Path,
                    default=Path("data/voice/probes/length_stratified.jsonl"))
    args = ap.parse_args()

    if not args.candidate.exists():
        print(json.dumps({
            "short_bucket_mean_length": 0.0,
            "long_bucket_mean_length": 0.0,
            "samples_per_bucket": 0,
            "error": "candidate_missing",
        }))
        sys.exit(0)

    if not args.probes_path.exists():
        # Probe file missing — emit a structured "skip" with zero short bucket.
        # The gate is `short_bucket_mean_length >= min_short_bucket_mean_length`,
        # so 0 fails closed. Steward overrides via --param during initial bake.
        print(json.dumps({
            "short_bucket_mean_length": 0.0,
            "long_bucket_mean_length": 0.0,
            "samples_per_bucket": 0,
            "error": "probes_missing",
            "probes_path": str(args.probes_path),
        }))
        sys.exit(0)

    # Same v0.1 posture as the other SFT probes: python implementation
    # punts to the Java harness for the live-inference work. Structured
    # "not implemented" marker; gate fails closed; steward overrides
    # during initial bake.
    print(json.dumps({
        "short_bucket_mean_length": 0.0,
        "long_bucket_mean_length": 0.0,
        "samples_per_bucket": 0,
        "short_bucket_replies": [],
        "error": "python_probe_not_implemented_v01",
        "note": "Run the Java length-stratified harness against the "
                "candidate manually for v0.1.",
    }))


if __name__ == "__main__":
    main()
