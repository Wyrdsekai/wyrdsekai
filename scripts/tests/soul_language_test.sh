#!/usr/bin/env bash
# `wyrd soul language <companion>` shows what the language healer sees in her soul (2026-10-05): a
# steward whose node was busy re-rendering could not tell whether the notes were in another language
# or the detector misread them. Run against a stand-in server.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"
cat > "$T/server.py" <<'PY'
import http.server, json, sys
LIST = [{"did": "did:key:z6MkA", "name": "Mia", "version": 3, "forgedAt": 0}]
REPORT = {"household": "en", "verifiable": True, "fragments": 1, "memoryNodes": 1, "items": [
    {"id": "frag-1", "kind": "fragment", "detected": "es", "strikes": 0, "mercied": False, "text": "El peso que llevaba ya no es el de antes."},
    {"id": "node-9", "kind": "memory", "detected": "es", "strikes": 3, "mercied": True, "text": "biblioteca — library"}]}
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, d):
        b = json.dumps(d).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        open(sys.argv[1], "a").write(self.path + " " + str(self.headers.get("Authorization")) + "\n")
        if self.headers.get("Authorization") != "Bearer steward-token":
            return self._send(403, {"error": "Not authorized for this soul"})
        if self.path.lower() in ("/api/soul/mia/language", "/api/soul/did:key:z6mka/language"):
            return self._send(200, REPORT)
        self._send(404, {"error": "No soul found for: " + self.path.split("/")[3]})
    def do_POST(self):
        open(sys.argv[1], "a").write("POST " + self.path + " " + str(self.headers.get("Authorization")) + "\n")
        if self.headers.get("Authorization") != "Bearer steward-token":
            return self._send(403, {"error": "Not authorized for this soul"})
        if self.path.lower() == "/api/soul/mia/language/heal": return self._send(202, REPORT)
        if self.path.lower() == "/api/soul/gone/language/heal": return self._send(409, {"error": "That companion is not running on this node"})
        self._send(404, {"error": "No soul found"})
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[2], "w").write(str(s.server_address[1]))
s.serve_forever()
PY
python3 "$T/server.py" "$T/requests" "$T/port" & SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$T"' EXIT
for i in $(seq 1 50); do [ -s "$T/port" ] && break; sleep 0.1; done
export WYRDSEKAI_API_URL="http://127.0.0.1:$(cat "$T/port")"
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" soul language mia 2>&1); rc=$?
check "the name goes to the node, which resolves it" 'grep -q "^/api/soul/mia/language Bearer steward-token" "$T/requests"'
check "the household language and the counts are said" '[[ $rc -eq 0 && "$out" == *"household language: en"* && "$out" == *"1 fragment(s), 1 memory node(s)"* ]]'
check "each note shows what it reads as and where it stands" '[[ "$out" == *"[fragment] frag-1 reads as es, not tried yet"* && "$out" == *"[memory] node-9 reads as es, left in peace"* && "$out" == *"biblioteca"* ]]'
out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" soul language did:key:z6MkA 2>&1); rc=$?
check "a DID is taken as it is" '[[ $rc -eq 0 && "$out" == *"frag-1"* ]] && grep -q "^/api/soul/did:key:z6MkA/language Bearer steward-token" "$T/requests"'
out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" soul language nobody 2>&1); rc=$?
check "an unknown name is said so" '[[ $rc -ne 0 && "$out" == *"No companion named"* ]]'
out=$(WYRDSEKAI_TOKEN=someone-else bash "$HERE/bin/wyrd" soul language did:key:z6MkA 2>&1); rc=$?
check "a member who is not her bondholder is refused" '[[ $rc -ne 0 && "$out" == *"Only the steward or her bondholder"* ]]'
out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" soul language mia heal 2>&1); rc=$?
check "heal posts to the node and says what is happening" '[[ $rc -eq 0 && "$out" == *"Healing now"* ]] && grep -q "^POST /api/soul/mia/language/heal Bearer steward-token" "$T/requests"'
out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" soul language gone heal 2>&1); rc=$?
check "a companion not running here is said so" '[[ $rc -ne 0 && "$out" == *"not running on this node"* ]]'
out=$(bash "$HERE/bin/wyrd" soul language mia dance 2>&1); rc=$?
check "an unknown second word is refused" '[[ $rc -eq 64 && "$out" == *"[heal]"* ]]'
out=$(bash "$HERE/bin/wyrd" soul help 2>&1); check "the usage names the verb" '[[ "$out" == *"language <name> [heal]"* ]]'
echo "failures: $fails"
[ "$fails" -eq 0 ] && echo "all passed"
exit $(( fails > 0 ))
