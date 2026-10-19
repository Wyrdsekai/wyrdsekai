# Windows parity for the doctor's processor-load line (2026-10-19): when a companion model runs on
# the processor (a llama-server with no GPU offload), the share of the cores busy right now, warned
# about at nine tenths. Runs the launcher's function against stand-in CIM answers.
Set-StrictMode -Version Latest
$ps1 = Get-Content (Join-Path $PSScriptRoot "../../packaging/windows/wyrd.ps1") -Raw
function Write-Warn2 { param($m) Write-Host "[warn] $m" }
$script:WyrdLocale = 'en'
$script:WyrdI18nDir = (Resolve-Path (Join-Path $PSScriptRoot "../i18n")).Path
$script:I18nCat = $null; $script:I18nCatEn = $null
foreach ($fn in @('Get-JProp', 'Import-I18nCatalog', '_T', 'Get-LocalModelOnCpu', 'Invoke-DoctorHostLoad')) {
    $m = [regex]::Match($ps1, "(?ms)^function $fn(\([^)]*\))? \{.*?^\}")
    if (-not $m.Success) { "FAIL $fn not found in wyrd.ps1"; exit 1 }
    Invoke-Expression $m.Value
}
$script:fails = 0
function Check($name, $ok, $got) { if ($ok) { "ok   $name" } else { "FAIL $name (got: $got)"; $script:fails++ } }

# Stand-in CIM: the processes and the processors the host would report.
$script:procs = @(); $script:load = 0
function Get-CimInstance {
    param($ClassName, $Filter, $ErrorAction)
    $cls = if ($ClassName) { $ClassName } else { $args[0] }
    if ($cls -eq 'Win32_Process') { return $script:procs }
    if ($cls -eq 'Win32_Processor') { return @([pscustomobject]@{ LoadPercentage = $script:load; NumberOfLogicalProcessors = 32 }) }
    return @()
}

$script:procs = @()
$out = Invoke-DoctorHostLoad *>&1 | Out-String
Check 'no model on the processor: silent' ($out.Trim() -eq '') $out

$script:procs = @([pscustomobject]@{ Name = 'llama-server.exe'; CommandLine = 'llama-server.exe --model x.gguf --port 11525 --n-gpu-layers 99' })
$out = Invoke-DoctorHostLoad *>&1 | Out-String
Check 'a model on the GPU: silent' ($out.Trim() -eq '') $out

$script:procs = @([pscustomobject]@{ Name = 'llama-server.exe'; CommandLine = 'llama-server.exe --model x.gguf --port 11525 --parallel 1' })
$script:load = 94
$out = Invoke-DoctorHostLoad *>&1 | Out-String
Check 'a pinned processor is warned about' ($out.Contains('[warn] a companion model runs on this machine') -and $out.Contains('94% of its 32 cores')) $out

$script:load = 12
$out = Invoke-DoctorHostLoad *>&1 | Out-String
Check 'an easy load is said plainly' ($out.Contains('processor    : a companion model runs on this machine') -and $out.Contains('12% of its 32 cores')) $out

if ($script:fails -eq 0) { "all passed"; exit 0 } else { "$($script:fails) failed"; exit 1 }
