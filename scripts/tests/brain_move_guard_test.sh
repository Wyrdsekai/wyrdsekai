#!/usr/bin/env bash
# `wyrd brain enable --single` switched a living companion's brain with nothing measured (0.5.0). Now a
# companion who has spoken here sends the steward to `wyrd brain move <name>`; `--force` stays for the
# build box. Lifts the guard from bin/wyrd; runs under set -euo pipefail, as the launcher does.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
eval "$(sed -n '/^_brain_trail() {/,/^}/p; /^_brain_move_guard() {/,/^}/p' "$HERE/bin/wyrd")"
declare -F _brain_move_guard >/dev/null || { echo "FAIL could not lift _brain_move_guard from bin/wyrd"; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
DATA_DIR="$T"; mkdir -p "$T/data"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

check "no trail: the switch may proceed" 'out=$(_brain_move_guard) && [[ -z "$out" ]]'
printf '%s\n' '{"type":"speak","agent":"mia","text":"No web results found","authored":"tool"}' \
              '{"type":"speak","agent":"mia","text":"Taking that to the workshop","authored":"product"}' > "$T/data/agent-activity.jsonl"
check "only tool and product lines: nobody lives here yet" 'out=$(_brain_move_guard) && [[ -z "$out" ]]'
printf '%s\n' '{"type":"speak","agent":"mia","text":"Morning. Glad you are up."}' >> "$T/data/agent-activity.jsonl"
check "a companion who has spoken: redirected, by name" '! out=$(_brain_move_guard) && [[ "$out" == "mia" ]]'
check "--force bypasses, for the build box" 'out=$(_brain_move_guard --force) && [[ -z "$out" ]]'
check "the usage names move and identity" 'grep -q "move <companion>|identity <companion>" "$HERE/scripts/i18n/wyrd_en.json"'
check "the launcher redirects enable --single through the guard" 'grep -q "_brain_move_guard \"\${@:2}\"" "$HERE/bin/wyrd"'
check "the move exports the new profile before it restarts (the build box came back up on the two models, 2026-10-01)" 'grep -q "export WYRDSEKAI_SERVING_PROFILE=single-sparse" "$HERE/bin/wyrd"'
[[ $fail -eq 0 ]] && echo "all passed" || { echo "some failed"; exit 1; }
