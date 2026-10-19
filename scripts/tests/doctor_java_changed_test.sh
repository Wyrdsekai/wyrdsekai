#!/usr/bin/env bash
# `wyrd doctor` says when the Java on disk is not the Java the running server was started on
# (2026-10-19: a host's unattended upgrade replaced the JDK under a household's node; every helper
# it started printed a four-line warning for two weeks and nothing said why). Runs the doctor's
# function from bin/wyrd with the two readings stood in for.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
for fn in _doctor_java_changed _t; do
    body="$(sed -n "/^${fn}() {/,/^}/p" "$W")"
    eval "$body"
done
warn() { echo "WARN: $*"; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en

_java_runtime_running() { echo "25.0.4+7-1-24.04-Ubuntu"; }
_java_runtime_installed() { echo "25.0.4+7-1-24.04-Ubuntu"; }
out="$(_doctor_java_changed; echo "rc=$?")"
check 'the same Java: silent, rc 0' '[[ "$out" == "rc=0" ]]'

_java_runtime_installed() { echo "25.0.4.1+1-1-24.04.4-Ubuntu"; }
out="$(_doctor_java_changed; echo "rc=$?")"
check 'a newer Java on disk is warned about, rc 1' '[[ "$out" == *"WARN: the Java on this machine changed under the running server (25.0.4.1+1-1-24.04.4-Ubuntu is installed; the server still runs on 25.0.4+7-1-24.04-Ubuntu)"*"wyrd restart"*"rc=1" ]]'
out_ja="$(WYRD_LOCALE=ja _doctor_java_changed || true)"
check 'the warning reads in Japanese on a Japanese household' '[[ "$out_ja" == *"動作中のサーバーの下で変わりました"*"wyrd restart"* ]]'

_java_runtime_running() { echo ""; }
out="$(_doctor_java_changed; echo "rc=$?")"
check 'the node not answering: silent, rc 0' '[[ "$out" == "rc=0" ]]'
_java_runtime_running() { echo "25.0.4+7-1-24.04-Ubuntu"; }
_java_runtime_installed() { echo ""; }
out="$(_doctor_java_changed; echo "rc=$?")"
check 'no Java found on disk: silent, rc 0' '[[ "$out" == "rc=0" ]]'

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
