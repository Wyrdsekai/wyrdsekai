#!/bin/bash
# The ROCm trainer proof on a rented AMD box. Runs ON the pod as whatever user it gives us.
# Phases: facts → the launcher's own GPU reader → venv with AMD's PyTorch for this chip → llama.cpp
# checkout → the served model + the training bundle (checksummed) → trainer_check.py → a made-up
# fortnight → one night's write with its gate → summary. Everything under $W; log beside it.
set -uo pipefail
W="${W:-$([ -d /workspace ] && echo /workspace/wyrd || echo "$HOME/wyrd")}"
SRC="${SRC:-$HOME/wyrd-src}"            # bin/wyrd + scripts/training/sleepwrite/*.py + pod/ (rsynced by rocm_rental_run.sh)
export WYRDSEKAI_DATA_DIR="$W/data" WYRDSEKAI_LOCALE=en
mkdir -p "$W/data/models" "$W/data/data" "$W/data/sleepwrite" "$W/data/adapters/brainwrite"
SUM="$W/summary.json"; : > "$W/phases.log"
say() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$W/phases.log"; }
SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo -n"
t_all=$(date +%s)

say "== 1. the machine"
( rocminfo 2>/dev/null | grep -m3 -E 'Name:\s+gfx|Marketing Name' ; rocm-smi --showproductname 2>/dev/null | grep -i 'card series' | head -2 ) | sed 's/^/     /'
grep -h gfx_target_version /sys/class/kfd/kfd/topology/nodes/*/properties 2>/dev/null | sort -u | sed 's/^/     kfd: /'
python3 --version | sed 's/^/     /'; nproc | sed 's/^/     cores: /'; free -g | awk 'NR==2{print "     ram: "$2" GB"}'

say "== 2. the launcher's GPU reader on real hardware"
GFX="$(bash -c "$(sed -n '/^_amd_card_dir() {/,/^}/p; /^_amd_gfx_target() {/,/^}/p' "$SRC/bin/wyrd"); _amd_gfx_target")"
say "     _amd_gfx_target → ${GFX:-<nothing>}"
[ -n "$GFX" ] || { say "FAIL the launcher could not name this GPU"; exit 1; }

say "== 3. the trainer's environment (AMD's PyTorch for $GFX)"
if ! python3 -m venv --help >/dev/null 2>&1 || ! python3 -m venv "$W/venv" 2>/dev/null; then
    $SUDO apt-get update -qq >/dev/null 2>&1; $SUDO apt-get install -y -qq python3-venv git curl >/dev/null 2>&1
    python3 -m venv "$W/venv" || { say "FAIL no venv"; exit 1; }
fi
PY="$W/venv/bin/python"; PIP="$W/venv/bin/pip"
$PIP install -q --upgrade pip
t0=$(date +%s)
$PIP install -q --index-url https://stable.repo.amd.com/rocm/whl-next/ "torch[device-$GFX]" 2>&1 | tail -2 | sed 's/^/     /'
$PIP install -q "transformers==5.15.0" "peft==0.20.0" "accelerate==1.14.0" safetensors gguf sentencepiece numpy 2>&1 | tail -1 | sed 's/^/     /'
$PIP install -q "flash-linear-attention==0.5.2" "fla-core==0.5.2" 2>/dev/null || say "     flash-linear-attention skipped (the model's own path is used)"
say "     installed in $(( $(date +%s) - t0 )) s"
$PY - <<'PYV' 2>&1 | sed 's/^/     /'
import torch; print("torch", torch.__version__, "hip", torch.version.hip, "cuda-api", torch.cuda.is_available())
if torch.cuda.is_available(): print("device", torch.cuda.get_device_name(0), "free/total MiB", [x//2**20 for x in torch.cuda.mem_get_info(0)])
import transformers, peft; print("transformers", transformers.__version__, "peft", peft.__version__)
try:
    import fla; print("fla", getattr(fla, "__version__", "present"))
except Exception as e: print("fla absent:", type(e).__name__)
PYV

say "== 4. llama.cpp checkout (gguf-py, the adapter converter)"
[ -d "$W/data/sleepwrite/llama.cpp/gguf-py" ] || git clone -q --depth 1 https://github.com/ggml-org/llama.cpp "$W/data/sleepwrite/llama.cpp"
say "     $(git -C "$W/data/sleepwrite/llama.cpp" log -1 --format='%h %ad' --date=short)"

say "== 5. the served model and the training bundle"
GGUF_URL="https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/a483e9e6cbd595906af30beda3187c2663a1118c/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"
GGUF_SHA=ac0e2c1189e055faa36eff361580e79c5bd6f8e76bffb4ce547f167d53e31a61
BUNDLE_URL="https://wyrdsekai.org/models/wyrdsekai-3.6-35b-a3b-train-spine-v1.tar"
BUNDLE_SHA=ff6e8b236f37411e546d2c221485c6972f6b506fb7efc9c140df3627a7b53191
fetch() {   # <url> <dest> <sha>
    [ -f "$2" ] && [ "$(sha256sum "$2" | cut -d' ' -f1)" = "$3" ] && { say "     $(basename "$2") already here"; return 0; }
    t0=$(date +%s)
    if command -v aria2c >/dev/null; then aria2c -q -x8 -s8 -c -o "$(basename "$2")" -d "$(dirname "$2")" "$1" || return 1
    else curl -fL -C - --retry 5 --retry-delay 5 -o "$2" "$1" || return 1; fi
    local got; got="$(sha256sum "$2" | cut -d' ' -f1)"
    [ "$got" = "$3" ] || { say "FAIL checksum of $(basename "$2"): $got"; return 1; }
    say "     $(basename "$2"): $(du -h "$2" | cut -f1) in $(( $(date +%s) - t0 )) s, checksum good"
}
fetch "$GGUF_URL" "$W/data/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf" "$GGUF_SHA" &
P1=$!
fetch "$BUNDLE_URL" "$W/bundle.tar" "$BUNDLE_SHA" && { mkdir -p "$W/data/models/brain-bundle"; [ -f "$W/data/models/brain-bundle/bundle.json" ] || tar -xf "$W/bundle.tar" -C "$W/data/models/brain-bundle" --strip-components=1; say "     bundle: $(python3 -c "import json;d=json.load(open('$W/data/models/brain-bundle/bundle.json'));print(d.get('class'),d.get('layers'),'layers, base',d.get('base'))")"; }
wait $P1 || { say "FAIL the served model did not arrive"; exit 1; }

say "== 6. wyrd brain check — the machine proves its arithmetic"
t0=$(date +%s)
WYRDSEKAI_SLEEP_WRITE_LLAMACPP="$W/data/sleepwrite/llama.cpp" $PY "$SRC/scripts/training/sleepwrite/trainer_check.py" 2>&1 | tee "$W/trainer-check.log" | sed 's/^/     /'
CHECK_RC=${PIPESTATUS[0]}; say "     trainer_check exit $CHECK_RC in $(( $(date +%s) - t0 )) s"

say "== 7. a made-up fortnight"
python3 "$SRC/pod/make_corpus.py" "$W/data/data/agent-activity.jsonl" 420 | sed 's/^/     /'

say "== 8. one night's write, with its gate"
t0=$(date +%s)
( cd "$SRC/scripts/training/sleepwrite" && WYRDSEKAI_SLEEP_WRITE_AGENT_ID=companion-wren WYRDSEKAI_SLEEP_WRITE_PAUSE_VOICE=false \
  WYRDSEKAI_BRAIN_WRITE_SINCE=2026-10-01T00:00:00+00:00 WYRDSEKAI_SLEEP_WRITE_LLAMACPP="$W/data/sleepwrite/llama.cpp" \
  $PY brain_write.py ) 2>&1 | tee "$W/brain-write.log" | grep -E '^\[brainwrite\]|Error|error|Traceback' | sed 's/^/     /'
WRITE_RC=${PIPESTATUS[0]}; say "     brain_write exit $WRITE_RC in $(( ($(date +%s) - t0) / 60 )) min"
OUTDIR="$W/data/adapters/brainwrite/companion-wren"
ls -la "$OUTDIR"/*.gguf "$OUTDIR"/last-result.json 2>/dev/null | awk '{print "     "$5, $9}'

say "== 9. summary"
python3 - "$W" "$GFX" "$CHECK_RC" "$WRITE_RC" "$t_all" <<'PYS' | tee "$SUM"
import json, sys, os, time, pathlib
W, gfx, check_rc, write_rc, t_all = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
def j(p):
    try: return json.load(open(p))
    except Exception: return None
check = j(f"{W}/data/adapters/brainwrite/trainer-check.json") or {}
res = j(f"{W}/data/adapters/brainwrite/companion-wren/last-result.json") or {}
out = {"gfx": gfx, "trainer_check": {"ok": check.get("ok"), "device": check.get("device"), "torch": check.get("torch"), "hip": check.get("hip"),
       "checks": {k: check.get("checks", {}).get(k) for k in ("bf16_forward_rel","sdpa_rel","bf16_backward_rel","gguf_worst_abs","gguf_formats","fla")}, "reasons": check.get("reasons"), "exit": check_rc},
       "night_write": {"exit": write_rc, **{k: res.get(k) for k in ("gate","steps","minutes","peak_vram_gib","holdout_delta","neutral_delta","tic_delta","lines","experts_from","adapter")}},
       "adapter_bytes": next((os.path.getsize(p) for p in pathlib.Path(f"{W}/data/adapters/brainwrite/companion-wren").glob("adapter-*.gguf")), None),
       "wall_minutes": round((time.time() - t_all) / 60, 1)}
print(json.dumps(out, indent=1))
PYS
say "DONE"
