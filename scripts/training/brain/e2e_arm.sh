#!/bin/bash
# Build box: the product's live-model e2e suites (the 9B's acceptance gate) against one candidate adapter, served
# the way the product serves slot 0 (applied to every request). Usage: e2e_arm.sh <arm.gguf> <label> [scale=1.0]
# Numbers to beat (2026-09-21, same suites): shipped honesty adapter 61/63, 9B+4B 53/63, bare 35B 48/63.
set -u
GGUF=${1:?adapter gguf}; LABEL=${2:?label}; SCALE=${3:-1.0}; WORK=${4:-}
D=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$D/../../.." && pwd); G="$REPO/data/training/brain"
PORT=10084; URL=http://127.0.0.1:$PORT; IMG=ghcr.io/ggml-org/llama.cpp:server-cuda
cp "$GGUF" "$G/e2e_$LABEL.gguf"
# A 4th argument serves a working-turn adapter beside slot 0, at the path the product recognises (…/brain/work.gguf).
WORK_ARGS=(); if [[ -n "$WORK" ]]; then mkdir -p "$G/brain"; cp "$WORK" "$G/brain/work.gguf"; WORK_ARGS=(--lora-scaled /s/brain/work.gguf:0.0); fi
docker rm -f brain-e2e >/dev/null 2>&1
docker run -d --name brain-e2e --gpus all -p 127.0.0.1:$PORT:8080 --ulimit memlock=-1 -v ~/models:/models:ro -v "$G":/s:ro $IMG \
  -m /models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf --n-cpu-moe 24 -c 32768 -b 2048 -ub 2048 --parallel 2 --kv-unified -ngl 99 \
  --lora-scaled "/s/e2e_$LABEL.gguf:$SCALE" "${WORK_ARGS[@]}" --jinja --reasoning off --reasoning-budget 0 --flash-attn on --metrics --host 0.0.0.0 --port 8080 >/dev/null
for i in $(seq 1 120); do curl -sf -o /dev/null $URL/health && break; sleep 3; done
cd "$REPO"
export WYRDSEKAI_SERVING_PROFILE=single-sparse WYRDSEKAI_E2E_BACKEND=llama-server WYRDSEKAI_E2E_LLAMA_PORT=$PORT WYRDSEKAI_E2E_VOICE_PORT=$PORT WYRDSEKAI_INFERENCE_URL=$URL
# WYRDSEKAI_INFERENCE_URL: the product's own inference URL, which under single-sparse is where it asks for the
# served adapter list (/lora-adapters) and sends the decision/description passes; without it the product asked
# the box's :8200 and a per-request adapter list was never sent (found 2026-09-26 on the working-floor run).
# The coding tasks the suites dispatch think on THIS server, not on whatever the build box's
# codezaiku config points at (it pointed at another machine's dev proxy, 2026-09-25).
export WYRDSEKAI_CODING_CODEZAIKU_DRIVE_URL=$URL WYRDSEKAI_CODING_CODEZAIKU_MODEL=/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf
SUITES="SubstrateArcE2ETest SoulSubstrateE2ETest MemoryE2ETest EconomyE2ETest PersonhoodActionsLiveE2ETest DepartureReturnRitualE2ETest ToolSelectionE2ETest EmberProgressiveTasksE2ETest VoiceRouteClassifierE2ETest SubstrateGroupCDeterministicE2ETest GoldenPathE2ETest"
out="$G/e2e_$LABEL"; mkdir -p "$out/xml"
for s in $SUITES; do
  echo "=== $s $(date -Is)" >> "$out/run.log"
  timeout 5400 ./gradlew --offline -q :e2e-test:test --tests "*.$s" --rerun-tasks > "$out/$s.out" 2>&1; echo "exit $? $s" >> "$out/run.log"
  cp e2e-test/build/test-results/test/TEST-*$s*.xml "$out/xml/" 2>/dev/null
done
docker rm -f brain-e2e >/dev/null 2>&1
python3 - "$out/xml" <<'EOF'
import sys, glob, xml.etree.ElementTree as ET
t = f = e = s = 0; per = []
for p in sorted(glob.glob(sys.argv[1] + "/*.xml")):
    r = ET.parse(p).getroot(); n, ff, ee, ss = (int(r.get(k)) for k in ("tests", "failures", "errors", "skipped"))
    t += n; f += ff; e += ee; s += ss; per.append(f"{r.get('name').split('.')[-1]} {n - ff - ee - ss}/{n - ss}")
print(" · ".join(per)); print(f"TOTAL {t - f - e - s}/{t - s}")
EOF
echo "done $(date -Is)" > "$out/done"
