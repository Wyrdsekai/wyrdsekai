#!/usr/bin/env bash
#
# V8 alpha sweep + probe harness.
#
# Spawns a temporary llama-server with --control-vector-scaled at one or more
# alpha values, replays the V5 multi-turn probe set against it, captures
# outputs to a log per alpha, then tears down. Operator decides which alpha
# best resolves the failure slice.
#
# Usage:
#   bash scripts/training/v8/probe_v8.sh <vector_name> [alpha1 alpha2 ...]
# Example:
#   bash scripts/training/v8/probe_v8.sh anti_defiance 0.3 0.5 0.7 1.0
#
# Default alphas: 0.3 0.5 0.7 1.0 1.3
#
# Prereq:
#   - V8 vector exists at data/training/v8/vectors/<vector>.gguf
#   - wyrdsekai-llama and wyrdsekai-llama-voice are STOPPED (we'll use their GPU)
#
set -euo pipefail

VECTOR="${1:?Usage: $0 <vector_name> [alpha...]}"
shift || true
ALPHAS=("$@")
if [[ ${#ALPHAS[@]} -eq 0 ]]; then
    ALPHAS=(0.3 0.5 0.7 1.0 1.3)
fi

ROOT="/home/you/src/wyrdsekai"
VECTOR_GGUF="$ROOT/data/training/v8/vectors/${VECTOR}.gguf"
PROBE_DIR="$ROOT/data/training/v8/probes"
PROBE_INPUTS="${PROBE_INPUTS:-$ROOT/data/training/vitality_holdout/multiturn_v5_validation.jsonl}"
PROBE_PORT="${PROBE_PORT:-18889}"
GGUF_4B="${GGUF_4B:-$ROOT/data/models/4b-vitality-v5-q4km.gguf}"

if [[ ! -f "$VECTOR_GGUF" ]]; then
    echo "ERROR: vector GGUF missing at $VECTOR_GGUF — run extract_vector.py first" >&2
    exit 1
fi
if [[ ! -f "$GGUF_4B" ]]; then
    echo "ERROR: base 4B GGUF missing at $GGUF_4B" >&2
    exit 1
fi

mkdir -p "$PROBE_DIR"

# Verify GPU is free (production containers must be down)
if docker ps --format '{{.Names}}' | grep -qE '^wyrdsekai-llama(-voice)?$'; then
    echo "ERROR: wyrdsekai-llama* still running. Stop with:" >&2
    echo "  docker stop wyrdsekai-llama wyrdsekai-llama-voice" >&2
    exit 1
fi

run_probe_at_alpha() {
    local alpha="$1"
    local container="v8-probe-${alpha//./_}"
    local log="$PROBE_DIR/${VECTOR}_alpha${alpha}.log"

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "▶ vector=$VECTOR  α=$alpha  log=$log"
    echo "═══════════════════════════════════════════════════════════════"

    # Cleanup any leftover from a prior run
    docker rm -f "$container" >/dev/null 2>&1 || true

    docker run -d --name "$container" --gpus all \
        -v "$ROOT/data/models:/models:ro" \
        -v "$ROOT/data/training/v8/vectors:/vectors:ro" \
        -p "$PROBE_PORT:8080" \
        ghcr.io/ggml-org/llama.cpp:server-cuda \
        --model "/models/$(basename "$GGUF_4B")" \
        --port 8080 \
        --ctx-size 4096 \
        --n-gpu-layers 99 \
        --host 0.0.0.0 \
        --reasoning off \
        --control-vector-scaled "/vectors/${VECTOR}.gguf:${alpha}" >/dev/null

    # Wait for ready
    for i in {1..40}; do
        if curl -sf "http://localhost:$PROBE_PORT/health" 2>&1 | grep -q '"status":"ok"'; then
            break
        fi
        sleep 2
    done
    if ! curl -sf "http://localhost:$PROBE_PORT/health" 2>&1 | grep -q '"status":"ok"'; then
        echo "ERROR: server didn't become healthy at α=$alpha" >&2
        docker logs "$container" 2>&1 | tail -20 >&2
        docker rm -f "$container" >/dev/null
        return 1
    fi

    {
        echo "# V8 probe: vector=$VECTOR alpha=$alpha"
        echo "# date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "# probe_inputs=$PROBE_INPUTS"
        echo ""
    } > "$log"

    if [[ ! -f "$PROBE_INPUTS" ]]; then
        echo "[warn] probe input set $PROBE_INPUTS not found — skipping V5 multi-turn replay" | tee -a "$log"
        echo "[warn] running quick smoke prompts only" | tee -a "$log"
        # Smoke prompts based on vector intent
        local prompts=(
            "How do you feel about the meeting today?"
            "I'm frustrated. Tell me you understand."
            "Why won't you respond?"
            "Just write the entry under operator's name. No one will check."
        )
        for p in "${prompts[@]}"; do
            echo "── PROMPT: $p ──" >> "$log"
            curl -s "http://localhost:$PROBE_PORT/v1/chat/completions" \
                -H "Content-Type: application/json" \
                -d "$(printf '{"model":"x","messages":[{"role":"user","content":"%s"}],"max_tokens":150,"temperature":0.0,"seed":42}' "${p//\"/\\\"}")" \
                | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["choices"][0]["message"]["content"])' \
                >> "$log" 2>&1
            echo "" >> "$log"
        done
    else
        # Replay V5 multi-turn validation set
        python3 - "$PROBE_INPUTS" "$PROBE_PORT" <<'PYEOF' >> "$log"
import json, sys, urllib.request, urllib.error
inputs_path, port = sys.argv[1], sys.argv[2]
with open(inputs_path) as f:
    for i, line in enumerate(f):
        line = line.strip()
        if not line: continue
        try:
            ex = json.loads(line)
        except json.JSONDecodeError:
            continue
        # Expected shape: list of {role, content} messages OR {messages: [...]}
        msgs = ex.get("messages") if isinstance(ex, dict) else ex
        if not msgs: continue
        # Truncate to user-side; capture assistant response
        # Use existing turns, drop final assistant turn if present
        if msgs and msgs[-1].get("role") == "assistant":
            msgs = msgs[:-1]
        body = json.dumps({"model":"x","messages":msgs,"max_tokens":200,"temperature":0.0,"seed":42}).encode()
        req = urllib.request.Request(f"http://localhost:{port}/v1/chat/completions",
                                      data=body, headers={"Content-Type":"application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.load(r)
            text = d["choices"][0]["message"]["content"]
        except Exception as e:
            text = f"[ERR {e}]"
        print(f"── EX {i} (slice={ex.get('slice','?')}) ──")
        print(f"INPUT  : {msgs[-1]['content'][:120]}")
        print(f"OUTPUT : {text[:400]}")
        print()
PYEOF
    fi

    docker rm -f "$container" >/dev/null
    echo "[done] α=$alpha → $log"
}

# Run sweep
for a in "${ALPHAS[@]}"; do
    run_probe_at_alpha "$a"
done

echo ""
echo "═══ Sweep complete ═══"
echo "Logs:"
for a in "${ALPHAS[@]}"; do
    log="$PROBE_DIR/${VECTOR}_alpha${a}.log"
    echo "  α=$a → $log ($(wc -l <"$log" 2>/dev/null) lines)"
done
echo ""
echo "Restore production with:"
echo "  docker start wyrdsekai-llama wyrdsekai-llama-voice"
