#!/usr/bin/env bash
#
# build-pkg.sh — Build a macOS .pkg installer for Wyrdsekai
#
# Installs to /usr/local/wyrdsekai with symlinks in /usr/local/bin.
# Uses macOS native pkgbuild + productbuild (no external deps).
#
# Usage:
#   ./packaging/macos/build-pkg.sh
#   WYRDSEKAI_VERSION=0.2.2 ./packaging/macos/build-pkg.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGING_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_DIR="$(dirname "$PACKAGING_DIR")"
VERSION="${WYRDSEKAI_VERSION:-0.2.2}"
DIST_NAME="wyrdsekai-${VERSION}"
DIST_DIR="$PROJECT_DIR/build/dist/$DIST_NAME"
PKG_BUILD="$PROJECT_DIR/build/pkg"
PKG_IDENTIFIER="org.wyrdsekai.app"
PKG_FILE="$PKG_BUILD/Wyrdsekai-${VERSION}.pkg"

info()  { echo -e "\033[36m[pkg]\033[0m $*"; }
ok()    { echo -e "\033[32m[pkg]\033[0m $*"; }
err()   { echo -e "\033[31m[pkg]\033[0m $*" >&2; }

# ── Platform check ──
if [[ "$(uname -s)" != "Darwin" ]]; then
    err "This script must run on macOS (needs pkgbuild/productbuild)"
    exit 1
fi

if ! command -v pkgbuild &>/dev/null; then
    err "pkgbuild not found. Install Xcode command line tools: xcode-select --install"
    exit 1
fi

# ── (Re)build the dist EVERY time ──
# Always re-run build-dist.sh (matching build-deb.sh). Gradle's up-to-date
# checks make the Java build a fast no-op when nothing changed, but build-dist.sh
# also re-copies bin/wyrd + scripts fresh — so a stale build/dist/ from an earlier
# run can never ship an old CLI into the .pkg (the bug that shipped a pre-fix
# bin/wyrd: the old `[[ ! -d "$DIST_DIR" ]]` guard reused whatever dist existed).
info "Refreshing distribution (build-dist.sh)…"
"$PACKAGING_DIR/build-dist.sh"

if [[ ! -d "$DIST_DIR/lib" ]]; then
    err "Distribution missing lib/ directory at $DIST_DIR"
    exit 1
fi

# ── Build the menu-bar desktop app (the friendly front door, #1285) ──
# Compiles Sources/*.swift into build/macos-app/Wyrdsekai.app. Staged into
# /Applications in the payload below; the app is the GUI face of `wyrd`.
MENUBAR_APP="$PROJECT_DIR/build/macos-app/Wyrdsekai.app"
info "Building menu-bar app..."
WYRDSEKAI_VERSION="$VERSION" "$SCRIPT_DIR/menubar/build-menubar.sh"
if [[ ! -d "$MENUBAR_APP" ]]; then
    err "menu-bar app build failed — expected $MENUBAR_APP"
    exit 1
fi

# ── Clean ──
rm -rf "$PKG_BUILD"
mkdir -p "$PKG_BUILD"

# ── Create payload ──
# macOS convention: /usr/local/wyrdsekai for the app
PAYLOAD="$PKG_BUILD/payload"
mkdir -p "$PAYLOAD/usr/local/wyrdsekai"

cp -r "$DIST_DIR/lib"     "$PAYLOAD/usr/local/wyrdsekai/lib"
# GATE (2026-07-30): a full disk made this cp silently drop the three 179MB
# core jars — the LARGEST files, so ENOSPC hits them first — and the pkg
# shipped looking complete (237 jars) but unable to run a server. The dist's
# own classifier gate passed because the DIST had the jar; it vanished in
# THIS copy. Assert the core jar landed in every module dir of the PAYLOAD.
for _mod in server cli wyrd-rendezvous; do
    if [[ -d "$PAYLOAD/usr/local/wyrdsekai/lib/$_mod" ]]; then
        ls "$PAYLOAD/usr/local/wyrdsekai/lib/$_mod/core-"*.jar >/dev/null 2>&1 || {
            err "core jar MISSING from payload lib/$_mod (disk full during copy? df: $(df -h / | tail -1))"
            exit 1
        }
    fi
done
# daemon-desktop is the LINUX GUI — on macOS the GUI ships as Wyrdsekai.app
# and the dist's lib/daemon-desktop is at best a partial glob-staged leftover.
# A core-less daemon-desktop dir is dead weight, not a broken server: drop it
# from the payload rather than ship jars that can't run.
if [[ -d "$PAYLOAD/usr/local/wyrdsekai/lib/daemon-desktop" ]] \
   && ! ls "$PAYLOAD/usr/local/wyrdsekai/lib/daemon-desktop/core-"*.jar >/dev/null 2>&1; then
    info "lib/daemon-desktop staged without its core jar (linux GUI leftovers) — removing it from the mac payload"
    rm -rf "$PAYLOAD/usr/local/wyrdsekai/lib/daemon-desktop"
fi
cp "$DIST_DIR/models-index.json" "$PAYLOAD/usr/local/wyrdsekai/models-index.json" 2>/dev/null || true
cp -r "$DIST_DIR/bin"     "$PAYLOAD/usr/local/wyrdsekai/bin"
cp -r "$DIST_DIR/etc"     "$PAYLOAD/usr/local/wyrdsekai/etc"
cp -r "$DIST_DIR/docker"  "$PAYLOAD/usr/local/wyrdsekai/docker"  2>/dev/null || true
cp -r "$DIST_DIR/scripts" "$PAYLOAD/usr/local/wyrdsekai/scripts" 2>/dev/null || true
cp -r "$DIST_DIR/rooms"   "$PAYLOAD/usr/local/wyrdsekai/rooms"   2>/dev/null || true
# #1089: classifier bootstrap seeds + pretrained heads +
# probe anchors + embedding ONNX (when present). The recipe runner CWDs to
# /usr/local/wyrdsekai and resolves `core/src/main/resources/...` paths
# against bundled assets. Without this copy a fresh .pkg install can enroll
# recipes but the very first SHELL step fails (missing seeds.jsonl).
cp -r "$DIST_DIR/core"    "$PAYLOAD/usr/local/wyrdsekai/core"    2>/dev/null || true
cp    "$DIST_DIR/VERSION"  "$PAYLOAD/usr/local/wyrdsekai/VERSION"
# OSS license — every installer must carry it (audit 2026-07-11).
cp    "$PROJECT_DIR/LICENSE" "$PAYLOAD/usr/local/wyrdsekai/LICENSE"
cp    "$PROJECT_DIR/NOTICE"  "$PAYLOAD/usr/local/wyrdsekai/NOTICE"  2>/dev/null || true
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

cp    "$(_tpn_src)" "$PAYLOAD/usr/local/wyrdsekai/THIRD_PARTY_NOTICES.md" 2>/dev/null || true

# Bundled binaries (audit 2026-07-11): NatsServerManager explicitly probes
# /usr/local/wyrdsekai/bin — which this script never populated, so macOS
# installs were silently single-node (Between disabled) unless brew happened
# to provide nats-server. Mirror the deb's if-present pattern: stage darwin
# binaries from packaging/ when they exist, warn when they don't.
# Per-binary bundling, each with its own honest message. NOTE: macOS INFERENCE
# IS MLX (Apple Silicon, staged from data/training/v8/mlx — unchanged).
# Intel Macs: the documented path is `brew install llama.cpp` (welcome page +
# conf template say so) — deliberately NOT bundled (operator 2026-07-11: no
# means to test an Intel build at present; brew is the supported story).
for bin_name in nats-server metasearch; do
    if [[ -f "$PACKAGING_DIR/$bin_name" ]]; then
        cp "$PACKAGING_DIR/$bin_name" "$PAYLOAD/usr/local/wyrdsekai/bin/$bin_name"
        chmod +x "$PAYLOAD/usr/local/wyrdsekai/bin/$bin_name"
        echo "[pkg] Bundled: $bin_name"
        continue
    fi
    case "$bin_name" in
        nats-server)  echo "[pkg] WARNING: nats-server not at $PACKAGING_DIR/ — installs run single-node (Between disabled) unless brew provides it." ;;
        metasearch)   echo "[pkg] WARNING: metasearch not at $PACKAGING_DIR/ — web search falls back to keyless DuckDuckGo." ;;
    esac
done
# Root docker-compose.yml (used by wyrd setup for Searxng + NATS)
cp    "$DIST_DIR/docker-compose.yml" "$PAYLOAD/usr/local/wyrdsekai/docker-compose.yml" 2>/dev/null || true

# FIRST_ENCOUNTER.md — three-page introduction surfaced by `wyrd setup` after
# install completes. the bondholder reads
# this BEFORE their first turn with their companion.
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
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/share"
    cp "$_FE_SRC" "$PAYLOAD/usr/local/wyrdsekai/share/FIRST_ENCOUNTER.md"
    info "Bundled FIRST_ENCOUNTER.md"
fi

# Dictionary library bundle — staged into the
# dist by build-dist.sh; BundledPackInstaller indexes it from
# /usr/local/wyrdsekai/share/library-bundle/ at first boot, no network needed.
if [[ -d "$DIST_DIR/share/library-bundle" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/share/library-bundle"
    cp -r "$DIST_DIR/share/library-bundle/." "$PAYLOAD/usr/local/wyrdsekai/share/library-bundle/"
    info "Bundled dictionary library ($(find "$PAYLOAD/usr/local/wyrdsekai/share/library-bundle" -name '*.jsonl' | wc -l | tr -d ' ') chunk files)"
fi

# Embedding-model assets — paraphrase-l12 ONNX + tokenizer (~130MB).
# Fetched by packaging/fetch-embedding-models.sh (NOT in git). Staged under
# /usr/local/wyrdsekai/share/embedding-models/ where `wyrd setup` looks for
# them before falling back to a runtime HuggingFace download. Mirrors the
# .deb path at /opt/wyrdsekai/share/embedding-models/.
EMB_SRC="$PACKAGING_DIR/embedding-models"
if [[ -d "$EMB_SRC" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/share/embedding-models"
    for f in "$EMB_SRC"/*; do
        [[ -f "$f" ]] || continue
        cp "$f" "$PAYLOAD/usr/local/wyrdsekai/share/embedding-models/"
        info "Bundled embedding asset: $(basename "$f") ($(du -sh "$f" | cut -f1))"
    done
else
    info "NOTE: embedding-models not bundled — run packaging/fetch-embedding-models.sh first; setup will fall back to HF download"
fi

# Oracle prediction-engine wheel (oracle-core) — bundle so native .pkg installs
# run the forecasting sidecar, not just docker. Staged under
# /usr/local/wyrdsekai/share/oracle/ where postinstall's `wyrd oracle bootstrap`
# pip-installs it into ~/.wyrdsekai/.venv-oracle. Light base deps.
if compgen -G "$PROJECT_DIR/packaging/oracle/oracle_core-*.whl" >/dev/null 2>&1; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/share/oracle"
    cp "$PROJECT_DIR"/packaging/oracle/oracle_core-*.whl \
       "$PAYLOAD/usr/local/wyrdsekai/share/oracle/"
    echo "Bundled oracle-core wheel for native Oracle sidecar"
else
    echo "NOTE: oracle-core wheel not bundled — copy ../oracle-core/dist/*.whl into packaging/oracle/"
fi
# Oracle LaunchDaemon plist template → payload scripts/ (postinstall renders __HOME__).
if [[ -f "$PROJECT_DIR/packaging/macos/com.wyrdsekai.oracle.plist" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/scripts"
    cp "$PROJECT_DIR/packaging/macos/com.wyrdsekai.oracle.plist" \
       "$PAYLOAD/usr/local/wyrdsekai/scripts/com.wyrdsekai.oracle.plist"
fi

mkdir -p "$PAYLOAD/usr/local/wyrdsekai/data"

# Coding-CLI bundle manifest (optional backends; binaries fetched on demand).
# bin/wyrd resolves it from /usr/local/wyrdsekai/data/coding-cli-bundle/manifest.json.
if [[ -d "$DIST_DIR/data/coding-cli-bundle" ]]; then
    # The WHOLE bundle dir, not just the manifest — same fix as build-deb.sh:
    # build-dist stages bundled backends (0.2.0: codezaiku is the bundled
    # default), and a manifest-only copy ships default-backend=codezaiku with
    # no codezaiku. A gate is only as good as its distance from the artifact
    # that ships, so the runnable check re-runs against the PAYLOAD tree.
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/data/coding-cli-bundle"
    cp -R "$DIST_DIR/data/coding-cli-bundle/." "$PAYLOAD/usr/local/wyrdsekai/data/coding-cli-bundle/"
    python3 - "$PAYLOAD/usr/local/wyrdsekai/data/coding-cli-bundle" <<'PKG_BUNDLE_GATE'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "manifest.json").read_text())
bad = []
for name, e in manifest["backends"].items():
    if not e.get("bundled"):
        continue
    slot = root / name
    ok = any(c.is_file() for base in (slot / name, slot / "bin" / name,
                                      slot / name / "bin" / name)
             for c in (base, base.with_suffix(".bat"), base.with_suffix(".exe")))
    if not ok:
        bad.append(name)
if bad:
    print("PKG bundled-backend gate FAILED: " + ", ".join(bad)
          + " claimed bundled but absent from the payload tree", file=sys.stderr)
    sys.exit(1)
print("[pkg] bundled-backend gate: payload carries every bundled backend")
PKG_BUNDLE_GATE
fi

# Track-B B1 — release-evidence dir.
if [[ -d "$DIST_DIR/data/release-evidence" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/data/release-evidence"
    cp -r "$DIST_DIR/data/release-evidence/." "$PAYLOAD/usr/local/wyrdsekai/data/release-evidence/"
fi

# V8 voice steering vectors — bin/wyrd's voice launch reads these and passes
# them to llama-server via --control-vector-scaled. Without them the .pkg
# silently ships a regressed voice (no first_person_presence / anti_defiance
# /etc).
if [[ -d "$DIST_DIR/data/vectors" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/data/vectors"
    cp -r "$DIST_DIR/data/vectors/." "$PAYLOAD/usr/local/wyrdsekai/data/vectors/"
fi

# MLX-format V8 vectors for the Darwin
# voice runtime. bin/wyrd start_mlx_voice resolves these at
# $DATA_DIR/training/v8/mlx/ or /usr/local/wyrdsekai/data/training/v8/mlx/.
# Without them the runtime falls through to vanilla mlx_lm.server (no
# steering); the postinstall WARNs if the dir is missing.
if [[ -d "$DIST_DIR/data/training/v8/mlx" ]]; then
    mkdir -p "$PAYLOAD/usr/local/wyrdsekai/data/training/v8/mlx"
    cp -r "$DIST_DIR/data/training/v8/mlx/." \
          "$PAYLOAD/usr/local/wyrdsekai/data/training/v8/mlx/"
fi

# ── Menu-bar desktop app → /Applications + GUI helper scripts (#1285) ──
mkdir -p "$PAYLOAD/Applications"
cp -R "$MENUBAR_APP" "$PAYLOAD/Applications/Wyrdsekai.app"
info "Staged Wyrdsekai.app → /Applications"

# Privileged GUI helpers — the app invokes each behind ONE native auth prompt
# (Touch ID / password) so onboarding / settings / uninstall stay one-click.
mkdir -p "$PAYLOAD/usr/local/wyrdsekai/scripts"
for h in gui-onboard.sh gui-apply-settings.sh gui-uninstall.sh; do
    cp "$SCRIPT_DIR/menubar/helpers/$h" "$PAYLOAD/usr/local/wyrdsekai/scripts/$h"
    chmod 755 "$PAYLOAD/usr/local/wyrdsekai/scripts/$h"
done

# Menu-bar LaunchAgent template (postinstall renders + installs per-user so the
# app opens at login).
if [[ -f "$SCRIPT_DIR/com.wyrdsekai.menubar.plist" ]]; then
    cp "$SCRIPT_DIR/com.wyrdsekai.menubar.plist" \
       "$PAYLOAD/usr/local/wyrdsekai/scripts/com.wyrdsekai.menubar.plist"
fi

# ── Pre/Post-install scripts ──
SCRIPTS_DIR="$PKG_BUILD/scripts"
mkdir -p "$SCRIPTS_DIR"

# postinstall — runs as root.
#  1. Symlinks under /usr/local/bin so `wyrd` is on PATH.
#  2. macOS LaunchDaemon: render the plist template (substitute __USER__ /
#     __HOME__) and install to /Library/LaunchDaemons/. Loaded only if the
#     user-context bootstrap succeeds; otherwise the steward runs
#     `sudo launchctl load ...` manually after `wyrd setup-trainer`.
#  3. On Apple Silicon: drop privileges to the actual installing user and
#     run scripts/mac-node-bootstrap-mlx-trainer.sh — installs Homebrew (if
#     missing), python@3.12, mlx-venv, mlx-lm, llama.cpp, pre-pulls a base,
#     stamps env. Not blocking — failures print "run wyrd setup-trainer".
cat > "$SCRIPTS_DIR/postinstall" << 'EOF'
#!/bin/bash
set -u

WYRD_HOME=/usr/local/wyrdsekai
LOG=/tmp/wyrdsekai-postinstall.log
exec > >(tee -a "$LOG") 2>&1
echo "=== wyrdsekai postinstall $(date) ==="

# 1) Symlinks
ln -sf "$WYRD_HOME/bin/wyrd"             /usr/local/bin/wyrd
ln -sf "$WYRD_HOME/bin/wyrdsekai-server" /usr/local/bin/wyrdsekai-server
ln -sf "$WYRD_HOME/bin/wyrdsekai-cli"    /usr/local/bin/wyrdsekai-cli
ln -sf "$WYRD_HOME/bin/wyrdsekai-daemon" /usr/local/bin/wyrdsekai-daemon
echo "symlinks placed under /usr/local/bin"

# 2) Identify the installing user. The graphical installer sets $USER to
# the console user; the CLI installer to whatever ran sudo. `stat /dev/console`
# is the canonical macOS way to find "who is logged in at the GUI". Fall back
# to $USER, then to the SUDO_USER env if either is root or empty.
TARGET_USER="$(stat -f "%Su" /dev/console 2>/dev/null)"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
    TARGET_USER="${SUDO_USER:-${USER:-}}"
fi
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
    echo "WARN: could not identify installing user — skipping LaunchDaemon + bootstrap."
    echo "      Run 'wyrd setup-trainer' after install to finish setup."
    exit 0
fi
TARGET_HOME="$(dscl . -read "/Users/$TARGET_USER" NFSHomeDirectory 2>/dev/null \
    | awk '{print $2}')"
if [ -z "$TARGET_HOME" ]; then TARGET_HOME="/Users/$TARGET_USER"; fi
echo "installing user: $TARGET_USER (home: $TARGET_HOME)"

# Create the user-level data dir owned by the target user. Do this AS ROOT
# (mkdir + chown) rather than `sudo -u` — under installd `sudo -u` is flaky
# (no askpass / restricted env) and silently no-ops, leaving the dir to be
# created root-owned by the LaunchDaemon below. A root-owned ~/.wyrdsekai then
# makes every user-context bootstrap (oracle venv §4b, MLX venv §5, and the
# menu-bar's `wyrd inference setup-local`) fail with "Permission denied" — so
# the companion ships with no local brain.
mkdir -p "$TARGET_HOME/.wyrdsekai/data"
chown -R "$TARGET_USER" "$TARGET_HOME/.wyrdsekai" 2>/dev/null || true

# 3) Seed baseline config at the canonical system-wide location.
# matches .deb's /etc/wyrdsekai/wyrdsekai.conf
# layout. Previous behaviour wrote to ~/.wyrdsekai/env which left .pkg out
# of step with .deb and made `wyrd config show` read the wrong file.
# Idempotent — leaves any existing file alone (don't stomp on hand-tuned
# configs). One-time migration for old installs: if the legacy
# ~/.wyrdsekai/env exists and the new file doesn't, copy contents over.
mkdir -p /etc/wyrdsekai
chmod 755 /etc/wyrdsekai
SYS_CONF=/etc/wyrdsekai/wyrdsekai.conf
LEGACY_ENV="$TARGET_HOME/.wyrdsekai/env"
NODE_NAME="$(hostname -s 2>/dev/null || echo macnode)"
if [ ! -f "$SYS_CONF" ]; then
    if [ -f "$LEGACY_ENV" ]; then
        echo "Migrating legacy config from $LEGACY_ENV → $SYS_CONF"
        cp "$LEGACY_ENV" "$SYS_CONF"
    else
        # Per-arch inference defaults. On Apple Silicon we seed the dual-MLX
        # wiring so the Java daemon recognizes both backends out of the box;
        # the LaunchAgent (installed below) starts mlx_lm.server on :8201 +
        # `wyrd voice enable` covers :8200. Without this, stewards had to
        # edit the conf by hand after install — see the
        # mac-node rough-edges cleanup.
        # Apple Silicon detection must NOT rely on `uname -m` here: the .pkg
        # postinstall runs under installd with a minimal PATH (and can be
        # Rosetta-translated), so `uname` may be unresolved or report x86_64.
        # That silently routed Apple Silicon Macs to the Intel `else` branch
        # below — which ships the inference backend COMMENTED OUT, leaving the
        # companion with no drive ("No inference backend available"). The
        # hw.optional.arm64 sysctl reflects the real hardware regardless of
        # PATH or translation. Absolute paths so a stripped PATH can't hide them.
        ARCH_FOR_CONF=$(/usr/bin/uname -m 2>/dev/null || echo unknown)
        if [ "$(/usr/sbin/sysctl -n hw.optional.arm64 2>/dev/null)" = "1" ]; then
            ARCH_FOR_CONF=arm64
        fi
        cat > "$SYS_CONF" <<EEOF
# Wyrdsekai baseline configuration — seeded by .pkg installer.
# Canonical location (matches .deb layout). Edit and restart the daemon:
#   sudo launchctl kickstart -k system/com.wyrdsekai.server

# Between bridge (cross-node mesh). Always on — every install is a household peer.
WYRDSEKAI_BETWEEN_ENABLED=true
WYRDSEKAI_NATS_URL=nats://127.0.0.1:4222
WYRDSEKAI_NODE_NAME=$NODE_NAME
# Distinct key from NODE_NAME: the server reads NODE_ID for the CountingHouse
# replica id. Seeding only NODE_NAME left every Mac on the "local" default, so
# two Macs in one household shared a replica id (found 2026-07-29). Main
# now falls back to the node name, but seed it here too so the value is
# visible in the conf instead of only implied.
WYRDSEKAI_NODE_ID=$NODE_NAME
WYRDSEKAI_ZONE_ID=home
WYRDSEKAI_DATA_DIR=$TARGET_HOME/.wyrdsekai

EEOF
        if [ "$ARCH_FOR_CONF" = "arm64" ]; then
            cat >> "$SYS_CONF" <<'EEOF'
# Inference — Apple Silicon dual-MLX ( + dual-mlx 2026-05-28).
# Skills (9B drive) on :8200 via mlx_lm.server, voice (4B + V8 vectors) on :8201.
# Both spawn from `wyrd start_inference` (LaunchAgent fires this on user login).
WYRDSEKAI_LLAMA_ENABLED=true
WYRDSEKAI_LLAMA_URL="mlx://127.0.0.1:8200"
WYRDSEKAI_VOICE_ENABLED=true
WYRDSEKAI_VOICE_URL="mlx://127.0.0.1:8201"
# (The 4B voice-pass — the 9B decides CONTENT, the 4B re-voices it — defaults ON
# automatically wherever WYRDSEKAI_VOICE_ENABLED=true; see WyrdConfig.voiceEnabled
# + CompanionActor.shouldRunVoicePass. No separate seed needed.)

EEOF
        else
            cat >> "$SYS_CONF" <<'EEOF'
# Inference — local llama-server (Metal on Intel Macs / Linux).
# WYRDSEKAI_LLAMA_ENABLED=true
# WYRDSEKAI_LLAMA_URL=http://127.0.0.1:8200

EEOF
        fi
        cat >> "$SYS_CONF" <<'EEOF'
# Relay — required for cross-zone messaging. Configure via 'wyrd relay register'.
# WYRDSEKAI_RELAY_ENABLED=true
# WYRDSEKAI_RELAY_URL=nats://relay.example:4222
# WYRDSEKAI_RELAY_USER=
# WYRDSEKAI_RELAY_TOKEN=
EEOF
    fi
    chmod 644 "$SYS_CONF"
    echo "baseline config seeded at $SYS_CONF (Between=true, node=$NODE_NAME)"
else
    echo "config already present at $SYS_CONF — leaving as-is"
fi

# 4) Render + install the LaunchDaemon plist, then auto-load it.
# previous behavior dropped the plist into
# /Library/LaunchDaemons/ and printed "(not loaded yet)" — operators had
# to run `sudo launchctl bootstrap system ...` manually. .deb postinst
# auto-starts; macOS should match.
PLIST_TEMPLATE="$WYRD_HOME/scripts/com.wyrdsekai.server.plist"
PLIST_TARGET=/Library/LaunchDaemons/com.wyrdsekai.server.plist
SVC=system/com.wyrdsekai.server
if [ -f "$PLIST_TEMPLATE" ]; then
    # The plist now runs as root (no UserName/GroupName) to bypass macOS
    # Local Network Privacy on LAN-bound traffic — see the comment block
    # at the top of the plist for the full reasoning. We still substitute
    # __HOME__ so per-user data paths (~/.wyrdsekai/...) resolve correctly
    # even though the daemon runs as root.
    sed -e "s|__HOME__|$TARGET_HOME|g" \
        "$PLIST_TEMPLATE" > "$PLIST_TARGET"
    chown root:wheel "$PLIST_TARGET"
    chmod 644 "$PLIST_TARGET"
    echo "LaunchDaemon installed at $PLIST_TARGET (runs as root, HOME=$TARGET_HOME)"

    # First-session ownership repair (2026-07-30): the root daemon bootstraps
    # ~/.wyrdsekai content root-owned the moment it loads; the plist sweeps at
    # every START, but the user's first `wyrd setup` runs before any restart.
    # Give bootstrap a moment, then hand the tree back to its owner.
    ( sleep 8; [ -d "$TARGET_HOME/.wyrdsekai" ] && \
        chown -R "$(stat -f%Su "$TARGET_HOME")" "$TARGET_HOME/.wyrdsekai" 2>/dev/null ) &

    # Idempotent reload: tear down any existing instance (handles upgrades
    # / reinstalls), then enable + bootstrap + kickstart. On upgrades the
    # old daemon must finish tearing down before we re-bootstrap, and a
    # bootstrap alone does not reliably (re)spawn from the installer's
    # launchd context — so we wait for the bootout to settle, then force a
    # (re)start with `kickstart -k`. Failures are non-fatal — the operator
    # can retry manually if launchctl is unhappy.
    /bin/launchctl bootout "$SVC" 2>/dev/null || true
    # Wait (bounded) for the previous instance to fully tear down; bootout
    # is asynchronous and bootstrapping mid-teardown races / errors.
    for _i in 1 2 3 4 5 6 7 8 9 10; do
        /bin/launchctl print "$SVC" >/dev/null 2>&1 || break
        sleep 1
    done
    # `enable` clears any persisted disabled state (idempotent; safe before bootstrap).
    /bin/launchctl enable "$SVC" 2>>"$LOG" || true
    if /bin/launchctl bootstrap system "$PLIST_TARGET" 2>>"$LOG"; then
        # Force a (re)start regardless of RunAtLoad timing under installer context.
        /bin/launchctl kickstart -k "$SVC" 2>>"$LOG" || true
        # Give launchd a moment to spawn; check state (a few retries — JVM boot).
        for _i in 1 2 3 4 5; do
            if /bin/launchctl print "$SVC" 2>/dev/null | grep -q "state = running"; then
                break
            fi
            sleep 1
        done
        if /bin/launchctl print "$SVC" 2>/dev/null | grep -q "state = running"; then
            echo "LaunchDaemon: running"
        else
            echo "LaunchDaemon: bootstrapped but not yet running — check:"
            echo "  log show --predicate 'subsystem == \"com.wyrdsekai.server\"' --last 5m"
        fi
    else
        echo "WARN: launchctl bootstrap failed — start manually with:"
        echo "  sudo launchctl bootstrap system $PLIST_TARGET"
        echo "  sudo launchctl enable $SVC && sudo launchctl kickstart -k $SVC"
    fi
else
    echo "WARN: $PLIST_TEMPLATE missing — LaunchDaemon not configured"
fi

# 4a) Re-assert user ownership of ~/.wyrdsekai BEFORE the user-context bootstraps
# below. The root LaunchDaemon (§4) just started and creates data files as root
# on first boot; if the tree is root-owned, the oracle venv (§4b) and MLX venv
# (§5) bootstraps — which run as $TARGET_USER — fail with "Permission denied:
# ~/.wyrdsekai/...". Root keeps full write access to user-owned files, so
# re-owning the tree does not affect the daemon. This is the fix for the
# brainless-companion-on-a-truly-fresh-install bug (mlx-venv never built).
chown -R "$TARGET_USER" "$TARGET_HOME/.wyrdsekai" 2>/dev/null || true

# 4b) Oracle forecasting sidecar (oracle-core). Bootstrap the wheel into a
# venv (as the installing user, so it owns ~/.wyrdsekai/.venv-oracle), then
# install + load a LaunchDaemon that runs oracle-server on :7073. The Java zone
# auto-connects when it health-probes OK. Non-fatal — air-gapped installs that
# can't pip stay inert until a later `wyrd oracle bootstrap`.
ORACLE_WHEEL_DIR="$WYRD_HOME/share/oracle"
if [ -d "$ORACLE_WHEEL_DIR" ]; then
    echo "bootstrapping oracle-core venv as $TARGET_USER ..."
    ORACLE_OK=0
    if sudo -u "$TARGET_USER" env \
        WYRDSEKAI_DATA_DIR="$TARGET_HOME/.wyrdsekai" \
        WYRDSEKAI_ORACLE_WHEEL_DIRS="$ORACLE_WHEEL_DIR" \
        "$WYRD_HOME/bin/wyrd" oracle bootstrap 2>>"$LOG"; then
        ORACLE_OK=1
    else
        echo "oracle bootstrap deferred — run 'wyrd oracle bootstrap' later"
    fi

    ORACLE_PLIST_TEMPLATE="$WYRD_HOME/scripts/com.wyrdsekai.oracle.plist"
    ORACLE_PLIST_TARGET=/Library/LaunchDaemons/com.wyrdsekai.oracle.plist
    OSVC=system/com.wyrdsekai.oracle
    # Only stand up the LaunchDaemon when the venv actually bootstrapped. Starting
    # it with no venv makes the daemon's executable missing, and `launchctl
    # kickstart -k` then WEDGES (it blocks the postinstall — observed hanging the
    # installer on "Running package scripts…"). If bootstrap deferred, leave the
    # daemon uninstalled; `wyrd oracle bootstrap` wires it up later.
    if [ -f "$ORACLE_PLIST_TEMPLATE" ] && [ "$ORACLE_OK" = "1" ]; then
        sed -e "s|__HOME__|$TARGET_HOME|g" \
            "$ORACLE_PLIST_TEMPLATE" > "$ORACLE_PLIST_TARGET"
        chown root:wheel "$ORACLE_PLIST_TARGET"
        chmod 644 "$ORACLE_PLIST_TARGET"
        /bin/launchctl bootout "$OSVC" 2>/dev/null || true
        /bin/launchctl enable "$OSVC" 2>>"$LOG" || true
        if /bin/launchctl bootstrap system "$ORACLE_PLIST_TARGET" 2>>"$LOG"; then
            # Background the kickstart so a stuck launchctl can never hang the
            # installer; launchd's RunAtLoad/KeepAlive starts it regardless.
            ( /bin/launchctl kickstart -k "$OSVC" 2>>"$LOG" || true ) &
            echo "Oracle LaunchDaemon installed + started on :7073"
        else
            echo "WARN: oracle launchctl bootstrap failed — start manually:"
            echo "  sudo launchctl bootstrap system $ORACLE_PLIST_TARGET"
        fi
    elif [ -f "$ORACLE_PLIST_TEMPLATE" ]; then
        echo "Oracle LaunchDaemon NOT started (bootstrap deferred). After install run:"
        echo "  sudo wyrd oracle bootstrap   # or: wyrd oracle bootstrap (as your user)"
    fi
fi

# 5) Apple Silicon The Darwin voice
# runtime is mlx_lm.server now, not llama-server, so the mlx-venv must
# exist before `wyrd start_inference` can fire. Run the bootstrap as the
# installing user (it needs $HOME + brew context, NOT root). Failures are
# non-fatal — `wyrd start_inference` falls back to llama-server when
# find_mlx_venv_python returns 1.
#
# WYRD_PHASE4_SKIP_BASE_PULL=1 keeps install-time small; the actual MLX
# voice base lands later via `wyrd setup` (Phase 4D) so the first install
# isn't gated on a 3GB download.
# Apple Silicon detection must NOT use `uname -m` here: under installd the
# postinstall can run Rosetta-translated → `uname -m` reports x86_64, which
# silently SKIPPED this ENTIRE MLX bootstrap on Apple Silicon (no mlx-venv →
# the companion has no local brain on a fresh install). hw.optional.arm64
# reflects the real CPU regardless of translation. Mirrors the conf-seed check.
ARCH=$(/usr/bin/uname -m 2>/dev/null || echo unknown)
if [ "$(/usr/sbin/sysctl -n hw.optional.arm64 2>/dev/null)" = "1" ]; then ARCH=arm64; fi
if [ "$ARCH" = "arm64" ] && [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
    BOOTSTRAP_SCRIPT="$WYRD_HOME/scripts/mac-node-bootstrap-mlx-trainer.sh"
    if [ -f "$BOOTSTRAP_SCRIPT" ]; then
        echo
        echo "────────────────────────────────────────────────────────────────────────"
        echo " Apple Silicon detected — bootstrapping MLX runtime for user $TARGET_USER"
        echo " (this is the Darwin voice path)"
        echo "────────────────────────────────────────────────────────────────────────"
        # sudo -u to drop privileges; -H so $HOME resolves to the user's home;
        # explicit env to pass the skip-flag through (sudo strips most env).
        if sudo -u "$TARGET_USER" -H \
            env WYRD_PHASE4_SKIP_BASE_PULL=1 \
            bash "$BOOTSTRAP_SCRIPT" 2>&1; then
            echo "MLX bootstrap complete — mlx-venv at $TARGET_HOME/.wyrdsekai/mlx-venv"
        else
            echo "WARN: MLX bootstrap failed (non-fatal). Run manually:"
            echo "  bash $BOOTSTRAP_SCRIPT"
        fi
    else
        echo "WARN: $BOOTSTRAP_SCRIPT missing — MLX runtime not bootstrapped"
    fi

    # 5b) Install the MLX voice LaunchAgent. User-context (NOT LaunchDaemon)
    # because Metal access + per-user mlx-venv need the user's session.
    # Template ships with __USER__/__HOME__ placeholders that we substitute
    # at install-time.
    AGENT_SRC="$WYRD_HOME/scripts/wyrdsekai-mlx-voice.plist"
    AGENT_DST_DIR="$TARGET_HOME/Library/LaunchAgents"
    AGENT_DST="$AGENT_DST_DIR/com.wyrdsekai.mlx-voice.plist"
    if [ -f "$AGENT_SRC" ]; then
        # Render template + install as the target user so the file lands
        # with the correct ownership for `launchctl bootstrap gui/<uid>`.
        sudo -u "$TARGET_USER" -H mkdir -p "$AGENT_DST_DIR"
        sed -e "s|__USER__|$TARGET_USER|g" \
            -e "s|__HOME__|$TARGET_HOME|g" \
            "$AGENT_SRC" > /tmp/wyrdsekai-mlx-voice.plist.rendered
        sudo -u "$TARGET_USER" -H cp /tmp/wyrdsekai-mlx-voice.plist.rendered "$AGENT_DST"
        chown "$TARGET_USER" "$AGENT_DST" 2>/dev/null || true
        chmod 644 "$AGENT_DST"
        rm -f /tmp/wyrdsekai-mlx-voice.plist.rendered
        echo "MLX voice LaunchAgent installed at $AGENT_DST"

        # Bootstrap into the user's gui domain. We don't have the user's
        # console session here (postinstall runs as root) so this may
        # fail if the user isn't logged in — that's fine, the agent
        # auto-loads on next login (RunAtLoad=true).
        TARGET_UID=$(id -u "$TARGET_USER" 2>/dev/null || echo "")
        if [ -n "$TARGET_UID" ]; then
            sudo -u "$TARGET_USER" -H \
                /bin/launchctl bootout "gui/$TARGET_UID/com.wyrdsekai.mlx-voice" \
                2>/dev/null || true
            if sudo -u "$TARGET_USER" -H \
                /bin/launchctl bootstrap "gui/$TARGET_UID" "$AGENT_DST" 2>/dev/null; then
                echo "MLX voice LaunchAgent bootstrapped into gui/$TARGET_UID"
            else
                echo "MLX voice LaunchAgent will auto-load on next user login"
            fi
        fi
    else
        echo "WARN: $AGENT_SRC missing — MLX voice LaunchAgent not installed"
    fi
fi

# 5c) Menu-bar desktop app (#1285) — install the per-user LaunchAgent that
# opens the Wyrdsekai app at login, and open it now so the user sees the front
# door immediately. Any arch (Intel + Apple Silicon); needs a GUI user.
if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ]; then
    MB_SRC="$WYRD_HOME/scripts/com.wyrdsekai.menubar.plist"
    MB_DST_DIR="$TARGET_HOME/Library/LaunchAgents"
    MB_DST="$MB_DST_DIR/com.wyrdsekai.menubar.plist"
    if [ -f "$MB_SRC" ]; then
        sudo -u "$TARGET_USER" -H mkdir -p "$MB_DST_DIR"
        sudo -u "$TARGET_USER" -H cp "$MB_SRC" "$MB_DST"
        chown "$TARGET_USER" "$MB_DST" 2>/dev/null || true
        chmod 644 "$MB_DST"
        MB_UID=$(id -u "$TARGET_USER" 2>/dev/null || echo "")
        if [ -n "$MB_UID" ]; then
            sudo -u "$TARGET_USER" -H /bin/launchctl bootout   "gui/$MB_UID/com.wyrdsekai.menubar" 2>/dev/null || true
            sudo -u "$TARGET_USER" -H /bin/launchctl bootstrap "gui/$MB_UID" "$MB_DST" 2>/dev/null || true
        fi
        echo "Menu-bar app LaunchAgent installed at $MB_DST"
    fi
    # Open the app now (best-effort — needs an active GUI session).
    sudo -u "$TARGET_USER" -H /usr/bin/open -a /Applications/Wyrdsekai.app 2>/dev/null \
        && echo "Opened Wyrdsekai.app" || echo "Wyrdsekai.app will open at next login"
fi

# 6) F4 phase 2: mint a one-time steward-bootstrap invite (skipped if any
# user already exists — re-installs preserve state).
BOOTSTRAP_INVITE=/etc/wyrdsekai/steward-bootstrap.invite
mkdir -p /etc/wyrdsekai
if [ ! -f "$BOOTSTRAP_INVITE" ] && [ -x "$WYRD_HOME/bin/wyrd" ]; then
    if CODE=$("$WYRD_HOME/bin/wyrd" invite bootstrap 2>/dev/null); then
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
        echo "  Or pre-place your SSH pubkey in ~/.wyrdsekai/authorized_keys"
        echo "  before first connect — pubkey path bypasses the invite entirely."
        echo "─────────────────────────────────────────────────────────────────"
        echo ""
    fi
fi

echo "=== postinstall complete ==="
exit 0
EOF
chmod 755 "$SCRIPTS_DIR/postinstall"

# preinstall — pre-upgrade data snapshot + Java check
cat > "$SCRIPTS_DIR/preinstall" << 'PREEOF'
#!/bin/sh

# Pre-upgrade data snapshot (W11, audit 2026-07-11 — parity with the deb's
# 2026-07-09 durability insurance): if a previous install has databases, copy
# them aside BEFORE the new binary's schema migrations run. Keep 3 newest.
TARGET_USER="$(stat -f "%Su" /dev/console 2>/dev/null)"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
    TARGET_USER="${SUDO_USER:-${USER:-}}"
fi
TARGET_HOME="$(dscl . -read "/Users/$TARGET_USER" NFSHomeDirectory 2>/dev/null \
    | awk '{print $2}')"
if [ -z "$TARGET_HOME" ]; then TARGET_HOME="/Users/$TARGET_USER"; fi
DATA_DIR="$TARGET_HOME/.wyrdsekai"
if [ -d "$DATA_DIR" ] && ls "$DATA_DIR"/*.db >/dev/null 2>&1; then
    ts=$(date +%Y%m%d-%H%M%S)
    snap="$DATA_DIR/backups/pre-upgrade-$ts"
    mkdir -p "$snap"
    cp "$DATA_DIR"/*.db "$snap/" 2>/dev/null || true
    ls -dt "$DATA_DIR"/backups/pre-upgrade-* 2>/dev/null | tail -n +4 | xargs rm -rf 2>/dev/null || true
fi

# Check for Java 25+ (project standard — audit 2026-07-11)
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/' || echo "0")
    if [ "$JAVA_VER" -ge 25 ] 2>/dev/null; then
        exit 0
    fi
fi

echo ""
echo "⚠  Java 25+ is required but not found."
echo ""
echo "Install via Homebrew:"
echo "  brew install --cask temurin"
echo ""
echo "Or download from: https://adoptium.net/temurin/releases/"
echo ""
echo "The installer will continue, but Wyrdsekai won't run until Java is installed."
echo ""

# Don't block install — user may install Java after
exit 0
PREEOF
chmod 755 "$SCRIPTS_DIR/preinstall"

# ── Build component package ──
info "Building component package..."
COMPONENT_PKG="$PKG_BUILD/wyrdsekai-component.pkg"

# Disable bundle relocation. By default pkgbuild marks app bundles "relocatable", so the
# macOS Installer — finding an existing Wyrdsekai.app anywhere on disk (e.g. the build tree
# on a dev/build machine like mac-node) — redirects the install THERE instead of
# /Applications. The menu-bar app then never appears and its login agent fails. Forcing
# BundleIsRelocatable=false in a generated component plist pins the app to /Applications,
# always. The sed flips the <true/> that follows each BundleIsRelocatable key to <false/>.
COMPONENT_PLIST="$PKG_BUILD/component.plist"
pkgbuild --analyze --root "$PAYLOAD" "$COMPONENT_PLIST"
/usr/bin/sed -i '' -e '/<key>BundleIsRelocatable<\/key>/{n;s/<true\/>/<false\/>/;}' "$COMPONENT_PLIST"

pkgbuild \
    --root "$PAYLOAD" \
    --component-plist "$COMPONENT_PLIST" \
    --identifier "$PKG_IDENTIFIER" \
    --version "$VERSION" \
    --scripts "$SCRIPTS_DIR" \
    --install-location "/" \
    "$COMPONENT_PKG"

# ── Distribution XML (for productbuild — gives us license, welcome, etc.) ──
cat > "$PKG_BUILD/distribution.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
    <title>Wyrdsekai ${VERSION}</title>
    <options customize="never" require-scripts="false"/>
    <domains enable_anywhere="false" enable_currentUserHome="false" enable_localSystem="true"/>

    <welcome file="welcome.html" mime-type="text/html"/>

    <choices-outline>
        <line choice="wyrdsekai"/>
    </choices-outline>

    <choice id="wyrdsekai" title="Wyrdsekai" description="Distributed text-native world engine">
        <pkg-ref id="$PKG_IDENTIFIER"/>
    </choice>

    <pkg-ref id="$PKG_IDENTIFIER" version="$VERSION" onConclusion="none">#wyrdsekai-component.pkg</pkg-ref>
</installer-gui-script>
EOF

# Welcome HTML
mkdir -p "$PKG_BUILD/resources"
cat > "$PKG_BUILD/resources/welcome.html" << 'EOF'
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><style>
/* Opt into native light/dark rendering and pin readable contrast in BOTH
   modes — the macOS Installer pane follows the system theme, so hardcoded
   light-only colors made the highlighted code/notes unreadable in Dark Mode. */
:root { color-scheme: light dark; }
body { font-family: -apple-system, Helvetica Neue, sans-serif; padding: 20px; color: #1d1d1f; background: #ffffff; }
h1 { font-size: 24px; }
code, pre { color: #1d1d1f; background: #ececec; border-radius: 4px; font-size: 13px; }
code { padding: 2px 6px; }
pre { padding: 10px 12px; overflow-x: auto; }
.note { background: #eef1f6; color: #1d1d1f; border-left: 3px solid #007AFF; padding: 10px 15px; margin: 15px 0; }
@media (prefers-color-scheme: dark) {
  body { color: #e8e8ea; background: #1e1e1e; }
  code, pre { color: #f2f2f2; background: #3a3a3c; }
  .note { background: #2c2c2e; color: #e8e8ea; border-left-color: #0a84ff; }
}
</style></head>
<body>
<h1>Wyrdsekai</h1>
<p>Distributed text-native world engine. AI agents and humans coexist in a shared programmable world.</p>

<div class="note">
<strong>Requires:</strong> Java 25+ (install via <code>brew install --cask temurin</code>)
</div>

<div class="note">
<strong>New:</strong> after install, the <strong>Wyrdsekai</strong> app appears in your menu bar (the ✦ icon).
Click it → <strong>Enter World</strong>. A one-time setup wizard lets you name your companion and pick a
language — no Terminal needed.
</div>

<p>This installer will place Wyrdsekai in <code>/usr/local/wyrdsekai</code> and add commands to your PATH.</p>

<p><strong>Prefer the Terminal?</strong></p>
<ul>
<li><code>wyrd start</code> — Start the server</li>
<li><code>wyrd status</code> — Check status</li>
<li><code>wyrdsekai-cli</code> — Connect via CLI</li>
</ul>

<p><strong>Inference:</strong> Wyrdsekai needs a running inference backend. After install, run:</p>
<pre>wyrd inference local          # auto-picks a model for your hardware
wyrd inference remote &lt;url&gt;   # use an existing server on your LAN
wyrd inference zone &lt;zoneId&gt;  # delegate to a federated zone over NATS</pre>

<p><strong>Apple Silicon:</strong> llama.cpp is the default backend on M-series (uses Metal).
Install once via Homebrew: <code>brew install llama.cpp</code></p>

<p><strong>Zone directory (optional):</strong> To run this node as a rendezvous aggregator for federated zones:</p>
<pre>wyrd rendezvous start    # foreground (, port 7071)</pre>
<p>macOS does not auto-start rendezvous; run it manually or write a launchd plist.</p>
</body>
</html>
EOF

# ── Build product package ──
info "Building product package..."
productbuild \
    --distribution "$PKG_BUILD/distribution.xml" \
    --resources "$PKG_BUILD/resources" \
    --package-path "$PKG_BUILD" \
    "$PKG_FILE"

ok "Package: build/pkg/Wyrdsekai-${VERSION}.pkg"
du -sh "$PKG_FILE"

# Publish into build/installers/ — the canonical dir the artifacts are scp'd
# from (second-node 2026-07-07: a fresh build that only landed in build/pkg/ never
# reached the installed box). One source of truth for "the installer to ship".
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

stage_installer "$PKG_FILE" "$PROJECT_DIR/build/installers/Wyrdsekai-${VERSION}.pkg"
ok "Published: build/installers/Wyrdsekai-${VERSION}.pkg"
echo ""
echo "Install:     open build/pkg/Wyrdsekai-${VERSION}.pkg"
# NOT a plain rm -rf. This package installs launchd jobs (com.wyrdsekai.server,
# .oracle, .menubar, .mlx-voice), and deleting the files without booting them out
# leaves launchd respawning services whose binaries are gone. `wyrd uninstall` is
# the full teardown — it boots out the jobs first, then removes files, the
# /Applications app and the pkg receipt.
echo "Uninstall:   wyrd uninstall        # deletes files AND stops the launchd jobs"
