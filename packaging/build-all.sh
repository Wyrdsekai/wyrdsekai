#!/usr/bin/env bash
#
# build-all.sh — Build all Wyrdsekai packages for the current platform
#
# Usage:
#   ./packaging/build-all.sh           # Build dist + platform package
#   ./packaging/build-all.sh --dist    # Distribution archive only (tar.gz)
#   ./packaging/build-all.sh --deb     # .deb only (Linux)
#   ./packaging/build-all.sh --pkg     # .pkg only (macOS)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
VERSION="${WYRDSEKAI_VERSION:-0.1.1}"

info()  { echo -e "\033[36m[pack]\033[0m $*"; }
ok()    { echo -e "\033[32m[pack]\033[0m $*"; }
err()   { echo -e "\033[31m[pack]\033[0m $*" >&2; }

BUILD_DIST=false
BUILD_DEB=false
BUILD_PKG=false
BUILD_MSI=false
BUILD_RELAY=false
AUTO=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dist) BUILD_DIST=true; AUTO=false; shift ;;
        --deb)  BUILD_DEB=true; AUTO=false; shift ;;
        --pkg)  BUILD_PKG=true; AUTO=false; shift ;;
        --msi)  BUILD_MSI=true; AUTO=false; shift ;;
        --relay) BUILD_RELAY=true; AUTO=false; shift ;;
        *)      shift ;;
    esac
done

# Auto-detect platform
if $AUTO; then
    BUILD_DIST=true
    BUILD_RELAY=true
    case "$(uname -s)" in
        Linux)
            if command -v dpkg-deb &>/dev/null; then
                BUILD_DEB=true
            fi
            ;;
        Darwin)
            BUILD_PKG=true
            ;;
        MINGW*|MSYS*|CYGWIN*)
            BUILD_MSI=true
            ;;
    esac
fi

# Detect architecture for .deb
if [[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]]; then
    export WYRDSEKAI_ARCH="arm64"
else
    export WYRDSEKAI_ARCH="amd64"
fi

export WYRDSEKAI_VERSION="$VERSION"

echo ""
echo "  ╔══════════════════════════════════╗"
echo "  ║      Wyrdsekai Packaging         ║"
echo "  ║         v${VERSION}                ║"
echo "  ╚══════════════════════════════════╝"
echo ""

# ── 0. Pre-bundle: release-time evolution bake (B1) ──
# Runs retrain-classifier-head for each enrolled head against the real
# production code path; on success commits the evolved .onnx and writes
# evidence + first-boot soul-fragment seeds to data/release-evidence/.
# Aborts the release if any head fails. Skip with WYRDSEKAI_SKIP_BAKE=1
# for emergency builds (or use BAKE_SKIP_HEADS=… for a partial skip).
if [[ -z "${WYRDSEKAI_SKIP_BAKE:-}" ]]; then
    if $BUILD_DIST; then
        info "Running release-time evolution bake (B1)..."
        "$SCRIPT_DIR/build-evolved-artifact.sh"
        echo ""
    fi
else
    info "WYRDSEKAI_SKIP_BAKE set — skipping release-time evolution bake (B1)"
fi

# ── 1. Distribution archive ──
if $BUILD_DIST; then
    "$SCRIPT_DIR/build-dist.sh"
    echo ""
fi

# ── 2. Platform packages ──
if $BUILD_DEB; then
    "$SCRIPT_DIR/deb/build-deb.sh"
    echo ""
fi

if $BUILD_PKG; then
    "$SCRIPT_DIR/macos/build-pkg.sh"
    echo ""
fi

if $BUILD_MSI; then
    if command -v powershell &>/dev/null || command -v pwsh &>/dev/null; then
        PWSH=$(command -v pwsh 2>/dev/null || command -v powershell)
        "$PWSH" -ExecutionPolicy Bypass -File "$SCRIPT_DIR/windows/build-msi.ps1" -Version "$VERSION"
    else
        err "PowerShell not found — skipping MSI build"
    fi
    echo ""
fi

# ── Relay bundle ──
# A relay host needs `relay.sh` AND the `deploy/relay/` payload beside it
# (relay.sh resolves BUNDLE_DIR from $SCRIPT_DIR/deploy/relay). Without a
# bundle, standing up a relay meant cloning the whole 1.8GB repo onto a box
# whose only job is shuffling bytes. This tarball is that box's entire world:
# extract, run, done.
if $BUILD_RELAY; then
    info "Building relay bundle..."
    RELAY_STAGE="$PROJECT_DIR/build/relay/wyrdsekai-relay-${VERSION}"
    rm -rf "$PROJECT_DIR/build/relay"
    mkdir -p "$RELAY_STAGE/deploy/relay"
    cp "$SCRIPT_DIR/relay.sh" "$RELAY_STAGE/relay.sh"
    chmod +x "$RELAY_STAGE/relay.sh"
    # Payload only — no __pycache__, no logs from someone else's run.
    for f in Dockerfile registration.py test_registration.py relay.conf \
             Caddyfile setup.sh aio-entrypoint.sh tunnel-sshd_config README.md; do
        [[ -e "$PROJECT_DIR/deploy/relay/$f" ]] && \
            cp -r "$PROJECT_DIR/deploy/relay/$f" "$RELAY_STAGE/deploy/relay/"
    done
    [[ -d "$PROJECT_DIR/deploy/relay/certinit" ]] && \
        cp -r "$PROJECT_DIR/deploy/relay/certinit" "$RELAY_STAGE/deploy/relay/"
    # Redact before tarring. This staging duplicates build-relay-bundle.sh, and
    # fixing that script did NOT fix this one — the versioned bundle produced
    # here is what publish-release.sh ships, and it was still carrying `operator`,
    # `relay-node` and `home-server` in comments after the other copy was clean.
    #
    # Of all four payloads this is the one that matters most: the .deb/.pkg/.msi
    # go to people's own machines, but the relay bundle is what a stranger
    # installs on an internet-facing host.
    if [[ -f "$PROJECT_DIR/scripts/lib/oss_redact.py" ]] && command -v python3 &>/dev/null; then
        info "Redacting relay bundle payload..."
        if ! python3 "$PROJECT_DIR/scripts/lib/dist_redact.py" "$RELAY_STAGE"; then
            err "Relay bundle redaction failed — refusing to ship it"
            exit 1
        fi
    fi

    mkdir -p "$PROJECT_DIR/build/dist"
    tar czf "$PROJECT_DIR/build/dist/wyrdsekai-relay-${VERSION}.tar.gz" \
        -C "$PROJECT_DIR/build/relay" "wyrdsekai-relay-${VERSION}"
    # Publish to build/installers/ alongside the packages. That is the canonical
    # directory release tooling reads from — leaving the relay bundle only in
    # build/dist/ meant build/installers/ kept a stale copy indefinitely, and the
    # stale one was the pre-redaction bundle that still leaked household names.
    mkdir -p "$PROJECT_DIR/build/installers"
    cp -f "$PROJECT_DIR/build/dist/wyrdsekai-relay-${VERSION}.tar.gz" \
          "$PROJECT_DIR/build/installers/wyrdsekai-relay-${VERSION}.tar.gz"
    ok "Relay bundle: build/dist/wyrdsekai-relay-${VERSION}.tar.gz"
    ok "Published: build/installers/wyrdsekai-relay-${VERSION}.tar.gz"
    echo ""
fi

# ── Summary ──
echo ""
ok "Build complete!"
echo ""
echo "  Artifacts:"
[[ -f "$PROJECT_DIR/build/dist/wyrdsekai-${VERSION}.tar.gz" ]] && \
    echo "    $(du -sh "$PROJECT_DIR/build/dist/wyrdsekai-${VERSION}.tar.gz" | cut -f1)  build/dist/wyrdsekai-${VERSION}.tar.gz"
[[ -f "$PROJECT_DIR/build/dist/wyrdsekai-relay-${VERSION}.tar.gz" ]] && \
    echo "    $(du -sh "$PROJECT_DIR/build/dist/wyrdsekai-relay-${VERSION}.tar.gz" | cut -f1)  build/dist/wyrdsekai-relay-${VERSION}.tar.gz"
[[ -f "$PROJECT_DIR/build/deb/wyrdsekai_${VERSION}_${WYRDSEKAI_ARCH}.deb" ]] && \
    echo "    $(du -sh "$PROJECT_DIR/build/deb/wyrdsekai_${VERSION}_${WYRDSEKAI_ARCH}.deb" | cut -f1)  build/deb/wyrdsekai_${VERSION}_${WYRDSEKAI_ARCH}.deb"
[[ -f "$PROJECT_DIR/build/pkg/Wyrdsekai-${VERSION}.pkg" ]] && \
    echo "    $(du -sh "$PROJECT_DIR/build/pkg/Wyrdsekai-${VERSION}.pkg" | cut -f1)  build/pkg/Wyrdsekai-${VERSION}.pkg"
for msi in "$PROJECT_DIR"/build/win/Wyrdsekai-*.msi; do
    [[ -f "$msi" ]] && echo "    $(du -sh "$msi" | cut -f1)  build/win/$(basename "$msi")"
done
echo ""
