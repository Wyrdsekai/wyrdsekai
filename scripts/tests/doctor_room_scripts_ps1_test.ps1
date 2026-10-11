# Windows parity for the doctor's room-scripts line (2026-10-09): a room script a companion wrote
# that failed three times is set aside (BrokenRoomScripts writes <data>\scripts\.broken\state.json),
# and `wyrd doctor` names it. Runs the launcher's function against a record with one script set
# aside and one still counting, with no record, and with a record that is not JSON.
Set-StrictMode -Version Latest
$ps1 = Get-Content (Join-Path $PSScriptRoot "../../packaging/windows/wyrd.ps1") -Raw
function Write-Warn2 { param($m) Write-Host "[warn] $m" }
$script:WyrdLocale = 'en'
$script:WyrdI18nDir = (Resolve-Path (Join-Path $PSScriptRoot "../i18n")).Path
$script:I18nCat = $null; $script:I18nCatEn = $null
foreach ($fn in @('Get-JProp', 'Import-I18nCatalog', '_T', 'Invoke-DoctorRoomScripts')) {
    $m = [regex]::Match($ps1, "(?ms)^function $fn(\([^)]*\))? \{.*?^\}")
    if (-not $m.Success) { "FAIL $fn not found in wyrd.ps1"; exit 1 }
    Invoke-Expression $m.Value
}
$script:fails = 0
function Check($name, $ok, $got) { if ($ok) { "ok   $name" } else { "FAIL $name (got: $got)"; $script:fails++ } }
$T = Join-Path ([IO.Path]::GetTempPath()) ("wyrd-doctor-rooms-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $T | Out-Null
$DataDir = $T

$out = Invoke-DoctorRoomScripts *>&1 | Out-String
Check 'no record: silent' ($out.Trim() -eq '') $out

New-Item -ItemType Directory -Path (Join-Path $T "scripts\.broken") | Out-Null
Set-Content (Join-Path $T "scripts\.broken\state.json") @'
{
  "quiet-alcove": {"mtime": 1, "failures": 4, "lastError": "onEnter: ReferenceError: emit is not defined", "told": "x", "setAside": true},
  "hearth": {"mtime": 3, "failures": 3, "lastError": "onActivate: SyntaxError: Unnamed:2:65 Missing close quote\n  emit('narrate', { text: 'The water doesn't move.' });\n                 ^", "told": "z", "setAside": true},
  "study": {"mtime": 2, "failures": 1, "lastError": "onSay: TypeError: entity.getProperty is not a function", "told": "y"}
}
'@
$out = Invoke-DoctorRoomScripts *>&1 | Out-String
Check 'two set aside: warned with the right count' ($out.Contains('[warn] 2 room script(s) set aside after failing three times')) $out
Check 'a multi-line error is shown on one line' ($out.Contains('hearth — onActivate: SyntaxError: Unnamed:2:65 Missing close quote emit(')) $out
Check 'the set-aside room and its error are named' ($out -like '*quiet-alcove — onEnter: ReferenceError: emit is not defined*') $out
Check 'a script still counting is not listed' ($out -notlike '*study*') $out

Set-Content (Join-Path $T "scripts\.broken\state.json") '{not json'
$out = Invoke-DoctorRoomScripts *>&1 | Out-String
Check 'an unreadable record: silent' ($out.Trim() -eq '') $out

Remove-Item -Recurse -Force $T
if ($script:fails -eq 0) { "all passed"; exit 0 } else { "$($script:fails) failed"; exit 1 }
