#!/usr/bin/env bash
# Two 0.5.4 regressions a household reported (2026-10-05): every `wyrd` subcommand run as an ordinary
# user failed with "Permission denied" because the launcher sourced the root-owned env file whenever
# it existed; and `wyrd update --help` took "--help" for an installer file. Neither may come back.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'chmod -R u+rwX "$T" 2>/dev/null; rm -rf "$T"' EXIT
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

out=$(bash "$HERE/bin/wyrd" update --help 2>&1); rc=$?
check "update --help prints usage and succeeds" '[[ $rc -eq 0 && "$out" == *"Usage: wyrd update"* && "$out" != *"No such file"* ]]'
out=$(bash "$HERE/bin/wyrd" update -h 2>&1); rc=$?
check "update -h too" '[[ $rc -eq 0 && "$out" == *"Usage: wyrd update"* ]]'
out=$(bash "$HERE/bin/wyrd" update now --help 2>&1); rc=$?
check "update now --help prints usage instead of updating" '[[ $rc -eq 0 && "$out" == *"Usage: wyrd update"* ]]'
out=$(bash "$HERE/bin/wyrd" researcher update --help 2>&1); rc=$?
check "researcher update --help prints usage and never runs the updater" '[[ $rc -eq 0 && "$out" == *"Usage: wyrd researcher update"* && "$out" != *"not installed"* ]]'

if [[ "$(id -u)" == "0" ]]; then
    echo "skip an unreadable env file (root reads everything)"
else
    mkdir -p "$T/data" "$T/home"
    echo "WYRDSEKAI_TEST_SECRET=1" > "$T/data/env"; chmod 000 "$T/data/env"
    out=$(HOME="$T/home" WYRDSEKAI_DATA_DIR="$T/data" bash "$HERE/bin/wyrd" version 2>&1); rc=$?
    check "an env file this user may not read is passed over in silence" '[[ "$out" != *"Permission denied"* ]]'
    check "the launcher still answers" '[[ $rc -eq 0 && -n "$out" ]]'
fi
echo "failures: $fails"
[ "$fails" -eq 0 ] && echo "all passed"
exit $(( fails > 0 ))
