#!/usr/bin/env bash
# `wyrd researcher setup` tells ResearchZosho's setup where this home's brain is
# (RESEARCHZOSHO_DRIVE_DEFAULT), so it does not take the first model server answering on the usual
# ports. Order: a value already exported, the node's http brain address, the brain on this machine.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
eval "$(sed -n '/^_rz_brain_url() {/,/^}/p' "$HERE/bin/wyrd")"
declare -F _rz_brain_url >/dev/null || { echo "FAIL could not lift _rz_brain_url"; exit 1; }
fails=0
check() { if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1 (got '$2', want '$3')"; fails=$((fails+1)); fi; }
unset RESEARCHZOSHO_DRIVE_DEFAULT WYRDSEKAI_LLAMA_URL LLAMA_DRIVE_PORT
check "the brain on this machine by default" "$(_rz_brain_url)" "http://127.0.0.1:8200"
check "the node's brain address" "$(WYRDSEKAI_LLAMA_URL=http://gpu-host:8200/ _rz_brain_url)" "http://gpu-host:8200"
check "a /v1 suffix is dropped" "$(WYRDSEKAI_LLAMA_URL=http://127.0.0.1:8200/v1 _rz_brain_url)" "http://127.0.0.1:8200"
check "a non-http address is not passed" "$(WYRDSEKAI_LLAMA_URL=nats://zone _rz_brain_url)" "http://127.0.0.1:8200"
check "an exported value wins" "$(RESEARCHZOSHO_DRIVE_DEFAULT=http://x:9 WYRDSEKAI_LLAMA_URL=http://y:8200 _rz_brain_url)" "http://x:9"
grep -q 'RESEARCHZOSHO_DRIVE_DEFAULT="$brain" "$rz" setup --yes' "$HERE/bin/wyrd" && echo "ok   the unattended setup passes it" || { echo "FAIL unattended setup"; fails=$((fails+1)); }
grep -q 'RESEARCHZOSHO_DRIVE_DEFAULT="$brain" "$rz" setup </dev/tty' "$HERE/bin/wyrd" && echo "ok   the interactive setup passes it" || { echo "FAIL interactive setup"; fails=$((fails+1)); }
echo "failures: $fails"; exit $(( fails > 0 ))
