# fetch-build-assets.ps1 — Windows port of packaging/fetch-build-assets.sh
#
# Usage: .\packaging\windows\fetch-build-assets.ps1 [-Force]
#
# WHY THIS EXISTS
# ---------------
# The .deb and .pkg builds call fetch-build-assets.sh + fetch-classifier-encoder.sh,
# so they can be built from a clean checkout. build-msi.ps1 had no equivalent: it
# staged core\src\main\resources\models straight from source and hard-failed with
# "[fatal] SetFit encoder ONNX missing from payload". Those files are large model
# weights, correctly excluded from the public repository — so a public clone could
# build a Linux or macOS installer but NOT a Windows one, and the failure came
# after several minutes of gradle with no MSI and no obvious cause.
#
# It looked like it worked once only by accident: a tree copied from a machine
# where the Linux build had already fetched them carried the files along, and
# re-exporting removed them again.
#
# Reads the SAME manifests as the bash fetchers, so the pins cannot drift:
#   packaging\build-assets.json  — embedding models, tokenizers, V8 voice vectors
#   models-index.json            — the SetFit classifier encoder
#
# Every download is verified against the manifest's sha256. A file that is
# already present and correct is left alone; one that is present and WRONG is
# re-fetched rather than trusted.

param(
    [switch]$Force,
    [string]$Root = (Get-Location).Path
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"   # Invoke-WebRequest is ~10x faster without it

function Write-Fetch($msg) { Write-Host "[fetch-assets] $msg" }

function Get-Sha256($path) {
    (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower()
}

# Try each candidate URL in turn; verify the pinned hash whichever answered.
# HuggingFace first (canonical), then wyrdsekai.org (reachable when HF is not —
# HF's Xet CDN has been known to refuse plain clients).
function Get-Asset {
    param(
        [string[]] $Urls,
        [string]   $Dest,
        [string]   $Sha256,
        [string]   $Label
    )
    if ((Test-Path $Dest) -and -not $Force) {
        if ([string]::IsNullOrWhiteSpace($Sha256) -or $Sha256 -eq "-") {
            Write-Fetch "$Label already present (no pin to check)"
            return $true
        }
        if ((Get-Sha256 $Dest) -eq $Sha256.ToLower()) {
            Write-Fetch "$Label already present, sha256 ok"
            return $true
        }
        # Present but WRONG. A stale or truncated file is worse than none: the
        # build would embed it and nothing downstream would notice.
        Write-Fetch "$Label present but sha256 MISMATCH — refetching"
        Remove-Item $Dest -Force
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $Dest -Parent) | Out-Null
    $tmp = "$Dest.partial"
    foreach ($url in $Urls) {
        if ([string]::IsNullOrWhiteSpace($url) -or $url -eq "-") { continue }
        Write-Fetch "$Label from $url"
        try {
            Remove-Item $tmp -Force -ErrorAction SilentlyContinue
            Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -TimeoutSec 1800
        } catch {
            Write-Fetch "  failed: $($_.Exception.Message)"
            continue
        }
        if (-not (Test-Path $tmp)) { continue }
        if ($Sha256 -and $Sha256 -ne "-") {
            $got = Get-Sha256 $tmp
            if ($got -ne $Sha256.ToLower()) {
                # Never keep a file that failed its pin — that is the whole point.
                Write-Fetch "  sha256 mismatch (want $Sha256, got $got) — discarding"
                Remove-Item $tmp -Force -ErrorAction SilentlyContinue
                continue
            }
        }
        Move-Item $tmp $Dest -Force
        Write-Fetch "  ok"
        return $true
    }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return $false
}

$manifest = Join-Path $Root "packaging\build-assets.json"
if (-not (Test-Path $manifest)) { throw "manifest not found: $manifest" }

$failed = @()

# ── 1. packaging\build-assets.json ────────────────────────────────────────────
$assets = (Get-Content $manifest -Raw | ConvertFrom-Json).assets
foreach ($a in $assets) {
    $dest = Join-Path $Root (Join-Path ($a.dest -replace '/','\') $a.file)
    $urls = @()
    if ($a.hf_repo -and $a.hf_revision -and $a.hf_path) {
        $urls += "https://huggingface.co/$($a.hf_repo)/resolve/$($a.hf_revision)/$($a.hf_path)"
    }
    if ($a.url) { $urls += $a.url }
    if (-not (Get-Asset -Urls $urls -Dest $dest -Sha256 $a.sha256 -Label $a.file)) {
        $failed += $a.file
    }
}

# ── 2. models-index.json — the SetFit classifier encoder ──────────────────────
# Separate manifest and separate bash fetcher upstream (fetch-classifier-encoder.sh);
# build-msi.ps1 hard-fails without this exact file, so it is not optional.
$index = Join-Path $Root "models-index.json"
if (Test-Path $index) {
    $idx = Get-Content $index -Raw | ConvertFrom-Json
    $entries = @()
    function Collect($node) {
        if ($null -eq $node) { return }
        if ($node -is [System.Management.Automation.PSCustomObject]) {
            if ($node.PSObject.Properties.Name -contains 'local_file') { $script:entries += $node }
            foreach ($p in $node.PSObject.Properties) { Collect $p.Value }
        } elseif ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
            foreach ($i in $node) { Collect $i }
        }
    }
    $script:entries = @()
    Collect $idx
    $setfit = $script:entries | Where-Object { $_.local_file -like "*setfit*.onnx" } | Select-Object -First 1
    if ($setfit) {
        $dest = Join-Path $Root "core\src\main\resources\models\$($setfit.local_file)"
        $urls = @()
        if ($setfit.hf_repo -and $setfit.hf_revision -and $setfit.published_file) {
            $urls += "https://huggingface.co/$($setfit.hf_repo)/resolve/$($setfit.hf_revision)/$($setfit.published_file)"
        }
        if ($setfit.url) { $urls += $setfit.url }
        if (-not (Get-Asset -Urls $urls -Dest $dest -Sha256 $setfit.sha256 -Label $setfit.local_file)) {
            $failed += $setfit.local_file
        }
    } else {
        Write-Fetch "WARNING: no setfit entry in models-index.json"
    }
}

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "[fetch-assets] FAILED to obtain:" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host "  Every source was tried. Check network access to huggingface.co and" -ForegroundColor Red
    Write-Host "  wyrdsekai.org, then re-run. Building without these produces an" -ForegroundColor Red
    Write-Host "  installer with a degraded classifier and a voice missing its" -ForegroundColor Red
    Write-Host "  steering vectors, which no runtime warning makes obvious." -ForegroundColor Red
    # NOT `exit` — this script is invoked as a scriptblock by build-msi.ps1
    # (execution policy gates script FILES), and `exit` there terminates the
    # HOST process: the build died with no message, no MSI and an empty log.
    throw "build assets missing: $($failed -join ', ')"
}

Write-Fetch "all build assets present and verified"
