#!/usr/bin/env bash
# The bundled llama-server on a GPU through Vulkan, live (0.5.8): the small models ride the GPU on a
# host without Docker — a Ryzen AI MAX box, an Intel laptop. The 4B voice model comes up with every
# layer on the GPU and answers a turn; the server's own log says the layers went; the doctor's rule
# sees a model on the GPU, not the processor; then the same turn on the processor, for the numbers.
# On the build box the integrated GPU is chosen over the NVIDIA card (GGML_VK_VISIBLE_DEVICES), the
# household shape. Opt in: WYRD_LLAMA_VULKAN_LIVE=1. Needs packaging/llama-server (fetch-llama-cpu.sh)
# with libggml-vulkan.so beside it and the 4B under ${WYRDSEKAI_DATA:-/var/lib/wyrdsekai}/models. Port 18201.
set -u
[[ "${WYRD_LLAMA_VULKAN_LIVE:-}" == "1" ]] || { echo "skip: set WYRD_LLAMA_VULKAN_LIVE=1 to run the bundled server on Vulkan live"; exit 0; }
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
# The integrated GPU's render node belongs to group render. A user who is in it on paper but not
# in this session (added after login) runs the test under that group, once.
if [[ -z "${WYRD_LLAMA_VULKAN_LIVE_SG:-}" ]] && ! id -nG | grep -qw render && getent group render | grep -qE "[:,]$(id -un)(,|$)" && command -v sg >/dev/null; then
    export WYRD_LLAMA_VULKAN_LIVE_SG=1
    exec sg render -c "bash '$0'"
fi
BIN="${WYRD_LLAMA_SERVER:-$HERE/packaging/llama-server}"
DATA="${WYRDSEKAI_DATA:-/var/lib/wyrdsekai}"
MODEL="${WYRD_SMALL_MODEL:-wyrdsekai-3.5-4b-v10-q4km.gguf}"
PORT="${LLAMA_PORT:-18201}"
[[ -x "$BIN" ]] || { echo "skip: no $BIN (packaging/fetch-llama-cpu.sh)"; exit 0; }
[[ -f "$(dirname "$BIN")/libggml-vulkan.so" ]] || { echo "skip: no Vulkan backend beside $BIN"; exit 0; }
[[ -f "$DATA/models/$MODEL" ]] || { echo "skip: no $DATA/models/$MODEL"; exit 0; }
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
SRV=""
cleanup() { [[ -n "$SRV" ]] && kill "$SRV" 2>/dev/null; wait "$SRV" 2>/dev/null; }
trap cleanup EXIT
T="$(mktemp -d)"
for fn in _local_model_on_cpu; do eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"; done

echo "== 1. the GPUs the server sees"
# An integrated GPU over the NVIDIA card where this user can see one (the household shape); never
# the CPU software device (llvmpipe), which the server itself leaves out unless told to use it.
pick=""; name=""
for i in 0 1 2 3; do
    line="$(GGML_VK_VISIBLE_DEVICES=$i timeout 60 "$BIN" --list-devices 2>/dev/null | grep -E '^\s*Vulkan0:' || true)"
    [[ -z "$line" ]] && continue
    echo "     device $i: ${line#*: }"
    [[ "$line" == *llvmpipe* ]] && continue
    if [[ -z "$pick" || ( "$name" == *NVIDIA* && "$line" != *NVIDIA* ) ]]; then pick=$i; name="${line#*: }"; fi
done
[[ -z "$pick" ]] && { echo "FAIL no Vulkan GPU visible to $(id -un) (render group? $(id -nG))"; exit 1; }
export GGML_VK_VISIBLE_DEVICES=$pick
echo "     chosen: device $pick, $name"
[[ "$name" == *NVIDIA* ]] && echo "     (no integrated GPU visible to $(id -un); groups: $(id -nG))"
check 'the chosen device is a GPU, not the CPU fallback' '[[ "$name" != *llvmpipe* ]]'

serve() {   # <ngl> <log> → SRV, waits for health. Zero layers = no device, as the node and the unit do.
    local gpu=(--n-gpu-layers "$1"); [[ "$1" == 0 ]] && gpu=(--device none)
    "$BIN" --model "$DATA/models/$MODEL" --port "$PORT" "${gpu[@]}" --ctx-size 8192 --parallel 1 \
        --cache-ram 1024 --jinja --reasoning off --reasoning-budget 0 > "$2" 2>&1 &
    SRV=$!
    local i; for i in $(seq 1 60); do curl -sf -m 2 "http://127.0.0.1:$PORT/health" >/dev/null && return 0; kill -0 "$SRV" 2>/dev/null || break; sleep 2; done
    return 1
}
turn() {   # → "prompt_tps gen_tps words"
    curl -s -m 180 -X POST "http://127.0.0.1:$PORT/completion" -H 'Content-Type: application/json' \
        -d '{"prompt":"Write three short lines about a quiet morning in a kitchen.","n_predict":64,"temperature":0}' \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); t=d.get("timings",{}); print(round(t.get("prompt_per_second",0),1), round(t.get("predicted_per_second",0),1), len((d.get("content") or "").split()))'
}
_llama_server_pids() { echo "$SRV"; }

echo "== 2. every layer on the GPU"
t0=$(date +%s)
check "healthy within two minutes" 'serve 99 "$T/gpu.log"'
echo "     up in $(( $(date +%s) - t0 )) s"
offl="$(grep -oE 'offloaded [0-9]+/[0-9]+ layers' "$T/gpu.log" | tail -1)"
echo "     log: ${offl:-no offload line}"
check "the server's log says every layer went" '[[ -n "$offl" ]] && [[ "$(cut -d" " -f2 <<<"$offl" | cut -d/ -f1)" == "$(cut -d" " -f2 <<<"$offl" | cut -d/ -f2)" ]]'
read -r gpp gtps gwords <<<"$(turn)"
echo "     GPU: prompt $gpp tok/s, generation $gtps tok/s, $gwords words back"
check "it answers a turn" '[[ "${gwords:-0}" -gt 3 ]]'
check "the doctor's rule: a model on the GPU, not the processor" '! _local_model_on_cpu'
kill "$SRV"; wait "$SRV" 2>/dev/null; SRV=""

echo "== 3. the same on the processor"
check "the CPU server comes up" 'serve 0 "$T/cpu.log"'
read -r cpp ctps cwords <<<"$(turn)"
echo "     CPU: prompt $cpp tok/s, generation $ctps tok/s, $cwords words back"
check "the doctor's rule: a model on the processor" '_local_model_on_cpu'
kill "$SRV"; wait "$SRV" 2>/dev/null; SRV=""
echo "     $name: generation ${gtps} tok/s on the GPU vs ${ctps} tok/s on the processor; prompt ${gpp} vs ${cpp}"
rm -rf "$T"
[[ $fail -eq 0 ]] && echo "all passed" || { echo "some failed"; exit 1; }
