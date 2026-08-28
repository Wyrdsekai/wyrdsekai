# Wyrdsekai - Universal Installer for Windows (BUILDS FROM SOURCE)
#
# Usage:
#   .\install.ps1           (from a repo checkout)
#
# NOT the one-liner. `irm https://wyrdsekai.org/install.ps1 | iex` serves
# site/install.ps1, which downloads the prebuilt .msi and verifies it against
# the release's SHA256SUMS. This script is the from-source path.
#
# (This header used to point at wyrdsekai.dev, a domain that does not resolve,
# so the Windows instructions in the shipped repo pointed at nothing.)
#
# Options:
#   -Version VERSION        Install a specific version
#   -Dir PATH               Install directory (default: %LOCALAPPDATA%\wyrdsekai)
#   -Port PORT              Default port (default: 7070)
#   -WithCodeZaiku          Also install CodeZaiku (currently inert: not wired)
#   -Uninstall              Remove Wyrdsekai cleanly

param(
    [string]$Version = "",
    [string]$Dir = "",
    [int]$Port = 0,
    [switch]$WithCodeZaiku,
    [switch]$NoService,
    [switch]$Uninstall,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$REPO = "wyrdsekai/wyrdsekai"
$MIN_JAVA = 25
$DEFAULT_DIR = "$env:LOCALAPPDATA\wyrdsekai"
$DEFAULT_PORT = 7070

function Write-Info  { param($msg) Write-Host "[info] " -ForegroundColor Blue -NoNewline; Write-Host $msg }
function Write-Ok    { param($msg) Write-Host "[ok] " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-Warn  { param($msg) Write-Host "[warn] " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Write-Err   { param($msg) Write-Host "[error] " -ForegroundColor Red -NoNewline; Write-Host $msg }

if ($Help) {
    Get-Content $PSCommandPath | Select-Object -Skip 1 -First 13 | ForEach-Object { $_ -replace '^# ?' }
    exit 0
}

$InstallDir = if ($Dir) { $Dir } elseif ($env:WYRDSEKAI_HOME) { $env:WYRDSEKAI_HOME } else { $DEFAULT_DIR }
$EffectivePort = if ($Port -gt 0) { $Port } elseif ($env:WYRDSEKAI_PORT) { [int]$env:WYRDSEKAI_PORT } else { $DEFAULT_PORT }

# --- Platform ----------------------------------------------------------------

function Detect-Platform {
    $arch = if ([Environment]::Is64BitOperatingSystem) { "amd64" } else { "x86" }
    try {
        $procArch = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture
        if ($procArch -eq [System.Runtime.InteropServices.Architecture]::Arm64) { $arch = "arm64" }
    } catch {}
    Write-Info "Platform: windows-$arch"
    return $arch
}

# --- Java --------------------------------------------------------------------

function Check-Java {
    $javacmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javacmd) {
        return $false
    }
    try {
        $output = & java -version 2>&1 | Select-Object -First 1
        if ($output -match 'version "(\d+)') {
            $ver = [int]$Matches[1]
            if ($ver -ge $MIN_JAVA) {
                Write-Ok "Java $ver detected"
                return $true
            }
            Write-Warn "Java $ver found, but $MIN_JAVA+ required."
        }
    } catch {}
    return $false
}

function Install-Java {
    Write-Info "Java $MIN_JAVA+ is required."

    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Info "Installing via winget..."
        winget install EclipseAdoptium.Temurin.25.JRE --accept-source-agreements --accept-package-agreements
        # Refresh PATH
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")
        if (Check-Java) { return }
    }

    Write-Err "Install Java $MIN_JAVA+ from https://adoptium.net and re-run."
    exit 1
}

# --- Detect mode -------------------------------------------------------------

function Detect-Mode {
    $scriptDir = Split-Path -Parent $PSCommandPath -ErrorAction SilentlyContinue
    if ($scriptDir -and (Test-Path "$scriptDir\gradlew.bat") -and (Test-Path "$scriptDir\server\build.gradle.kts")) {
        return @{ Mode = "source"; RepoDir = $scriptDir }
    }
    return @{ Mode = "release"; RepoDir = $null }
}

# --- Download release --------------------------------------------------------

function Download-Release {
    param($Repo, $Ver, $Name, $Dest, $Arch)

    $artifact = "$Name-$Ver-windows-$Arch.zip"
    $url = "https://github.com/$Repo/releases/download/v$Ver/$artifact"

    Write-Info "Downloading $artifact..."

    $archive = [System.IO.Path]::GetTempFileName() + ".zip"
    try {
        Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
    } catch {
        return $false
    }

    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Expand-Archive -Path $archive -DestinationPath $Dest -Force
    Remove-Item $archive -Force
    return $true
}

# --- Model downloads ---------------------------------------------------------

function Download-Models {
    $modelsDir = "$InstallDir\models"
    New-Item -ItemType Directory -Force -Path $modelsDir | Out-Null

    # Embedding model (MiniLM-L6-v2 int8, ~22MB) - always-on semantic search
    $embedModel = "$modelsDir\minilm-l6-v2-q8.onnx"
    if (-not (Test-Path $embedModel)) {
        Write-Info "Downloading embedding model (MiniLM-L6-v2, ~22MB)..."
        try {
            Invoke-WebRequest -Uri "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" `
                -OutFile $embedModel -UseBasicParsing
            Write-Ok "Embedding model downloaded"
        } catch {
            Write-Warn "Embedding model download failed - semantic search will use BM25 fallback"
        }
    } else {
        Write-Ok "Embedding model already downloaded"
    }
    $tokenizer = "$modelsDir\minilm-tokenizer.json"
    if (-not (Test-Path $tokenizer)) {
        try {
            Invoke-WebRequest -Uri "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json" `
                -OutFile $tokenizer -UseBasicParsing 2>$null
        } catch {}
    }

    # Companion model (Qwen3.5-4B SSD-trained GGUF, ~2.6GB)
    $companionModel = "$modelsDir\wyrdsekai-3.5-4b-v10-q4km.gguf"
    if (-not (Test-Path $companionModel)) {
        Write-Info "Downloading companion model (Qwen3.5-4B SSD, ~2.6GB)..."
        try {
            Invoke-WebRequest -Uri "https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf/resolve/main/wyrdsekai-3.5-4b-v10-q4km.gguf" `
                -OutFile $companionModel -UseBasicParsing
            Write-Ok "Companion model downloaded"
        } catch {
            Write-Info "Falling back to base model (no SSD training)..."
            try {
                Invoke-WebRequest -Uri "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf" `
                    -OutFile $companionModel -UseBasicParsing
                Write-Ok "Base model downloaded"
            } catch {
                Write-Warn "Model download failed - download manually to $companionModel"
            }
        }
    } else {
        Write-Ok "Companion model already downloaded"
    }

    return $companionModel
}

# --- Build from source -------------------------------------------------------

function Build-FromSource {
    param($RepoDir, $Dest)

    Write-Info "Building from source..."
    Push-Location $RepoDir
    & .\gradlew.bat :server:installDist -q --no-daemon
    Pop-Location

    $dist = "$RepoDir\server\build\install\server"
    if (-not (Test-Path $dist)) {
        Write-Err "Build failed."
        exit 1
    }

    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Copy-Item -Path "$dist\*" -Destination $Dest -Recurse -Force

    # Copy scripts
    if (Test-Path "$RepoDir\scripts\rooms") {
        New-Item -ItemType Directory -Force -Path "$Dest\scripts" | Out-Null
        Copy-Item -Path "$RepoDir\scripts\rooms" -Destination "$Dest\scripts\" -Recurse -Force
    }

    Write-Ok "Built and installed from source"
}

# --- PATH setup --------------------------------------------------------------

function Setup-Path {
    $binDir = "$InstallDir\bin"
    $currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if ($currentPath -notlike "*$binDir*") {
        [Environment]::SetEnvironmentVariable("PATH", "$binDir;$currentPath", "User")
        $env:Path = "$binDir;$env:Path"
        Write-Ok "Added $binDir to user PATH"
        Write-Info "Open a new terminal for PATH to take effect."
    } else {
        Write-Ok "$binDir already in PATH"
    }
}

# --- Config ------------------------------------------------------------------

function Write-Config {
    param($ModelPath)
    $envFile = "$InstallDir\.env"
    if (Test-Path $envFile) { return }

    @"
# Wyrdsekai configuration - auto-generated by install.ps1

# Inference - llama-server is the default backend
WYRDSEKAI_LLAMA_ENABLED=true
WYRDSEKAI_MODEL_PATH=$ModelPath
WYRDSEKAI_INFERENCE_TIMEOUT=300
WYRDSEKAI_INFERENCE_CONCURRENCY=1

# Search
WYRDSEKAI_SEARXNG_URL=http://localhost:8888

# Oracle
ORACLE_URL=http://localhost:7073

# SGLang (optional - for NVIDIA GPU, higher throughput)
# WYRDSEKAI_SGLANG_ENABLED=true
# WYRDSEKAI_SGLANG_URL=http://localhost:8000

# Ollama (optional fallback - llama-server is preferred)
# WYRDSEKAI_OLLAMA_ENABLED=true
# WYRDSEKAI_OLLAMA_URL=http://localhost:11434

# Optional search API keys (Searxng is preferred - these are fallbacks)
# BRAVE_SEARCH_API_KEY=
# TAVILY_API_KEY=
# SERPAPI_API_KEY=
"@ | Out-File -FilePath $envFile -Encoding utf8
}

# --- Hardware ----------------------------------------------------------------

function Detect-Hardware {
    Write-Info "Hardware:"

    # NVIDIA GPU
    try {
        $gpuInfo = & nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>$null | Select-Object -First 1
        if ($gpuInfo) { Write-Ok "  GPU: $gpuInfo (CUDA)" }
    } catch {}

    # AMD GPU (DirectML)
    if (-not $gpuInfo) {
        try {
            $amdGpu = Get-CimInstance Win32_VideoController | Where-Object { $_.Name -match "AMD|Radeon" } | Select-Object -First 1
            if ($amdGpu) { Write-Ok "  GPU: $($amdGpu.Name) (DirectML)" }
        } catch {}
    }

    if (-not $gpuInfo -and -not $amdGpu) {
        Write-Info "  GPU: None detected (CPU-only)"
    }

    try {
        $totalBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
        $ramGb = [math]::Round($totalBytes / 1GB)
        Write-Info "  RAM: ${ramGb}GB"
        Write-Info "  Model: Qwen3.5-4B Q4_K_M (recommended for all configurations)"
        if ($ramGb -ge 32) { Write-Info "  Note: 32GB+ RAM - can also run Qwen3.5-14B if desired" }
    } catch {}
}

# --- Searxng -----------------------------------------------------------------

function Start-Searxng {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return }
    try { $null = & docker info 2>&1 } catch { return }

    try {
        $running = & docker ps --format "{{.Names}}" 2>$null | Where-Object { $_ -match "searxng" }
        if ($running) {
            Write-Ok "Searxng already running"
            return
        }
    } catch {}

    Write-Info "Starting Searxng..."
    try {
        & docker compose up -d searxng 2>$null
        Write-Ok "Searxng started on port 8888"
    } catch {
        Write-Warn "Could not start Searxng - web search will use DuckDuckGo fallback"
    }
}

# --- Uninstall ---------------------------------------------------------------

function Do-Uninstall {
    Write-Info "Uninstalling Wyrdsekai from $InstallDir..."

    # Remove scheduled task
    $taskName = "Wyrdsekai"
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($task) {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Ok "Removed auto-start task"
    }

    $binDir = "$InstallDir\bin"
    $currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if ($currentPath -like "*$binDir*") {
        $newPath = ($currentPath -split ";" | Where-Object { $_ -ne $binDir }) -join ";"
        [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
        Write-Ok "Removed from PATH"
    }

    if (Test-Path "$InstallDir\data") {
        Write-Warn "Preserving world data at $InstallDir\data"
        Get-ChildItem $InstallDir -Exclude "data" | Remove-Item -Recurse -Force
    } else {
        Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Ok "Wyrdsekai uninstalled."
}

# --- Main --------------------------------------------------------------------

if ($Uninstall) {
    Do-Uninstall
    exit 0
}

Write-Host ""
Write-Host "  +==================================+" -ForegroundColor Cyan
Write-Host "  |       Wyrdsekai Installer         |" -ForegroundColor Cyan
Write-Host "  |   Distributed Text-Native World   |" -ForegroundColor Cyan
Write-Host "  +==================================+" -ForegroundColor Cyan
Write-Host ""

$arch = Detect-Platform
$modeInfo = Detect-Mode

if ($modeInfo.Mode -eq "source") {
    Write-Info "Mode: build from source ($($modeInfo.RepoDir))"
    if (-not (Check-Java)) { Install-Java }
    Build-FromSource -RepoDir $modeInfo.RepoDir -Dest $InstallDir
} else {
    Write-Info "Mode: release download"
    if (-not $Version) {
        Write-Info "Checking latest release..."
        try {
            $releaseInfo = Invoke-RestMethod "https://api.github.com/repos/$REPO/releases/latest" -UseBasicParsing
            $Version = $releaseInfo.tag_name -replace '^v'
        } catch { $Version = "0.1.0" }
    }
    Write-Info "Version: $Version"

    if (-not (Download-Release -Repo $REPO -Ver $Version -Name "wyrdsekai" -Dest $InstallDir -Arch $arch)) {
        Write-Warn "Release not found. Checking for local source..."
        $sourceDir = $null
        foreach ($d in @("$HOME\src\wyrdsekai", "$HOME\wyrdsekai", (Get-Location).Path)) {
            if ((Test-Path "$d\gradlew.bat") -and (Test-Path "$d\server\build.gradle.kts")) {
                $sourceDir = $d; break
            }
        }
        if ($sourceDir) {
            if (-not (Check-Java)) { Install-Java }
            Build-FromSource -RepoDir $sourceDir -Dest $InstallDir
        } else {
            Write-Err "No release or source found. See https://github.com/$REPO"
            exit 1
        }
    }
}

if (-not (Check-Java)) { Install-Java }

$modelPath = Download-Models
Write-Config -ModelPath $modelPath
New-Item -ItemType Directory -Force -Path "$InstallDir\data" | Out-Null
Setup-Path
Start-Searxng
Detect-Hardware

# Auto-start service (Windows Task Scheduler)
if (-not $NoService) {
    $taskName = "Wyrdsekai"
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Ok "Scheduled task '$taskName' already exists"
    } else {
        Write-Info "Setting up auto-start task..."
        $action = New-ScheduledTaskAction `
            -Execute "$InstallDir\bin\server.bat" `
            -WorkingDirectory $InstallDir
        $trigger = New-ScheduledTaskTrigger -AtLogOn
        $settings = New-ScheduledTaskSettingsSet `
            -AllowStartIfOnBatteries `
            -DontStopIfGoingOnBatteries `
            -StartWhenAvailable `
            -RestartCount 3 `
            -RestartInterval (New-TimeSpan -Seconds 30)
        Register-ScheduledTask `
            -TaskName $taskName `
            -Action $action `
            -Trigger $trigger `
            -Settings $settings `
            -Description "Wyrdsekai Server - starts on login" `
            -RunLevel Limited | Out-Null
        Write-Ok "Auto-start task created (runs at login)"
        Write-Info "  Start now:  Start-ScheduledTask -TaskName '$taskName'"
        Write-Info "  Disable:    Unregister-ScheduledTask -TaskName '$taskName'"
    }
}

Write-Host ""
Write-Ok "Wyrdsekai installed!"
Write-Host ""
Write-Host "  Start server:   wyrdsekai up   (or: .\wyrdsekai.ps1 up)"
Write-Host "  Connect:        ssh -p 7022 $env:USERNAME@localhost"
Write-Host "  Browser:        http://localhost:$EffectivePort"
Write-Host "  Config:         $InstallDir\.env"
Write-Host "  Uninstall:      .\install.ps1 -Uninstall"
Write-Host ""
