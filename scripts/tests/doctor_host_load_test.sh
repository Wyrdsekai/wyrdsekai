#!/usr/bin/env bash
# `wyrd doctor` says when a companion model runs on this processor and how loaded the processor is
# over the last 15 minutes; it warns at nine tenths of the cores, where her own time slows
# (2026-10-19, after a household's 32-thread box ran two companions' own time at 1300% CPU until
# its thermal limit reset it). Runs the doctor's function from bin/wyrd against a stand-in loadavg.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
for fn in _doctor_host_load _local_model_on_cpu _t; do
    prog="/^${fn}() {/,/^}/p"
    body="$(sed -n "$prog" "$W")"
    eval "$body"
done
warn() { echo "WARN: $*"; }
log() { echo "LOG: $*"; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en   # the doctor's lines go through the translations
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export WYRD_PROC_LOADAVG="$tmp/loadavg" WYRD_NPROC=32

_local_model_on_cpu() { return 1; }
echo "30.2 29.8 29.1 3/1200 4321" > "$tmp/loadavg"
out="$(_doctor_host_load; echo "rc=$?")"
check 'no model on the processor: silent, rc 0' '[[ "$out" == "rc=0" ]]'

_local_model_on_cpu() { return 0; }
out="$(_doctor_host_load; echo "rc=$?")"
check 'a pinned processor is warned about, rc 1' '[[ "$out" == *"WARN: a companion model runs on this machine"*"29.1 on 32 cores"*"rc=1" ]]'
out_es="$(WYRD_LOCALE=es _doctor_host_load || true)"
check 'the warning reads in Spanish on a Spanish household' '[[ "$out_es" == *"corre en el procesador"*"29.1 en 32 núcleos"* ]]'

echo "3.4 3.1 2.9 1/1200 4321" > "$tmp/loadavg"
out="$(_doctor_host_load; echo "rc=$?")"
check 'an easy load is said plainly, rc 0' '[[ "$out" == *"LOG: a companion model runs on this machine"*"2.9 on 32 cores"*"rc=0" ]]'

rm -f "$tmp/loadavg"
out="$(_doctor_host_load; echo "rc=$?")"
check 'no loadavg to read: silent, rc 0' '[[ "$out" == "rc=0" ]]'

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
