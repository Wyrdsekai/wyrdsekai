# Every call wyrd.ps1 makes to this node's /api carries a login (2026-09-28: the node refuses /api
# calls without one). The Invoke-RestMethod / Invoke-WebRequest wrappers add the session or operator
# token to this node's address only and keep a -Headers the caller passed. Needs python3 for the
# echo server; run with pwsh.
Set-StrictMode -Version Latest
$py = (Get-Command python3 -ErrorAction SilentlyContinue)
if (-not $py) { "skip: python3 not found"; exit 0 }
$T = Join-Path ([IO.Path]::GetTempPath()) ("wyrd-apilogin-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path "$T/data" | Out-Null
Set-Content "$T/data/operator.token" "op-tok-9"
$echo = @'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def _r(self):
        n=int(self.headers.get('Content-Length') or 0); body=self.rfile.read(n).decode() if n else ''
        out=json.dumps({"auth": self.headers.get('Authorization') or "none", "method": self.command, "body": body, "ct": self.headers.get('Content-Type') or ''}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(out))); self.end_headers(); self.wfile.write(out)
    do_GET=_r; do_POST=_r
    def log_message(self,*a): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
'@
Set-Content "$T/echo.py" $echo
$e1 = Start-Process python3 -ArgumentList "$T/echo.py",17970 -PassThru
$e2 = Start-Process python3 -ArgumentList "$T/echo.py",17971 -PassThru
Start-Sleep -Seconds 1
try {
$RestPort = 17970
$DataDir = "$T/data"
$ps1 = Get-Content (Join-Path $PSScriptRoot "../../packaging/windows/wyrd.ps1") -Raw
foreach ($fn in 'Get-ApiBase','Get-SessionToken','Get-ApiToken','Get-AdminHeaders','Get-WyrdApiLoginHeaders','Invoke-RestMethod','Invoke-WebRequest') {
    $m = [regex]::Match($ps1, "(?ms)^function $fn(\([^)]*\))? \{.*?^\}")
    if (-not $m.Success) { "FAIL could not lift $fn"; exit 1 }
    Invoke-Expression $m.Value
}
$fails = 0
function Check($name, $got, $want) { if ($got -eq $want) { "ok   $name" } else { "FAIL $name (got $got)"; $script:fails++ } }
Check 'local GET gets the operator token' (Invoke-RestMethod -Uri 'http://localhost:17970/api/pair/household-key' -TimeoutSec 5).auth 'Bearer op-tok-9'
$r = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:17970/api/inference/pause' -ContentType 'application/json' -Body '{"a":1}' -TimeoutSec 5
Check 'local POST keeps method, body and content type' "$($r.auth)|$($r.method)|$($r.body)|$($r.ct)" 'Bearer op-tok-9|POST|{"a":1}|application/json'
Check 'another port gets nothing' (Invoke-RestMethod -Uri 'http://localhost:17971/api/search' -TimeoutSec 5).auth 'none'
Check 'explicit headers win' (Invoke-RestMethod -Uri 'http://localhost:17970/api/body' -Headers @{ Authorization = 'Bearer mine' } -TimeoutSec 5).auth 'Bearer mine'
$w = Invoke-WebRequest -Uri 'http://localhost:17970/api/vault' -UseBasicParsing -TimeoutSec 5
Check 'Invoke-WebRequest too' (($w.Content | ConvertFrom-Json).auth) 'Bearer op-tok-9'
"failures: $fails"
} finally { Stop-Process -Id $e1.Id,$e2.Id -ErrorAction SilentlyContinue; Remove-Item -Recurse -Force $T }
exit [int]($fails -gt 0)
