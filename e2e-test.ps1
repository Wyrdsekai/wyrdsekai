# ═══════════════════════════════════════════════════════════════════════════════
# Wyrdsekai E2E Test Orchestrator (Windows)
# ═══════════════════════════════════════════════════════════════════════════════
#
# Usage:
#   .\e2e-test.ps1                              # Run all tiers, auto-detect GPU
#   .\e2e-test.ps1 -Tier 0                      # Tier 0 only (WireMock, no deps)
#   .\e2e-test.ps1 -Tier 1                      # Tier 1 (smoke, 0.6B)
#   .\e2e-test.ps1 -Tier 2                      # Tier 2 (e2e, 4B)
#   .\e2e-test.ps1 -Tier 3                      # Tier 3 (between, NATS)
#   .\e2e-test.ps1 -Tier 4                      # Tier 4 (relay, 2x backends)
#   .\e2e-test.ps1 -Tier 5                      # Tier 5 (household, 3x backends)
#   .\e2e-test.ps1 -Engine sglang               # Use SGLang (default, 16GB GPU)
#   .\e2e-test.ps1 -Engine vllm                 # Use vLLM (24GB+ GPU)
#   .\e2e-test.ps1 -Engine llama                # Use llama-server (lightweight)
#   .\e2e-test.ps1 -Device gpu                  # Force GPU mode
#   .\e2e-test.ps1 -Device cpu                  # Force CPU-only mode
#   .\e2e-test.ps1 -Keep                        # Keep Docker containers after
#   .\e2e-test.ps1 -Model "Qwen/Qwen3-8B"      # Override model
#
# Mirrors e2e-test.sh for Windows / Docker Desktop.
# ═══════════════════════════════════════════════════════════════════════════════

param(
    [ValidateSet("all","0","1","2","3","4","5")]
    [string]$Tier = "all",

    [ValidateSet("auto","gpu","cpu")]
    [string]$Device = "auto",

    [ValidateSet("sglang","vllm","llama","ollama","claude")]
    [string]$Engine = "sglang",

    [string]$Model = "",
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ComposeFile = Join-Path $ScriptDir "docker\docker-compose.e2e.yml"
$ProjectName = "wyrdsekai-e2e"

# Default models per engine
$DefaultModels = @{
    sglang = "Qwen/Qwen3-8B"
    vllm   = "cpatonn/Qwen3-Coder-30B-A3B-Instruct-AWQ-4bit"
    llama  = "qwen3-4b-q4_k_m.gguf"
}

if (-not $Model -and $DefaultModels.ContainsKey($Engine)) {
    $Model = $DefaultModels[$Engine]
}

# --- Helpers ---
function Write-E2E       { param($msg) Write-Host "[E2E] " -ForegroundColor Cyan -NoNewline; Write-Host $msg }
function Write-E2EOk     { param($msg) Write-Host "[E2E] " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-E2EWarn   { param($msg) Write-Host "[E2E] " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Write-E2EFail   { param($msg) Write-Host "[E2E] " -ForegroundColor Red -NoNewline; Write-Host $msg }

function Invoke-Compose {
    & docker compose -f $ComposeFile -p $ProjectName @args
}

# --- Prerequisites ---
function Test-Prerequisites {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-E2EFail "Docker not found"
        exit 1
    }
    try {
        & docker info 2>&1 | Out-Null
    } catch {
        Write-E2EFail "Docker daemon not running"
        exit 1
    }
}

# --- GPU Detection ---
# AMD ROCm is Linux-only. AMD GPU on Windows is not supported for Docker inference.
# Apple Silicon uses vllm-mlx natively on macOS (not via this PowerShell script).
# Users with AMD GPUs on Windows get CPU-only mode.
function Get-GpuDevice {
    try {
        $null = & nvidia-smi 2>&1
        if ($LASTEXITCODE -eq 0) { return "gpu" }
    } catch {}
    return "cpu"
}

function Get-GpuVramGb {
    try {
        $output = & nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>$null |
            Select-Object -First 1
        if ($output) { return [math]::Floor([int]$output / 1024) }
    } catch {}
    return 0
}

function Test-NeedsInference {
    return ($Tier -ne "0" -and $Tier -ne "3")
}

# --- Docker Management ---
function Remove-ConflictingContainers {
    $names = @("wyrdsekai-e2e-nats","wyrdsekai-e2e-ollama","wyrdsekai-e2e-sglang","wyrdsekai-e2e-vllm","wyrdsekai-e2e-llama")
    $existing = & docker ps -a --format '{{.Names}}' 2>$null
    $conflicts = @()
    foreach ($name in $names) {
        if ($existing -contains $name) { $conflicts += $name }
    }
    if ($conflicts.Count -gt 0) {
        Write-E2EWarn "Conflicting containers: $($conflicts -join ', ')"
        foreach ($name in $conflicts) {
            & docker rm -f $name 2>$null | Out-Null
        }
        Write-E2EOk "Conflicting containers removed"
    }
}

function Start-DockerServices {
    param([string]$DeviceMode)

    Remove-ConflictingContainers

    Write-E2E "Starting Docker services (device=$DeviceMode, engine=$Engine)..."

    if ($Tier -eq "0") {
        Write-E2E "Tier 0 needs no Docker services"
        return
    }

    # Start NATS for tiers 3, 4, 5, all
    if ($Tier -match '^(3|4|5|all)$') {
        Invoke-Compose up -d nats 2>&1 | ForEach-Object { "  $_" }
    }

    # Start inference engine
    if (Test-NeedsInference) {
        $toolParser = if ($Model -like "*Coder*") { "qwen3_coder" } else { "qwen" }

        switch ($Engine) {
            "sglang" {
                $extraArgs = if ($env:SGLANG_EXTRA_ARGS) { $env:SGLANG_EXTRA_ARGS } else { "--quantization fp8" }
                Write-E2E "Starting SGLang with model $Model (parser=$toolParser)..."
                $env:SGLANG_MODEL = $Model
                $env:SGLANG_MAX_MODEL_LEN = "16384"
                $env:SGLANG_TOOL_PARSER = $toolParser
                $env:SGLANG_EXTRA_ARGS = $extraArgs
                $env:COMPOSE_PROFILES = "sglang"
                Invoke-Compose up -d sglang 2>&1 | ForEach-Object { "  $_" }
            }
            "vllm" {
                $extraArgs = if ($env:VLLM_EXTRA_ARGS) { $env:VLLM_EXTRA_ARGS }
                             elseif ($Model -notmatch 'AWQ|GPTQ') { "--quantization fp8" }
                             else { "" }
                Write-E2E "Starting vLLM with model $Model..."
                $env:VLLM_MODEL = $Model
                $env:VLLM_MAX_MODEL_LEN = "16384"
                $env:VLLM_TOOL_PARSER = "qwen3_coder"
                $env:VLLM_EXTRA_ARGS = $extraArgs
                $env:COMPOSE_PROFILES = "vllm"
                Invoke-Compose up -d vllm 2>&1 | ForEach-Object { "  $_" }
            }
            "llama" {
                Write-E2E "Starting llama-server with model $Model..."
                $env:LLAMA_MODEL = $Model
                $env:COMPOSE_PROFILES = "llama"
                Invoke-Compose up -d llama-server 2>&1 | ForEach-Object { "  $_" }
            }
            "ollama" {
                Invoke-Compose up -d ollama 2>&1 | ForEach-Object { "  $_" }
            }
            "claude" {
                Write-E2E "Claude CLI engine - no Docker container needed"
            }
        }
    }
}

function Stop-DockerServices {
    if ($Keep) {
        Write-E2E "Keeping Docker services running (-Keep)"
        return
    }
    if ($Tier -eq "0") { return }
    Write-E2E "Stopping Docker services..."
    $env:COMPOSE_PROFILES = "sglang,vllm,llama"
    Invoke-Compose down 2>&1 | ForEach-Object { "  $_" }
}

function Wait-ForHealth {
    param([string]$Service, [string]$Url, [int]$MaxWait = 120)
    $elapsed = 0
    while ($elapsed -lt $MaxWait) {
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 3 -ErrorAction SilentlyContinue
            Write-E2EOk "$Service healthy (${elapsed}s)"
            return $true
        } catch {}
        Start-Sleep -Seconds 2
        $elapsed += 2
    }
    Write-E2EFail "$Service not healthy after ${MaxWait}s"
    return $false
}

function Wait-ForServices {
    # NATS
    if ($Tier -match '^(3|4|5|all)$') {
        if (-not (Wait-ForHealth "NATS" "http://localhost:8222/healthz" 30)) { exit 1 }
    }

    # Inference engine
    if (Test-NeedsInference) {
        switch ($Engine) {
            "sglang" {
                Write-E2E "Waiting for SGLang (model download may take minutes on first run)..."
                if (-not (Wait-ForHealth "SGLang" "http://localhost:8000/health" 600)) { exit 1 }
            }
            "vllm" {
                Write-E2E "Waiting for vLLM (model download may take minutes on first run)..."
                if (-not (Wait-ForHealth "vLLM" "http://localhost:8100/health" 600)) { exit 1 }
            }
            "llama" {
                if (-not (Wait-ForHealth "llama-server" "http://localhost:8080/health" 120)) { exit 1 }
            }
            "ollama" {
                if (-not (Wait-ForHealth "Ollama" "http://localhost:11435/api/tags" 60)) { exit 1 }
            }
            "claude" {
                Write-E2E "Claude CLI engine - checking CLI auth..."
                try {
                    & claude auth status 2>&1 | Out-Null
                    Write-E2EOk "Claude CLI authenticated"
                } catch {
                    Write-E2EFail "Claude CLI not authenticated (run: claude auth login)"
                    exit 1
                }
            }
        }
    }
}

# --- Build ---
function Build-Java {
    Write-E2E "Building Java modules..."
    Push-Location $ScriptDir
    & .\gradlew.bat ":e2e-test:compileTestJava" 2>&1 | Select-Object -Last 5 | ForEach-Object { "  $_" }
    Pop-Location
    Write-E2EOk "Java built"
}

# --- Run Tests ---
function Get-TierTag {
    param([string]$T)
    switch ($T) {
        "0" { "integration" }
        "1" { "smoke" }
        "2" { "e2e" }
        "3" { "between" }
        "4" { "relay" }
        "5" { "household" }
    }
}

function Invoke-Tier {
    param([string]$TierNum, [string]$DeviceMode)

    $tag = Get-TierTag $TierNum
    Write-E2E "Running Tier $TierNum ($tag) tests (device=$DeviceMode, engine=$Engine)..."

    $modelProp = if ($Model) { "-Dwyrdsekai.e2e.engine.model=$Model" } else { "" }

    Push-Location $ScriptDir
    $exitCode = 0
    try {
        $args = @(
            ":e2e-test:test",
            "--no-configuration-cache",
            "-PincludeTags=`"$tag`"",
            "-Dwyrdsekai.e2e.device=$DeviceMode",
            "-Dwyrdsekai.e2e.engine=$Engine"
        )
        if ($modelProp) { $args += $modelProp }

        & .\gradlew.bat @args 2>&1 | ForEach-Object { "  $_" }
        if ($LASTEXITCODE -ne 0) { $exitCode = $LASTEXITCODE }
    } catch {
        $exitCode = 1
    }
    Pop-Location

    if ($exitCode -eq 0) {
        Write-E2EOk "Tier $TierNum ($tag, $DeviceMode): PASSED"
    } else {
        Write-E2EFail "Tier $TierNum ($tag, $DeviceMode): FAILED (exit code $exitCode)"
    }
    return $exitCode
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

Write-Host ""
Write-Host "===============================================" -ForegroundColor White
Write-Host " Wyrdsekai E2E Test Suite (Windows)" -ForegroundColor White
Write-Host "===============================================" -ForegroundColor White
Write-Host ""

Test-Prerequisites

# Resolve device
if ($Device -eq "auto") {
    $Device = Get-GpuDevice
    Write-E2E "Auto-detected device: $Device"
}

$vram = Get-GpuVramGb
if ($vram -gt 0) {
    Write-E2E "GPU: ${vram}GB VRAM detected"
}
Write-E2E "Tier: $Tier, Device: $Device, Engine: $Engine"
if ($Model) { Write-E2E "Model: $Model" }
Write-Host ""

# Cleanup on exit
$cleanup = {
    Stop-DockerServices
}

try {
    # 1. Start Docker
    if ($Tier -ne "0") {
        Start-DockerServices $Device
    }

    # 2. Wait for services
    if ($Tier -ne "0") {
        Write-E2E "Waiting for services..."
        Wait-ForServices
    }

    # 3. Build
    Build-Java

    # 4. Run tests
    Write-Host ""
    Write-Host "-----------------------------------------------" -ForegroundColor White
    Write-Host " Running Tests" -ForegroundColor White
    Write-Host "-----------------------------------------------" -ForegroundColor White
    Write-Host ""

    $overallExit = 0
    $tiers = if ($Tier -eq "all") { @("0","1","2","3","4","5") } else { @($Tier) }

    foreach ($t in $tiers) {
        $result = Invoke-Tier $t $Device
        if ($result -ne 0) { $overallExit = $result }
    }

    # 5. Summary
    Write-Host ""
    Write-Host "-----------------------------------------------" -ForegroundColor White
    if ($overallExit -eq 0) {
        Write-Host " All tests PASSED" -ForegroundColor Green
    } else {
        Write-Host " Some tests FAILED (exit code $overallExit)" -ForegroundColor Red
    }
    Write-Host "-----------------------------------------------" -ForegroundColor White

    exit $overallExit
} finally {
    & $cleanup
}
