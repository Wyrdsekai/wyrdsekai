# run-node.ps1 — Start a Wyrdsekai node with role-appropriate configuration.
#
# Usage:
#   .\scripts\run-node.ps1 <role> [options]
#
# Roles:
#   phone    — Tiny model (0.6B), 2K context, relay to household hub
#   laptop   — Mid model (4B CPU), 4K context, self-sufficient
#   desktop  — Large model (8B GPU), 16K context, household relay target
#   server   — Largest model (30B+ GPU), 32K context, inference hub
#
# Options:
#   -NatsUrl <url>        NATS URL (default: nats://127.0.0.1:4222)
#   -InferenceUrl <url>   Local inference backend URL
#   -RelayUrl <url>       Remote inference relay URL (phone/laptop only)
#   -ZoneId <id>          Zone ID (default: home)
#   -Port <port>          HTTP/WS port (default: 7070)
#   -ArteryPort <port>    Pekko artery port (default: 25520)
#   -NatsAutoStart        Auto-start local NATS server (server role only)

param(
    [Parameter(Mandatory=$true, Position=0)]
    [ValidateSet("phone", "laptop", "desktop", "server")]
    [string]$Role,

    [string]$NatsUrl = "",
    [string]$InferenceUrl = "",
    [string]$RelayUrl = "",
    [string]$ZoneId = "",
    [string]$Port = "",
    [string]$ArteryPort = "",
    [switch]$NatsAutoStart
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir

# Defaults from env vars or hardcoded
if (-not $NatsUrl) { $NatsUrl = if ($env:WYRDSEKAI_NATS_URL) { $env:WYRDSEKAI_NATS_URL } else { "nats://127.0.0.1:4222" } }
if (-not $ZoneId) { $ZoneId = if ($env:WYRDSEKAI_ZONE_ID) { $env:WYRDSEKAI_ZONE_ID } else { "home" } }
if (-not $Port) { $Port = if ($env:WYRDSEKAI_PORT) { $env:WYRDSEKAI_PORT } else { "7070" } }
if (-not $ArteryPort) { $ArteryPort = if ($env:WYRDSEKAI_ARTERY_PORT) { $env:WYRDSEKAI_ARTERY_PORT } else { "25520" } }
$NatsAutoStartVal = if ($NatsAutoStart) { "true" } else { "false" }

# Detect local IP (prefer non-loopback, non-virtual)
$LocalIP = "127.0.0.1"
try {
    $addr = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.InterfaceAlias -notmatch 'vEthernet|Loopback' } |
        Select-Object -First 1
    if ($addr) { $LocalIP = $addr.IPAddress }
} catch {}

# Role-specific defaults
$LlamaPriority = ""
$RelayPriority = ""
switch ($Role) {
    "phone" {
        if (-not $env:WYRDSEKAI_MODEL) { $env:WYRDSEKAI_MODEL = "Qwen3-0.6B" }
        $LlamaPriority = "100"
        $RelayPriority = "10"
    }
    "laptop" {
        if (-not $env:WYRDSEKAI_MODEL) { $env:WYRDSEKAI_MODEL = "Qwen3-4B" }
        $LlamaPriority = "20"
        $RelayPriority = "10"
    }
    "desktop" {
        if (-not $env:WYRDSEKAI_MODEL) { $env:WYRDSEKAI_MODEL = "Qwen3-8B" }
        $LlamaPriority = "10"
    }
    "server" {
        if (-not $env:WYRDSEKAI_MODEL) { $env:WYRDSEKAI_MODEL = "Qwen3-30B-A3B" }
        $LlamaPriority = "10"
        if (-not $NatsAutoStart) { $NatsAutoStartVal = "true" }
    }
}

# Export common env vars
$env:WYRDSEKAI_PORT = $Port
$env:WYRDSEKAI_ARTERY_HOST = if ($env:WYRDSEKAI_ARTERY_HOST) { $env:WYRDSEKAI_ARTERY_HOST } else { $LocalIP }
$env:WYRDSEKAI_ARTERY_PORT = $ArteryPort
$env:WYRDSEKAI_BETWEEN_ENABLED = "true"
$env:WYRDSEKAI_ZONE_ID = $ZoneId
$env:WYRDSEKAI_ZONE_NAME = if ($env:WYRDSEKAI_ZONE_NAME) { $env:WYRDSEKAI_ZONE_NAME } else { $ZoneId }
$env:WYRDSEKAI_NATS_URL = $NatsUrl
$env:WYRDSEKAI_NATS_AUTO_START = $NatsAutoStartVal
$env:WYRDSEKAI_HOSTNAME = if ($env:WYRDSEKAI_HOSTNAME) { $env:WYRDSEKAI_HOSTNAME } else { $LocalIP }

# Configure local inference backend
if ($InferenceUrl) {
    $env:WYRDSEKAI_LLAMA_ENABLED = "true"
    $env:WYRDSEKAI_LLAMA_URL = $InferenceUrl
    $env:WYRDSEKAI_LLAMA_PRIORITY = $LlamaPriority
}

# Configure relay backend (phone/laptop -> server/desktop)
if ($RelayUrl -and $RelayPriority) {
    $env:WYRDSEKAI_SGLANG_ENABLED = "true"
    $env:WYRDSEKAI_SGLANG_URL = $RelayUrl
}

# JVM heap sizing per role
$Heap = switch ($Role) {
    "phone"   { "-Xmx512m" }
    "laptop"  { "-Xmx1g" }
    "desktop" { "-Xmx2g" }
    "server"  { "-Xmx4g" }
}
$env:JAVA_OPTS = "$($env:JAVA_OPTS) -Xms256m $Heap"

Write-Host "=== Wyrdsekai Node ===" -ForegroundColor Cyan
Write-Host "  Role:     $Role"
Write-Host "  Zone:     $ZoneId"
Write-Host "  Host:     $($env:WYRDSEKAI_ARTERY_HOST)"
Write-Host "  Port:     $Port (artery: $ArteryPort)"
Write-Host "  NATS:     $NatsUrl (auto-start: $NatsAutoStartVal)"
Write-Host "  Model:    $($env:WYRDSEKAI_MODEL)"
if ($InferenceUrl) {
    Write-Host "  Inference: $InferenceUrl (priority: $LlamaPriority)"
}
if ($RelayUrl) {
    Write-Host "  Relay:    $RelayUrl (priority: $RelayPriority)"
}
Write-Host "=====================" -ForegroundColor Cyan

Set-Location $ProjectDir

& .\gradlew.bat ":server:run" --args="--cluster" --console=plain -q
