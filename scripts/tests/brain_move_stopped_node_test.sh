#!/usr/bin/env bash
# `wyrd brain move` runs on a stopped node (0.5.8). On a household box the node's two models and its
# companions' own turns ran beside the large model on one chip and took it to 100 °C, and the move's
# two questions were never shown (2026-10-19). Now: the move refuses while the node is up; brings up
# the large model alone; writes, measures, asks — the questions printed; on a pass flips the profile
# and stops the candidate; on a no or a fail stops the candidate; and never starts, stops or holds the
# node itself — `wyrd start` is the steward's. Lifts the move from bin/wyrd with every neighbour
# stubbed and records what it does, in order. Run: bash scripts/tests/brain_move_stopped_node_test.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
for fn in _brain_move _brain_move_revert _brain_ask _t; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$W")"
done
declare -F _brain_move >/dev/null && declare -F _brain_ask >/dev/null || { echo "FAIL could not lift the move from bin/wyrd"; exit 1; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
DATA_DIR="$T"; BRAIN_CONTAINER=wyrdsekai-llama-brain; BRAIN_MODEL_DEFAULT=big.gguf; LLAMA_DRIVE_PORT=8200
mkdir -p "$T/models"; : > "$T/models/big.gguf"
EVENTS="$T/events"; : > "$EVENTS"
ev() { echo "$*" >> "$EVENTS"; }
# The neighbours, recording.
log() { echo "$*"; }; err() { echo "$*" >&2; }; warn() { echo "$*" >&2; }
_brain_companions() { printf 'mia\tcompanion-mia\tdid:key:z6MkTestSoul\n'; }
_brain_move_facts() { echo '{}' > "$3"; }
_brain_adapter_id() { echo ""; }
_brain_move_start_candidate() { ev "candidate-up"; [[ "${CANDIDATE_FAILS:-0}" == 1 ]] && return 1; return 0; }
_brain_identity() { ev "identity-written"; return 0; }
_brain_move_transfer_night() { return 1; }
_node_is_up() { [[ "${NODE_UP:-0}" == 1 ]]; }
do_config() { ev "config $*"; }
do_start() { ev "FORBIDDEN do_start"; }
do_stop() { ev "FORBIDDEN do_stop"; }
_quiesce_notice() { ev "FORBIDDEN quiesce $*"; }
docker() { ev "docker $*"; }
curl() { ev "FORBIDDEN curl $*"; return 1; }
# The gate: --check says her state; a run prints the verdict GATE_VERDICT names.
GATE="$T/gate.py"
cat > "$GATE" <<'PY'
import sys, json, os
a = sys.argv[1:]
if "--check" in a:
    print(json.dumps({"state": os.environ.get("HER_STATE", "established"), "lines_28d": 240, "days": 30})); sys.exit(0)
out = a[a.index("--out") + 1]; json.dump({"verdict": os.environ["GATE_VERDICT"]}, open(out, "w"))
sheet = a[a.index("--sheet") + 1]; open(sheet, "w").write("A: ...\nB: ...\n")
print("gate: measuring", file=sys.stderr); print(os.environ["GATE_VERDICT"])
sys.exit(0 if os.environ["GATE_VERDICT"] == "PASS" else 1)
PY
_brain_gate_script() { echo "$GATE"; }

fail=0
run() { : > "$EVENTS"; OUT="$T/out.txt"; ( cd "$T" && _brain_move mia "$@" ) > "$OUT" 2>&1; RC=$?; }
events() { tr '\n' '|' < "$EVENTS"; }
check() {   # <label> <condition>
    if eval "$2"; then echo "ok   $1"
    else echo "FAIL $1"; echo "     rc=$RC events: $(events)"; sed 's/^/     | /' "$OUT" | tail -6; fail=1; fi
}
MOVED="candidate-up|identity-written|config set WYRDSEKAI_SERVING_PROFILE=single-sparse|docker stop wyrdsekai-llama-brain|"
REVERTED="candidate-up|identity-written|docker stop wyrdsekai-llama-brain|"

NODE_UP=1 GATE_VERDICT=PASS WYRDSEKAI_BRAIN_MOVE_ANSWER=y run
check "node up: the move refuses and names wyrd stop"            '[[ $RC -ne 0 ]] && grep -q "wyrd stop" "$OUT"'
check "node up: nothing was started or touched"                  '[[ ! -s "$EVENTS" ]]'

NODE_UP=0 GATE_VERDICT=PASS WYRDSEKAI_BRAIN_MOVE_ANSWER=y run
check "pass + yes: moved"                                        '[[ $RC -eq 0 ]]'
check "pass + yes: candidate up, her description, the profile, the candidate stopped — in that order" '[[ "$(events)" == "$MOVED" ]]'
check "pass + yes: the node is never started, stopped, held or asked anything" '! grep -q FORBIDDEN "$EVENTS"'
check "pass + yes: the steward is told to run wyrd start"        'grep -q "wyrd start" "$OUT"'
check "pass + yes: the question is printed, with its answer"     'grep -q "Move mia to the large model? \[y/N\] y" "$OUT"'
check "pass + yes: the record says moved and by whom"            'python3 -c "import json,glob,sys; d=json.load(open(sorted(glob.glob(\"$T/adapters/brainwrite/companion-mia/move-*.json\"))[-1])); sys.exit(0 if d.get(\"moved\") and d.get(\"approved_by\") else 1)"'

NODE_UP=0 GATE_VERDICT=PASS WYRDSEKAI_BRAIN_MOVE_ANSWER=n run
check "pass + no: not moved"                                     '[[ $RC -ne 0 ]]'
check "pass + no: the candidate stopped, the profile untouched, the node untouched" '[[ "$(events)" == "$REVERTED" ]]'
check "pass + no: wyrd start brings the two models back"         'grep -q "wyrd start" "$OUT"'

NODE_UP=0 GATE_VERDICT=FAIL WYRDSEKAI_BRAIN_MOVE_ANSWER=y run
check "fail: not moved, the candidate stopped, the profile untouched" '[[ $RC -ne 0 && "$(events)" == "$REVERTED" ]]'
check "fail: wyrd start brings the two models back"              'grep -q "wyrd start" "$OUT"'

NODE_UP=0 CANDIDATE_FAILS=1 GATE_VERDICT=PASS WYRDSEKAI_BRAIN_MOVE_ANSWER=y run
check "the large model does not start: nothing changed, the node is not started by the move" '[[ $RC -ne 0 && "$(events)" == "candidate-up|" ]] && grep -q "wyrd start" "$OUT"'

NODE_UP=0 HER_STATE=born GATE_VERDICT=PASS WYRDSEKAI_BRAIN_MOVE_ANSWER=y run
check "born here: moves at once, nothing measured, and still nothing of the node is touched" '[[ $RC -eq 0 && "$(events)" == "$MOVED" ]]'

# The questions: on a terminal they are shown and read; without one the answer is no and it says why.
unset WYRDSEKAI_BRAIN_MOVE_ANSWER
{ declare -f _brain_ask _t; printf 'WYRD_I18N_DIR=%q; WYRD_LOCALE=en\n' "$WYRD_I18N_DIR"
  printf '%s\n' "_brain_ask 'Move her? [y/N] '" 'echo "answer=$BRAIN_ANSWER"'; } > "$T/ask.sh"
if command -v setsid >/dev/null 2>&1; then
    out=$(env -u WYRDSEKAI_BRAIN_MOVE_ANSWER setsid -w bash "$T/ask.sh" 2>&1 </dev/null)
    RC=0; echo "$out" > "$OUT"
    check "no terminal: the question is printed, the answer is no, and it says why" '[[ "$out" == *"Move her? [y/N]"* && "$out" == *"No terminal to answer on"* && "$out" == *"answer=n"* ]]'
fi
if command -v script >/dev/null 2>&1 && script --version 2>/dev/null | grep -q util-linux; then
    out=$(printf 'y\n' | env -u WYRDSEKAI_BRAIN_MOVE_ANSWER script -qec "bash $T/ask.sh" /dev/null 2>&1 | tr -d '\r')
    RC=0; echo "$out" > "$OUT"
    check "a terminal: the question is shown and the answer read from it" '[[ "$out" == *"Move her? [y/N]"* && "$out" == *"answer=y"* ]]'
fi

[[ $fail -eq 0 ]] && echo "all passed" || { echo "some failed"; exit 1; }
