# fetch-nats-server.ps1 — download nats-server.exe for bundling in the .msi
#
# Usage: .\fetch-nats-server.ps1 [-Version "2.12.7"] [-Out "packaging/windows/"]
#
# Mirrors the .deb's bundled-binary pattern (packaging/nats-server). Without
# this, the MSI installs the JVM app but Between can't start the bridge.
# Download from GitHub releases (Synadia/nats-server, MIT license).
#
# UNVERIFIED — needs to be run on a Windows machine. The .ps1 should work as
# written but has not been executed. See packaging/windows/build-msi.ps1
# header for context.

param(
    [string]$Version = "2.12.7",
    [string]$Out = "packaging/windows"
)

$ErrorActionPreference = "Stop"

$archive = "nats-server-v$Version-windows-amd64.zip"
$url     = "https://github.com/nats-io/nats-server/releases/download/v$Version/$archive"
$exePath = Join-Path $Out "nats-server.exe"

if (Test-Path $exePath) {
    Write-Host "[fetch-nats] already present: $exePath"
    exit 0
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$tmpZip = Join-Path $env:TEMP $archive
Write-Host "[fetch-nats] downloading $url..."
Invoke-WebRequest -Uri $url -OutFile $tmpZip

$tmpDir = Join-Path $env:TEMP "nats-server-$Version"
if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
Expand-Archive -Path $tmpZip -DestinationPath $tmpDir

# Archive layout: nats-server-v<ver>-windows-amd64/nats-server.exe
$exeInZip = Get-ChildItem -Path $tmpDir -Recurse -Filter "nats-server.exe" | Select-Object -First 1
if (-not $exeInZip) {
    Write-Error "nats-server.exe not found in $archive"
    exit 1
}

Copy-Item -Path $exeInZip.FullName -Destination $exePath -Force
Write-Host "[fetch-nats] $exePath ready ($([math]::Round((Get-Item $exePath).Length / 1MB, 1)) MB)"

Remove-Item -Force $tmpZip
Remove-Item -Recurse -Force $tmpDir
