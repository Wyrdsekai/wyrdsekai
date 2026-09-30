#!/usr/bin/env bash
# Every call bin/wyrd makes to this node's /api carries a login (2026-09-28: the node refuses /api
# calls without one). The curl wrapper adds the session or operator token to local /api calls only,
# keeps a header the caller already set, and never sends a token to any other address.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
for fn in wyrd_session_token_file wyrd_session_token curl; do
    eval "$(sed -n "/^${fn}() {/,/^}/p" "$HERE/bin/wyrd")"
    declare -F "$fn" >/dev/null || { echo "FAIL could not lift $fn from bin/wyrd"; exit 1; }
done
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
# A fake curl that prints its arguments, one per line.
cat > "$T/bin/curl" <<'SH'
#!/bin/sh
for a in "$@"; do echo "$a"; done
SH
chmod +x "$T/bin/curl"
export PATH="$T/bin:$PATH"
export WYRDSEKAI_DATA_DIR="$T/data"; mkdir -p "$WYRDSEKAI_DATA_DIR"
echo "op-token-123" > "$WYRDSEKAI_DATA_DIR/operator.token"
wyrd_session_token_file() { echo "$T/no-session"; }
unset WYRDSEKAI_TOKEN WYRDSEKAI_API_URL
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

out=$(curl -sf "http://localhost:7070/api/pair/household-key")
check "a local /api call gets the operator token" '[[ "$out" == *"Authorization: Bearer op-token-123"* ]]'
out=$(curl -s -X POST "http://127.0.0.1:7070/api/study/add" -d '{}')
check "127.0.0.1 counts as local too" '[[ "$out" == *"Bearer op-token-123"* ]]'
out=$(curl -sf -H "Authorization: Bearer mine" "http://localhost:7070/api/body")
check "a header the caller set is kept" '[[ "$out" == *"Bearer mine"* && "$out" != *"op-token-123"* ]]'
out=$(curl -sf "http://127.0.0.1:8200/health")
check "the model server gets no token" '[[ "$out" != *"Authorization"* ]]'
out=$(curl -sf "https://api.github.com/repos/x/y/releases/latest")
check "the internet gets no token" '[[ "$out" != *"Authorization"* ]]'
out=$(curl -sf "http://localhost:4649/api/search")
check "another local service with an /api path (ResearchZosho) gets no token" '[[ "$out" != *"Authorization"* ]]'
export WYRDSEKAI_TOKEN="session-abc"
out=$(curl -sf "http://localhost:7070/api/study/journal")
check "a session token wins over the operator token" '[[ "$out" == *"Bearer session-abc"* ]]'
unset WYRDSEKAI_TOKEN
export WYRDSEKAI_API_URL="http://second-node:7070"
out=$(curl -sf "http://second-node:7070/api/federation/status")
check "the configured node address counts as this node" '[[ "$out" == *"Authorization: Bearer"* ]]'
out=$(curl -sf "http://elsewhere:7070/api/federation/status")
check "any other host gets nothing" '[[ "$out" != *"Authorization"* ]]'
echo "failures: $fails"
exit $(( fails > 0 ))
