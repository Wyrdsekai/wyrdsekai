# Builds Wyrdsekai.Tray.exe — the Windows desktop shell (tray + WebView2 + onboarding wizard).
# Requires the .NET SDK 10+ (LTS) on the build host (windows-node):  winget install Microsoft.DotNet.SDK.10
# Output: packaging/windows/tray/out/Wyrdsekai.Tray.exe  (self-contained single-file, win-x64)
#
# The MSI builder (build-msi.ps1) stages this exe into the install tree and repoints the
# Start-menu/desktop shortcut to it.

param(
    [string]$Version = $env:WYRDSEKAI_VERSION
)

$ErrorActionPreference = "Stop"
if (-not $Version) { $Version = "0.1.5" }

$proj = Join-Path $PSScriptRoot "tray\Wyrdsekai.Tray.csproj"
$out  = Join-Path $PSScriptRoot "tray\out"

$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if (-not $dotnet) {
    Write-Error "dotnet SDK not found. Install it first:  winget install Microsoft.DotNet.SDK.10"
    exit 1
}

Write-Host "[tray] dotnet publish $proj  (v$Version)"
& dotnet publish $proj `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:Version=$Version `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -o $out

if ($LASTEXITCODE -ne 0) { Write-Error "tray build failed ($LASTEXITCODE)"; exit 1 }

$exe = Join-Path $out "Wyrdsekai.Tray.exe"
if (-not (Test-Path $exe)) { Write-Error "expected $exe not produced"; exit 1 }
Write-Host "[ok] tray built -> $exe ($([math]::Round((Get-Item $exe).Length / 1MB, 1)) MB)"
