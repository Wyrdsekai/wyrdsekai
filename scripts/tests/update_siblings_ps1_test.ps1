# The Windows launcher's sibling step, by the siblings' update contract: lifts Invoke-SiblingUpdate and
# Update-Siblings from wyrd.ps1 and runs them against fake siblings (sh scripts; run with pwsh on Linux
# or macOS). Run: pwsh -NoProfile -File scripts/tests/update_siblings_ps1_test.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $here 'packaging/windows/wyrd.ps1'), [ref]$null, [ref]$errs)
foreach ($name in 'Invoke-SiblingUpdate', 'Update-Siblings') {
    $fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true) | Select-Object -First 1
    if (-not $fn) { Write-Host "FAIL could not lift $name"; exit 1 }
    Invoke-Expression $fn.Extent.Text
}
$script:said = New-Object System.Collections.ArrayList
function _T { ($args -join ' ') }
function Write-Info($m) { [void]$script:said.Add("INFO $m") }
function Write-Ok($m) { [void]$script:said.Add("OK $m") }
function Write-Warn2($m) { [void]$script:said.Add("WARN $m") }
function Test-VersionNewer([string]$a, [string]$b) { ([version]$a) -gt ([version]$b) }
$script:latest = @{}
function Get-LatestRelease([string]$Repo, [switch]$Fresh) { $script:latest[$Repo] }
$T = Join-Path ([IO.Path]::GetTempPath()) ("sib-" + [guid]::NewGuid())
New-Item -ItemType Directory $T | Out-Null
$calls = Join-Path $T 'calls'; Set-Content $calls ''
function New-Fake([string]$id, [string]$verword) {
    $p = Join-Path $T $id
    @"
#!/bin/sh
T="$T"; id=$id
mode=`$(cat "`$T/`$id.mode")
case "`$1" in
  $verword) echo "`$id `$(cat "`$T/`$id.version")"; exit 0 ;;
  update)
    if [ "`$2" = "--json" ]; then
      [ "`$mode" = contract ] || { echo "usage" >&2; exit 2; }
      cat "`$T/`$id.status"; exit 0
    fi
    if [ "`$2" = now ]; then
      echo "`$id update now `$3" >> "$calls"
      if [ "`$3" = "--json" ]; then cat "`$T/`$id.now"; fi
      exit `$(cat "`$T/`$id.now_rc")
    fi ;;
esac
exit 2
"@ | Set-Content -NoNewline $p
    chmod +x $p
    return $p
}
$cz = New-Fake 'cz' '--version'
function Set-Contract([string]$id, [string]$newer, [string]$can, [string]$upd, [int]$rc, [string]$now) {
    Set-Content (Join-Path $T "$id.mode") 'contract'
    Set-Content (Join-Path $T "$id.status") ('{"installed":"0.3.10","latest":"0.3.11","newer":' + $newer + ',"mode":"check","root":"C:\\x","canUpdate":' + $can + ',"updating":' + $upd + '}')
    Set-Content (Join-Path $T "$id.now_rc") "$rc"; Set-Content (Join-Path $T "$id.now") $now
}
function Set-Old([string]$id, [string]$ver, [int]$rc) {
    Set-Content (Join-Path $T "$id.mode") 'old'; Set-Content (Join-Path $T "$id.version") $ver; Set-Content (Join-Path $T "$id.now_rc") "$rc"
}
$fail = 0
function Check([string]$label, [bool]$ok) { if ($ok) { Write-Host "ok   $label" } else { Write-Host "FAIL $label"; $script:fail = 1 } }
function Run { Set-Content $calls ''; $script:said.Clear(); Invoke-SiblingUpdate 'CodeZaiku' $cz 'Wyrdsekai/codezaiku' '--version' }
function Called { (Get-Content $calls -Raw) }

Set-Contract 'cz' 'true' 'true' 'false' 0 '{"result":"updated","code":0,"from":"0.3.10","to":"0.3.11","finishesAfterExit":true,"note":""}'; Run
Check 'contract: newer + canUpdate -> update now --json' ((Called) -match 'cz update now --json')
Check 'contract: finishesAfterExit is said' (($script:said -join "`n") -match 'update.sibling.finishing CodeZaiku 0.3.10 0.3.11')
Set-Contract 'cz' 'true' 'false' 'false' 0 '{}'; Run
Check 'contract: canUpdate false -> not asked' (-not ((Called) -match 'update now'))
Check 'contract: canUpdate false -> the person is told' (($script:said -join "`n") -match 'WARN update.sibling.cannot CodeZaiku check')
Set-Contract 'cz' 'false' 'true' 'false' 0 '{}'; Run
Check 'contract: not newer -> not asked' (-not ((Called) -match 'update now'))
Set-Contract 'cz' 'true' 'true' 'false' 75 '{"result":"busy","code":75}'; Run
Check 'contract: exit 75 -> busy' (($script:said -join "`n") -match 'update.sibling.busy CodeZaiku')
Set-Contract 'cz' 'true' 'true' 'false' 1 '{"result":"failed","code":1,"note":"no network"}'; Run
Check 'contract: exit 1 -> failed with its note' (($script:said -join "`n") -match 'WARN update.sibling.failed CodeZaiku 0.3.10 no network')
Set-Old 'cz' '0.3.6' 0; $script:latest['Wyrdsekai/codezaiku'] = '0.3.10'; Run
Check 'before the contract: older -> plain update now' ((Called) -match 'cz update now')
Check 'before the contract: updated is said' (($script:said -join "`n") -match 'update.sibling.updated CodeZaiku 0.3.6 0.3.10')
Set-Old 'cz' '0.3.10' 0; Run
Check 'before the contract: current -> not asked' (-not ((Called) -match 'update now'))
$env:WYRDSEKAI_UPDATE_SIBLINGS = '0'; $script:said.Clear(); Update-Siblings; Remove-Item Env:WYRDSEKAI_UPDATE_SIBLINGS
Check 'WYRDSEKAI_UPDATE_SIBLINGS=0 -> skipped' (($script:said -join "`n") -match 'update.sibling.skipped')
Remove-Item -Recurse -Force $T
exit $fail
