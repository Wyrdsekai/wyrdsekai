# ═══════════════════════════════════════════════════════════════════
# wyrd.ps1 — Wyrdsekai control CLI for Windows
#
# Usage:
#   .\wyrd.ps1 setup        First-time setup (deps, model, service, build)
#   .\wyrd.ps1 start        Start the server
#   .\wyrd.ps1 stop         Stop the server
#   .\wyrd.ps1 restart      Stop then start
#   .\wyrd.ps1 status       Show what's running
#   .\wyrd.ps1 log          Tail server log
#   .\wyrd.ps1 backup       Create a backup snapshot
#   .\wyrd.ps1 restore      Restore from a backup
#   .\wyrd.ps1 recover      Reset steward password with recovery key
#   .\wyrd.ps1 reset-zone   Factory reset with recovery key
#   .\wyrd.ps1 update       Pull latest, rebuild, restart
#   .\wyrd.ps1 doctor       Diagnose problems (disk, memory, GPU, ports)
#   .\wyrd.ps1 uninstall    Clean removal
# ═══════════════════════════════════════════════════════════════════

param(
    [Parameter(Position=0)]
    [string]$Command = "help",
    [Parameter(Position=1)]
    [string]$Option = "",
    [Parameter(Position=2)]
    [string]$Extra = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallDir = Join-Path $env:USERPROFILE ".wyrdsekai"
$EnvFile = Join-Path $InstallDir "env.ps1"

function Write-Log($msg) { Write-Host "[wyrdsekai] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[wyrdsekai] $msg" -ForegroundColor Yellow }
function Write-Err($msg) { Write-Host "[wyrdsekai] $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "[wyrdsekai] $msg" -ForegroundColor Cyan }

# ── Prerequisite checks ──

function Test-Java {
    try {
        $ver = & java -version 2>&1 | Select-Object -First 1
        if ($ver -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 25) {
                Write-Log "Java: $ver"
                return $true
            }
            Write-Warn "Java $major found, but 25+ required."
            return $false
        }
        Write-Log "Java: $ver"
        return $true
    } catch {
        Write-Warn "Java not found. Install: https://adoptium.net"
        return $false
    }
}

function Test-Docker {
    try {
        $null = & docker info 2>&1
        Write-Log "Docker: running"
        return $true
    } catch {
        Write-Warn "Docker not running. Install: https://docker.com/products/docker-desktop"
        return $false
    }
}

function Test-LlamaServer {
    # Check WYRDSEKAI_INFERENCE_URL first, then default ports
    $urls = @()
    if ($env:WYRDSEKAI_INFERENCE_URL) { $urls += $env:WYRDSEKAI_INFERENCE_URL }
    $urls += "http://localhost:11525"  # Docker llama-server
    $urls += "http://localhost:8200"   # Native llama-server

    foreach ($url in $urls) {
        try {
            $resp = Invoke-RestMethod -Uri "$url/health" -TimeoutSec 3 -ErrorAction SilentlyContinue
            Write-Log "llama-server: running at $url"
            return $true
        } catch {}
    }
    Write-Warn "llama-server: not running"
    return $false
}

function Test-Ollama {
    try {
        $resp = Invoke-RestMethod -Uri "http://localhost:11434/api/version" -TimeoutSec 3
        $models = (Invoke-RestMethod -Uri "http://localhost:11434/api/tags" -TimeoutSec 3).models.name -join ", "
        Write-Log "Ollama: v$($resp.version) ($models)"
        return $true
    } catch {
        return $false
    }
}

function Test-Searxng {
    $url = if ($env:WYRDSEKAI_SEARXNG_URL) { $env:WYRDSEKAI_SEARXNG_URL } else { "http://localhost:8888" }
    try {
        $null = Invoke-WebRequest -Uri "$url/healthz" -TimeoutSec 3 -UseBasicParsing
        Write-Log "Searxng: running at $url"
        return $true
    } catch {
        Write-Warn "Searxng: not running (web search will use DuckDuckGo fallback)"
        return $false
    }
}

function Test-Oracle {
    $url = if ($env:ORACLE_URL) { $env:ORACLE_URL } else { "http://localhost:7073" }
    try {
        $null = Invoke-RestMethod -Uri "$url/health" -TimeoutSec 3
        Write-Log "Oracle: running at $url"
        return $true
    } catch {
        Write-Info "Oracle: not running (predictions disabled)"
        return $false
    }
}

function Test-Server {
    $port = if ($env:WYRDSEKAI_PORT) { $env:WYRDSEKAI_PORT } else { "7070" }
    try {
        $resp = Invoke-RestMethod -Uri "http://localhost:${port}/health" -TimeoutSec 3
        Write-Log "Server: $($resp.status)"
        return $true
    } catch {
        return $false
    }
}

# ── Install ──

function Install-Wyrdsekai {
    Write-Log "Installing Wyrdsekai on Windows..."
    Write-Host ""

    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    New-Item -ItemType Directory -Path "$InstallDir\models" -Force | Out-Null

    Test-Java
    Test-Docker

    # Start Searxng
    if (Test-Docker) {
        try {
            $running = & docker ps --format "{{.Names}}" 2>$null | Where-Object { $_ -match "searxng" }
            if (-not $running) {
                Write-Log "Starting Searxng..."
                & docker compose up -d searxng 2>$null
            }
        } catch {}
    }

    # Download models
    $modelsDir = "$InstallDir\models"

    # Embedding model (MiniLM-L6-v2 int8, ~22MB)
    $embedModel = "$modelsDir\minilm-l6-v2-q8.onnx"
    if (-not (Test-Path $embedModel)) {
        Write-Log "Downloading embedding model (MiniLM-L6-v2, ~22MB)..."
        try {
            Invoke-WebRequest -Uri "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" `
                -OutFile $embedModel -UseBasicParsing
            Write-Log "Embedding model downloaded"
        } catch {
            Write-Warn "Embedding model download failed — semantic search will use BM25 fallback"
        }
    }

    # Companion model (Qwen3.5-4B SSD GGUF, ~2.6GB)
    $companionModel = "$modelsDir\wyrdsekai-3.5-4b-v10-q4km.gguf"
    if (-not (Test-Path $companionModel)) {
        Write-Log "Downloading companion model (Qwen3.5-4B SSD, ~2.6GB)..."
        try {
            Invoke-WebRequest -Uri "https://huggingface.co/wyrdsekai/companion-3.5-4b-gguf/resolve/main/wyrdsekai-3.5-4b-v10-q4km.gguf" `
                -OutFile $companionModel -UseBasicParsing
        } catch {
            Write-Log "Falling back to base model..."
            try {
                Invoke-WebRequest -Uri "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf" `
                    -OutFile $companionModel -UseBasicParsing
            } catch {
                Write-Warn "Model download failed — download manually to $companionModel"
            }
        }
    }

    # Generate env
    if (-not (Test-Path $EnvFile)) {
        Write-Log "Generating configuration..."
        @"
# Wyrdsekai configuration — auto-generated

# Inference — llama-server is the default backend
`$env:WYRDSEKAI_LLAMA_ENABLED = "true"
`$env:WYRDSEKAI_MODEL_PATH = "$companionModel"
`$env:WYRDSEKAI_INFERENCE_TIMEOUT = "300"
`$env:WYRDSEKAI_INFERENCE_CONCURRENCY = "1"

# Search
`$env:WYRDSEKAI_SEARXNG_URL = "http://localhost:8888"

# Oracle
`$env:ORACLE_URL = "http://localhost:7073"

# SGLang (optional — for NVIDIA GPU, higher throughput)
# `$env:WYRDSEKAI_SGLANG_ENABLED = "true"
# `$env:WYRDSEKAI_SGLANG_URL = "http://localhost:8000"

# Ollama (optional fallback — llama-server is preferred)
# `$env:WYRDSEKAI_OLLAMA_ENABLED = "true"
# `$env:WYRDSEKAI_OLLAMA_URL = "http://localhost:11434"
"@ | Out-File -Encoding UTF8 $EnvFile
    }

    # Build
    if (Test-Path "gradlew.bat") {
        Write-Log "Building server..."
        & .\gradlew.bat :server:installDist --quiet
    }

    # Install Windows service via NSSM
    $nssm = Get-Command nssm -ErrorAction SilentlyContinue
    if ($nssm) {
        Write-Log "Installing Windows service..."
        & nssm install Wyrdsekai java -jar "$InstallDir\lib\wyrdsekai-server.jar"
        & nssm set Wyrdsekai AppDirectory $InstallDir
        & nssm set Wyrdsekai Start SERVICE_AUTO_START
        Write-Log "Service installed."
    } else {
        Write-Warn "NSSM not found — install for auto-start: choco install nssm"
    }

    Write-Host ""
    Write-Log "Installation complete!"
    Write-Log "  Run: .\wyrdsekai.ps1 up"
    Write-Log "  Connect: ssh -p 7022 $env:USERNAME@localhost"
}

# ── Up ──

function Start-Wyrdsekai {
    if (Test-Path $EnvFile) { . $EnvFile }

    $profile = ""
    if ($Option -eq "--full") {
        $profile = "--profile full"
        Write-Log "Starting full stack..."
    } else {
        Write-Log "Starting Wyrdsekai..."
    }

    # Auto-rebuild if source changed since last build
    Maybe-Rebuild

    # Docker services
    if (Test-Docker) {
        if ($profile) {
            & docker compose $profile up -d 2>$null
        } else {
            & docker compose up -d searxng 2>$null
        }
        # Wait for Searxng
        for ($i = 0; $i -lt 15; $i++) {
            try {
                $null = Invoke-WebRequest -Uri "http://localhost:8888/healthz" -TimeoutSec 2 -UseBasicParsing
                break
            } catch { Start-Sleep -Seconds 1 }
        }
    }

    Test-Searxng | Out-Null
    Test-LlamaServer | Out-Null
    if (-not (Test-LlamaServer)) { Test-Ollama | Out-Null }
    Test-Oracle | Out-Null

    # Start server
    if (Test-Path "scripts\deploy.sh") {
        & bash scripts/deploy.sh full
    } elseif (Test-Path "scripts\run-node.ps1") {
        & .\scripts\run-node.ps1 solo
    } elseif (Test-Path "server\build\install\server\bin\server.bat") {
        Write-Log "Starting server..."
        Start-Process -FilePath "server\build\install\server\bin\server.bat" `
            -WorkingDirectory (Get-Location) -NoNewWindow
    }
}

function Maybe-Rebuild {
    $buildDir = "server\build\install\server\lib"
    $needsBuild = $false
    if (-not (Test-Path $buildDir)) {
        $needsBuild = $true
    } else {
        $buildTime = (Get-Item $buildDir).LastWriteTime
        $newerSource = Get-ChildItem -Path "core\src","server\src","common\src","scripting\src" -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -gt $buildTime } | Select-Object -First 1
        if ($newerSource) { $needsBuild = $true }
    }
    if ($needsBuild) {
        Write-Log "Source changed — rebuilding..."
        if (Test-Path "gradlew.bat") {
            & .\gradlew.bat :server:installDist --quiet
        }
    }
}

# ── Down ──

function Stop-Wyrdsekai {
    Write-Log "Stopping Wyrdsekai..."
    & docker compose --profile full down 2>$null
    & docker compose down 2>$null
    Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object {
        try { $_.CommandLine -match "wyrdsekai" } catch { $false }
    } | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Log "Stopped."
}

# ── Status ──

function Get-WyrdStatus {
    if (Test-Path $EnvFile) { . $EnvFile }
    Write-Host ""
    Test-Server | Out-Null
    if (-not (Test-Server)) { Write-Warn "Server: not running" }
    Test-LlamaServer | Out-Null
    Test-Ollama | Out-Null
    Test-Searxng | Out-Null
    Test-Oracle | Out-Null
    Write-Host ""
    Write-Info "Docker services:"
    & docker compose ps 2>$null
    Write-Host ""
}

# ── Update ──

function Update-Wyrdsekai {
    Write-Log "Updating Wyrdsekai..."
    if (Test-Path ".git") {
        & git pull --ff-only
    }
    Maybe-Rebuild
    Stop-Wyrdsekai 2>$null
    Start-Wyrdsekai
    Write-Log "Updated."
}

# ── Doctor ──

function Test-WyrdHealth {
    Write-Log "Running diagnostics..."
    Write-Host ""

    # Disk
    try {
        $disk = Get-PSDrive C | Select-Object Free
        Write-Log "Disk free: $([math]::Round($disk.Free/1GB, 1)) GB"
    } catch {}

    # RAM
    try {
        $ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB, 0)
        Write-Log "RAM: $ramGb GB"
    } catch {}

    # GPU (NVIDIA)
    try {
        $gpu = & nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>$null
        if ($gpu) { Write-Log "GPU: $gpu (CUDA)" }
    } catch {}

    # GPU (AMD)
    if (-not $gpu) {
        try {
            $amdGpu = Get-CimInstance Win32_VideoController | Where-Object { $_.Name -match "AMD|Radeon" } | Select-Object -First 1
            if ($amdGpu) { Write-Log "GPU: $($amdGpu.Name) (DirectML)" }
        } catch {}
    }

    if (-not $gpu -and -not $amdGpu) {
        Write-Warn "GPU: none detected (CPU-only)"
    }

    Write-Host ""
    Test-Java | Out-Null
    Test-Docker | Out-Null
    Test-LlamaServer | Out-Null
    Test-Ollama | Out-Null
    Test-Searxng | Out-Null
    Test-Oracle | Out-Null
    Test-Server | Out-Null

    # Port conflicts
    Write-Host ""
    Write-Info "Port scan:"
    foreach ($port in @(7070, 7071, 7022, 8888, 11434, 11525, 8200, 7073)) {
        try {
            $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($conn) {
                $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
                $name = if ($proc) { $proc.ProcessName } else { "?" }
                Write-Log "  Port ${port}: in use by $name (PID $($conn.OwningProcess))"
            }
        } catch {}
    }
}

# ── Logs ──

function Get-WyrdLogs {
    $logFile = "$env:TEMP\wyrdsekai-server.log"
    if (Test-Path $logFile) {
        Get-Content -Path $logFile -Wait -Tail 50
    } else {
        Write-Err "No server log found at $logFile"
    }
}

# ── Uninstall ──

function Uninstall-Wyrdsekai {
    Stop-Wyrdsekai
    $remove = Read-Host "Remove data too? (y/N)"
    if ($remove -match "^[Yy]") {
        Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
        Write-Log "Data removed."
    }

    # Remove NSSM service
    $nssm = Get-Command nssm -ErrorAction SilentlyContinue
    if ($nssm) {
        & nssm stop Wyrdsekai 2>$null
        & nssm remove Wyrdsekai confirm 2>$null
    }

    # Remove scheduled task
    $task = Get-ScheduledTask -TaskName "Wyrdsekai" -ErrorAction SilentlyContinue
    if ($task) {
        Stop-ScheduledTask -TaskName "Wyrdsekai" -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName "Wyrdsekai" -Confirm:$false
    }

    Write-Log "Uninstalled."
}

# ── Main ──
switch ($Command) {
    "setup"     { Install-Wyrdsekai }
    "install"   { Install-Wyrdsekai }    # backward compat
    "start"     { Start-Wyrdsekai }
    "up"        { Start-Wyrdsekai }      # backward compat
    "stop"      { Stop-Wyrdsekai }
    "down"      { Stop-Wyrdsekai }       # backward compat
    "restart"   { Stop-Wyrdsekai; Start-Sleep -Seconds 1; Start-Wyrdsekai }
    "status"    { Get-WyrdStatus }
    "log"       { Get-WyrdLogs }
    "logs"      { Get-WyrdLogs }         # backward compat
    "update"    { Update-Wyrdsekai }
    "doctor"    { Test-WyrdHealth }
    "uninstall" { Uninstall-Wyrdsekai }
    "backup" {
        $BackupDir = Join-Path $InstallDir "backups"
        New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
        $ts = Get-Date -Format "yyyyMMdd-HHmmss"
        $BackupPath = Join-Path $BackupDir "wyrdsekai-$ts"
        New-Item -ItemType Directory -Force -Path $BackupPath | Out-Null
        Get-ChildItem "$InstallDir\*.db" | ForEach-Object {
            Copy-Item $_.FullName $BackupPath; Write-Log "  $($_.Name)"
        }
        if (Test-Path "$InstallDir\jetstream") {
            Copy-Item -Recurse "$InstallDir\jetstream" "$BackupPath\jetstream"
            Write-Log "  jetstream/"
        }
        if (Test-Path "$InstallDir\node-identity.json") {
            Copy-Item "$InstallDir\node-identity.json" $BackupPath
        }
        $size = "{0:N2} MB" -f ((Get-ChildItem $BackupPath -Recurse | Measure-Object Length -Sum).Sum / 1MB)
        Write-Log "Backup complete: $BackupPath ($size)"
    }
    "restore" {
        $BackupDir = Join-Path $InstallDir "backups"
        if (-not $Option) {
            Write-Host "Available backups:"
            Get-ChildItem $BackupDir -Directory | ForEach-Object { Write-Host "  $($_.Name)" }
            Write-Host "`nUsage: .\wyrd.ps1 restore <backup-name>"
        } else {
            $src = if (Test-Path $Option) { $Option } else { Join-Path $BackupDir $Option }
            if (-not (Test-Path $src)) { Write-Err "Backup not found: $Option"; return }
            Write-Warn "Restoring from $src — this overwrites current data."
            $confirm = Read-Host "Continue? (y/N)"
            if ($confirm -ne "y") { Write-Host "Cancelled."; return }
            Get-ChildItem "$src\*.db" | ForEach-Object { Copy-Item $_.FullName $InstallDir; Write-Log "  $($_.Name)" }
            if (Test-Path "$src\jetstream") {
                Remove-Item -Recurse -Force "$InstallDir\jetstream" -ErrorAction SilentlyContinue
                Copy-Item -Recurse "$src\jetstream" "$InstallDir\jetstream"
            }
            Write-Log "Restore complete. Start the server: .\wyrd.ps1 start"
        }
    }
    "recover" {
        if (-not $Option) { Write-Host "Usage: .\wyrd.ps1 recover <recovery-key>`n  Then enter new password when prompted."; return }
        $newPass = Read-Host "New password"
        $body = @{recoveryKey=$Option; newPassword=$newPass} | ConvertTo-Json
        $resp = Invoke-RestMethod -Uri "http://localhost:7070/api/auth/recover" -Method Post -Body $body -ContentType "application/json" -ErrorAction SilentlyContinue
        if ($resp.status -eq "recovered") { Write-Log $resp.message } else { Write-Err ($resp.error ?? "Recovery failed") }
    }
    "reset-zone" {
        if (-not $Option) { Write-Host "Usage: .\wyrd.ps1 reset-zone <recovery-key>"; return }
        Write-Warn "This will wipe ALL accounts, sessions, invites, and config."
        $confirm = Read-Host "Continue? (y/N)"
        if ($confirm -ne "y") { Write-Host "Cancelled."; return }
        $body = @{recoveryKey=$Option} | ConvertTo-Json
        $resp = Invoke-RestMethod -Uri "http://localhost:7070/api/auth/reset-zone" -Method Post -Body $body -ContentType "application/json" -ErrorAction SilentlyContinue
        if ($resp.status -eq "reset") { Write-Log $resp.message } else { Write-Err ($resp.error ?? "Reset failed") }
    }
    "inference" {
        # Mirrors the bash `wyrd inference ...` subcommand. Writes WYRDSEKAI_INFERENCE_URL
        # into the user env so subsequent `wyrd start` picks it up.
        $sub = if ($Option) { $Option } else { "status" }
        $envPath = Join-Path $InstallDir "env.ps1"
        switch ($sub) {
            "local" {
                $llama = Get-Command llama-server -ErrorAction SilentlyContinue
                if (-not $llama) {
                    Write-Err "llama-server not on PATH. Install llama.cpp (https://github.com/ggml-org/llama.cpp/releases) and put llama-server.exe on PATH."
                    break
                }
                $modelDir = Join-Path $InstallDir "models"
                $model = if ($Extra) { $Extra } else { Join-Path $modelDir "wyrdsekai-3.5-4b-v10-q4km.gguf" }
                if (-not (Test-Path $model)) {
                    Write-Err "Model not found: $model  (run: .\wyrd.ps1 setup)"
                    break
                }
                # Kill any prior llama-server we spawned on 11525
                Get-Process -Name llama-server -ErrorAction SilentlyContinue | Where-Object {
                    $_.CommandLine -match '11525'
                } | Stop-Process -Force -ErrorAction SilentlyContinue
                $args = @('--host','127.0.0.1','--port','11525','--model',$model,'--ctx-size','8192','--jinja')
                Start-Process -FilePath $llama.Source -ArgumentList $args -WindowStyle Hidden
                'WYRDSEKAI_INFERENCE_URL=http://127.0.0.1:11525' | Out-File -Encoding ascii $envPath
                Write-Log "Started llama-server on 127.0.0.1:11525 (model: $(Split-Path $model -Leaf))"
            }
            "remote" {
                if (-not $Extra) { Write-Err "Usage: .\wyrd.ps1 inference remote <url>"; break }
                "WYRDSEKAI_INFERENCE_URL=$Extra" | Out-File -Encoding ascii $envPath
                Write-Log "Wrote inference override: $Extra"
            }
            "zone" {
                if (-not $Extra) { Write-Err "Usage: .\wyrd.ps1 inference zone <zoneId>"; break }
                "WYRDSEKAI_INFERENCE_URL=nats://$Extra" | Out-File -Encoding ascii $envPath
                Write-Log "Wrote inference override: nats://$Extra (needs federation)"
            }
            "status" {
                $current = $env:WYRDSEKAI_INFERENCE_URL
                if (-not $current -and (Test-Path $envPath)) {
                    $line = Get-Content $envPath | Where-Object { $_ -match '^WYRDSEKAI_INFERENCE_URL=' }
                    if ($line) { $current = $line -replace '^WYRDSEKAI_INFERENCE_URL=','' }
                }
                Write-Log ("Inference backend: {0}" -f ($current ?? "<unset>"))
                $cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue
                $ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 0)
                Write-Host ("  CPU: {0} cores, {1}" -f $cpu.NumberOfCores, $cpu.Name)
                Write-Host ("  RAM: {0} GB" -f $ramGb)
            }
            "disable" {
                Get-Process -Name llama-server -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
                if (Test-Path $envPath) {
                    (Get-Content $envPath) | Where-Object { $_ -notmatch '^WYRDSEKAI_INFERENCE_URL=' } | Set-Content $envPath
                }
                Write-Log "Stopped local llama-server and cleared inference override."
            }
            default {
                Write-Host "Usage: .\wyrd.ps1 inference <local|remote <url>|zone <id>|status|disable>"
            }
        }
    }
    default {
        Write-Host "wyrd — Wyrdsekai control CLI"
        Write-Host ""
        Write-Host "Usage: .\wyrd.ps1 <command>"
        Write-Host ""
        Write-Host "Commands:"
        Write-Host "  setup        First-time setup (models, inference, docker services)"
        Write-Host "  start        Start the server"
        Write-Host "  stop         Stop the server"
        Write-Host "  restart      Stop then start"
        Write-Host "  status       Show what's running"
        Write-Host "  log          Tail server log"
        Write-Host "  backup       Create a backup snapshot"
        Write-Host "  restore      Restore from a backup (lists available if no arg)"
        Write-Host "  update       Pull latest code and rebuild (source mode)"
        Write-Host "  doctor       Diagnose problems (disk, memory, GPU, ports)"
        Write-Host "  recover      Reset steward password: .\wyrd.ps1 recover <key>"
        Write-Host "  reset-zone   Factory reset: .\wyrd.ps1 reset-zone <recovery-key>"
        Write-Host "  inference    Configure inference: local / remote <url> / zone <id> / status"
        Write-Host "  uninstall    Stop services, optionally remove data"
        Write-Host ""
        Write-Host "First time? Run: .\wyrd.ps1 setup"
    }
}
