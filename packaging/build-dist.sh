#!/usr/bin/env bash
#
# build-dist.sh — Build Wyrdsekai distribution archive
#
# Creates a self-contained directory with:
#   - Server + CLI + daemon-desktop JARs + dependencies
#   - bin/wyrd launcher script
#   - Room scripts, data templates, config
#
# Output: build/dist/wyrdsekai-<version>/
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
VERSION="${WYRDSEKAI_VERSION:-0.2.1}"
DIST_NAME="wyrdsekai-${VERSION}"
DIST_DIR="$PROJECT_DIR/build/dist/$DIST_NAME"

info()  { echo -e "\033[36m[dist]\033[0m $*"; }
ok()    { echo -e "\033[32m[dist]\033[0m $*"; }
warn()  { echo -e "\033[33m[dist]\033[0m $*" >&2; }
err()   { echo -e "\033[31m[dist]\033[0m $*" >&2; }

# ── Clean ──
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# ── Build all application modules ──
# F21: the previous version piped gradle through
# `tail -5`, and `set -euo pipefail`'s pipe-RC capture defaulted to tail's
# RC, so a failed build (e.g. installDist guard fires when a server is
# running) silently exited 0 and the dist dir got partially populated.
# Two-belt fix: (a) check ${PIPESTATUS[0]} explicitly so any non-zero
# from gradle aborts the script even when piped, (b) keep full output
# rather than tail-truncating so the actual error is in the build log.
info "Building server, cli, daemon-desktop..."
cd "$PROJECT_DIR"
./gradlew :server:installDist :cli:installDist :clients:daemon-desktop:installDist \
    :rendezvous:installDist \
    --no-daemon
gradle_rc=${PIPESTATUS[0]:-$?}
if [[ "$gradle_rc" -ne 0 ]]; then
    err "Gradle build failed (exit $gradle_rc) — aborting dist assembly"
    exit "$gradle_rc"
fi

ok "Build complete"

# ── Embedding model (the BUNDLED default) ──
# paraphrase-l12 is what the product calls its "bundled default", and until now it
# was not bundled by anything. build-deb.sh had the staging code, guarded on a
# directory that nothing ever populated, so every build took the else-branch, printed
# a one-line NOTE into a wall of output, and shipped an installer whose default model
# had to be fetched from HuggingFace at `wyrd setup`.
#
# It worked for months because HuggingFace served it. On 2026-07-13 HF moved those
# files onto its Xet CAS backend, whose CDN began answering 403 to plain curl (every
# repo, ours included), and a fresh second-node install came up with retrieval disabled — an
# entire feature lost to somebody else's CDN policy, in a product whose whole promise
# is that it runs on your own machine.
#
# Fetch it here, as part of the build, so "bundled" is a fact rather than a hope. The
# fetch script prefers local caches and only reaches the network as a last resort.
info "Staging embedding model (bundled default)..."
"$SCRIPT_DIR/fetch-embedding-models.sh"

# llama.cpp CPU server. Gitignored (it is an upstream release binary, not our
# source), and every packager stages it from packaging/ with a fail-soft guard —
# so on a tree that never ran this fetcher the installer was built WITHOUT a
# local inference server and only said so in one warning line. A package that
# installs and then cannot think is a worse outcome than a build that paused to
# download 30MB. The script pins a release and no-ops if the binary is already
# there, so this is free on a warm tree.
if [[ ! -f "$SCRIPT_DIR/llama-server" && -x "$SCRIPT_DIR/fetch-llama-cpu.sh" ]]; then
    info "llama-server absent — fetching the pinned llama.cpp CPU build..."
    "$SCRIPT_DIR/fetch-llama-cpu.sh" || warn "llama-server fetch failed — this package will have no local inference server"
fi

# nats-server: the Between rides NATS, so a package without it has no
# cross-node bridge. Windows has had a fetcher for ages; Linux/macOS never did,
# which is why a clone could build a .deb missing what the .msi always had.
if [[ ! -f "$SCRIPT_DIR/nats-server" && -x "$SCRIPT_DIR/fetch-nats-server.sh" ]]; then
    info "nats-server absent — fetching the pinned release..."
    "$SCRIPT_DIR/fetch-nats-server.sh" || warn "nats-server fetch failed — this package will have no cross-node bridge"
fi

# Everything else that is gitignored but required: the embedding ONNX the
# recipes load by source path, and the metasearch backend. Manifest-driven, so
# a new asset is a JSON entry rather than another script. Hard-fails: these are
# the files whose fail-soft guards let a clone build a package that was quietly
# ~580MB short and still reported success.
if [[ -x "$SCRIPT_DIR/fetch-build-assets.sh" ]]; then
    if ! "$SCRIPT_DIR/fetch-build-assets.sh"; then
        err "Build assets unavailable — refusing to package an incomplete installer."
        err "Re-run when the network is back, or see packaging/build-assets.json."
        exit 1
    fi
fi

# ── Assemble distribution ──
info "Assembling distribution..."

# Collect JARs per module — each gets its own lib/ to avoid version conflicts
# (Pekko's transitive deps can pull different Jackson versions per module)
for module_name in server cli daemon-desktop wyrd-rendezvous; do
    mkdir -p "$DIST_DIR/lib/$module_name"
done

# Server
cp "$PROJECT_DIR/server/build/install/server/lib/"*.jar "$DIST_DIR/lib/server/" 2>/dev/null || true

# CLI
cp "$PROJECT_DIR/cli/build/install/cli/lib/"*.jar "$DIST_DIR/lib/cli/" 2>/dev/null || true

# Daemon
cp "$PROJECT_DIR/clients/daemon-desktop/build/install/daemon-desktop/lib/"*.jar "$DIST_DIR/lib/daemon-desktop/" 2>/dev/null || true

# Rendezvous (zone directory aggregator, )
cp "$PROJECT_DIR/rendezvous/build/install/wyrd-rendezvous/lib/"*.jar "$DIST_DIR/lib/wyrd-rendezvous/" 2>/dev/null || true

SERVER_JARS=$(ls -1 "$DIST_DIR/lib/server/"*.jar 2>/dev/null | wc -l)
CLI_JARS=$(ls -1 "$DIST_DIR/lib/cli/"*.jar 2>/dev/null | wc -l)
DAEMON_JARS=$(ls -1 "$DIST_DIR/lib/daemon-desktop/"*.jar 2>/dev/null | wc -l)
RDVZ_JARS=$(ls -1 "$DIST_DIR/lib/wyrd-rendezvous/"*.jar 2>/dev/null | wc -l)
ok "Collected JARs — server: $SERVER_JARS, cli: $CLI_JARS, daemon: $DAEMON_JARS, rendezvous: $RDVZ_JARS"

# ── HARD gate: the trained classifier heads must be INSIDE the staged core jar ──
# Nothing above stages these explicitly — they ride along because they live in
# core/src/main/resources/, so gradle embeds them. That makes their absence
# invisible: the build succeeds, the jar looks plausible, and ClassifierArm is
# simply inert at runtime. It happened once already, when a blanket *.onnx
# packaging filter swept them out of the sources the jar was compiled from.
#
# This lives in build-dist.sh rather than a platform script because DIST_DIR is
# the common ancestor of the .deb, the .pkg AND the .msi. The check previously
# sat in the .deb path alone, which left macOS and Windows free to ship an
# inert classifier with nothing to notice.
DIST_CORE_JAR=$(ls "$DIST_DIR/lib/server/core-"*.jar 2>/dev/null | head -1)
if [[ -z "$DIST_CORE_JAR" ]]; then
    err "core jar missing from $DIST_DIR/lib/server — cannot verify classifier heads"
    exit 1
fi
MISSING_HEADS=()
for head in request_type substrate_present task_present cleanliness; do
    unzip -l "$DIST_CORE_JAR" "classifier/pretrained/${head}.onnx" >/dev/null 2>&1 \
        || MISSING_HEADS+=("$head")
done
if (( ${#MISSING_HEADS[@]} )); then
    err "classifier heads MISSING from $(basename "$DIST_CORE_JAR"): ${MISSING_HEADS[*]}"
    err "  ClassifierArm is inert without these — routing falls back to heuristics."
    err "  They are tracked at core/src/main/resources/classifier/pretrained/."
    err "  If a packaging filter excludes *.onnx, add an exception for them."
    exit 1
fi
ok "classifier gate: all 4 trained heads present in $(basename "$DIST_CORE_JAR")"

# ── Launcher scripts ──
mkdir -p "$DIST_DIR/bin"

# Copy the real wyrd script
cp "$PROJECT_DIR/bin/wyrd" "$DIST_DIR/bin/wyrd"
# Release model index (data-durability, 2026-07-09) — `wyrd model` reads this.
cp "$PROJECT_DIR/models-index.json" "$DIST_DIR/models-index.json" 2>/dev/null || true
chmod +x "$DIST_DIR/bin/wyrd"

# Shared Java finder — sourced by all launchers
cat > "$DIST_DIR/bin/find-java.sh" << 'FINDJAVA'
# find-java.sh — locate Java 25+ for Wyrdsekai launchers
# Sourced (not executed). Sets JAVA_CMD.

find_java() {
    # 1. JAVA_HOME (explicit user override)
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
        return 0
    fi

    # 2. mise (version manager — common on dev machines)
    if command -v mise &>/dev/null; then
        local mise_java
        mise_java=$(mise which java 2>/dev/null) || true
        if [[ -n "$mise_java" && -x "$mise_java" ]]; then
            JAVA_CMD="$mise_java"
            return 0
        fi
    fi

    # 3. Plain PATH
    if command -v java &>/dev/null; then
        local ver
        ver=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/' || echo "0")
        if [[ "$ver" -ge 25 ]] 2>/dev/null; then
            JAVA_CMD="java"
            return 0
        fi
    fi

    # 4. Homebrew temurin (macOS)
    local brew_java="/opt/homebrew/opt/openjdk/bin/java"
    if [[ -x "$brew_java" ]]; then
        JAVA_CMD="$brew_java"
        return 0
    fi
    # Homebrew temurin cask installs here
    for d in /Library/Java/JavaVirtualMachines/temurin-*/Contents/Home/bin/java; do
        if [[ -x "$d" ]]; then
            JAVA_CMD="$d"
            return 0
        fi
    done

    # 5. Standard Linux locations
    for d in /usr/lib/jvm/java-25-openjdk*/bin/java \
             /usr/lib/jvm/java-25-openjdk*/bin/java \
             /usr/lib/jvm/temurin-25*/bin/java \
             /usr/lib/jvm/temurin-21*/bin/java; do
        if [[ -x "$d" ]]; then
            JAVA_CMD="$d"
            return 0
        fi
    done

    # 6. mise data directory (fallback — non-activated mise)
    for d in ~/.local/share/mise/installs/java/temurin-*/bin/java; do
        if [[ -x "$d" ]]; then
            JAVA_CMD="$d"
            return 0
        fi
    done

    echo "ERROR: Java 25+ not found." >&2
    echo "Install: https://adoptium.net/temurin/releases/" >&2
    echo "Or set JAVA_HOME to your Java installation." >&2
    return 1
}

find_java
FINDJAVA

# Server launch script (used by wyrd start internally, also standalone)
cat > "$DIST_DIR/bin/wyrdsekai-server" << 'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail

# Resolve symlinks to find real install directory
SELF="$0"
if [[ -L "$SELF" ]]; then SELF="$(readlink "$SELF")"; fi
REAL_BIN="$(cd "$(dirname "$SELF")" && pwd)"
INSTALL_DIR="$(dirname "$REAL_BIN")"
source "$REAL_BIN/find-java.sh"

# Anchor the process to the install root. Under systemd the CWD is / and any
# CWD-relative payload path (scripts/items, rooms fallbacks, ...) resolves to
# nothing — on second-node that silently loaded ZERO scripted items and every
# furnishing went dead (2026-07-04). Belt: cd. Suspenders: WYRDSEKAI_HOME for
# WyrdConfig.installRoot()-aware lookups. Both respect explicit overrides.
export WYRDSEKAI_HOME="${WYRDSEKAI_HOME:-$INSTALL_DIR}"
cd "$INSTALL_DIR"

CLASSPATH=""
for jar in "$INSTALL_DIR/lib/server/"*.jar; do
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

JVM_ARGS=(
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED
    -XX:+UseCompactObjectHeaders
    -cp "$CLASSPATH"
)

exec "$JAVA_CMD" "${JVM_ARGS[@]}" ${JAVA_OPTS:-} org.wyrdsekai.server.Main "$@"
LAUNCHER
chmod +x "$DIST_DIR/bin/wyrdsekai-server"

# CLI launch script
cat > "$DIST_DIR/bin/wyrdsekai-cli" << 'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail

# Resolve symlinks to find real install directory
SELF="$0"
if [[ -L "$SELF" ]]; then SELF="$(readlink "$SELF")"; fi
REAL_BIN="$(cd "$(dirname "$SELF")" && pwd)"
INSTALL_DIR="$(dirname "$REAL_BIN")"
source "$REAL_BIN/find-java.sh"

CLASSPATH=""
for jar in "$INSTALL_DIR/lib/cli/"*.jar; do
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

JVM_ARGS=(
    --enable-native-access=ALL-UNNAMED
    -XX:+UseCompactObjectHeaders
    -cp "$CLASSPATH"
)

exec "$JAVA_CMD" "${JVM_ARGS[@]}" ${JAVA_OPTS:-} org.wyrdsekai.cli.Wyrd "$@"
LAUNCHER
chmod +x "$DIST_DIR/bin/wyrdsekai-cli"

# Daemon launch script
cat > "$DIST_DIR/bin/wyrdsekai-daemon" << 'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail

# Resolve symlinks to find real install directory
SELF="$0"
if [[ -L "$SELF" ]]; then SELF="$(readlink "$SELF")"; fi
REAL_BIN="$(cd "$(dirname "$SELF")" && pwd)"
INSTALL_DIR="$(dirname "$REAL_BIN")"
source "$REAL_BIN/find-java.sh"

CLASSPATH=""
for jar in "$INSTALL_DIR/lib/daemon-desktop/"*.jar; do
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

JVM_ARGS=(
    -XX:+UseCompactObjectHeaders
    -cp "$CLASSPATH"
)

exec "$JAVA_CMD" "${JVM_ARGS[@]}" ${JAVA_OPTS:-} org.wyrdsekai.daemon.desktop.DaemonApp "$@"
LAUNCHER
chmod +x "$DIST_DIR/bin/wyrdsekai-daemon"

# wyrd-rendezvous launcher (zone directory aggregator, ).
# Runs as its own process — MUST NOT share the JVM with the main server so
# tunnel saturation can't take directory down.
cat > "$DIST_DIR/bin/wyrd-rendezvous" << 'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
SELF="$0"
if [[ -L "$SELF" ]]; then SELF="$(readlink "$SELF")"; fi
REAL_BIN="$(cd "$(dirname "$SELF")" && pwd)"
INSTALL_DIR="$(dirname "$REAL_BIN")"
source "$REAL_BIN/find-java.sh"
find_java

CLASSPATH=""
for jar in "$INSTALL_DIR/lib/wyrd-rendezvous/"*.jar; do
    CLASSPATH="${CLASSPATH:+$CLASSPATH:}$jar"
done

JVM_ARGS=(
    -XX:+UseCompactObjectHeaders
    -Xms64m -Xmx512m
    -cp "$CLASSPATH"
)

exec "$JAVA_CMD" "${JVM_ARGS[@]}" ${JAVA_OPTS:-} org.wyrdsekai.rendezvous.RendezvousMain "$@"
LAUNCHER
chmod +x "$DIST_DIR/bin/wyrd-rendezvous"

# ── Support files ──

# Room scripts
if [[ -d "$PROJECT_DIR/scripts/rooms" ]]; then
    cp -r "$PROJECT_DIR/scripts/rooms" "$DIST_DIR/rooms"
    ok "Room scripts copied ($(ls -1 "$DIST_DIR/rooms/"*.js 2>/dev/null | wc -l) scripts)"
fi

# Config templates
mkdir -p "$DIST_DIR/etc"
if [[ -f "$PROJECT_DIR/server/src/main/resources/application.conf" ]]; then
    cp "$PROJECT_DIR/server/src/main/resources/application.conf" "$DIST_DIR/etc/application.conf"
fi
if [[ -f "$PROJECT_DIR/server/src/main/resources/logback.xml" ]]; then
    cp "$PROJECT_DIR/server/src/main/resources/logback.xml" "$DIST_DIR/etc/logback.xml"
fi

# Docker compose files (for users who want Docker-based infrastructure)
mkdir -p "$DIST_DIR/docker"
for f in "$PROJECT_DIR/docker/"*.yml; do
    [[ -f "$f" ]] && cp "$f" "$DIST_DIR/docker/"
done
if [[ -f "$PROJECT_DIR/docker/.env.example" ]]; then
    cp "$PROJECT_DIR/docker/.env.example" "$DIST_DIR/docker/.env.example"
fi
# Root docker-compose.yml (used by wyrd setup for Searxng + NATS)
if [[ -f "$PROJECT_DIR/docker-compose.yml" ]]; then
    cp "$PROJECT_DIR/docker-compose.yml" "$DIST_DIR/docker-compose.yml"
fi
# Searxng config
if [[ -f "$PROJECT_DIR/docker/searxng-settings.yml" ]]; then
    cp "$PROJECT_DIR/docker/searxng-settings.yml" "$DIST_DIR/docker/searxng-settings.yml"
fi

# Operational scripts
mkdir -p "$DIST_DIR/scripts"
# Config catalog — read by `wyrd config list --all` on every platform, and
# generated from the in-world Scroll of Settings (ConfigCatalogParityTest pins
# the two together). Without it the CLI can only list keys already written.
cp "$PROJECT_DIR/scripts/config-catalog.json" "$DIST_DIR/scripts/" 2>/dev/null || true
cp "$PROJECT_DIR/scripts/run-node.sh" "$DIST_DIR/scripts/" 2>/dev/null || true
cp "$PROJECT_DIR/scripts/setup-daemon.sh" "$DIST_DIR/scripts/" 2>/dev/null || true

# macOS LaunchDaemon plist template + MLX trainer bootstrap. The .pkg
# postinstall reads these from /usr/local/wyrdsekai/scripts/ at install time.
cp "$PROJECT_DIR/scripts/com.wyrdsekai.server.plist" "$DIST_DIR/scripts/" 2>/dev/null || true
cp "$PROJECT_DIR/scripts/mac-node-bootstrap-mlx-trainer.sh" "$DIST_DIR/scripts/" 2>/dev/null || true

# scripts/training is ~5MB, most of it research bench (act_model, v7_agency,
# vitality, substrate) that nothing on an installed system can reach. But
# shipped recipes DO live in here — rebake-argot drives scripts/training/argot/,
# run-emit-rft drives scripts/training/emit_rft/, the sleep-forge pair
# (0.2.0) drives scripts/training/sleep/ — and VoiceAligner resolves
# mlx_adapter_to_peft.py. Ship those, drop the bench.
mkdir -p "$DIST_DIR/scripts/training"
cp "$PROJECT_DIR/scripts/training/mlx_adapter_to_peft.py" "$DIST_DIR/scripts/training/" 2>/dev/null || true
for tsub in argot emit_rft sleep sleepwrite; do
    if [[ -d "$PROJECT_DIR/scripts/training/$tsub" ]]; then
        mkdir -p "$DIST_DIR/scripts/training/$tsub"
        cp -r "$PROJECT_DIR/scripts/training/$tsub/." "$DIST_DIR/scripts/training/$tsub/"
    fi
done

# #1089 — bundle classifier-recipe Python scripts. The
# retrain-classifier-head recipe (and friends) shells out to these from
# the daemon's CWD (= install root). Without them on disk a fresh .pkg
# install can enroll recipes but cannot execute them.
if [[ -d "$PROJECT_DIR/scripts/classifier" ]]; then
    mkdir -p "$DIST_DIR/scripts/classifier"
    cp -r "$PROJECT_DIR/scripts/classifier/." "$DIST_DIR/scripts/classifier/"
    ok "classifier scripts copied ($(ls -1 "$DIST_DIR/scripts/classifier/"*.py 2>/dev/null | wc -l) .py files)"
fi

# #1089 — bundle classifier bootstrap seeds + probe anchors +
# pretrained ONNX heads + embedding ONNX (paraphrase-MiniLM, MiniLM L6).
# The recipe YAMLs reference these by source-relative paths (e.g.
# core/src/main/resources/classifier/bootstrap/{head}/seeds.jsonl) so we
# preserve the layout under DIST_DIR. The daemon's WorkingDirectory is
# set to the install root for .pkg installs (see com.wyrdsekai.server.plist)
# so the paths resolve.
if [[ -d "$PROJECT_DIR/core/src/main/resources/classifier" ]]; then
    mkdir -p "$DIST_DIR/core/src/main/resources/classifier"
    cp -r "$PROJECT_DIR/core/src/main/resources/classifier/." \
        "$DIST_DIR/core/src/main/resources/classifier/"
    ok "classifier assets copied (bootstrap + pretrained + probe-anchors)"
fi
if [[ -d "$PROJECT_DIR/core/src/main/resources/models" ]]; then
    mkdir -p "$DIST_DIR/core/src/main/resources/models"
    # Only ship the embedding ONNX files + tokenizers — these are what
    # train_classifier.py / probe_overrouting.py load by default.
    for f in "$PROJECT_DIR/core/src/main/resources/models/"*.onnx \
             "$PROJECT_DIR/core/src/main/resources/models/"*-tokenizer.json \
             "$PROJECT_DIR/core/src/main/resources/models/"*-vocab.txt ; do
        [[ -f "$f" ]] && cp "$f" "$DIST_DIR/core/src/main/resources/models/"
    done
    ok "embedding ONNX + tokenizers copied"

    # Decouple guard (2026-05-29): the classifier's SetFit encoder is a separate,
    # gitignored artifact with no HF mirror — it ships only from this working tree.
    # If it's missing, the build silently degrades the classifier (it falls back to
    # the stock retrieval encoder at runtime, wrong feature space for the heads).
    # Catch it at BUILD time, not weeks later in production.
    SETFIT_ENC="$PROJECT_DIR/core/src/main/resources/models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx"
    # Fetch it if it is not here. It is 113MB of build output and therefore
    # gitignored, which combined with the hard-fail below meant a fresh clone
    # could not build an installer at all — the error told the reader to copy
    # the file off a machine only the author has. The fetcher tries the local
    # node cache, then HuggingFace, then wyrdsekai.org, verifying the pinned
    # sha256 whichever answered.
    if [[ ! -f "$SETFIT_ENC" && -x "$SCRIPT_DIR/fetch-classifier-encoder.sh" ]]; then
        info "SetFit classifier encoder absent — fetching..."
        "$SCRIPT_DIR/fetch-classifier-encoder.sh" || true
    fi
    if [[ ! -f "$SETFIT_ENC" ]]; then
        warn "================================================================"
        warn "SetFit CLASSIFIER encoder MISSING: $(basename "$SETFIT_ENC")"
        warn "This build will ship a classifier that falls back to the STOCK"
        warn "retrieval encoder (degraded routing accuracy). It is gitignored +"
        warn "gitignored (113MB of build output), and every mirror in"
        warn "models-index.json was unreachable. Regenerate it via the"
        warn "retrain-classifier-head recipe (scripts/classifier/train_setfit.py"
        warn "+ export_setfit_encoder_onnx.py), or retry when the network is"
        warn "back. NOT release-ready as-is."
        warn "================================================================"
        # Hard-fail for release builds; dev builds can override to proceed.
        if [[ "${WYRDSEKAI_ALLOW_MISSING_SETFIT:-0}" != "1" ]]; then
            err "Refusing to build a release dist without the SetFit classifier encoder. Set WYRDSEKAI_ALLOW_MISSING_SETFIT=1 to override (dev only)."
            exit 1
        fi
    else
        ok "SetFit classifier encoder present (decoupled from retrieval)"
    fi
fi

# i18n message catalogs
if [[ -d "$PROJECT_DIR/scripts/i18n" ]]; then
    cp -r "$PROJECT_DIR/scripts/i18n" "$DIST_DIR/scripts/i18n"
    ok "i18n catalogs copied"
fi

# scripts/lib — dependency-free helpers the CLI shells out to at runtime
# (wyrd_qr.py: the self-contained QR renderer for `wyrd phone invite`, so a
# fresh box needs no qrencode/python3-qrcode).
if [[ -d "$PROJECT_DIR/scripts/lib" ]]; then
    cp -r "$PROJECT_DIR/scripts/lib" "$DIST_DIR/scripts/lib"
    # These helpers are BUILD-time only — nothing at runtime shells out to them,
    # and a released package has no use for the machinery that produced it.
    # Removing them is not cosmetic: they carry configuration written for one
    # developer's environment, and a package is the wrong place for that.
    #
    # Hard-fail rather than a bare `rm -f`. A prune that silently matches nothing
    # looks identical to a prune that worked, and the check below is what turns
    # "probably fine" into "verified". Every staging path needs its own copy of
    # this — they do not inherit each other's guarantees.
    _oss_tooling=(oss_redact.py oss_scan.py oss_spec_index.py oss_opsec_scan.py dist_redact.py)
    for _t in "${_oss_tooling[@]}"; do
        rm -f "$DIST_DIR/scripts/lib/$_t"
    done
    # __pycache__ holds the COMPILED form, and a .pyc keeps every string literal
    # the source had — so pruning only the .py left the operator's relay domain
    # and username in oss_redact.cpython-*.pyc. The gate below caught it; this
    # is the fix. Bytecode has no business in a shipped tree regardless.
    find "$DIST_DIR/scripts" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
    for _t in "${_oss_tooling[@]}"; do
        if [[ -e "$DIST_DIR/scripts/lib/$_t" ]]; then
            err "build-time tooling survived staging: scripts/lib/$_t"
            exit 1
        fi
    done
    # Belt and braces: nothing left under scripts/lib may name the operator.
    if grep -rlE 'example-relay|you@' "$DIST_DIR/scripts/lib" >/dev/null 2>&1; then
        err "operator identifiers remain in staged scripts/lib:"
        grep -rlE 'example-relay|you@' "$DIST_DIR/scripts/lib" >&2
        exit 1
    fi
    ok "scripts/lib copied (build-time tooling pruned)"
fi

# #1089 — bundle ALL recipe-callable script subdirs. Each of the
# ship-default recipes (#1024-1028) shells out to scripts/<subdir>/*.py;
# without these on disk those recipes throw RecipeValidationException at
# dispatch time ("script not found at ..."). The recipe-callable validator
# already enforces a `# recipe-callable: local-ok` header on each, so this
# is a safe wholesale bundle. Discovered 2026-05-27 on mac-node live verify.
# The authoritative membership test is the `# recipe-callable: local-ok` header
# the validator already enforces — not habit. Checked 2026-07-27, and the list
# had drifted badly in BOTH directions:
#
#   MISSING  soul, recipe, oracle, corpus — 8 recipe-callable scripts. The
#            companion's recipe-authoring prompt names scripts/soul/…,
#            scripts/recipe/library_freshness.py and
#            scripts/oracle/recalibrate_oracle.py as available helpers, so she
#            would author a recipe against a script that was never shipped and
#            get "script not found" at dispatch — on every install, both
#            platforms. Exactly the failure this loop was added to prevent.
#   EXTRA    behavior, steering-vectors, persona-moe, persona-vectors, test —
#            ~4MB of research artefacts with no recipe-callable script and no
#            runtime caller (the only code mentions of `behavior` are comments
#            citing a diagnosis note).
#
# Before adding a directory here, confirm something actually resolves it:
#   grep -rl '# recipe-callable:' scripts/<dir>
for sub in memory voice library soul recipe oracle corpus \
           items policy mcp std setup; do
    if [[ -d "$PROJECT_DIR/scripts/$sub" ]]; then
        mkdir -p "$DIST_DIR/scripts/$sub"
        cp -r "$PROJECT_DIR/scripts/$sub/." "$DIST_DIR/scripts/$sub/"
    fi
done
# Dev-machine artifacts must not ship: a stray scripts/training/.venv-*
# alone is >5GB and silently ballooned the dist to 13GB (found 2026-06-11
# while building the AIO docker image — would have hit the .deb next).
find "$DIST_DIR/scripts" -type d \( -name ".venv*" -o -name "__pycache__" \
    -o -name ".pytest_cache" \) -prune -exec rm -rf {} + 2>/dev/null || true
ok "recipe-callable script subdirs bundled (dev venvs/__pycache__ pruned)"

# Data directory placeholder
mkdir -p "$DIST_DIR/data"

# Coding-CLI bundle manifest — version pins + per-platform sha256 + download URLs
# for the coding backends (`wyrd coding` / `wyrd download-bundle`). Backends
# marked bundled:true are STAGED INTO THE DIST by the block below and gated;
# the rest are fetched on demand, so only their manifest rows ship.
if [[ -f "$PROJECT_DIR/data/coding-cli-bundle/manifest.json" ]]; then
    mkdir -p "$DIST_DIR/data/coding-cli-bundle"
    cp "$PROJECT_DIR/data/coding-cli-bundle/manifest.json" "$DIST_DIR/data/coding-cli-bundle/manifest.json"
    ok "Coding-CLI manifest bundled"
fi

# ── Stage every bundled coding backend, then PROVE it. ──────────────────────
#
# "bundled: true" in the manifest is a claim the installer's `wyrd coding
# list` repeats to the operator. For months opencode carried that claim for a
# directory no build ever staged: it listed as "(bundled)", `install` refused
# it ("no separate download needed"), and a clean machine had nothing.
# Nothing checked, so nothing failed until a task ran.
#
# So this stage does two things and the SECOND is the point:
#   1. fetch + sha-verify + extract each bundled backend that carries a
#      download_url (the manifest's own sha — build and manifest cannot
#      disagree about what a good artifact is);
#   2. HARD-GATE: after staging, every bundled:true entry must contain a
#      runnable binary in the dist. A bundled claim without a binary kills
#      the build here, on the box that made the claim — not on a household
#      machine at task time.
python3 - "$PROJECT_DIR" "$DIST_DIR" <<'BUNDLE_PY'
import hashlib, json, subprocess, sys, tarfile, pathlib

project, dist = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
manifest = json.loads((project / "data/coding-cli-bundle/manifest.json").read_text())
cache = project / "data/coding-cli-bundle/cache"
cache.mkdir(parents=True, exist_ok=True)
failures = []

def runnable(slot: pathlib.Path, name: str) -> bool:
    # Mirrors BackendExecutableResolver's bundle shapes, .bat included so the
    # same staged tree satisfies the Windows installer too.
    for c in (slot / name, slot / "bin" / name, slot / name / "bin" / name):
        for cand in (c, c.with_suffix(".bat"), c.with_suffix(".exe")):
            if cand.is_file():
                return True
    return False

for name, e in manifest["backends"].items():
    if not e.get("bundled"):
        continue
    slot = dist / "data/coding-cli-bundle" / name
    url, shas = e.get("download_url_template"), e.get("sha256_per_platform") or {}
    if url and "{" not in url:
        # Platform-independent artifact (single URL, one sha repeated per key).
        sha = next(iter(shas.values()), None)
        art = cache / url.rsplit("/", 1)[-1]
        if not (art.exists() and hashlib.sha256(art.read_bytes()).hexdigest() == sha):
            print(f"[dist] fetching bundled backend {name}: {url}")
            subprocess.run(["curl", "-fsSL", "-o", str(art), url], check=True)
        actual = hashlib.sha256(art.read_bytes()).hexdigest()
        if actual != sha:
            failures.append(f"{name}: sha mismatch (manifest {sha[:16]}.., got {actual[:16]}..)")
            continue
        slot.mkdir(parents=True, exist_ok=True)
        with tarfile.open(art) as t:
            try:
                t.extractall(slot, filter="data")
            except TypeError:  # filter= needs Python >= 3.12; macOS ships 3.9
                t.extractall(slot)
        (slot / ".version").write_text(e.get("version", ""))
    if not runnable(slot, name):
        failures.append(f"{name}: bundled:true but no runnable binary staged under {slot}")

if failures:
    print("[dist] BUNDLED-BACKEND GATE FAILED:", file=sys.stderr)
    for f in failures:
        print(f"  {f}", file=sys.stderr)
    sys.exit(1)
staged = [n for n, e in manifest["backends"].items() if e.get("bundled")]
print(f"[dist] bundled-backend gate: {', '.join(staged) or 'none'} staged and runnable")
BUNDLE_PY
[[ $? -eq 0 ]] || { err "Bundled-backend gate failed — refusing to ship a manifest that lies"; exit 1; }
ok "Bundled coding backends staged and verified"

# Track-B B1 — release-evidence dir. Produced at release-bake
# time by packaging/build-evolved-artifact.sh; first-boot CompanionActor
# scans this dir on soul birth to ingest DEXTERITY seed fragments
# attributed to did:wyrd:release-bake. Shipped as-is — every install gets
# the same evidence + the same baked-in fragments. If the dir is missing
# (dev build w/o bake) the install simply has no release-evidence; soul
# birth falls back to its existing path. Provenance lives in the .json;
# the .onnx files are already inside the JAR via core's resources.
if [[ -d "$PROJECT_DIR/data/release-evidence" ]]; then
    mkdir -p "$DIST_DIR/data/release-evidence"
    cp -r "$PROJECT_DIR/data/release-evidence/." "$DIST_DIR/data/release-evidence/"
    n=$(find "$DIST_DIR/data/release-evidence" -maxdepth 1 -name "*.json" | wc -l | tr -d ' ')
    ok "Release-evidence bundled ($n JSON artifact(s))"
fi

# License + attribution — the distributable tarball must carry the Apache-2.0
# LICENSE, the §4(d) NOTICE, and the third-party attribution inventory.
cp "$PROJECT_DIR/LICENSE" "$DIST_DIR/LICENSE"
cp "$PROJECT_DIR/NOTICE"  "$DIST_DIR/NOTICE"  2>/dev/null || true
# The third-party attribution inventory is authored in docs/public/ (the single
# public source). The root copy was a duplicate that drifted — twice — because a
# root->docs copy-forward silently reverted fixes made downstream. Fall back to
# it only for checkouts that predate the move.
_tpn_src() {
    for _c in "$PROJECT_DIR/docs/public/THIRD_PARTY_NOTICES.md" \
              "$PROJECT_DIR/THIRD_PARTY_NOTICES.md"; do
        [[ -f "$_c" ]] && { echo "$_c"; return 0; }
    done
    echo "$PROJECT_DIR/docs/public/THIRD_PARTY_NOTICES.md"
}

cp "$(_tpn_src)" "$DIST_DIR/THIRD_PARTY_NOTICES.md" 2>/dev/null || true

# FIRST_ENCOUNTER.md — three-page introduction surfaced by `wyrd setup` after
# install completes.
_FE_SRC=""
# docs/public/ first: the root and docs/ copies are private working drafts
# that still carry internal spec citations and a "not for the bondholder"
# section. Shipping those to every install is a leak, not just staleness.
for _c in "$PROJECT_DIR/docs/public/FIRST_ENCOUNTER.md" \
          "$PROJECT_DIR/docs/FIRST_ENCOUNTER.md" \
          "$PROJECT_DIR/FIRST_ENCOUNTER.md"; do
    [[ -f "$_c" ]] && { _FE_SRC="$_c"; break; }
done
if [[ -n "$_FE_SRC" ]]; then
    mkdir -p "$DIST_DIR/share"
    cp "$_FE_SRC" "$DIST_DIR/share/FIRST_ENCOUNTER.md"
    info "Bundled FIRST_ENCOUNTER.md"
fi

# Dictionary knowledge packs — jmdict (JA↔EN)
# + freedict-spa-eng (ES↔EN), pre-downloaded and format-converted via
# `wyrd library bundle` so a fresh install indexes the multilingual floor on
# first boot with NO network (BundledPackInstaller reads
# <installRoot>/share/library-bundle/). Downloads go through a persistent
# cache so rebuilds are free; fail-soft — an offline build machine just ships
# without them (the setup-prompt download path still covers those installs).
LIB_BUNDLE_CACHE="$PROJECT_DIR/build/library-bundle-cache"
if "$PROJECT_DIR/bin/wyrd" library bundle \
        --packs jmdict,freedict-spa-eng --dest "$LIB_BUNDLE_CACHE" >/dev/null 2>&1; then
    mkdir -p "$DIST_DIR/share/library-bundle"
    for pack in jmdict freedict-spa-eng; do
        if [[ -d "$LIB_BUNDLE_CACHE/$pack/chunks" ]]; then
            mkdir -p "$DIST_DIR/share/library-bundle/$pack/chunks"
            cp "$LIB_BUNDLE_CACHE/$pack/pack.json" "$DIST_DIR/share/library-bundle/$pack/" 2>/dev/null || true
            cp "$LIB_BUNDLE_CACHE/$pack/chunks/"*.jsonl "$DIST_DIR/share/library-bundle/$pack/chunks/"
        fi
    done
    n=$(find "$DIST_DIR/share/library-bundle" -name "*.jsonl" | wc -l | tr -d ' ')
    ok "Dictionary packs bundled ($n chunk file(s): jmdict + freedict-spa-eng)"
else
    warn "Dictionary pack bundle failed (offline build machine?) — installer ships without bundled dictionaries; the setup-prompt download path still covers them"
fi

# V8 voice steering vectors — control vectors that home-server's production voice
# container applies via llama-server's --control-vector-scaled flag. Without
# these the .pkg/.deb install ships a voice missing the V9 "Here." greeting
# collapse fix, first_person_presence (third-person drift), refusal_stability,
# es_register_hold, and anti_defiance. Calibration per home-server's docker default
# (Gate-3 finding — halved per-vector alphas to avoid over-steering). bin/wyrd
# start_inference resolves these at $DATA_DIR/vectors/v8/ and passes the flag.
if [[ -d "$PROJECT_DIR/data/training/v8/vectors" ]]; then
    mkdir -p "$DIST_DIR/data/vectors/v8"
    for v in anti_defiance es_register_hold refusal_stability first_person_presence; do
        if [[ -f "$PROJECT_DIR/data/training/v8/vectors/$v.gguf" ]]; then
            cp "$PROJECT_DIR/data/training/v8/vectors/$v.gguf" "$DIST_DIR/data/vectors/v8/$v.gguf"
        fi
    done
    n=$(find "$DIST_DIR/data/vectors/v8" -name "*.gguf" | wc -l | tr -d ' ')
    ok "V8 voice steering vectors bundled ($n .gguf)"
fi

# MLX-format V8 vectors. These are the
# same 4 control vectors above, pre-converted to .safetensors (per Phase
# 1A's scripts/voice/mlx_load_vectors.py). The Darwin voice runtime
# (mlx_lm.server + scripts/voice/mlx_runtime.py monkey-patch) loads these
# at start_inference. bin/wyrd find_mlx_vectors_dir resolves them from
# $DATA_DIR/training/v8/mlx/ or /usr/local/wyrdsekai/data/training/v8/mlx/.
if [[ -d "$PROJECT_DIR/data/training/v8/mlx" ]]; then
    mkdir -p "$DIST_DIR/data/training/v8/mlx"
    for v in anti_defiance es_register_hold refusal_stability first_person_presence; do
        if [[ -f "$PROJECT_DIR/data/training/v8/mlx/$v.safetensors" ]]; then
            cp "$PROJECT_DIR/data/training/v8/mlx/$v.safetensors" \
               "$DIST_DIR/data/training/v8/mlx/$v.safetensors"
        fi
        # Sidecar metadata (small, helpful for debug)
        if [[ -f "$PROJECT_DIR/data/training/v8/mlx/$v.meta.json" ]]; then
            cp "$PROJECT_DIR/data/training/v8/mlx/$v.meta.json" \
               "$DIST_DIR/data/training/v8/mlx/$v.meta.json"
        fi
    done
    n=$(find "$DIST_DIR/data/training/v8/mlx" -name "*.safetensors" | wc -l | tr -d ' ')
    ok "V8 MLX vectors bundled ($n .safetensors)"
fi

# MLX voice LaunchAgent plist (Darwin).
# postinstall substitutes __USER__/__HOME__ + installs to
# ~/Library/LaunchAgents/com.wyrdsekai.mlx-voice.plist.
if [[ -f "$PROJECT_DIR/packaging/macos/wyrdsekai-mlx-voice.plist" ]]; then
    cp "$PROJECT_DIR/packaging/macos/wyrdsekai-mlx-voice.plist" \
       "$DIST_DIR/scripts/wyrdsekai-mlx-voice.plist"
    ok "MLX voice LaunchAgent plist bundled"
fi

# ── Gate: every script a shipped recipe calls must actually be in the tree ──
#
# The staging list above has drifted twice, silently, in opposite directions —
# scripts/soul, scripts/recipe and scripts/oracle were never added when their
# recipes landed, so the companion could author a recipe against a helper she
# was told existed and get "script not found" at dispatch, on every install.
# Reviewing the list by eye is what failed. This derives the requirement from
# the recipes themselves and fails the build instead.
if command -v python3 &>/dev/null; then
    if ! python3 - "$PROJECT_DIR" "$DIST_DIR" <<'PYGATE'; then
import re, sys
from pathlib import Path

project, dist = Path(sys.argv[1]), Path(sys.argv[2])
recipes = sorted((project / "core/src/main/resources/recipes").glob("*.yaml"))
wanted = {}
for y in recipes:
    for ref in re.findall(r"scripts/[A-Za-z0-9_./-]+\.(?:py|sh|js)", y.read_text()):
        wanted.setdefault(ref, set()).add(y.name)

missing = {r: w for r, w in wanted.items() if not (dist / r).exists()}
if missing:
    print(f"[dist] RECIPE GATE: {len(missing)} script(s) a recipe calls are not staged:")
    for ref in sorted(missing):
        print(f"[dist]   {ref}  ← {', '.join(sorted(missing[ref]))}")
    print("[dist] Add the directory to the staging loop above, or drop the recipe.")
    sys.exit(1)
print(f"[dist] recipe gate: {len(wanted)} script(s) across {len(recipes)} recipes all present")
PYGATE
        err "Recipe gate failed — refusing to ship an installer with dead recipes"
        exit 1
    fi
fi

# Version file
echo "$VERSION" > "$DIST_DIR/VERSION"

# ── Redact household identifiers ──
#
# A build machine leaves traces of itself in the tree: hostnames in comments,
# home paths in sample config, account names in test fixtures. Packaging is a
# distribution step in its own right, so it does its own pass rather than
# assuming some earlier stage cleaned up.
#
# Runs here, on the staged tree, so .deb / .pkg / .msi all inherit it — and
# before the tarball is rolled, so the source archive is covered too.
# The step is conditional on a local identifier map being configured. Without
# one there is nothing to substitute and the build proceeds unchanged, which is
# the ordinary case. With one, a failure here is fatal: a half-redacted package
# is worse than a build that stopped and said so.
if [[ -f "$PROJECT_DIR/scripts/lib/oss_redact.py" ]]; then
    if [[ ! -f "$PROJECT_DIR/scripts/lib/dist_redact.py" ]] || ! command -v python3 &>/dev/null; then
        err "An identifier map is configured but dist_redact.py or python3 is"
        err "missing. Refusing to package without applying it."
        exit 1
    fi
    info "Applying identifier substitutions to the staged tree..."
    if python3 "$PROJECT_DIR/scripts/lib/dist_redact.py" "$DIST_DIR"; then
        ok "Staged tree processed"
    else
        err "Substitution FAILED — refusing to package a partially processed tree"
        exit 1
    fi
fi

# ── Create tar.gz ──
info "Creating archive..."
cd "$PROJECT_DIR/build/dist"
tar czf "${DIST_NAME}.tar.gz" "$DIST_NAME"

ok "Distribution: build/dist/${DIST_NAME}.tar.gz"
du -sh "$PROJECT_DIR/build/dist/${DIST_NAME}.tar.gz"

# Also report directory size
du -sh "$DIST_DIR"
echo ""
echo "Contents:"
echo "  bin/     — Launcher scripts (wyrd, wyrdsekai-server, wyrdsekai-cli, wyrdsekai-daemon)"
echo "  lib/     — server: $SERVER_JARS, cli: $CLI_JARS, daemon: $DAEMON_JARS JARs"
echo "  etc/     — Configuration templates"
echo "  rooms/   — Room scripts"
echo "  docker/  — Docker Compose files"
echo "  scripts/ — Operational scripts"
echo "  data/    — Data directory"
