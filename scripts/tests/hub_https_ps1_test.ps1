# The Windows launcher's pinned HTTPS to the household's hub: the doctor's probe (Test-HubHttps) and the POSTs
# of `wyrd federate join --request` (Invoke-PinnedHubPost). Lifts both from wyrd.ps1 and runs them against
# small python HTTPS servers with certificates made here: the household's, an impostor's (another CA) and an
# expired one. On Windows the same checks run again under Windows PowerShell 5.1, which runs a certificate
# callback on a thread with no runspace (the reason the check is compiled).
# Run: pwsh -NoProfile -File scripts/tests/hub_https_ps1_test.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ps1 = Join-Path $here 'packaging/windows/wyrd.ps1'
$errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($ps1, [ref]$null, [ref]$errs)
$lifted = foreach ($name in 'Initialize-WyrdHubHttps', 'Test-HubHttps', 'Invoke-PinnedHubPost') {
    $fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true) | Select-Object -First 1
    if (-not $fn) { Write-Host "FAIL could not lift $name"; exit 1 }
    $fn.Extent.Text
}

$T = Join-Path ([IO.Path]::GetTempPath()) ("hubhttps-" + [guid]::NewGuid())
New-Item -ItemType Directory $T | Out-Null
$X = 'System.Security.Cryptography.X509Certificates'

function New-Key { [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve+NamedCurves]::nistP256) }
function New-Ca([string]$Cn) {
    $req = New-Object "$X.CertificateRequest" "CN=$Cn", (New-Key), ([System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $req.CertificateExtensions.Add((New-Object "$X.X509BasicConstraintsExtension" $true, $false, 0, $true))
    $req.CertificateExtensions.Add((New-Object "$X.X509KeyUsageExtension" ([System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]'KeyCertSign, CrlSign'), $true))
    $req.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-30), [DateTimeOffset]::UtcNow.AddYears(5))
}
# A leaf for 127.0.0.1 signed by $Ca, written with the CA after it (the chain a node sends) and its key.
function New-Leaf($Ca, [string]$Name, [DateTimeOffset]$From, [DateTimeOffset]$To) {
    $key = New-Key
    $req = New-Object "$X.CertificateRequest" "CN=$Name", $key, ([System.Security.Cryptography.HashAlgorithmName]::SHA256)
    $san = New-Object "$X.SubjectAlternativeNameBuilder"
    $san.AddIpAddress([System.Net.IPAddress]::Loopback)
    $req.CertificateExtensions.Add($san.Build())
    $serial = [byte[]]::new(16); [System.Security.Cryptography.RandomNumberGenerator]::Fill($serial); $serial[0] = 0x3F
    $leaf = $req.Create($Ca, $From, $To, $serial)
    Set-Content -NoNewline -Path (Join-Path $T "$Name.pem") -Value ($leaf.ExportCertificatePem() + "`n" + $Ca.ExportCertificatePem() + "`n")
    Set-Content -NoNewline -Path (Join-Path $T "$Name.key") -Value $key.ExportPkcs8PrivateKeyPem()
}
function Get-Fp($Cert) { [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($Cert.RawData)).ToLower() }

$hhCa = New-Ca 'Wyrdsekai household CA test'
$other = New-Ca 'Someone else CA'
$now = [DateTimeOffset]::UtcNow
New-Leaf $hhCa 'hub' $now.AddDays(-1) $now.AddDays(200)
New-Leaf $other 'impostor' $now.AddDays(-1) $now.AddDays(200)
New-Leaf $hhCa 'expired' $now.AddDays(-20) $now.AddDays(-2)

Set-Content -Path (Join-Path $T 'serve.py') -Value @'
import http.server, json, ssl, sys
cert, key, log = sys.argv[1:4]
class H(http.server.BaseHTTPRequestHandler):
    def answer(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        self.answer(200, {"status": "ok"})
    def do_POST(self):
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        with open(log, "ab") as f:
            f.write(self.path.encode() + b" " + data + b"\n")
        self.answer(403 if self.path == "/refuse" else 200, {"path": self.path, "got": json.loads(data or b"null")})
    def log_message(self, *a):
        pass
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(cert, key)
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
print(srv.server_address[1], flush=True)
srv.serve_forever()
'@

$py = (Get-Command python3, python -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $py) { Write-Host "FAIL no python to serve HTTPS"; exit 1 }
$servers = @{}
foreach ($name in 'hub', 'impostor', 'expired') {
    $out = Join-Path $T "$name.port"
    $p = Start-Process -FilePath $py -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError (Join-Path $T "$name.err") `
        -ArgumentList @((Join-Path $T 'serve.py'), (Join-Path $T "$name.pem"), (Join-Path $T "$name.key"), (Join-Path $T "$name.log"))
    $port = $null
    for ($i = 0; $i -lt 100 -and -not $port; $i++) {
        Start-Sleep -Milliseconds 100
        $port = (Get-Content $out -ErrorAction SilentlyContinue | Select-Object -First 1)
    }
    if (-not $port) { Write-Host "FAIL the $name server did not start: $(Get-Content (Join-Path $T "$name.err") -Raw)"; exit 1 }
    $servers[$name] = @{ Process = $p; Port = [int]$port; Log = (Join-Path $T "$name.log") }
}
$closed = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback), 0
$closed.Start(); $closedPort = $closed.LocalEndpoint.Port; $closed.Stop()

# The checks, run in a fresh shell each time so each one compiles the class itself.
$checks = ($lifted -join "`n`n") + @'


$fail = 0
function Check([string]$label, [bool]$ok) { if ($ok) { Write-Host "ok   $label" } else { Write-Host "FAIL $label"; $script:fail = 1 } }
function Logged($log) { if (Test-Path $log) { (Get-Content $log -Raw) } else { '' } }
$fp = $env:HUB_FP; $otherFp = $env:OTHER_FP
$hub = [int]$env:HUB_PORT; $impostor = [int]$env:IMPOSTOR_PORT; $expired = [int]$env:EXPIRED_PORT

$r = Test-HubHttps -Fingerprint $fp -Port $hub
Check "doctor: the household's certificate answers OK with its days left ($r)" ($r -match '^OK (19[89]|200)$')
$r = Test-HubHttps -Fingerprint (($fp.ToUpper() -split '(..)' | Where-Object { $_ }) -join ':') -Port $hub
Check "doctor: a fingerprint written AA:BB:.. in capitals pins the same CA ($r)" ($r -like 'OK *')
$r = Test-HubHttps -Fingerprint $fp -Port $impostor
Check "doctor: another CA's certificate is BADCERT ($r)" ($r -like 'BADCERT issued by CN=Someone else CA*')
$r = Test-HubHttps -Fingerprint $fp -Port $expired
Check "doctor: an expired household certificate is BADCERT ($r)" ($r -eq 'BADCERT NotTimeValid')
$r = Test-HubHttps -Fingerprint $otherFp -Port $hub
Check "doctor: pinned to another CA, the household's certificate is BADCERT ($r)" ($r -like 'BADCERT *')
$r = Test-HubHttps -Fingerprint $fp -Port ([int]$env:CLOSED_PORT) -TimeoutMs 2000
Check "doctor: nothing listening is DOWN ($r)" ($r -like 'DOWN *')

$key = 'k-' + [guid]::NewGuid()
$a = Invoke-PinnedHubPost -Uri "https://127.0.0.1:$hub/api/pair/key" -Body (@{ deviceName = 'w'; key = $key } | ConvertTo-Json -Compress) -Fingerprint $fp
Check "join: the hub gets the key and its answer comes back as an object" ($a.path -eq '/api/pair/key' -and $a.got.key -eq $key)

$sent = $false
try { Invoke-PinnedHubPost -Uri "https://127.0.0.1:$impostor/api/pair/key" -Body (@{ key = $key } | ConvertTo-Json -Compress) -Fingerprint $fp | Out-Null; $sent = $true } catch { }
Check "join: another CA's server is refused" (-not $sent)
Check "join: the impostor never received the key" (-not (Logged $env:IMPOSTOR_LOG).Contains($key))

$sent = $false
try { Invoke-PinnedHubPost -Uri "https://127.0.0.1:$hub/api/pair/key" -Body (@{ key = "$key-2" } | ConvertTo-Json -Compress) -Fingerprint $otherFp | Out-Null; $sent = $true } catch { }
Check "join: pinned to another CA, the household's hub is refused" (-not $sent)
Check "join: ... and the key was not sent to it" (-not (Logged $env:HUB_LOG).Contains("$key-2"))

$sent = $false
try { Invoke-PinnedHubPost -Uri "https://127.0.0.1:$expired/api/pair/key" -Body (@{ key = "$key-3" } | ConvertTo-Json -Compress) -Fingerprint $fp | Out-Null; $sent = $true } catch { }
Check "join: an expired certificate is refused and gets nothing" (-not $sent -and -not (Logged $env:EXPIRED_LOG).Contains("$key-3"))

$threw = $false
try { Invoke-PinnedHubPost -Uri "https://127.0.0.1:$hub/refuse" -Body '{}' -Fingerprint $fp | Out-Null } catch { $threw = $true }
Check "join: a refusal from the hub (403) is an error, as Invoke-RestMethod's was" $threw
exit $fail
'@
$checksFile = Join-Path $T 'checks.ps1'
Set-Content -Path $checksFile -Value $checks -Encoding ascii

$env:HUB_FP = Get-Fp $hhCa; $env:OTHER_FP = Get-Fp $other
$env:HUB_PORT = $servers.hub.Port; $env:IMPOSTOR_PORT = $servers.impostor.Port; $env:EXPIRED_PORT = $servers.expired.Port
$env:CLOSED_PORT = $closedPort
$env:HUB_LOG = $servers.hub.Log; $env:IMPOSTOR_LOG = $servers.impostor.Log; $env:EXPIRED_LOG = $servers.expired.Log

$fail = 0
$shells = @(@{ Label = 'pwsh ' + $PSVersionTable.PSVersion; Exe = (Get-Process -Id $PID).Path; Args = @('-NoProfile', '-File', $checksFile) })
if ($IsWindows) {
    $shells += @{ Label = 'Windows PowerShell 5.1'; Exe = 'powershell.exe'; Args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $checksFile) }
}
try {
    foreach ($sh in $shells) {
        Write-Host "-- $($sh.Label)"
        & $sh.Exe @($sh.Args) | ForEach-Object { Write-Host "   $_" }
        if ($LASTEXITCODE -ne 0) { $fail = 1 }
    }
} finally {
    foreach ($s in $servers.Values) { try { $s.Process.Kill() } catch { } }
    Remove-Item -Recurse -Force $T -ErrorAction SilentlyContinue
}
if ($fail) { Write-Host "FAILED"; exit 1 }
Write-Host "all passed"
