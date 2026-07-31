#!/usr/bin/env python3
"""
L4 corpus pipeline · step 6 · poll Anthropic batch + parse into substrate corpus.

Reads batch id from data/l2_vector/l4_batch_id.txt, polls until ended, fetches
results, parses each batch reply as a JSON array of substrate-shape messages,
writes data/training/substrate/substrate_welfare_v2_raw.jsonl.

Run periodically until status == 'ended'. Anthropic batches usually finish in
30 min - 2 h.
"""

import json
import os
import sys
import time
from pathlib import Path

from anthropic import Anthropic

REPO = Path(__file__).resolve().parents[3]
BATCH_ID_PATH = REPO / "data" / "l2_vector" / "l4_batch_id.txt"
OUT_PATH = REPO / "data" / "training" / "substrate" / "substrate_welfare_v2_raw.jsonl"


def key():
    k = os.environ.get("ANTHROPIC_API_KEY")
    if k:
        return k
    return (Path.home() / "claudeapi.txt").read_text().strip()


def parse_array(text: str):
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text.rsplit("```", 1)[0]
        if text.startswith("json"):
            text = text[4:].lstrip()
    return json.loads(text)


def main():
    batch_id = BATCH_ID_PATH.read_text().strip()
    print(f"Batch: {batch_id}", flush=True)
    client = Anthropic(api_key=key())

    info = client.messages.batches.retrieve(batch_id)
    print(f"Status: {info.processing_status}", flush=True)
    if info.processing_status != "ended":
        print(f"  request_counts: {info.request_counts}", flush=True)
        print("Not finished — re-run later.", flush=True)
        return 0

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    n_ok = n_err = n_examples = 0
    with OUT_PATH.open("w") as out:
        for result in client.messages.batches.results(batch_id):
            cid = result.custom_id
            if result.result.type != "succeeded":
                n_err += 1
                print(f"  [{cid}] errored: {result.result.type}", flush=True)
                continue
            text = result.result.message.content[0].text
            try:
                examples = parse_array(text)
            except json.JSONDecodeError as e:
                n_err += 1
                print(f"  [{cid}] parse error: {e}", flush=True)
                continue
            for ex in examples:
                if not isinstance(ex, dict) or "messages" not in ex:
                    continue
                ex["_meta"] = {"src": "l4_welfare_v2", "batch_cid": cid}
                out.write(json.dumps(ex, ensure_ascii=False) + "\n")
                n_examples += 1
            n_ok += 1
    print(f"Done: {n_ok} requests OK, {n_err} errored, {n_examples} examples written → {OUT_PATH}", flush=True)


if __name__ == "__main__":
    main()
