#!/usr/bin/env bash
# The brain profile and the inference mode agree (0.5.9). A household set up with its models on
# another machine (mode cloud) moved a companion to the large model; `wyrd start` obeyed the mode,
# started nothing local, and brought up the node with its brain down, nothing said (2026-10-22).
# Now: enabling or moving sets the mode to local and keeps the old one; disabling puts it back;
# `wyrd start` under a brain profile with a mode that starts nothing here refuses with the way out;
# `brain status` and the doctor say the same. Lifts the pieces from bin/wyrd with the neighbours
# stubbed. Run: bash scripts/tests/brain_mode_follows_profile_test.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
for fn in _brain_mode_for_profile _start_mode_blocks_profile _serving_profile effective_inference_mode _t; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$W")"
done
declare -F _brain_mode_for_profile >/dev/null && declare -F _start_mode_blocks_profile >/dev/null \
    || { echo "FAIL could not lift the mode helpers from bin/wyrd"; exit 1; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
EVENTS="$T/events"; : > "$EVENTS"
ev() { echo "$*" >> "$EVENTS"; }
log() { echo "$*"; }; err() { echo "$*" >&2; }; warn() { echo "$*" >&2; }; info() { echo "$*"; }
# The conf: recorded, and applied to the environment so a sequence reads its own writes.
do_config() { ev "config $*"; case "$1" in set) export "$2" ;; unset) unset "$2" ;; esac; }
local_inference_wanted() { [[ "${LOCAL_WANTED:-1}" == 1 ]]; }
fails=0
check() { if [[ "$2" == 0 ]]; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }
reset() { : > "$EVENTS"; unset WYRDSEKAI_INFERENCE_MODE WYRDSEKAI_BRAIN_MODE_BEFORE WYRDSEKAI_INFERENCE_URL WYRDSEKAI_SERVING_PROFILE LOCAL_WANTED 2>/dev/null; }

# 1. Enabling under cloud: the mode becomes local, the old mode is kept, and it is said.
reset; export WYRDSEKAI_INFERENCE_MODE=cloud WYRDSEKAI_INFERENCE_URL=http://example.test:11434
_brain_mode_for_profile single-sparse > "$T/out"; out="$(cat "$T/out")"
grep -qx "config set WYRDSEKAI_BRAIN_MODE_BEFORE=cloud" "$EVENTS"; check "enable under cloud keeps the old mode" $?
grep -qx "config set WYRDSEKAI_INFERENCE_MODE=local" "$EVENTS"; check "enable under cloud sets the mode to local" $?
[[ "$out" == *local* && "$out" == *cloud* && "$out" == *example.test* ]]; check "enable under cloud says so, naming the old mode and the server kept as a fallback" $?
[[ "$WYRDSEKAI_INFERENCE_MODE" == local ]]; check "the running shell sees the new mode" $?

# 2. Enabling again does not overwrite the kept mode.
_brain_mode_for_profile single-sparse > "$T/out"
[[ "$(grep -c "WYRDSEKAI_BRAIN_MODE_BEFORE=" "$EVENTS")" == 1 ]]; check "a second enable leaves the kept mode alone" $?

# 3. Disabling puts the old mode back and forgets it.
: > "$EVENTS"; _brain_mode_for_profile two-model > "$T/out"; out="$(cat "$T/out")"
grep -qx "config set WYRDSEKAI_INFERENCE_MODE=cloud" "$EVENTS"; check "disable restores the kept mode" $?
grep -qx "config unset WYRDSEKAI_BRAIN_MODE_BEFORE" "$EVENTS"; check "disable forgets the kept mode" $?
[[ "$out" == *cloud* ]]; check "disable says which mode came back" $?

# 4. Already local: nothing written, nothing said. Disable with nothing kept: the same.
reset; export WYRDSEKAI_INFERENCE_MODE=local
_brain_mode_for_profile single-sparse > "$T/out"; [[ ! -s "$EVENTS" && ! -s "$T/out" ]]; check "enable under local writes and says nothing" $?
_brain_mode_for_profile two-model > "$T/out"; [[ ! -s "$EVENTS" && ! -s "$T/out" ]]; check "disable with no kept mode writes and says nothing" $?

# 5. sparse-drive is a brain profile too.
reset; export WYRDSEKAI_INFERENCE_MODE=zone
_brain_mode_for_profile sparse-drive > "$T/out"
grep -qx "config set WYRDSEKAI_INFERENCE_MODE=local" "$EVENTS"; check "sparse-drive under zone sets the mode to local" $?

# 6. What blocks the start: a brain profile with cloud, zone, or local inference switched off.
reset; export WYRDSEKAI_SERVING_PROFILE=single-sparse WYRDSEKAI_INFERENCE_MODE=cloud
why="$(_start_mode_blocks_profile)"; rc=$?; [[ "$rc" == 0 && "$why" == cloud ]]; check "single-sparse under cloud is blocked, reason cloud" $?
export WYRDSEKAI_SERVING_PROFILE=sparse-drive WYRDSEKAI_INFERENCE_MODE=zone
why="$(_start_mode_blocks_profile)"; rc=$?; [[ "$rc" == 0 && "$why" == zone ]]; check "sparse-drive under zone is blocked, reason zone" $?
export WYRDSEKAI_SERVING_PROFILE=single-sparse WYRDSEKAI_INFERENCE_MODE=local LOCAL_WANTED=0
why="$(_start_mode_blocks_profile)"; rc=$?; [[ "$rc" == 0 && "$why" == *LLAMA_ENABLED* ]]; check "single-sparse with local inference switched off is blocked, and says which switch" $?
export WYRDSEKAI_SERVING_PROFILE=single-sparse WYRDSEKAI_INFERENCE_MODE=local LOCAL_WANTED=1
_start_mode_blocks_profile >/dev/null; [[ $? == 1 ]]; check "single-sparse under local is not blocked" $?
export WYRDSEKAI_SERVING_PROFILE=two-model WYRDSEKAI_INFERENCE_MODE=cloud
_start_mode_blocks_profile >/dev/null; [[ $? == 1 ]]; check "two-model under cloud is not blocked (nothing of the brain is wanted here)" $?

# 7. The refusal names the fix, in every language.
ok=0; for WYRD_LOCALE in en es ja; do
    m="$(_t start.brain_needs_local single-sparse cloud)"
    [[ "$m" == *"WYRDSEKAI_INFERENCE_MODE=local"* && "$m" == *single-sparse* && "$m" == *cloud* ]] || ok=1
    m="$(_t brain.status.mode_blocks cloud)"; [[ "$m" == *"WYRDSEKAI_INFERENCE_MODE=local"* ]] || ok=1
    m="$(_t config.set.apply_hint_stack)"; [[ "$m" == *"wyrd stop"* && "$m" == *"wyrd start"* ]] || ok=1
    m="$(_t brain.status.companion_moved mia 2026-10-22 PASS)"; [[ "$m" == *mia* && "$m" == *2026-10-22* && "$m" == *PASS* ]] || ok=1
done; WYRD_LOCALE=en
check "the refusal, the status line, the config hint and the companion line read right in en, es and ja" $ok

# 8. Wiring pins in bin/wyrd: who calls what, and in what order.
s=$(grep -n '_start_mode_blocks_profile)"; then' "$W" | head -1 | cut -d: -f1); t=$(grep -n '^    _start_ssh_tunnel$' "$W" | head -1 | cut -d: -f1)
[[ -n "$s" && -n "$t" && "$s" -lt "$t" ]]; check "wyrd start refuses before the tunnel and the JVM" $?
grep -q 'do_config set "WYRDSEKAI_SERVING_PROFILE=single-sparse" >/dev/null' "$W" && \
    sed -n '/do_config set "WYRDSEKAI_SERVING_PROFILE=single-sparse" >\/dev\/null/{n;p;}' "$W" | grep -q '_brain_mode_for_profile single-sparse'; check "the move's pass sets the mode after the profile" $?
grep -q '_brain_mode_for_profile "\$want"' "$W"; check "brain enable sets the mode for the profile it chose" $?
grep -q '_brain_mode_for_profile two-model' "$W"; check "brain disable puts the mode back" $?
grep -q '_doctor_brain_mode || _wbump' "$W"; check "the doctor counts the mismatch as a warning" $?
grep -q 'brain.status.companion_moved' "$W" && grep -q 'brain.status.companion_home' "$W"; check "brain status has a line per companion" $?
grep -q 'config.set.apply_hint_stack' "$W"; check "config set points stack keys at stop/start" $?

echo; if [[ "$fails" == 0 ]]; then echo "ALL PASS"; else echo "$fails FAILED"; exit 1; fi
