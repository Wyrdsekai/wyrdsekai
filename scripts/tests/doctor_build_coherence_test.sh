#!/usr/bin/env bash
# `wyrd doctor`'s build coherence and `wyrd version --mesh` read the home's /api/version/mesh answer.
# Both piped it into a python here-doc, which takes python's stdin, so the answer never arrived and
# every run printed "returned non-JSON"; the doctor also replaced drift's summary with "probe failed"
# and never counted it. Lifts both from bin/wyrd with a fake curl. Runs under set -euo pipefail, as
# the launcher does.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
for fn in _doctor_build_coherence do_version; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
log() { echo "$*"; }; err() { echo "ERR $*"; }; _t() { echo "$*"; }
WARNS=0; _wbump() { WARNS=$((WARNS + 1)); }
ANSWER=""
curl() { [[ -n "$ANSWER" ]] || return 22; printf '%s' "$ANSWER"; }
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; echo "$OUT" | sed 's/^/     | /'; fail=1; fi; }

local_json='"local":{"zoneId":"hush","buildHash":"abc1234","appVersion":"0.5.0","federationSchema":1}'

ANSWER="{${local_json},\"peers\":[]}"; WARNS=0
OUT="$(_doctor_build_coherence)"; _doctor_build_coherence >/dev/null
check "doctor: the answer is read (this build shown)" '[[ "$OUT" == *"local: build=abc1234 (0.5.0, schema=1)"* ]]'
check "doctor: no peers, no warning" '[[ "$OUT" == *"no federation peers"* && $WARNS -eq 0 ]]'
check "doctor: never 'returned non-JSON' for JSON" '[[ "$OUT" != *"non-JSON"* ]]'

ANSWER="{${local_json},\"peers\":[{\"zoneId\":\"lake\",\"buildVersion\":{\"buildHash\":\"fff0000\",\"federationSchema\":1}}]}"; WARNS=0
OUT="$(_doctor_build_coherence)"; _doctor_build_coherence >/dev/null
check "doctor: a peer on another build is named" '[[ "$OUT" == *"lake: build fff0000 != abc1234"* && "$OUT" == *"drift=1"* ]]'
check "doctor: drift counts as a warning" '[[ $WARNS -eq 1 ]]'
check "doctor: drift's summary is not replaced by 'probe failed'" '[[ "$OUT" != *"mesh probe failed"* ]]'

ANSWER="{${local_json},\"peers\":[{\"zoneId\":\"lake\",\"buildVersion\":{\"buildHash\":\"abc1234\",\"federationSchema\":2}}]}"; WARNS=0
OUT="$(_doctor_build_coherence)"; _doctor_build_coherence >/dev/null
check "doctor: another federation schema is named and warned" '[[ "$OUT" == *"lake: schema 2 != 1"* && $WARNS -eq 1 ]]'

ANSWER="{${local_json},\"peers\":[{\"zoneId\":\"old\"}]}"; WARNS=0
OUT="$(_doctor_build_coherence)"; _doctor_build_coherence >/dev/null
check "doctor: a peer too old to say is counted, not warned" '[[ "$OUT" == *"pre-F14=1"* && $WARNS -eq 0 && "$OUT" != *"probe failed"* ]]'

ANSWER="{${local_json},\"peers\":[{\"zoneId\":\"lake\",\"buildVersion\":{\"buildHash\":\"abc1234\",\"federationSchema\":1}}]}"; WARNS=0
OUT="$(_doctor_build_coherence)"; _doctor_build_coherence >/dev/null
check "doctor: a peer in step, no warning" '[[ "$OUT" == *"drift=0"* && $WARNS -eq 0 ]]'

ANSWER="<html>401</html>"; WARNS=0
OUT="$(_doctor_build_coherence)"
check "doctor: a non-JSON answer says so and does not stop the doctor" '[[ "$OUT" == *"returned non-JSON"* ]]'

ANSWER=""
OUT="$(_doctor_build_coherence)"
check "doctor: no answer is skipped" '[[ "$OUT" == *"doctor.build_skipped"* ]]'

ANSWER="{${local_json},\"peers\":[]}"
OUT="$(do_version --mesh)"
check "version --mesh: the answer is read" '[[ "$OUT" == *"Local zone:  hush build=abc1234 (0.5.0, schema=1)"* && "$OUT" == *"No federation peers known."* ]]'

if [[ $fail -ne 0 ]]; then echo "FAILED"; exit 1; fi
echo "all passed"
