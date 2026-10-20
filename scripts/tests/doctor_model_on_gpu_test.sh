#!/usr/bin/env bash
# `wyrd doctor` tells a companion model on the processor from one on the GPU by what the server
# loaded, not by the flag it was given (0.5.8: the bundled llama-server carries the Vulkan backend and
# asks for every layer on a host with a GPU; where the driver is missing it runs on the CPU with the
# flag). Stand-in /proc trees; lifts the rule and the layer rule from bin/wyrd.
# Run: bash scripts/tests/doctor_model_on_gpu_test.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
for fn in _local_model_on_cpu _llama_gpu_layers _doctor_gpu_unused _t; do eval "$(sed -n "/^${fn}() {/,/^}/p" "$W")"; done
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
warn() { echo "WARN: $*"; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export WYRD_PROC="$T/proc"
proc() {   # <pid> <cmdline words...> ; maps from stdin ("-" = unreadable)
    local pid="$1"; shift; mkdir -p "$WYRD_PROC/$pid"
    printf '%s\0' "$@" > "$WYRD_PROC/$pid/cmdline"
    local m; m="$(cat)"; [[ "$m" == "-" ]] || printf '%s\n' "$m" > "$WYRD_PROC/$pid/maps"
}
proc 100 llama-server --model /m/voice.gguf --port 11525 --n-gpu-layers 99 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-vulkan.so
7f01 r-xp /usr/lib/x86_64-linux-gnu/libvulkan_radeon.so
M
proc 101 llama-server --model /m/voice.gguf --port 11525 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-cpu-x64.so
M
proc 102 llama-server --model /m/voice.gguf --port 11525 --n-gpu-layers 99 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-vulkan.so
7f01 r-xp /opt/wyrdsekai/bin/libggml-cpu-x64.so
M
proc 103 llama-server -m /m/big.gguf --port 8200 -ngl 99 <<'M'
-
M
proc 104 llama-server --model /m/voice.gguf --port 11525 --n-gpu-layers 0 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-vulkan.so
7f01 r-xp /usr/lib/x86_64-linux-gnu/libvulkan_intel.so
M
proc 105 llama-server --model /m/drive.gguf --port 8200 --n-gpu-layers 99 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-cuda.so
7f01 r-xp /usr/lib/x86_64-linux-gnu/libcuda.so.1
M
proc 106 llama-server --model /m/voice.gguf --port 11525 --device none --parallel 1 <<'M'
7f00 r-xp /opt/wyrdsekai/bin/libggml-vulkan.so
7f01 r-xp /usr/lib/x86_64-linux-gnu/libvulkan_intel.so
M

_llama_server_pids() { echo 100; }
check "Vulkan backend and a driver mapped, layers asked: on the GPU"             '! _local_model_on_cpu'
_llama_server_pids() { echo 101; }
check "no layers asked: on the processor"                                        '_local_model_on_cpu'
_llama_server_pids() { echo 102; }
check "layers asked, Vulkan backend loaded, no driver: on the processor after all" '_local_model_on_cpu'
_llama_server_pids() { echo 103; }
check "maps not ours to read (a container's): the flag's word, on the GPU"       '! _local_model_on_cpu'
_llama_server_pids() { echo 104; }
check "zero layers asked: on the processor"                                      '_local_model_on_cpu'
_llama_server_pids() { echo 105; }
check "CUDA backend with libcuda: on the GPU"                                    '! _local_model_on_cpu'
_llama_server_pids() { echo 106; }
check "no device asked for (the processor on purpose): on the processor"         '_local_model_on_cpu'
_llama_server_pids() { printf '100\n101\n'; }
check "two servers, one on the processor: said"                                  '_local_model_on_cpu'
_llama_server_pids() { :; }
check "no server: nothing to say"                                                '! _local_model_on_cpu'

# The layer rule for the server the launcher starts by hand.
mkdir -p "$T/bin" "$T/drm/card0/device" "$T/drm/card0-eDP-1"; : > "$T/bin/llama-server"; : > "$T/bin/libggml-cpu-x64.so"
echo 0x1002 > "$T/drm/card0/device/vendor"
export WYRD_SYS_DRM="$T/drm"; unset WYRDSEKAI_GPU_LAYERS
check "a Radeon but a CPU-only build: 0"           '[[ "$(_llama_gpu_layers "$T/bin/llama-server")" == 0 ]]'
: > "$T/bin/libggml-vulkan.so"
check "the Vulkan backend beside it: every layer"  '[[ "$(_llama_gpu_layers "$T/bin/llama-server")" == 99 ]]'
check "WYRDSEKAI_GPU_LAYERS=0 keeps it off"        '[[ "$(WYRDSEKAI_GPU_LAYERS=0 _llama_gpu_layers "$T/bin/llama-server")" == 0 ]]'
# The doctor: a GPU in the host, the Vulkan backend beside the server, and a model on the processor all the same.
export WYRD_LLAMA_BIN="$T/bin/llama-server"
_llama_server_pids() { echo 102; }
out="$(_doctor_gpu_unused; echo "rc=$?")"
check "GPU idle beside a processor model: warned, names the unit and what to check" '[[ "$out" == *"WARN: a companion model runs on the processor although this computer has an AMD graphics unit"*"mesa-vulkan-drivers"*"renderD"*"rc=1" ]]'
_llama_server_pids() { echo 100; }
check "the model on the GPU: nothing to say"       '[[ "$(_doctor_gpu_unused; echo "rc=$?")" == "rc=0" ]]'
rm -rf "$T/drm/card0"; mkdir -p "$T/drm"
check "no GPU in the host: 0"                      '[[ "$(_llama_gpu_layers "$T/bin/llama-server")" == 0 ]]'
_llama_server_pids() { echo 101; }
check "no GPU in the host, a processor model: the host-load check's business, not this one's" '[[ "$(_doctor_gpu_unused; echo "rc=$?")" == "rc=0" ]]'

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
