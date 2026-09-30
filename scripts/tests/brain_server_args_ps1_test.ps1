# The Windows launcher's single model runs two slots on one shared context pool (-np 2 --kv-unified),
# so a one-token typed question is answered while the other slot writes a long reply, and either slot
# may use the whole -c window. Lifts Start-BrainServer from wyrd.ps1 and runs it with Start-Process
# replaced by a stub that keeps the argument list; no llama-server, no model. The two-model drive and
# voice servers keep one slot each. Run with pwsh on Linux or macOS.
# Run: pwsh -NoProfile -File scripts/tests/brain_server_args_ps1_test.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ps1 = Join-Path $here 'packaging/windows/wyrd.ps1'
$errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($ps1, [ref]$null, [ref]$errs)
if ($errs -and $errs.Count -gt 0) { Write-Host "FAIL wyrd.ps1 does not parse: $($errs[0].Message)"; exit 1 }
function Lift([string]$name) {
    $fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true) | Select-Object -First 1
    if (-not $fn) { Write-Host "FAIL could not lift $name"; exit 1 }
    return $fn.Extent.Text
}
$fail = 0
function Check([string]$label, [bool]$ok) { if ($ok) { Write-Host "ok   $label" } else { Write-Host "FAIL $label"; $script:fail = 1 } }
function Has([string[]]$argv, [string[]]$seq) {   # the sequence appears in the argument list, in order and adjacent
    for ($i = 0; $i -le $argv.Count - $seq.Count; $i++) {
        $all = $true
        for ($j = 0; $j -lt $seq.Count; $j++) { if ($argv[$i + $j] -ne $seq[$j]) { $all = $false; break } }
        if ($all) { return $true }
    }
    return $false
}

# The window the launcher gives the brain, as wyrd.ps1 sets it.
$ctxAssign = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -eq '$BrainCtx' }, $false) | Select-Object -First 1
if (-not $ctxAssign) { Write-Host "FAIL could not find `$BrainCtx in wyrd.ps1"; exit 1 }
Invoke-Expression $ctxAssign.Extent.Text

$T = Join-Path ([IO.Path]::GetTempPath()) ("brainargs-" + [guid]::NewGuid())
New-Item -ItemType Directory $T | Out-Null
$AdaptersDir = Join-Path $T 'adapters'; $DataDir = $T
New-Item -ItemType Directory (Join-Path $AdaptersDir 'brain') | Out-Null
Set-Content (Join-Path $AdaptersDir 'brain/species.gguf') '' ; Set-Content (Join-Path $AdaptersDir 'brain/work.gguf') ''
$model = Join-Path $T 'brain.gguf'; Set-Content $model ''
$DrivePort = 8200; $CacheRam = '1024'; $LlamaServerExe = 'llama-server.exe'
$LlamaLog = Join-Path $T 'llama.log'; $LlamaPidFile = Join-Path $T 'drive.pid'; $VoicePidFile = Join-Path $T 'voice.pid'

function _T { ($args -join ' ') }
function Write-Info($m) { }
function Write-Warn2($m) { }
function Get-BrainModelPath { $model }
function Get-LlamaPid([string]$File) { $null }
function Test-PortListening([int]$Port) { $false }
$script:argv = $null
function Start-Process { param($FilePath, $ArgumentList, $WorkingDirectory, [switch]$PassThru, $WindowStyle, $RedirectStandardOutput, $RedirectStandardError)
    $script:argv = [string[]]$ArgumentList
    [pscustomobject]@{ Id = 4242 }
}

Invoke-Expression (Lift 'Start-BrainServer')
$env:LLAMA_CPU_MOE = '24'   # skip the residency planner
$started = Start-BrainServer
Remove-Item Env:LLAMA_CPU_MOE
Check 'the brain starts'                                ($started -eq $true -and $null -ne $script:argv)
Check 'two slots on one shared pool (-np 2 --kv-unified)' (Has $script:argv @('-np', '2', '--kv-unified'))
Check 'the whole 32768 window, not split or doubled'    ($BrainCtx -eq '32768' -and (Has $script:argv @('-c', '32768')))
Check 'no single-slot leftover'                         (-not (Has $script:argv @('-np', '1')))
Check 'the floor is still adapter 0'                    (Has $script:argv @('--lora-scaled', 'brain\species.gguf:1.0', '--lora-scaled', 'brain\work.gguf:0.0'))
Check 'residency flag kept'                             (Has $script:argv @('--n-cpu-moe', '24'))

# The two-model drive and voice servers are unchanged: one slot, no shared pool.
$pair = Lift 'Start-LlamaServer'
Check 'two-model servers: no shared pool'  (-not $pair.Contains('--kv-unified'))
Check 'two-model drive: still one slot'    ($pair.Contains('"-c", "$DriveCtx", "-np", "1"'))
Check 'two-model voice: still one slot'    ($pair.Contains('"-c", "$VoiceCtx", "-np", "1"'))

Remove-Item -Recurse -Force $T
exit $fail
