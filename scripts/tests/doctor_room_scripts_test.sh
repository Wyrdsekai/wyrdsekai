#!/usr/bin/env bash
# `wyrd doctor` names the room scripts a companion wrote that failed three times and were set aside
# (BrokenRoomScripts writes <data>/scripts/.broken/state.json). This runs the doctor's function from
# bin/wyrd against a record with one script set aside and one still counting, and with no record.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
for fn in _doctor_room_scripts _room_scripts_set_aside _t; do
    prog="/^${fn}() {/,/^}/p"
    body="$(sed -n "$prog" "$W")"
    eval "$body"
done
warn() { echo "WARN: $*"; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en   # the doctor's lines go through the translations
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
DATA_DIR="$tmp"
out="$(_doctor_room_scripts; echo "rc=$?")"
check 'no record: silent, rc 0' '[[ "$out" == "rc=0" ]]'
mkdir -p "$tmp/scripts/.broken"
cat > "$tmp/scripts/.broken/state.json" <<'EOF'
{
  "quiet-alcove": {"mtime": 1, "failures": 4, "lastError": "onEnter: ReferenceError: emit is not defined", "told": "x", "setAside": true},
  "hearth": {"mtime": 3, "failures": 3, "lastError": "onActivate: SyntaxError: Unnamed:2:65 Missing close quote\n  emit('narrate', { text: 'The water doesn't move.' });\n                 ^", "told": "z", "setAside": true},
  "study": {"mtime": 2, "failures": 1, "lastError": "onSay: TypeError: entity.getProperty is not a function", "told": "y"}
}
EOF
out="$(_doctor_room_scripts; echo "rc=$?")"
check 'two set aside: warned with the right count, rc 1 (a parse error quoted across lines is one script)' '[[ "$out" == *"WARN: 2 room script(s) set aside after failing three times"* && "$out" == *"rc=1" ]]'
out_es="$(WYRD_LOCALE=es _doctor_room_scripts || true)"   # it returns 1 when it warns
check 'the warning reads in Spanish on a Spanish household' '[[ "$out_es" == *"WARN: 2 script(s) de sala apartado(s)"* ]]'
check 'a multi-line error is shown on one line' '[[ "$out" == *"hearth — onActivate: SyntaxError: Unnamed:2:65 Missing close quote emit("* ]]'
check 'the set-aside room and its error are named' '[[ "$out" == *"quiet-alcove — onEnter: ReferenceError: emit is not defined"* ]]'
check 'a script still counting is not listed' '[[ "$out" != *"study"* ]]'
echo '{not json' > "$tmp/scripts/.broken/state.json"
out="$(_doctor_room_scripts; echo "rc=$?")"
check 'an unreadable record: silent, rc 0' '[[ "$out" == "rc=0" ]]'
exit $fail
