#!/usr/bin/env bash
#
# build-menubar.sh — compile the Wyrdsekai macOS menu-bar app into a .app bundle.
#
# Produces build/macos-app/Wyrdsekai.app (AppKit + WebKit, ad-hoc signed).
# build-pkg.sh calls this and stages the result into /Applications.
#
# Usage:  WYRDSEKAI_VERSION=0.2.1 ./packaging/macos/menubar/build-menubar.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
VERSION="${WYRDSEKAI_VERSION:-0.2.1}"

OUT_DIR="$PROJECT_DIR/build/macos-app"
APP="$OUT_DIR/Wyrdsekai.app"

info() { echo -e "\033[36m[menubar]\033[0m $*"; }
ok()   { echo -e "\033[32m[menubar]\033[0m $*"; }
err()  { echo -e "\033[31m[menubar]\033[0m $*" >&2; }

if [[ "$(uname -s)" != "Darwin" ]]; then
    err "menu-bar app must be built on macOS (needs swiftc + AppKit/WebKit)"
    exit 1
fi
if ! command -v swiftc >/dev/null 2>&1; then
    err "swiftc not found — install Xcode / command line tools (xcode-select --install)"
    exit 1
fi

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# Info.plist (substitute version)
sed "s/__VERSION__/$VERSION/g" "$SCRIPT_DIR/Info.plist" > "$APP/Contents/Info.plist"

# App icon (Finder / Launchpad / "Open in browser" Dock-less app icon). Prefer a prebuilt
# wyrdsekai.icns if present; otherwise generate one from the repo's PNG logo so the app
# shows the real Wyrdsekai mark instead of the generic blank app icon. (Without this the
# CFBundleIconFile reference resolves to nothing → macOS falls back to the default icon.)
ICON_DST="$APP/Contents/Resources/wyrdsekai.icns"
if [[ -f "$SCRIPT_DIR/wyrdsekai.icns" ]]; then
    cp "$SCRIPT_DIR/wyrdsekai.icns" "$ICON_DST"
else
    ICON_SRC="$PROJECT_DIR/img/icons-light/icon-rounded-1024.png"
    [[ -f "$ICON_SRC" ]] || ICON_SRC="$PROJECT_DIR/img/icons-light/icon-1024.png"
    if [[ -f "$ICON_SRC" ]] && command -v iconutil >/dev/null 2>&1 && command -v sips >/dev/null 2>&1; then
        ICONSET="$(mktemp -d)/wyrdsekai.iconset"; mkdir -p "$ICONSET"
        for sz in 16 32 128 256 512; do
            sips -z "$sz"        "$sz"        "$ICON_SRC" --out "$ICONSET/icon_${sz}x${sz}.png"    >/dev/null 2>&1
            sips -z "$((sz*2))"  "$((sz*2))"  "$ICON_SRC" --out "$ICONSET/icon_${sz}x${sz}@2x.png" >/dev/null 2>&1
        done
        if iconutil -c icns "$ICONSET" -o "$ICON_DST" 2>/dev/null; then
            ok "Generated app icon from $(basename "$ICON_SRC")"
        else
            info "iconutil failed — Finder icon will be generic"
        fi
        rm -rf "$(dirname "$ICONSET")"
    else
        info "No app-icon source found — Finder icon will be generic (drop a wyrdsekai.icns here to override)"
    fi
fi

# Menu-bar status-bar glyph — the brand mark (torii + cats) as a monochrome template that
# adapts to light/dark menu bars. @2x supplies the Retina rep. (See main.swift statusItem.)
for f in menubar-icon.png menubar-icon@2x.png; do
    [[ -f "$SCRIPT_DIR/$f" ]] && cp "$SCRIPT_DIR/$f" "$APP/Contents/Resources/$f"
done

info "Compiling Swift sources (arm64 + x86_64 universal where possible)..."
ARCH_FLAGS=()
# Build universal if both slices are available; otherwise native arch.
if [[ "$(uname -m)" == "arm64" ]]; then
    ARCH_FLAGS=(-target arm64-apple-macos13.0)
else
    ARCH_FLAGS=(-target x86_64-apple-macos13.0)
fi

swiftc -O \
    "${ARCH_FLAGS[@]}" \
    -framework Cocoa -framework WebKit \
    -o "$APP/Contents/MacOS/Wyrdsekai" \
    "$SCRIPT_DIR"/Sources/*.swift

# Ad-hoc codesign so Gatekeeper lets it run locally (the .pkg is the trust
# boundary; a Developer ID signature can be layered on later for distribution).
if command -v codesign >/dev/null 2>&1; then
    codesign --force --deep --sign - "$APP" 2>/dev/null \
        && info "ad-hoc signed" || info "codesign skipped (non-fatal)"
fi

ok "Built $APP"
du -sh "$APP"
