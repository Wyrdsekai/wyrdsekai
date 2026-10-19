#!/usr/bin/env bash
# The doctor runs to its summary whatever its checks find. bin/wyrd runs under set -e, and a check
# that warns returns 1: called bare and followed by `if [[ $? -eq 1 ]]`, the first warning ended the
# doctor with no summary (the Mac's, after one set-aside room script, 2026-10-19). Every warning
# check is called as `if ! _doctor_x; then _wbump; fi`, which set -e leaves alone. Two checks here:
# the launcher holds no bare call of a warning check, and the pattern it uses survives set -e.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }

# Every doctor check that can return non-zero, found from the launcher itself, and the way each is called.
bare=""; guarded=0; returning=0
for fn in $(grep -oE '^_doctor_[a-z_]+\(\) \{' "$W" | sed 's/() {//'); do
    body="$(sed -n "/^${fn}() {/,/^}/p" "$W")"
    printf '%s\n' "$body" | grep -qE 'return [1-9]|return \$\?' || continue
    returning=$((returning+1))
    if grep -qE "^\s*${fn}\s*$" "$W"; then bare="$bare $fn"; fi
    if grep -qE "^\s*if ! ${fn}; then" "$W"; then guarded=$((guarded+1)); fi
done
check "no check that can warn is called bare ($returning can; $guarded use the if-not form, the rest a condition or a pipe)" '[[ -z "$bare" && "$returning" -ge 8 ]]'
[[ -n "$bare" ]] && echo "     called bare:$bare"

# The pattern, under the launcher's own options: a warning check that returns 1 is counted and
# the doctor goes on; a bare call would have ended it.
out="$(bash -c 'set -euo pipefail
_wbump() { W=$((W+1)); }; W=0
_doctor_warns() { echo "WARN: something"; return 1; }
_doctor_fine() { return 0; }
if ! _doctor_warns; then _wbump; fi
if ! _doctor_fine; then _wbump; fi
echo "Doctor: $W warning(s)."' 2>&1)"
check 'a warning is counted and the summary is reached' '[[ "$out" == *"WARN: something"*"Doctor: 1 warning(s)."* ]]'
bare_out="$(bash -c 'set -euo pipefail
_doctor_warns() { echo "WARN: something"; return 1; }
_doctor_warns
echo "Doctor: reached"' 2>&1 || true)"
check 'the bare call would have ended the doctor (the control)' '[[ "$bare_out" != *"Doctor: reached"* ]]'

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
