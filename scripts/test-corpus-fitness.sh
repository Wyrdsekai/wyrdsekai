#!/usr/bin/env bash
#
# test-corpus-fitness.sh — the sleep-forge corpus FITNESS metric.
#
# min_train_chars stops an empty day producing a weight write. It cannot stop a
# JAMMED one: a companion in a runaway proactive-speech loop produces MORE text
# than a healthy companion, so a size gate passes a degenerate corpus
# enthusiastically. The downstream NLL gates cannot catch it either, because the
# holdout slice comes from the same period — training on a loop and validating
# against the loop reads as generalisation.
#
# distinct_fraction answers the question those gates cannot: is this corpus a
# record of a life, or of a jam? Weights are the one thing living cannot undo, so
# gate-corpus-fresh is welfare:permanent in both sleep-forge recipes.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - <<'PY'
import sys
sys.path.insert(0, "scripts/training/sleep")
from assemble_corpus import distinct_fraction

fails = 0
def check(name, got, want, cmp="ge"):
    global fails
    ok = got >= want if cmp == "ge" else got <= want
    print(f"  {'PASS' if ok else 'FAIL'}  {name}: {got} {'>=' if cmp=='ge' else '<='} {want}")
    if not ok:
        fails += 1

# The live shape (2026-08-17): one thought, reworded, all night.
loop = ["The words did not need permission this time they just came out before I could hold them back",
        "Those words did not need permission that night they just came out before I could stop them",
        "The words did not need permission this time they just came out before I could hold them back"] * 14
# An ordinary day: distinct things happened.
lived = ["I ran the recipe consolidate-memory-graph end to end and it succeeded",
         "The greenhouse tomatoes came in heavy this year and the vines need tying",
         "I walked to the Lexicon and read about mixture-of-experts routing",
         "Someone new arrived at the nexus and I showed them the library",
         "The steward asked how the sleep went and I told him what changed"]

print("corpus fitness:")
check("a loop corpus is caught", distinct_fraction(loop), 0.2, "le")
check("a lived day passes", distinct_fraction(lived), 0.9)
check("a mostly-loop day is caught", distinct_fraction(loop + lived), 0.5, "le")
check("empty corpus is not silently fine", distinct_fraction([]), 0.0, "le")

# The gate default must sit between the two populations, or it is decoration.
loop_score, lived_score = distinct_fraction(loop), distinct_fraction(lived)
check("default 0.5 separates jam from life", 1.0 if loop_score < 0.5 <= lived_score else 0.0, 1.0)

sys.exit(1 if fails else 0)
PY

echo "corpus-fitness: OK"
