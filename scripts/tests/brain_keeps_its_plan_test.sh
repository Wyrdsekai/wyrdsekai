#!/usr/bin/env bash
# Her brain keeps its plan through a night. After a night's write the launcher starts the brain
# again; the start exports the plan (how many layers' experts stay in system RAM) and the recreate
# reads it. With an assignment in front of the start, bash 5.3 dropped the export when the function
# returned: the brain came back with no plan, every expert in system RAM and the whole file pinned,
# and the kernel killed it for memory every ten minutes (a household node on Ubuntu 26.04,
# 2026-11-14). A brain already running without a plan was read as "not up", so nothing moved it
# back. Checks, with a stub docker that writes down the plan each call was given: the recreate
# after a night carries the plan and starts the service for this kind of GPU; a start that cannot
# plan restarts the brain as it is; a running brain with no plan is "all"; the brainstem moves it
# back. No docker, no card. Run: bash scripts/tests/brain_keeps_its_plan_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
check() {   # <label> <condition...>
    local label="$1"; shift
    if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi
}
has() { grep -qF -- "$2" "$1" 2>/dev/null; }
hasnt() { ! has "$@"; }

# A docker that sees only what a real one would: the exported environment and its arguments.
# `inspect` prints the brain's environment from a file, or fails when there is no brain.
mkdir -p "$T/bin" "$T/data/models" "$T/root"
cat > "$T/bin/docker" <<EOF
#!/bin/sh
if [ "\$1" = inspect ]; then [ -f "$T/brain-env" ] && cat "$T/brain-env" && exit 0; exit 1; fi
echo "plan=[\${LLAMA_CPU_MOE-unset}] \$*" >> "$T/calls"
EOF
printf '#!/bin/sh\nexit 0\n' > "$T/bin/nvidia-smi"
chmod +x "$T/bin/docker" "$T/bin/nvidia-smi"
PATH="$T/bin:$PATH"

# ── the launcher: the start, the service for this GPU, the recreate after a night, the reader ──
eval "$(sed -n '/^BRAIN_CONTAINER=/p' "$HERE/bin/wyrd")"
for fn in _start_brain_compose _brain_compose_up _brain_current_plan; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
done
eval "$(sed -n '/^    _brain_recreate() {/,/^    }/p' "$HERE/bin/wyrd")"
declare -F _brain_recreate >/dev/null || { echo "FAIL could not lift _brain_recreate"; exit 1; }
uname() { echo Linux; }
log() { :; }; warn() { :; }; _t() { :; }; _ensure_nvidia_container_runtime() { :; }
_serving_profile() { echo single-sparse; }; _brain_vram_available_mb() { echo 16311; }; _brain_ram_mb() { echo 30819; }
_brain_single_mem_limit() { echo 24000m; }
KIND=nvidia; PLAN=19
_brain_gpu_kind() { echo "$KIND"; }; _brain_plan_for_kind() { echo "$PLAN"; }
DATA_DIR="$T/data"; PROJECT_ROOT="$T/root"; BRAIN_MODEL_DEFAULT=brain.gguf; : > "$DATA_DIR/models/brain.gguf"
night() { rm -f "$T/calls"; ( unset LLAMA_CPU_MOE LLAMA_BRAIN_MEM_LIMIT; _brain_recreate ); [[ -f "$T/calls" ]] || : > "$T/calls"; }

night
check "after a night: the brain is recreated"                       has "$T/calls" "up -d --force-recreate llama-brain"
check "after a night: the recreate carries the plan"                has "$T/calls" "plan=[19] compose --profile brain up -d --force-recreate llama-brain"
check "after a night: no start is made without a plan"              bash -c "! grep -E ' up -d' '$T/calls' | grep -qv -F 'plan=[19]'"
KIND=amd; night
check "AMD: the Vulkan service is the one recreated"                has "$T/calls" "plan=[19] compose --profile brain-vulkan up -d --force-recreate llama-brain-vulkan"
check "AMD: the NVIDIA service is not started"                      hasnt "$T/calls" "--profile brain up"
KIND=nvidia; PLAN=gpu-full; night
check "whole model on the card: the plan is 0, not empty"           has "$T/calls" "plan=[0] compose --profile brain up -d --force-recreate llama-brain"
PLAN=not-viable; night
check "cannot plan now: nothing is started without a plan"          hasnt "$T/calls" " up -d"
check "cannot plan now: the running brain is restarted as it is"    has "$T/calls" "restart $BRAIN_CONTAINER"
PLAN=19

rm -f "$T/brain-env"
check "reader: no brain is no plan"            test -z "$(_brain_current_plan)"
printf 'PATH=/usr/bin\nLLAMA_CPU_MOE=\nLLAMA_BRAIN_CTX=\n' > "$T/brain-env"
check "reader: a brain given no plan is all"   test "$(_brain_current_plan)" = all
printf 'LLAMA_CPU_MOE=19\n' > "$T/brain-env"
check "reader: 19 is 19"                       test "$(_brain_current_plan)" = 19
printf 'LLAMA_CPU_MOE=0\n' > "$T/brain-env"
check "reader: 0 is the whole model on the card" test "$(_brain_current_plan)" = gpu-full

# ── the brainstem: a brain with no plan is moved back when the card is free ──
BS="$HERE/packaging/brainstem/wyrdsekai-brainstem"
eval "$(sed -n '/^card_share() {/,/^}/p;/^plan_rank() {/,/^}/p' "$BS")"
cat > "$T/bin/wyrd" <<EOF
#!/bin/sh
case "\$2" in others-mb) echo 32 ;; plan-value) echo 19 ;; reshape) echo reshape >> "$T/moves" ;; esac
EOF
chmod +x "$T/bin/wyrd"
mkdir -p "$T/state"; echo 'WYRDSEKAI_SERVING_PROFILE=single-sparse' > "$T/conf"
read_state() { cat "$T/state/$1" 2>/dev/null || echo "${2:-}"; }
write_state() { printf '%s' "$2" > "$T/state/$1"; }
event() { echo "$*" >> "$T/events"; }; health_up() { return 0; }; inflight() { echo 0; }
CARD_SHARE=1; WYRD="$T/bin/wyrd"; OS=Linux; CARD_MIN_MB=1024; CARD_HOLD=0; CARD_RECLAIM_HOLD=0; CARD_EVERY=0; CONFIG_FILE="$T/conf"
look() { rm -f "$T/moves" "$T/events" "$T/state/"*; write_state card_looked 1; card_share; }

printf 'LLAMA_CPU_MOE=\n' > "$T/brain-env"; look
check "brainstem: a brain with no plan is moved back onto the card"  has "$T/moves" reshape
check "brainstem: the move is told as a return to the card"          has "$T/events" "card-reclaimed"
check "brainstem: it says what she had, all"                         has "$T/events" "(all → 19)"
printf 'LLAMA_CPU_MOE=19\n' > "$T/brain-env"; look
check "brainstem: a brain on its plan is left alone"                 test ! -f "$T/moves"
rm -f "$T/brain-env"; look
check "brainstem: no brain, nothing to move"                         test ! -f "$T/moves"

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
