#!/bin/bash
# Wyrdsekai all-in-one entrypoint: pulls models into /data on first boot,
# then supervises nats + llama (drive/voice) + the JVM server with
# restart-on-crash. First boot mints the steward invite and prints it to
# the container log (docker logs <name>).
#
# Env knobs:
#   WYRDSEKAI_TIER=auto|gpu|cpu     model tier (auto = nvidia-smi probe)
#   WYRDSEKAI_STEWARD_NAME=steward  first-boot account name
#   WYRDSEKAI_SKIP_MODELS=1         skip downloads + inference (CI/smoke)
#   WYRDSEKAI_LIBRARY_STARTER=true  starter Library packs on first boot
set -u

ROOT=/opt/wyrdsekai
DATA="${WYRDSEKAI_DATA:-/data}"
LOGS="$DATA/logs"
mkdir -p "$DATA/models" "$LOGS"

say() { echo "[aio] $*"; }

TIER="${WYRDSEKAI_TIER:-auto}"
if [[ "$TIER" == "auto" ]]; then
    if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
        TIER=gpu
    else
        TIER=cpu
    fi
fi
say "tier: $TIER"

dl() { # url dest — interrupt-safe (.part + mv), idempotent
    [[ -f "$2" ]] && { say "$(basename "$2") present — skipping"; return 0; }
    say "downloading $(basename "$2")"
    curl -fL --retry 3 --progress-bar -o "$2.part" "$1" && mv "$2.part" "$2"
}

M4B="$DATA/models/wyrdsekai-3.5-4b-v10-q4km.gguf"
M9B="$DATA/models/wyrdsekai-3.5-9b-drive-v6-q4km.gguf"
if [[ "${WYRDSEKAI_SKIP_MODELS:-0}" != "1" ]]; then
    dl "https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx" \
       "$DATA/models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
    dl "https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json" \
       "$DATA/models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json"
    dl "https://wyrdsekai.org/models/wyrdsekai-3.5-4b-v10-q4km.gguf" "$M4B"
    if [[ "$TIER" == "gpu" ]]; then
        dl "https://wyrdsekai.org/models/wyrdsekai-3.5-9b-drive-v6-q4km.gguf" "$M9B"
    fi
fi

# ── supervised services ──
declare -a PIDS=()

run_svc() { # name cmd...
    local name="$1"; shift
    (
        while true; do
            "$@" >>"$LOGS/$name.log" 2>&1
            local rc=$?
            # 143/130 = SIGTERM/SIGINT during shutdown — don't respawn
            [[ $rc -eq 143 || $rc -eq 130 ]] && exit 0
            echo "[aio] $name exited (rc=$rc) — restarting in 3s" >&2
            sleep 3
        done
    ) &
    PIDS+=($!)
    say "started $name"
}

run_svc nats nats-server -p 4222

if [[ "${WYRDSEKAI_SKIP_MODELS:-0}" != "1" ]]; then
    if [[ "$TIER" == "gpu" ]]; then
        run_svc llama-drive /app/llama-server --model "$M9B" --host 0.0.0.0 --port 8200 \
            --ctx-size 16384 --jinja --reasoning off --reasoning-budget 0 --flash-attn on -ngl 99 --parallel 1
        # 16K to match the drive model: the same prompts reach both backends, and an
        # 8K voice window took HTTP 400s on interiority + on every drive turn when the
        # 9B was down. (CPU-only paths below stay at 8K — memory-constrained hardware —
        # and rely on the router's compaction retry instead.)
        run_svc llama-voice /app/llama-server --model "$M4B" --host 0.0.0.0 --port 8201 \
            --ctx-size 16384 --jinja --reasoning off --reasoning-budget 0 -ngl 99 --parallel 1
    else
        # CPU: one 4B serving as the single backend
        run_svc llama-drive /app/llama-server --model "$M4B" --host 0.0.0.0 --port 8200 \
            --ctx-size 8192 --jinja --reasoning off --reasoning-budget 0 --parallel 1
    fi
fi

export WYRDSEKAI_DATA="$DATA"
# The Java reads WYRDSEKAI_DATA_DIR, not WYRDSEKAI_DATA — export both so state
# lands on the /data volume instead of the container writable layer (2026-07-18).
export WYRDSEKAI_DATA_DIR="$DATA"
run_svc server "$ROOT/bin/wyrdsekai-server"

# ── first-boot steward invite → container log ──
(
    for _ in $(seq 1 120); do
        sleep 2
        curl -sf http://127.0.0.1:7070/health >/dev/null 2>&1 && break
    done
    if curl -sf http://127.0.0.1:7070/health >/dev/null 2>&1; then
        say "server healthy on :7070"
        marker="$DATA/.steward-bootstrapped"
        if [[ ! -f "$marker" ]]; then
            name="${WYRDSEKAI_STEWARD_NAME:-steward}"
            code=$("$ROOT/bin/wyrd" invite bootstrap --name "$name" 2>/dev/null | tail -1)
            touch "$marker"
            if [[ -n "$code" && "$code" != *INFO* ]]; then
                echo ""
                echo "=================================================================="
                echo "[aio] First boot — steward invite for '$name' (valid 24h):"
                echo ""
                echo "        $code"
                echo ""
                echo "[aio] Browser:  http://<host>:7070/app   (I have an invite)"
                echo "[aio] SSH:      ssh -p 7022 $name@<host>  (password = the code)"
                echo "=================================================================="
                echo ""
            else
                say "invite mint failed — run: docker exec <container> $ROOT/bin/wyrd invite bootstrap"
            fi
        fi
    else
        say "server did not become healthy within 4min — see $LOGS/server.log"
    fi
) &

term() { say "shutting down"; kill "${PIDS[@]}" 2>/dev/null; pkill -P $$ 2>/dev/null; exit 0; }
trap term SIGTERM SIGINT
wait
