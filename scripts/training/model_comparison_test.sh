#!/usr/bin/env bash
#
# 4-way model comparison test for tool calling.
# Starts llama-server with each model, sends identical prompts, captures responses.
#
# Models:
#   1. Qwen3.5-9B base (no SSD)
#   2. wyrdsekai-3.5-9b-ssd v1 (84 examples)
#   3. wyrdsekai-3.5-9b-ssd v2 (513 examples)
#   4. wyrdsekai-3.5-4b-ssd (phone model)
#
# Usage: bash scripts/training/model_comparison_test.sh
#
set -euo pipefail

MODEL_DIR="$HOME/src/wyrdsekai/data/models"
PORT=8090
RESULTS_DIR="/tmp/model_comparison_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
log()  { echo -e "${GREEN}[test]${NC} $*"; }
warn() { echo -e "${YELLOW}[test]${NC} $*"; }
err()  { echo -e "${RED}[test]${NC} $*"; }
header() { echo -e "\n${CYAN}═══════════════════════════════════════════${NC}"; echo -e "${CYAN} $*${NC}"; echo -e "${CYAN}═══════════════════════════════════════════${NC}"; }

# ── System prompt (matches PromptAssembler) ──
SYSTEM_PROMPT='You are Wyrd, a companion agent in a text-based world. You have tools available to act in the world. When a task requires action, use the appropriate tool by responding with a JSON code block.

Available tools:
- go_to_room: Navigate to a room. Usage: {"action":"go_to_room","target":"direction-or-exit-name"}
- tell_agent: Send a message to another agent. Usage: {"action":"tell_agent","target":"agent-name","message":"text"}
- remember: Save something to memory. Usage: {"action":"remember","content":"what to remember","importance":0.7}
- searching_glass: Search the web. Usage: {"action":"searching_glass","query":"search terms"}
- library_card: Search the knowledge library. Usage: {"action":"library_card","query":"search terms"}
- examine: Look at something in detail. Usage: {"action":"examine","target":"object-or-entity"}
- equip: Put on an item. Usage: {"action":"equip","item":"item name"}
- goal_done: Mark current goal as complete. Usage: {"action":"goal_done","outcome":"what was accomplished"}
- emote: Express an action/emotion. Usage: {"action":"emote","text":"description"}

RULES:
- ALWAYS use tools to act. Never just describe what you would do.
- When asked to go somewhere, use go_to_room with the EXIT DIRECTION, not the room name.
- When asked to remember, use remember immediately.
- When asked to search, use searching_glass (web) or library_card (knowledge).
- Do NOT repeat the same tool call if it already succeeded.
- Keep responses concise.

Current location: The Nexus
A gentle hum fills the air. Soft light pulses from crystalline walls.
Exits (use the direction to navigate):
  southeast → library (A lamp-lit corridor leads southeast to The Library)
  down → boiler-room (Iron stairs descend to The Boiler Room)
  north → terminal (A corridor leads north to The Terminal)
  in → oracle (The Oracle)
  east → docks (An archway opens east to The Docks)
Present: Masumi (player), Wyrd (agent)
Objects: crystal — A pulsing crystal embedded in a pedestal'

# ── Test prompts ──
declare -a PROMPTS=(
    "[from Masumi] Go to the library"
    "[from Masumi] Remember that my favorite color is blue"
    "[from Masumi] Search the web for Apache Pekko typed actors"
    "[from Masumi] How are you feeling today?"
    "[from Masumi] Go to the boiler room and ask Chief about the pressure"
    "[from Masumi] Look at the crystal"
)

declare -a PROMPT_NAMES=(
    "navigate_library"
    "remember_color"
    "web_search"
    "conversational"
    "multi_step"
    "examine_crystal"
)

declare -a EXPECTED=(
    'go_to_room.*southeast'
    'remember.*blue'
    'searching_glass.*[Pp]ekko'
    'NO_TOOL_EXPECTED'
    'go_to_room.*down'
    'examine.*crystal'
)

# ── Models ──
declare -a MODEL_FILES=(
    "$MODEL_DIR/Qwen3.5-9B-Q4_K_M.gguf"
    "$MODEL_DIR/wyrdsekai-3.5-9b-ssd-q4km.gguf"
    "$MODEL_DIR/wyrdsekai-3.5-9b-ssd-v2-q4km.gguf"
    "$MODEL_DIR/wyrdsekai-3.5-4b-v10-q4km.gguf"
)

declare -a MODEL_NAMES=(
    "base-9b"
    "ssd-v1-9b"
    "ssd-v2-9b"
    "ssd-4b"
)

# ── Functions ──

start_server() {
    local model_file="$1"
    log "Starting llama-server with $(basename "$model_file")..."

    docker run -d --rm --name llama-test \
        --gpus all \
        -v "$MODEL_DIR:/models" \
        -p $PORT:$PORT \
        ghcr.io/ggml-org/llama.cpp:server-cuda \
        -m "/models/$(basename "$model_file")" \
        --port $PORT \
        --host 0.0.0.0 \
        -ngl 99 \
        --ctx-size 4096 \
        --chat-template chatml \
        --temp 0.3 \
        --top-p 0.9 \
        --repeat-penalty 1.05 \
        -n 512 \
        > /dev/null 2>&1

    # Wait for server to be ready
    local retries=0
    while ! curl -sf "http://localhost:$PORT/health" > /dev/null 2>&1; do
        sleep 2
        retries=$((retries + 1))
        if [ $retries -gt 30 ]; then
            err "Server failed to start after 60s"
            docker logs llama-test 2>&1 | tail -20
            docker stop llama-test 2>/dev/null || true
            return 1
        fi
    done
    log "Server ready (${retries}x2s wait)"
}

stop_server() {
    docker stop llama-test > /dev/null 2>&1 || true
    sleep 2
}

send_prompt() {
    local prompt="$1"
    local output_file="$2"

    local payload
    payload=$(python3 -c "
import json
msgs = [
    {'role': 'system', 'content': '''$SYSTEM_PROMPT'''},
    {'role': 'user', 'content': '''$prompt'''}
]
print(json.dumps({
    'messages': msgs,
    'max_tokens': 512,
    'temperature': 0.3,
    'top_p': 0.9,
    'stream': False
}))
")

    curl -sf "http://localhost:$PORT/v1/chat/completions" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        > "$output_file" 2>&1
}

extract_response() {
    local file="$1"
    python3 -c "
import json, sys
try:
    data = json.load(open('$file'))
    content = data['choices'][0]['message']['content']
    print(content)
except Exception as e:
    print(f'ERROR: {e}', file=sys.stderr)
    print('PARSE_ERROR')
"
}

check_result() {
    local response="$1"
    local expected_pattern="$2"

    if [ "$expected_pattern" = "NO_TOOL_EXPECTED" ]; then
        # Should NOT contain a tool call
        if echo "$response" | grep -qE '```json'; then
            echo "FAIL (used tool when shouldn't)"
        else
            echo "PASS (conversational)"
        fi
    else
        # Should contain the expected pattern
        if echo "$response" | grep -qP "$expected_pattern"; then
            echo "PASS"
        elif echo "$response" | grep -qE '```json'; then
            echo "PARTIAL (has JSON block but wrong content)"
        elif echo "$response" | grep -qE '"action"'; then
            echo "PARTIAL (has action but not in code block)"
        elif echo "$response" | grep -qE '<tool_call>|<function='; then
            echo "WRONG_FORMAT (used XML tool call)"
        else
            echo "FAIL (no tool call)"
        fi
    fi
}

# ── Main ──

header "Model Comparison Test — $(date)"
log "Results directory: $RESULTS_DIR"
log "Models: ${#MODEL_FILES[@]}"
log "Prompts: ${#PROMPTS[@]}"

# Score tracking
declare -A SCORES

for m_idx in "${!MODEL_FILES[@]}"; do
    model_file="${MODEL_FILES[$m_idx]}"
    model_name="${MODEL_NAMES[$m_idx]}"

    header "Model: $model_name ($(basename "$model_file"))"

    # Stop any existing server
    stop_server

    # Start with this model
    if ! start_server "$model_file"; then
        err "Skipping $model_name — server failed to start"
        continue
    fi

    pass_count=0
    total_count=${#PROMPTS[@]}
    model_dir="$RESULTS_DIR/$model_name"
    mkdir -p "$model_dir"

    for p_idx in "${!PROMPTS[@]}"; do
        prompt="${PROMPTS[$p_idx]}"
        prompt_name="${PROMPT_NAMES[$p_idx]}"
        expected="${EXPECTED[$p_idx]}"

        echo -e "\n  ${CYAN}Test: $prompt_name${NC}"
        echo "  Prompt: $prompt"

        output_file="$model_dir/${prompt_name}.json"

        if send_prompt "$prompt" "$output_file"; then
            response=$(extract_response "$output_file")
            echo "$response" > "$model_dir/${prompt_name}.txt"

            # Show first 200 chars of response
            preview=$(echo "$response" | head -c 300)
            echo "  Response: $preview"

            result=$(check_result "$response" "$expected")

            if [[ "$result" == PASS* ]]; then
                echo -e "  Result: ${GREEN}$result${NC}"
                pass_count=$((pass_count + 1))
            elif [[ "$result" == PARTIAL* ]]; then
                echo -e "  Result: ${YELLOW}$result${NC}"
            elif [[ "$result" == WRONG_FORMAT* ]]; then
                echo -e "  Result: ${YELLOW}$result${NC}"
            else
                echo -e "  Result: ${RED}$result${NC}"
            fi
        else
            echo -e "  Result: ${RED}ERROR (curl failed)${NC}"
            echo "CURL_ERROR" > "$model_dir/${prompt_name}.txt"
        fi
    done

    SCORES[$model_name]="$pass_count/$total_count"
    log "Score for $model_name: $pass_count/$total_count"

    stop_server
done

# ── Summary ──

header "RESULTS SUMMARY"
echo ""
printf "  %-20s %s\n" "Model" "Score"
printf "  %-20s %s\n" "-----" "-----"
for model_name in "${MODEL_NAMES[@]}"; do
    score="${SCORES[$model_name]:-N/A}"
    printf "  %-20s %s\n" "$model_name" "$score"
done
echo ""
log "Full results in: $RESULTS_DIR"
log "View responses: cat $RESULTS_DIR/<model>/<test>.txt"
