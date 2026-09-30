#!/usr/bin/env bash
# Table test for the residency planner's inputs: the VRAM a plan may use is the card less what
# others hold, no more than the steward's ceiling; the plan follows. Pure functions lifted from
# bin/wyrd so the arithmetic is tested without a card. Run: bash scripts/tests/brain_card_share_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
# lift the constants and the pure functions
eval "$(sed -n '/^BRAIN_LAYERS=/p;/^BRAIN_VRAM_HEADROOM_MB=/p;/^BRAIN_RAM_FLOOR_MB=/p' "$HERE/bin/wyrd")"
eval "$(sed -n '/^plan_brain_residency() {/,/^}/p' "$HERE/bin/wyrd")"
eval "$(sed -n '/^_brain_vram_available_mb() {/,/^}/p' "$HERE/bin/wyrd")"
_brain_vram_mb() { echo 0; }; _brain_vram_others_mb() { echo 0; }; _brain_vram_cap_mb() { echo 0; }
fail=0
check() {   # <label> <got> <want>
    if [[ "$2" == "$3" ]]; then echo "ok   $1 → $2"; else echo "FAIL $1 → $2 (want $3)"; fail=1; fi
}
# second-node: 16 GB card, 30 GB RAM
check "available, card alone"            "$(_brain_vram_available_mb 16311 0 0)"     16311
check "available, others hold 6 GB"      "$(_brain_vram_available_mb 16311 6144 0)"  10167
check "available, ceiling 8 GB"          "$(_brain_vram_available_mb 16311 0 8192)"  8192
check "available, ceiling under others"  "$(_brain_vram_available_mb 16311 6144 8192)" 8192
check "available, others take it all"    "$(_brain_vram_available_mb 16311 17000 0)" 0
check "available, junk inputs"           "$(_brain_vram_available_mb x y z)"         0
check "plan, card alone"                 "$(plan_brain_residency 16311 30820)"       19
check "plan, others hold 6 GB"           "$(plan_brain_residency 10167 30820)"       31
check "plan, ceiling 8 GB"               "$(plan_brain_residency 8192 30820)"        36
check "plan, ceiling 4 GB is under the spine+headroom floor" "$(plan_brain_residency 4096 30820)" not-viable
check "plan, ceiling 6 GB → everything in RAM" "$(plan_brain_residency 6144 30820)"     all
check "plan, nothing free"               "$(plan_brain_residency 0 30820)"           not-viable
check "plan, 24 GB card"                 "$(plan_brain_residency 24576 65536)"       1
exit $fail
