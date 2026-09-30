# The Windows launcher's `wyrd researcher gpu`: where the library's model runs (the companions' brain by
# default, its own server, another machine), moving it to another machine or back, and the suggestion in
# setup and doctor. Lifts the functions from wyrd.ps1 and runs them against a fake ResearchZosho (a sh
# script; run with pwsh on Linux or macOS). Every change goes through ResearchZosho's own commands: with
# 0.5.0 its setup offers the address and the service is restarted; with 0.5.1 `model use` switches the
# running service, with no setup and no restart.
# Run: pwsh -NoProfile -File scripts/tests/researcher_gpu_ps1_test.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ps1 = Join-Path $here 'packaging/windows/wyrd.ps1'
$errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($ps1, [ref]$null, [ref]$errs)
$names = 'Get-RzModelStatus', 'Get-RzDriveOf', 'Test-RzHasOwnModel', 'Test-RzLocalHost', 'ConvertTo-RzUrlKey', 'Get-RzWhere',
         'Get-RzGpuCards', 'Get-RzGpuSuggestion', 'Show-RzGpu', 'Restart-RzService', 'Set-RzDrive', 'Get-RzHostUrl',
         'Complete-RzGpu', 'Invoke-RzGpuHost', 'Invoke-RzGpuShared', 'Invoke-RzGpu', 'Read-ResearcherAnswer',
         'Get-RzVersion', 'Get-RzChatModel', 'Get-RzCurrentModel', 'Invoke-RzModelUse', 'Write-RzPlanAfter'
$lifted = @{}
foreach ($name in $names) {
    $fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true) | Select-Object -First 1
    if (-not $fn) { Microsoft.PowerShell.Utility\Write-Host "FAIL could not lift $name"; exit 1 }
    $lifted[$name] = $fn.Extent.Text
    Invoke-Expression $fn.Extent.Text
}
$fail = 0
function Check([string]$label, [bool]$ok) { if ($ok) { Microsoft.PowerShell.Utility\Write-Host "ok   $label" } else { Microsoft.PowerShell.Utility\Write-Host "FAIL $label"; $script:fail = 1 } }

$script:said = New-Object System.Collections.ArrayList
function _T { ($args -join ' ') }
function Write-Info($m) { [void]$script:said.Add("INFO $m") }
function Write-Ok($m) { [void]$script:said.Add("OK $m") }
function Write-Warn2($m) { [void]$script:said.Add("WARN $m") }
function Write-Err2($m) { [void]$script:said.Add("ERR $m") }
function Write-Host { param([Parameter(ValueFromRemainingArguments = $true)]$m) [void]$script:said.Add("HOST " + ($m -join ' ')) }
function Said { $script:said -join "`n" }
function Has([string]$s) { (Said).Contains($s) }

$T = Join-Path ([IO.Path]::GetTempPath()) ("rzgpu-" + [guid]::NewGuid())
New-Item -ItemType Directory $T | Out-Null
$calls = Join-Path $T 'calls'
$rz = Join-Path $T 'researchzosho'
@"
#!/bin/sh
T="$T"
case "`$1 `$2" in
  "version "*) echo "researchzosho `$(cat "`$T/rz.version")"; exit 0 ;;
  "model use")
    [ "`$(cat "`$T/rz.version")" = 0.5.0 ] && exit 2
    echo "rz model use `$3 `$4" >> "`$T/calls"
    grep -qxF "`$3" "`$T/rz.answers" 2>/dev/null || exit 1
    n=`$(cat "`$T/rz.nmodels")
    if [ -z "`$4" ] && [ "`$n" -gt 1 ]; then echo "  name the model"; exit 2; fi
    echo "`$3" > "`$T/rz.drive"; exit 0 ;;
  "model status")
    echo "  drive: `$(cat "`$T/rz.drive")"
    if [ -f "`$T/rz.own" ]; then echo "  model: Qwen3.8-27B on Vulkan"; else echo "  no model server of this machine's own (researchzosho model install sets one up)"; fi
    exit 0 ;;
  "model uninstall")
    echo "rz model uninstall" >> "`$T/calls"; rm -f "`$T/rz.own"
    [ -f "`$T/rz.prev" ] && mv "`$T/rz.prev" "`$T/rz.drive"
    exit 0 ;;
  "model install") echo "rz model install `$3 `$4 `$5" >> "`$T/calls"; exit 0 ;;
  "setup "*)
    shift; echo "rz setup `$* DRIVE=`$RESEARCHZOSHO_DRIVE MODEL=`$RESEARCHZOSHO_MODEL" >> "`$T/calls"
    [ -n "`$RESEARCHZOSHO_DRIVE" ] && echo "`$RESEARCHZOSHO_DRIVE" > "`$T/rz.drive"
    exit 0 ;;
esac
exit 2
"@ | Set-Content -NoNewline $rz
chmod +x $rz

function Get-ResearchZoshoCmd { $rz }
function Get-RzBrainUrl { 'http://127.0.0.1:8200' }
$VoicePort = 8201
$LlamaPidFile = 'drive.pid'; $VoicePidFile = 'voice.pid'
function Get-LlamaPid([string]$File) { if ($File -eq 'drive.pid') { 4242 } else { $null } }
$script:gpus = @(); $script:apps = @()
function nvidia-smi { if ("$args" -match 'query-gpu=') { $script:gpus } else { $script:apps } }
$script:answers = @()
function Test-RzHostAnswers([string]$Url) { $script:answers -contains $Url }
function Invoke-RestMethod { param($Uri, $TimeoutSec)
    if (-not ($script:answers -contains ($Uri -replace '/v1/models$', ''))) { throw "no answer" }
    [pscustomobject]@{ data = @($script:models | ForEach-Object { [pscustomobject]@{ id = $_ } }) }
}
function Answers([string[]]$urls) {   # the servers that answer, for wyrd (Test-RzHostAnswers) and for the fake's model use
    $script:answers = @($urls); Set-Content (Join-Path $T 'rz.answers') ($urls -join "`n")
}
function Models([string[]]$ids) { $script:models = @($ids); Set-Content (Join-Path $T 'rz.nmodels') "$($ids.Count)" }
$script:ver = '0.5.0'
function State([string]$drive, [switch]$Own) {
    Set-Content $calls ''
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $T 'rz.own'), (Join-Path $T 'rz.prev')
    Set-Content (Join-Path $T 'rz.drive') $drive
    if ($Own) { Set-Content (Join-Path $T 'rz.own') ''; Set-Content (Join-Path $T 'rz.prev') 'http://127.0.0.1:8200' }
    Set-Content (Join-Path $T 'rz.version') $script:ver
    $script:said.Clear(); Answers @(); Models @('embed', 'qwen3.8-27b')   # the proxy lists its embeddings server too
}
function Called([string]$s) { (Get-Content $calls -Raw).Contains($s) }
function Drive { (Get-Content (Join-Path $T 'rz.drive') -Raw).Trim() }

# Restart-RzService with no logon task: says so, starts nothing.
$env:USERPROFILE = $T; $script:said.Clear()
Restart-RzService
Check 'restart with no logon task: says the library is not a service here' (Has 'researcher.gpu.no_service')
$script:restarts = 0
function Restart-RzService { $script:restarts++ }

# -- comparing model servers --
Check 'localhost is this machine' ((ConvertTo-RzUrlKey 'http://localhost:8200') -eq 'local:8200')
Check '127.0.0.1 with /v1 is this machine' ((ConvertTo-RzUrlKey 'http://127.0.0.1:8200/v1') -eq 'local:8200')
Check 'another machine keeps its name, lower case' ((ConvertTo-RzUrlKey 'http://Dollhouse:8211/') -eq 'gpu-host:8211')
Check 'https without a port is 443' ((ConvertTo-RzUrlKey 'https://api.example.com/v1') -eq 'api.example.com:443')
Check 'a machine name -> its proxy on 8211' ((Get-RzHostUrl 'gpu-host') -eq 'http://gpu-host:8211')
Check 'a port named is kept' ((Get-RzHostUrl 'gpu-host:9000') -eq 'http://gpu-host:9000')
Check 'where: the brain -> shared' ((Get-RzWhere 'http://localhost:8200' 'http://127.0.0.1:8200') -eq 'shared')
Check 'where: unset -> the brain' ((Get-RzWhere '(unset)' 'http://127.0.0.1:8200') -eq 'shared')
Check 'where: the voice port counts as the brain' ((Get-RzWhere 'http://127.0.0.1:8201' 'http://127.0.0.1:8200') -eq 'shared')
Check 'where: its proxy here -> own' ((Get-RzWhere 'http://127.0.0.1:8211' 'http://127.0.0.1:8200') -eq 'own')
Check 'where: another machine -> other' ((Get-RzWhere 'http://gpu-host:8211' 'http://127.0.0.1:8200') -eq 'other')

# -- the cards --
$script:gpus = @('0, GPU-aaa, NVIDIA GeForce RTX 4090, 24564, 20010', '1, GPU-bbb, NVIDIA RTX A4000, 16376, 4')
$script:apps = @('GPU-aaa, 4242')
$cards = Get-RzGpuCards 'http://127.0.0.1:8200'
Check 'cards: the brain''s card' ($cards[0].Who -eq 'brain' -and $cards[0].Name -eq 'NVIDIA GeForce RTX 4090')
Check 'cards: an empty card is free' ($cards[1].Who -eq 'free' -and $cards[1].Total -eq '16376')

# -- the status screen --
State 'http://127.0.0.1:8200'; Invoke-RzGpu @()
Check 'status shared: says it shares the brain' (Has 'researcher.gpu.now_shared http://127.0.0.1:8200')
Check 'status shared: the cards' ((Has 'researcher.gpu.card 0 NVIDIA GeForce RTX 4090 24564 20010 researcher.gpu.card_brain') -and (Has 'researcher.gpu.card 1 NVIDIA RTX A4000 16376 4 researcher.gpu.card_free'))
Check 'status shared: why it matters' (Has 'researcher.gpu.why')
Check 'status shared: choosing a card is Linux only; the other-machine way' ((Has 'researcher.gpu.linux_only') -and (Has 'researcher.gpu.cmd_host') -and -not (Has 'researcher.gpu.cmd_card'))
Check 'status: changes nothing' (-not (Called 'rz ') -and $script:restarts -eq 0 -and $script:RzGpuRc -eq 0)
State 'http://127.0.0.1:8211' -Own; Invoke-RzGpu @()
Check 'status own: says so, and the way back' ((Has 'researcher.gpu.now_own http://127.0.0.1:8211') -and (Has 'researcher.gpu.brain http://127.0.0.1:8200') -and (Has 'researcher.gpu.cmd_shared'))
State 'http://gpu-host:8211'; Invoke-RzGpu @()
Check 'status other machine: says so, and the way back' ((Has 'researcher.gpu.now_other http://gpu-host:8211') -and (Has 'researcher.gpu.cmd_shared'))

# -- ResearchZosho 0.5.0 (State's default): its setup offers the address, then the service restarts --
# -- gpu <card>: not offered on Windows --
State 'http://127.0.0.1:8200'; Invoke-RzGpu @('1', '--yes')
Check 'gpu 1: not offered here, the other-machine way' ((Has 'WARN researcher.gpu.linux_only') -and (Has 'researcher.gpu.cmd_host'))
Check 'gpu 1: nothing installed, exit 1' (-not (Called 'model install') -and $script:RzGpuRc -eq 1)

# -- gpu <machine> --
State 'http://127.0.0.1:8200'; Invoke-RzGpu @('gpu-host', '--yes')
Check 'gpu gpu-host, nothing answers: refused with what to run there' ((Has 'ERR researcher.gpu.host_down http://gpu-host:8211') -and (Has 'researcher.gpu.host_prepare'))
Check 'gpu gpu-host, nothing answers: nothing changed' (-not (Called 'rz setup') -and $script:restarts -eq 0 -and $script:RzGpuRc -eq 1)
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); $script:restarts = 0
Invoke-RzGpu @('gpu-host', '--yes')
Check 'gpu gpu-host: its own setup with the address offered' (Called 'rz setup --yes --no-service --no-claude DRIVE=http://gpu-host:8211')
Check 'gpu gpu-host: the drive points there' ((Drive) -eq 'http://gpu-host:8211')
Check 'gpu gpu-host: the service restarts, exit 0' ($script:restarts -eq 1 -and $script:RzGpuRc -eq 0)
Check 'gpu gpu-host: RESEARCHZOSHO_DRIVE is not left in this shell' (-not (Test-Path Env:RESEARCHZOSHO_DRIVE))
Check '0.5.0: says the service restarts, never asks model use' ((Has 'researcher.gpu.plan_restart') -and -not (Has 'researcher.gpu.plan_use') -and -not (Called 'model use'))
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); Invoke-RzGpu @('gpu-host', 'qwen-b', '--yes')
Check 'gpu gpu-host <model>: its setup is offered that model too' ((Called 'DRIVE=http://gpu-host:8211 MODEL=qwen-b') -and -not (Test-Path Env:RESEARCHZOSHO_MODEL))

# -- gpu shared --
State 'http://127.0.0.1:8211' -Own; $script:restarts = 0; Invoke-RzGpu @('shared', '--yes')
Check 'gpu shared with its own server: model uninstall' (Called 'rz model uninstall')
Check 'gpu shared with its own server: back on the brain, no setup needed' ((Drive) -eq 'http://127.0.0.1:8200' -and -not (Called 'rz setup'))
Check 'gpu shared: the service restarts' ($script:restarts -eq 1)
State 'http://gpu-host:8211'; Invoke-RzGpu @('shared', '--yes')
Check 'gpu shared from another machine: setup offered the brain' (Called 'rz setup --yes --no-service --no-claude DRIVE=http://127.0.0.1:8200')
Check 'gpu shared from another machine: no uninstall' (-not (Called 'model uninstall'))
State 'http://127.0.0.1:8200'; $script:restarts = 0; Invoke-RzGpu @('shared', '--yes')
Check 'gpu shared when already shared: nothing to do' ((Has 'researcher.gpu.already_shared') -and -not (Called 'rz ') -and $script:restarts -eq 0)

# -- ResearchZosho 0.5.1: `model use` switches the running service; no setup, no restart --
$script:ver = '0.5.1'
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); $script:restarts = 0
Invoke-RzGpu @('gpu-host', '--yes')
Check '0.5.1 gpu gpu-host: model use, the one chat model named' (Called 'rz model use http://gpu-host:8211 qwen3.8-27b')
Check '0.5.1 gpu gpu-host: no setup run, no restart' (-not (Called 'rz setup') -and $script:restarts -eq 0)
Check '0.5.1 gpu gpu-host: says the running service takes it, no word about setup' ((Has 'researcher.gpu.plan_use') -and -not (Has 'researcher.gpu.plan_setup') -and -not (Has 'researcher.gpu.plan_restart'))
Check '0.5.1 gpu gpu-host: the drive points there, exit 0' ((Drive) -eq 'http://gpu-host:8211' -and $script:RzGpuRc -eq 0)
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); Models @('qwen-a', 'qwen-b', 'embed')
Invoke-RzGpu @('gpu-host', '--yes')
Check '0.5.1 gpu gpu-host, two chat models: ResearchZosho asks for the name, we say how' ((Called 'rz model use http://gpu-host:8211 ') -and (Has 'researcher.gpu.use_name gpu-host') -and $script:RzGpuRc -eq 1 -and (Drive) -eq 'http://127.0.0.1:8200')
$script:said.Clear(); Set-Content $calls ''; Invoke-RzGpu @('gpu-host', 'qwen-b', '--yes')
Check '0.5.1 gpu gpu-host <model>: that model' ((Called 'rz model use http://gpu-host:8211 qwen-b') -and $script:RzGpuRc -eq 0)
# A server with several models keeps the one the library uses now; one it does not offer is not guessed.
$rzHome = Join-Path $env:USERPROFILE '.researchzosho'; New-Item -ItemType Directory -Force -Path $rzHome | Out-Null
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); Models @('qwen-a', 'qwen-b', 'embed')
Set-Content (Join-Path $rzHome 'config') 'RESEARCHZOSHO_MODEL = qwen-b'
Invoke-RzGpu @('gpu-host', '--yes')
Check '0.5.1 gpu gpu-host, several models: keeps the one it uses now' ((Called 'rz model use http://gpu-host:8211 qwen-b') -and $script:RzGpuRc -eq 0)
State 'http://127.0.0.1:8200'; Answers @('http://gpu-host:8211'); Models @('qwen-a', 'qwen-b', 'embed')
Set-Content (Join-Path $rzHome 'config') 'RESEARCHZOSHO_MODEL = qwen-z'
Invoke-RzGpu @('gpu-host', '--yes')
Check '0.5.1 gpu gpu-host, several models, its model not offered: asks for the name' ((Has 'researcher.gpu.use_name gpu-host') -and $script:RzGpuRc -eq 1)
Remove-Item (Join-Path $rzHome 'config') -Force
State 'http://gpu-host:8211'; Answers @('http://127.0.0.1:8200'); Models @('brain'); $script:restarts = 0
Invoke-RzGpu @('shared', '--yes')
Check '0.5.1 gpu shared from another machine: model use on the brain' (Called 'rz model use http://127.0.0.1:8200 brain')
Check '0.5.1 gpu shared from another machine: no setup, no restart, back on the brain' (-not (Called 'rz setup') -and $script:restarts -eq 0 -and (Drive) -eq 'http://127.0.0.1:8200')
State 'http://127.0.0.1:8211' -Own; $script:restarts = 0; Invoke-RzGpu @('shared', '--yes')
Check '0.5.1 gpu shared with its own server: uninstall brings the brain back, nothing more' ((Called 'rz model uninstall') -and -not (Called 'model use') -and -not (Called 'rz setup') -and $script:restarts -eq 0 -and (Drive) -eq 'http://127.0.0.1:8200')
State 'http://127.0.0.1:8200'; Invoke-RzGpu @('shared', 'qwen-b', '--yes')
Check 'a model name after shared is a usage error' ((Has 'researcher.gpu.usage') -and $script:RzGpuRc -eq 2 -and -not (Called 'rz '))
$script:ver = '0.5.0'

# -- the suggestion (setup and doctor) --
State 'http://127.0.0.1:8200'; $s = @(Get-RzGpuSuggestion $rz)
Check 'suggest shared: it shares, why, the command' ($s.Count -eq 3 -and $s[0] -eq 'researcher.gpu.suggest_shares http://127.0.0.1:8200' -and $s[1] -eq 'researcher.gpu.suggest_why' -and $s[2] -eq 'researcher.gpu.suggest_cmd')
State 'http://127.0.0.1:8211' -Own
Check 'suggest own: nothing' (@(Get-RzGpuSuggestion $rz).Count -eq 0)
State 'http://gpu-host:8211'
Check 'suggest other machine: nothing' (@(Get-RzGpuSuggestion $rz).Count -eq 0)
$doctor = ($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Doctor' }, $true) | Select-Object -First 1).Extent.Text
Check 'doctor gives the suggestion, as information' ($doctor.Contains('Get-RzGpuSuggestion $rzg') -and $doctor.Contains('"  library      : "'))
$researcher = ($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Researcher' }, $true) | Select-Object -First 1).Extent.Text
Check 'setup ends with the suggestion' ($researcher.Contains('Get-RzGpuSuggestion $rz') -and $researcher.Contains('"gpu" {'))

# -- ASCII only (Windows PowerShell 5.1 reads a file without a BOM in the system codepage) and every key in the catalog --
$nonAscii = @($lifted.Keys | Where-Object { $lifted[$_] -match '[^\x00-\x7F]' })
Check "the new functions are ASCII$(if ($nonAscii) { ' (not: ' + ($nonAscii -join ', ') + ')' })" ($nonAscii.Count -eq 0)
$en = Get-Content (Join-Path $here 'scripts/i18n/wyrd_en.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$keys = @([regex]::Matches((Get-Content $ps1 -Raw), "researcher\.gpu\.[a-z_]+") | ForEach-Object { $_.Value } | Sort-Object -Unique)
$missing = @($keys | Where-Object { -not ($en.PSObject.Properties.Name -contains $_) })
Check "every researcher.gpu key in wyrd.ps1 is in wyrd_en.json$(if ($missing) { ' (missing: ' + ($missing -join ', ') + ')' })" ($missing.Count -eq 0)

Remove-Item -Recurse -Force $T
exit $fail
