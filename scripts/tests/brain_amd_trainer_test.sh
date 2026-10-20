#!/usr/bin/env bash
# The night's trainer on an AMD GPU (0.5.8): the GPU's LLVM target is read from the kernel's compute
# topology (gfx_target_version 110501 → gfx1151), else from the PCI id of the chips households buy;
# the trainer's PyTorch comes from AMD's own release channel with the wheel built for that GPU, from
# the CUDA wheels on NVIDIA; `brain status` reads the last trainer check. Pure functions lifted
# from bin/wyrd against stand-in trees; no GPU. Run: bash scripts/tests/brain_amd_trainer_test.sh
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
for fn in _amd_card_dir _amd_gfx_target _brain_trainer_pip_torch _brain_trainer_check_line _t; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$W")"
done
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en
fail=0
check() {   # <label> <got> <want>
    if [[ "$2" == "$3" ]]; then echo "ok   $1 → $2"; else echo "FAIL $1 → $2 (want $3)"; fail=1; fi
}
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
unset WYRDSEKAI_AMD_GFX WYRDSEKAI_ROCM_WHEELS

# The kernel's compute topology: node 0 is the processor (0), node 1 the GPU.
mkdir -p "$T/kfd/0" "$T/kfd/1"
printf 'cpu_cores_count 16\ngfx_target_version 0\n' > "$T/kfd/0/properties"
printf 'cpu_cores_count 0\ngfx_target_version 110501\nmax_waves_per_simd 16\n' > "$T/kfd/1/properties"
WYRD_SYS_KFD="$T/kfd"; WYRD_SYS_DRM="$T/none"; mkdir -p "$T/none"
check "kfd 110501 → gfx1151 (Ryzen AI MAX)"   "$(_amd_gfx_target)"  gfx1151
printf 'gfx_target_version 90010\n' > "$T/kfd/1/properties"
check "kfd 90010 → gfx90a (the step in hex)"   "$(_amd_gfx_target)"  gfx90a
printf 'gfx_target_version 110000\n' > "$T/kfd/1/properties"
check "kfd 110000 → gfx1100"                   "$(_amd_gfx_target)"  gfx1100

# No compute topology (ROCm's kernel side absent): the PCI id of the card.
WYRD_SYS_KFD="$T/nokfd"
mkdir -p "$T/drm/card1/device"; echo 0x1002 > "$T/drm/card1/device/vendor"; echo 0x1586 > "$T/drm/card1/device/device"
echo 34359738368 > "$T/drm/card1/device/mem_info_vram_total"
WYRD_SYS_DRM="$T/drm"
check "PCI 1002:1586 → gfx1151"                "$(_amd_gfx_target)"  gfx1151
echo 0x1681 > "$T/drm/card1/device/device"
check "PCI 1002:1681 → gfx1035 (Radeon 680M)"  "$(_amd_gfx_target)"  gfx1035
echo 0xffff > "$T/drm/card1/device/device"
check "an unknown chip → nothing"              "$(_amd_gfx_target)"  ""
WYRDSEKAI_AMD_GFX=gfx1151
check "WYRDSEKAI_AMD_GFX says"                 "$(_amd_gfx_target)"  gfx1151
unset WYRDSEKAI_AMD_GFX

# Where the trainer's PyTorch comes from.
check "AMD: the device wheel from AMD's release channel" "$(_brain_trainer_pip_torch amd gfx1151)" \
      "--index-url https://stable.repo.amd.com/rocm/whl-next/ torch[device-gfx1151]"
WYRDSEKAI_ROCM_WHEELS=https://rc.repo.amd.com/rocm/whl-next/
check "AMD: another channel when asked"        "$(_brain_trainer_pip_torch amd gfx1100)" \
      "--index-url https://rc.repo.amd.com/rocm/whl-next/ torch[device-gfx1100]"
unset WYRDSEKAI_ROCM_WHEELS
check "NVIDIA: the CUDA wheels"                "$(_brain_trainer_pip_torch nvidia)" \
      "torch==2.11.0 --index-url https://download.pytorch.org/whl/cu130"

# The status line from the last check.
DATA_DIR="$T/data"; mkdir -p "$DATA_DIR/adapters/brainwrite"
check "no check yet"                           "$(_brain_trainer_check_line)" "$(_t brain.status.check_none)"
cat > "$DATA_DIR/adapters/brainwrite/trainer-check.json" <<'EOF'
{"when": "2026-10-19T23:40:12+00:00", "torch": "2.14.0+rocm10.1.0", "hip": "10.1", "device": "AMD Radeon 8060S Graphics", "ok": true, "reasons": []}
EOF
check "a pass"                                 "$(_brain_trainer_check_line)" "Trainer check: passed on AMD Radeon 8060S Graphics (2026-10-19 23:40)"
cat > "$DATA_DIR/adapters/brainwrite/trainer-check.json" <<'EOF'
{"when": "2026-10-19T23:50:00+00:00", "device": "AMD Radeon 8060S Graphics", "ok": false, "reasons": ["bf16 forward differs from fp32 by 0.31 (limit 0.025)"]}
EOF
out="$(_brain_trainer_check_line)"
check "a failure names the reason"             "$([[ "$out" == "Trainer check: FAILED on AMD Radeon 8060S Graphics (2026-10-19 23:50): bf16 forward differs from fp32 by 0.31 (limit 0.025)"* ]] && echo yes || echo "no: $out")" yes
out_es="$(WYRD_LOCALE=es _brain_trainer_check_line)"
check "and reads in Spanish"                   "$([[ "$out_es" == *"FALLÓ en AMD Radeon 8060S"* ]] && echo yes || echo no)" yes

[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
