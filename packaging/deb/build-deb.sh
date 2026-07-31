#!/usr/bin/env bash
#
# build-deb.sh — Build a .deb package for Wyrdsekai
#
# Installs to /opt/wyrdsekai with symlinks in /usr/local/bin.
# Requires: dpkg-deb (apt install dpkg-dev)
#
# Usage:
#   ./packaging/deb/build-deb.sh               # Uses build/dist/wyrdsekai-<version>/
#   WYRDSEKAI_VERSION=0.2.0 ./packaging/deb/build-deb.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGING_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_DIR="$(dirname "$PACKAGING_DIR")"
VERSION="${WYRDSEKAI_VERSION:-0.1.2}"
ARCH="${WYRDSEKAI_ARCH:-amd64}"  # amd64 or arm64
DIST_NAME="wyrdsekai-${VERSION}"
DIST_DIR="$PROJECT_DIR/build/dist/$DIST_NAME"
DEB_NAME="wyrdsekai_${VERSION}_${ARCH}"
DEB_ROOT="$PROJECT_DIR/build/deb/$DEB_NAME"

info()  { echo -e "\033[36m[deb]\033[0m $*"; }
ok()    { echo -e "\033[32m[deb]\033[0m $*"; }
err()   { echo -e "\033[31m[deb]\033[0m $*" >&2; }

# ── Prerequisite check ──
if ! command -v dpkg-deb &>/dev/null; then
    err "dpkg-deb not found. Install: sudo apt install dpkg-dev"
    exit 1
fi

# ── Serialise with other build-dir consumers ──
# Session 2026-04-22: a concurrent `./gradlew :server:installDist` on the
# same checkout (from a parallel ssh rebuild) wiped build/install/server
# between this script's dist-copy and dpkg-deb's pack step — resulting in
# a 2.5KB .deb with only control scripts and no lib/ payload. flock the
# build dir for the duration of this script.
mkdir -p "$PROJECT_DIR/build"
LOCK_FD=9
LOCK_FILE="$PROJECT_DIR/build/.build-deb.lock"
exec 9>"$LOCK_FILE"
if ! flock -w 600 -x "$LOCK_FD"; then
    err "Timed out waiting for build/.build-deb.lock — another build is holding it."
    exit 1
fi

# ── Build dist ──
# Always rebuild: build-dist.sh runs a quiet gradle build and Gradle's up-to-date
# checks skip if nothing changed. A stale dist directory from an earlier checkout
# would otherwise be reused and package stale JARs. (Set WYRDSEKAI_SKIP_DIST=1 to reuse.)
if [[ "${WYRDSEKAI_SKIP_DIST:-0}" != "1" ]] || [[ ! -d "$DIST_DIR" ]]; then
    info "Building distribution..."
    "$PACKAGING_DIR/build-dist.sh"
fi

if [[ ! -d "$DIST_DIR/lib" ]]; then
    err "Distribution missing lib/ directory at $DIST_DIR"
    exit 1
fi

# ── Clean previous build ──
rm -rf "$DEB_ROOT"

# ── Create directory structure ──
# Application files → /opt/wyrdsekai
mkdir -p "$DEB_ROOT/opt/wyrdsekai"
cp -r "$DIST_DIR/lib"     "$DEB_ROOT/opt/wyrdsekai/lib"

# Classifier heads are gated in build-dist.sh, which runs before this and is
# the common ancestor of all three installers — the .deb, the .pkg and the
# .msi all stage from DIST_DIR. Duplicating the check here would just be a
# second copy to drift.
# Release model index (data-durability, 2026-07-09) — `wyrd model` reads /opt/wyrdsekai/models-index.json
cp "$DIST_DIR/models-index.json" "$DEB_ROOT/opt/wyrdsekai/models-index.json" 2>/dev/null || true
cp -r "$DIST_DIR/bin"     "$DEB_ROOT/opt/wyrdsekai/bin"
cp -r "$DIST_DIR/etc"     "$DEB_ROOT/opt/wyrdsekai/etc"
cp -r "$DIST_DIR/docker"  "$DEB_ROOT/opt/wyrdsekai/docker"  2>/dev/null || true
cp -r "$DIST_DIR/scripts" "$DEB_ROOT/opt/wyrdsekai/scripts" 2>/dev/null || true
cp -r "$DIST_DIR/rooms"   "$DEB_ROOT/opt/wyrdsekai/rooms"   2>/dev/null || true
# Classifier bootstrap seeds + pretrained heads (audit 2026-07-11): the recipe
# steps shell out to core/src/main/resources/... relative to WorkingDirectory
# (/opt/wyrdsekai). pkg and msi both stage this; the deb never did — every
# classifier retrain recipe on a fresh deb failed at its first SHELL step.
cp -r "$DIST_DIR/core"    "$DEB_ROOT/opt/wyrdsekai/core"    2>/dev/null || true
# OSS license — every installer must carry it, plus the Apache-2.0 §4(d)
# NOTICE and the third-party attribution inventory.
cp    "$PROJECT_DIR/LICENSE" "$DEB_ROOT/opt/wyrdsekai/LICENSE"
cp    "$PROJECT_DIR/NOTICE"  "$DEB_ROOT/opt/wyrdsekai/NOTICE"  2>/dev/null || true
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

cp    "$(_tpn_src)" "$DEB_ROOT/opt/wyrdsekai/THIRD_PARTY_NOTICES.md" 2>/dev/null || true
cp    "$DIST_DIR/VERSION"  "$DEB_ROOT/opt/wyrdsekai/VERSION"
# Root docker-compose.yml (used by wyrd setup for Searxng + NATS)
cp    "$DIST_DIR/docker-compose.yml" "$DEB_ROOT/opt/wyrdsekai/docker-compose.yml" 2>/dev/null || true

# Bundled binaries — nats-server, metasearch, and llama-server (no Docker required)
# llama-server gives CPU-only / no-Docker hosts (laptops, AVX-512 desktops without GPUs)
# a first-class inference path. Fetch with packaging/fetch-llama-cpu.sh when absent.
for bin_name in nats-server metasearch llama-server; do
    if [[ -f "$PACKAGING_DIR/$bin_name" ]]; then
        cp "$PACKAGING_DIR/$bin_name" "$DEB_ROOT/opt/wyrdsekai/bin/$bin_name"
        chmod +x "$DEB_ROOT/opt/wyrdsekai/bin/$bin_name"
        info "Bundled: $bin_name ($(du -sh "$PACKAGING_DIR/$bin_name" | cut -f1))"
    else
        if [[ "$bin_name" == "llama-server" ]]; then
            info "NOTE: llama-server not bundled — run packaging/fetch-llama-cpu.sh first for CPU-only nodes"
        else
            info "WARN: $bin_name not found in $PACKAGING_DIR — skipping bundle"
        fi
    fi
done

# FIRST_ENCOUNTER.md — three-page introduction surfaced by `wyrd setup` after
# install completes. the bondholder reads
# this BEFORE their first turn with their companion. Bundled into
# /opt/wyrdsekai/share/ where `wyrd setup` looks for it.
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
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/share"
    cp "$_FE_SRC" "$DEB_ROOT/opt/wyrdsekai/share/FIRST_ENCOUNTER.md"
    info "Bundled FIRST_ENCOUNTER.md"
fi

# Dictionary library bundle — staged into the
# dist by build-dist.sh; BundledPackInstaller indexes it from
# /opt/wyrdsekai/share/library-bundle/ at first boot, no network needed.
if [[ -d "$DIST_DIR/share/library-bundle" ]]; then
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/share/library-bundle"
    cp -r "$DIST_DIR/share/library-bundle/." "$DEB_ROOT/opt/wyrdsekai/share/library-bundle/"
    info "Bundled dictionary library ($(find "$DEB_ROOT/opt/wyrdsekai/share/library-bundle" -name '*.jsonl' | wc -l | tr -d ' ') chunk files)"
fi

# Embedding-model assets — paraphrase-l12 ONNX + tokenizer, ~130MB total.
# Fetched by packaging/fetch-embedding-models.sh (NOT checked into git; build-dist
# runs it). Staged under /opt/wyrdsekai/share/embedding-models/, which is tier 2 of
# the three-tier resolver in `bin/wyrd` (present → bundled → HuggingFace).
#
# A MISSING BUNDLE IS A BUILD FAILURE, not a note.
#
# This used to print "NOTE: embedding-models not bundled ... setup will fall back to
# HF download" and carry on. Nothing populated the directory, so that branch fired on
# every build, the note scrolled past in a wall of output, and we shipped installers
# whose "bundled default" was actually a runtime download from a third party. It went
# unnoticed for months because HuggingFace served the file. When HF moved these assets
# behind its Xet CDN — which answers 403 to curl — a fresh install came up with
# retrieval silently disabled.
#
# The failure has to be at build time, where we can fix it, not at install time on
# someone else's machine. Ship no installer that cannot do what it claims.
EMB_SRC="$PACKAGING_DIR/embedding-models"
EMB_REQUIRED=(
    "paraphrase-multilingual-MiniLM-L12-v2-q8.onnx"
    "paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json"
)
EMB_MISSING=()
for f in "${EMB_REQUIRED[@]}"; do
    [[ -s "$EMB_SRC/$f" ]] || EMB_MISSING+=("$f")
done
if (( ${#EMB_MISSING[@]} > 0 )); then
    err "Embedding model (the BUNDLED default) is missing: ${EMB_MISSING[*]}"
    err "Expected in: $EMB_SRC"
    err "Run: ./packaging/fetch-embedding-models.sh"
    err "Refusing to build an installer whose bundled default is not bundled — it would"
    err "fall back to a HuggingFace download at 'wyrd setup', which currently 403s."
    exit 1
fi
mkdir -p "$DEB_ROOT/opt/wyrdsekai/share/embedding-models"
for f in "$EMB_SRC"/*; do
    [[ -f "$f" ]] || continue
    cp "$f" "$DEB_ROOT/opt/wyrdsekai/share/embedding-models/"
    info "Bundled embedding asset: $(basename "$f") ($(du -sh "$f" | cut -f1))"
done

# Shared libraries that ship alongside llama-server in the release archive
# (libllama.so, libggml*.so, libmtmd*.so, etc.).
#
# CRITICAL: llama.cpp's ggml_backend_load_all() searches the EXE'S DIRECTORY
# for backend .so files — NOT LD_LIBRARY_PATH. An earlier attempt to put libs
# under /opt/wyrdsekai/lib/llama/ + LD_LIBRARY_PATH failed at runtime with
# "no backends are loaded" because the loader never looks at env paths.
# So: libs must live in the same directory as the binary.
for lib in "$PACKAGING_DIR"/libllama*.so* "$PACKAGING_DIR"/libggml*.so* "$PACKAGING_DIR"/libmtmd*.so*; do
    [[ -f "$lib" ]] || continue
    cp -a "$lib" "$DEB_ROOT/opt/wyrdsekai/bin/"
    info "Bundled llama lib: $(basename "$lib")"
done

# NATS config for bundled nats-server
mkdir -p "$DEB_ROOT/opt/wyrdsekai/etc"
# ── Oracle prediction-engine wheel (oracle-core) ──
# Bundle the prebuilt oracle-core wheel so native installs can run the Oracle
# forecasting sidecar — not just docker-compose. Staged under
# /opt/wyrdsekai/share/oracle/ where `wyrd oracle bootstrap` (run by postinst)
# pip-installs it into /var/lib/wyrdsekai/.venv-oracle. Light base deps
# (flask/scikit-learn/pandas/numpy/scipy); heavy forecasting extras stay opt-in.
ORACLE_WHL="$PACKAGING_DIR/oracle"
# HARD gate. This was `if present; then copy; else print a note; fi`, and the
# note scrolled past in a 20-minute build: the 0.1.0 packages shipped with no
# wheel at all, so /v1/train was simply absent from a "successful" build. A
# payload the product needs is not optional — if it is missing, the build is
# wrong and must say so by failing.
if ! compgen -G "$ORACLE_WHL/oracle_core-*.whl" >/dev/null 2>&1; then
    err "oracle-core wheel MISSING from $ORACLE_WHL"
    err "  The Oracle sidecar (/v1/train, /v1/predict) cannot work without it."
    err "  It is tracked in git at packaging/oracle/ — a clean checkout has it."
    err "  If you deliberately want a wheel-less package, set WYRDSEKAI_NO_ORACLE=1."
    [[ -n "${WYRDSEKAI_NO_ORACLE:-}" ]] || exit 1
fi
if compgen -G "$ORACLE_WHL/oracle_core-*.whl" >/dev/null 2>&1; then
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/share/oracle"
    cp "$ORACLE_WHL"/oracle_core-*.whl "$DEB_ROOT/opt/wyrdsekai/share/oracle/"
    info "Bundled oracle-core wheel for native Oracle sidecar"
fi

cat > "$DEB_ROOT/opt/wyrdsekai/etc/nats.conf" << 'NATSCONF'
listen: 0.0.0.0:4222
max_payload: 65536

# JetStream disabled by default (enable for Between persistence)
# jetstream { store_dir: /var/lib/wyrdsekai/jetstream }

# WebSocket listener for mobile clients (NATS-over-WS, G2 2026-07-11)
websocket {
  listen: "0.0.0.0:4223"
  no_tls: true
}
NATSCONF

# Data directory (marked as config so upgrades preserve it)
mkdir -p "$DEB_ROOT/opt/wyrdsekai/data"

# Coding-CLI bundle manifest (optional backends; binaries fetched on demand).
# bin/wyrd resolves it from /opt/wyrdsekai/data/coding-cli-bundle/manifest.json.
if [[ -f "$DIST_DIR/data/coding-cli-bundle/manifest.json" ]]; then
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/data/coding-cli-bundle"
    cp "$DIST_DIR/data/coding-cli-bundle/manifest.json" "$DEB_ROOT/opt/wyrdsekai/data/coding-cli-bundle/manifest.json"
fi

# Track-B B1 — release-evidence dir (recipe-run logs + soul-fragment
# seeds produced at release-bake time). CompanionActor scans this dir on soul
# birth to ingest DEXTERITY fragments attributed to did:wyrd:release-bake.
if [[ -d "$DIST_DIR/data/release-evidence" ]]; then
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/data/release-evidence"
    cp -r "$DIST_DIR/data/release-evidence/." "$DEB_ROOT/opt/wyrdsekai/data/release-evidence/"
fi

# V8 voice steering vectors — the wyrdsekai-llama-voice systemd unit below
# reads WYRDSEKAI_V8_VECTORS_DIR=/opt/wyrdsekai/data/vectors/v8. build-dist
# stages them at data/vectors/v8 but this script never copied them into the
# package, so a fresh .deb shipped an UNSTEERED voice (the unit's env pointed
# at a directory the package never installed — same class as the .msi
# unsteered-voice gap fixed 2026-06-29; masked on home-server because the files
# existed on disk). Found 2026-07-01 via tar-listing the built .deb.
if [[ -d "$DIST_DIR/data/vectors/v8" ]]; then
    mkdir -p "$DEB_ROOT/opt/wyrdsekai/data/vectors/v8"
    cp -r "$DIST_DIR/data/vectors/v8/." "$DEB_ROOT/opt/wyrdsekai/data/vectors/v8/"
fi

# ── Desktop GUI app (the friendly front door, #1286) ──
# Python/GTK tray + onboarding wizard + settings editor. No compile step — ship
# the .py + privileged helper scripts + .desktop launchers. GUI runtime deps are
# Recommends (control file below) so headless server installs aren't forced to
# pull GTK.
DESK_SRC="$PACKAGING_DIR/deb/desktop"
mkdir -p "$DEB_ROOT/opt/wyrdsekai/desktop/helpers"
cp "$DESK_SRC/wyrdsekai_desktop.py" "$DEB_ROOT/opt/wyrdsekai/desktop/wyrdsekai_desktop.py"
chmod 755 "$DEB_ROOT/opt/wyrdsekai/desktop/wyrdsekai_desktop.py"
for h in gui-onboard.sh gui-apply-settings.sh gui-uninstall.sh; do
    cp "$DESK_SRC/helpers/$h" "$DEB_ROOT/opt/wyrdsekai/desktop/helpers/$h"
    chmod 755 "$DEB_ROOT/opt/wyrdsekai/desktop/helpers/$h"
done
mkdir -p "$DEB_ROOT/usr/share/applications" "$DEB_ROOT/etc/xdg/autostart"
cp "$DESK_SRC/wyrdsekai.desktop"           "$DEB_ROOT/usr/share/applications/wyrdsekai.desktop"
cp "$DESK_SRC/wyrdsekai-settings.desktop"  "$DEB_ROOT/usr/share/applications/wyrdsekai-settings.desktop"
cp "$DESK_SRC/wyrdsekai-autostart.desktop" "$DEB_ROOT/etc/xdg/autostart/wyrdsekai.desktop"
info "Staged desktop GUI app + launchers"

# Symlinks in /usr/local/bin
mkdir -p "$DEB_ROOT/usr/local/bin"
# Symlinks will be created by postinst to avoid dpkg issues

# Systemd system services (not user — needs to run as system for NATS)
mkdir -p "$DEB_ROOT/usr/lib/systemd/system"

# NATS service (bundled)
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai-nats.service" << EOF
[Unit]
Description=Wyrdsekai NATS Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/wyrdsekai/bin/nats-server -c /opt/wyrdsekai/etc/nats.conf
Restart=on-failure
RestartSec=5
User=nobody
Group=nogroup

[Install]
WantedBy=multi-user.target
EOF

# metasearch service (bundled, fallback web search)
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai-metasearch.service" << EOF
[Unit]
Description=Wyrdsekai Metasearch (web search proxy)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/wyrdsekai/bin/metasearch
Restart=on-failure
RestartSec=5
User=nobody
Group=nogroup

[Install]
WantedBy=multi-user.target
EOF

# llama-server service (bundled CPU build). Disabled by default — enabled on
# demand by `wyrd inference local`. Model path + port come from the drop-in
# that `wyrd inference local` writes at /etc/systemd/system/wyrdsekai-llama.service.d/.
# Default port 11525 matches WYRDSEKAI_INFERENCE_URL=http://127.0.0.1:11525.
#
# WorkingDirectory=/opt/wyrdsekai/bin ensures llama.cpp's ggml_backend_load_all()
# finds the backend .so files (it searches the exe's directory, not PATH/LD_LIBRARY_PATH).
# Model files under /opt/wyrdsekai/data/models must be world-readable (the
# postinst sets this; keep in sync if the perm model changes).
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai-llama.service" << 'EOF'
[Unit]
Description=Wyrdsekai llama-server (CPU inference)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Environment=WYRDSEKAI_LLAMA_PORT=11525
Environment=WYRDSEKAI_LLAMA_HOST=127.0.0.1
Environment=WYRDSEKAI_LLAMA_MODEL=/opt/wyrdsekai/data/models/wyrdsekai-3.5-4b-v10-q4km.gguf
Environment=WYRDSEKAI_LLAMA_CTX=8192
Environment=WYRDSEKAI_LLAMA_THREADS=auto
# V8 voice steering vectors — must match home-server's docker default (Gate-3 calibration).
# Vectors bundled at /opt/wyrdsekai/data/vectors/v8/ by .deb postinstall. Skip
# silently if any are missing. Override the comma-sep "file:scale" list via
# WYRDSEKAI_V8_VECTORS to disable, retune, or add factual_recall_anchor /
# inline_creative when those are validated for stacking.
Environment=WYRDSEKAI_V8_VECTORS=anti_defiance.gguf:0.15,es_register_hold.gguf:0.20,refusal_stability.gguf:0.20,first_person_presence.gguf:0.15
Environment=WYRDSEKAI_V8_VECTORS_DIR=/opt/wyrdsekai/data/vectors/v8
WorkingDirectory=/opt/wyrdsekai/bin
ExecStart=/bin/sh -c '\
    threads="$WYRDSEKAI_LLAMA_THREADS"; \
    if [ "$threads" = "auto" ]; then threads=$(nproc 2>/dev/null || echo 4); fi; \
    V8_CSV=""; \
    if [ -d "$WYRDSEKAI_V8_VECTORS_DIR" ] && [ -n "$WYRDSEKAI_V8_VECTORS" ]; then \
        IFS=","; for pair in $WYRDSEKAI_V8_VECTORS; do \
            f="${pair%%:*}"; s="${pair##*:}"; \
            if [ -f "$WYRDSEKAI_V8_VECTORS_DIR/$f" ]; then \
                if [ -z "$V8_CSV" ]; then V8_CSV="$WYRDSEKAI_V8_VECTORS_DIR/$f:$s"; \
                else V8_CSV="$V8_CSV,$WYRDSEKAI_V8_VECTORS_DIR/$f:$s"; fi; \
            fi; \
        done; unset IFS; \
    fi; \
    EXTRA=""; \
    if [ -n "$V8_CSV" ]; then EXTRA="--control-vector-scaled $V8_CSV"; \
        echo "[wyrdsekai-llama] V8 vectors: $V8_CSV"; \
    else \
        echo "[wyrdsekai-llama] WARNING: no V8 vectors found at $WYRDSEKAI_V8_VECTORS_DIR"; \
    fi; \
    exec /opt/wyrdsekai/bin/llama-server \
        --host "$WYRDSEKAI_LLAMA_HOST" \
        --port "$WYRDSEKAI_LLAMA_PORT" \
        --model "$WYRDSEKAI_LLAMA_MODEL" \
        --ctx-size "$WYRDSEKAI_LLAMA_CTX" \
        --threads "$threads" \
        --jinja \
        --reasoning off \
        --reasoning-budget 0 \
        $EXTRA'
Restart=on-failure
RestartSec=5
User=nobody
Group=nogroup

[Install]
WantedBy=multi-user.target
EOF

# Main Wyrdsekai server service
# Note: no dependency on wyrdsekai-nats.service — BetweenActor spawns its own
# embedded nats-server. The bundled wyrdsekai-nats.service exists only for
# nodes that want NATS standalone (no Java server), and would collide on
# port 4222 if both were running.
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai.service" << 'EOF'
[Unit]
Description=Wyrdsekai Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/wyrdsekai/bin/wyrdsekai-server
# JVMs exit 143 on SIGTERM (128+15); without this a clean `wyrd stop` shows as
# "Failed with result 'exit-code'" in systemctl status (second-node 2026-07-09).
SuccessExitStatus=143
# Anchor CWD to the install root. systemd's default CWD is /, where every
# CWD-relative payload lookup (scripts/items, rooms dev-fallbacks) resolves to
# nothing — ScriptedItemLoader booted with "0 item(s) loaded from 0 dir(s)"
# and every furnishing played dead (second-node 2026-07-04). The launcher also cd's
# and exports WYRDSEKAI_HOME; unit-level settings are the belt to that
# suspenders so a future launcher edit can't silently regress this.
WorkingDirectory=/opt/wyrdsekai
Environment=WYRDSEKAI_HOME=/opt/wyrdsekai
Restart=on-failure
RestartSec=5
Environment=JAVA_OPTS=-Xmx2g
Environment=PATH=/opt/wyrdsekai/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# SystemPaths canonical data dir — /var/lib/wyrdsekai for all .deb installs.
# Without this the service falls back to /root/.wyrdsekai via user.home and
# the CLI (which runs as the invoking user) reads /home/$USER/.wyrdsekai.
# That silent split stole hours of debugging before we made it explicit.
Environment=WYRDSEKAI_DATA_DIR=/var/lib/wyrdsekai
Environment=WYRDSEKAI_SERVICE_MODE=true
# Pin HOME so Java's user.home is deterministic (/root). Paired with the
# postinst symlink /root/.wyrdsekai -> /var/lib/wyrdsekai, this makes the ~20
# code paths that hardcode `user.home/.wyrdsekai` (profile.toml, models, souls,
# adapters, zone-aesthetic, …) resolve to the canonical data dir instead of a
# divergent /root/.wyrdsekai. Closes the config-split class (second-node 2026-07-02).
Environment=HOME=/root
# SINGLE-CANONICAL-CONF INVARIANT: /etc/wyrdsekai/wyrdsekai.conf is the ONLY
# config file the systemd service reads. There is deliberately no second
# EnvironmentFile (e.g. /var/lib/wyrdsekai/env): a node-state env file the
# service silently ignored is exactly the "competing conf files" split that
# bit a live CPU node. The CLI must read AND write this same file —
# `wyrd config set`, `wyrd relay`, `wyrd join` all target it (and bin/wyrd
# source-loads it), so the operator's view and the running service never
# disagree about config. Minus-prefix makes it optional so fresh installs
# before 'wyrd config' runs still boot off the postinst skeleton below.
EnvironmentFile=-/etc/wyrdsekai/wyrdsekai.conf

[Install]
WantedBy=multi-user.target
EOF

# Zone directory rendezvous — separate systemd unit
# from the tunnel relay so a tunnel-side outage can't take directory down.
# Disabled by default; operators enable on nodes that want to aggregate
# (relay hosts, community-run directory nodes).
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai-rendezvous.service" << EOF
[Unit]
Description=Wyrdsekai Rendezvous — zone directory aggregator
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/wyrdsekai/bin/wyrd-rendezvous
Restart=on-failure
RestartSec=5
Environment=JAVA_OPTS=-Xms64m -Xmx512m
Environment=PATH=/opt/wyrdsekai/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MemoryMax=512M
LimitNOFILE=8192

[Install]
WantedBy=multi-user.target
EOF

# ── DEBIAN control files ──
mkdir -p "$DEB_ROOT/DEBIAN"

# Control file
# Oracle forecasting sidecar (oracle-core). Runs the bundled wheel's
# oracle-server on :7073; the Java zone auto-connects on health. The venv is
# created by postinst (wyrd oracle bootstrap). Enabled by postinst.
cat > "$DEB_ROOT/usr/lib/systemd/system/wyrdsekai-oracle.service" << 'EOF'
[Unit]
Description=Wyrdsekai Oracle (prediction / forecasting engine)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Environment=ORACLE_PORT=7073
Environment=WYRDSEKAI_DATA_DIR=/var/lib/wyrdsekai
# Guard: only run if the venv was bootstrapped (online install). Air-gapped
# installs that skipped pip stay inert until `wyrd oracle bootstrap` runs.
ExecStartPre=/bin/sh -c 'test -x /var/lib/wyrdsekai/.venv-oracle/bin/oracle-server'
ExecStart=/var/lib/wyrdsekai/.venv-oracle/bin/oracle-server --port 7073 --host 127.0.0.1 --data-dir /var/lib/wyrdsekai/oracle
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

cat > "$DEB_ROOT/DEBIAN/control" << EOF
Package: wyrdsekai
Version: ${VERSION}
Section: misc
Priority: optional
Architecture: ${ARCH}
Depends: default-jre-headless (>= 2:1.25) | openjdk-25-jre-headless
Recommends: docker.io | docker-ce | podman, python3-gi, gir1.2-gtk-3.0, gir1.2-ayatanaappindicator3-0.1 | gir1.2-appindicator3-0.1, policykit-1 | polkit, xdg-utils
Maintainer: Wyrdsekai Project <hello@wyrdsekai.org>
Description: Distributed text-native world engine
 Wyrdsekai is a distributed text-native OS built on the MUD paradigm.
 AI agents and humans coexist in a shared programmable world.
 .
 Includes the server, CLI client, and inference daemon.
Homepage: https://wyrdsekai.org
EOF

# postinst — create symlinks, state dirs, enable bundled services
cat > "$DEB_ROOT/DEBIAN/postinst" << 'EOF'
#!/bin/sh
set -e

# Pre-upgrade data snapshot (data-durability, 2026-07-09): on UPGRADE ($2 = previously
# installed version), copy the databases aside BEFORE the new binary's schema migrations
# touch them. Cheap insurance against a bad migration; pruned to the 3 newest snapshots.
# The service is stopped during upgrade, so the copy is consistent.
if [ -n "$2" ] && [ -d /var/lib/wyrdsekai ]; then
    ts=$(date +%Y%m%d-%H%M%S)
    snap="/var/lib/wyrdsekai/backups/pre-upgrade-$2-$ts"
    if ls /var/lib/wyrdsekai/*.db >/dev/null 2>&1; then
        mkdir -p "$snap"
        cp /var/lib/wyrdsekai/*.db "$snap/" 2>/dev/null || true
        cp /var/lib/wyrdsekai/data-version.json "$snap/" 2>/dev/null || true
        echo "wyrdsekai: pre-upgrade snapshot of databases at $snap"
        ls -dt /var/lib/wyrdsekai/backups/pre-upgrade-* 2>/dev/null | tail -n +4 | xargs -r rm -rf
    fi
fi

# Create symlinks
ln -sf /opt/wyrdsekai/bin/wyrd /usr/local/bin/wyrd
ln -sf /opt/wyrdsekai/bin/wyrdsekai-server /usr/local/bin/wyrdsekai-server
ln -sf /opt/wyrdsekai/bin/wyrdsekai-cli /usr/local/bin/wyrdsekai-cli
ln -sf /opt/wyrdsekai/bin/wyrdsekai-daemon /usr/local/bin/wyrdsekai-daemon
# Desktop GUI launcher (#1286) — `wyrdsekai-desktop [--settings|--world]`.
ln -sf /opt/wyrdsekai/desktop/wyrdsekai_desktop.py /usr/local/bin/wyrdsekai-desktop
# Refresh the application menu so the launchers show without re-login.
update-desktop-database -q 2>/dev/null || true

# Canonical state + config dirs (Phase 1 + Phase 3). /var/lib follows FHS,
# /etc/wyrdsekai holds the single config file the systemd unit reads.
mkdir -p /var/lib/wyrdsekai /etc/wyrdsekai
chmod 755 /var/lib/wyrdsekai /etc/wyrdsekai

# If there is no config file yet, drop a skeleton so `wyrd config list`
# has something to show and admins know where to edit.
if [ ! -f /etc/wyrdsekai/wyrdsekai.conf ]; then
    cat > /etc/wyrdsekai/wyrdsekai.conf << CONF
# Wyrdsekai configuration — read by systemd at service start.
# Lines are KEY=VALUE. Reload with: wyrd restart   (or: wyrd config apply)
#
# Set by the .deb (do not change):
#   WYRDSEKAI_DATA_DIR=/var/lib/wyrdsekai  (in systemd unit)
#   WYRDSEKAI_SERVICE_MODE=true            (in systemd unit)
#
# Set by you via 'wyrd config set KEY=VALUE' or by editing this file:
WYRDSEKAI_BETWEEN_ENABLED=true
# Strict chat templates (Qwen3.5 9B, some Llama variants) reject multiple
# consecutive system messages; we merge them before sending. Leave 'true'
# unless your inference backend handles system-run fragmentation natively.
WYRDSEKAI_MERGE_SYSTEM_MESSAGES=true
# WYRDSEKAI_ZONE_ID=home
# WYRDSEKAI_INFERENCE_URL=http://localhost:8200
# WYRDSEKAI_RELAY_URL=nats://relay.example:4222
# WYRDSEKAI_RELAY_TOKEN=
# WYRDSEKAI_RENDEZVOUS_URLS=http://rendezvous.example:7071
# Federation auto-accept: trust any zone that reaches you over the relay.
# Appropriate only for test meshes and single-owner households where every
# peer is known. Leave commented (default: manual 'wyrd federate accept <zone>')
# for anything that talks to strangers.
# WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true
CONF
    chmod 644 /etc/wyrdsekai/wyrdsekai.conf
fi

# Migrate legacy /opt/wyrdsekai/data on upgrade so old installs don't lose
# state. Best-effort; the admin can wyrd restore from backup if this fails.
if [ -d /opt/wyrdsekai/data ] && [ ! -f /var/lib/wyrdsekai/world.db ]; then
    for f in world.db world.db-shm world.db-wal library.db library.db-wal \
             library.db-shm contacts env node-identity.json ssh_host_key; do
        if [ -e "/opt/wyrdsekai/data/$f" ]; then
            mv "/opt/wyrdsekai/data/$f" "/var/lib/wyrdsekai/$f" 2>/dev/null || true
        fi
    done
    for d in souls packs search vault data backups; do
        if [ -d "/opt/wyrdsekai/data/$d" ] && [ ! -d "/var/lib/wyrdsekai/$d" ]; then
            mv "/opt/wyrdsekai/data/$d" "/var/lib/wyrdsekai/$d" 2>/dev/null || true
        fi
    done
fi

# Model dir — world-readable so wyrdsekai-llama.service (running as
# `nobody`) can load GGUFs dropped there by `wyrd inference local`.
mkdir -p /var/lib/wyrdsekai/models
chmod 755 /var/lib/wyrdsekai/models

# Oracle prediction engine — install the bundled wheel into a venv so the
# forecasting sidecar runs natively (not just under Docker). Best-effort:
# needs network for base deps; air-gapped installs stay inert until a later
# `wyrd oracle bootstrap`. The Java zone auto-connects when :7073 is healthy.
mkdir -p /var/lib/wyrdsekai/oracle
if [ -d /opt/wyrdsekai/share/oracle ]; then
    WYRDSEKAI_DATA_DIR=/var/lib/wyrdsekai \
    WYRDSEKAI_ORACLE_WHEEL_DIRS=/opt/wyrdsekai/share/oracle \
        /usr/local/bin/wyrd oracle bootstrap 2>/dev/null || \
        echo "[wyrdsekai] oracle bootstrap deferred (no network?) — run 'wyrd oracle bootstrap' later"
fi

# Reload systemd unit cache. BetweenActor spawns its own embedded nats-server
# when the main wyrdsekai service starts, so we do NOT enable wyrdsekai-nats
# by default — it exists only for standalone-NATS deployments and would
# collide on port 4222 with the embedded server. wyrdsekai-llama is also
# disabled by default — `wyrd inference local` enables it on demand.
systemctl daemon-reload 2>/dev/null || true
# Defensive: Phase 1 cleanup in case an old install left nats masked.
systemctl unmask wyrdsekai-nats 2>/dev/null || true

# Deliberately NOT enabled/started here. The server spawns its own nats-server
# (NatsServerManager) from a *generated* /var/lib/wyrdsekai/nats.conf carrying
# the household accounts and per-session ACLs. This unit uses the packaged
# /opt/wyrdsekai/etc/nats.conf, which has neither. Since NatsServerManager
# reuses any healthy :4222 rather than replacing it, enabling this unit would
# make the zone come up on the un-scoped config after every reboot. It exists
# for operators who deliberately run NATS externally.

# Oracle forecasting sidecar — enable + start by default so the Oracle is
# available on native installs. The unit's ExecStartPre guards against a
# missing venv (air-gapped installs that skipped pip stay inert harmlessly).
systemctl enable wyrdsekai-oracle 2>/dev/null || true
systemctl start wyrdsekai-oracle 2>/dev/null || true

# F4 phase 2: mint a one-time steward-bootstrap invite. The operator can
# `ssh steward@host -p 7022` with this code as password to register the
# first account. The file is mode 0600 (only root + steward user can read).
# Skipped if any users already exist (re-installs preserve state).
BOOTSTRAP_INVITE=/etc/wyrdsekai/steward-bootstrap.invite
if [ ! -f "$BOOTSTRAP_INVITE" ] && command -v wyrd >/dev/null 2>&1; then
    if CODE=$(wyrd invite bootstrap 2>/dev/null); then
        umask 077
        printf '%s\n' "$CODE" > "$BOOTSTRAP_INVITE"
        chmod 600 "$BOOTSTRAP_INVITE"
        echo ""
        echo "─────────────────────────────────────────────────────────────────"
        echo "  Steward bootstrap invite minted (one-time, expires in 24h):"
        echo ""
        echo "    Code:     $CODE"
        echo "    Saved to: $BOOTSTRAP_INVITE  (mode 0600)"
        echo ""
        echo "  To register the first account:"
        echo "    ssh steward@<host> -p 7022    (password = the code above)"
        echo ""
        echo "  Or pre-place your SSH pubkey at /var/lib/wyrdsekai/authorized_keys"
        echo "  before first connect — pubkey path bypasses the invite entirely."
        echo "─────────────────────────────────────────────────────────────────"
        echo ""
    else
        # invite bootstrap returns non-zero if users already exist or DB not yet
        # provisioned — both fine, operator continues with existing accounts.
        :
    fi
fi

# Single-owner household: hand the state + config dirs to the human who ran
# `sudo dpkg -i`, so `wyrd setup` and `wyrd config` work WITHOUT sudo. The bug
# this fixes: /var/lib/wyrdsekai + its models/ were root-owned 0755, so a
# non-root `wyrd setup` (the documented flow) died writing the embedding model
# with "Permission denied" mid-download. SAFE because every ENABLED service runs
# as root (main wyrdsekai.service + wyrdsekai-oracle) and writes into a
# user-owned dir fine; the on-demand `nobody` services (llama) only READ the
# world-readable models. The user's client profile stays at ~/.wyrdsekai
# (separate from this system state), so no sudo re-exec / no /root split.
# Skipped for a bare-root install (no SUDO_USER) — then `sudo wyrd setup` is the
# path and the do_setup pre-flight guides it.
if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ] && id "$SUDO_USER" >/dev/null 2>&1; then
    chown -R "$SUDO_USER" /var/lib/wyrdsekai /etc/wyrdsekai 2>/dev/null || true
fi

# CANONICAL-DATA-DIR SYMLINKS — close the whole "config split" class.
# ~20 Java call sites (profile.toml, models, souls, adapters, zone-aesthetic,
# knowledge-packs, …) resolve paths as `System.getProperty("user.home") +
# "/.wyrdsekai/…"` INSTEAD of $WYRDSEKAI_DATA_DIR. On a .deb the canonical data
# dir is /var/lib/wyrdsekai, but the root service's user.home is /root and the
# operator's is /home/<user> — so those paths silently diverge from where setup
# actually wrote things (second-node 2026-07-02: the zone showed the hostname-generated
# "ferngrove" because the root daemon read /root/.wyrdsekai/profile.toml while
# `wyrd config` wrote /home/<user>/.wyrdsekai/profile.toml). Rather than refactor
# every call site, point BOTH homes' .wyrdsekai at the one canonical dir so every
# hardcoded user.home path lands in /var/lib/wyrdsekai. Guarded: never clobber a
# real (non-symlink) .wyrdsekai dir — a source-mode dev box keeps its own.
_link_home_datadir() {
    # $1 = home dir whose .wyrdsekai should point at the canonical data dir
    local _hd="$1/.wyrdsekai"
    if [ -L "$_hd" ] || [ ! -e "$_hd" ]; then
        ln -sfn /var/lib/wyrdsekai "$_hd" 2>/dev/null || true
    fi
}
_link_home_datadir /root
if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ]; then
    _sudo_home="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)"
    [ -n "$_sudo_home" ] && [ -d "$_sudo_home" ] && _link_home_datadir "$_sudo_home"
fi

# On UPGRADE ($2 = the previously-installed version), bring the main service
# back — an operator upgrading a RUNNING node expects it to keep running, not
# silently stop (the pre-2026-07-23 behaviour). prerm stopped it so the new
# jars swapped cleanly and recorded whether it was active. FRESH installs do
# NOT auto-start: they still need `wyrd setup` first (models + config), so this
# is strictly the upgrade path.
if [ -n "$2" ]; then
    state=running   # default: an upgrade from a pre-flag package was live
    if [ -f /run/wyrdsekai.upgrade-state ]; then
        state="$(cat /run/wyrdsekai.upgrade-state 2>/dev/null || echo running)"
    fi
    rm -f /run/wyrdsekai.upgrade-state 2>/dev/null || true
    if [ "$state" != inactive ]; then
        systemctl enable wyrdsekai 2>/dev/null || true
        if systemctl restart wyrdsekai 2>/dev/null; then
            echo "wyrdsekai: upgraded to this version — main service restarted."
        else
            echo "wyrdsekai: upgraded — could not auto-restart; run 'wyrd start'."
        fi
    else
        echo "wyrdsekai: upgraded (service was stopped before the upgrade — left stopped)."
    fi
    # Upgrades skip the first-run banner below.
    exit 0
fi

echo ""
echo "Wyrdsekai installed!"
echo ""
echo "  First time? Complete setup (downloads models, starts services):"
echo "    wyrd setup"
echo ""
echo "  Then:"
echo "    wyrd start           # Start the server"
echo "    wyrd status          # Check what's running"
echo "    ssh -p 7022 \$USER@localhost   # Connect"
echo ""
EOF
chmod 755 "$DEB_ROOT/DEBIAN/postinst"

# prerm — stop services, remove symlinks + custom systemd dropins
# (The dropins live under /etc/systemd/system/wyrdsekai.service.d/ and are
# created by setup flows, not owned by the .deb, so apt won't clean them.)
cat > "$DEB_ROOT/DEBIAN/prerm" << 'EOF'
#!/bin/sh
set -e

# dpkg calls prerm with "$1" = remove | upgrade | deconfigure | ... . An
# UPGRADE and a REMOVAL want opposite things:
#   upgrade → stop the services so the new build's jars swap cleanly, but KEEP
#             them enabled + KEEP custom dropins (relay config etc.) so postinst
#             can bring the node right back exactly as it was.
#   remove  → stop AND disable, and clean dropins/symlinks so a later reinstall
#             starts pristine.
# Pre-2026-07-23 this script always disabled + wiped dropins, so `dpkg -i` of a
# newer .deb left the server stopped, disabled, and stripped of its relay
# customizations — an upgrade that silently took the node down.
action="$1"

# Record whether the main service was running, so postinst knows whether to
# bring it back on upgrade. (File content: "active" / "inactive"; absent means
# an upgrade FROM a pre-flag package, which postinst treats as "was running".)
if systemctl is-active --quiet wyrdsekai 2>/dev/null; then
    echo active > /run/wyrdsekai.upgrade-state 2>/dev/null || true
else
    echo inactive > /run/wyrdsekai.upgrade-state 2>/dev/null || true
fi

# Stop always (clean jar swap on upgrade; clean shutdown on remove).
for svc in wyrdsekai wyrdsekai-oracle wyrdsekai-rendezvous wyrdsekai-metasearch \
           wyrdsekai-nats wyrdsekai-llama; do
    systemctl stop "$svc" 2>/dev/null || true
done

# Belt-and-suspenders: a server started OUTSIDE systemd (or one systemctl could
# not reap) survives 'apt purge'/'remove' and keeps owning :7070/:7022 — a fresh
# install then never binds and silently runs the OLD process. Hard-kill any
# lingering Main JVM AND the oracle-server sidecar so both a reinstall AND an
# in-place upgrade start from the NEW jars.
pkill -TERM -f 'org.wyrdsekai.server.Main' 2>/dev/null || true
pkill -TERM -f 'oracle-server.*--port' 2>/dev/null || true
sleep 2
pkill -KILL -f 'org.wyrdsekai.server.Main' 2>/dev/null || true
pkill -KILL -f 'oracle-server.*--port' 2>/dev/null || true
rm -f /var/lib/wyrdsekai/.server.pid /var/lib/wyrdsekai/.oracle.pid 2>/dev/null || true

# Stale masks survive apt purge and block reinstall — unmask defensively (safe
# either way).
for svc in wyrdsekai-nats wyrdsekai; do
    systemctl unmask "$svc" 2>/dev/null || true
done

case "$action" in
    upgrade)
        # Upgrade path: leave units ENABLED, leave dropins + CLI symlinks in
        # place. postinst restarts the main service.
        :
        ;;
    *)
        # remove | deconfigure | purge: full teardown so a later reinstall is clean.
        for svc in wyrdsekai wyrdsekai-oracle wyrdsekai-rendezvous \
                   wyrdsekai-metasearch wyrdsekai-nats wyrdsekai-llama; do
            systemctl disable "$svc" 2>/dev/null || true
        done
        # Custom dropins aren't .deb-owned; remove them so a reinstall starts clean.
        rm -rf /etc/systemd/system/wyrdsekai.service.d 2>/dev/null || true
        rm -rf /etc/systemd/system/wyrdsekai-rendezvous.service.d 2>/dev/null || true
        # postinst-created symlinks (the package-owned .py/.desktop are removed by dpkg).
        rm -f /usr/local/bin/wyrdsekai-desktop 2>/dev/null || true
        rm -f /usr/local/bin/wyrd \
              /usr/local/bin/wyrdsekai-server \
              /usr/local/bin/wyrdsekai-cli \
              /usr/local/bin/wyrdsekai-daemon 2>/dev/null || true
        rm -f /run/wyrdsekai.upgrade-state 2>/dev/null || true

        # Docker-hosted inference is NOT a systemd unit, so disabling services
        # above does not touch it. `wyrd uninstall` reaps these containers, but
        # `apt-get purge` never calls `wyrd` — so a purge left
        # wyrdsekai-llama/-voice holding :8200/:8201 and still "Up" hours later.
        # The next install then had a second inference stack racing the first,
        # and the JVM's own llama-server lost the bind (home-server, 2026-07-29).
        #
        # Only containers this package created, matched by exact name — never a
        # prune, never anything of the operator's.
        if command -v docker >/dev/null 2>&1; then
            docker rm -f wyrdsekai-llama wyrdsekai-llama-voice wyrdsekai-searxng \
                         wyrdsekai-nats wyrdsekai-oracle >/dev/null 2>&1 || true
        fi
        ;;
esac

systemctl daemon-reload 2>/dev/null || true
EOF
chmod 755 "$DEB_ROOT/DEBIAN/prerm"

# postrm — on 'purge' (apt purge), wipe config + state dirs. On 'remove'
# keep them so a reinstall can pick up where the admin left off.
cat > "$DEB_ROOT/DEBIAN/postrm" << 'EOF'
#!/bin/sh
set -e

case "$1" in
    purge)
        # A companion's world and soul live under /var/lib/wyrdsekai, because
        # postinst points every home's ~/.wyrdsekai at it to stop the root
        # daemon and the operator reading different profiles. That fixes a real
        # bug, but it also means package state and irreplaceable user data are
        # the same directory — so `dpkg --purge`, which a person runs casually
        # before reinstalling, was silently deleting the world DB, the souls and
        # several GB of model cache with no warning.
        #
        # Purge still removes it: that is what purge means. But the parts that
        # cannot be re-downloaded are archived first, and we say where they went.
        # Models are deliberately NOT archived — they are a cache, they are the
        # bulk of the size, and `wyrd model update` refetches them.
        _keep="/var/backups"
        _stamp="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo unknown)"
        _arch="$_keep/wyrdsekai-data-$_stamp.tgz"
        if [ -d /var/lib/wyrdsekai ]; then
            _found=""
            for _f in world.db souls profile.toml node-identity chronicle; do
                [ -e "/var/lib/wyrdsekai/$_f" ] && _found="$_found $_f"
            done
            if [ -n "$_found" ]; then
                mkdir -p "$_keep" 2>/dev/null || true
                if tar czf "$_arch" -C /var/lib/wyrdsekai $_found 2>/dev/null; then
                    echo "wyrdsekai: archived irreplaceable data to $_arch"
                    echo "wyrdsekai:   ($_found )"
                    echo "wyrdsekai: model cache NOT archived — refetch with 'wyrd model update'."
                else
                    echo "wyrdsekai: WARNING could not archive user data; it will be removed." >&2
                    rm -f "$_arch" 2>/dev/null || true
                fi
            fi
        fi

        rm -rf /etc/wyrdsekai
        rm -rf /var/lib/wyrdsekai
        # Legacy paths from pre-Phase-1 installs — purge anyway so old
        # machines don't leave stale state around after a clean uninstall.
        rm -rf /opt/wyrdsekai/data 2>/dev/null || true
        rm -rf /opt/wyrdsekai/lib 2>/dev/null || true
        # The rolling JVM log is created at RUNTIME, so dpkg does not track it
        # and `apt purge` left /opt/wyrdsekai behind holding just
        # logs/wyrdsekai.log. An uninstall that leaves a directory is an
        # uninstall someone has to finish by hand (observed 2026-07-29).
        rm -rf /opt/wyrdsekai/logs 2>/dev/null || true
        # Now empty unless something untracked lives there — rmdir, never rm -rf,
        # so anything unexpected is preserved and visible rather than deleted.
        rmdir /opt/wyrdsekai 2>/dev/null || true

        # The ~/.wyrdsekai symlinks postinst created now dangle. Remove only
        # symlinks — never a real directory, which is a source-mode dev box's
        # own data and nothing to do with this package.
        for _h in /root $(getent passwd 2>/dev/null | cut -d: -f6 | sort -u); do
            [ -L "$_h/.wyrdsekai" ] && rm -f "$_h/.wyrdsekai" 2>/dev/null || true
        done
        ;;
esac

exit 0
EOF
chmod 755 "$DEB_ROOT/DEBIAN/postrm"

# conffiles — mark config and data as preserved across upgrades
cat > "$DEB_ROOT/DEBIAN/conffiles" << EOF
/opt/wyrdsekai/etc/application.conf
/opt/wyrdsekai/etc/logback.xml
EOF

# ── Build the .deb ──
info "Building .deb package..."
cd "$PROJECT_DIR/build/deb"
dpkg-deb --build "$DEB_NAME"

DEB_FILE="$PROJECT_DIR/build/deb/${DEB_NAME}.deb"
ok "Package: build/deb/${DEB_NAME}.deb"
du -sh "$DEB_FILE"

# Publish into build/installers/ — the canonical dir the artifacts are scp'd
# from. Leaving the build ONLY in build/deb/ meant fresh builds silently never
# reached the box being installed (second-node 2026-07-07: reinstalled a STALE deb for
# hours because build/installers/ was never refreshed). One source of truth.
mkdir -p "$PROJECT_DIR/build/installers"

# ── Stage into build/installers, verifying the copy actually landed ──────────
# A plain `cp` of a 1.7GB artifact fails LOUDLY on stderr and quietly in $? if
# the caller does not check: on a full disk macOS reported
# "fcopyfile failed: No space left on device", left a 37MB truncated .pkg in
# the canonical output dir, and the build still exited 0. That truncated file
# is exactly what a human reaches for when shipping. Compare sizes and refuse.
stage_installer() {
    _src="$1"; _dst="$2"
    mkdir -p "$(dirname "$_dst")"
    if ! cp -f "$_src" "$_dst"; then
        rm -f "$_dst"
        echo "ERROR: failed to copy $(basename "$_src") into $(dirname "$_dst")" >&2
        exit 1
    fi
    _ssz=$(wc -c < "$_src"); _dsz=$(wc -c < "$_dst" 2>/dev/null || echo 0)
    if [ "$_ssz" != "$_dsz" ]; then
        rm -f "$_dst"
        echo "ERROR: staged copy is truncated ($_dsz of $_ssz bytes) — removed it." >&2
        echo "       Free space on this volume and rebuild." >&2
        exit 1
    fi
}

stage_installer "$DEB_FILE" "$PROJECT_DIR/build/installers/${DEB_NAME}.deb"
ok "Published: build/installers/${DEB_NAME}.deb"
echo ""
echo "Install:   sudo dpkg -i build/deb/${DEB_NAME}.deb"
echo "Remove:    sudo dpkg -r wyrdsekai"
