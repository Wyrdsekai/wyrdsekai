# Wyrdsekai management CLI — Windows (PowerShell)
#
# Windows counterpart to bin/wyrd (the bash management CLI shipped in the
# .deb/.pkg). bin/wyrd is ~9800 lines of bash full of Linux-isms (systemd,
# /etc paths, lsof, stat -c) that don't translate 1:1; this port covers the
# full-peer surface (W9): lifecycle, config, inference + model index, backup/
# restore, naming admin (whoami/contacts/zones/blocks/safety), federation,
# recipes/journal/library, login/logout, uninstall/purge — see `wyrd help`.
# i18n: strings for the ported commands go through _T (scripts\i18n\
# wyrd_<locale>.json, en fallback per key).
#
# NOT yet ported to Windows (honest stubs — use a Linux/macOS node, or the
# REST API on :7070):
#   daemon / relay-server / rendezvous / web / reseed / embed-migrate /
#   embedding-model / verify-release / residency / connect / phone / bond /
#   issue / voice / reset / nuke
#
# Conf + data dir mirror the bash CLI's model:
#   DATA_DIR  = $env:WYRDSEKAI_DATA_DIR, else %USERPROFILE%\.wyrdsekai
#   CONF      = $DATA_DIR\wyrdsekai.conf  (KEY=VALUE, one per line)
# The installed Wyrdsekai.exe (jpackage launcher) already carries the JVM
# --add-opens / --enable-native-access options baked at build time, and
# NatsServerManager auto-starts the bundled app\nats-server.exe, so a Windows
# "start" is just: load conf into the environment and run the launcher.

[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Command = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ── Paths ───────────────────────────────────────────────────────────────────
# jpackage layout: staged files (jars, nats-server.exe, FIRST_ENCOUNTER.md,
# and this script) land in <Install>\app\ ; the launcher Wyrdsekai.exe sits at
# <Install>\ (one level up). Dev layout: everything side-by-side. Resolve both.
$AppDir = $PSScriptRoot
if (Test-Path (Join-Path $AppDir "Wyrdsekai.exe")) {
    $InstallDir = $AppDir
} elseif (Test-Path (Join-Path (Split-Path $AppDir -Parent) "Wyrdsekai.exe")) {
    $InstallDir = Split-Path $AppDir -Parent
} else {
    $InstallDir = $AppDir
}
$ServerExe = Join-Path $InstallDir "Wyrdsekai.exe"

if ($env:WYRDSEKAI_DATA_DIR) {
    $DataDir = $env:WYRDSEKAI_DATA_DIR
} else {
    $DataDir = Join-Path $env:USERPROFILE ".wyrdsekai"
}
$ConfFile = Join-Path $DataDir "wyrdsekai.conf"
$PidFile  = Join-Path $DataDir ".wyrd-server.pid"
$LogFile  = Join-Path $DataDir "server.log"
$ErrFile  = Join-Path $DataDir "server.err.log"

# Where the JVM side writes its rolling log. logback.xml resolves
# ${WYRDSEKAI_LOG_DIR:-logs} — a RELATIVE default — so left unset, every
# Java-backed subcommand drops a logs\ directory into whatever directory the
# operator happens to be standing in, and one elevated run leaves an
# Administrator-owned wyrdsekai.log that later ordinary runs cannot open.
# Logback treats a failed appender as a CONFIGURATION error and dumps ~60 lines
# of internal status plus a stack trace over the command's real output.
# Pin it to one absolute per-user path. This mirrors bin/wyrd on Linux/macOS —
# the same bug existed on both and has to be fixed in both, because these are
# two independent CLI implementations of the same contract.
if (-not $env:WYRDSEKAI_LOG_DIR) {
    $stateRoot = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $env:USERPROFILE "AppData\Local" }
    $env:WYRDSEKAI_LOG_DIR = Join-Path $stateRoot "Wyrdsekai\logs"
}
New-Item -ItemType Directory -Path $env:WYRDSEKAI_LOG_DIR -Force -ErrorAction SilentlyContinue | Out-Null

# Local inference (llama.cpp) — bootstrapped by `wyrd inference install`.
# Mirrors the .deb (bundled CPU llama-server) / .pkg (MLX) local-first model:
# Windows ships no binary in the .msi (keeps it lean), but `wyrd inference
# install` GPU-detects and pulls the matching llama.cpp Windows build so the
# companion thinks locally out of the box — same as every other platform.
$LlamaDir       = Join-Path $DataDir "llama"
$LlamaServerExe = Join-Path $LlamaDir "llama-server.exe"
$ModelsDir      = Join-Path $DataDir "models"
$VectorsDir     = Join-Path $DataDir "vectors\v8"
$LlamaPidFile   = Join-Path $DataDir ".llama-server.pid"
$VoicePidFile   = Join-Path $DataDir ".llama-server-voice.pid"
$LlamaLog       = Join-Path $DataDir ".llama-server.log"
$VoiceLog       = Join-Path $DataDir ".llama-server-voice.log"

$RestPort  = 7070
$DrivePort = 8200
$VoicePort = 8201

# llama.cpp release source + model fallback chain (mirrors bin/wyrd setup)
$LlamaRepo      = "ggml-org/llama.cpp"
$ModelPrimary   = "https://wyrdsekai.org/models/wyrdsekai-3.5-4b-v10-q4km.gguf"
$ModelFallback  = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"
# Default V8 voice vectors + scales (matches bin/wyrd's v8_default)
$V8Default = @(
    @{ File = "anti_defiance.gguf";          Scale = 0.15 },
    @{ File = "es_register_hold.gguf";        Scale = 0.20 },
    @{ File = "refusal_stability.gguf";       Scale = 0.20 },
    @{ File = "first_person_presence.gguf";   Scale = 0.15 }
)

# Coding backend — goose is the default (Rust binary, tri-platform; with the
# default provider=openai → local llama-server it is local-free and tool-uses
# the model reliably where pi did not). Recipes dispatch through it, so a
# first-class Windows node bootstraps it exactly like .deb/.pkg do at setup.
$GooseRepo    = "aaif-goose/goose"   # repo moved block/goose -> aaif-goose/goose
$GooseTag     = "v1.34.1"            # pinned floor (matches coding-cli-bundle/manifest.json)
$GooseDir     = Join-Path $DataDir "coding-cli-bundle\goose"
$GooseExe     = Join-Path $GooseDir "goose.exe"

# Web search — metasearch (mat-1/metasearch2, Rust/axum; Searxng-API-compatible:
# /search?q=...&format=json on :8888). The Linux .deb bundles it as a service;
# the Windows node bootstraps the same binary so search is local + Docker-free.
# DuckDuckGo keyless fallback always works without this; metasearch lifts news
# + result quality to parity with Searxng.
$MetasearchDir  = Join-Path $DataDir "metasearch"
$MetasearchExe  = Join-Path $MetasearchDir "metasearch.exe"
$MetasearchPort = 8888
$MetasearchPid  = Join-Path $DataDir ".metasearch.pid"
$MetasearchLog  = Join-Path $DataDir ".metasearch.log"
# Windows binary source: a cross-compiled metasearch.exe (mat-1/metasearch2).
# Bundled in the .msi (app\bin) when present; else fetched from this URL.
# Set WYRDSEKAI_METASEARCH_WIN_URL to a published metasearch.exe (or .zip) to
# enable `wyrd search install` to pull it (mirrors the model-repo override).
$MetasearchWinUrl = $env:WYRDSEKAI_METASEARCH_WIN_URL

# Oracle forecasting sidecar (oracle-core, pure-python Flask on :7073). The .msi
# bundles the wheel at app\share\oracle ; `wyrd oracle bootstrap` pip-installs it
# into $DataDir\.venv-oracle and `wyrd oracle start` runs oracle-server. The Java
# zone (OracleBridge) auto-connects on :7073 health — no flag, sidecar-reachability
# gated. Mirrors the .deb (systemd unit) / .pkg (LaunchDaemon). Needs python3 on
# PATH; absent → bootstrap no-ops with a hint and the zone runs without forecasting.
$OracleVenv    = Join-Path $DataDir ".venv-oracle"
$OracleExe     = Join-Path $OracleVenv "Scripts\oracle-server.exe"
$OracleDataDir = Join-Path $DataDir "oracle"
$OraclePort    = 7073
$OraclePidFile = Join-Path $DataDir ".oracle.pid"
$OracleLog     = Join-Path $DataDir ".oracle.log"

# ── Output helpers ───────────────────────────────────────────────────────────
function Write-Info  { param($m) Write-Host "[wyrd] $m" }
function Write-Ok    { param($m) Write-Host "[ok]   $m" -ForegroundColor Green }
function Write-Warn2 { param($m) Write-Host "[warn] $m" -ForegroundColor Yellow }
function Write-Err2  { param($m) Write-Host "[err]  $m" -ForegroundColor Red }

# ── i18n (_T — parity with bin/wyrd's _t) ─────────────────────────────────────
# Loads scripts/i18n/wyrd_<locale>.json — staged in the .msi payload at
# app\scripts\i18n, or at the repo root in a dev checkout. Locale resolution:
# WYRDSEKAI_LOCALE, then WYRDSEKAI_LANG, then the Windows UI culture; normalized
# to en|es|ja (anything else falls back to en). Lookup is per-key: active locale
# first, then the English catalog, then the key itself — the user always sees
# SOMETHING, never silence. {0},{1},... are filled from the remaining args. The
# ${GREEN}/${NC} ANSI tokens some catalog strings carry are stripped (coloring on
# Windows is the Write-* helpers' job, not inline escapes).
function Resolve-WyrdLocale {
    $l = $env:WYRDSEKAI_LOCALE
    if (-not $l) { $l = $env:WYRDSEKAI_LANG }
    if (-not $l) { try { $l = (Get-Culture).TwoLetterISOLanguageName } catch { $l = "en" } }
    $l = ([string]$l).ToLower()
    $l = ($l -split '[._-]')[0]
    if ($l -in @('en','es','ja')) { return $l }
    return 'en'
}

function Resolve-WyrdI18nDir {
    foreach ($d in @((Join-Path $AppDir "scripts\i18n"),
                     (Join-Path $InstallDir "scripts\i18n"),
                     (Join-Path $AppDir "..\..\scripts\i18n"))) {
        if (Test-Path (Join-Path $d "wyrd_en.json")) { return $d }
    }
    return $null
}

$script:WyrdLocale  = Resolve-WyrdLocale
$script:WyrdI18nDir = Resolve-WyrdI18nDir
$script:I18nCat     = $null   # active-locale catalog (hashtable key -> string), lazy-loaded
$script:I18nCatEn   = $null   # English fallback catalog

function Import-I18nCatalog {
    param([string]$Locale)
    if (-not $script:WyrdI18nDir) { return @{} }
    $p = Join-Path $script:WyrdI18nDir "wyrd_$Locale.json"
    if (-not (Test-Path $p)) { return @{} }
    $h = @{}
    try {
        $obj = Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($prop in $obj.PSObject.Properties) { $h[$prop.Name] = [string]$prop.Value }
    } catch { return @{} }
    return $h
}

function _T {
    param(
        [Parameter(Mandatory = $true, Position = 0)] [string] $Key,
        [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $TArgs = @()
    )
    if ($null -eq $script:I18nCat) { $script:I18nCat = Import-I18nCatalog -Locale $script:WyrdLocale }
    if ($null -eq $script:I18nCatEn) {
        if ($script:WyrdLocale -eq 'en') { $script:I18nCatEn = $script:I18nCat }
        else { $script:I18nCatEn = Import-I18nCatalog -Locale 'en' }
    }
    $val = $null
    if ($script:I18nCat.ContainsKey($Key))       { $val = $script:I18nCat[$Key] }
    elseif ($script:I18nCatEn.ContainsKey($Key)) { $val = $script:I18nCatEn[$Key] }
    if ($null -eq $val) { $val = $Key }
    for ($i = 0; $i -lt $TArgs.Count; $i++) {
        $val = $val.Replace('{' + $i + '}', [string]$TArgs[$i])
    }
    foreach ($tok in @('${GREEN}','${YELLOW}','${RED}','${CYAN}','${NC}')) {
        $val = $val.Replace($tok, '')
    }
    return $val
}

# ── models-index.json (release model index — parity with bin/wyrd _models_index) ──
# The RELEASE index of blessed model material (pinned HF revisions + sha256).
# Staged at app\models-index.json by build-msi.ps1; repo root in dev checkouts.
# models-manifest.jsonl = what THIS node actually has (when, hash, source);
# model-history.jsonl = the audit trail of swaps.
function Get-ModelsIndexPath {
    foreach ($f in @((Join-Path $AppDir "models-index.json"),
                     (Join-Path $InstallDir "models-index.json"),
                     (Join-Path $AppDir "..\..\models-index.json"))) {
        if (Test-Path $f) { return $f }
    }
    return $null
}

function Get-ModelsIndex {
    $p = Get-ModelsIndexPath
    if (-not $p) { return $null }
    try { return (Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Get-IndexModel {
    param([string]$Id)
    $idx = Get-ModelsIndex
    if (-not $idx) { return $null }
    return ($idx.models | Where-Object { $_.id -eq $Id } | Select-Object -First 1)
}

# Null-safe property read on JSON objects (PSCustomObject) — Set-StrictMode makes
# bare access on an absent property a terminating error, so every optional field
# in REST/index payloads goes through here.
function Get-JProp {
    param($Obj, [string]$Name, $Default = $null)
    if ($null -ne $Obj) {
        $p = $Obj.PSObject.Properties[$Name]
        if ($p -and $null -ne $p.Value) { return $p.Value }
    }
    return $Default
}

function Add-ModelManifestRecord {
    param([string]$File, [string]$Id, [string]$Version, [string]$SourceUrl)
    if (-not (Test-Path $File)) { return }
    try {
        New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null
        $sha = (Get-FileHash $File -Algorithm SHA256).Hash.ToLower()
        $ts = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        $rec = '{"v":1,"file":"' + (Split-Path $File -Leaf) + '","id":"' + $Id + '","version":"' + $Version +
               '","sha256":"' + $sha + '","source_url":"' + $SourceUrl + '","recorded_at":"' + $ts + '"}'
        Add-Content -Path (Join-Path $ModelsDir "models-manifest.jsonl") -Value $rec
    } catch { Write-Warn2 "manifest record failed: $_" }
}

function Add-ModelHistory {
    param([string]$Id, [string]$Action, [string]$Detail)
    try {
        New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
        $ts = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
        $rec = '{"v":1,"id":"' + $Id + '","action":"' + $Action + '","detail":"' + $Detail + '","at":"' + $ts + '"}'
        Add-Content -Path (Join-Path $DataDir "model-history.jsonl") -Value $rec
    } catch { }
}

function Get-ManifestEntries {
    # Latest manifest record per file name (hashtable: file -> record object).
    $mf = Join-Path $ModelsDir "models-manifest.jsonl"
    $h = @{}
    if (Test-Path $mf) {
        foreach ($line in (Get-Content $mf -ErrorAction SilentlyContinue)) {
            if (-not $line -or -not $line.Trim()) { continue }
            try {
                $e = $line | ConvertFrom-Json
                $f = [string](Get-JProp $e 'file')
                if ($f) { $h[$f] = $e }
            } catch { }
        }
    }
    return $h
}

# ── REST API base + session token (parity with bin/wyrd login/logout) ─────────
function Get-ApiBase {
    if ($env:WYRDSEKAI_API_URL) { return $env:WYRDSEKAI_API_URL.TrimEnd('/') }
    $port = if ($env:WYRDSEKAI_PORT) { $env:WYRDSEKAI_PORT } else { $RestPort }
    return "http://localhost:$port"
}

function Get-SessionToken {
    # WYRDSEKAI_TOKEN env wins; else the file `wyrd login` persisted.
    if ($env:WYRDSEKAI_TOKEN) { return $env:WYRDSEKAI_TOKEN }
    $f = Join-Path $DataDir "session.token"
    if (Test-Path $f) {
        $t = Get-Content $f -Raw -ErrorAction SilentlyContinue
        if ($t) { return $t.Trim() }
    }
    return $null
}

function Get-AdminHeaders {
    $h = @{}
    if ($env:WYRDSEKAI_ADMIN_TOKEN) { $h['X-Wyrdsekai-Admin-Token'] = $env:WYRDSEKAI_ADMIN_TOKEN }
    return $h
}

# ── Conf load/save (KEY=VALUE env file) ───────────────────────────────────────
function Get-Conf {
    $h = [ordered]@{}
    if (Test-Path $ConfFile) {
        foreach ($line in Get-Content $ConfFile) {
            $t = $line.Trim()
            if ($t -eq "" -or $t.StartsWith("#")) { continue }
            $eq = $t.IndexOf("=")
            if ($eq -lt 1) { continue }
            $h[$t.Substring(0, $eq).Trim()] = $t.Substring($eq + 1).Trim()
        }
    }
    return $h
}

function Set-ConfKey {
    param($Key, $Value)
    $lines = if (Test-Path $ConfFile) { Get-Content $ConfFile } else { @() }
    $found = $false
    $out = foreach ($line in $lines) {
        if ($line -match "^\s*$([regex]::Escape($Key))\s*=") { $found = $true; "$Key=$Value" } else { $line }
    }
    if (-not $found) { $out = @($out) + "$Key=$Value" }
    Set-Content -Path $ConfFile -Value $out -Encoding ASCII
}

# ── Java CLI bridge ─────────────────────────────────────────────────────────────
# The crypto-heavy relay/household operations (NKey enrollment, join redemption,
# zone-secret claim, deregister) live in ONE Java class, RelayNkeyAdminMain, that
# Linux/macOS (bin/wyrd) already shell into. Windows reuses the exact same jar —
# no PowerShell crypto. Resolve the bundled jpackage JRE (or a dev/PATH java),
# build the classpath from the staged jars, and run with $WYRDSEKAI_CONF pointed
# at our conf so Java reads/writes the right file.
function Resolve-WyrdJava {
    $candidates = @(
        (Join-Path $InstallDir "runtime\bin\java.exe"),
        "C:\tools\jdk25\bin\java.exe"
    )
    foreach ($j in $candidates) { if (Test-Path $j) { return $j } }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

function Invoke-WyrdJavaClass {
    param([string]$Class, [string[]]$JavaArgs = @())
    $java = Resolve-WyrdJava
    if (-not $java) {
        Write-Err2 "No Java runtime found (looked in $InstallDir\runtime\bin, C:\tools\jdk25, PATH)."
        return 2
    }
    # Jars are staged alongside this script in the jpackage app dir.
    $cp = Join-Path $AppDir "*"
    $jvm = @(
        "-Djava.net.preferIPv4Stack=true",
        "--add-opens","java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens","java.base/java.lang=ALL-UNNAMED",
        "--add-opens","java.base/sun.nio.ch=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",$cp,$Class
    )
    $prev = $env:WYRDSEKAI_CONF
    $env:WYRDSEKAI_CONF = $ConfFile
    try {
        & $java @jvm @JavaArgs
        return $LASTEXITCODE
    } finally {
        if ($null -eq $prev) { Remove-Item Env:\WYRDSEKAI_CONF -ErrorAction SilentlyContinue }
        else { $env:WYRDSEKAI_CONF = $prev }
    }
}

# Like Invoke-WyrdJavaClass, but streams the tool's stdout/stderr to the console
# via Write-Host and returns ONLY the exit code (a `$rc = Invoke-WyrdJavaClass ...`
# capture swallows the tool's stdout into $rc — fine for silent admin verbs, wrong
# for output-bearing ones like NamingAdminMain/StateDumpMain). -LibSubdir switches
# the classpath to app\lib\<subdir>\* (the per-module dists build-msi.ps1 stages:
# cli / daemon-desktop / wyrd-rendezvous) with fallback to the flat app dir.
function Invoke-WyrdJavaClassStream {
    param([string]$Class, [string[]]$JavaArgs = @(), [string]$LibSubdir = "")
    # Normalize: a $null $Rest wrapped via @($Rest) yields @($null), which would
    # splat as a bogus empty-string argv[0] to the Java tool. Strip nulls.
    $JavaArgs = @($JavaArgs | Where-Object { $null -ne $_ -and "$_" -ne "" })
    $java = Resolve-WyrdJava
    if (-not $java) {
        Write-Err2 "No Java runtime found (looked in $InstallDir\runtime\bin, C:\tools\jdk25, PATH)."
        return 2
    }
    $cp = Join-Path $AppDir "*"
    if ($LibSubdir) {
        $sub = Join-Path $AppDir "lib\$LibSubdir"
        if (Test-Path $sub) { $cp = Join-Path $sub "*" }
    }
    $jvm = @(
        "-Djava.net.preferIPv4Stack=true",
        "--add-opens","java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens","java.base/java.lang=ALL-UNNAMED",
        "--add-opens","java.base/sun.nio.ch=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",$cp,$Class
    )
    $prev = $env:WYRDSEKAI_CONF
    $env:WYRDSEKAI_CONF = $ConfFile
    # Relax EAP for the native call: with 2>&1 under EAP=Stop, PS 5.1 promotes any
    # stderr line to a terminating NativeCommandError (same trap as build-msi.ps1).
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $java @jvm @JavaArgs 2>&1 | ForEach-Object { Write-Host "$_" }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEAP
        if ($null -eq $prev) { Remove-Item Env:\WYRDSEKAI_CONF -ErrorAction SilentlyContinue }
        else { $env:WYRDSEKAI_CONF = $prev }
    }
}

# ── Relay / household join (parity with bin/wyrd `do_relay`) ─────────────────────
# Member-node surface: join (direct token), the NKey lifecycle (register-nkey,
# re-enroll, re-register-existing, deregister), phone-invite, ssh-enable/disable —
# all forwarded to RelayNkeyAdminMain. status/disable/leave/legs are conf-native.
function Invoke-Relay {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }

    # Subcommands that forward verbatim to the Java entrypoint (args[0]=subcmd).
    $javaForwarded = @("join","register-nkey","re-enroll","re-register-existing",
                       "deregister","print-pubkey","phone-invite","ssh-enable",
                       "ssh-disable","claim")
    if ($javaForwarded -contains $sub) {
        if ($sub -eq "join") { Write-Info "Joining relay-homed household..." }
        # Stream variant, NOT Invoke-WyrdJavaClass: these verbs print output
        # ("relay join OK — homed on …"), and the plain capture swallows that
        # output into $rc, so a SUCCESSFUL join was reported as "[err] relay
        # join failed" (observed live 2026-07-30 — registered fine server-side).
        $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.server.RelayNkeyAdminMain" -JavaArgs $Rest
        if ($rc -eq 0) {
            if ($sub -in @("join","register-nkey","re-enroll")) {
                Write-Ok "Relay join/enroll complete. Apply: wyrd restart"
            }
            if ($sub -eq "ssh-disable") {
                # Parity with bin/wyrd (2026-07-30): disabling the tunnel must
                # clear everything the enablement created, not just the relay
                # record — the WYRDSEKAI_SSH_TUNNEL_* conf knobs and any key
                # material. ssh-enable regenerates all of it.
                if (Test-Path $ConfFile) {
                    $kept = Get-Content $ConfFile | Where-Object { $_ -notmatch '^\s*WYRDSEKAI_SSH_TUNNEL_' }
                    Set-Content -Path $ConfFile -Value $kept -Encoding ASCII
                }
                Remove-Item (Join-Path $DataDir "ssh_tunnel_key"), (Join-Path $DataDir "ssh_tunnel_key.pub"), `
                            (Join-Path $DataDir "jump_key"), (Join-Path $DataDir "jump_key.pub"), `
                            (Join-Path $DataDir "ssh_tunnel_known_hosts") -Force -ErrorAction SilentlyContinue
                Write-Ok "SSH-over-relay disabled - tunnel config and keys cleared."
            }
        } else {
            Write-Err2 "relay $sub failed (exit $rc)."
        }
        return
    }

    $conf = Get-Conf
    switch ($sub) {
        "status" {
            $enabled = if ($conf.Contains('WYRDSEKAI_RELAY_ENABLED')) { $conf['WYRDSEKAI_RELAY_ENABLED'] } else { "false" }
            if ($enabled -eq "true") {
                Write-Ok "Relay: enabled"
                $url = if ($conf.Contains('WYRDSEKAI_RELAY_URL')) { $conf['WYRDSEKAI_RELAY_URL'] } else { "(unset)" }
                $fp  = if ($conf.Contains('WYRDSEKAI_RELAY_FINGERPRINT')) { $conf['WYRDSEKAI_RELAY_FINGERPRINT'] } else { "(unset)" }
                Write-Host "  URL         : $url"
                Write-Host "  fingerprint : $fp"
                Write-Host "  NKey mode   : $(if ($conf.Contains('WYRDSEKAI_RELAY_USE_NKEY')) { $conf['WYRDSEKAI_RELAY_USE_NKEY'] } else { 'false' })"
                if ($url -match 'nats://([^:/]+):?(\d+)?') {
                    $h = $Matches[1]; $p = if ($Matches[2]) { [int]$Matches[2] } else { 4222 }
                    $ok = Test-NetConnection -ComputerName $h -Port $p -InformationLevel Quiet -WarningAction SilentlyContinue
                    Write-Host ("  reachable   : {0}" -f $(if ($ok) { "yes ($h`:$p)" } else { "NO ($h`:$p)" }))
                }
                # SSH-over-relay: show HOW to connect, not just a flag (parity
                # with bin/wyrd, 2026-07-30 — the assigned port was previously
                # only in the ssh-enable output and the conf).
                if ($conf.Contains('WYRDSEKAI_SSH_TUNNEL_ENABLED') -and $conf['WYRDSEKAI_SSH_TUNNEL_ENABLED'] -eq 'true') {
                    $stHost  = if ($conf.Contains('WYRDSEKAI_SSH_TUNNEL_RELAY_HOST'))  { $conf['WYRDSEKAI_SSH_TUNNEL_RELAY_HOST'] }  else { '?' }
                    $stCPort = if ($conf.Contains('WYRDSEKAI_SSH_TUNNEL_RELAY_PORT'))  { $conf['WYRDSEKAI_SSH_TUNNEL_RELAY_PORT'] }  else { '2222' }
                    $stRPort = if ($conf.Contains('WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT')) { $conf['WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT'] } else { '?' }
                    $stTopo  = if ($conf.Contains('WYRDSEKAI_SSH_TUNNEL_TOPOLOGY'))    { $conf['WYRDSEKAI_SSH_TUNNEL_TOPOLOGY'] }    else { 'port' }
                    Write-Host ""
                    Write-Host "  SSH-over-relay: enabled (topology: $stTopo, your port: $stRPort)"
                    if ($stTopo -eq 'jump') {
                        Write-Host "    Connect with:  ssh -J wyrd-tunnel@$stHost`:$stCPort -p $stRPort <your-zone-account>@127.0.0.1"
                    } else {
                        Write-Host "    Connect with:  ssh -p $stRPort <your-zone-account>@$stHost"
                    }
                }
            } else {
                Write-Warn2 "Relay: disabled."
                Write-Host "  Join one with: wyrd relay join <wyrdjoin://...>"
                Write-Host "  Or self-serve on a commons relay: wyrd relay join <host> --fingerprint <fp>"
                Write-Host "  (the fingerprint is published on the relay's web page)"
                Write-Host "  (A same-LAN household needs NO relay — just point WYRDSEKAI_NATS_URL at the hub.)"
            }
        }
        "disable" {
            Set-ConfKey -Key "WYRDSEKAI_RELAY_ENABLED" -Value "false"
            Write-Ok "Relay disabled. Apply: wyrd restart"
        }
        "leave" {
            # If SSH-over-relay is enabled, tear it down FIRST — while the
            # relay credentials still authorize the signed ssh-disable
            # (parity with bin/wyrd, found live 2026-07-30: leaving stripped
            # WYRDSEKAI_RELAY_* but left the tunnel enablement in play).
            $confNow = Get-Conf
            if ($confNow.Contains('WYRDSEKAI_SSH_TUNNEL_ENABLED') -and $confNow['WYRDSEKAI_SSH_TUNNEL_ENABLED'] -eq 'true') {
                Write-Info "SSH-over-relay is enabled - disabling it before leaving..."
                Invoke-WyrdJavaClass -Class "org.wyrdsekai.server.RelayNkeyAdminMain" -JavaArgs @("ssh-disable") | Out-Null
            }
            Write-Info "Deregistering from relay (best-effort)..."
            Invoke-WyrdJavaClass -Class "org.wyrdsekai.server.RelayNkeyAdminMain" -JavaArgs @("deregister") | Out-Null
            if (Test-Path $ConfFile) {
                $kept = Get-Content $ConfFile | Where-Object { $_ -notmatch '^\s*WYRDSEKAI_(RELAY|SSH_TUNNEL)_' }
                Set-Content -Path $ConfFile -Value $kept -Encoding ASCII
            }
            Remove-Item (Join-Path $DataDir "ssh_tunnel_key"), (Join-Path $DataDir "ssh_tunnel_key.pub"), `
                        (Join-Path $DataDir "jump_key"), (Join-Path $DataDir "jump_key.pub"), `
                        (Join-Path $DataDir "ssh_tunnel_known_hosts") -Force -ErrorAction SilentlyContinue
            Write-Ok "Left relay; stripped relay + ssh-tunnel config. Apply: wyrd restart"
        }
        { $_ -in @("legs","list-legs") } {
            Write-Host "Relay legs:"
            if ($conf.Contains('WYRDSEKAI_RELAY_URL')) { Write-Host ("  [0] {0}" -f $conf['WYRDSEKAI_RELAY_URL']) }
            $conf.Keys | Where-Object { $_ -match '^WYRDSEKAI_RELAY_LEG_(\d+)_URL$' } | Sort-Object | ForEach-Object {
                $n = $Matches[1]; Write-Host ("  [$n] {0}" -f $conf[$_])
            }
        }
        default {
            Write-Host "usage: wyrd relay <subcommand>"
            Write-Host "  join <wyrdjoin://host:port/code.cafp> | <host[:port]> <code>   join a relay-homed household"
            Write-Host "  join <host> --fingerprint <ca_fp>                             self-serve on a commons relay"
            Write-Host "       (no invite code; the fingerprint comes from the relay's web page — on Windows"
            Write-Host "        --fingerprint is REQUIRED for self-serve; there is no interactive confirm)"
            Write-Host "  status | disable | leave | legs                               manage the relay leg"
            Write-Host "  register-nkey <wyrdrelay://...> | re-enroll | re-register-existing | deregister"
            Write-Host "  print-pubkey | phone-invite | ssh-enable | ssh-disable | claim <owner-token>"
            Write-Host ""
            Write-Host "Note: a same-LAN household needs NO relay. Use 'wyrd discover' to find the hub,"
            Write-Host "      then 'wyrd config set WYRDSEKAI_NATS_URL nats://<hub-ip>:4222' + same WYRDSEKAI_ZONE_ID."
        }
    }
}

# Direct-LAN discovery (parity with `wyrd discover` / bin/wyrd MdnsDiscoveryMain).
function Invoke-Discover {
    Invoke-WyrdJavaClass -Class "org.wyrdsekai.core.config.MdnsDiscoveryMain" -JavaArgs @("--timeout","3000") | Out-Null
}

# Load conf into the current process environment (so the server inherits it).
function Import-ConfEnv {
    $conf = Get-Conf
    foreach ($k in $conf.Keys) {
        # Skip shell-expansion artifacts copied from a *nix conf (e.g. $(hostname))
        if ($conf[$k] -match '\$\(') { continue }
        Set-Item -Path "Env:$k" -Value $conf[$k]
    }
    # DATA_DIR always wins from our resolution
    $env:WYRDSEKAI_DATA_DIR = $DataDir
    # WYRDSEKAI_HOME = the install payload root (app\), where the full standalone
    # tree lives (scripts/, core/, rooms/, data/) — recipes + classifier Python
    # resolve their source-relative paths from here. Equivalent of the .pkg
    # plist's WorkingDirectory = /usr/local/wyrdsekai.
    $env:WYRDSEKAI_HOME = $AppDir
    # Put the bootstrapped goose binary on PATH so GooseRuntimeConfig's
    # PATH-based executable lookup ("goose") resolves for the server process.
    if ((Test-Path $GooseExe) -and ($env:PATH -notlike "*$GooseDir*")) {
        $env:PATH = "$GooseDir;$env:PATH"
    }
}

# ── Process helpers ───────────────────────────────────────────────────────────
function Get-ServerPid {
    if (Test-Path $PidFile) {
        $p = (Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($p -and (Get-Process -Id $p -ErrorAction SilentlyContinue)) { return [int]$p }
    }
    # Fall back to scanning for the launcher process
    $proc = Get-Process Wyrdsekai -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($proc) { return $proc.Id }
    return $null
}

function Test-Health {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$RestPort/health" -UseBasicParsing -TimeoutSec 4
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Test-PortListening {
    param([int]$Port = $RestPort)
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

# ── Local inference (llama.cpp) bootstrap ──────────────────────────────────────
# Pick the right llama.cpp Windows build for this machine's GPU. All variants
# are real llama.cpp, so all support --control-vector-scaled (the V8 voice
# vectors) — that's why we bootstrap llama.cpp rather than lean on Ollama.
function Resolve-LlamaBackend {
    # Returns @{ Backend = 'cuda'|'vulkan'|'cpu'; CudaVer = '13.3'|'12.4'|$null }
    $nvidia = $false
    $anyGpu = $false
    try {
        $vcs = Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue
        foreach ($v in $vcs) {
            if ($v.Name -and $v.Name -notmatch 'Microsoft Basic|Remote Display') { $anyGpu = $true }
            if ($v.Name -match 'NVIDIA') { $nvidia = $true }
        }
    } catch {}

    if ($nvidia -and (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
        # Parse the driver's max CUDA version to pick a compatible build.
        $cudaVer = "12.4"
        try {
            $smi = & nvidia-smi 2>$null | Out-String
            if ($smi -match 'CUDA Version:\s*([0-9]+)\.([0-9]+)') {
                $maj = [int]$Matches[1]; $min = [int]$Matches[2]
                if ($maj -gt 13 -or ($maj -eq 13 -and $min -ge 3)) { $cudaVer = "13.3" }
                elseif ($maj -ge 12) { $cudaVer = "12.4" }
            }
        } catch {}
        return @{ Backend = 'cuda'; CudaVer = $cudaVer }
    }
    if ($anyGpu) { return @{ Backend = 'vulkan'; CudaVer = $null } }
    return @{ Backend = 'cpu'; CudaVer = $null }
}

function Get-LatestLlamaAssets {
    # Query the GitHub releases API for the latest llama.cpp Windows assets.
    $headers = @{ 'User-Agent' = 'wyrd-cli'; 'Accept' = 'application/vnd.github+json' }
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$LlamaRepo/releases/latest" -Headers $headers -TimeoutSec 30
    return @{ Tag = $rel.tag_name; Assets = $rel.assets }
}

function Install-LlamaServer {
    param([string]$Backend, [string]$CudaVer, [switch]$Force)

    if ((Test-Path $LlamaServerExe) -and -not $Force) {
        Write-Ok "llama.cpp already installed → $LlamaDir (use 'inference install --force' to re-fetch)"
        if (-not (Get-Conf).Contains('WYRDSEKAI_LLAMA_BACKEND')) { Set-ConfKey -Key "WYRDSEKAI_LLAMA_BACKEND" -Value $Backend }
        return $true
    }

    $rel = Get-LatestLlamaAssets
    Write-Info "llama.cpp latest release: $($rel.Tag) (backend: $Backend$(if ($CudaVer) { " cuda-$CudaVer" }))"

    # Match the main runtime zip for the chosen backend.
    $wanted = switch ($Backend) {
        'cuda'   { "bin-win-cuda-$CudaVer-x64.zip" }
        'vulkan' { "bin-win-vulkan-x64.zip" }
        default  { "bin-win-cpu-x64.zip" }
    }
    # Anchor on the "llama-" prefix: the cudart companion is named
    # "cudart-llama-...bin-win-cuda-13.3-x64.zip" and would otherwise also match.
    $mainAsset = $rel.Assets | Where-Object { $_.name -like "llama-*$wanted" } | Select-Object -First 1
    if (-not $mainAsset) { Write-Err2 "No llama.cpp asset matching 'llama-*$wanted' in release $($rel.Tag)"; return $false }

    # CUDA builds need the cudart runtime DLLs from a companion zip.
    $cudartAsset = $null
    if ($Backend -eq 'cuda') {
        $cudartAsset = $rel.Assets | Where-Object { $_.name -like "*cudart*cuda-$CudaVer-x64.zip" } | Select-Object -First 1
        if (-not $cudartAsset) { Write-Warn2 "cudart-$CudaVer zip not found — CUDA build may fail to load DLLs" }
    }

    New-Item -ItemType Directory -Force -Path $LlamaDir | Out-Null
    $tmp = Join-Path $env:TEMP "wyrd-llama"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $oldPP = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'  # PS5 progress bar slows downloads ~10x
    try {
        foreach ($a in @($mainAsset, $cudartAsset)) {
            if (-not $a) { continue }
            $zip = Join-Path $tmp $a.name
            $mb = [math]::Round($a.size / 1MB, 0)
            Write-Info "Downloading $($a.name) ($mb MB)..."
            Invoke-WebRequest -Uri $a.browser_download_url -OutFile $zip -TimeoutSec 1800
            Write-Info "Extracting $($a.name)..."
            Expand-Archive -Path $zip -DestinationPath $tmp -Force
            Remove-Item $zip -ErrorAction SilentlyContinue
        }
    } finally {
        $ProgressPreference = $oldPP
    }

    # llama.cpp zips sometimes nest binaries one level down; flatten everything
    # that looks like a runtime file into $LlamaDir.
    $bins = Get-ChildItem -Path $tmp -Recurse -Include *.exe, *.dll -ErrorAction SilentlyContinue
    foreach ($b in $bins) { Copy-Item $b.FullName -Destination (Join-Path $LlamaDir $b.Name) -Force }
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $LlamaServerExe)) { Write-Err2 "llama-server.exe not found after extract"; return $false }
    Set-ConfKey -Key "WYRDSEKAI_LLAMA_BACKEND" -Value $Backend
    Write-Ok "llama.cpp installed → $LlamaDir"
    return $true
}

# Robust large-file download (parity with bin/wyrd's _download). Windows 10 1803+ ships
# the real curl.exe: --http1.1 dodges the "HTTP/2 stream CANCEL" that fails multi-GB GGUF
# pulls near the end; -C - RESUMES the .part on retry instead of restarting from 0%;
# --retry rides out transient drops. Falls back to Invoke-WebRequest where curl.exe is
# absent. Returns $true on success (dest present).
function Invoke-RobustDownload {
    param([string]$Url, [string]$Dest)
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        $part = "$Dest.part"
        & curl.exe -fL --http1.1 --retry 8 --retry-delay 3 --retry-all-errors -C - -o $part $Url
        if ($LASTEXITCODE -eq 0 -and (Test-Path $part)) { Move-Item -Force $part $Dest; return $true }
        Remove-Item $part -ErrorAction SilentlyContinue
        return $false
    }
    try { Invoke-WebRequest -Uri $Url -OutFile $Dest -TimeoutSec 3600; return (Test-Path $Dest) }
    catch { return $false }
}

function Install-Model {
    New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null
    $existing = Get-ChildItem -Path $ModelsDir -Filter *.gguf -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existing) { Write-Ok "Model already present: $($existing.Name)"; return $true }

    # Model URL comes from models-index.json (release-pinned revision + sha256 —
    # model durability on Windows, W9); the legacy hardcoded URLs stay as fallback
    # for old payloads that shipped without the index.
    $sources = @()
    $cm = Get-IndexModel -Id "companion-4b"
    $cmUrl = [string](Get-JProp $cm 'url')
    if ($cmUrl) {
        $sources += @{ Url = $cmUrl; Id = "companion-4b"; Version = [string](Get-JProp $cm 'version' 'v10'); Sha = [string](Get-JProp $cm 'sha256') }
    } else {
        $sources += @{ Url = $ModelPrimary; Id = "companion-4b"; Version = "v10"; Sha = "" }
    }
    $sources += @{ Url = $ModelFallback; Id = "companion-4b"; Version = "upstream-fallback"; Sha = "" }

    $oldPP = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
    try {
        foreach ($src in $sources) {
            $name = Split-Path $src.Url -Leaf
            $dest = Join-Path $ModelsDir $name
            Write-Info "Pulling model $name (~2.6 GB)..."
            try {
                if (Invoke-RobustDownload -Url $src.Url -Dest $dest) {
                    if ((Test-Path $dest) -and (Get-Item $dest).Length -gt 100MB) {
                        if ($src.Sha) {
                            $got = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
                            if ($got -ne $src.Sha.ToLower()) {
                                Write-Warn2 "sha256 mismatch for $name (got $got, expected $($src.Sha)) - discarding, trying next source"
                                Remove-Item $dest -ErrorAction SilentlyContinue
                                continue
                            }
                            Write-Ok "sha256 verified against models-index.json"
                        }
                        Write-Ok "Model ready: $name"
                        # Node model manifest (data-durability, 2026-07-09) — parity with bin/wyrd.
                        Add-ModelManifestRecord -File $dest -Id $src.Id -Version $src.Version -SourceUrl $src.Url
                        return $true
                    }
                }
                Remove-Item $dest -ErrorAction SilentlyContinue
            } catch {
                Write-Warn2 "Pull from $name source failed; trying next..."
                Remove-Item $dest -ErrorAction SilentlyContinue
            }
        }
    } finally {
        $ProgressPreference = $oldPP
    }
    Write-Warn2 "Could not auto-pull a model. Drop a .gguf into $ModelsDir and re-run, or point WYRDSEKAI_LLAMA_URL at a remote node."
    return $false
}

function Sync-V8Vectors {
    # Copy V8 vectors bundled in the .msi (app\data\vectors\v8) into the data dir
    # where start resolves them. Idempotent.
    foreach ($src in @((Join-Path $AppDir "data\vectors\v8"), (Join-Path $InstallDir "data\vectors\v8"))) {
        if (Test-Path $src) {
            New-Item -ItemType Directory -Force -Path $VectorsDir | Out-Null
            Copy-Item -Path (Join-Path $src "*.gguf") -Destination $VectorsDir -Force -ErrorAction SilentlyContinue
            return
        }
    }
}

function Install-EmbeddingModel {
    # Embedding model — paraphrase-multilingual-MiniLM-L12-v2 (384-d, ~118MB).
    # Without it, EmbeddingService cold-starts and the classifier heads +
    # memory/library retrieval are unavailable. Mirrors bin/wyrd's three-tier
    # resolution: (1) already in models dir, (2) bundled in the .msi payload,
    # (3) HuggingFace download (Xenova mirror) as last resort.
    $onnx = "paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
    $tok  = "paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json"
    $onnxDst = Join-Path $ModelsDir $onnx
    $tokDst  = Join-Path $ModelsDir $tok
    New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null

    if ((Test-Path $onnxDst) -and (Test-Path $tokDst)) {
        Write-Ok "Embedding model already present in $ModelsDir"
        return
    }
    # (2) bundled copy. share\embedding-models is the canonical bundle dir
    # (parity with the .deb /opt/wyrdsekai/share/embedding-models and .pkg;
    # staged by build-msi.ps1 from packaging\embedding-models) — probed FIRST so
    # offline installs never touch HuggingFace. The core-resources dirs are the
    # older staging spots, kept as fallback.
    foreach ($base in @((Join-Path $AppDir "share\embedding-models"),
                        (Join-Path $InstallDir "share\embedding-models"),
                        (Join-Path $AppDir "core\src\main\resources\models"),
                        (Join-Path $InstallDir "core\src\main\resources\models"),
                        (Join-Path $AppDir "models"))) {
        if (Test-Path (Join-Path $base $onnx)) {
            Copy-Item (Join-Path $base $onnx) $onnxDst -Force -ErrorAction SilentlyContinue
            if (Test-Path (Join-Path $base $tok)) { Copy-Item (Join-Path $base $tok) $tokDst -Force -ErrorAction SilentlyContinue }
            if (Test-Path $onnxDst) { Write-Ok "Embedding model installed from bundle ($base)"; break }
        }
    }
    # (3) HuggingFace download fallback — URL from models-index.json (pinned
    # revision, W9 model durability), hardcoded main-branch URL only when no
    # index is present.
    $embUrl = "https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
    $tokUrl = "https://wyrdsekai.org/models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json"
    $em = Get-IndexModel -Id "embedding-paraphrase-l12"
    $emIdxUrl = [string](Get-JProp $em 'url')
    if ($emIdxUrl) { $embUrl = $emIdxUrl }
    # Use the index's explicit tokenizer_url. This used to RECONSTRUCT a HuggingFace URL
    # from hf_repo + hf_revision, which silently overrode the working URL set above — and
    # HuggingFace now answers 403 to plain HTTP for every Xet-backed file, ours included.
    # hf_repo/hf_revision record where the bytes CAME FROM; they are provenance, not a
    # download path, and must never be turned back into one.
    $emTokIdxUrl = [string](Get-JProp $em 'tokenizer_url')
    if ($emTokIdxUrl) { $tokUrl = $emTokIdxUrl }
    $oldPP = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
    try {
        if (-not (Test-Path $onnxDst)) {
            Write-Info "Downloading embedding model from HuggingFace (~118MB)..."
            try {
                if (Invoke-RobustDownload -Url $embUrl -Dest $onnxDst) {
                    if ((Test-Path $onnxDst) -and (Get-Item $onnxDst).Length -gt 50MB) { Write-Ok "Embedding ONNX ready" }
                    else { Remove-Item $onnxDst -ErrorAction SilentlyContinue; Write-Warn2 "Embedding ONNX download incomplete" }
                } else { Write-Warn2 "Embedding ONNX download incomplete" }
            } catch {
                Remove-Item $onnxDst -ErrorAction SilentlyContinue
                Write-Warn2 "Embedding ONNX download failed — retrieval/classifiers disabled until 'wyrd setup' re-runs with network"
            }
        }
        if (-not (Test-Path $tokDst)) {
            try {
                [void](Invoke-RobustDownload -Url $tokUrl -Dest $tokDst)
            } catch { Write-Warn2 "Embedding tokenizer download failed" }
        }
    } finally { $ProgressPreference = $oldPP }
}

function Resolve-ModelPath {
    if (-not (Test-Path $ModelsDir)) { return $null }
    $m = Get-ChildItem -Path $ModelsDir -Filter *.gguf -ErrorAction SilentlyContinue |
         Sort-Object { $_.Name -notlike "wyrdsekai*" }, Name | Select-Object -First 1
    if ($m) { return $m.FullName }
    return $null
}

function Invoke-InferenceInstall {
    param([string]$BackendOverride, [switch]$SkipModel, [switch]$Force)

    $resolved = if ($BackendOverride) { @{ Backend = $BackendOverride; CudaVer = "13.3" } } else { Resolve-LlamaBackend }
    Write-Info "Detected inference backend: $($resolved.Backend)$(if ($resolved.CudaVer -and $resolved.Backend -eq 'cuda') { " (CUDA $($resolved.CudaVer))" })"

    if (-not (Install-LlamaServer -Backend $resolved.Backend -CudaVer $resolved.CudaVer -Force:$Force)) { exit 1 }
    Sync-V8Vectors
    if (-not $SkipModel) { Install-Model | Out-Null }

    # Flip conf to local inference. Drive (9B) on :$DrivePort + 4B voice on :$VoicePort
    # (parity with macOS/Linux dual-inference). WYRDSEKAI_VOICE_ENABLED=true also
    # flips the Java voice-pass default ON (WyrdConfig.voiceEnabled), so the 9B's
    # content is re-voiced through the 4B.
    Set-ConfKey -Key "WYRDSEKAI_LLAMA_ENABLED" -Value "true"
    Set-ConfKey -Key "WYRDSEKAI_LLAMA_URL"     -Value "http://127.0.0.1:$DrivePort"
    Set-ConfKey -Key "WYRDSEKAI_VOICE_ENABLED" -Value "true"
    Set-ConfKey -Key "WYRDSEKAI_VOICE_URL"     -Value "http://127.0.0.1:$VoicePort"
    # Household inference auto-share: a GPU backend offers
    # its accelerator to household members by default (parity with bin/wyrd setup).
    if ($resolved.Backend -in @("cuda","vulkan")) {
        Set-ConfKey -Key "WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE" -Value "true"
        Write-Ok "GPU backend ($($resolved.Backend)) — household GPU sharing enabled."
    }
    Write-Ok "Local inference configured. Start it with: wyrd start"
}

# ── Coding backend (goose) bootstrap ───────────────────────────────────────────
# goose is the default coding backend (recipes dispatch through it). Mirrors the
# .deb/.pkg `wyrd setup` auto-install: fetch the upstream release, drop the binary
# in coding-cli-bundle/goose, and point it (provider=openai) at the local
# llama-server so it's local-free out of the box.
function Install-Goose {
    param([switch]$Force)

    if ((Test-Path $GooseExe) -and -not $Force) {
        Write-Ok "goose already installed → $GooseDir (use 'coding install goose --force' to re-fetch)"
        return $true
    }

    $headers = @{ 'User-Agent' = 'wyrd-cli'; 'Accept' = 'application/vnd.github+json' }
    # Pin to the manifest floor, but fall back to latest if the tag is gone.
    $rel = $null
    foreach ($uri in @("https://api.github.com/repos/$GooseRepo/releases/tags/$GooseTag",
                       "https://api.github.com/repos/$GooseRepo/releases/latest")) {
        try { $rel = Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 30; break }
        catch { continue }
    }
    if (-not $rel) { Write-Err2 "Could not query $GooseRepo releases (network?)."; return $false }
    Write-Info "goose release: $($rel.tag_name)"

    # Match the Windows x64 asset. Upstream ships goose-x86_64-pc-windows-msvc.zip
    # plus a -cuda variant. Prefer the PLAIN msvc zip — goose talks to our
    # llama-server over HTTP, so it needs no CUDA of its own.
    $winZips = $rel.assets | Where-Object {
        $_.name -match 'windows' -and $_.name -match 'x86_64|x64|amd64' -and $_.name -match '\.zip$'
    }
    $asset = $winZips | Where-Object { $_.name -notmatch 'cuda' } | Select-Object -First 1
    if (-not $asset) { $asset = $winZips | Select-Object -First 1 }
    if (-not $asset) {
        # Last-ditch: any windows asset at all (.zip or .exe).
        $asset = $rel.assets | Where-Object { $_.name -match 'windows' } | Select-Object -First 1
    }
    if (-not $asset) {
        Write-Warn2 "No Windows goose asset in release $($rel.tag_name)."
        Write-Warn2 "Available: $(( $rel.assets | ForEach-Object { $_.name }) -join ', ')"
        Write-Warn2 "goose may not publish a Windows binary — the coding fallback chain (pi, …) still works, but recipes need goose. Revisit upstream."
        return $false
    }

    New-Item -ItemType Directory -Force -Path $GooseDir | Out-Null
    $tmp = Join-Path $env:TEMP "wyrd-goose"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $oldPP = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
    try {
        $dl = Join-Path $tmp $asset.name
        $mb = [math]::Round($asset.size / 1MB, 0)
        Write-Info "Downloading $($asset.name) ($mb MB)..."
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $dl -TimeoutSec 1800
        if ($asset.name -match '\.zip$') {
            Write-Info "Extracting $($asset.name)..."
            Expand-Archive -Path $dl -DestinationPath $tmp -Force
        }
    } finally {
        $ProgressPreference = $oldPP
    }

    # Flatten goose.exe (and any sidecar DLLs) into $GooseDir.
    $exe = Get-ChildItem -Path $tmp -Recurse -Filter "goose*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $exe) {
        # Some releases ship the bare binary as the downloaded file itself.
        $bare = Join-Path $tmp $asset.name
        if ($asset.name -match '\.exe$') { Copy-Item $bare (Join-Path $GooseDir "goose.exe") -Force }
    } else {
        Copy-Item $exe.FullName (Join-Path $GooseDir "goose.exe") -Force
        Get-ChildItem -Path $exe.DirectoryName -Filter *.dll -ErrorAction SilentlyContinue |
            ForEach-Object { Copy-Item $_.FullName (Join-Path $GooseDir $_.Name) -Force }
    }
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $GooseExe)) { Write-Err2 "goose.exe not found after extract"; return $false }

    # Wire conf: goose is default backend; provider=openai → local llama-server
    # (keyless). Record the actual loaded model name so the GOOSE_MODEL forwarded
    # to the subprocess matches what llama-server reports.
    Set-ConfKey -Key "WYRDSEKAI_CODING_DEFAULT_BACKEND" -Value "goose"
    Set-ConfKey -Key "WYRDSEKAI_CODING_GOOSE_ENABLED"   -Value "true"
    Set-ConfKey -Key "WYRDSEKAI_CODING_GOOSE_PROVIDER"  -Value "openai"
    $model = Resolve-ModelPath
    if ($model) { Set-ConfKey -Key "WYRDSEKAI_CODING_GOOSE_MODEL" -Value (Split-Path $model -Leaf) }
    Write-Ok "goose installed → $GooseDir (default coding backend; provider=openai → local :$DrivePort)"
    return $true
}

function Invoke-Coding {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }
    switch ($sub) {
        "status" {
            Write-Host "Coding backend:"
            $conf = Get-Conf
            $def = if ($conf.Contains('WYRDSEKAI_CODING_DEFAULT_BACKEND')) { $conf['WYRDSEKAI_CODING_DEFAULT_BACKEND'] } else { "goose (default)" }
            Write-Host "  default backend = $def"
            Write-Host ("  goose           = {0}" -f $(if (Test-Path $GooseExe) { "installed ($GooseDir)" } else { "not installed - run 'wyrd coding install goose'" }))
            if ($conf.Contains('WYRDSEKAI_CODING_GOOSE_MODEL')) { Write-Host "  goose model     = $($conf['WYRDSEKAI_CODING_GOOSE_MODEL'])" }
        }
        "install" {
            $what = if ($Rest.Count -ge 2) { $Rest[1] } else { "goose" }
            $force = $Rest -contains '--force'
            New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
            if (-not (Test-Path $ConfFile)) { Invoke-Setup }
            if ($what -eq 'goose') { Install-Goose -Force:$force | Out-Null }
            else { Write-Err2 "Only 'goose' is bootstrappable on Windows so far (it's the default). Other backends: install via npm / point at a Linux node." ; exit 2 }
        }
        default { Write-Err2 "usage: wyrd coding [status|install goose [--force]]"; exit 2 }
    }
}

# ── Web search (metasearch) bootstrap ──────────────────────────────────────────
function Resolve-MetasearchSource {
    # Prefer a binary bundled in the .msi payload (app\bin or install\bin).
    foreach ($d in @((Join-Path $AppDir "bin"), (Join-Path $InstallDir "bin"))) {
        $cand = Join-Path $d "metasearch.exe"
        if (Test-Path $cand) { return @{ Kind = "bundled"; Path = $cand } }
    }
    if ($MetasearchWinUrl) { return @{ Kind = "url"; Path = $MetasearchWinUrl } }
    return $null
}

function Install-Metasearch {
    param([switch]$Force)
    if ((Test-Path $MetasearchExe) -and -not $Force) {
        Write-Ok "metasearch already installed → $MetasearchDir (use 'search install --force' to re-fetch)"
        return $true
    }
    $src = Resolve-MetasearchSource
    if (-not $src) {
        Write-Warn2 "No metasearch.exe available (not bundled in .msi, and WYRDSEKAI_METASEARCH_WIN_URL unset)."
        Write-Warn2 "Web search still works via the DuckDuckGo keyless fallback; metasearch adds Searxng-quality news/results."
        Write-Warn2 "Provide a cross-compiled metasearch.exe (mat-1/metasearch2) via that env var or the .msi to enable it."
        return $false
    }
    New-Item -ItemType Directory -Force -Path $MetasearchDir | Out-Null
    if ($src.Kind -eq "bundled") {
        Copy-Item $src.Path $MetasearchExe -Force
        Write-Ok "metasearch installed from .msi payload → $MetasearchDir"
    } else {
        $oldPP = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
        try {
            $tmp = Join-Path $env:TEMP ("wyrd-metasearch-" + (Split-Path $src.Path -Leaf))
            Write-Info "Downloading metasearch from $($src.Path)..."
            Invoke-WebRequest -Uri $src.Path -OutFile $tmp -TimeoutSec 900
            if ($src.Path -match '\.zip$') {
                $ex = Join-Path $env:TEMP "wyrd-metasearch-ex"; New-Item -ItemType Directory -Force -Path $ex | Out-Null
                Expand-Archive -Path $tmp -DestinationPath $ex -Force
                $found = Get-ChildItem -Path $ex -Recurse -Filter "metasearch*.exe" | Select-Object -First 1
                if ($found) { Copy-Item $found.FullName $MetasearchExe -Force }
                Remove-Item $ex -Recurse -Force -ErrorAction SilentlyContinue
            } else {
                Copy-Item $tmp $MetasearchExe -Force
            }
            Remove-Item $tmp -ErrorAction SilentlyContinue
        } finally { $ProgressPreference = $oldPP }
    }
    if (-not (Test-Path $MetasearchExe)) { Write-Err2 "metasearch.exe not present after install"; return $false }
    # Point the companion's web-search at it.
    Set-ConfKey -Key "WYRDSEKAI_SEARXNG_URL" -Value "http://localhost:$MetasearchPort"
    Write-Ok "metasearch installed → $MetasearchDir (WYRDSEKAI_SEARXNG_URL=http://localhost:$MetasearchPort)"
    return $true
}

function Start-Metasearch {
    if (-not (Test-Path $MetasearchExe)) { return $false }
    if (Get-LlamaPid -File $MetasearchPid) { Write-Ok "metasearch already running"; return $true }
    # metasearch (mat-1/metasearch2) defaults to 0.0.0.0:28019. Pin it to :8888
    # (matching WYRDSEKAI_SEARXNG_URL) via an explicit config.toml passed as arg.
    # Partial config — all other fields fall back to serde defaults, EXCEPT api:
    # metasearch2's JSON API (`?format=json`, the Searxng-compatible surface the
    # Java WebSearchService consumes) defaults OFF and 403s without `api = true`.
    $cfg = Join-Path $MetasearchDir "config.toml"
    # Rewrite when absent or when an older config lacks the api flag (pre-fix files
    # pinned only `bind`, which left the JSON API returning 403 Forbidden).
    if ((-not (Test-Path $cfg)) -or -not (Select-String -Path $cfg -Pattern '^\s*api\s*=' -Quiet)) {
        Set-Content -Path $cfg -Value "bind = `"127.0.0.1:$MetasearchPort`"`napi = true" -Encoding ASCII
    }
    $p = Start-Process -FilePath $MetasearchExe -ArgumentList "`"$cfg`"" -WorkingDirectory $MetasearchDir `
        -RedirectStandardOutput $MetasearchLog -RedirectStandardError "$MetasearchLog.err" `
        -PassThru -WindowStyle Hidden
    Set-Content -Path $MetasearchPid -Value $p.Id -Encoding ASCII
    Write-Info "metasearch started (pid $($p.Id)) → http://localhost:$MetasearchPort"
    return $true
}

function Stop-Metasearch {
    # NB: do not name this $pid — that collides with the read-only automatic
    # $PID variable, so the assignment throws and the process is never killed.
    $msPid = Get-LlamaPid -File $MetasearchPid
    if ($msPid) { Stop-Process -Id $msPid -Force -ErrorAction SilentlyContinue; Remove-Item $MetasearchPid -ErrorAction SilentlyContinue }
}

function Invoke-Search {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }
    switch ($sub) {
        "status" {
            $conf = Get-Conf
            Write-Host "Web search:"
            Write-Host ("  metasearch       = {0}" -f $(if (Test-Path $MetasearchExe) { "installed ($MetasearchDir)" } else { "not installed (DuckDuckGo fallback active)" }))
            Write-Host ("  SEARXNG_URL      = {0}" -f $(if ($conf.Contains('WYRDSEKAI_SEARXNG_URL')) { $conf['WYRDSEKAI_SEARXNG_URL'] } else { "(unset → DuckDuckGo fallback)" }))
            if (Test-Path $MetasearchExe) {
                Write-Host ("  :$MetasearchPort running     = {0}" -f $(if (Get-LlamaPid -File $MetasearchPid) { "yes" } else { "no (run 'wyrd search start')" }))
            }
        }
        "install" {
            $force = $Rest -contains '--force'
            New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
            if (-not (Test-Path $ConfFile)) { Invoke-Setup }
            Install-Metasearch -Force:$force | Out-Null
        }
        "start" { if (Install-Metasearch) { Start-Metasearch | Out-Null } }
        "stop"  { Stop-Metasearch; Write-Ok "metasearch stopped." }
        default { Write-Err2 "usage: wyrd search [status|install [--force]|start|stop]"; exit 2 }
    }
}

# ── llama-server lifecycle ─────────────────────────────────────────────────────
function Get-LlamaPid {
    param([string]$File)
    if (Test-Path $File) {
        $p = (Get-Content $File -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($p -and (Get-Process -Id $p -ErrorAction SilentlyContinue)) { return [int]$p }
    }
    return $null
}

function Start-LlamaServer {
    if (-not (Test-Path $LlamaServerExe)) { return }   # not installed; nothing to start
    $model = Resolve-ModelPath
    if (-not $model) { Write-Warn2 "No model in $ModelsDir — run 'wyrd inference install'. Skipping local inference."; return }

    $ngl = if ($env:WYRDSEKAI_GPU_LAYERS) { $env:WYRDSEKAI_GPU_LAYERS } else { "99" }

    # Drive/skills brain on :8200
    if (-not (Get-LlamaPid -File $LlamaPidFile) -and -not (Test-PortListening -Port $DrivePort)) {
        Write-Info "Starting local inference (drive :$DrivePort, model=$(Split-Path $model -Leaf))..."
        # --jinja applies the GGUF's embedded chat template; --reasoning-budget 0
        # then disables <think> blocks (the control only engages under --jinja).
        # Qwen3.5-derived models ship reasoning ON and would burn the whole token
        # budget thinking, leaving message.content empty. Mirrors home-server's docker args.
        $driveArgs = @("-m", "`"$model`"", "--host", "127.0.0.1", "--port", "$DrivePort", "-c", "8192", "-np", "1", "-ngl", $ngl, "--jinja", "--reasoning", "off", "--reasoning-budget", "0")
        $p = Start-Process -FilePath $LlamaServerExe -ArgumentList $driveArgs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $LlamaLog -RedirectStandardError "$LlamaLog.err"
        Set-Content -Path $LlamaPidFile -Value $p.Id -Encoding ASCII
    }

    # Voice brain on :8201 (+ V8 control vectors) unless explicitly disabled.
    # Key normalized to WYRDSEKAI_VOICE_ENABLED (parity with macOS/Linux + the Java
    # WyrdConfig.voiceEnabled that gates the 4B voice-pass). Default-on (unset spawns)
    # so existing installs don't regress; "0"/"false"/"no" disables.
    if ($env:WYRDSEKAI_VOICE_ENABLED -notin @("0","false","no") -and
        -not (Get-LlamaPid -File $VoicePidFile) -and -not (Test-PortListening -Port $VoicePort)) {
        # --control-vector-scaled wants comma-separated FNAME:SCALE, but the
        # FNAME:SCALE split collides with a Windows drive-letter colon
        # (C:\...gguf:0.15 → parse error). Fix: run llama-server with its working
        # directory = the vectors dir and pass BARE filenames, so no drive colon
        # appears. Verified loading 4 V8 vectors clean on the 5060 Ti.
        $csv = @()
        foreach ($v in $V8Default) {
            if (Test-Path (Join-Path $VectorsDir $v.File)) { $csv += "$($v.File):$($v.Scale)" }
        }
        $vecArgs = @()
        if ($csv.Count -gt 0) {
            $vecArgs = @("--control-vector-scaled", ($csv -join ","))
            Write-Info "Starting voice brain (:$VoicePort, +$($csv.Count) V8 vectors)..."
        } else {
            Write-Warn2 "Voice :$VoicePort starting WITHOUT V8 vectors (none at $VectorsDir)"
        }
        # Working dir = vectors dir so the bare control-vector filenames resolve.
        $voiceCwd = if (Test-Path $VectorsDir) { $VectorsDir } else { $DataDir }
        $vargs = @("-m", "`"$model`"", "--host", "127.0.0.1", "--port", "$VoicePort", "-c", "4096", "-np", "1", "-ngl", $ngl, "--jinja", "--reasoning", "off", "--reasoning-budget", "0") + $vecArgs
        $vp2 = Start-Process -FilePath $LlamaServerExe -ArgumentList $vargs -WorkingDirectory $voiceCwd -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $VoiceLog -RedirectStandardError "$VoiceLog.err"
        Set-Content -Path $VoicePidFile -Value $vp2.Id -Encoding ASCII
    }
}

function Stop-LlamaServer {
    foreach ($f in @($LlamaPidFile, $VoicePidFile)) {
        $lp = Get-LlamaPid -File $f
        if ($lp) { Stop-Process -Id $lp -Force -ErrorAction SilentlyContinue }
        Remove-Item $f -ErrorAction SilentlyContinue
    }
}

function Stop-NatsServer {
    # The JVM's NatsServerManager auto-starts the BUNDLED app\nats-server.exe as a
    # child, and nothing used to stop it: `wyrd stop` reported "Stopped." while
    # nats-server kept running and holding :4222.
    #
    # That is not merely untidy. The exe lives INSIDE the install directory, so a
    # subsequent MSI uninstall fails with 1601 and leaves the whole tree on disk
    # plus the product still registered in Add/Remove Programs — i.e. anyone who
    # actually RAN wyrdsekai could not uninstall it. (Proven 2026-07-29: uninstall
    # before ever starting = 0; with the orphan alive = 1601 twice; orphan killed,
    # identical command = 0.)
    #
    # Match on the executable PATH, not the process name: a nats-server the
    # operator runs for their own reasons is none of our business.
    $ours = Join-Path $AppDir "nats-server.exe"
    $alt  = Join-Path $InstallDir "app\nats-server.exe"
    Get-Process -Name "nats-server" -ErrorAction SilentlyContinue | ForEach-Object {
        $path = try { $_.Path } catch { $null }
        if ($path -and ($path -eq $ours -or $path -eq $alt)) {
            Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-LlamaHealth {
    param([int]$Port = $DrivePort)
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 4
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

# ── Oracle forecasting sidecar (oracle-core) ──────────────────────────────────
$script:SawVenvlessPython = $null
function Test-PythonHasVenv {
    # The EMBEDDABLE Windows distribution ships without venv/ensurepip, so a
    # candidate that answers --version can still be useless for bootstrapping
    # (observed 2026-07-30: setup found C:\Tools\python312 — embeddable — and
    # `-m venv` died with "No module named venv"). Probe before accepting.
    param($Exe, $CandArgs)
    try {
        & $Exe @($CandArgs + @("-m","venv","-h")) *> $null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}
function Resolve-Python {
    # Prefer the py launcher (-3), then python / python3 on PATH.
    foreach ($cand in @(@{Exe="py";Args=@("-3")}, @{Exe="python";Args=@()}, @{Exe="python3";Args=@()})) {
        if (Get-Command $cand.Exe -ErrorAction SilentlyContinue) {
            try {
                & $cand.Exe @($cand.Args + @("--version")) *> $null
                if ($LASTEXITCODE -eq 0) {
                    if (Test-PythonHasVenv -Exe $cand.Exe -CandArgs $cand.Args) { return $cand }
                    $script:SawVenvlessPython = $cand.Exe
                }
            } catch { }
        }
    }
    # Nothing on PATH. Before giving up, look where Python actually tends to be
    # on Windows: the embeddable/no-admin installs people use precisely because
    # they do NOT want to modify PATH. An interpreter sitting in one of these
    # is perfectly usable — "not on PATH" is not the same as "not installed",
    # and reporting the former as the latter sends someone to reinstall
    # something they already have (observed on a box with a working
    # C:\Tools\python312, 2026-07-29).
    $roots = @(
        "$env:LOCALAPPDATA\Programs\Python\Python3*",
        "C:\Tools\python3*",
        "C:\Python3*",
        "$env:ProgramFiles\Python3*"
    )
    foreach ($pattern in $roots) {
        $dirs = Get-Item $pattern -ErrorAction SilentlyContinue | Sort-Object Name -Descending
        foreach ($d in $dirs) {
            $exe = Join-Path $d.FullName "python.exe"
            if (Test-Path $exe) {
                try {
                    & $exe --version *> $null
                    if ($LASTEXITCODE -eq 0) {
                        if (Test-PythonHasVenv -Exe $exe -CandArgs @()) { return @{ Exe = $exe; Args = @() } }
                        $script:SawVenvlessPython = $exe
                    }
                } catch { }
            }
        }
    }
    return $null
}

function Resolve-OracleWheel {
    foreach ($d in @((Join-Path $AppDir "share\oracle"), (Join-Path $InstallDir "share\oracle"))) {
        if (Test-Path $d) {
            $w = Get-ChildItem (Join-Path $d "oracle_core-*.whl") -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($w) { return $w.FullName }
        }
    }
    return $null
}

function Install-Oracle {
    param([switch]$Force)
    if ((Test-Path $OracleExe) -and -not $Force) {
        Write-Ok "oracle-core already bootstrapped → $OracleVenv (use 'oracle bootstrap --force' to rebuild)"
        return $true
    }
    $py = Resolve-Python
    if (-not $py) {
        if ($script:SawVenvlessPython) {
            Write-Warn2 "Found Python at $($script:SawVenvlessPython), but it is the EMBEDDABLE distribution (no venv module) and cannot host the Oracle sidecar. Install a full Python 3 ('winget install Python.Python.3.12') then 'wyrd oracle bootstrap'. Oracle forecasting disabled until then."
        } else {
            Write-Warn2 "No Python 3 found — Oracle forecasting disabled. Looked on PATH (py -3 / python / python3) and in the usual no-admin install roots (%LOCALAPPDATA%\Programs\Python, C:\Tools, C:\Python3x, Program Files). Install Python 3 ('winget install Python.Python.3.12') then 'wyrd oracle bootstrap'."
        }
        return $false
    }
    $wheel = Resolve-OracleWheel
    if (-not $wheel) { Write-Warn2 "oracle-core wheel not bundled in this .msi — Oracle forecasting unavailable."; return $false }
    Write-Info "Bootstrapping oracle-core venv ($OracleVenv) with $($py.Exe)..."
    & $py.Exe @($py.Args + @("-m","venv",$OracleVenv))
    if ($LASTEXITCODE -ne 0) { Write-Err2 "venv creation failed"; return $false }
    $venvPy = Join-Path $OracleVenv "Scripts\python.exe"
    & $venvPy -m pip install --quiet --upgrade pip
    & $venvPy -m pip install --quiet "$wheel"
    if ($LASTEXITCODE -ne 0) { Write-Err2 "pip install of oracle-core failed"; return $false }
    New-Item -ItemType Directory -Force -Path $OracleDataDir | Out-Null
    if (Test-Path $OracleExe) { Write-Ok "oracle-core bootstrapped → $OracleVenv"; return $true }
    Write-Err2 "oracle-server.exe not present after install"; return $false
}

function Start-Oracle {
    if (-not (Test-Path $OracleExe)) { return }   # not bootstrapped; nothing to start
    if ((Get-LlamaPid -File $OraclePidFile) -or (Test-PortListening -Port $OraclePort)) { return }
    New-Item -ItemType Directory -Force -Path $OracleDataDir | Out-Null
    $oargs = @("--port","$OraclePort","--host","127.0.0.1","--data-dir","`"$OracleDataDir`"")
    $p = Start-Process -FilePath $OracleExe -ArgumentList $oargs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $OracleLog -RedirectStandardError "$OracleLog.err"
    Set-Content -Path $OraclePidFile -Value $p.Id -Encoding ASCII
    Write-Info "oracle-core started (pid $($p.Id)) → http://localhost:$OraclePort"
}

function Stop-Oracle {
    $op = Get-LlamaPid -File $OraclePidFile
    if ($op) { Stop-Process -Id $op -Force -ErrorAction SilentlyContinue }
    Remove-Item $OraclePidFile -ErrorAction SilentlyContinue
}

function Test-OracleHealth {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$OraclePort/health" -UseBasicParsing -TimeoutSec 4
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Invoke-Oracle {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }
    switch ($sub) {
        "bootstrap" { Install-Oracle -Force:([bool]($Rest -contains "--force")) | Out-Null }
        "start"     { Start-Oracle }
        "stop"      { Stop-Oracle; Write-Ok "oracle stopped" }
        "restart"   { Stop-Oracle; Start-Sleep -Seconds 1; Start-Oracle }
        "status"    {
            $running = (Get-LlamaPid -File $OraclePidFile) -or (Test-PortListening -Port $OraclePort)
            $state = if ($running) { "running (/health=$(Test-OracleHealth))" } else { "stopped" }
            Write-Host ("  oracle :$OraclePort        = {0}" -f $state)
            Write-Host ("  venv                    = {0}" -f $(if (Test-Path $OracleExe) { $OracleVenv } else { "not bootstrapped" }))
        }
        default     { Write-Err2 "unknown oracle subcommand: $sub (bootstrap|start|stop|restart|status)" }
    }
}

# ── Commands ──────────────────────────────────────────────────────────────────
function Invoke-Setup {
    Write-Info "Setting up Wyrdsekai (data dir: $DataDir)"
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

    if (Test-Path $ConfFile) {
        Write-Warn2 "Config already exists: $ConfFile (leaving as-is; edit with 'wyrd config set')"
    } else {
        $nodeName = $env:COMPUTERNAME.ToLower()
        $conf = @"
# Wyrdsekai configuration - generated by 'wyrd setup' (Windows)

# Data
WYRDSEKAI_DATA_DIR=$DataDir

# Between mesh - on by default. Single-node runs NATS locally (bundled
# nats-server.exe auto-starts). To join an existing household, point
# WYRDSEKAI_NATS_URL at the first node's IP.
WYRDSEKAI_BETWEEN_ENABLED=true
WYRDSEKAI_NODE_NAME=$nodeName
WYRDSEKAI_ZONE_ID=home
WYRDSEKAI_NATS_URL=nats://127.0.0.1:4222
WYRDSEKAI_NATS_AUTO_START=true

# Household inference auto-share: offer this node's GPU
# to household members (set true by 'wyrd inference install' on a GPU box, or
# 'wyrd inference share on'); borrow a household peer's GPU when this box has none.
WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE=false
WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW=true

# Inference - `wyrd setup` configures LOCAL inference automatically
# (GPU-detect + llama.cpp + model download; the .msi ships no llama binary,
# it is fetched to match your GPU). To use a different backend instead:
#   Remote: point at another household node's drive backend, e.g.
#        WYRDSEKAI_LLAMA_URL=http://192.168.x.x:8200
#   Cloud:  set an API key in the Key Chest / conf, e.g.
#        ANTHROPIC_API_KEY=sk-...
#   (set WYRDSEKAI_SKIP_INFERENCE_INSTALL=1 before setup to skip the local
#    install entirely; 'wyrd inference install' re-runs it any time)
# Until a backend is set, the companion will boot but can't think.
WYRDSEKAI_LLAMA_ENABLED=false

# Search (optional - run a Searxng instance and point here)
# WYRDSEKAI_SEARXNG_URL=http://localhost:8888
"@
        Set-Content -Path $ConfFile -Value $conf -Encoding ASCII
        Write-Ok "Wrote $ConfFile"
    }

    # ── LAN auto-join offer (parity with bin/wyrd setup) ──────────────────────
    # Browse mDNS for a household hub; if one is found and this box isn't already
    # joined, offer to join it now so companions can borrow its GPU (install →
    # join → borrow in one guided step). Interactive only (a key must be pasted);
    # skips silently under CI / non-interactive and honours an off-box
    # WYRDSEKAI_NATS_URL. Best-effort: any failure leaves setup proceeding.
    try {
        $alreadyJoined = $false
        $natsUrl = $env:WYRDSEKAI_NATS_URL
        if ($natsUrl -and $natsUrl -notmatch '^nats://(127\.0\.0\.1|localhost):') { $alreadyJoined = $true }
        if (-not $alreadyJoined -and [Environment]::UserInteractive -and -not $env:CI) {
            $java = Resolve-WyrdJava
            if ($java) {
                $cp = Join-Path $AppDir "*"
                Write-Info "Scanning the LAN for existing wyrdsekai households (3s)..."
                $lanJson = & $java "-Djava.net.preferIPv4Stack=true" "-cp" $cp `
                    "org.wyrdsekai.core.config.MdnsDiscoveryMain" "--json" "--timeout" "3000" 2>$null
                $peers = $null
                if ($lanJson) { try { $peers = $lanJson | ConvertFrom-Json } catch { $peers = $null } }
                if ($peers) {
                    if ($peers -isnot [array]) { $peers = @($peers) }
                    $hub = $peers |
                        Where-Object { ($_.household -and $_.household -notin @('none','?','')) -or $_.inference } |
                        Sort-Object @{ Expression = {
                            [int][bool]($_.household -and $_.household -notin @('none','?','')) + [int][bool]$_.inference
                        }; Descending = $true } |
                        Select-Object -First 1
                    if ($hub) {
                        Write-Host ("  Found a Wyrdsekai household hub on your LAN: {0} ({1})." -f $hub.name, $hub.host)
                        $ans = Read-Host "  Join it so your companions can borrow its GPU? [Y/n]"
                        # Default-yes: Enter / y / yes proceeds; only an explicit n/no skips.
                        if ($ans -notmatch '^[Nn][Oo]?$') {
                            $key = Read-Host "  Paste the household key (run 'wyrd household key' on the hub)"
                            if ($key) {
                                $script:Rest = @($hub.host, "--household-key", $key)
                                Invoke-Join
                                Write-Info "Restart to apply: wyrd restart"
                            } else {
                                Write-Info "Skipped joining — run 'wyrd join <host> --household-key <key>' anytime."
                            }
                        } else {
                            Write-Info "Skipped joining — run 'wyrd join <host> --household-key <key>' anytime."
                        }
                    }
                }
            }
        }
    } catch { Write-Warn2 "LAN household scan skipped: $_" }

    # First-encounter doc (staged next to this script in the app dir)
    $fe = Join-Path $AppDir "FIRST_ENCOUNTER.md"
    if (-not (Test-Path $fe)) { $fe = Join-Path $InstallDir "FIRST_ENCOUNTER.md" }
    if (Test-Path $fe) {
        Write-Host ""
        Get-Content $fe | Select-Object -First 60 | ForEach-Object { Write-Host $_ }
        Write-Host ""
    }

    # Sync any V8 voice vectors bundled in the .msi into the data dir.
    Sync-V8Vectors

    # Embedding model — without it, classifier heads + memory/library retrieval
    # cold-start. Bundle-copy or HuggingFace download (mirrors bin/wyrd setup).
    Install-EmbeddingModel

    # Coding backend: bootstrap goose (the default — recipes dispatch through
    # it). Best-effort; the fallback chain covers a failed pull. Skip with
    # WYRDSEKAI_SKIP_GOOSE_INSTALL=1 (air-gap / CI), mirroring bin/wyrd.
    if ($env:WYRDSEKAI_SKIP_GOOSE_INSTALL -ne "1") {
        if (Test-Path $GooseExe) { Write-Ok "goose already present → $GooseDir" }
        else {
            Write-Info "Installing goose (default coding backend)..."
            try { Install-Goose | Out-Null } catch { Write-Warn2 "goose install failed: $_ (coding falls back to the chain)" }
        }
    }

    # Web search: install metasearch if a binary is available (bundled or URL).
    # Best-effort — web search falls back to keyless DuckDuckGo without it.
    if ($env:WYRDSEKAI_SKIP_METASEARCH_INSTALL -ne "1") {
        if (Resolve-MetasearchSource) {
            try { Install-Metasearch | Out-Null } catch { Write-Warn2 "metasearch install failed: $_ (web search uses DuckDuckGo fallback)" }
        } else {
            Write-Info "Web search: using DuckDuckGo fallback (no metasearch binary bundled). 'wyrd search install' once a Windows metasearch.exe is available."
        }
    }

    # Oracle forecasting sidecar: bootstrap the bundled wheel into a venv + start
    # it on :7073 so the zone connects on first boot. Best-effort — no python or
    # no wheel just leaves forecasting off (the zone runs fine without it). Skip
    # with WYRDSEKAI_SKIP_ORACLE_INSTALL=1 (air-gap / CI), mirroring bin/wyrd.
    if ($env:WYRDSEKAI_SKIP_ORACLE_INSTALL -ne "1") {
        try { if (Install-Oracle) { Start-Oracle } } catch { Write-Warn2 "oracle bootstrap failed: $_ (forecasting off; 'wyrd oracle bootstrap' to retry)" }
    }

    # Local inference — PARITY (2026-07-30): Linux and macOS `wyrd setup` end
    # with a companion that can think; Windows used to end with homework
    # ("Next: wyrd inference install") and a node that boots brainless. Now
    # setup chains into the same GPU-detect + llama.cpp + model flow
    # automatically, UNLESS the operator already chose a backend (local
    # enabled, remote URL, or a cloud key in conf) or opts out with
    # WYRDSEKAI_SKIP_INFERENCE_INSTALL=1 (air-gap / remote-backend installs).
    $confNow = Get-Conf
    $haveBackend = ($confNow.Contains('WYRDSEKAI_LLAMA_ENABLED') -and $confNow['WYRDSEKAI_LLAMA_ENABLED'] -eq 'true') -or
                   ($confNow.Contains('WYRDSEKAI_LLAMA_URL') -and $confNow['WYRDSEKAI_LLAMA_URL']) -or
                   $confNow.Contains('ANTHROPIC_API_KEY')
    if ($env:WYRDSEKAI_SKIP_INFERENCE_INSTALL -eq "1") {
        Write-Info "Local inference skipped (WYRDSEKAI_SKIP_INFERENCE_INSTALL=1) — point at a remote/cloud backend, or run 'wyrd inference install' later."
    } elseif ($haveBackend) {
        Write-Ok "Inference backend already configured — leaving it as-is."
    } else {
        Write-Info "Setting up local inference (GPU-detect + llama.cpp + model download — a few GB, several minutes)..."
        try {
            Invoke-InferenceInstall
        } catch {
            Write-Warn2 "Local inference setup failed: $_"
            Write-Warn2 "Retry with 'wyrd inference install', or point at a remote/cloud backend — see $ConfFile"
        }
    }

    Write-Ok "Setup complete."
    Write-Info "Next: 'wyrd start'"
    # Offer to add the install dir to the user PATH so 'wyrd' is callable anywhere.
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -notlike "*$InstallDir*") {
        Write-Info "Tip: add Wyrdsekai to your PATH so 'wyrd' works anywhere:"
        Write-Host "     [Environment]::SetEnvironmentVariable('Path', `"`$([Environment]::GetEnvironmentVariable('Path','User'));$InstallDir`", 'User')"
    }
}

function Invoke-Start {
    if (-not (Test-Path $ServerExe)) { Write-Err2 "Server launcher not found: $ServerExe"; exit 1 }
    $existing = Get-ServerPid
    if ($existing) {
        Write-Warn2 "Already running (pid $existing)"
        # Node up, but inference is a separate process — self-heal it if it died
        # under a running node (parity with the Linux bin/wyrd ensure_inference
        # fix, 2026-07-07). Start-LlamaServer is idempotent: no-op if :8200/:8201
        # are already serving. Needs env for the LLAMA_ENABLED gate + model paths.
        Import-ConfEnv
        if ($env:WYRDSEKAI_LLAMA_ENABLED -eq "true") { Start-LlamaServer }
        return
    }
    if (Test-PortListening) { Write-Warn2 "Port $RestPort already in use by another process"; }

    if (-not (Test-Path $ConfFile)) {
        Write-Warn2 "No config yet — running setup first."
        Invoke-Setup
    }
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
    Import-ConfEnv

    # Bring up local inference first (if installed + enabled) so :8200 is ready
    # when the companion boots. No-op if WYRDSEKAI_LLAMA_ENABLED!=true or no binary.
    if ($env:WYRDSEKAI_LLAMA_ENABLED -eq "true") { Start-LlamaServer }
    # Local web-search backend (if installed) so the companion's web/news search
    # has metasearch quality rather than the DuckDuckGo fallback.
    if (Test-Path $MetasearchExe) { Start-Metasearch | Out-Null }
    # Oracle forecasting sidecar (if bootstrapped) so :7073 is up when the zone
    # health-probes it. No-op if the venv was never created.
    Start-Oracle

    Write-Info "Starting server (launcher: $ServerExe)..."
    # WorkingDirectory = payload root so source-relative paths (scripts/, core/,
    # rooms/) that recipes + classifier tooling reference actually resolve.
    $p = Start-Process -FilePath $ServerExe -WorkingDirectory $AppDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $LogFile -RedirectStandardError $ErrFile
    Set-Content -Path $PidFile -Value $p.Id -Encoding ASCII

    # Wait up to 40s for /health
    $ok = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 1
        if (Test-Health) { $ok = $true; break }
        if (-not (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { break }
    }
    if ($ok) {
        Write-Ok "Server up (pid $($p.Id)) — REST http://localhost:$RestPort  SSH :7022"
    } elseif (Get-Process -Id $p.Id -ErrorAction SilentlyContinue) {
        Write-Warn2 "Process running (pid $($p.Id)) but /health not green yet — check $LogFile"
    } else {
        Write-Err2 "Server exited during startup. Tail of ${ErrFile}:"
        Get-Content $ErrFile -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
        exit 1
    }
}

function Invoke-Stop {
    $serverPid = Get-ServerPid
    if (-not $serverPid) {
        Write-Info "Not running."
        Remove-Item $PidFile -ErrorAction SilentlyContinue
        Stop-LlamaServer   # still tear down any orphaned llama-server processes
        Stop-NatsServer    # ...and the bundled nats-server, which blocks uninstall
        Stop-Oracle
        return
    }
    Write-Info "Stopping server (pid $serverPid)..."
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    # Also clear anything still holding :7070
    $portProc = Get-NetTCPConnection -State Listen -LocalPort $RestPort -ErrorAction SilentlyContinue
    if ($portProc) { Stop-Process -Id $portProc.OwningProcess -Force -ErrorAction SilentlyContinue }
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    Stop-LlamaServer
    Stop-NatsServer
    Stop-Metasearch
    Stop-Oracle
    Write-Ok "Stopped."
}

function Invoke-Status {
    $serverPid = Get-ServerPid
    $health = Test-Health
    $listen = Test-PortListening
    Write-Host "Wyrdsekai status:"
    Write-Host ("  process : {0}" -f $(if ($serverPid) { "running (pid $serverPid)" } else { "stopped" }))
    Write-Host ("  REST    : {0}" -f $(if ($health) { "http://localhost:$RestPort /health = 200" } elseif ($listen) { "port $RestPort listening (health not 200)" } else { "down" }))
    Write-Host ("  data dir: {0}" -f $DataDir)
    Write-Host ("  config  : {0}" -f $(if (Test-Path $ConfFile) { $ConfFile } else { "MISSING - run 'wyrd setup'" }))
    if (Test-Path $ConfFile) {
        $conf = Get-Conf
        $inf = if ($conf.Contains('WYRDSEKAI_LLAMA_URL')) { $conf['WYRDSEKAI_LLAMA_URL'] }
               elseif ($conf.Contains('WYRDSEKAI_LLAMA_ENABLED') -and $conf['WYRDSEKAI_LLAMA_ENABLED'] -eq 'true') { "local :$DrivePort" }
               else { "NOT configured" }
        Write-Host ("  inference: {0}" -f $inf)
        if (Test-Path $LlamaServerExe) {
            $dl = if (Get-LlamaPid -File $LlamaPidFile) { "running, /health=$(Test-LlamaHealth -Port $DrivePort)" } else { "stopped" }
            $vc = if (Get-LlamaPid -File $VoicePidFile) { "running" } else { "stopped" }
            Write-Host ("  llama    : drive :$DrivePort $dl  |  voice :$VoicePort $vc")
        }
    }
}

# Locate the shipped config catalog (scripts/config-catalog.json): every
# WYRDSEKAI_* key this build understands, with description + default.
# Generated from the in-world Scroll of Settings, so bash, PowerShell and the
# scroll all read ONE inventory (parity pass 2026-07-31).
function Resolve-ConfigCatalog {
    foreach ($p in @(
        (Join-Path $InstallDir "app\scripts\config-catalog.json"),
        (Join-Path $InstallDir "scripts\config-catalog.json"),
        (Join-Path $AppDir "scripts\config-catalog.json"))) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Invoke-Config {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "list" }
    switch ($sub) {
        "list" {
            # `config list --all` / `config list <group>` shows the CATALOG —
            # every key, its meaning, default, and current value. Bare `list`
            # keeps showing what is actually written in the conf.
            $arg = if ($Rest.Count -ge 2) { $Rest[1] } else { "" }
            if ($arg) {
                $catPath = Resolve-ConfigCatalog
                if (-not $catPath) { Write-Err2 "config catalog not found (scripts\config-catalog.json)"; exit 1 }
                $cat = Get-Content $catPath -Raw | ConvertFrom-Json
                $conf = Get-Conf
                $wantAll = @('--all','-a','all') -contains $arg
                $groups = if ($wantAll) { $cat.groups } else { $cat.groups | Where-Object { $_.id -eq $arg } }
                if (-not $groups) {
                    Write-Err2 "no such group: $arg"
                    Write-Host ("groups: " + (($cat.groups | ForEach-Object { $_.id }) -join ', '))
                    exit 1
                }
                foreach ($g in $groups) {
                    Write-Host ""
                    Write-Host ("== {0} - {1}" -f $g.id, $g.title)
                    foreach ($k in $g.keys) {
                        $cur = if ($conf.Contains($k.key)) { "= " + $conf[$k.key] }
                               else { "(unset, default: {0})" -f $(if ($k.default) { $k.default } else { '-' }) }
                        Write-Host ("  {0,-44} {1}" -f $k.key, $cur)
                        Write-Host ("  {0,-44}   {1}" -f "", $k.description)
                    }
                }
                if ($wantAll) {
                    Write-Host ""
                    Write-Host "Set one with:  wyrd config set KEY VALUE   then   wyrd restart"
                    Write-Host ("One group at a time:  wyrd config list <group>   ({0})" -f (($cat.groups | ForEach-Object { $_.id }) -join ', '))
                }
                return
            }
            if (-not (Test-Path $ConfFile)) { Write-Warn2 "No config. Run 'wyrd setup'."; return }
            Get-Content $ConfFile | ForEach-Object { Write-Host $_ }
            Write-Host ""
            Write-Host "(`wyrd config list --all` shows every key this build understands)"
        }
        "get" {
            if ($Rest.Count -lt 2) { Write-Err2 "usage: wyrd config get <KEY>"; exit 2 }
            $conf = Get-Conf; $k = $Rest[1]
            if ($conf.Contains($k)) { Write-Host $conf[$k] } else { Write-Warn2 "$k not set" }
        }
        "set" {
            if ($Rest.Count -lt 3) { Write-Err2 "usage: wyrd config set <KEY> <VALUE>"; exit 2 }
            New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
            Set-ConfKey -Key $Rest[1] -Value ($Rest[2..($Rest.Count-1)] -join ' ')
            Write-Ok "$($Rest[1]) set. Restart for it to take effect: wyrd restart"
        }
        "unset" {
            # Parity with bin/wyrd (2026-07-31) — Windows had get/set only.
            if ($Rest.Count -lt 2) { Write-Err2 "usage: wyrd config unset <KEY>"; exit 2 }
            if (-not (Test-Path $ConfFile)) { Write-Warn2 "No config. Run 'wyrd setup'."; return }
            $k = $Rest[1]
            $kept = Get-Content $ConfFile | Where-Object { $_ -notmatch ("^\s*" + [regex]::Escape($k) + "\s*=") }
            Set-Content -Path $ConfFile -Value $kept -Encoding ASCII
            Write-Ok "$k removed from $ConfFile. Restart for it to take effect: wyrd restart"
        }
        "path" {
            Write-Host $ConfFile
        }
        "edit" {
            if (-not (Test-Path $ConfFile)) { Write-Warn2 "No config. Run 'wyrd setup'."; return }
            $editor = if ($env:EDITOR) { $env:EDITOR } else { "notepad" }
            & $editor $ConfFile
        }
        "apply" {
            # The conf is read at process start, so applying = restarting.
            Write-Info "Applying configuration (restarting the node)..."
            Invoke-Stop; Start-Sleep -Seconds 1; Invoke-Start
        }
        default {
            Write-Err2 "usage: wyrd config [list [--all|<group>]|get <KEY>|set <KEY> <VALUE>|unset <KEY>|path|edit|apply]"
            exit 2
        }
    }
}

# Steward credential slots for external adapters — parity with `wyrd cred`
# (2026-07-31: Windows had NO cred command at all, so a Windows household
# could not store an API token for an item script). Forwards to the same
# CredAdminMain the bash CLI uses; the value is never passed on the command
# line, it is read from a hidden prompt inside that tool.
function Invoke-Cred {
    $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.server.CredAdminMain" -JavaArgs $Rest
    if ($rc -ne 0 -and $Rest.Count -eq 0) {
        Write-Host "usage: wyrd cred <set|get|list|unset> [slot]"
        Write-Host "       wyrd cred list --all    every slot this build understands"
    }
    return $rc
}

function Invoke-Inference {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }
    $conf = Get-Conf
    switch ($sub) {
        "status" {
            Write-Host "Inference configuration:"
            $enabled = if ($conf.Contains('WYRDSEKAI_LLAMA_ENABLED')) { $conf['WYRDSEKAI_LLAMA_ENABLED'] } else { "(unset)" }
            $url     = if ($conf.Contains('WYRDSEKAI_LLAMA_URL')) { $conf['WYRDSEKAI_LLAMA_URL'] } else { "(unset)" }
            $backend = if ($conf.Contains('WYRDSEKAI_LLAMA_BACKEND')) { $conf['WYRDSEKAI_LLAMA_BACKEND'] } else { "(not installed)" }
            Write-Host "  WYRDSEKAI_LLAMA_ENABLED = $enabled"
            Write-Host "  WYRDSEKAI_LLAMA_URL     = $url"
            Write-Host "  llama.cpp backend       = $backend"
            $model = Resolve-ModelPath
            Write-Host ("  model                   = {0}" -f $(if ($model) { Split-Path $model -Leaf } else { "(none in $ModelsDir)" }))
            if ($conf.Contains('ANTHROPIC_API_KEY')) { Write-Host "  ANTHROPIC_API_KEY       = (set)" }
            if (Test-Path $LlamaServerExe) {
                Write-Host ("  drive :$DrivePort           = {0}" -f $(if (Get-LlamaPid -File $LlamaPidFile) { "running (/health=$(Test-LlamaHealth -Port $DrivePort))" } else { "stopped" }))
                Write-Host ("  voice :$VoicePort           = {0}" -f $(if (Get-LlamaPid -File $VoicePidFile) { "running" } else { "stopped" }))
            }
            if ($enabled -ne 'true' -and $url -eq '(unset)' -and -not $conf.Contains('ANTHROPIC_API_KEY')) {
                Write-Warn2 "No inference backend configured — companion can't think yet."
                Write-Host  "  Local (recommended):  wyrd inference install"
                Write-Host  "  Remote household node: wyrd config set WYRDSEKAI_LLAMA_URL http://<node-ip>:$DrivePort"
                Write-Host  "  Cloud key:             wyrd config set ANTHROPIC_API_KEY sk-..."
            }
        }
        "install" {
            # wyrd inference install [cpu|vulkan|cuda] [--skip-model]
            $backend = $null; $skip = $false; $force = $false
            $instArgs = if ($Rest.Count -gt 1) { $Rest[1..($Rest.Count-1)] } else { @() }
            foreach ($a in $instArgs) {
                if ($a -in @('cpu','vulkan','cuda')) { $backend = $a }
                elseif ($a -eq '--skip-model') { $skip = $true }
                elseif ($a -eq '--force') { $force = $true }
            }
            New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
            if (-not (Test-Path $ConfFile)) { Invoke-Setup }
            Invoke-InferenceInstall -BackendOverride $backend -SkipModel:$skip -Force:$force
        }
        "start" { Import-ConfEnv; Start-LlamaServer }
        "stop"  { Stop-LlamaServer; Write-Ok "Local inference stopped." }
        "share" {
            # Household inference auto-share "offer" toggle.
            $arg = if ($Rest.Count -ge 2) { $Rest[1] } else { "status" }
            switch ($arg) {
                { $_ -in @('on','true','yes','enable') }   { Set-ConfKey -Key "WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE" -Value "true";  Write-Ok "Household inference share = true. Apply: wyrd restart" }
                { $_ -in @('off','false','no','disable') }  { Set-ConfKey -Key "WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE" -Value "false"; Write-Ok "Household inference share = false. Apply: wyrd restart" }
                default {
                    $share  = if ($conf.Contains('WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE'))  { $conf['WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE'] }  else { "(unset -> default off)" }
                    $borrow = if ($conf.Contains('WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW')) { $conf['WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW'] } else { "(unset -> default on)" }
                    Write-Host "Household inference share (offer GPU): $share"
                    Write-Host "Household inference borrow (use a household GPU): $borrow"
                }
            }
        }
        default { Write-Err2 "usage: wyrd inference [status|install [cpu|vulkan|cuda] [--skip-model]|start|stop|share [on|off|status]]"; exit 2 }
    }
}

function Invoke-Doctor {
    Write-Host "wyrd doctor:"
    # jpackage ships a minimal jlink runtime (no standalone java.exe — the JVM is
    # embedded in Wyrdsekai.exe), so check the runtime dir, not java.exe.
    $runtime = Join-Path $InstallDir "runtime"
    Write-Host ("  bundled JRE  : {0}" -f $(if (Test-Path $runtime) { "ok ($runtime)" } else { "MISSING" }))
    Write-Host ("  launcher     : {0}" -f $(if (Test-Path $ServerExe) { "ok" } else { "MISSING ($ServerExe)" }))
    Write-Host ("  nats-server  : {0}" -f $(if (Test-Path (Join-Path $AppDir 'nats-server.exe')) { "bundled" } else { "absent (Between single-node only)" }))
    Write-Host ("  data dir     : {0}" -f $(if (Test-Path $DataDir) { "ok ($DataDir)" } else { "absent - run 'wyrd setup'" }))
    Write-Host ("  config       : {0}" -f $(if (Test-Path $ConfFile) { "ok" } else { "absent - run 'wyrd setup'" }))
    $model = Resolve-ModelPath
    Write-Host ("  llama.cpp    : {0}" -f $(if (Test-Path $LlamaServerExe) { "ok ($LlamaDir)" } else { "not installed - run 'wyrd inference install'" }))
    Write-Host ("  model        : {0}" -f $(if ($model) { "ok ($(Split-Path $model -Leaf))" } else { "none in $ModelsDir" }))
    Write-Host ("  goose        : {0}" -f $(if (Test-Path $GooseExe) { "ok ($GooseDir)" } else { "not installed - run 'wyrd coding install goose'" }))
    Write-Host ("  web search   : {0}" -f $(if (Test-Path $MetasearchExe) { "metasearch ($MetasearchDir)" } else { "DuckDuckGo fallback (run 'wyrd search install' for metasearch)" }))
    $serverPid = Get-ServerPid
    Write-Host ("  server       : {0}" -f $(if ($serverPid) { "running (pid $serverPid), /health=$(Test-Health)" } else { "stopped" }))
    Write-Host ("  port $RestPort   : {0}" -f $(if (Test-PortListening) { "listening" } else { "free" }))
}

function Invoke-Help {
    Write-Host @"
Wyrdsekai - Windows management CLI

Usage: wyrd <command> [args]

Lifecycle:
  setup                 create data dir + write config + show first encounter
  start                 launch the server
  stop                  stop the server
  restart               stop then start
  status                process + REST health + config summary
  log                   tail -f the server log
  version [--mesh]      installed build (from the jar); --mesh = peers too
  update                source checkout: git pull + rebuild (msi: use a new .msi)

Config:
  config list           print wyrdsekai.conf
  config get <KEY>      read one key
  config set <KEY> <V>  set one key (restart to apply)

Data:
  backup                snapshot DBs/identity/souls/search into backups\ (keep 5)
  restore [<name>]      list backups, or restore one (server must be stopped)
  state dump            unified JSON snapshot of every household state store
  recover <key> <pass>  reset the steward password via the recovery key

Inference:
  inference status                     show backend + local llama state
  inference install [cpu|vulkan|cuda]  GPU-detect, fetch llama.cpp + model, enable local
  inference start | stop               manage the local llama-server(s)
  model [status|verify|update <id>|rollback <id>|check|history]
                                       release-index model lifecycle (models-index.json)

Coding:
  coding status                        show default backend + goose state
  coding install goose [--force]       fetch the goose binary (default coding backend)

Search:
  search status                        show web-search backend (metasearch vs DuckDuckGo)
  search install [--force]             install metasearch (Searxng-quality, no Docker)
  search start | stop                  manage the local metasearch process

Oracle (forecasting sidecar, :7073):
  oracle status                        show oracle process + /health + venv
  oracle bootstrap [--force]           pip-install the bundled oracle-core wheel
  oracle start | stop | restart        manage the oracle-server process

Household / federation:
  whoami | contacts | zones            naming admin (DID identity + directory)
  block | unblock | blocks | safety    naming-admin safety surface
  zone [federate|accept|revoke|status] zone federation (thin REST)
  federate <propose|accept|revoke|status [--mesh]|list|join|code|household-key>
  invite | key | journal | recipes | library
  login | logout                       persist/clear a steward session token

Housekeeping:
  doctor                prereqs, ports, health
  uninstall             stop + optional data wipe + msiexec /x
  purge [-y]            stop + WIPE data dir (+ reinstall from WYRDSEKAI_MSI)

Not yet on Windows (use a Linux/macOS node or the REST API on :$RestPort):
  daemon  relay-server  rendezvous  web  reseed  embed-migrate  embedding-model
  verify-release  residency  connect  phone  bond  issue  voice  reset  nuke

Data dir : $DataDir
Config   : $ConfFile
Locale   : $($script:WyrdLocale) (WYRDSEKAI_LOCALE / WYRDSEKAI_LANG; catalogs: scripts\i18n\wyrd_<locale>.json)
"@
}

# ── Household join ( — parity with bin/wyrd `do_join`) ───
# `wyrd join <host[:port]> --household-key <key>` auto-adds THIS node to a hub's
# home zone via the hub's pre-shared household key. Forwarded verbatim to
# RelayNkeyAdminMain so the crypto / HTTP / JDBC enrollment path is shared.
function Invoke-Join {
    Write-Info "Joining household..."
    $rc = Invoke-WyrdJavaClass -Class "org.wyrdsekai.server.RelayNkeyAdminMain" `
        -JavaArgs (@("household-join") + $Rest)
    if ($rc -ne 0) { Write-Err2 "household join failed (exit $rc)." }
}

# `wyrd household key` prints the hub's active household key (generating one if
# none), via the local REST surface — the key a peer hands to `wyrd join`.
function Invoke-Household {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    switch ($sub) {
        "key" {
            $base = "http://127.0.0.1:$RestPort"
            $resp = $null
            try {
                $resp = Invoke-RestMethod -Uri "$base/api/pair/household-key" -TimeoutSec 5
            } catch {
                try {
                    $resp = Invoke-RestMethod -Method Post `
                        -Uri "$base/api/pair/household-key/generate" -TimeoutSec 5
                } catch {
                    Write-Err2 "Could not reach the local server on $base — is the zone running?"
                    return
                }
            }
            if ($resp -and $resp.key) { Write-Host $resp.key }
            else { Write-Err2 "No household key in server response." }
        }
        "join" { $script:Rest = $Rest[1..($Rest.Count-1)]; Invoke-Join }
        default {
            Write-Host "Usage:"
            Write-Host "  wyrd household key"
            Write-Host "       Print this hub's active household key (generates one if none)."
            Write-Host "  wyrd join <host[:port]> --household-key <key>"
            Write-Host "       Auto-add this node to the hub's home zone (run on the joining node)."
        }
    }
}

# ── W9 parity commands (ported from bin/wyrd — see each do_* there) ──────────

function Invoke-Log {
    if (Test-Path $LogFile) { Get-Content $LogFile -Tail 50 -Wait }
    else { Write-Err2 (_T 'log.no_log_at' $LogFile) }
}

function Invoke-Backup {
    # Consistency note: copying live libSQL files while the server writes can
    # capture a mid-transaction state. Warn (don't block) — a slightly-stale
    # backup beats no backup. (Parity with bin/wyrd do_backup.)
    if (Get-ServerPid) { Write-Warn2 (_T 'backup.live_warning') }
    $backupDir = Join-Path $DataDir "backups"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $ts = Get-Date -Format "yyyyMMdd-HHmmss"
    $backupPath = Join-Path $backupDir "wyrdsekai-$ts"
    New-Item -ItemType Directory -Force -Path $backupPath | Out-Null
    Write-Host (_T 'backup.creating' $backupPath)

    Get-ChildItem -Path $DataDir -Filter *.db -File -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName -Destination $backupPath -Force
        Write-Host "  $($_.Name)"
    }
    if (Test-Path (Join-Path $DataDir "jetstream")) {
        Copy-Item (Join-Path $DataDir "jetstream") -Destination (Join-Path $backupPath "jetstream") -Recurse -Force
        Write-Host "  jetstream/"
    }
    foreach ($f in @("node-identity.json", "nats.conf")) {
        $p = Join-Path $DataDir $f
        if (Test-Path $p) { Copy-Item $p -Destination $backupPath -Force }
    }
    foreach ($d in @("search", "lucene", "souls")) {
        $p = Join-Path $DataDir $d
        if (Test-Path $p) {
            Copy-Item $p -Destination (Join-Path $backupPath $d) -Recurse -Force
            Write-Host "  $d/"
        }
    }
    foreach ($f in @("contacts", "my-zones", "blocks")) {
        $p = Join-Path $DataDir $f
        if (Test-Path $p) { Copy-Item $p -Destination $backupPath -Force; Write-Host "  $f" }
    }
    if (Test-Path $ConfFile) {
        Copy-Item $ConfFile -Destination (Join-Path $backupPath "wyrdsekai.conf") -Force
        Write-Host "  wyrdsekai.conf"
    }

    $sumObj = Get-ChildItem $backupPath -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum
    $sum = if ($sumObj -and $sumObj.Sum) { $sumObj.Sum } else { 0 }
    $size = "{0:N1} MB" -f ($sum / 1MB)
    Write-Host ""
    Write-Host (_T 'backup.complete' $backupPath $size)

    # Prune old backups (keep 5 — parity with bin/wyrd)
    Get-ChildItem $backupDir -Directory -Filter "wyrdsekai-*" -ErrorAction SilentlyContinue |
        Sort-Object CreationTime -Descending | Select-Object -Skip 5 | ForEach-Object {
            Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host (_T 'backup.pruned' $_.Name)
        }
}

function Invoke-Restore {
    $name = if ($Rest.Count -ge 1) { $Rest[0] } else { $null }
    $backupsRoot = Join-Path $DataDir "backups"
    if (-not $name) {
        Write-Host (_T 'restore.available_header')
        Get-ChildItem $backupsRoot -Directory -Filter "wyrdsekai-*" -ErrorAction SilentlyContinue |
            Sort-Object CreationTime -Descending | ForEach-Object {
                $sumObj = Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum
                $sum = if ($sumObj -and $sumObj.Sum) { $sumObj.Sum } else { 0 }
                Write-Host ("  {0}  ({1:N1} MB)" -f $_.Name, ($sum / 1MB))
            }
        Write-Host ""
        Write-Host (_T 'restore.usage')
        Write-Host (_T 'restore.example')
        exit 1
    }
    $backupPath = $name
    if (-not (Test-Path $backupPath)) { $backupPath = Join-Path $backupsRoot $name }
    if (-not (Test-Path $backupPath)) { Write-Err2 (_T 'restore.not_found' $backupPath); exit 1 }

    Write-Host (_T 'restore.from' $backupPath)
    Write-Host (_T 'restore.warning')
    Write-Host ""
    $confirm = Read-Host (_T 'restore.prompt_continue')
    if ($confirm -ne 'y') { Write-Host (_T 'restore.cancelled'); exit 1 }

    Get-ChildItem -Path $backupPath -Filter *.db -File -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName -Destination $DataDir -Force
        Write-Host "  $($_.Name)"
    }
    foreach ($f in @("node-identity.json", "contacts", "my-zones", "blocks")) {
        $p = Join-Path $backupPath $f
        if (Test-Path $p) { Copy-Item $p -Destination $DataDir -Force }
    }
    foreach ($d in @("jetstream", "search", "lucene", "souls")) {
        $src = Join-Path $backupPath $d
        if (Test-Path $src) {
            $dst = Join-Path $DataDir $d
            if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
            Copy-Item $src -Destination $dst -Recurse -Force
        }
    }
    $bc = Join-Path $backupPath "wyrdsekai.conf"
    if (Test-Path $bc) {
        Copy-Item $bc -Destination $ConfFile -Force
        Write-Host (_T 'restore.config_path' $ConfFile)
    }
    Write-Host ""
    Write-Host (_T 'restore.complete')
}

function Invoke-Recover {
    $rkey = if ($Rest.Count -ge 1) { $Rest[0] } else { $null }
    $newPass = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
    if (-not $rkey -or -not $newPass) {
        Write-Host (_T 'recover.usage')
        Write-Host ""
        Write-Host (_T 'recover.help_1')
        Write-Host (_T 'recover.help_2')
        exit 1
    }
    Write-Host (_T 'recover.resetting')
    try {
        $body = @{ recoveryKey = $rkey; newPassword = $newPass } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Method Post -Uri "$(Get-ApiBase)/api/auth/recover" -ContentType 'application/json' -Body $body -TimeoutSec 15
        Write-Host (Get-JProp $resp 'message' (Get-JProp $resp 'error' "$resp"))
    } catch {
        Write-Err2 "recover failed: $_"
        exit 1
    }
}

function Invoke-ResetZone {
    $rkey = if ($Rest.Count -ge 1) { $Rest[0] } else { $null }
    if (-not $rkey) {
        Write-Host (_T 'reset_zone.usage')
        Write-Host ""
        Write-Host (_T 'reset_zone.help_1')
        Write-Host (_T 'reset_zone.help_2')
        Write-Host (_T 'reset_zone.help_3')
        exit 1
    }
    Write-Host (_T 'reset_zone.warning')
    Write-Host (_T 'reset_zone.restart_needed')
    $confirm = Read-Host "Continue? (y/N)"
    if ($confirm -ne 'y') { Write-Host "Cancelled."; exit 1 }
    try {
        $body = @{ recoveryKey = $rkey } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Method Post -Uri "$(Get-ApiBase)/api/auth/reset-zone" -ContentType 'application/json' -Body $body -TimeoutSec 15
        Write-Host (Get-JProp $resp 'message' (Get-JProp $resp 'error' "$resp"))
    } catch { Write-Err2 "reset-zone failed: $_"; exit 1 }
}

function Invoke-Update {
    # Source-checkout dev layout (this script two levels below the repo root):
    # git pull + rebuild, mirroring bin/wyrd source mode. Installed .msi node:
    # updates ship as a new installer (which snapshots DBs before upgrading).
    $repoRoot = Join-Path $AppDir "..\.."
    if ((Test-Path (Join-Path $repoRoot ".git")) -and (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Info (_T 'update.pulling')
        Push-Location $repoRoot
        try {
            & git pull --rebase
            if ($LASTEXITCODE -ne 0) { Write-Warn2 (_T 'update.git_failed'); exit 1 }
            Write-Info (_T 'update.rebuilding')
            & .\gradlew.bat :server:installDist
            if ($LASTEXITCODE -ne 0) { Write-Err2 "Build failed"; exit 1 }
            Write-Info (_T 'update.done')
        } finally { Pop-Location }
    } else {
        Write-Warn2 (_T 'update.source_only')
        Write-Host "  Windows: download and run the new .msi - the installer snapshots your databases before upgrading."
    }
}

function Invoke-Version {
    # F14: --mesh fans out version queries to every federated peer.
    if ($Rest.Count -ge 1 -and $Rest[0] -eq '--mesh') {
        try { $d = Invoke-RestMethod -Uri "$(Get-ApiBase)/api/version/mesh" -TimeoutSec 15 }
        catch { Write-Err2 (_T 'version.mesh_failed'); exit 1 }
        $local = Get-JProp $d 'local'
        Write-Host ("Local zone:  {0} build={1} ({2}, schema={3})" -f `
            (Get-JProp $local 'zoneId' '?'), (Get-JProp $local 'buildHash' '?'), `
            (Get-JProp $local 'appVersion' '?'), (Get-JProp $local 'federationSchema' '?'))
        Write-Host ""
        $peers = @(Get-JProp $d 'peers' @())
        if ($peers.Count -eq 0) { Write-Host "  No federation peers known."; return }
        Write-Host ("  {0,-24} {1,-14} {2,-12} {3,-8} status" -f 'partner','version','buildHash','schema')
        Write-Host ("  " + ("-" * 68))
        $localSchema = "$(Get-JProp $local 'federationSchema' '')"
        $localHash = "$(Get-JProp $local 'buildHash' '')"
        $drift = 0
        foreach ($p in $peers) {
            $bv = Get-JProp $p 'buildVersion'
            $schema = "$(Get-JProp $bv 'federationSchema' '?')"
            $hash   = "$(Get-JProp $bv 'buildHash' '?')"
            $appv   = "$(Get-JProp $bv 'appVersion' '?')"
            $status = if ($schema -eq '?' -or $hash -eq '?') { "? unknown (pre-F14 peer)" }
                      elseif ($schema -ne $localSchema) { $drift++; "x schema mismatch (peer=$schema)" }
                      elseif ($hash -ne $localHash) { $drift++; "! build drift" }
                      else { "+ in sync" }
            Write-Host ("  {0,-24} {1,-14} {2,-12} {3,-8} {4}" -f (Get-JProp $p 'zoneId' '?'), $appv, $hash, $schema, $status)
        }
        Write-Host ""
        if ($drift -gt 0) {
            Write-Host "  $drift peer(s) drifting from local build $localHash."
            Write-Host "  ! Rebuild & redeploy out-of-sync nodes from the same git ref before live testing."
        } else {
            Write-Host "  All peers in sync with local build."
        }
        return
    }
    # Local-only version — read wyrdsekai-version.properties baked into
    # common-*.jar at build time (F14: reflect the INSTALLED build, not a
    # script literal), falling back to the staged VERSION file.
    $verText = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $jar = Get-ChildItem (Join-Path $AppDir "common-*.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($jar) {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                $entry = $zip.GetEntry("wyrdsekai-version.properties")
                if ($entry) {
                    $sr = New-Object System.IO.StreamReader($entry.Open())
                    try { $verText = $sr.ReadToEnd().Trim() } finally { $sr.Dispose() }
                }
            } finally { $zip.Dispose() }
        }
    } catch { $verText = $null }
    if ($verText) {
        Write-Host ("wyrd " + (($verText -split "`r?`n") -join " | "))
    } else {
        $vf = Join-Path $AppDir "VERSION"
        if (Test-Path $vf) { Write-Host ("wyrd v" + (Get-Content $vf -Raw).Trim()) }
        else { Write-Host (_T 'version.fallback') }
    }
    $mode = if (Test-Path (Join-Path $AppDir "..\..\.git")) { "source" } else { "package (msi)" }
    Write-Host (_T 'version.mode' $mode)
    Write-Host (_T 'version.install' $InstallDir)
    Write-Host (_T 'version.data' $DataDir)
    $java = Resolve-WyrdJava
    if ($java) {
        $prevEAP = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $jv = (& $java -version 2>&1 | Select-Object -First 1)
        $ErrorActionPreference = $prevEAP
        Write-Host (_T 'version.java' "$jv")
    } else { Write-Host (_T 'version.java_missing') }
}

# ── wyrd model — model-material lifecycle (parity with bin/wyrd do_model) ─────
function Invoke-Model {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "status" }
    $arg = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
    $idxPath = Get-ModelsIndexPath
    if (-not $idxPath) { Write-Err2 "models-index.json not found (app dir, install dir, or repo root)"; exit 1 }
    $idx = Get-ModelsIndex
    if (-not $idx) { Write-Err2 "models-index.json unreadable: $idxPath"; exit 1 }
    $manifestFile = Join-Path $ModelsDir "models-manifest.jsonl"

    switch ($sub) {
        "status" {
            Write-Host "Model status (index: $idxPath)"
            $manifest = Get-ManifestEntries
            foreach ($m in @($idx.models)) {
                $mid = [string](Get-JProp $m 'id' '?')
                $lf  = [string](Get-JProp $m 'local_file')
                $url = [string](Get-JProp $m 'url')
                $ver = [string](Get-JProp $m 'version' '?')
                if (-not $url) {
                    Write-Host ("  {0,-24} {1,-28} (bundled/informational)" -f $mid, $ver)
                    continue
                }
                $path = Join-Path $ModelsDir $lf
                if (-not (Test-Path $path)) {
                    Write-Host ("  {0,-24} {1,-28} NOT INSTALLED" -f $mid, $ver)
                    continue
                }
                $rec = $manifest[(Split-Path $lf -Leaf)]
                if ($null -eq $rec) {
                    Write-Host ("  {0,-24} {1,-28} present, UNVERIFIED (run: wyrd model verify)" -f $mid, $ver)
                    continue
                }
                $recVer = [string](Get-JProp $rec 'version' '?')
                $idxSha = [string](Get-JProp $m 'sha256')
                $recSha = [string](Get-JProp $rec 'sha256')
                if (($recVer -eq $ver) -and ((-not $idxSha) -or ($recSha -eq $idxSha))) {
                    Write-Host ("  {0,-24} {1,-28} up to date" -f $mid, $recVer)
                } else {
                    Write-Host ("  {0,-24} local={1} index={2}  UPDATE AVAILABLE (wyrd model update {0})" -f $mid, $recVer, $ver)
                }
            }
        }
        "verify" {
            Write-Host "Verifying local model files against the index (hashing - may take a minute)..."
            foreach ($m in @($idx.models)) {
                $mid = [string](Get-JProp $m 'id' '?')
                $lf  = [string](Get-JProp $m 'local_file')
                $url = [string](Get-JProp $m 'url')
                if (-not $url -or -not $lf) { continue }
                $path = Join-Path $ModelsDir $lf
                if (-not (Test-Path $path)) { continue }
                $digest = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
                $known = [string](Get-JProp $m 'sha256')
                $ver = if ((-not $known) -or ($digest -eq $known.ToLower())) { [string](Get-JProp $m 'version' '') } else { "unknown" }
                $status = if ($known -and $digest -eq $known.ToLower()) { "matches index" }
                          elseif (-not $known) { "recorded (index sha unknown)" }
                          else { "DOES NOT MATCH index sha - version unknown" }
                Write-Host ("  {0,-24} {1}" -f $mid, $status)
                # Append the honest verification record (source_url=verify — parity with bin/wyrd).
                try {
                    $ts = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
                    $rec = '{"v":1,"file":"' + (Split-Path $lf -Leaf) + '","id":"' + $mid + '","version":"' + $ver +
                           '","sha256":"' + $digest + '","source_url":"verify","recorded_at":"' + $ts + '"}'
                    Add-Content -Path $manifestFile -Value $rec
                } catch { }
            }
            Write-Host "Manifest updated: $manifestFile"
        }
        "update" {
            if (-not $arg) { Write-Err2 "usage: wyrd model update <id>   (see: wyrd model status)"; exit 2 }
            $m = Get-IndexModel -Id $arg
            $url = [string](Get-JProp $m 'url')
            if (-not $url) { Write-Err2 "model '$arg' not in index or has no download url"; exit 1 }
            $ver = [string](Get-JProp $m 'version' '?')
            $lf  = [string](Get-JProp $m 'local_file')
            $sha = [string](Get-JProp $m 'sha256')
            New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null
            $dest = Join-Path $ModelsDir $lf
            Write-Info "Updating $arg -> $ver"
            if (-not (Invoke-RobustDownload -Url $url -Dest "$dest.part")) {
                Remove-Item "$dest.part" -ErrorAction SilentlyContinue
                Write-Err2 "download failed"; exit 1
            }
            if ($sha) {
                $got = (Get-FileHash "$dest.part" -Algorithm SHA256).Hash.ToLower()
                if ($got -ne $sha.ToLower()) {
                    Write-Err2 "sha256 mismatch (got $got, expected $sha) - NOT installing"
                    Remove-Item "$dest.part" -ErrorAction SilentlyContinue
                    exit 1
                }
                Write-Info "sha256 verified"
            }
            if (Test-Path $dest) {
                Move-Item -Force $dest "$dest.prev"
                Write-Info "previous kept at $(Split-Path $dest -Leaf).prev (wyrd model rollback $arg)"
            }
            Move-Item -Force "$dest.part" $dest
            Add-ModelManifestRecord -File $dest -Id $arg -Version $ver -SourceUrl $url
            Add-ModelHistory -Id $arg -Action "update" -Detail "-> $ver"
            Write-Info "$arg updated to $ver. Restart inference to load it: wyrd restart"
        }
        "rollback" {
            if (-not $arg) { Write-Err2 "usage: wyrd model rollback <id>"; exit 2 }
            $m = Get-IndexModel -Id $arg
            $lf = [string](Get-JProp $m 'local_file')
            if (-not $lf) { Write-Err2 "model '$arg' not in index"; exit 1 }
            $dest = Join-Path $ModelsDir $lf
            if (-not (Test-Path "$dest.prev")) { Write-Err2 "no previous version kept for $arg"; exit 1 }
            if (Test-Path $dest) { Move-Item -Force $dest "$dest.rolledback" }
            Move-Item -Force "$dest.prev" $dest
            if (Test-Path "$dest.rolledback") { Move-Item -Force "$dest.rolledback" "$dest.prev" }
            # Re-identify the restored file honestly: index version if the hash matches, else unknown.
            $rbsha = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
            $idxSha = [string](Get-JProp $m 'sha256')
            $rbver = if ($idxSha -and $rbsha -eq $idxSha.ToLower()) { [string](Get-JProp $m 'version' 'unknown') } else { "unknown" }
            Add-ModelManifestRecord -File $dest -Id $arg -Version $rbver -SourceUrl "rollback"
            Add-ModelHistory -Id $arg -Action "rollback" -Detail "swapped .prev back"
            Write-Info "$arg rolled back. Restart inference: wyrd restart"
        }
        "check" {
            Write-Host "Inference health:"
            Write-Host ("  :{0} (drive) {1}" -f $DrivePort, $(if (Test-LlamaHealth -Port $DrivePort) { "OK" } else { "DOWN" }))
            Write-Host ("  :{0} (voice) {1}" -f $VoicePort, $(if (Test-LlamaHealth -Port $VoicePort) { "OK" } else { "DOWN" }))
            try {
                $body = '{"messages":[{"role":"user","content":"Reply with exactly: OK"}],"max_tokens":8,"temperature":0}'
                $r = Invoke-RestMethod -Method Post -Uri "http://localhost:$DrivePort/v1/chat/completions" -ContentType 'application/json' -Body $body -TimeoutSec 30
                $reply = ([string]$r.choices[0].message.content).Trim()
                Write-Host "  completion probe: '$reply'"
            } catch { Write-Host "  completion probe: FAILED" }
            if (Test-Path $manifestFile) {
                Write-Host "Recent manifest entries:"
                Get-Content $manifestFile -Tail 3 | ForEach-Object { Write-Host "  $_" }
            }
        }
        "history" {
            $hf = Join-Path $DataDir "model-history.jsonl"
            if (Test-Path $hf) { Get-Content $hf | ForEach-Object { Write-Host "  $_" } }
            else { Write-Host "  (no model changes recorded)" }
        }
        default {
            Write-Host "usage: wyrd model [status|verify|update <id>|rollback <id>|check|history]"
        }
    }
}

# ── Naming admin (whoami/contacts/zones/block/unblock/blocks/safety) ──────────
# Shells into NamingAdminMain on the server classpath — DID derivation needs
# base58 + multicodec encoding we don't duplicate in PowerShell. Output flows
# straight through; exit code preserved. (Parity with bin/wyrd do_naming_admin.)
function Invoke-NamingAdmin {
    param([string]$Sub)
    $javaArgs = @($Sub) + @($Rest)
    $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.server.NamingAdminMain" -JavaArgs $javaArgs
    if ($rc -ne 0) { exit $rc }
}

function Invoke-State {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    if ($sub -eq "dump") {
        $dumpArgs = if ($Rest.Count -gt 1) { @($Rest[1..($Rest.Count-1)]) } else { @() }
        $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.core.state.StateDumpMain" -JavaArgs $dumpArgs
        if ($rc -ne 0) { exit $rc }
    } else {
        Write-Host (_T 'state.help.title')
        Write-Host ""
        Write-Host (_T 'state.help.subcommands_header')
        Write-Host (_T 'state.help.cmd_dump')
        Write-Host (_T 'state.help.cmd_dump_default')
        Write-Host (_T 'state.help.cmd_dump_summary')
        Write-Host (_T 'state.help.cmd_dump_out')
        Write-Host ""
        Write-Host (_T 'state.help.walks_1')
        Write-Host (_T 'state.help.walks_2')
        Write-Host (_T 'state.help.walks_3')
    }
}

function Invoke-InviteCmd {
    $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.server.InviteAdminMain" -JavaArgs @($Rest)
    if ($rc -ne 0) { exit $rc }
}

function Invoke-KeyCmd {
    $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.server.KeyAdminMain" -JavaArgs @($Rest)
    if ($rc -ne 0) { exit $rc }
}

function Write-JournalEntries {
    param($Entries)
    if ($null -eq $Entries) { return }
    foreach ($e in @($Entries)) {
        if ($null -eq $e) { continue }
        $meta = Get-JProp $e 'metadata'
        $ts = [long](Get-JProp $meta 'timestamp' 0)
        $when = if ($ts) { [DateTimeOffset]::FromUnixTimeMilliseconds($ts).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss") } else { "?" }
        Write-Host "  $when"
        $content = [string](Get-JProp $e 'content' '')
        foreach ($line in (($content -split "`n") | Select-Object -First 6)) { Write-Host "    $line" }
        Write-Host ""
    }
}

function Invoke-Journal {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    $api = Get-ApiBase
    switch ($sub) {
        "list" {
            $user = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $user) { Write-Err2 (_T 'journal.usage_list'); exit 1 }
            $tag = if ($Rest.Count -ge 3) { $Rest[2] } else { $null }
            $limit = if ($Rest.Count -ge 4) { $Rest[3] } else { 20 }
            $url = "$api/api/familiar/journal?user=$([uri]::EscapeDataString($user))&limit=$limit"
            if ($tag) { $url += "&tag=$([uri]::EscapeDataString($tag))" }
            try { $d = Invoke-RestMethod -Uri $url -TimeoutSec 20 }
            catch { Write-Err2 (_T 'journal.list_failed' $api); exit 1 }
            $tagSuffix = if (Get-JProp $d 'tag') { " [tag=$(Get-JProp $d 'tag')]" } else { "" }
            Write-Host "$(Get-JProp $d 'count' 0) entries for $(Get-JProp $d 'user' $user)$tagSuffix"
            Write-Host ""
            Write-JournalEntries (Get-JProp $d 'entries')
        }
        "search" {
            $user = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            $query = if ($Rest.Count -ge 3) { $Rest[2] } else { $null }
            if (-not $user -or -not $query) { Write-Err2 (_T 'journal.usage_search'); exit 1 }
            $limit = if ($Rest.Count -ge 4) { $Rest[3] } else { 20 }
            $url = "$api/api/familiar/journal/search?user=$([uri]::EscapeDataString($user))&q=$([uri]::EscapeDataString($query))&limit=$limit"
            try { $d = Invoke-RestMethod -Uri $url -TimeoutSec 30 }
            catch { Write-Err2 (_T 'journal.search_failed' $api); exit 1 }
            Write-Host "$(Get-JProp $d 'count' 0) matches for '$(Get-JProp $d 'query' $query)' (user $(Get-JProp $d 'user' $user))"
            Write-Host ""
            Write-JournalEntries (Get-JProp $d 'entries')
        }
        "tags" {
            Write-Host (_T 'journal.tags.title')
            foreach ($k in @('familiar_shaped','familiar_revised','familiar_retired','familiar_unretired',
                             'familiar_summoned','familiar_returned','familiar_stuck','familiar_cancelled',
                             'bunshin_dispatch','bunshin_return','imprint_created','imprint_restored')) {
                Write-Host (_T "journal.tags.$k")
            }
            Write-Host ""
            Write-Host (_T 'journal.tags.example')
        }
        { $_ -in @("help","-h","--help") } {
            Write-Host (_T 'journal.help.usage_header')
            Write-Host (_T 'journal.help.cmd_list')
            Write-Host (_T 'journal.help.cmd_search')
            Write-Host (_T 'journal.help.cmd_tags')
            Write-Host ""
            Write-Host (_T 'journal.help.env_header')
            Write-Host (_T 'journal.help.env_api_url')
        }
        default {
            Write-Err2 (_T 'journal.unknown_subcommand' $sub)
            Write-Err2 (_T 'journal.try_help')
            exit 1
        }
    }
}

function Invoke-Recipes {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    $api = Get-ApiBase
    switch ($sub) {
        "list" {
            try { $d = Invoke-RestMethod -Uri "$api/api/recipes" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
            $n = [int](Get-JProp $d 'count' 0)
            if ($n -eq 0) { Write-Host (_T 'recipes.list.empty'); return }
            Write-Host (_T 'recipes.list.header' "$n")
            foreach ($r in @(Get-JProp $d 'rows' @())) {
                $en = if (Get-JProp $r 'enabled' $false) { "on" } else { "off" }
                Write-Host (_T 'recipes.list.row' "$(Get-JProp $r 'recipeId' '?')" $en "$(Get-JProp $r 'cadenceTier' '?')" "$(Get-JProp $r 'queueDepth' 0)" "$(Get-JProp $r 'lastStatus' '-')")
            }
        }
        "status" {
            $name = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $name) { Write-Err2 (_T 'recipes.usage.status'); exit 1 }
            try { $d = Invoke-RestMethod -Uri "$api/api/recipes/$name" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
            $enrolls = @(Get-JProp $d 'enrollments' @())
            $runs = @(Get-JProp $d 'runs' @())
            if ($enrolls.Count -eq 0 -and $runs.Count -eq 0) { Write-Host (_T 'recipes.status.not_found' $name); return }
            Write-Host (_T 'recipes.status.header' "$(Get-JProp $d 'recipeId' $name)")
            Write-Host (_T 'recipes.status.enrollments_header' "$($enrolls.Count)")
            foreach ($e in $enrolls) {
                $gaps = @(Get-JProp $e 'gapKeys' @()) -join ','
                if (-not $gaps) { $gaps = '-' }
                $en = if (Get-JProp $e 'enabled' $false) { "on" } else { "off" }
                Write-Host (_T 'recipes.status.enrollment_row' "$(Get-JProp $e 'agentDid' '*')" $en "$(Get-JProp $e 'cadenceTier' '?')" "$(Get-JProp $e 'consecutiveSuccesses' 0)" $gaps)
            }
            Write-Host ""
            Write-Host (_T 'recipes.status.runs_header' "$($runs.Count)")
            foreach ($r in ($runs | Select-Object -First 10)) {
                $ts = Get-JProp $r 'completedAt' (Get-JProp $r 'attemptedAt' (Get-JProp $r 'enqueuedAt' '?'))
                Write-Host (_T 'recipes.status.run_row' "$ts" "$(Get-JProp $r 'status' '?')" "$(Get-JProp $r 'triggerSource' '?')" "$(Get-JProp $r 'triggerReason' '-')")
            }
        }
        "log" {
            $name = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $name) { Write-Err2 (_T 'recipes.usage.log'); exit 1 }
            $limit = 20
            for ($i = 2; $i -lt $Rest.Count; $i++) {
                if ($Rest[$i] -eq "--limit" -and ($i + 1) -lt $Rest.Count) { $limit = $Rest[$i+1]; $i++ }
            }
            try { $d = Invoke-RestMethod -Uri "$api/api/recipes/$name/log?limit=$limit" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
            $n = [int](Get-JProp $d 'count' 0)
            if ($n -eq 0) { Write-Host (_T 'recipes.log.empty' $name); return }
            Write-Host (_T 'recipes.log.header' $name "$n")
            foreach ($r in @(Get-JProp $d 'rows' @())) {
                Write-Host (_T 'recipes.log.row' "$(Get-JProp $r 'completedAt' '?')" "$(Get-JProp $r 'status' '?')" "$(Get-JProp $r 'triggerSource' '?')" "$(Get-JProp $r 'agentDid' '*')")
            }
        }
        { $_ -in @("pause","resume") } {
            $verb = $_
            $name = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            $did = if ($Rest.Count -ge 3) { $Rest[2] } else { $null }
            if (-not $name) { Write-Err2 (_T "recipes.usage.$verb"); exit 1 }
            $url = "$api/api/recipes/$name/$verb"
            if ($did) { $url += "?agentDid=$([uri]::EscapeDataString($did))" }
            try { $d = Invoke-RestMethod -Method Post -Uri $url -TimeoutSec 20 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
            $expected = if ($verb -eq "pause") { "paused" } else { "resumed" }
            $tpl = if ((Get-JProp $d 'status') -eq $expected) { "recipes.$verb.ok" } else { "recipes.$verb.not_found" }
            Write-Host (_T $tpl "$(Get-JProp $d 'recipeId' $name)" "$(Get-JProp $d 'agentDid' '*')")
        }
        "run" {
            $name = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $name) { Write-Err2 (_T 'recipes.usage.run'); exit 1 }
            $entity = ""; $paramsKv = ""
            for ($i = 2; $i -lt $Rest.Count; $i++) {
                switch ($Rest[$i]) {
                    "--entity" { if (($i + 1) -lt $Rest.Count) { $entity = $Rest[$i+1]; $i++ } }
                    "--params" { if (($i + 1) -lt $Rest.Count) { $paramsKv = $Rest[$i+1]; $i++ } }
                }
            }
            if (-not $entity) {
                # Auto-pick when exactly one companion is enrolled in this recipe;
                # otherwise the operator must pass --entity to disambiguate.
                try {
                    $probe = Invoke-RestMethod -Uri "$api/api/recipes/$name" -TimeoutSec 10
                    $dids = @(@(Get-JProp $probe 'enrollments' @()) | ForEach-Object { Get-JProp $_ 'agentDid' } | Where-Object { $_ } | Select-Object -Unique)
                    if ($dids.Count -eq 1) { $entity = $dids[0] }
                } catch { }
                if (-not $entity) { Write-Err2 (_T 'recipes.run.no_entity'); exit 1 }
                Write-Info "auto-selected sole enrolled companion: $entity"
            }
            $params = @{}
            if ($paramsKv) {
                foreach ($tok in ($paramsKv -split ',')) {
                    if ($tok -match '=') {
                        $kv = $tok -split '=', 2
                        $params[$kv[0].Trim()] = $kv[1].Trim()
                    }
                }
            }
            # W16 — production route, steward-token gated (wyrd login first).
            $token = Get-SessionToken
            if (-not $token) { Write-Err2 (_T 'recipes.run.needs_token'); exit 1 }
            $body = @{ entityId = $entity; recipe = $name; reason = "wyrd recipes run (steward override)"; params = $params } | ConvertTo-Json -Compress
            try {
                Invoke-RestMethod -Method Post -Uri "$api/api/recipes/run" -ContentType 'application/json' `
                    -Headers @{ Authorization = "Bearer $token" } -Body $body -TimeoutSec 60 | Out-Null
            } catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
            Write-Host (_T 'recipes.run.dispatched' $name $entity)
        }
        "bondholder-eligibility" {
            $map = @{ "--bondholder" = "bondholder"; "--agent" = "agent"; "--min-corpus-pairs" = "min-corpus-pairs";
                      "--min-bond-age-days" = "min-bond-age-days"; "--min-distinct-sessions" = "min-distinct-sessions";
                      "--required-bond-state" = "required-bond-state"; "--substrate-pressure-threshold" = "substrate-pressure-threshold";
                      "--min-new-turns" = "min-new-turns" }
            $q = ""
            for ($i = 1; $i -lt $Rest.Count; $i++) {
                if ($map.ContainsKey($Rest[$i]) -and ($i + 1) -lt $Rest.Count) {
                    $q += "&$($map[$Rest[$i]])=$([uri]::EscapeDataString($Rest[$i+1]))"; $i++
                }
            }
            try { Invoke-RestMethod -Method Post -Uri "$api/api/recipes/bondholder/eligibility?$($q.TrimStart('&'))" -Headers (Get-AdminHeaders) -TimeoutSec 60 | ConvertTo-Json -Depth 6 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
        }
        "bondholder-pairs" {
            $map = @{ "--bondholder" = "bondholder"; "--agent" = "agent"; "--output" = "output"; "--max-pairs" = "max-pairs" }
            $q = ""
            for ($i = 1; $i -lt $Rest.Count; $i++) {
                if ($map.ContainsKey($Rest[$i]) -and ($i + 1) -lt $Rest.Count) {
                    $q += "&$($map[$Rest[$i]])=$([uri]::EscapeDataString($Rest[$i+1]))"; $i++
                }
            }
            try { Invoke-RestMethod -Method Post -Uri "$api/api/recipes/bondholder/pairs?$($q.TrimStart('&'))" -Headers (Get-AdminHeaders) -TimeoutSec 300 | ConvertTo-Json -Depth 6 }
            catch { Write-Err2 (_T 'recipes.request_failed' $api); exit 1 }
        }
        { $_ -in @("help","-h","--help") } {
            Write-Host (_T 'recipes.help.usage_header')
            Write-Host (_T 'recipes.help.cmd_list')
            Write-Host (_T 'recipes.help.cmd_status')
            Write-Host (_T 'recipes.help.cmd_log')
            Write-Host (_T 'recipes.help.cmd_pause')
            Write-Host (_T 'recipes.help.cmd_resume')
            Write-Host (_T 'recipes.help.cmd_run')
            Write-Host "  bondholder-eligibility --bondholder <did> --agent <did>  [#1035]"
            Write-Host "  bondholder-pairs --bondholder <did> --agent <did> --output <path>  [#1035]"
            Write-Host ""
            Write-Host (_T 'recipes.help.env_header')
            Write-Host (_T 'recipes.help.env_api_url')
        }
        default {
            Write-Err2 (_T 'recipes.unknown_subcommand' $sub)
            Write-Err2 (_T 'recipes.try_help')
            exit 1
        }
    }
}

function Invoke-Library {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    $api = Get-ApiBase
    $subArgs = if ($Rest.Count -gt 1) { @($Rest[1..($Rest.Count-1)]) } else { @() }
    switch ($sub) {
        "install" {
            $pack = if ($subArgs.Count -ge 1 -and $subArgs[0] -notlike '--*') { $subArgs[0] } else { $null }
            $url = $null
            for ($i = 0; $i -lt $subArgs.Count; $i++) {
                switch ($subArgs[$i]) {
                    "--from-dir" { if (($i + 1) -lt $subArgs.Count) { $url = "file:///" + ((Resolve-Path $subArgs[$i+1]).Path -replace '\\','/'); $i++ } }
                    "--url"      { if (($i + 1) -lt $subArgs.Count) { $url = $subArgs[$i+1]; $i++ } }
                }
            }
            if (-not $pack) { Write-Host "Usage: wyrd library install <pack-name> [--from-dir <path> | --url <url>]"; exit 1 }
            $payload = if ($url) { @{ pack = $pack; url = $url } } else { @{ pack = $pack } }
            try {
                $resp = Invoke-RestMethod -Method Post -Uri "$api/api/library/install" -ContentType 'application/json' -Body ($payload | ConvertTo-Json -Compress) -TimeoutSec 120
                Write-Host ($resp | ConvertTo-Json -Depth 6 -Compress)
            } catch { Write-Err2 "library install failed - is the node running at $api?"; exit 1 }
        }
        "ingest" {
            $dir = if ($subArgs.Count -ge 1 -and $subArgs[0] -notlike '--*') { $subArgs[0] } else { $null }
            $collection = ""
            $user = if ($env:WYRDSEKAI_USER) { $env:WYRDSEKAI_USER } else { $env:USERNAME }
            $ingestMode = "auto"
            for ($i = 1; $i -lt $subArgs.Count; $i++) {
                switch ($subArgs[$i]) {
                    "--collection" { if (($i + 1) -lt $subArgs.Count) { $collection = $subArgs[$i+1]; $i++ } }
                    "--user"       { if (($i + 1) -lt $subArgs.Count) { $user = $subArgs[$i+1]; $i++ } }
                    "--catalog"    { $ingestMode = "catalog" }
                    "--full-text"  { $ingestMode = "full" }
                }
            }
            if (-not $dir -or -not (Test-Path $dir -PathType Container)) {
                Write-Host "Usage: wyrd library ingest <directory> [--collection <name>] [--user <name>] [--catalog | --full-text]"
                Write-Host "  Indexes documents under <directory> (recursive) into the Study library:"
                Write-Host "  epub, pdf, docx, pptx, md, txt, csv, json, ... Calibre libraries default"
                Write-Host "  to --catalog (instant card catalog from metadata.db); --full-text extracts"
                Write-Host "  everything (resumable)."
                if ($dir) { Write-Err2 "not a directory: $dir" }
                exit 1
            }
            $absDir = (Resolve-Path $dir).Path
            if (-not $collection) { $collection = Split-Path $absDir -Leaf }
            $payload = @{ user = $user; path = $absDir; collection = $collection; mode = $ingestMode } | ConvertTo-Json -Compress
            try {
                $resp = Invoke-RestMethod -Method Post -Uri "$api/api/study/add" -ContentType 'application/json' -Body $payload -TimeoutSec 60
                Write-Host ($resp | ConvertTo-Json -Depth 6 -Compress)
            } catch { Write-Err2 "library ingest failed - is the node running at $api?"; exit 1 }
            Write-Info "Ingest accepted - indexing runs in the background (collection: $collection)."
            Write-Info "Check progress with: wyrd library status"
        }
        "available" {
            try { Invoke-RestMethod -Uri "$api/api/library/available" -TimeoutSec 20 | ConvertTo-Json -Depth 6 }
            catch { Write-Err2 "library request failed - is the node running at $api?"; exit 1 }
        }
        "status" {
            try { Invoke-RestMethod -Uri "$api/api/library/status" -TimeoutSec 20 | ConvertTo-Json -Depth 6 }
            catch { Write-Err2 "library request failed - is the node running at $api?"; exit 1 }
        }
        "compact" {
            $action = if ($subArgs.Count -ge 1) { $subArgs[0] } else { "help" }
            $collection = $null; $probes = $null
            for ($i = 1; $i -lt $subArgs.Count; $i++) {
                switch ($subArgs[$i]) {
                    "--collection" { if (($i + 1) -lt $subArgs.Count) { $collection = $subArgs[$i+1]; $i++ } }
                    "--probes"     { if (($i + 1) -lt $subArgs.Count) { $probes = $subArgs[$i+1]; $i++ } }
                }
            }
            $q = if ($collection) { "?collection=$([uri]::EscapeDataString($collection))" } else { "" }
            switch ($action) {
                { $_ -in @("snapshot","merge","prune","reembed") } {
                    try { Invoke-RestMethod -Method Post -Uri "$api/api/library/compact/$action$q" -Headers (Get-AdminHeaders) -TimeoutSec 300 | ConvertTo-Json -Depth 6 }
                    catch { Write-Err2 "library compact $action failed"; exit 1 }
                }
                "probe" {
                    if (-not $probes) { Write-Err2 "library compact probe needs --probes <path>"; exit 1 }
                    $sep = if ($q) { "&" } else { "?" }
                    try { Invoke-RestMethod -Method Post -Uri "$api/api/library/compact/probe$q${sep}probes_file=$([uri]::EscapeDataString($probes))" -Headers (Get-AdminHeaders) -TimeoutSec 300 | ConvertTo-Json -Depth 6 }
                    catch { Write-Err2 "library compact probe failed"; exit 1 }
                }
                default { Write-Host "Usage: wyrd library compact <snapshot|merge|prune|reembed|probe> --collection <name> [--probes <jsonl-path>]" }
            }
        }
        "freshness" {
            $action = if ($subArgs.Count -ge 1) { $subArgs[0] } else { "help" }
            switch ($action) {
                "enumerate" {
                    $limit = 500
                    for ($i = 1; $i -lt $subArgs.Count; $i++) {
                        if ($subArgs[$i] -eq "--limit" -and ($i + 1) -lt $subArgs.Count) { $limit = $subArgs[$i+1]; $i++ }
                    }
                    try { Invoke-RestMethod -Method Post -Uri "$api/api/library/freshness/enumerate?limit=$limit" -Headers (Get-AdminHeaders) -TimeoutSec 300 | ConvertTo-Json -Depth 6 }
                    catch { Write-Err2 "library freshness enumerate failed"; exit 1 }
                }
                "prune-ids" {
                    $ids = ""; $idsFile = ""
                    for ($i = 1; $i -lt $subArgs.Count; $i++) {
                        switch ($subArgs[$i]) {
                            "--ids"      { if (($i + 1) -lt $subArgs.Count) { $ids = $subArgs[$i+1]; $i++ } }
                            "--ids-file" { if (($i + 1) -lt $subArgs.Count) { $idsFile = $subArgs[$i+1]; $i++ } }
                        }
                    }
                    $idList = @()
                    if ($ids) { $idList += ($ids -split ',') }
                    if ($idsFile -and (Test-Path $idsFile)) { $idList += (Get-Content $idsFile) }
                    $idList = @($idList | ForEach-Object { ([string]$_).Trim().Trim('"') } | Where-Object { $_ })
                    if ($idList.Count -eq 0) { Write-Err2 "library freshness prune-ids needs --ids a,b,c or --ids-file <path>"; exit 1 }
                    $body = @{ ids = $idList } | ConvertTo-Json -Compress
                    try { Invoke-RestMethod -Method Post -Uri "$api/api/library/freshness/prune-ids" -ContentType 'application/json' -Headers (Get-AdminHeaders) -Body $body -TimeoutSec 300 | ConvertTo-Json -Depth 6 }
                    catch { Write-Err2 "library freshness prune-ids failed"; exit 1 }
                }
                default {
                    Write-Host "Usage: wyrd library freshness <enumerate|prune-ids>"
                    Write-Host "       enumerate [--limit N]"
                    Write-Host "       prune-ids --ids a,b,c | --ids-file <path>"
                }
            }
        }
        "bundle" {
            # Air-gap pack pre-downloader — pure Java CLI on the lib\cli classpath
            # (staged into the .msi by build-msi.ps1 as part of W9 full-peer).
            $rc = Invoke-WyrdJavaClassStream -Class "org.wyrdsekai.core.library.LibraryBundleCli" -JavaArgs $subArgs -LibSubdir "cli"
            if ($rc -ne 0) { exit $rc }
        }
        default {
            Write-Host "Usage: wyrd library <subcommand>"
            Write-Host "  install <pack-name> [--from-dir <path> | --url <url>]"
            Write-Host "  ingest <dir> [--collection <name>] [--user <name>] [--catalog | --full-text]"
            Write-Host "  available                       (registry packs)"
            Write-Host "  status                          (installed packs + chunk counts)"
            Write-Host "  bundle [--dest <dir>] [--packs <csv> | --langs <csv>]   (air-gap pre-download)"
            Write-Host "  compact <snapshot|merge|prune|reembed|probe> ..."
            Write-Host "  freshness <enumerate|prune-ids> ..."
        }
    }
}

function Invoke-Federate {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    $api = Get-ApiBase
    switch ($sub) {
        "propose" {
            $zone = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $zone) { Write-Err2 (_T 'federate.propose.usage'); exit 1 }
            Write-Info (_T 'federate.propose.proposing' $zone)
            try { $d = Invoke-RestMethod -Method Post -Uri "$api/api/federation/propose/$zone" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'federate.propose.failed' $api); exit 1 }
            Write-Host "  $(Get-JProp $d 'result' '(no result)')"
        }
        "accept" {
            $zone = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $zone) { Write-Err2 (_T 'federate.accept.usage'); exit 1 }
            Write-Info (_T 'federate.accept.accepting' $zone)
            try { $d = Invoke-RestMethod -Method Post -Uri "$api/api/federation/accept/$zone" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'federate.accept.failed'); exit 1 }
            Write-Host "  $(Get-JProp $d 'result' '(no result)')"
        }
        "revoke" {
            $zone = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
            if (-not $zone) { Write-Err2 (_T 'federate.revoke.usage'); exit 1 }
            Write-Info (_T 'federate.revoke.revoking' $zone)
            try { $d = Invoke-RestMethod -Method Post -Uri "$api/api/federation/revoke/$zone" -TimeoutSec 20 }
            catch { Write-Err2 (_T 'federate.revoke.failed'); exit 1 }
            Write-Host "  $(Get-JProp $d 'result' '(no result)')"
        }
        "status" {
            if ($Rest.Count -ge 2 -and $Rest[1] -eq '--mesh') {
                # F12: both-sides consensus matrix.
                try { $d = Invoke-RestMethod -Uri "$api/api/federation/mesh-status" -TimeoutSec 12 }
                catch { Write-Err2 (_T 'federate.status.mesh_failed'); exit 1 }
                if (-not $d) {
                    Write-Host "  Mesh status unavailable - the zone returned an empty response."
                    Write-Host "  Use 'wyrd federate status' for the single-zone view."
                    return
                }
                $entries = @(Get-JProp $d 'entries' @())
                Write-Host ("Federation mesh status (local zone: {0}, probed at {1})" -f (Get-JProp $d 'localZone' '?'), (Get-JProp $d 'probedAt' '?'))
                Write-Host ""
                if ($entries.Count -eq 0) { Write-Host "  No bilateral agreements recorded."; return }
                $sym = @{ agree = "+"; mismatch = "x"; unreachable = "?" }
                Write-Host ("  {0,-24} {1,-10} {2,-14} consensus" -f 'partner','local view','partner view')
                Write-Host ("  " + ("-" * 60))
                foreach ($e in $entries) {
                    $cons = [string](Get-JProp $e 'consensus' '?')
                    $s = if ($sym.ContainsKey($cons)) { $sym[$cons] } else { "." }
                    Write-Host ("  {0,-24} {1,-10} {2,-14} {3} {4}" -f (Get-JProp $e 'partnerZoneId' '?'), (Get-JProp $e 'localStatus' '?'), (Get-JProp $e 'partnerStatus' '?'), $s, $cons)
                }
                Write-Host ""
                $agree = Get-JProp $d 'agreeCount' 0
                $mismatch = Get-JProp $d 'mismatchCount' 0
                $unreach = Get-JProp $d 'unreachableCount' 0
                Write-Host "  $($entries.Count) partner(s); + $agree, x $mismatch, ? $unreach"
                if ([int]$mismatch -gt 0) {
                    Write-Host ""
                    Write-Host "  ! mismatch means the two sides disagree about the agreement state."
                    Write-Host "    Try: wyrd federate propose <partner>   (re-handshake; F6 reconciles)"
                }
                if ([int]$unreach -gt 0) {
                    Write-Host "  ! unreachable means the partner didn't reply within 3s."
                    Write-Host "    Check: relay is up; partner is reachable; partner runs F6+ code."
                }
                return
            }
            try { $d = Invoke-RestMethod -Uri "$api/api/federation/status" -TimeoutSec 12 }
            catch { Write-Err2 (_T 'federate.status.failed'); exit 1 }
            Write-Host (Get-JProp $d 'status' '(no status)')
        }
        "list" {
            try { $d = Invoke-RestMethod -Uri "$api/api/federation/agreements" -TimeoutSec 12 }
            catch { Write-Err2 (_T 'federate.list.failed'); exit 1 }
            $agreements = @(Get-JProp $d 'agreements' @())
            if ($agreements.Count -eq 0) { Write-Host "  (no agreements)"; return }
            Write-Host "  $($agreements.Count) agreement(s) for zone '$(Get-JProp $d 'localZone' '?')':"
            foreach ($a in $agreements) {
                $remote = Get-JProp $a 'remoteZone' (Get-JProp $a 'remoteZoneId' '?')
                Write-Host ("    - {0,-20} {1,-10} {2}" -f $remote, (Get-JProp $a 'status' '?'), (Get-JProp $a 'trustLevel' ''))
            }
        }
        "code" {
            # Steward-side: show the pending pair code (a new node is waiting).
            try { $d = Invoke-RestMethod -Uri "$api/api/pair/code" -TimeoutSec 10 }
            catch { Write-Err2 (_T 'federate.code.none_pending'); exit 1 }
            Write-Host ("  code: {0}    expires-at: {1}" -f (Get-JProp $d 'code' '?'), (Get-JProp $d 'expiresAt' '?'))
        }
        "household-key" {
            $action = if ($Rest.Count -ge 2) { $Rest[1] } else { "show" }
            switch ($action) {
                { $_ -in @("show","get") } {
                    try { $d = Invoke-RestMethod -Uri "$api/api/pair/household-key" -TimeoutSec 10 }
                    catch { Write-Err2 (_T 'federate.household_key.no_active'); exit 1 }
                    Write-Host "  key: $(Get-JProp $d 'key' '?')"
                    Write-Host "  created: $(Get-JProp $d 'createdAt' '?')"
                }
                { $_ -in @("generate","new") } {
                    try { $d = Invoke-RestMethod -Method Post -Uri "$api/api/pair/household-key/generate" -TimeoutSec 10 }
                    catch { Write-Err2 (_T 'federate.household_key.generate_failed'); exit 1 }
                    Write-Host "  new key: $(Get-JProp $d 'key' '?')"
                    Write-Host "  Share with new nodes via: wyrd federate join --request <host> --household-key <key>"
                }
                default { Write-Err2 (_T 'federate.household_key.usage'); exit 1 }
            }
        }
        "join" {
            # --lan discover | --request HOST[:PORT] [--name N] [--household-key K]
            # | direct --relay-url URL --user HH --token TOKEN. The request flow
            # rides the same /api/pair/* endpoints phone clients use.
            $joinMode = ""; $rurl = ""; $ruser = ""; $rtoken = ""; $reqHost = ""; $reqName = ""; $hhKey = ""
            for ($i = 1; $i -lt $Rest.Count; $i++) {
                switch ($Rest[$i]) {
                    "--lan"           { $joinMode = "lan" }
                    "--request"       { $joinMode = "request"; if (($i + 1) -lt $Rest.Count) { $reqHost = $Rest[$i+1]; $i++ } }
                    "--name"          { if (($i + 1) -lt $Rest.Count) { $reqName = $Rest[$i+1]; $i++ } }
                    "--household-key" { if (($i + 1) -lt $Rest.Count) { $hhKey = $Rest[$i+1]; $i++ } }
                    "--relay-url"     { if (($i + 1) -lt $Rest.Count) { $rurl = $Rest[$i+1]; $i++ } }
                    "--user"          { if (($i + 1) -lt $Rest.Count) { $ruser = $Rest[$i+1]; $i++ } }
                    "--token"         { if (($i + 1) -lt $Rest.Count) { $rtoken = $Rest[$i+1]; $i++ } }
                    default           { Write-Err2 (_T 'federate.join.unknown_arg' $Rest[$i]); exit 1 }
                }
            }
            if ($joinMode -eq "lan") {
                Write-Info (_T 'federate.join.lan.browsing')
                Invoke-Discover
                Write-Host ""
                Write-Info (_T 'federate.join.lan.hint_header')
                Write-Host (_T 'federate.join.lan.hint_request_code')
                Write-Host (_T 'federate.join.lan.hint_request_key')
                return
            }
            if ($joinMode -eq "request") {
                if (-not $reqHost) { Write-Err2 (_T 'federate.join.request.needs_host'); exit 1 }
                $pairHost = $reqHost
                if ($pairHost -notmatch ':') { $pairHost = "${pairHost}:7070" }
                $pairUrl = "http://$pairHost"
                $nm = if ($reqName) { $reqName } else { $env:COMPUTERNAME.ToLower() }
                $result = $null
                if ($hhKey) {
                    Write-Info (_T 'federate.join.request.with_key' $reqHost)
                    $body = @{ deviceName = $nm; deviceType = "node"; key = $hhKey } | ConvertTo-Json -Compress
                    try { $result = Invoke-RestMethod -Method Post -Uri "$pairUrl/api/pair/key" -ContentType 'application/json' -Body $body -TimeoutSec 20 }
                    catch { Write-Err2 (_T 'federate.join.request.key_failed'); exit 1 }
                } else {
                    Write-Info (_T 'federate.join.request.requesting_code' $reqHost)
                    $body = @{ deviceName = $nm; deviceType = "node" } | ConvertTo-Json -Compress
                    try { $reqResp = Invoke-RestMethod -Method Post -Uri "$pairUrl/api/pair/request" -ContentType 'application/json' -Body $body -TimeoutSec 20 }
                    catch { Write-Err2 (_T 'federate.join.request.host_unreachable'); exit 1 }
                    $cid = [string](Get-JProp $reqResp 'challengeId')
                    if (-not $cid) { Write-Err2 (_T 'federate.join.request.no_challenge' ($reqResp | ConvertTo-Json -Compress -Depth 4)); exit 1 }
                    Write-Info (_T 'federate.join.request.challenge_issued')
                    Write-Info (_T 'federate.join.request.ask_admin')
                    $code = Read-Host (_T 'federate.join.request.code_prompt')
                    if (-not $code) { Write-Err2 (_T 'federate.join.request.no_code'); exit 1 }
                    $body = @{ challengeId = $cid; code = $code } | ConvertTo-Json -Compress
                    try { $result = Invoke-RestMethod -Method Post -Uri "$pairUrl/api/pair/verify" -ContentType 'application/json' -Body $body -TimeoutSec 20 }
                    catch { Write-Err2 (_T 'federate.join.request.verify_failed'); exit 1 }
                }
                $rurl = [string](Get-JProp $result 'relayUrl' '')
                $ruser = [string](Get-JProp $result 'householdId' '')
                $rtoken = [string](Get-JProp $result 'relayToken' (Get-JProp $result 'token' ''))
                if (-not $rurl -or -not $rtoken) {
                    Write-Err2 (_T 'federate.join.request.no_creds' ($result | ConvertTo-Json -Compress -Depth 4))
                    exit 1
                }
                Write-Info (_T 'federate.join.paired')
                # fall through to the direct-config profile write below
            }
            if (-not $rurl -or -not $ruser -or -not $rtoken) {
                Write-Err2 (_T 'federate.join.usage_lan')
                Write-Err2 (_T 'federate.join.usage_direct')
                exit 1
            }
            # Replace-or-append the [relay] section in profile.toml (idempotent —
            # re-running join with new creds updates in place).
            New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
            $profilePath = Join-Path $DataDir "profile.toml"
            $content = ""
            if (Test-Path $profilePath) {
                $raw = Get-Content $profilePath -Raw -ErrorAction SilentlyContinue
                if ($raw) { $content = $raw }
            }
            $content = [regex]::Replace($content, '(?ms)^#?\s*\[relay\].*?(?=^\[|\Z)', '')
            $relayBlock = "[relay]`nurl = `"$rurl`"`nuser = `"$ruser`"`ntoken = `"$rtoken`""
            Set-Content -Path $profilePath -Value ($content.TrimEnd() + "`n`n" + $relayBlock + "`n") -Encoding ASCII
            Write-Info (_T 'federate.join.wrote_relay' $profilePath)
            Write-Info (_T 'federate.join.restart_hint')
        }
        default {
            Write-Host (_T 'federate.help.usage')
            Write-Host ""
            Write-Host (_T 'federate.help.tagline')
            Write-Host ""
            Write-Host (_T 'federate.help.commands_label')
            Write-Host (_T 'federate.help.cmd_join_lan')
            Write-Host (_T 'federate.help.cmd_join_request_code')
            Write-Host (_T 'federate.help.cmd_join_request_key')
            Write-Host (_T 'federate.help.cmd_join_direct')
            Write-Host (_T 'federate.help.cmd_code')
            Write-Host (_T 'federate.help.cmd_household_key')
            Write-Host (_T 'federate.help.cmd_propose')
            Write-Host (_T 'federate.help.cmd_accept')
            Write-Host (_T 'federate.help.cmd_revoke')
            Write-Host (_T 'federate.help.cmd_status')
            Write-Host (_T 'federate.help.cmd_list')
            Write-Host ""
            Write-Host (_T 'federate.help.env_label')
            Write-Host (_T 'federate.help.env_api_url')
            Write-Host ""
            Write-Host (_T 'federate.help.normal_flow')
        }
    }
}

function Invoke-ZoneCmd {
    $sub = if ($Rest.Count -ge 1) { $Rest[0] } else { "help" }
    $target = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
    $api = Get-ApiBase
    switch ($sub) {
        "federate" {
            if (-not $target) { Write-Host (_T 'zone.usage_federate'); exit 1 }
            Write-Info (_T 'zone.proposing' $target)
            try {
                $d = Invoke-RestMethod -Method Post -Uri "$api/api/federation/propose/$target" -TimeoutSec 20
                Write-Host (Get-JProp $d 'result' 'unknown')
            } catch { Write-Host (_T 'zone.failed_between') }
        }
        "accept" {
            if (-not $target) { Write-Host (_T 'zone.usage_accept'); exit 1 }
            Write-Info (_T 'zone.accepting' $target)
            try {
                $d = Invoke-RestMethod -Method Post -Uri "$api/api/federation/accept/$target" -TimeoutSec 20
                Write-Host (Get-JProp $d 'result' 'unknown')
            } catch { Write-Host (_T 'zone.failed_between') }
        }
        "revoke" {
            if (-not $target) { Write-Host (_T 'zone.usage_revoke'); exit 1 }
            Write-Info (_T 'zone.revoking' $target)
            try { Invoke-RestMethod -Method Post -Uri "$api/api/federation/revoke/$target" -TimeoutSec 20 | Out-Null }
            catch { Write-Host (_T 'zone.failed_between') }
        }
        "status" {
            try {
                $d = Invoke-RestMethod -Uri "$api/api/federation/status" -TimeoutSec 12
                Write-Host (Get-JProp $d 'status' 'unknown')
            } catch { Write-Host (_T 'zone.status_unavailable') }
        }
        default {
            Write-Host (_T 'zone.help_title')
            Write-Host ""
            Write-Host (_T 'zone.help_commands')
            Write-Host (_T 'zone.help_federate')
            Write-Host (_T 'zone.help_accept')
            Write-Host (_T 'zone.help_revoke')
            Write-Host (_T 'zone.help_status')
        }
    }
}

function Invoke-Login {
    $user = if ($Rest.Count -ge 1) { $Rest[0] } else { $null }
    $pass = if ($Rest.Count -ge 2) { $Rest[1] } else { $null }
    if (-not $user) { $user = Read-Host (_T 'login.username_prompt') }
    if (-not $pass) {
        $sec = Read-Host (_T 'login.password_prompt') -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
        try { $pass = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }
    if (-not $user -or -not $pass) { Write-Err2 (_T 'login.username_password_required'); exit 1 }
    $body = @{ username = $user; password = $pass } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$(Get-ApiBase)/api/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 15
    } catch { Write-Err2 (_T 'login.failed'); exit 1 }
    $tok = [string](Get-JProp $resp 'token' '')
    $role = [string](Get-JProp $resp 'role' '')
    if (-not $tok) { Write-Err2 (_T 'login.no_token_in_response' ($resp | ConvertTo-Json -Compress -Depth 4)); exit 1 }
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
    $f = Join-Path $DataDir "session.token"
    Set-Content -Path $f -Value $tok -Encoding ASCII -NoNewline
    Write-Info (_T 'login.success' $user $role)
    Write-Host (_T 'login.token_stored_at' $f)
    Write-Host (_T 'login.hint_logout')
}

function Invoke-Logout {
    $f = Join-Path $DataDir "session.token"
    if (Test-Path $f) {
        Remove-Item $f -Force
        Write-Info (_T 'logout.cleared' $f)
    } else {
        Write-Info (_T 'logout.no_session')
    }
}

function Invoke-Uninstall {
    Write-Warn2 (_T 'uninstall.warning')
    $removeData = Read-Host (_T 'uninstall.prompt_remove_data')

    # Best-effort relay deregister first (parity with bin/wyrd) — NEVER blocks
    # the uninstall if the relay is unreachable.
    $conf = Get-Conf
    if ($conf.Contains('WYRDSEKAI_RELAY_URL') -or ($conf.Contains('WYRDSEKAI_RELAY_ENABLED') -and $conf['WYRDSEKAI_RELAY_ENABLED'] -eq 'true')) {
        Write-Info "Deregistering from relay before uninstall (best-effort)..."
        try { Invoke-WyrdJavaClass -Class "org.wyrdsekai.server.RelayNkeyAdminMain" -JavaArgs @("deregister") | Out-Null }
        catch { Write-Warn2 "Relay deregister skipped (unreachable or not configured) - continuing uninstall." }
    }

    Invoke-Stop

    if ($removeData -match '^[Yy]') {
        Remove-Item $DataDir -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path $DataDir) { Write-Warn2 (_T 'uninstall.wipe_incomplete' $DataDir) }
        else { Write-Info (_T 'uninstall.data_removed' $DataDir) }
    }

    # Remove the login-autostart Run key, then hand the payload removal to the
    # MSI engine (the .msi's uninstall entry is the single source of truth for
    # what was installed). Needs elevation; msiexec triggers UAC itself.
    Remove-ItemProperty -Path "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "Wyrdsekai" -ErrorAction SilentlyContinue
    $product = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue |
        Where-Object { (Get-JProp $_ 'DisplayName') -eq 'Wyrdsekai' } | Select-Object -First 1
    if ($product) {
        Write-Info "Launching MSI uninstall ($($product.PSChildName))..."
        Start-Process msiexec.exe -ArgumentList @("/x", $product.PSChildName, "/qb") -Wait
    } else {
        Write-Warn2 "MSI product entry not found - remove 'Wyrdsekai' via Settings > Apps (or msiexec /x <msi>)."
    }
    Write-Info (_T 'uninstall.done')
}

function Invoke-Purge {
    # Windows analog of the .deb nuke-and-pave: stop everything, wipe the data
    # dir, then reinstall from WYRDSEKAI_MSI if set. Non-interactive with -y.
    $yes = ($Rest -contains '-y') -or ($Rest -contains '--yes')
    if (-not $yes) {
        Write-Warn2 "This will STOP the node and WIPE $DataDir (and reinstall if WYRDSEKAI_MSI points at an .msi)."
        $ans = Read-Host (_T 'purge.prompt_proceed')
        if ($ans -notmatch '^[Yy]') { Write-Host "Cancelled."; return }
    }
    Invoke-Stop
    Remove-Item $DataDir -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path $DataDir) { Write-Warn2 "Some files under $DataDir could not be removed (still in use?) - close processes and retry." }
    else { Write-Info "Data dir wiped: $DataDir" }
    $msi = $env:WYRDSEKAI_MSI
    if ($msi -and (Test-Path $msi)) {
        Write-Info "Reinstalling from $msi..."
        Start-Process msiexec.exe -ArgumentList @("/i", "`"$msi`"", "/qb") -Wait
        Write-Info "Reinstalled. Next: wyrd setup, then wyrd start"
    } else {
        Write-Info "State wiped. Reinstall by running the .msi again (or set WYRDSEKAI_MSI=<path> before 'wyrd purge')."
    }
}

# Commands that are Linux/macOS-only by nature (systemd/docker/launchd, or not
# yet ported). Honest stubs: say so, point at the working alternative, exit 3.
$script:WindowsStubCommands = @(
    "daemon","relay-server","rendezvous","web","reseed","embed-migrate",
    "embedding-model","verify-release","residency","connect","phone","bond",
    "issue","voice","reset","nuke"
)

function Invoke-WindowsStub {
    param([string]$Name)
    Write-Warn2 "'wyrd $Name' is not yet available on Windows - use a Linux/macOS node (or the REST API on :$RestPort)."
    if ($Name -in @("reset","nuke")) {
        Write-Host "  Closest Windows equivalents: 'wyrd stop' / 'wyrd purge' (wipe data dir) / 'wyrd uninstall'."
    }
    exit 3
}

# ── Dispatch ──────────────────────────────────────────────────────────────────
switch ($Command.ToLower()) {
    "setup"     { Invoke-Setup }
    "start"     { Invoke-Start }
    "stop"      { Invoke-Stop }
    "restart"   { Invoke-Stop; Start-Sleep -Seconds 1; Invoke-Start }
    "status"    { Invoke-Status }
    "log"       { Invoke-Log }
    "config"    { Invoke-Config }
    "cred"      { Invoke-Cred | Out-Null }
    "inference" { Invoke-Inference }
    "model"     { Invoke-Model }
    "relay"     { Invoke-Relay }
    "join"      { Invoke-Join }
    "household" { Invoke-Household }
    "discover"  { Invoke-Discover }
    "oracle"    { Invoke-Oracle }
    "coding"    { Invoke-Coding }
    "search"    { Invoke-Search }
    "doctor"    { Invoke-Doctor }
    "backup"    { Invoke-Backup }
    "restore"   { Invoke-Restore }
    "recover"   { Invoke-Recover }
    "reset-zone" { Invoke-ResetZone }
    "update"    { Invoke-Update }
    "version"   { Invoke-Version }
    "state"     { Invoke-State }
    "invite"    { Invoke-InviteCmd }
    "key"       { Invoke-KeyCmd }
    "journal"   { Invoke-Journal }
    "recipes"   { Invoke-Recipes }
    "library"   { Invoke-Library }
    "federate"  { Invoke-Federate }
    "zone"      { Invoke-ZoneCmd }
    "zones"     { Invoke-NamingAdmin -Sub "zones" }
    "whoami"    { Invoke-NamingAdmin -Sub "whoami" }
    "contacts"  { Invoke-NamingAdmin -Sub "contacts" }
    "block"     { Invoke-NamingAdmin -Sub "block" }
    "unblock"   { Invoke-NamingAdmin -Sub "unblock" }
    "blocks"    { Invoke-NamingAdmin -Sub "blocks" }
    "safety"    { Invoke-NamingAdmin -Sub "safety" }
    "login"     { Invoke-Login }
    "logout"    { Invoke-Logout }
    "uninstall" { Invoke-Uninstall }
    "purge"     { Invoke-Purge }
    "help"      { Invoke-Help }
    "--help"    { Invoke-Help }
    "-h"        { Invoke-Help }
    default     {
        if ($script:WindowsStubCommands -contains $Command.ToLower()) {
            Invoke-WindowsStub -Name $Command.ToLower()
        } else {
            Write-Err2 "unknown command: $Command"; Invoke-Help; exit 2
        }
    }
}
