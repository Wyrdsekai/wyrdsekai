#!/usr/bin/env bash
# The release bake's model server, started and stopped on demand.
#
# packaging/build-evolved-artifact.sh needs a llama-server on :8200 serving the
# 9B drive (corpus expansion, regression probes, smoke steps). This runs the
# compose service `llama-server` (container `wyrdsekai-llama`) for exactly as
# long as it is needed:
#
#   * `start` brings it up and then turns its restart policy OFF, so a reboot
#     of the build box never brings the bake server back on its own — the GPU
#     belongs to whatever the box is doing, not to a container nobody asked for.
#   * `stop` stops and removes the container (nothing lingers in `docker ps -a`).
#   * `status` says whether :8200 answers and with which model.
#
# packaging/build-all.sh calls `start` before the bake when :8200 is silent and
# `stop` afterwards, so a release build leaves the box as it found it.
#
# Usage:
#   scripts/bake-server.sh start|stop|status|restart
#
# Environment:
#   WYRDSEKAI_DATA        data dir whose models/ holds the gguf (default: <repo>/data)
#   LLAMA_SKILLS_MODEL    model file name (default: from .env, else the 9B drive v6)
#   BAKE_SERVER_WAIT      seconds to wait for /health on start (default: 180)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CONTAINER="wyrdsekai-llama"
SERVICE="llama-server"
PORT="${LLAMA_PORT:-8200}"
URL="http://127.0.0.1:${PORT}"
WAIT="${BAKE_SERVER_WAIT:-180}"

export WYRDSEKAI_DATA="${WYRDSEKAI_DATA:-$REPO_ROOT/data}"

model_name() {
    if [[ -n "${LLAMA_SKILLS_MODEL:-}" ]]; then echo "$LLAMA_SKILLS_MODEL"; return; fi
    if [[ -f .env ]]; then
        local m
        m="$(grep -E '^LLAMA_SKILLS_MODEL=' .env | tail -1 | cut -d= -f2- || true)"
        if [[ -n "$m" ]]; then echo "$m"; return; fi
    fi
    echo "wyrdsekai-3.5-9b-drive-v6-q4km.gguf"
}

compose() {
    docker compose --profile inference "$@"
}

answers() {
    curl -fsS -m 3 "$URL/health" >/dev/null 2>&1
}

served_model() {
    curl -fsS -m 3 "$URL/v1/models" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(", ".join(m.get("id","?") for m in d.get("data",[])))' 2>/dev/null \
        || true
}

cmd_status() {
    local state
    state="$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null || echo "absent")"
    local policy
    policy="$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$CONTAINER" 2>/dev/null || echo "-")"
    if answers; then
        echo "bake server: UP on $URL (container $state, restart=$policy) — model: $(served_model)"
        return 0
    fi
    echo "bake server: DOWN ($URL does not answer; container $state)"
    return 1
}

cmd_start() {
    local model
    model="$(model_name)"
    local file="$WYRDSEKAI_DATA/models/$model"
    if [[ ! -s "$file" ]]; then
        echo "bake server: model not found: $file" >&2
        echo "  Put the 9B drive there (a hard link is fine), or set WYRDSEKAI_DATA / LLAMA_SKILLS_MODEL." >&2
        echo "  Download: curl -fL -C - -o \"$file.part\" https://wyrdsekai.org/models/$model && mv \"$file.part\" \"$file\"" >&2
        exit 2
    fi
    if answers; then
        echo "bake server: already up — $(served_model)"
        docker update --restart=no "$CONTAINER" >/dev/null 2>&1 || true
        return 0
    fi
    echo "bake server: starting $SERVICE with $model (data: $WYRDSEKAI_DATA)"
    LLAMA_SKILLS_MODEL="$model" compose up -d "$SERVICE"
    # Never on boot: the compose file says unless-stopped for household installs;
    # a build box's bake server is a tool, not a service.
    docker update --restart=no "$CONTAINER" >/dev/null
    local waited=0
    until answers; do
        if (( waited >= WAIT )); then
            echo "bake server: not healthy after ${WAIT}s — last log lines:" >&2
            docker logs --tail 20 "$CONTAINER" >&2 || true
            exit 1
        fi
        sleep 3; waited=$((waited + 3))
    done
    echo "bake server: UP on $URL after ${waited}s — model: $(served_model)"
}

cmd_stop() {
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
        compose rm -sf "$SERVICE" >/dev/null
        echo "bake server: stopped and removed ($CONTAINER)"
    else
        echo "bake server: not running"
    fi
}

case "${1:-}" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    status)  cmd_status ;;
    *) echo "usage: $0 start|stop|status|restart" >&2; exit 64 ;;
esac
