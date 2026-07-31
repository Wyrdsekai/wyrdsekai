#!/usr/bin/env bash
#
# fetch-nats-server.sh — download the nats-server binary for bundling.
#
# NATS is how nodes reach each other: the Between rides it, and a package
# without it has no cross-node bridge at all. The binary is gitignored (it is
# an upstream release artifact, not our source), and every packager stages it
# from packaging/ behind a fail-soft guard — so on a tree that never had it,
# the installer was built without NATS and said so in a single warning line.
#
# Windows has had packaging/windows/fetch-nats-server.ps1 for a while; this is
# the Linux/macOS counterpart, which never existed. That asymmetry is why a
# clone could build a .deb that quietly lacked what the .msi always had.
#
# Pinned rather than "latest": a package built today and one built next month
# should contain the same server. Bump deliberately after testing.
#
# Usage:
#   ./packaging/fetch-nats-server.sh
#   NATS_VERSION=v2.14.3 ./packaging/fetch-nats-server.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# v2.12.5 is what the 0.1.0 packages were built and tested against.
NATS_VERSION="${NATS_VERSION:-v2.12.5}"
DEST="$SCRIPT_DIR/nats-server"

info() { echo -e "\033[36m[fetch-nats]\033[0m $*"; }
ok()   { echo -e "\033[32m[fetch-nats]\033[0m $*"; }
warn() { echo -e "\033[33m[fetch-nats]\033[0m $*" >&2; }
err()  { echo -e "\033[31m[fetch-nats]\033[0m $*" >&2; }

if [[ -f "$DEST" && "${FORCE:-0}" != "1" ]]; then
    ok "already present: $("$DEST" --version 2>/dev/null | head -1 || echo nats-server)"
    exit 0
fi

case "$(uname -s)" in
    Linux)  os="linux"  ;;
    Darwin) os="darwin" ;;
    *)      err "unsupported OS $(uname -s) — use packaging/windows/fetch-nats-server.ps1 on Windows"; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  arch="amd64" ;;
    arm64|aarch64) arch="arm64" ;;
    *)             err "unsupported architecture $(uname -m)"; exit 1 ;;
esac

NAME="nats-server-${NATS_VERSION}-${os}-${arch}"
URL="https://github.com/nats-io/nats-server/releases/download/${NATS_VERSION}/${NAME}.tar.gz"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

info "fetching ${NATS_VERSION} for ${os}/${arch}"
if ! curl -fL --retry 5 --retry-delay 3 --retry-all-errors --progress-bar \
        -o "$tmp/nats.tar.gz" "$URL"; then
    err "download failed: $URL"
    exit 1
fi

tar xzf "$tmp/nats.tar.gz" -C "$tmp"
found="$(find "$tmp" -type f -name nats-server -perm -u+x | head -1)"
if [[ -z "$found" ]]; then
    err "archive did not contain a nats-server binary — upstream layout may have changed"
    exit 1
fi

cp "$found" "$DEST"
chmod +x "$DEST"
# Run it: a binary for the wrong architecture downloads and extracts perfectly
# happily, and only fails much later when the packaged node will not start.
if ! "$DEST" --version >/dev/null 2>&1; then
    err "the fetched binary does not execute here — wrong architecture?"
    rm -f "$DEST"
    exit 1
fi
ok "installed $("$DEST" --version | head -1) → packaging/nats-server"
