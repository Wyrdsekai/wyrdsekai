# wyrdsekai one-line installer for Windows - https://wyrdsekai.org/install.ps1
#
#   irm https://wyrdsekai.org/install.ps1 | iex
#
# Downloads the 0.2.2 release artifact from GitHub, verifies it against the
# release's SHA256SUMS, and installs it. Nothing here is served from
# wyrdsekai.org except this script - the installer and the checksums both come
# from the same GitHub release, so this script cannot substitute a payload the
# checksums don't match.

$ErrorActionPreference = 'Stop'

$Version = '0.2.2'
$Base    = "https://github.com/Wyrdsekai/wyrdsekai/releases/download/v$Version"
$Art     = "Wyrdsekai-$Version.msi"

function Say([string]$m) { Write-Host "[wyrdsekai] $m" -ForegroundColor Cyan }
function Die([string]$m) { Write-Host "[wyrdsekai] $m" -ForegroundColor Red; exit 1 }

# The published .msi is x64. Say so rather than installing something that
# cannot run: an installer that "succeeds" onto the wrong architecture is a
# worse outcome than a refusal that names the reason.
$arch = $env:PROCESSOR_ARCHITECTURE
if ($arch -ne 'AMD64') {
    Die "The prebuilt Windows package is x64 only (this is $arch) - build from source: https://github.com/Wyrdsekai/wyrdsekai#build-from-source"
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("wyrdsekai-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    $msi  = Join-Path $tmp $Art
    $sums = Join-Path $tmp 'SHA256SUMS'

    Say "downloading $Art (~1.2-1.7 GB)..."
    # ProgressPreference throttles Invoke-WebRequest badly on large files;
    # silencing it is the documented way to keep the download at full speed.
    $prevProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri "$Base/$Art"     -OutFile $msi  -UseBasicParsing
        Invoke-WebRequest -Uri "$Base/SHA256SUMS" -OutFile $sums -UseBasicParsing
    } finally {
        $ProgressPreference = $prevProgress
    }

    Say 'verifying checksum...'
    # SHA256SUMS lines are "<hash>  <filename>". Match on the exact artifact
    # name so a rename or a partial file cannot pass on someone else's line.
    $line = Select-String -Path $sums -Pattern ("\s" + [regex]::Escape($Art) + "$") |
            Select-Object -First 1
    if (-not $line) { Die "no checksum for $Art in SHA256SUMS - refusing to install." }
    $expected = ($line.Line -split '\s+')[0]
    $actual   = (Get-FileHash -Path $msi -Algorithm SHA256).Hash

    if ($actual -ine $expected) {
        Die "CHECKSUM MISMATCH - refusing to install. Re-run, and if it persists, report it.`n  expected $expected`n  got      $actual"
    }
    Say 'checksum OK.'

    Say 'installing (Windows will ask to elevate)...'
    # -Verb RunAs raises the UAC prompt rather than failing obscurely when the
    # one-liner is run from an ordinary shell, which is how most people run it.
    $p = Start-Process -FilePath 'msiexec.exe' `
                       -ArgumentList @('/i', "`"$msi`"", '/passive', '/norestart') `
                       -Verb RunAs -Wait -PassThru
    # 3010 = success, reboot required. Treating it as failure would be wrong.
    if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) {
        Die "msiexec exited with $($p.ExitCode) - the install did not complete."
    }
    if ($p.ExitCode -eq 3010) { Say 'installed - a reboot is required to finish.' }
    else { Say 'installed.' }

    Say 'Next:  wyrd setup'
    Say 'verify anytime: the checksums live with the release -'
    Say "  $Base/SHA256SUMS"
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
