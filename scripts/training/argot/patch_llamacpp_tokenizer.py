#!/usr/bin/env python3
"""
Register the wyrdsekai v10 4B tokenizer's BPE pre-tokenizer hash with a llama.cpp
checkout so `convert_hf_to_gguf.py` / `convert_lora_to_gguf.py` accept it.

WHY THIS EXISTS
---------------
The v10 4B (`wyrdsekai-3.5-4b-v10`) ships a Qwen3.5-*4B*-family tokenizer whose
`get_vocab_base_pre` chkhsh differs from the Qwen3.5-*9B* hash llama.cpp already
registers as `qwen35`. Without registering it, conversion dies with:

    NotImplementedError: BPE pre-tokenizer was not recognized

It is the SAME BPE pre-tokenizer regex (just a different size's merges), so the
correct fix is to map our hash to the existing `qwen35` pre-tokenizer. Upstream
llama.cpp won't carry a hash for our private model, so this applier reproduces the
one-line registration on any fresh/updated llama.cpp checkout. It is the in-repo
source of truth for that edit (b).

Idempotent + drift-proof: it anchors on the already-present 9B `qwen35` hash line
rather than a line number, and is a no-op if our hash is already registered.

USAGE
-----
    python patch_llamacpp_tokenizer.py --llama-cpp /path/to/llama.cpp        # apply
    python patch_llamacpp_tokenizer.py --llama-cpp /path/to/llama.cpp --check # verify only (exit 1 if missing)

Re-run after any `git pull`/reset of the llama.cpp checkout.
"""
import argparse
import os
import sys

# The v10 4B tokenizer's chkhsh, as computed by llama.cpp's get_vocab_base_pre
# (printed in its own "** chkhsh:" warning before it raises NotImplementedError).
V10_4B_CHKHSH = "1444df51289cfa8063b96f0e62b1125440111bc79a52003ea14b6eac7016fd5f"

# Anchor: the Qwen3.5-9B hash llama.cpp already maps to "qwen35". We insert our
# block immediately after this one. If upstream ever renames/removes it, --check
# will fail loudly rather than silently mis-patching.
ANCHOR_9B_CHKHSH = "d30d75d9059f1aa2c19359de71047b3ae408c70875e8a3ccf8c5fba56c9d8af4"

INSERT_BLOCK = (
    f'        if chkhsh == "{V10_4B_CHKHSH}":\n'
    f'            # ref: wyrdsekai-3.5-4b-v10 (Qwen3.5-4B-family tokenizer variant, same BPE pre-tokenizer)\n'
    f'            res = "qwen35"\n'
)


def resolve_target(llama_cpp_dir: str) -> str:
    p = os.path.join(llama_cpp_dir, "convert_hf_to_gguf.py")
    if not os.path.isfile(p):
        sys.exit(f"convert_hf_to_gguf.py not found in {llama_cpp_dir!r}")
    return p


def is_registered(text: str) -> bool:
    return V10_4B_CHKHSH in text


def apply(path: str) -> None:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    if is_registered(text):
        print(f"already registered — no change ({path})")
        return

    anchor_line = f'        if chkhsh == "{ANCHOR_9B_CHKHSH}":'
    idx = text.find(anchor_line)
    if idx == -1:
        sys.exit(
            "anchor (Qwen3.5-9B qwen35 hash) not found — llama.cpp layout changed; "
            "patch by hand and update ANCHOR_9B_CHKHSH."
        )

    # Insert our block AFTER the anchor's 3-line if/comment/res stanza.
    # Find the end of the anchor stanza: the next line that dedents to 8-space `if chkhsh`.
    lines = text.splitlines(keepends=True)
    out, inserted = [], False
    i = 0
    while i < len(lines):
        out.append(lines[i])
        if not inserted and lines[i].lstrip().startswith(f'if chkhsh == "{ANCHOR_9B_CHKHSH}"'):
            # copy the anchor's body (comment + res = "qwen35"), then inject ours
            j = i + 1
            while j < len(lines) and not lines[j].lstrip().startswith("if chkhsh =="):
                out.append(lines[j])
                j += 1
            out.append(INSERT_BLOCK)
            inserted = True
            i = j
            continue
        i += 1

    if not inserted:
        sys.exit("failed to locate insertion point after anchor")

    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print(f"registered v10 4B tokenizer hash → qwen35 ({path})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--llama-cpp", required=True, help="path to a llama.cpp checkout")
    ap.add_argument("--check", action="store_true",
                    help="verify the hash is registered; exit 1 if not (no write)")
    args = ap.parse_args()

    target = resolve_target(args.llama_cpp)
    with open(target, "r", encoding="utf-8") as f:
        registered = is_registered(f.read())

    if args.check:
        if registered:
            print("OK: v10 4B tokenizer hash is registered")
            sys.exit(0)
        print("MISSING: run without --check to register", file=sys.stderr)
        sys.exit(1)

    apply(target)


if __name__ == "__main__":
    main()
