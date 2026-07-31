#!/bin/bash
# Import a companion's soul manifest to a Wyrdsekai server.
#
# Usage:
#   ./import-soul.sh ma.soul.json
#   ./import-soul.sh ma.soul.json --url http://relay-node:7070
#
# Environment:
#   WYRDSEKAI_URL  Server URL (default: http://localhost:7070)
#   WYRDSEKAI_TOKEN  Auth token (optional)

set -e

URL="${WYRDSEKAI_URL:-http://localhost:7070}"
TOKEN="${WYRDSEKAI_TOKEN:-}"
FILE="${1:-}"

if [[ -z "$FILE" || ! -f "$FILE" ]]; then
    echo "Usage: ./import-soul.sh <file.soul.json>"
    echo ""
    echo "Import a .soul.json file to a Wyrdsekai server."
    echo "Set WYRDSEKAI_URL to target a specific server."
    exit 1
fi

shift
while [[ $# -gt 0 ]]; do
    case $1 in
        --url) URL="$2"; shift 2 ;;
        --token) TOKEN="$2"; shift 2 ;;
        *) shift ;;
    esac
done

TOKEN_PARAM=""
if [[ -n "$TOKEN" ]]; then
    TOKEN_PARAM="?token=$TOKEN"
fi

# Extract DID from manifest
DID=$(python3 -c "import json; print(json.load(open('$FILE'))['did'])" 2>/dev/null)
if [[ -z "$DID" ]]; then
    echo "Error: Could not read 'did' from $FILE"
    exit 1
fi

NAME=$(python3 -c "import json; m=json.load(open('$FILE')); print(m.get('agentName', m.get('profile',{}).get('name','unknown')))" 2>/dev/null || echo "unknown")
VERSION=$(python3 -c "import json; print(json.load(open('$FILE')).get('manifestVersion', 0))" 2>/dev/null || echo "?")

echo "Importing: $NAME (v$VERSION)"
echo "DID: $DID"
echo "Server: $URL"

RESPONSE=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -d @"$FILE" \
    "$URL/api/soul/$(python3 -c "import urllib.parse; print(urllib.parse.quote('$DID', safe=''))" 2>/dev/null || echo "$DID")$TOKEN_PARAM")

echo "Response: $RESPONSE"
