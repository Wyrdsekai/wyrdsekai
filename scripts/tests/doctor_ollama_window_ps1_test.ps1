# Windows parity for the doctor's Ollama window line (2026-10-09): the window Ollama runs the
# loaded model with (/api/ps), warned about when too small for a companion's full turn. Runs the
# launcher's function against a stand-in Ollama. Needs python3.
Set-StrictMode -Version Latest
$py = (Get-Command python3 -ErrorAction SilentlyContinue)
if (-not $py) { "skip: python3 not found"; exit 0 }
$ps1 = Get-Content (Join-Path $PSScriptRoot "../../packaging/windows/wyrd.ps1") -Raw
function Write-Warn2 { param($m) Write-Host "[warn] $m" }
$script:WyrdLocale = 'en'
$script:WyrdI18nDir = (Resolve-Path (Join-Path $PSScriptRoot "../i18n")).Path
$script:I18nCat = $null; $script:I18nCatEn = $null
foreach ($fn in @('Get-JProp', 'Import-I18nCatalog', '_T', 'Invoke-DoctorOllamaWindow')) {
    $m = [regex]::Match($ps1, "(?ms)^function $fn(\([^)]*\))? \{.*?^\}")
    if (-not $m.Success) { "FAIL $fn not found in wyrd.ps1"; exit 1 }
    Invoke-Expression $m.Value
}
$script:fails = 0
function Check($name, $ok, $got) { if ($ok) { "ok   $name" } else { "FAIL $name (got: $got)"; $script:fails++ } }
$T = Join-Path ([IO.Path]::GetTempPath()) ("wyrd-doctor-ollama-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $T | Out-Null
$server = @'
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
            if MODE == "big": return self._send(200, {"models": [{"name": "qwen3.5:9b", "context_length": 16384}]})
            return self._send(200, {"models": []})
        self._send(404, {"error": "not found"})
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(s.server_address[1]))
s.serve_forever()
'@
Set-Content "$T/ollama.py" $server
$conf = @{}
function Serve($mode) {
    if ($script:proc) { $script:proc.Kill(); $script:proc.WaitForExit() }
    Remove-Item "$T/port" -ErrorAction SilentlyContinue
    $script:proc = Start-Process -FilePath $py.Source -ArgumentList @('-I', "$T/ollama.py", "$T/port", $mode) -PassThru -NoNewWindow
    for ($i = 0; $i -lt 50 -and -not (Test-Path "$T/port"); $i++) { Start-Sleep -Milliseconds 100 }
    return "http://127.0.0.1:" + (Get-Content "$T/port")
}
$script:proc = $null

$env:WYRDSEKAI_INFERENCE_URL = ''
$out = Invoke-DoctorOllamaWindow *>&1 | Out-String
Check 'no configured URL: silent' ($out.Trim() -eq '') $out

$url = Serve 'small'; $env:WYRDSEKAI_INFERENCE_URL = $url
$out = Invoke-DoctorOllamaWindow *>&1 | Out-String
Check 'a 4,096-token window is warned about' ($out.Contains("[warn] Ollama at ${url}: qwen3.5:9b runs with a 4096-token window") -and $out.Contains('OLLAMA_CONTEXT_LENGTH=16384')) $out

$url = Serve 'big'; $env:WYRDSEKAI_INFERENCE_URL = $url
$out = Invoke-DoctorOllamaWindow *>&1 | Out-String
Check 'a window of 8192 or more passes' ($out.Contains("ollama       : Ollama at ${url}: qwen3.5:9b runs with a 16384-token window") -and -not $out.Contains('[warn]')) $out

$url = Serve 'none'; $env:WYRDSEKAI_INFERENCE_URL = $url
$out = Invoke-DoctorOllamaWindow *>&1 | Out-String
Check 'nothing loaded: said plainly' ($out.Contains('no model loaded yet')) $out

if ($script:proc) { $script:proc.Kill(); $script:proc.WaitForExit() }
$env:WYRDSEKAI_INFERENCE_URL = $url
$out = Invoke-DoctorOllamaWindow *>&1 | Out-String
Check 'a URL that does not answer: silent' ($out.Trim() -eq '') $out
$env:WYRDSEKAI_INFERENCE_URL = ''
Remove-Item -Recurse -Force $T
if ($script:fails -eq 0) { "all passed"; exit 0 } else { "$($script:fails) failed"; exit 1 }
