#!/usr/bin/env bash
# The single model's server runs two slots on one shared context pool (--parallel 2 --kv-unified),
# so a one-token typed question is answered while the other slot writes a long reply, and either
# slot may use the whole window. Checks every place the brain's server is started on Linux and
# macOS: the llama-brain compose service (single-sparse and sparse-drive both start it), bin/wyrd
# _start_brain_native (macOS) and the e2e harness. The two-model servers keep one slot each. The
# compose command is run under sh with a stub server that writes down its arguments; no docker,
# no model. Run: bash scripts/tests/brain_server_args_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
check() {   # <label> <condition...>
    local label="$1"; shift
    if "$@"; then echo "ok   $label"; else echo "FAIL $label"; fail=1; fi
}
has() { [[ " $(cat "$1") " == *" $2 "* ]]; }   # <args file> <argument sequence>
hasnt() { ! has "$@"; }

printf '#!/bin/sh\nprintf "%%s " "$@" > "%s/args"\n' "$T" > "$T/stub"; chmod +x "$T/stub"
mkdir -p "$T/adapters/brain" "$T/adapters/brainwrite/mia"
: > "$T/adapters/brain/species.gguf"; : > "$T/adapters/brain/work.gguf"; : > "$T/adapters/brainwrite/mia/current.gguf"

# ── compose: the llama-brain command, compose's $$ escaping undone, pointed at the temp tree ──
service_block() {   # <service name> → the service's lines in docker-compose.yml
    awk -v s="  $1:" '$0 == s {f=1; print; next} f && /^  [a-z]/ {exit} f {print}' "$HERE/docker-compose.yml"
}
service_block llama-brain | awk '/^    command:/{c=1; next} c && /^    environment:/{exit} c && /^      - \|/{next} c {sub(/^        /, ""); print}' \
    | sed 's/\$\$/$/g' | sed "s#/app/llama-server#$T/stub#g; s#/adapters#$T/adapters#g" > "$T/brain.sh"
check "compose: the llama-brain command was found" grep -q -- '--lora-scaled' "$T/brain.sh"
brain() {   # [VAR=value ...] — the compose environment passes every unset variable as empty
    rm -f "$T/args"
    env LLAMA_BRAIN_MODEL= LLAMA_BRAIN_CTX= LLAMA_BRAIN_PARALLEL= LLAMA_BRAIN_MLOCK= LLAMA_BRAIN_EXTRA_ARGS= \
        LLAMA_GPU_LAYERS= LLAMA_CACHE_RAM= LLAMA_CPU_MOE=24 LLAMA_CHAT_TEMPLATE_KWARGS= "$@" sh "$T/brain.sh" >/dev/null 2>&1
    [[ -f "$T/args" ]] || : > "$T/args"
}
brain
check "compose default: two slots"                                  has "$T/args" "--parallel 2 --kv-unified"
check "compose default: the pool is the 32768 window, not doubled"  has "$T/args" "--ctx-size 32768"
check "compose default: the floor is still adapter 0"               has "$T/args" "--lora-scaled $T/adapters/brain/species.gguf:1.0 --lora-scaled $T/adapters/brain/work.gguf:0.0 --lora-scaled $T/adapters/brainwrite/mia/current.gguf:0.0"
check "compose default: residency flag kept"                        has "$T/args" "--n-cpu-moe 24"
brain LLAMA_BRAIN_PARALLEL=1
check "compose LLAMA_BRAIN_PARALLEL=1: one slot"                    has "$T/args" "--parallel 1 --kv-unified"
check "compose LLAMA_BRAIN_PARALLEL=1: same window"                 has "$T/args" "--ctx-size 32768"
brain LLAMA_BRAIN_PARALLEL=4 LLAMA_BRAIN_CTX=16384
check "compose 4 slots on 16384: the pool is not multiplied"        has "$T/args" "--ctx-size 16384"
check "compose 4 slots on 16384: four slots"                        has "$T/args" "--parallel 4 --kv-unified"

# The rendered file agrees, when this machine has compose (it parses the YAML the way docker will).
if docker compose version >/dev/null 2>&1; then
    rendered="$(docker compose -f "$HERE/docker-compose.yml" --profile brain config --format json llama-brain 2>/dev/null \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["llama-brain"]["command"][0])' 2>/dev/null)"
    check "compose config: renders the two slots" grep -qF -- '--parallel $${LLAMA_BRAIN_PARALLEL:-2} --kv-unified' <<<"$rendered"
else
    echo "skip compose config (no docker compose here)"
fi

# ── the two-model servers are unchanged: one slot each, their window multiplied per slot ──
for svc in llama-server llama-voice; do
    check "compose $svc: no shared pool"         bash -c '! grep -q -- "--kv-unified" <<<"$1"' _ "$(service_block "$svc")"
    check "compose $svc: still one slot default" grep -qE -- '--parallel \$\$\{LLAMA_(SKILLS|VOICE)_PARALLEL:-1\}' <<<"$(service_block "$svc")"
done

# ── bin/wyrd _start_brain_native (macOS): lifted and run against the stub ──
eval "$(sed -n '/^_start_brain_native() {/,/^}/p' "$HERE/bin/wyrd")"
declare -F _start_brain_native >/dev/null || { echo "FAIL could not lift _start_brain_native"; exit 1; }
warn() { :; }; log() { :; }; _t() { echo "$*"; }
_brain_ram_mb() { echo 65536; }; plan_brain_residency_darwin() { echo gpu-full; }
find_llama_server() { echo "$T/stub"; }
MODELS_DIR="$T/models"; DATA_DIR="$T"; BRAIN_MODEL_DEFAULT="brain.gguf"; mkdir -p "$MODELS_DIR"; : > "$MODELS_DIR/brain.gguf"
rm -f "$T/args"
_start_brain_native; wait
[[ -f "$T/args" ]] || : > "$T/args"
check "macOS native: two slots"                     has "$T/args" "-np 2 --kv-unified"
check "macOS native: the 32768 window"              has "$T/args" "-c 32768"
check "macOS native: no single-slot leftover"       hasnt "$T/args" "-np 1"
check "macOS native: the floor is still adapter 0"  has "$T/args" "--lora-scaled $T/adapters/brain/species.gguf:1.0"

# ── the e2e harness serves the brain the way the product does ──
check "e2e harness: two slots on the shared pool" grep -q -- '-c 32768 .*--parallel 2 --kv-unified' "$HERE/scripts/training/brain/e2e_arm.sh"

exit $fail
