#!/usr/bin/env bash
# `wyrd sleepwrite rollback` removed a night's adapter and left the night window after it, so the
# next night started where the removed one ended and its day was never learned (2026-09-29: an
# afternoon and an evening lost). The rollback now puts the window back to where that night began.
# Lifts the helper from bin/wyrd. Runs under set -euo pipefail, as the launcher does.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
eval "$(sed -n '/^_sleepwrite_restore_window() {/,/^}/p' "$HERE/bin/wyrd")"
declare -F _sleepwrite_restore_window >/dev/null || { echo "FAIL could not lift _sleepwrite_restore_window from bin/wyrd"; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
night() {  # a night directory: last written adapter, its window, the state after it
    rm -rf "$T/d"; mkdir -p "$T/d"
    echo '{"adapter": "adapter-20260930-0213.gguf", "window_since": "2026-09-29T17:22:12+00:00"}' > "$T/d/last-result.json"
    echo "{\"last_success_ts\": \"2026-09-30T02:13:12+00:00\", \"last_adapter\": \"$1\"}" > "$T/d/state.json"
    [[ "$2" == "served" ]] && touch "$T/d/current.gguf"
    return 0
}
ts() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["last_success_ts"])' "$T/d/state.json"; }

night adapter-20260930-0213.gguf served
OUT="$(_sleepwrite_restore_window "$T/d")"
check "the window goes back to where the removed night began" '[[ "$(ts)" == "2026-09-29T17:22:12+00:00" ]]'
check "the new start is printed for the message" '[[ "$OUT" == "2026-09-29T17:22:12+00:00" ]]'

night adapter-20260930-0213.gguf none
OUT="$(_sleepwrite_restore_window "$T/d")"
check "nothing served: the window stays (a second rollback moves nothing)" '[[ "$(ts)" == "2026-09-30T02:13:12+00:00" && -z "$OUT" ]]'

night adapter-20260929-1722.gguf served
OUT="$(_sleepwrite_restore_window "$T/d")"
check "the state names another night: the window stays" '[[ "$(ts)" == "2026-09-30T02:13:12+00:00" && -z "$OUT" ]]'

rm -rf "$T/d"; mkdir -p "$T/d"; touch "$T/d/current.gguf"
OUT="$(_sleepwrite_restore_window "$T/d")"
check "no state or result files: nothing happens, no failure" '[[ -z "$OUT" ]]'

[[ $fail -eq 0 ]] && echo "all passed" || { echo "FAILED"; exit 1; }
