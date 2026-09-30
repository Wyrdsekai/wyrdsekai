#!/usr/bin/env bash
# bin/wyrd runs on macOS under the system bash 3.2 and BSD tools. On a Mac `wyrd doctor` stopped at
# "${1,,}: bad substitution" (lowercase expansion is bash 4), relay leg removal used `declare -A`
# (bash 4), the leg list and `wyrd relay status` used `grep -P` (not in BSD grep) and the status
# probe used `timeout` (not on a stock Mac). This test fails if any of those comes back in code, and
# runs the relay-leg functions it touched. Run it with the Mac's own bash too: /bin/bash <this file>.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

# Code only: comment lines are skipped.
scan() {   # <label> <extended regex that must not match>
    local found
    found="$(grep -vE '^[[:space:]]*#' "$W" | grep -nE "$2" || true)"
    if [[ -z "$found" ]]; then echo "ok   $1"; else echo "FAIL $1"; echo "$found" | head -3 | sed 's/^/     | /'; fail=1; fi
}
scan 'no ${x,,} / ${x^^} (bash 4)' '\$\{[A-Za-z_0-9]+(,,|\^\^)\}'
scan 'no declare -A / local -A (bash 4)' '(declare|local|typeset) -[a-zA-Z]*A'
scan 'no mapfile / readarray / namerefs (bash 4)' '(^|[;[:space:]])(mapfile|readarray)[[:space:]]|(declare|local) -[a-zA-Z]*n[[:space:]]'
scan 'no grep -P (not in BSD grep)' 'grep -[a-zA-Z]*P'
scan 'no timeout command (not on a stock Mac)' '(^|[;(|&[:space:]])timeout [0-9]'

for fn in _relay_key _relay_leg_indexes _relay_leg_urls _relay_leg_index_for_url _relay_strip_leg \
          _relay_write_leg _relay_leg_field _relay_remove_leg_by_url; do
    # The sed program in a variable: bash 3.2 misreads a `}` inside "$( … "…" … )".
    prog="/^${fn}() {/,/^}/p"
    body="$(sed -n "$prog" "$W")"
    eval "$body"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
C="$T/wyrdsekai.conf"
cat > "$C" <<'CONF'
WYRDSEKAI_RELAY_ENABLED=true
WYRDSEKAI_RELAY_URL=nats://one.example:4222
WYRDSEKAI_RELAY_USER=u1
WYRDSEKAI_RELAY_URL_2=nats://two.example:4222
WYRDSEKAI_RELAY_USER_2=u2
WYRDSEKAI_RELAY_URL_3=nats://three.example:4443
WYRDSEKAI_RELAY_USER_3=u3
CONF
check "every leg is listed (0 2 3)" '[[ "$(_relay_leg_indexes "$C" | tr "\n" " ")" == "0 2 3 " ]]'
_relay_remove_leg_by_url "$C" "nats://two.example:4222"
check "removing the middle leg keeps the others, renumbered" \
    '[[ "$(_relay_leg_indexes "$C" | tr "\n" " ")" == "0 2 " ]]'
check "  leg 0 is still the first relay" '[[ "$(_relay_leg_field "$C" URL 0)" == "nats://one.example:4222" && "$(_relay_leg_field "$C" USER 0)" == "u1" ]]'
check "  the third relay is now leg 2, with its own login" '[[ "$(_relay_leg_field "$C" URL 2)" == "nats://three.example:4443" && "$(_relay_leg_field "$C" USER 2)" == "u3" ]]'
check "  the removed relay is gone" '! grep -q "two.example" "$C"'

echo "bash ${BASH_VERSION}"
if [[ $fail -ne 0 ]]; then echo "FAILED"; exit 1; fi
echo "all passed"
