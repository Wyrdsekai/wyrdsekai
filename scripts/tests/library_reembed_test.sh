#!/usr/bin/env bash
# `wyrd library reembed` reaches the steward's re-embed route (2026-10-04). Its first version sat
# inside the nested case of `wyrd library compact`, behind compact's own reembed action, so the verb
# was never reached and `wyrd library reembed` answered "unknown". This runs the launcher against a
# stand-in server and checks what it asked for and what it said.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"
cat > "$T/server.py" <<'PY'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, d):
        b = json.dumps(d).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _log(self):
        open(sys.argv[1], "a").write(self.command + " " + self.path + " " + str(self.headers.get("Authorization")) + "\n")
    def do_GET(self):
        self._log()
        if self.headers.get("Authorization") != "Bearer steward-token":
            return self._send(403, {"error": "steward only"})
        self._send(200, {"running": False, "collection": "study", "startedAt": "2026-10-04T20:00:00Z",
                         "finishedAt": "2026-10-04T21:00:00Z", "done": 1280})
    def do_POST(self):
        self._log()
        self._send(202, {"running": True, "collection": self.path.split("collection=")[-1], "done": 0,
                         "startedAt": "2026-10-04T20:00:00Z"})
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

out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" library reembed 2>&1)
check "a start posts to the route for the study shelf" 'grep -q "^POST /api/library/reembed?collection=study Bearer steward-token" "$T/requests"'
check "a start says it started and how to follow it" '[[ "$out" == *"Re-embed started on '"'"'study'"'"'"* && "$out" == *"wyrd library reembed status"* ]]'
out=$(WYRDSEKAI_TOKEN=steward-token bash "$HERE/bin/wyrd" library reembed status 2>&1)
check "status reads the route" 'grep -q "^GET /api/library/reembed Bearer steward-token" "$T/requests"'
check "status says how far it came" '[[ "$out" == *"finished: 1280 passage(s) done on study"* ]]'
out=$(WYRDSEKAI_TOKEN=someone-else bash "$HERE/bin/wyrd" library reembed status 2>&1); rc=$?
check "a non-steward is told it is the steward's, and it fails" '[[ $rc -ne 0 && "$out" == *"Only the steward can run this"* ]]'
out=$(bash "$HERE/bin/wyrd" library compact help 2>&1)
check "compact keeps its own help" '[[ "$out" == *"Usage: wyrd library compact"* ]]'
out=$(bash "$HERE/bin/wyrd" library help 2>&1)
check "the library help lists the verb" '[[ "$out" == *"reembed [study|status]"* ]]'
echo "failures: $fails"
[ "$fails" -eq 0 ] && echo "all passed"
exit $(( fails > 0 ))
