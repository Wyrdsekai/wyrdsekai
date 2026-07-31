# Wyrdsekai Windows MSI Installer Builder
# Requires: Java 25+ (jpackage), WiX Toolset 3.x
# Usage: .\build-msi.ps1 [-Version "0.2.0"] [-InputDir "server/build/install/server"]
#
# FULL STANDALONE NODE: the .msi ships the complete payload (jars + rooms/ +
# scripts/ + classifier/embedding resources + data/vectors + nats-server.exe +
# oracle wheel), so an installed node is a peer of the .deb/.pkg — see the
# staging block below. nats-server.exe is bundled (fetch-nats-server.ps1) and
# lands at app\nats-server.exe where NatsServerManager probes it; the Between
# cross-node bridge works out of the box (no manual nats install).
#
# A node launched straight from Wyrdsekai.exe (Start-menu shortcut / double-click)
# carries no WYRDSEKAI_HOME env, so it relies on WyrdConfig.installRoot()
# self-locating the install tree from the running jar (jpackage lays all jars
# flat in app\, siblings of rooms\/scripts\). That is what makes room scripts
# (docks.js federation transit) and std scripts resolve without the wyrd.ps1
# wrapper. `wyrd start` additionally sets WYRDSEKAI_HOME=app\ explicitly.

param(
    [string]$Version = $env:WYRDSEKAI_VERSION,
    [string]$InputDir = "",
    [string]$OutputDir = "build/win",
    [switch]$WithService
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "  ============================================"
Write-Host "       Wyrdsekai Windows MSI Builder"
Write-Host "  ============================================"
Write-Host ""

# --- Version ---
if (-not $Version) {
    $Version = "0.1.2"
    Write-Host "[info] No version specified, using default: $Version"
}
Write-Host "[info] Building Wyrdsekai v$Version"

# --- Java check ---
# `java -version` writes to stderr; under $ErrorActionPreference='Stop' (set
# above) PowerShell 5.1 promotes that stderr to a terminating NativeCommandError
# even with 2>&1. Relax EAP just for this native call so the version parse works.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
$ErrorActionPreference = $prevEAP
if ([int]$javaVersion -lt 25) {
    Write-Error "Java 25+ required for jpackage (found: $javaVersion)"
    exit 1
}
Write-Host "[ok] Java $javaVersion"

# --- jpackage check ---
$jpackagePath = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackagePath) {
    Write-Error "jpackage not found. Ensure JDK 21+ is installed (not just JRE)."
    exit 1
}
Write-Host "[ok] jpackage found"

# --- WiX check ---
$wixPath = Get-Command candle.exe -ErrorAction SilentlyContinue
if (-not $wixPath) {
    # Check common WiX install locations
    $wixLocations = @(
        "$env:WIX\bin",
        "C:\Program Files (x86)\WiX Toolset v3.14\bin",
        "C:\Program Files (x86)\WiX Toolset v3.11\bin"
    )
    foreach ($loc in $wixLocations) {
        if (Test-Path "$loc\candle.exe") {
            $env:PATH += ";$loc"
            Write-Host "[ok] WiX found at $loc"
            break
        }
    }
}
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    Write-Warning "WiX Toolset not found. jpackage may fall back to exe installer."
}

# --- Build jars from source if needed ---
$libDir = if ($InputDir -and (Test-Path $InputDir)) { $InputDir } else { "" }
if (-not $libDir) {
    # ── Build assets MUST be present before gradle compiles core ────────────
    # The embedding/classifier ONNX files live in core\src\main\resources and are
    # embedded INTO the core jar by gradle, so fetching them afterwards is too
    # late. They are large weights, correctly absent from a public checkout.
    #
    # Run the fetcher's TEXT as a scriptblock rather than invoking the .ps1 file:
    # execution policy gates script FILES, and this script already runs under
    # $ErrorActionPreference='Stop', so a blocked nested call would kill the whole
    # build (the same trap that bites fetch-nats-server.ps1 and build-tray.ps1).
    $fetcher = "packaging\windows\fetch-build-assets.ps1"
    if (Test-Path $fetcher) {
        Write-Host "[info] Verifying build assets (models, tokenizers, voice vectors)..."
        try {
            $fsb = [scriptblock]::Create((Get-Content $fetcher -Raw))
            & $fsb
        } catch {
            Write-Error "[fatal] build assets could not be fetched: $($_.Exception.Message)"
            Write-Error "        Refusing to build an MSI with a degraded classifier and a voice"
            Write-Error "        missing its steering vectors."
            exit 1
        }
    } else {
        Write-Warning "fetch-build-assets.ps1 not found — assets must already be staged"
    }

    Write-Host "[info] Building from source..."
    # gradle/javac emit "Note: Some input files use or override a deprecated API"
    # to stderr on a clean recompile; under $ErrorActionPreference='Stop' (set
    # above) PowerShell 5.1 promotes that stderr to a terminating NativeCommandError
    # and aborts mid-build (at :scripting:compileJava). Relax EAP just for the
    # gradle call — same workaround as the `java -version` check above.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # Full-peer claim (W9): build the sibling module dists too, mirroring
    # packaging/build-dist.sh, so lib\cli / lib\daemon-desktop / lib\wyrd-rendezvous
    # can be staged below. With -InputDir (windows-node flow) this branch is skipped and
    # the module-lib staging below picks up whatever install dirs already exist.
    & ./gradlew :server:installDist :cli:installDist :clients:daemon-desktop:installDist :rendezvous:installDist --no-daemon
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP
    if ($gradleExit -ne 0) {
        Write-Error "Gradle build failed"
        exit 1
    }
    $libDir = "server/build/install/server/lib"
}

# --- Assemble a FULL standalone payload (parity with build-dist.sh) ---
# Windows is a first-class node: the .msi must ship the same complete tree the
# .deb/.pkg do — jars PLUS room scripts, recipe-callable script subdirs, core
# source-relative classifier/model resources, i18n, and data/ (coding-cli
# manifest, release-evidence, vectors). jpackage --input copies this whole tree
# into %ProgramFiles%\Wyrdsekai\app\, and `wyrd.ps1` sets WYRDSEKAI_HOME to that
# dir so the daemon resolves the source-relative paths recipes shell out to.
# (Classifier + embedding ONNX themselves ride inside core-*.jar as resources;
# the on-disk copies here are what the recipe Python loads by source path.)
$Stage = Join-Path $OutputDir "stage"
if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

# jars at the staging root (jpackage --main-jar resolves here)
Copy-Item -Path (Join-Path $libDir "*.jar") -Destination $Stage -Force
# Release model index (data-durability, 2026-07-09)
if (Test-Path "models-index.json") { Copy-Item -Path "models-index.json" -Destination (Join-Path $Stage "models-index.json") -Force }

function Stage-Tree($src, $dstRel) {
    if (Test-Path $src) {
        $dst = Join-Path $Stage $dstRel
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Copy-Item -Path (Join-Path $src "*") -Destination $dst -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "[ok] payload: $dstRel"
    }
}
function Stage-File($src, $dstRel) {
    if (Test-Path $src) {
        $dst = Join-Path $Stage $dstRel
        New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
        Copy-Item -Path $src -Destination $dst -Force
    }
}

# Room scripts
Stage-Tree "scripts\rooms" "rooms"
# Config catalog — `wyrd config list --all` reads it (parity with bin/wyrd).
Stage-File "scripts\config-catalog.json" "scripts\config-catalog.json"
# core source-relative resources recipes load by path (classifier seeds/anchors + embedding ONNX)
Stage-Tree "core\src\main\resources\classifier" "core\src\main\resources\classifier"
Stage-Tree "core\src\main\resources\models"     "core\src\main\resources\models"
# recipe-callable script subdirs (each recipe shells out to scripts/<sub>/*.py)
# Kept in step with the same list in build-dist.sh — membership is decided by
# the `# recipe-callable: local-ok` header, not by habit. soul/recipe/oracle/
# corpus were missing (the companion is told those helpers exist); behavior/
# steering-vectors/persona-*/test were research trees nothing resolves.
foreach ($sub in @("classifier","i18n","memory","voice","library","soul","recipe",
                   "oracle","corpus","items","policy","mcp","std","setup","lib")) {
    Stage-Tree "scripts\$sub" "scripts\$sub"
}
# These helpers under scripts\lib are BUILD-time only — nothing at runtime shells
# out to them, and they carry configuration written for one developer's
# environment, which a released package should not.
#
# This tree is staged from SOURCE, independently of build-dist.sh, so the prune
# there does not reach here. Each staging path needs its own; they inherit
# nothing from one another. Hard-fail rather than a silent Remove-Item — a prune
# that matches nothing looks exactly like one that worked.
$ossTooling = @("oss_redact.py","oss_scan.py","oss_spec_index.py",
                "oss_opsec_scan.py","dist_redact.py")
$stagedLib = Join-Path $Stage "scripts\lib"
foreach ($t in $ossTooling) {
    Remove-Item (Join-Path $stagedLib $t) -Force -ErrorAction SilentlyContinue
}
# __pycache__ holds the COMPILED form, and a .pyc keeps every string literal the
# source had — pruning only the .py leaves the operator's relay domain and
# username in oss_redact.cpython-*.pyc. Bytecode has no business shipping anyway.
Get-ChildItem (Join-Path $Stage "scripts") -Recurse -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
foreach ($t in $ossTooling) {
    if (Test-Path (Join-Path $stagedLib $t)) {
        Write-Error "build-time tooling survived staging: scripts\lib\$t"; exit 1
    }
}
$leak = Get-ChildItem $stagedLib -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { (Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue) -match 'example-relay|you@' }
if ($leak) {
    Write-Error ("operator identifiers remain in staged scripts\lib: " + ($leak.Name -join ', ')); exit 1
}
# Out of the ~5MB training tree, only the parts recipes drive: rebake-argot
# uses scripts\training\argot, run-emit-rft uses scripts\training\emit_rft,
# and VoiceAligner resolves mlx_adapter_to_peft.py. The bench stays home.
Stage-File "scripts\training\mlx_adapter_to_peft.py" "scripts\training\mlx_adapter_to_peft.py"
Stage-Tree "scripts\training\argot"    "scripts\training\argot"
Stage-Tree "scripts\training\emit_rft" "scripts\training\emit_rft"
# config templates + coding bundle manifest + release-evidence seeds
Stage-File "server\src\main\resources\application.conf" "etc\application.conf"
Stage-File "server\src\main\resources\logback.xml"      "etc\logback.xml"
Stage-File "data\coding-cli-bundle\manifest.json"       "data\coding-cli-bundle\manifest.json"
Stage-Tree "data\release-evidence"                      "data\release-evidence"
Stage-File "VERSION" "VERSION"
# OSS license — every installer must carry it (audit 2026-07-11).
Stage-File "LICENSE" "LICENSE"
Stage-File "NOTICE" "NOTICE"
if (Test-Path "docs\public\THIRD_PARTY_NOTICES.md") {
    Stage-File "docs\public\THIRD_PARTY_NOTICES.md" "THIRD_PARTY_NOTICES.md"
} else {
    Stage-File "THIRD_PARTY_NOTICES.md" "THIRD_PARTY_NOTICES.md"
}

# Payload hygiene (audit 2026-07-11): the MSI bypasses build-dist.sh, so it
# lacked the dist guards. (1) Prune dev debris that Stage-Tree recursive-copies
# (a stray scripts/training/.venv-* once ballooned the dist to 13GB); (2) drop
# .bak files (a 118MB setfit .onnx.bak was riding along); (3) hard-fail if the
# SetFit encoder ONNX is missing — it's gitignored with no HF mirror, and a
# silent miss ships a degraded-classifier installer.
Get-ChildItem $Stage -Recurse -Directory -Force |
    Where-Object { $_.Name -like ".venv*" -or $_.Name -eq "__pycache__" } |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem $Stage -Recurse -File -Filter "*.bak" |
    Remove-Item -Force -ErrorAction SilentlyContinue
$setfit = Get-ChildItem (Join-Path $Stage "core\src\main\resources\models") -Filter "*setfit*q8.onnx" -ErrorAction SilentlyContinue
if (-not $setfit) {
    Write-Error "[fatal] SetFit encoder ONNX missing from payload (core\src\main\resources\models). Refusing to build a degraded-classifier MSI. Sync the model file to this machine first."
    exit 1
}

# Dictionary library bundle — same payload
# build-dist.sh stages for .deb/.pkg; BundledPackInstaller indexes it from
# <installRoot>\share\library-bundle at first boot, no network needed.
# `bin/wyrd library bundle` is bash-only, so on Windows we stage from the
# persistent cache (build\library-bundle-cache) prepared on a build machine
# with the CLI — sync it over before building. Fail-soft like build-dist.sh.
$libBundleCache = "build\library-bundle-cache"
$staged = 0
foreach ($pack in @("jmdict", "freedict-spa-eng")) {
    $packDir = Join-Path $libBundleCache $pack
    if ((Test-Path (Join-Path $packDir "pack.json")) -and
        (Get-ChildItem (Join-Path $packDir "chunks") -Filter *.jsonl -ErrorAction SilentlyContinue)) {
        $dst = Join-Path $Stage "share\library-bundle\$pack\chunks"
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Copy-Item (Join-Path $packDir "pack.json") (Split-Path $dst -Parent) -Force
        Copy-Item (Join-Path $packDir "chunks\*.jsonl") $dst -Force
        $staged++
        Write-Host "[ok] payload: share\library-bundle\$pack"
    }
}
if ($staged -eq 0) {
    Write-Warning "library-bundle cache empty ($libBundleCache) — .msi ships WITHOUT bundled dictionaries"
}

# Embedding-model assets (paraphrase-l12 ONNX + tokenizer, ~130MB) — parity with
# the .deb (/opt/wyrdsekai/share/embedding-models) and .pkg. Fetched by
# packaging/fetch-embedding-models.sh (NOT in git). Lands at app\share\embedding-models
# where wyrd.ps1 Install-EmbeddingModel probes BEFORE any HuggingFace download, so
# offline installs get retrieval/classifiers working out of the box.
$embSrc = "packaging\embedding-models"
if (Test-Path $embSrc) {
    $embDst = Join-Path $Stage "share\embedding-models"
    New-Item -ItemType Directory -Force -Path $embDst | Out-Null
    Get-ChildItem $embSrc -File | ForEach-Object {
        Copy-Item $_.FullName -Destination $embDst -Force
        Write-Host "[ok] payload: share\embedding-models\$($_.Name) ($([math]::Round($_.Length / 1MB, 0)) MB)"
    }
} else {
    Write-Warning "embedding-models not bundled ($embSrc missing) — run packaging/fetch-embedding-models.ps1|.sh first; setup falls back to HF download"
}

$InputDir = $Stage
Write-Host "[ok] Full standalone payload staged → $Stage"

# --- Find main JAR ---
$mainJar = Get-ChildItem "$InputDir/server-*.jar" | Select-Object -First 1
if (-not $mainJar) {
    Write-Error "Could not find server JAR in $InputDir"
    exit 1
}

# --- Stage nats-server.exe alongside the JARs so jpackage bundles it ---
# jpackage's --input copies the entire dir into the installed app folder.
# Placing nats-server.exe next to the JARs means it lands at
# %ProgramFiles%\Wyrdsekai\app\nats-server.exe — which NatsServerManager
# now probes via "next to JVM home" discovery (see between/.../NatsServerManager.java).
$natsExe = "packaging\windows\nats-server.exe"
if (-not (Test-Path $natsExe)) {
    Write-Host "[info] nats-server.exe missing — running fetch-nats-server.ps1..."
    & "packaging\windows\fetch-nats-server.ps1"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Could not fetch nats-server.exe — MSI will install but Between will be disabled"
    }
}
if (Test-Path $natsExe) {
    $stagedNats = Join-Path $InputDir "nats-server.exe"
    Copy-Item -Path $natsExe -Destination $stagedNats -Force
    Write-Host "[ok] Staged nats-server.exe into input dir"
}

# --- Stage metasearch.exe (web search) into app\bin so wyrd.ps1 finds it ---
# Mirrors the .deb, which bundles a `metasearch` binary (mat-1/metasearch2) as a
# service. Windows: place a cross-compiled metasearch.exe at
# packaging\windows\metasearch.exe and it lands at app\bin\metasearch.exe, where
# wyrd.ps1 Resolve-MetasearchSource picks it up. Absent → web search uses the
# DuckDuckGo keyless fallback (Install-Metasearch can still fetch via
# WYRDSEKAI_METASEARCH_WIN_URL later).
$metasearchExe = "packaging\windows\metasearch.exe"
if (Test-Path $metasearchExe) {
    $binDir = Join-Path $InputDir "bin"
    New-Item -ItemType Directory -Force -Path $binDir | Out-Null
    Copy-Item -Path $metasearchExe -Destination (Join-Path $binDir "metasearch.exe") -Force
    Write-Host "[ok] Staged metasearch.exe into app\bin"
} else {
    Write-Host "[info] metasearch.exe not present (packaging\windows\metasearch.exe) — web search will use DuckDuckGo fallback until provided"
}

# --- Stage FIRST_ENCOUNTER.md ---
# Three-page bondholder introduction surfaced by `wyrd setup` after install.
# Lands in the input dir so jpackage bundles it into the installed app folder
# where bin/wyrd's setup.complete render can find it.
# Prefer the TRACKED copy under docs/; the repo-root file is a working copy that
# does not exist in a clean clone, so this silently skipped on every build host
# except the author's own machine (found 2026-07-26).
# docs/public/ is the authoritative public copy; docs/ and the repo root are
# private working drafts still carrying internal spec citations and a
# "not for the bondholder" section that must never reach an install.
$firstEncounter = if (Test-Path "docs/public/FIRST_ENCOUNTER.md") { "docs/public/FIRST_ENCOUNTER.md" }
                  elseif (Test-Path "docs/FIRST_ENCOUNTER.md") { "docs/FIRST_ENCOUNTER.md" }
                  elseif (Test-Path "FIRST_ENCOUNTER.md") { "FIRST_ENCOUNTER.md" }
                  else { $null }
if ($firstEncounter) {
    $stagedFE = Join-Path $InputDir "FIRST_ENCOUNTER.md"
    Copy-Item -Path $firstEncounter -Destination $stagedFE -Force
    Write-Host "[ok] Staged FIRST_ENCOUNTER.md into input dir"
}

# --- Stage the Windows management CLI (wyrd.ps1 + wyrd.cmd shim) ---
# bin/wyrd is the bash management CLI shipped in .deb/.pkg; Windows gets a
# focused PowerShell MVP. Both land in the app dir (next to the JARs); the
# shim resolves wyrd.ps1 beside itself and the launcher one level up.
foreach ($cliFile in @("packaging\windows\wyrd.ps1", "packaging\windows\wyrd.cmd")) {
    if (Test-Path $cliFile) {
        Copy-Item -Path $cliFile -Destination (Join-Path $InputDir (Split-Path $cliFile -Leaf)) -Force
        Write-Host "[ok] Staged $(Split-Path $cliFile -Leaf) into input dir"
    } else {
        Write-Warning "Windows CLI file missing: $cliFile"
    }
}

# --- Stage the desktop shell (Wyrdsekai.Tray.exe) ---
# The native tray + WebView2 control panel + first-run onboarding wizard (see
# ). Lands beside the CLI shim in the app dir so
# NodeController finds wyrd.cmd next to itself. Built by build-tray.ps1 (needs
# the .NET SDK). If absent, the .msi still ships the headless node — the icon
# just won't have the friendly shell yet.
#
# SHORTCUT REPOINT (TODO, windows-node iteration): jpackage's --win-shortcut points at
# its own Java launcher (Wyrdsekai.exe → Main, headless). To make the Start-menu/
# desktop icon launch the tray instead, the robust path is jpackage --type
# app-image + a WiX step that authors the shortcut → app\Wyrdsekai.Tray.exe (the
# headless launcher stays as the server the shell drives). Tracked in #1284.
$trayExe = "packaging\windows\tray\out\Wyrdsekai.Tray.exe"
if (-not (Test-Path $trayExe) -and (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    Write-Host "[info] Tray exe not built yet — running build-tray.ps1"
    & (Join-Path $PSScriptRoot "build-tray.ps1") -Version $Version
}
if (Test-Path $trayExe) {
    Copy-Item -Path $trayExe -Destination (Join-Path $InputDir "Wyrdsekai.Tray.exe") -Force
    if (Test-Path "packaging\windows\wyrdsekai.ico") {
        Copy-Item -Path "packaging\windows\wyrdsekai.ico" -Destination (Join-Path $InputDir "wyrdsekai.ico") -Force
    }
    Write-Host "[ok] Staged Wyrdsekai.Tray.exe into input dir"
} else {
    Write-Warning "Desktop shell missing ($trayExe) — build it with build-tray.ps1 (needs .NET SDK). .msi will ship headless-only."
}

# --- Stage V8 voice steering vectors (GGUF) ---
# The 4 default repeng vectors the voice brain on :8201 loads via
# --control-vector-scaled. Lands at app\data\vectors\v8\ ; `wyrd setup` /
# `wyrd inference install` copy them into $DATA_DIR\vectors\v8 where
# Start-LlamaServer resolves them. Same set as build-dist.sh / .deb / .pkg.
$v8Src = "data\training\v8\vectors"
if (Test-Path $v8Src) {
    $v8Dest = Join-Path $InputDir "data\vectors\v8"
    New-Item -ItemType Directory -Force -Path $v8Dest | Out-Null
    foreach ($v in @("anti_defiance", "es_register_hold", "refusal_stability", "first_person_presence")) {
        $f = Join-Path $v8Src "$v.gguf"
        if (Test-Path $f) { Copy-Item -Path $f -Destination $v8Dest -Force }
    }
    $n = (Get-ChildItem $v8Dest -Filter *.gguf -ErrorAction SilentlyContinue | Measure-Object).Count
    Write-Host "[ok] Staged $n V8 voice vectors into input dir"
} else {
    Write-Warning "V8 vectors dir missing ($v8Src) — voice brain will run without steering vectors"
}

# --- Stage the Oracle forecasting sidecar wheel (oracle-core) ---
# Mirrors the .deb (/opt/wyrdsekai/share/oracle) and .pkg
# (/usr/local/wyrdsekai/share/oracle). The pure-python wheel lands at
# app\share\oracle\ ; `wyrd oracle bootstrap` (run by `wyrd setup`) pip-installs
# it into $DataDir\.venv-oracle and `wyrd oracle start` runs oracle-server on
# :7073, which the Java zone auto-connects to on health. Absent python → the
# bootstrap no-ops with a hint and the zone runs without forecasting.
$oracleWheel = Get-ChildItem "packaging\oracle\oracle_core-*.whl" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($oracleWheel) {
    $oracleDest = Join-Path $InputDir "share\oracle"
    New-Item -ItemType Directory -Force -Path $oracleDest | Out-Null
    Copy-Item -Path $oracleWheel.FullName -Destination (Join-Path $oracleDest $oracleWheel.Name) -Force
    Write-Host "[ok] Staged oracle-core wheel ($($oracleWheel.Name)) into app\share\oracle"
} else {
    Write-Warning "oracle-core wheel missing (packaging\oracle\oracle_core-*.whl) — Oracle forecasting disabled in this .msi"
}

Write-Host "[ok] Main JAR: $($mainJar.Name)"

# --- Create output directory ---
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

# --- Redact household identifiers from the payload ---
# The MSI assembles its own payload and never calls build-dist.sh, so it does
# not inherit that script's redaction pass — it needs its own. Runs last, so
# everything staged above (including FIRST_ENCOUNTER.md and wyrd.ps1) is
# covered, and before jpackage copies the tree into the app-image.
#
# Hard-fails without python3: shipping an unredacted installer is worse than a
# failed build. If this stops a build, put python3 on PATH — the script is
# pure stdlib and needs nothing installed.
# Conditional on a local identifier map being configured. Without one there is
# nothing to substitute and the build proceeds unchanged, which is the ordinary
# case; with one, a failure below is fatal rather than skipped.
if (-not (Test-Path "scripts\lib\oss_redact.py")) {
    $skipRedaction = $true
}
if (-not $skipRedaction) {
Write-Host "[info] Redacting household identifiers from the staged payload..."
# Not `??` — that is PowerShell 7+ only, and this script has to run under
# Windows PowerShell 5.1 as well.
$pyExe = Get-Command python3 -ErrorAction SilentlyContinue
if (-not $pyExe) { $pyExe = Get-Command python -ErrorAction SilentlyContinue }
if (-not $pyExe) {
    # The build box keeps its toolchain in C:\Tools (jdk25, wix314) rather than
    # on PATH, so look there too before giving up. An embeddable Python is
    # enough — the redactor is pure stdlib.
    $probe = Get-ChildItem "C:\Tools" -Directory -Filter "python*" -ErrorAction SilentlyContinue |
             ForEach-Object { Join-Path $_.FullName "python.exe" } |
             Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($probe) { $pyExe = [pscustomobject]@{ Source = $probe } }
}
if (-not $pyExe) {
    Write-Error "[fatal] python3 not found. Refusing to build an MSI whose payload has not been redacted. Install Python and re-run."
    exit 1
}
& $pyExe.Source "scripts\lib\dist_redact.py" $InputDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "[fatal] Payload redaction failed (exit $LASTEXITCODE). Refusing to package."
    exit 1
}
Write-Host "[ok] Payload redacted"
}

# --- Build app-image (jpackage), then author the MSI ourselves with WiX ---
# WHY NOT `jpackage --type msi`: that points the Start-menu/desktop shortcut at the
# headless Java launcher (Wyrdsekai.exe -> server). We want the icon to open the
# desktop SHELL (app\Wyrdsekai.Tray.exe — tray control panel + first-run onboarding).
# So: build an app-image, harvest it with WiX `heat`, and author the shortcut ->
# the tray in packaging/windows/wyrdsekai.wxs. The headless launcher stays in the
# image as the server the shell drives.
Write-Host "[info] Building app-image via jpackage..."
$imgStage = Join-Path $OutputDir "appimage"
if (Test-Path $imgStage) { Remove-Item -Recurse -Force $imgStage }
New-Item -ItemType Directory -Force -Path $imgStage | Out-Null

$jpackageArgs = @(
    "--type", "app-image",
    "--name", "Wyrdsekai",
    "--app-version", $Version,
    "--vendor", "Wyrdsekai",
    "--description", "Distributed text-native OS",
    "--input", $InputDir,
    "--main-jar", $mainJar.Name,
    "--main-class", "org.wyrdsekai.server.Main",
    "--java-options", "--add-opens java.base/java.lang.reflect=ALL-UNNAMED",
    "--java-options", "--enable-native-access=ALL-UNNAMED",
    "--java-options", "-XX:+UseCompactObjectHeaders",
    "--dest", $imgStage
)
if (Test-Path "packaging/windows/wyrdsekai.ico") {
    $jpackageArgs += @("--icon", "packaging/windows/wyrdsekai.ico")
}

# jpackage writes progress to stderr; relax EAP so it isn't promoted to terminating.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& jpackage @jpackageArgs
$jpExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($jpExit -ne 0) { Write-Error "jpackage app-image failed with exit code $jpExit"; exit 1 }

$imgDir = Join-Path $imgStage "Wyrdsekai"
if (-not (Test-Path (Join-Path $imgDir "app\Wyrdsekai.Tray.exe"))) {
    Write-Warning "Wyrdsekai.Tray.exe NOT in app-image — the shortcut will be dead. Build it first with build-tray.ps1."
}

# --- Module libs (full-peer claim, W9): app\lib\<module> ---
# cli (library bundle CLI), daemon-desktop, rendezvous — mirrors
# packaging/build-dist.sh's per-module lib/<module> isolation (Pekko's
# transitive deps can pull different Jackson versions per module). Injected
# into the app-image AFTER jpackage on purpose: jars in the jpackage --input
# would risk landing on the generated launcher classpath (version conflicts
# with the server's deps); copying here means heat harvests them into the MSI
# while the launcher .cfg never references them. wyrd.ps1 resolves them at
# app\lib\<module> (Invoke-WyrdJavaClassStream -LibSubdir). Fail-soft per
# module: an -InputDir build on a box without these dists still produces a
# working (server-only) MSI, with a warning.
$moduleLibs = @(
    @{ Name = "cli";             Src = "cli\build\install\cli\lib" },
    @{ Name = "daemon-desktop";  Src = "clients\daemon-desktop\build\install\daemon-desktop\lib" },
    @{ Name = "wyrd-rendezvous"; Src = "rendezvous\build\install\wyrd-rendezvous\lib" }
)
foreach ($m in $moduleLibs) {
    if (Test-Path $m.Src) {
        $dst = Join-Path $imgDir "app\lib\$($m.Name)"
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Copy-Item (Join-Path $m.Src "*.jar") -Destination $dst -Force -ErrorAction SilentlyContinue
        $n = (Get-ChildItem $dst -Filter *.jar -ErrorAction SilentlyContinue | Measure-Object).Count
        Write-Host "[ok] payload: app\lib\$($m.Name) ($n jars)"
    } else {
        Write-Warning "module dist missing ($($m.Src)) — MSI ships without lib\$($m.Name) (run the matching gradlew installDist first)"
    }
}

# --- Pre-upgrade DB snapshot custom-action payload (W11) ---
# Parity with the .deb postinst / .pkg preinstall: on upgrade, wyrdsekai.wxs runs
# this script (WixQuietExec + powershell -EncodedCommand, upgrade-only condition)
# BEFORE the new build's migrations can touch the node's databases. It probes the
# same data-dir candidates wyrd.ps1 uses (WYRDSEKAI_DATA_DIR, %USERPROFILE%\.wyrdsekai)
# plus %APPDATA%\wyrdsekai, snapshots *.db + data-version.json into
# <data>\backups\pre-upgrade-msi-<ts>\, and prunes to the 3 newest pre-upgrade-*
# snapshots. Everything is best-effort: the action is Return="ignore" in the .wxs
# and the script itself never throws.
$snapshotScript = @'
$ErrorActionPreference = 'SilentlyContinue'
$dirs = @()
if ($env:WYRDSEKAI_DATA_DIR) { $dirs += $env:WYRDSEKAI_DATA_DIR }
if ($env:USERPROFILE) { $dirs += (Join-Path $env:USERPROFILE '.wyrdsekai') }
if ($env:APPDATA) { $dirs += (Join-Path $env:APPDATA 'wyrdsekai') }
foreach ($d in ($dirs | Select-Object -Unique)) {
    if (-not (Test-Path $d)) { continue }
    $dbs = Get-ChildItem -Path $d -Filter *.db -File -ErrorAction SilentlyContinue
    if (-not $dbs) { continue }
    $ts = Get-Date -Format yyyyMMdd-HHmmss
    $backups = Join-Path $d 'backups'
    $snap = Join-Path $backups ('pre-upgrade-msi-' + $ts)
    New-Item -ItemType Directory -Force -Path $snap | Out-Null
    foreach ($f in $dbs) { Copy-Item $f.FullName -Destination $snap -Force }
    $dv = Join-Path $d 'data-version.json'
    if (Test-Path $dv) { Copy-Item $dv -Destination $snap -Force }
    Get-ChildItem -Path $backups -Directory -Filter 'pre-upgrade-*' -ErrorAction SilentlyContinue |
        Sort-Object CreationTime -Descending | Select-Object -Skip 3 |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
'@
$SnapshotCmd = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($snapshotScript))

# --- WiX: heat (harvest app-image) -> candle -> light, shortcut -> the tray ---
Write-Host "[info] Authoring MSI with WiX (shortcut -> Wyrdsekai.Tray.exe)..."
$wixObj = Join-Path $OutputDir "wixobj"
if (Test-Path $wixObj) { Remove-Item -Recurse -Force $wixObj }
New-Item -ItemType Directory -Force -Path $wixObj | Out-Null
$harvest   = Join-Path $wixObj "appfiles.wxs"
$product   = (Resolve-Path "packaging\windows\wyrdsekai.wxs").Path
$iconFull  = (Resolve-Path "packaging\windows\wyrdsekai.ico").Path
$imgDirFull = (Resolve-Path $imgDir).Path
$msiPath   = Join-Path $OutputDir "Wyrdsekai-$Version.msi"
if (Test-Path $msiPath) { Remove-Item -Force $msiPath }

$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& heat.exe dir $imgDirFull -nologo -cg AppFiles -dr INSTALLDIR -ag -srd -sreg -scom -sfrag -var var.SourceDir -out $harvest
$heatExit = $LASTEXITCODE
$candleExit = 1; $lightExit = 1
if ($heatExit -eq 0) {
    # -ext WixUtilExtension: wyrdsekai.wxs uses WixQuietExec (WixCA) for the
    # pre-upgrade DB snapshot; -dSnapshotCmd carries the encoded script (base64,
    # so it is a single quote-free token on the command line).
    & candle.exe -nologo -arch x64 -ext WixUtilExtension ("-dSourceDir=" + $imgDirFull) ("-dVersion=" + $Version) ("-dIconFile=" + $iconFull) ("-dSnapshotCmd=" + $SnapshotCmd) -out ($wixObj + "\") $harvest $product
    $candleExit = $LASTEXITCODE
}
if ($candleExit -eq 0) {
    & light.exe -nologo -sval -ext WixUtilExtension -out $msiPath (Join-Path $wixObj "appfiles.wixobj") (Join-Path $wixObj "wyrdsekai.wixobj")
    $lightExit = $LASTEXITCODE
}
$ErrorActionPreference = $prevEAP
if ($heatExit -ne 0)   { Write-Error "WiX heat failed ($heatExit)"; exit 1 }
if ($candleExit -ne 0) { Write-Error "WiX candle failed ($candleExit)"; exit 1 }
if ($lightExit -ne 0)  { Write-Error "WiX light failed ($lightExit)"; exit 1 }

$msiFile = Get-Item $msiPath -ErrorAction SilentlyContinue
if (-not $msiFile) { Write-Error "MSI not produced at $msiPath"; exit 1 }
Write-Host "[ok] MSI built: $($msiFile.FullName) ($([math]::Round($msiFile.Length / 1MB, 1)) MB)"

# --- WinSW service wrapper (optional) ---
if ($WithService) {
    Write-Host "[info] Adding WinSW service wrapper..."

    $serviceXml = @"
<service>
  <id>wyrdsekai</id>
  <name>Wyrdsekai Server</name>
  <description>Wyrdsekai distributed text-native OS — AI agents and humans in shared programmable world</description>
  <executable>%PROGRAMFILES%\Wyrdsekai\bin\Wyrdsekai.exe</executable>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>8</keepFiles>
  </log>
  <onfailure action="restart" delay="10 sec"/>
  <onfailure action="restart" delay="30 sec"/>
  <onfailure action="none"/>
</service>
"@

    $serviceXml | Out-File -FilePath "$OutputDir/wyrdsekai-service.xml" -Encoding UTF8
    Write-Host "[info] Service config written to $OutputDir/wyrdsekai-service.xml"
    Write-Host "[info] To install as service: download WinSW.exe, rename to wyrdsekai-service.exe,"
    Write-Host "       place alongside wyrdsekai-service.xml, run: wyrdsekai-service.exe install"
}

# --- Summary ---
Write-Host ""
Write-Host "  ============================================"
Write-Host "       Build complete!"
Write-Host "  ============================================"
Write-Host ""
Write-Host "  MSI:     $($msiFile.FullName)"
Write-Host "  Size:    $([math]::Round($msiFile.Length / 1MB, 1)) MB"
Write-Host "  Version: $Version"

# Publish into build\installers\ — the canonical dir artifacts are scp'd from
# (second-node 2026-07-07: a fresh build that only landed in build\win\ never reached
# the installed box). One source of truth for "the installer to ship".
$installersDir = "build\installers"
New-Item -ItemType Directory -Force -Path $installersDir | Out-Null
Copy-Item -Path $msiFile.FullName -Destination (Join-Path $installersDir "Wyrdsekai-$Version.msi") -Force
Write-Host "  Published: $installersDir\Wyrdsekai-$Version.msi"
Write-Host ""
Write-Host "  Install: msiexec /i $($msiFile.Name)"
Write-Host "  Silent:  msiexec /i $($msiFile.Name) /quiet"
Write-Host ""
