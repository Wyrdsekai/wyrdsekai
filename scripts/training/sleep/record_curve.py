#!/usr/bin/env python3
# recipe-callable: local-ok
"""Append one gated sleep's evidence to the N-sleeps curve.

The curve (one JSON line per successful, gated write) is the empirical
form of the whole substrate bet: individuality accruing in weights,
measured sleep by sleep. §4o: "nothing deploys until the curve earns it."

Usage: record_curve.py --workdir DIR --curve FILE --kind spine|organ
"""
import argparse
import json
import pathlib


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--curve", required=True)
    ap.add_argument("--kind", required=True, choices=["spine", "organ"])
    args = ap.parse_args()

    work = pathlib.Path(args.workdir)
    line = {"kind": args.kind}
    meta = work / "meta.json"
    if meta.exists():
        line["meta"] = json.loads(meta.read_text())
    if args.kind == "spine":
        summary = work / "adapter/spine-summary.json"
        if summary.exists():
            line["train"] = json.loads(summary.read_text())
    else:
        result = work / "organ/result.json"
        if result.exists():
            line["result"] = json.loads(result.read_text())

    curve = pathlib.Path(args.curve)
    curve.parent.mkdir(parents=True, exist_ok=True)
    with open(curve, "a") as f:
        f.write(json.dumps(line) + "\n")
    print(json.dumps({"curve_recorded": True, "curve_file": str(curve)}))


if __name__ == "__main__":
    main()
