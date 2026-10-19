#!/usr/bin/env bash
# The single brain on a GPU through Vulkan, live (2026-10-19): the published Vulkan image sees a
# real GPU (never the CPU fallback), the launcher plans for it, the compose service brings the
# 22 GB model up on it with the adapters, it answers a turn, and the numbers are written down.
# On the build box this runs on the Intel iGPU beside the NVIDIA card — the shape of a Ryzen AI
# MAX box (a GPU whose memory is the system's). Opt in: WYRD_BRAIN_VULKAN_LIVE=1. Needs docker,
# /dev/dri and the model under ${WYRDSEKAI_DATA:-/var/lib/wyrdsekai}/models. Port 18200.
set -u
[[ "${WYRD_BRAIN_VULKAN_LIVE:-}" == "1" ]] || { echo "skip: set WYRD_BRAIN_VULKAN_LIVE=1 to run the Vulkan brain live"; exit 0; }
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
DATA="${WYRDSEKAI_DATA:-/var/lib/wyrdsekai}"
MODEL="${LLAMA_BRAIN_MODEL:-Qwen3.6-35B-A3B-UD-Q4_K_M.gguf}"
PORT="${LLAMA_PORT:-18200}"
IMAGE=ghcr.io/ggml-org/llama.cpp:server-vulkan
command -v docker >/dev/null || { echo "skip: no docker"; exit 0; }
[[ -e /dev/dri ]] || { echo "skip: no /dev/dri"; exit 0; }
[[ -f "$DATA/models/$MODEL" ]] || { echo "skip: no $DATA/models/$MODEL"; exit 0; }
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
cleanup() {
    cd "$HERE" && LLAMA_PORT=$PORT WYRDSEKAI_DATA="$DATA" docker compose --profile brain-vulkan rm -sf llama-brain-vulkan >/dev/null 2>&1 || true
}
log() { echo "     [wyrd] $*"; }; warn() { echo "     [warn] $*"; }
trap cleanup EXIT

echo "== 1. the image's view of the GPUs"
devices="$(docker run --rm --device /dev/dri "$IMAGE" --list-devices 2>&1 | grep -E '^\s*Vulkan[0-9]+:' || true)"
printf '%s\n' "$devices" | sed 's/^/     /'
check 'a GPU is listed' '[[ "$devices" == *"Vulkan0:"* ]]'
check 'the CPU fallback is not a device' '[[ "$devices" != *"llvmpipe"* ]]'

echo "== 2. the launcher plans for it"
plan="$(cd "$HERE" && WYRDSEKAI_BRAIN_BACKEND=vulkan WYRDSEKAI_SERVING_PROFILE=single-sparse bin/wyrd brain plan 2>&1 | sed 's/\x1b\[[0-9;]*m//g')"
printf '%s\n' "$plan" | sed 's/^/     /'
check 'the hardware line names a GPU, not a card of 0 MB' '[[ "$plan" == *"GPU"* && "$plan" != *"0 MB VRAM"* ]]'
check 'the plan puts the whole model on it' '[[ "$plan" == *"the whole model fits on the card"* ]]'

echo "== 3. the brain comes up on it, through the launcher's own start"
cd "$HERE"
# The launcher's compose helper and what it leans on, lifted from bin/wyrd; the environment is what
# _start_brain_compose exports before it calls the helper.
eval "$(sed -n '/^BRAIN_CONTAINER=/p;/^BRAIN_FILE_MB=/p;/^BRAIN_LAYERS=/p' "$HERE/bin/wyrd")"
for fn in _drm_gpu_kind _brain_gpu_kind _amd_card_dir _brain_compose_up _brain_single_mem_limit _ensure_nvidia_container_runtime; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
done
export LLAMA_PORT=$PORT WYRDSEKAI_DATA="$DATA" LLAMA_BRAIN_MODEL="$MODEL" LLAMA_CPU_MOE=0 LLAMA_BRAIN_CTX=8192 WYRDSEKAI_BRAIN_BACKEND=vulkan
export LLAMA_BRAIN_MEM_LIMIT="$(_brain_single_mem_limit gpu-full "$(_brain_gpu_kind)")"
echo "     kind $(_brain_gpu_kind), cap $LLAMA_BRAIN_MEM_LIMIT"
if ! _brain_compose_up "$(_brain_gpu_kind)"; then echo "FAIL compose up"; exit 1; fi
check 'the Vulkan service is the one running' '[[ "$(docker inspect -f "{{index .Config.Labels \"com.docker.compose.service\"}}" wyrdsekai-llama-brain 2>/dev/null)" == "llama-brain-vulkan" ]]'
check 'the mapped file is not pinned twice' '[[ "$(docker inspect -f "{{range .Config.Env}}{{println .}}{{end}}" wyrdsekai-llama-brain | grep ^LLAMA_BRAIN_MLOCK=)" == "LLAMA_BRAIN_MLOCK=0" ]]'
t0=$(date +%s)
for _ in $(seq 1 60); do curl -sf -m 2 "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 5; done
ready=$(( $(date +%s) - t0 ))
check "healthy within four minutes (${ready}s)" '[[ $ready -lt 240 ]] && curl -sf -m 2 "http://127.0.0.1:$PORT/health" >/dev/null'
logs="$(docker logs wyrdsekai-llama-brain 2>&1)"
# The server's own log names no device on this image; the device list in step 1 is the evidence,
# and the memory the container holds says where the weights went: on a GPU with the system's
# memory they are charged to it (tens of GB), on a card they are not.
check 'the server did not fall back to the CPU' '[[ "$logs" != *"llvmpipe"* ]]'
if [[ -f "$DATA/adapters/brain/species.gguf" || -f "$DATA/adapters/brain/honesty.gguf" ]]; then
    check 'the floor adapter loaded' '[[ "$logs" == *"[brain] adapter 0:"* ]]'
fi
n_ctx="$(curl -sf -m 5 "http://127.0.0.1:$PORT/props" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("default_generation_settings",{}).get("n_ctx",0))' 2>/dev/null || echo 0)"
check "/props says the window ($n_ctx)" '[[ "$n_ctx" -ge 8192 ]]'
# Every adapter on disk is held by the server, the floor at 1.0: llama.cpp now calls repeated
# --lora-scaled deprecated ("only last value will be used") while still loading them all; the day
# it means it, this is where it shows.
want=0; for a in species honesty styled work; do [[ -f "$DATA/adapters/brain/$a.gguf" ]] && want=$((want+1)); done
[[ -f "$DATA/adapters/brain/species.gguf" && -f "$DATA/adapters/brain/honesty.gguf" ]] && want=$((want-1))   # one floor
for n in "$DATA"/adapters/brainwrite/*/current.gguf; do [[ -f "$n" ]] && want=$((want+1)); done
held="$(curl -sf -m 5 "http://127.0.0.1:$PORT/lora-adapters" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d), (d[0].get("scale") if d else "-"))' 2>/dev/null || echo "0 -")"
echo "     adapters held: ${held%% *} of $want wanted, the floor at ${held#* }"
check 'every adapter on disk is held by the server' '[[ "${held%% *}" -ge "$want" && "$want" -gt 0 ]]'
check 'the floor is adapter 0 at 1.0' '[[ "${held#* }" == "1.0" ]]'

echo "== 4. a turn, measured"
para="The household keeps a quiet record of the day: who came in, what was said, what she made of it, and what she set aside for later. "
prompt="$(for _ in $(seq 1 80); do printf '%s' "$para"; done)"
body="$(python3 -c 'import json,sys; print(json.dumps({"model":"brain","messages":[{"role":"user","content":sys.argv[1]+" Summarise that in two sentences."}],"max_tokens":96,"temperature":0}))' "$prompt")"
resp="$(curl -s -m 600 "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d "$body")"
line="$(printf '%s' "$resp" | python3 -c 'import json,sys
r=json.load(sys.stdin); t=r.get("timings",{}); c=(r.get("choices") or [{}])[0].get("message",{}).get("content","")
print("prompt %d tok at %.0f tok/s (%.1f s) | generated %d tok at %.1f tok/s | %r" % (t.get("prompt_n",0), t.get("prompt_per_second",0), t.get("prompt_ms",0)/1000, t.get("predicted_n",0), t.get("predicted_per_second",0), c[:60]))' 2>/dev/null || echo "no answer")"
echo "     $line"
check 'the turn completed with words' '[[ "$line" == prompt* && "$line" != *"generated 0 tok"* ]]'
[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
