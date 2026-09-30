# The Windows launcher's `wyrd seed`, `wyrd phone invite`, `wyrd logs` and `wyrd inference restart`:
# lifts the functions from wyrd.ps1 and runs them against stubs that record what they were given.
# Run: pwsh -NoProfile -File scripts/tests/seed_phone_ps1_test.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script = Join-Path $here 'packaging/windows/wyrd.ps1'
$errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$errs)
foreach ($name in 'Write-SeedRefusal', 'Invoke-Seed', 'Invoke-Phone', 'Invoke-Inference') {
    $fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true) | Select-Object -First 1
    if (-not $fn) { Write-Host "FAIL could not lift $name"; exit 1 }
    Invoke-Expression $fn.Extent.Text
}
$script:said = New-Object System.Collections.ArrayList
function _T { ($args -join ' ') }
function Write-Info($m) { [void]$script:said.Add("INFO $m") }
function Write-Ok($m) { [void]$script:said.Add("OK $m") }
function Write-Warn2($m) { [void]$script:said.Add("WARN $m") }
function Write-Err2($m) { [void]$script:said.Add("ERR $m") }
function Write-Host { param([Parameter(ValueFromRemainingArguments = $true)] $m) [void]$script:said.Add("HOST $($m -join ' ')") }
function Get-ApiBase { 'http://localhost:7070' }
$T = Join-Path ([IO.Path]::GetTempPath()) ("seedps-" + [guid]::NewGuid())
New-Item -ItemType Directory $T | Out-Null
$env:USERPROFILE = $T
$script:token = 'steward-token'
function Get-SeedToken { $script:token }
$PASS = 'correct horse battery staple'
function Read-SeedPassphrase([bool]$Confirm) { $script:confirmAsked = $Confirm; $PASS }
$script:sent = $null; $script:code = 200; $script:answer = '{}'
function Send-SeedRequest([string]$Sub, [string]$Token, [string]$Json) {
    $script:sent = @{ Sub = $Sub; Token = $Token; Json = $Json }
    @{ Code = $script:code; Text = $script:answer }
}
$fail = 0
function Check([string]$label, [bool]$ok) { if ($ok) { [Console]::WriteLine("ok   $label") } else { [Console]::WriteLine("FAIL $label"); $script:fail = 1 } }
function Said { $script:said -join "`n" }
function Run-Seed([string[]]$argv) { $script:said.Clear(); $script:sent = $null; $script:Rest = $argv; Invoke-Seed }

$sealed = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('WSRS-sealed-bytes'))
$script:answer = '{"name":"Mia","entityId":"mia","did":"did:key:z6Mk","createdAt":"2026-09-28T10:00:00Z","bonds":1,"carriesKey":true,"hereAlready":false,"file":"' + $sealed + '","localCopy":"C:\\wyrd\\recovery-seed\\mia.wsrs"}'
$rc = Run-Seed @('generate', 'Mia')
$body = $script:sent.Json | ConvertFrom-Json
Check 'generate: succeeds' ($rc -eq 0)
Check 'generate: asks for the passphrase twice' ($script:confirmAsked -eq $true)
Check 'generate: the passphrase and the companion go in the body' ($body.passphrase -eq $PASS -and $body.companion -eq 'Mia')
Check 'generate: the steward token is used' ($script:sent.Token -eq 'steward-token' -and $script:sent.Sub -eq 'generate')
$saved = Get-ChildItem $T -Filter 'mia-recovery-seed-*.wsrs' | Select-Object -First 1
Check 'generate: the file lands in the profile folder' ($null -ne $saved)
Check 'generate: the file holds the sealed bytes' ($saved -and [IO.File]::ReadAllText($saved.FullName) -eq 'WSRS-sealed-bytes')
Check 'generate: says what to keep apart' ((Said) -match 'seed.keep_passphrase')
$rc = Run-Seed @('generate', 'Mia')
Check 'generate: never writes over an existing file' ($rc -eq 1 -and (Said) -match 'seed.out_exists')
Check 'generate: the existing file is untouched' ([IO.File]::ReadAllText($saved.FullName) -eq 'WSRS-sealed-bytes')

Push-Location $T
$rc = Run-Seed @('generate', 'Mia', '--out', 'relative.wsrs')
Pop-Location
Check 'generate --out: a relative path is the shell''s folder' (Test-Path (Join-Path $T 'relative.wsrs'))

$in = Join-Path $T 'in.wsrs'; [IO.File]::WriteAllText($in, 'seedfile')
$script:answer = '{"name":"Mia","entityId":"mia","did":"did:key:z6Mk","createdAt":"2026-09-28T10:00:00Z","bonds":1,"carriesKey":true,"hereAlready":true}'
$rc = Run-Seed @('verify', $in)
$body = $script:sent.Json | ConvertFrom-Json
Check 'verify: sends the file bytes, base64' ($body.file -eq [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('seedfile')))
Check 'verify: asks for the passphrase once' ($script:confirmAsked -eq $false)
Check 'verify: says whose seed and when' ((Said) -match 'seed.verified Mia 2026-09-28 1 seed.key_included')
Check 'verify: says she is already here' ((Said) -match 'seed.verified_here Mia')
$rc = Run-Seed @('restore', $in)
Check 'restore: asks the restore route and says she wakes' ($script:sent.Sub -eq 'restore' -and (Said) -match 'seed.restored Mia')

$script:code = 403; $script:answer = '{"error":"Steward role required"}'
$rc = Run-Seed @('restore', $in)
Check 'a member is told only the steward can' ($rc -eq 1 -and (Said) -match 'seed.err.forbidden')
$script:code = 409; $script:answer = '{"error":"name_taken","message":"x"}'
$rc = Run-Seed @('restore', $in)
Check 'a refusal is said in the launcher''s words' ((Said) -match 'seed.err.name_taken')
$script:code = 404; $script:answer = '{"error":"no_such_companion","message":"x","names":["Mia","Rose"]}'
$rc = Run-Seed @('generate', 'Nobody')
Check 'an unknown name lists who lives here' ((Said) -match 'seed.err.no_such_companion Mia, Rose')
$script:code = 0; $script:answer = ''
$rc = Run-Seed @('generate', 'Mia')
Check 'no server: says so' ((Said) -match 'seed.no_server')
$script:token = $null
$rc = Run-Seed @('generate', 'Mia')
Check 'no token: nothing is sent' ($null -eq $script:sent -and (Said) -match 'seed.no_token')
$rc = Run-Seed @('restore', (Join-Path $T 'none.wsrs'))
Check 'a missing file is named' ((Said) -match 'seed.file_missing')

# wyrd phone invite
$script:invite = @{ Rc = 0; Lines = @('[wyrd] conf target: x', '{"invite_url":"wyrdphone://relay.example/eyJ6b25lX2lkIjoibWlhIn0","payload":{}}') }
function Get-PhoneInviteOutput([string[]]$JavaArgs) { $script:javaArgs = $JavaArgs; $script:invite }
function Show-InviteQr([string]$Url) { $script:qr = $Url; $true }
$script:said.Clear(); $script:Rest = @('invite', '--relay', 'https://relay.example/register', '--fingerprint', 'ab12')
$rc = Invoke-Phone
Check 'phone invite: runs the relay''s phone-invite with the renamed flags' ((($script:javaArgs) -join ' ') -eq 'phone-invite --registration-url https://relay.example/register --fingerprint ab12')
Check 'phone invite: draws the QR of the invite' ($script:qr -eq 'wyrdphone://relay.example/eyJ6b25lX2lkIjoibWlhIn0')
Check 'phone invite: prints the link and how to use it' ($rc -eq 0 -and (Said) -match 'wyrdphone://relay.example/' -and (Said) -match 'phone.invite.paste_hint')
$script:invite = @{ Rc = 3; Lines = @() }; $script:said.Clear(); $script:Rest = @('invite'); $rc = Invoke-Phone
Check 'phone invite: a zone-less invite is refused in plain words' ($rc -eq 1 -and (Said) -match 'phone.invite.no_zone')
$script:invite = @{ Rc = 2; Lines = @() }; $script:said.Clear(); $rc = Invoke-Phone
Check 'phone invite: a relay failure is said' ($rc -eq 1 -and (Said) -match 'phone.invite.failed')

# wyrd inference restart
$script:calls = New-Object System.Collections.ArrayList
function Get-Conf { [ordered]@{} }
function Stop-LlamaServer { [void]$script:calls.Add('stop') }
function Start-LlamaServer { [void]$script:calls.Add('start') }
function Import-ConfEnv { [void]$script:calls.Add('conf') }
function Start-Sleep { }
$script:Rest = @('restart'); Invoke-Inference
Check 'inference restart: stops, reloads the settings, starts' (($script:calls -join ' ') -eq 'stop conf start')

$text = Get-Content $script -Raw
Check 'wyrd logs is the same verb as wyrd log' ($text -match '(?m)^\s+"logs"\s+\{ Invoke-Log \}')
Check 'wyrd seed and wyrd phone are dispatched' ($text -match '(?m)^\s+"seed"\s+\{' -and $text -match '(?m)^\s+"phone"\s+\{')
$stubs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$script:WindowsStubCommands' }, $true) | Select-Object -First 1
Check 'phone is no longer a Windows stub' ($stubs -and $stubs.Right.Extent.Text -notmatch '"phone"')
Remove-Item $T -Recurse -Force
exit $fail
