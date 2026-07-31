#!/usr/bin/env bash
# Live gate for the want→act BRIDGE, same gpu-host rig as the V6 control.
# Checks out ONLY the bridge files at a927eff8 (= 4032f436 + bridge), serves V6 9B :8210 + V10 4B :8211,
# runs AgencyBattery x3. Bar to beat: acted > 3.0 (V6 baseline) with over-eager still 0.
set -uo pipefail
cd /ndata2/wyrdsekai
LLAMA="$HOME/llama.cpp/build/bin/llama-server"
log(){ echo "[$(date +%H:%M:%S)] $*"; }

# 1. bring the bridge files (+ test) to a927eff8; HEAD/venv untouched.
log "fetching + checking out bridge files at a927eff8"
git fetch origin >/dev/null 2>&1
git checkout a927eff8 -- \
  core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java \
  core/src/main/java/org/wyrdsekai/core/agent/interiority/WantActBridge.java \
  core/src/test/java/org/wyrdsekai/core/agent/interiority/WantActBridgeTest.java || { log "CHECKOUT FAILED"; exit 2; }
test -f core/src/main/java/org/wyrdsekai/core/agent/interiority/WantActBridge.java || { log "BRIDGE FILE MISSING"; exit 2; }
log "bridge present; CompanionActor WantActBridge refs: $(grep -c WantActBridge core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java)"

# 2. serve V6 9B (:8210) + V10 4B (:8211) on free A6000s (llama.cpp uses nvidia-smi ordinals; 1,2 free).
pkill -f "llama-server.*821[01]" 2>/dev/null; sleep 2
log "starting 9B drive :8210 (CUDA 1)"
CUDA_VISIBLE_DEVICES=1 nohup "$LLAMA" -m "$HOME/wyrdsekai-3.5-9b-drive-v6-q4km.gguf" \
  --host 127.0.0.1 --port 8210 -ngl 99 -c 8192 --jinja > "$HOME/serve_9b.log" 2>&1 &
log "starting 4B voice :8211 (CUDA 2)"
CUDA_VISIBLE_DEVICES=2 nohup "$LLAMA" -m "$HOME/wyrdsekai-3.5-4b-v10-q4km.gguf" \
  --host 127.0.0.1 --port 8211 -ngl 99 -c 8192 --jinja > "$HOME/serve_4b.log" 2>&1 &

# 3. wait for health (up to 3 min each)
for p in 8210 8211; do
  ok=0
  for i in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:$p/health" >/dev/null 2>&1; then ok=1; log "port $p healthy"; break; fi
    sleep 3
  done
  [ "$ok" = 1 ] || { log "PORT $p NEVER CAME UP — aborting"; touch "$HOME/bridge_battery.done"; exit 3; }
done

# 4. run the battery x3
export WYRDSEKAI_E2E_BACKEND=llama-server WYRD_AGENCY_BATTERY=1
export WYRDSEKAI_INFERENCE_URL=http://localhost:8210
export WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211
for n in 1 2 3; do
  log "battery run $n starting"
  ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.AgencyBatteryE2ETest" --rerun-tasks \
    > "$HOME/bridge_battery$n.log" 2>&1
  log "battery run $n done; ENACT-RATE: $(grep -E 'ENACT-RATE' "$HOME/bridge_battery$n.log" | tail -1)"
done
touch "$HOME/bridge_battery.done"
log "ALL DONE"
