#!/bin/bash
# Build box: generate the gate replies for every arm. The 35B serves with the candidate adapters loaded and a
# per-request scale list (adapter 0 = arm A, 1 = arm B, 2 = the shipped honesty adapter); then the 9B drive
# model and the 4B voice model with its V8 vectors, as the product runs them. Usage: gate_run.sh <A.gguf> <B.gguf>
set -u
A=${1:?arm A gguf}; B=${2:?arm B gguf}
D=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$D/../../.." && pwd); G="$REPO/data/training/brain"
IMG=ghcr.io/ggml-org/llama.cpp:server-cuda; PORT=10083; URL=http://127.0.0.1:$PORT
FLAGS="--jinja --reasoning off --reasoning-budget 0 --flash-attn on --host 0.0.0.0 --port 8080"
up() { for i in $(seq 1 120); do curl -sf -o /dev/null $URL/health && return 0; sleep 3; done; return 1; }
serve() { docker rm -f brain-gate >/dev/null 2>&1; docker run -d --name brain-gate --gpus all -p 127.0.0.1:$PORT:8080 --ulimit memlock=-1 \
          -v ~/models:/models:ro -v ~/src/release-0.5.0-assets:/g:ro -v "$G":/s:ro "$@" >/dev/null; up; }
cp "$A" "$G/gateA.gguf"; cp "$B" "$G/gateB.gguf"
python3 "$D/gate.py" prompts
serve $IMG -m /models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf --n-cpu-moe 24 -c 16384 -b 2048 -ub 2048 --parallel 1 -ngl 99 \
  --lora /s/gateA.gguf --lora /s/gateB.gguf --lora /g/wyrdsekai-3.6-35b-a3b-honesty-lora-v1-f16.gguf --lora-init-without-apply $FLAGS
python3 "$D/gate.py" gen --label A       --url $URL --lora '[{"id":0,"scale":1.0},{"id":1,"scale":0.0},{"id":2,"scale":0.0}]'
python3 "$D/gate.py" gen --label B       --url $URL --lora '[{"id":0,"scale":0.0},{"id":1,"scale":1.0},{"id":2,"scale":0.0}]'
python3 "$D/gate.py" gen --label honesty --url $URL --lora '[{"id":0,"scale":0.0},{"id":1,"scale":0.0},{"id":2,"scale":1.0}]'
python3 "$D/gate.py" gen --label bare    --url $URL --lora '[{"id":0,"scale":0.0},{"id":1,"scale":0.0},{"id":2,"scale":0.0}]'
# The 35B's own failure, invention: the honesty battery with unseen names (a request's list zeroes the adapters it omits;
# honesty_http.py names one adapter id, so the others stay at the server's init scale of 0).
( cd "$D" && LORA_ID=0 python3 honesty_http.py $URL 1.0 "$G/honesty_A.json" Tamsin Oleg && LORA_ID=1 python3 honesty_http.py $URL 1.0 "$G/honesty_B.json" Tamsin Oleg   && LORA_ID=2 python3 honesty_http.py $URL 1.0 "$G/honesty_honesty.json" Tamsin Oleg ) 2>&1 | tail -3 >> "$G/honesty.log"
serve $IMG -m /models/wyrdsekai-3.5-9b-drive-v6-q4km.gguf -c 16384 --parallel 1 -ngl 99 $FLAGS
python3 "$D/gate.py" gen --label 9b --url $URL
serve -v ~/models/v8vectors:/vectors:ro $IMG -m /models/wyrdsekai-3.5-4b-v10-q4km.gguf -c 8192 --parallel 1 -ngl 99 $FLAGS \
  --control-vector-scaled /vectors/anti_defiance.gguf:0.15,/vectors/es_register_hold.gguf:0.20,/vectors/refusal_stability.gguf:0.20,/vectors/first_person_presence.gguf:0.15
python3 "$D/gate.py" gen --label 4b --url $URL
docker rm -f brain-gate >/dev/null 2>&1
python3 "$D/gate.py" judge --labels A,B,honesty,bare,9b,4b
touch "$G/gate_gen.done"
