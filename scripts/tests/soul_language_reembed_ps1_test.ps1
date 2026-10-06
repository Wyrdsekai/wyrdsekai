# Windows parity for `wyrd soul language <companion> [heal]` and `wyrd library reembed [study|status]`
# (2026-10-05): the same routes, the same words from the language files, and a 4xx read as a status,
# not as a crash, on Windows PowerShell 5.1 as well as pwsh. Needs python3 for the stand-in node.
Set-StrictMode -Version Latest
$py = (Get-Command python3 -ErrorAction SilentlyContinue)
if (-not $py) { "skip: python3 not found"; exit 0 }
$T = Join-Path ([IO.Path]::GetTempPath()) ("wyrd-soullang-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path "$T/data" | Out-Null
Set-Content "$T/data/operator.token" "op-tok-9"
$node = @'
import http.server, json, sys
REPORT = {"household": "en", "verifiable": True, "fragments": 1, "memoryNodes": 1, "items": [
    {"id": "frag-1", "kind": "fragment", "detected": "es", "strikes": 0, "mercied": False, "text": "El peso que llevaba ya no es el de antes."},
    {"id": "node-9", "kind": "memory", "detected": "es", "strikes": 3, "mercied": True, "text": "biblioteca - library"}]}
STATUS = {"running": False, "collection": "study", "done": 1280, "startedAt": "2026-10-05T10:00:00Z", "finishedAt": "2026-10-05T10:04:30Z"}
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, code, d):
        b = json.dumps(d).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _log(self):
        open(sys.argv[2], "a").write(self.command + " " + self.path + " " + str(self.headers.get("Authorization")) + "\n")
    def do_GET(self):
        self._log()
        if self.headers.get("Authorization") != "Bearer op-tok-9": return self._send(403, {"error": "Not authorized"})
        p = self.path.lower()
        if p in ("/api/soul/mia/language", "/api/soul/did:key:z6mka/language"): return self._send(200, REPORT)
        if p == "/api/library/reembed": return self._send(200, STATUS)
        self._send(404, {"error": "No soul found"})
    def do_POST(self):
        self._log()
        if self.headers.get("Authorization") != "Bearer op-tok-9": return self._send(403, {"error": "Not authorized"})
        p = self.path.lower()
        if p == "/api/soul/mia/language/heal": return self._send(202, REPORT)
        if p == "/api/soul/gone/language/heal": return self._send(409, {"error": "not running"})
        if p == "/api/library/reembed?collection=study": return self._send(202, {"collection": "study", "running": True})
        self._send(404, {"error": "No soul found"})
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
'@
Set-Content "$T/node.py" $node
$srv = Start-Process python3 -ArgumentList "$T/node.py",17972,"$T/requests" -PassThru
Start-Sleep -Seconds 1
try {
$RestPort = 17972
$DataDir = "$T/data"
$script:WyrdLocale = 'en'
$script:WyrdI18nDir = (Resolve-Path (Join-Path $PSScriptRoot "../i18n")).Path
$script:I18nCat = $null; $script:I18nCatEn = $null
$ps1 = Get-Content (Join-Path $PSScriptRoot "../../packaging/windows/wyrd.ps1") -Raw
function Write-Err2 { param($m) Write-Host "[err]  $m" }
foreach ($fn in 'Get-JProp','Import-I18nCatalog','_T','Show-UpdateUsage','Get-ApiBase','Get-SessionToken','Get-ApiToken','Read-WyrdApi','Invoke-SoulLanguage','Invoke-LibraryReembed') {
    $m = [regex]::Match($ps1, "(?ms)^function $fn(\([^)]*\))? \{.*?^\}")
    if (-not $m.Success) { "FAIL could not lift $fn"; exit 1 }
    Invoke-Expression $m.Value
}
$fails = 0
function Check($name, $ok, $got) { if ($ok) { "ok   $name" } else { "FAIL $name (got: $got)"; $script:fails++ } }
function Run([scriptblock]$b) { $script:rc = $null; $out = & { $script:rc = (& $b) } *>&1 | Out-String; return $out }

$out = Run { Invoke-SoulLanguage 'mia' $null }
Check 'the name goes to the node, which resolves it' ((Get-Content "$T/requests") -contains 'GET /api/soul/mia/language Bearer op-tok-9') (Get-Content "$T/requests" | Out-String)
Check 'the household language and the counts are said' ($rc -eq 0 -and $out -like '*household language: en*' -and $out -like '*1 fragment(s), 1 memory node(s)*') $out
Check 'each note shows what it reads as and where it stands' ($out.Contains('[fragment] frag-1 reads as es, not tried yet') -and $out.Contains('[memory] node-9 reads as es, left in peace') -and $out.Contains('biblioteca')) $out
$out = Run { Invoke-SoulLanguage 'did:key:z6MkA' $null }
Check 'a DID is taken as it is' ($rc -eq 0 -and ((Get-Content "$T/requests") -contains 'GET /api/soul/did:key:z6MkA/language Bearer op-tok-9')) (Get-Content "$T/requests" | Out-String)
$out = Run { Invoke-SoulLanguage 'nobody' $null }
Check 'an unknown name is said so' ($rc -ne 0 -and $out -like '*No companion named*') $out
$env:WYRDSEKAI_TOKEN = 'someone-else'
$out = Run { Invoke-SoulLanguage 'mia' $null }
Check 'a member who is not her bondholder is refused' ($rc -ne 0 -and $out -like '*Only the steward or her bondholder*') $out
$env:WYRDSEKAI_TOKEN = $null
$out = Run { Invoke-SoulLanguage 'mia' 'heal' }
Check 'heal posts to the node and says what is happening' ($rc -eq 0 -and $out -like '*Healing now*' -and ((Get-Content "$T/requests") -contains 'POST /api/soul/mia/language/heal Bearer op-tok-9')) $out
$out = Run { Invoke-SoulLanguage 'gone' 'heal' }
Check 'a companion not running here is said so' ($rc -ne 0 -and $out -like '*not running on this node*') $out
$out = Run { Invoke-SoulLanguage 'mia' 'dance' }
Check 'an unknown second word is refused' ($rc -eq 64 -and $out.Contains('[heal]')) $out
$out = Run { Invoke-SoulLanguage $null $null }
Check 'the usage names the verb' ($rc -eq 64 -and $out -like '*wyrd soul language <companion>*') $out

$out = Run { Invoke-LibraryReembed 'study' }
Check 'a start posts to the route for the study shelf' ((Get-Content "$T/requests") -contains 'POST /api/library/reembed?collection=study Bearer op-tok-9') (Get-Content "$T/requests" | Out-String)
Check 'a start says it started and how to follow it' ($rc -eq 0 -and $out -like "*Re-embed started on 'study'*" -and $out -like '*wyrd library reembed status*') $out
$out = Run { Invoke-LibraryReembed 'status' }
Check 'status reads the route' ((Get-Content "$T/requests") -contains 'GET /api/library/reembed Bearer op-tok-9') (Get-Content "$T/requests" | Out-String)
Check 'status says how far it came' ($rc -eq 0 -and $out -like '*finished: 1280 passage(s) done on study*') $out
$env:WYRDSEKAI_TOKEN = 'someone-else'
$out = Run { Invoke-LibraryReembed 'status' }
Check "a non-steward is told it is the steward's, and it fails" ($rc -ne 0 -and $out -like '*Only the steward can run this*') $out
$env:WYRDSEKAI_TOKEN = $null
$out = Run { Invoke-LibraryReembed 'everything' }
Check 'an unknown shelf is refused' ($rc -eq 64 -and $out.Contains('reembed [study|status]')) $out
$env:WYRDSEKAI_API_URL = 'http://127.0.0.1:17973'
$out = Run { Invoke-LibraryReembed 'status' }
Check 'a node that does not answer is said so, not crashed on' ($rc -eq 1 -and $out -like '*127.0.0.1:17973*') $out
$env:WYRDSEKAI_API_URL = $null
$out = Run { Show-UpdateUsage }
Check 'update --help prints the six usage lines, translated' ((($out -split "`n") | Where-Object { $_.Trim() }).Count -eq 6 -and $out.Contains((_T 'update.usage.now'))) $out
"failures: $fails"
} finally { Stop-Process -Id $srv.Id -ErrorAction SilentlyContinue; Remove-Item -Recurse -Force $T }
exit [int]($fails -gt 0)
