#!/usr/bin/env bash
# `wyrd doctor` reads the window a configured Ollama runs its model with (/api/ps) and says when it
# is too small for a companion's full turn (2026-10-09, after a household's 400s on a 4,096-token
# model). Runs the doctor's function from bin/wyrd against a stand-in Ollama.
set -u
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
W="$HERE/bin/wyrd"
T="$(mktemp -d)"
trap 'rm -rf "$T"; [[ -n "${pid:-}" ]] && kill "$pid" 2>/dev/null' EXIT
cat > "$T/ollama.py" <<'PY'
import http.server, json, sys
MODE = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, d):
        b = json.dumps(d).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path == "/api/tags": return self._send(200, {"models": [{"name": "qwen3.5:9b"}]})
        if self.path == "/api/ps":
            if MODE == "small": return self._send(200, {"models": [{"name": "qwen3.5:9b", "context_length": 4096}]})
            if MODE == "big": return self._send(200, {"models": [{"name": "qwen3.5:9b", "context_length": 16384}, {"name": "gpt-oss:20b", "context_length": 32768}]})
            return self._send(200, {"models": []})
        self._send(404, {"error": "not found"})
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_address[1]))
s.serve_forever()
PY
fail=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
for fn in _doctor_ollama_window _ollama_window_report _t; do
    prog="/^${fn}() {/,/^}/p"
    body="$(sed -n "$prog" "$W")"
    eval "$body"
done
warn() { echo "WARN: $*"; }
log() { echo "LOG: $*"; }
WYRD_I18N_DIR="$HERE/scripts/i18n"; WYRD_LOCALE=en   # the doctor's lines go through the translations
serve() {   # <mode> → sets URL and pid
    [[ -n "${pid:-}" ]] && kill "$pid" 2>/dev/null; rm -f "$T/port"
    python3 -I "$T/ollama.py" "$T/port" "$1" & pid=$!
    for _ in $(seq 1 50); do [[ -s "$T/port" ]] && break; sleep 0.1; done
    URL="http://127.0.0.1:$(cat "$T/port")"
}
WYRDSEKAI_INFERENCE_URL=""
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'no configured URL: silent, rc 0' '[[ "$out" == "rc=0" ]]'
serve small; WYRDSEKAI_INFERENCE_URL="$URL"
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'a 4,096-token window is warned about, rc 1' '[[ "$out" == *"WARN: Ollama at $URL: qwen3.5:9b runs with a 4096-token window"* && "$out" == *"OLLAMA_CONTEXT_LENGTH=16384"* && "$out" == *"rc=1" ]]'
out_ja="$(WYRD_LOCALE=ja _doctor_ollama_window || true)"   # it returns 1 when it warns
check 'the warning reads in Japanese on a Japanese household' '[[ "$out_ja" == *"qwen3.5:9b は 4096 トークンのウィンドウで動作中"* && "$out_ja" == *"OLLAMA_CONTEXT_LENGTH=16384"* ]]'
serve big; WYRDSEKAI_INFERENCE_URL="$URL"
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'windows of 8192 or more pass, every loaded model named' '[[ "$out" == *"LOG: Ollama at $URL: qwen3.5:9b runs with a 16384-token window, gpt-oss:20b runs with a 32768-token window"* && "$out" == *"rc=0" ]]'
serve none; WYRDSEKAI_INFERENCE_URL="$URL"
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'nothing loaded: said plainly, rc 0' '[[ "$out" == *"no model loaded yet"* && "$out" == *"rc=0" ]]'
kill "$pid" 2>/dev/null; pid=""
WYRDSEKAI_INFERENCE_URL="$URL"
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'a URL that does not answer: silent, rc 0' '[[ "$out" == "rc=0" ]]'
WYRDSEKAI_INFERENCE_URL="nats://zone-a"
out="$(_doctor_ollama_window; echo "rc=$?")"
check 'a mesh URL is not probed' '[[ "$out" == "rc=0" ]]'
[[ $fail -eq 0 ]] && echo "all passed"
exit $fail
