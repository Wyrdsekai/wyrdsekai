#!/bin/bash
# Quick smoke test for the Wyrdsekai channel plugin.
# Sends an MCP initialize request over stdin and checks for a valid response.
# Does NOT require a running Wyrdsekai server (SSE failure is logged to stderr).
set -euo pipefail

cd "$(dirname "$0")"

echo "[test] Installing dependencies..."
npm install --silent 2>/dev/null

echo "[test] Sending MCP initialize handshake..."
RESPONSE=$(echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.0.1"}}}' \
  | timeout 10 npx tsx index.ts 2>/dev/null \
  | head -1)

if echo "$RESPONSE" | grep -q '"result"'; then
  echo "[test] PASS: MCP server responded to initialize"
  echo "[test] Response: $RESPONSE"
  exit 0
else
  echo "[test] FAIL: No valid MCP response"
  echo "[test] Got: $RESPONSE"
  exit 1
fi
