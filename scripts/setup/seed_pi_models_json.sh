#!/usr/bin/env bash
# Seed ~/.pi/agent/models.json with a "local" provider pointing at the
# household's llama-server. Idempotent: if a "local" provider already
# exists, leaves it alone (lets the steward edit if needed).
#
# Usage:
#   bash scripts/setup/seed_pi_models_json.sh                # autodetect
#   WYRDSEKAI_INFERENCE_URL=http://home-server:8200 \
#     bash scripts/setup/seed_pi_models_json.sh              # explicit url
#
# Called by `wyrd setup pi` (also safe to run by hand). Pi is the
# default coding backend; this seeding
# is what makes "complex items work out of the box" for households
# that haven't manually edited models.json.

set -euo pipefail

CONFIG_DIR="${HOME}/.pi/agent"
CONFIG_FILE="${CONFIG_DIR}/models.json"
LLAMA_URL="${WYRDSEKAI_INFERENCE_URL:-http://localhost:8200}"
LLAMA_URL="${LLAMA_URL%/}"  # strip trailing slash

# 1. Probe llama-server for the served model id. If it's not running
#    or doesn't speak /v1/models, bail with a friendly message — the
#    household can re-run after starting llama.
if ! response=$(curl -sf --max-time 5 "${LLAMA_URL}/v1/models" 2>/dev/null); then
    echo "WARN: ${LLAMA_URL}/v1/models did not respond." >&2
    echo "      Start llama-server (try: \`wyrd inference start\`) then re-run." >&2
    exit 0  # exit 0 so bin/wyrd setup doesn't fail when llama isn't up yet
fi

# Parse first model id; jq if available, fallback to grep.
if command -v jq >/dev/null 2>&1; then
    MODEL_ID=$(echo "$response" | jq -r '.data[0].id // empty')
else
    MODEL_ID=$(echo "$response" | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
fi

if [[ -z "${MODEL_ID:-}" ]]; then
    echo "WARN: couldn't parse a model id from ${LLAMA_URL}/v1/models response." >&2
    exit 0
fi

mkdir -p "${CONFIG_DIR}"

# 2. If models.json already exists with a "local" provider, leave it
#    alone — the steward may have customized it.
if [[ -f "${CONFIG_FILE}" ]] && grep -q '"local"' "${CONFIG_FILE}" 2>/dev/null; then
    echo "[pi-setup] ${CONFIG_FILE} already has a 'local' provider; leaving as-is."
    echo "[pi-setup] (To re-seed, delete the file or remove its 'local' entry.)"
    exit 0
fi

# 3. Write the seed file. The "local-llama" apiKey sentinel matches what
#    PiCodingE2ETest uses; pi requires a non-empty key field even when
#    the upstream server is unauthenticated.
cat > "${CONFIG_FILE}" <<EOF
{
  "providers": {
    "local": {
      "name": "Wyrdsekai local llama-server",
      "baseUrl": "${LLAMA_URL}/v1",
      "api": "openai-completions",
      "apiKey": "local-llama",
      "models": [
        { "id": "${MODEL_ID}" }
      ]
    }
  }
}
EOF

echo "[pi-setup] Seeded ${CONFIG_FILE}"
echo "[pi-setup]   provider=local  baseUrl=${LLAMA_URL}/v1  model=${MODEL_ID}"
echo "[pi-setup] Pi will route to local llama-server by default."
echo "[pi-setup] To add cloud providers (Anthropic, OpenAI, etc.), edit"
echo "[pi-setup] ${CONFIG_FILE} — see https://pi.dev/docs/latest/models"
