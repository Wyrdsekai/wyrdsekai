#!/bin/bash
# Export a companion's soul manifest from a Wyrdsekai server.
#
# Usage:
#   ./export-soul.sh                          # List all souls, pick one
#   ./export-soul.sh <did>                    # Export specific soul
#   ./export-soul.sh <did> -o ma.soul.json    # Export to file
#
# Environment:
#   WYRDSEKAI_URL  Server URL (default: http://localhost:7070)
#   WYRDSEKAI_TOKEN  Auth token (optional)

set -e

URL="${WYRDSEKAI_URL:-http://localhost:7070}"
TOKEN="${WYRDSEKAI_TOKEN:-}"
DID="${1:-}"
OUTPUT=""

# Parse flags
shift 2>/dev/null || true
while [[ $# -gt 0 ]]; do
    case $1 in
        -o|--output) OUTPUT="$2"; shift 2 ;;
        *) shift ;;
    esac
done

TOKEN_PARAM=""
if [[ -n "$TOKEN" ]]; then
    TOKEN_PARAM="?token=$TOKEN"
fi

# If no DID given, list all and let user pick
if [[ -z "$DID" ]]; then
    echo "Fetching souls from $URL..."
    SOULS=$(curl -s "$URL/api/soul/list$TOKEN_PARAM")

    if [[ "$SOULS" == "[]" || -z "$SOULS" ]]; then
        echo "No souls found on server."
        exit 1
    fi

    echo ""
    echo "Available souls:"
    echo "$SOULS" | python3 -c "
import json, sys
souls = json.load(sys.stdin)
for i, s in enumerate(souls):
    print(f\"  [{i+1}] {s['agentName']} (v{s['manifestVersion']}) — {s['did'][:40]}...\")
" 2>/dev/null || echo "$SOULS" | jq -r '.[] | "  \(.agentName) (v\(.manifestVersion)) — \(.did)"' 2>/dev/null || echo "$SOULS"

    echo ""
    read -p "Enter number or DID: " CHOICE

    # If numeric, extract DID from list
    if [[ "$CHOICE" =~ ^[0-9]+$ ]]; then
        DID=$(echo "$SOULS" | python3 -c "
import json, sys
souls = json.load(sys.stdin)
idx = int('$CHOICE') - 1
if 0 <= idx < len(souls):
    print(souls[idx]['did'])
" 2>/dev/null)
    else
        DID="$CHOICE"
    fi

    if [[ -z "$DID" ]]; then
        echo "Invalid selection."
        exit 1
    fi
fi

echo "Exporting soul: $DID"

MANIFEST=$(curl -s "$URL/api/soul/$(python3 -c "import urllib.parse; print(urllib.parse.quote('$DID', safe=''))" 2>/dev/null || echo "$DID")$TOKEN_PARAM")

if [[ -z "$MANIFEST" || "$MANIFEST" == "null" ]]; then
    echo "Soul not found."
    exit 1
fi

# Extract name for default filename
NAME=$(echo "$MANIFEST" | python3 -c "import json,sys; print(json.load(sys.stdin).get('profile',{}).get('name','companion'))" 2>/dev/null || echo "companion")

if [[ -z "$OUTPUT" ]]; then
    OUTPUT="${NAME,,}.soul.json"
fi

echo "$MANIFEST" | python3 -m json.tool > "$OUTPUT" 2>/dev/null || echo "$MANIFEST" > "$OUTPUT"

SIZE=$(wc -c < "$OUTPUT" | tr -d ' ')
echo "Exported to: $OUTPUT ($SIZE bytes)"
