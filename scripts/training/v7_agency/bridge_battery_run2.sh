#!/usr/bin/env bash
# Re-run of the want→act BRIDGE live gate after fix ①: drive-dominant resolution to AUTONOMOUS-
# SURFACE verbs (sending_stone/bear_the_wound/examine/emote/seek_sanctuary…) + pin the forced verb
# into the affordance surface. Files are rsync'd in place (NOT git-checked-out) — do not touch git.
set -uo pipefail
cd /ndata2/wyrdsekai
LLAMA="$HOME/llama.cpp/build/bin/llama-server"
log(){ echo "[$(date +%H:%M:%S)] $*"; }

log "bridge files in place; CompanionActor WantActBridge refs: $(grep -c WantActBridge core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java); surfaceByAffordance forcedPin: $(grep -c 'String forcedPin' core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java)"

pkill -f "llama-server.*821[01]" 2>/dev/null; sleep 2
log "starting 9B drive :8210 (CUDA 1)"
CUDA_VISIBLE_DEVICES=1 nohup "$LLAMA" -m "$HOME/wyrdsekai-3.5-9b-drive-v6-q4km.gguf" \
  --host 127.0.0.1 --port 8210 -ngl 99 -c 8192 --jinja > "$HOME/serve_9b.log" 2>&1 &
log "starting 4B voice :8211 (CUDA 2)"
CUDA_VISIBLE_DEVICES=2 nohup "$LLAMA" -m "$HOME/wyrdsekai-3.5-4b-v10-q4km.gguf" \
  --host 127.0.0.1 --port 8211 -ngl 99 -c 8192 --jinja > "$HOME/serve_4b.log" 2>&1 &

for p in 8210 8211; do
  ok=0
  for i in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:$p/health" >/dev/null 2>&1; then ok=1; log "port $p healthy"; break; fi
    sleep 3
  done
  [ "$ok" = 1 ] || { log "PORT $p NEVER CAME UP — aborting"; touch "$HOME/bridge2_battery.done"; exit 3; }
done

export WYRDSEKAI_E2E_BACKEND=llama-server WYRD_AGENCY_BATTERY=1
export WYRDSEKAI_INFERENCE_URL=http://localhost:8210
export WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211
for n in 1 2 3; do
  log "battery run $n starting"
  ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.AgencyBatteryE2ETest" --rerun-tasks \
    > "$HOME/bridge2_battery$n.log" 2>&1
  log "battery run $n done; ENACT-RATE: $(grep -E 'ENACT-RATE' "$HOME/bridge2_battery$n.log" | tail -1)"
done
touch "$HOME/bridge2_battery.done"
log "ALL DONE"
