#!/usr/bin/env bash
# The single brain on AMD and Intel GPUs (2026-10-19). Table test for what the launcher reads
# from the kernel's view of the cards (/sys/class/drm) and the plan that follows: the AMD GPU
# plans like a card from the memory the BIOS set aside for it; an Intel iGPU plans from the
# system's memory; the CPU fallback is never a GPU; WYRDSEKAI_BRAIN_BACKEND picks the path on a
# host with both. Pure functions lifted from bin/wyrd against stand-in sysfs trees; no GPU.
# Run: bash scripts/tests/brain_gpu_kind_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
eval "$(sed -n '/^BRAIN_LAYERS=/p;/^BRAIN_VRAM_HEADROOM_MB=/p;/^BRAIN_RAM_FLOOR_MB=/p;/^BRAIN_VOICE_RESERVE_MB=/p;/^BRAIN_DARWIN_RAM_MIN_MB=/p;/^BRAIN_UNIFIED_RAM_MIN_MB=/p' "$W")"
eval "$(sed -n '/^BRAIN_FILE_MB=/p;/^BRAIN_SPINE_VRAM_MB=/p;/^BRAIN_LAYER_MB=/p' "$W")"
for fn in _drm_gpu_kind _brain_gpu_kind _amd_card_dir _amd_mem_mb _brain_amd_vram_mb _brain_vram_others_mb \
          _brain_amd_gtt_mb _brain_amd_name _brain_vram_mb plan_brain_residency plan_for_profile \
          plan_brain_residency_darwin plan_brain_residency_unified _brain_plan_for_kind _brain_single_mem_limit; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$W")"
done
uname() { echo Linux; }
fail=0
check() {   # <label> <got> <want>
    if [[ "$2" == "$3" ]]; then echo "ok   $1 → $2"; else echo "FAIL $1 → $2 (want $3)"; fail=1; fi
}
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
card() {   # <tree> <card> <vendor> [device] [vram_bytes] [gtt_bytes] [used_bytes]
    local d="$T/$1/$2/device"; mkdir -p "$d"
    echo "$3" > "$d/vendor"; [[ -n "${4:-}" ]] && echo "$4" > "$d/device"
    [[ -n "${5:-}" ]] && echo "$5" > "$d/mem_info_vram_total"
    [[ -n "${6:-}" ]] && echo "$6" > "$d/mem_info_gtt_total"
    [[ -n "${7:-}" ]] && echo "$7" > "$d/mem_info_vram_used"
    return 0
}
# The household's Ryzen AI MAX+ 395 (readings sent 2026-10-19): 32 GiB set aside, 48 GiB to borrow.
card kuebiko card0 0x1002
card kuebiko card1 0x1002 0x1586 34359738368 50498011136 1436549120
mkdir -p "$T/kuebiko/card1-DP-1"; ln -s ../card1 "$T/kuebiko/card1-DP-1/device"
# The household node: an NVIDIA card beside a Radeon 680M with 1 GiB set aside.
card second-node card0 0x10de
card second-node card1 0x1002 0x1681 1073741824 16106127360 268435456
# The build box: an Intel iGPU beside an NVIDIA card.
card home-server card0 0x8086 0x7d55
card home-server card1 0x10de
mkdir -p "$T/none"

nvidia-smi() { return 1; }   # no NVIDIA tool answers unless a case says so
unset WYRDSEKAI_BRAIN_BACKEND

WYRD_SYS_DRM="$T/kuebiko"
check "kuebiko: kind"                      "$(_brain_gpu_kind)"            amd
check "kuebiko: set aside for the GPU"     "$(_brain_vram_mb)"             32768
check "kuebiko: to borrow"                 "$(_brain_amd_gtt_mb)"          48158
check "kuebiko: the carve-out is the brain's, whatever is in use (the plan read a stopped container's share, 2026-10-19)" "$(_brain_vram_others_mb)" 0
check "kuebiko: name"                      "$(_brain_amd_name)"            "AMD GPU 1586"
check "kuebiko: plan, whole model on the GPU" "$(_brain_plan_for_kind single-sparse 31398 96318 amd)" gpu-full
check "kuebiko: the voice beside it"       "$(_brain_plan_for_kind sparse-drive 31398 96318 amd)"  gpu-full
check "amd: 8 GB set aside → most experts in RAM" "$(_brain_plan_for_kind single-sparse 8192 96318 amd)" 36
check "amd: 4 GB set aside → not viable"   "$(_brain_plan_for_kind single-sparse 4096 96318 amd)" not-viable

WYRD_SYS_DRM="$T/second-node"
check "second-node without nvidia-smi: the 680M"  "$(_brain_gpu_kind)"            amd
check "second-node: 1 GiB set aside → not viable" "$(_brain_plan_for_kind single-sparse "$(_brain_vram_mb)" 30820 amd)" not-viable
nvidia-smi() { echo "NVIDIA GeForce RTX 5060 Ti, 16311"; }
check "second-node with nvidia-smi: nvidia"       "$(_brain_gpu_kind)"            nvidia
nvidia-smi() { echo "nvidia-smi not found. This is AMD country."; }   # a rented AMD box's stub, exit 0 (2026-10-20)
check "a stub nvidia-smi that talks is not a card: the AMD GPU"  "$(_brain_gpu_kind)"  amd
WYRDSEKAI_BRAIN_BACKEND=vulkan
check "second-node, Vulkan forced: the 680M"      "$(_brain_gpu_kind)"            amd
unset WYRDSEKAI_BRAIN_BACKEND
nvidia-smi() { return 1; }

WYRD_SYS_DRM="$T/home-server"
check "home-server without nvidia-smi: intel"     "$(_brain_gpu_kind)"            intel
check "intel: no memory of its own"        "$(_brain_vram_mb)"             0
check "intel + 64 GB → whole model on the GPU" "$(_brain_plan_for_kind single-sparse 0 65536 intel)" gpu-full
check "intel + 32 GB → not viable"         "$(_brain_plan_for_kind single-sparse 0 32768 intel)" not-viable
nvidia-smi() { echo "NVIDIA GeForce RTX 4060 Ti, 16311"; }
check "home-server: nvidia first"                 "$(_brain_gpu_kind)"            nvidia
WYRDSEKAI_BRAIN_BACKEND=vulkan
check "home-server, Vulkan forced: the Intel iGPU" "$(_brain_gpu_kind)"           intel
WYRDSEKAI_BRAIN_BACKEND=cuda
check "cuda forced"                        "$(_brain_gpu_kind)"            nvidia
unset WYRDSEKAI_BRAIN_BACKEND
nvidia-smi() { return 1; }

WYRD_SYS_DRM="$T/none"
check "no cards: none"                     "$(_brain_gpu_kind)"            none
check "none: not viable"                   "$(_brain_plan_for_kind single-sparse 0 96318 none)" not-viable
WYRD_SYS_DRM="$T/missing"
check "no drm tree: none"                  "$(_brain_gpu_kind)"            none
WYRDSEKAI_BRAIN_BACKEND=vulkan
check "Vulkan forced with no card: intel (the device list decides)" "$(_brain_gpu_kind)" intel
unset WYRDSEKAI_BRAIN_BACKEND

# The container's memory cap: a card keeps the file's size plus 2 GB; a GPU that uses the system's
# memory holds its working memory there too, and gets 8 GB of room.
check "cap on a card"                      "$(_brain_single_mem_limit gpu-full nvidia)"  "$(( BRAIN_FILE_MB + 1024 + 2048 ))m"
check "cap on an AMD unit"                 "$(_brain_single_mem_limit gpu-full amd)"     "$(( BRAIN_FILE_MB + 1024 + 8192 ))m"
check "cap on an Intel unit"               "$(_brain_single_mem_limit 36 intel)"        "$(( BRAIN_FILE_MB + 1024 + 8192 ))m"

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
