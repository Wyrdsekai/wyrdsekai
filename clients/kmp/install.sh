#!/bin/bash
# Install APK to connected Android device
# Usage: ./install.sh [options]
#
# Options:
#   --run               Install with default options
#   --apk <path>        Use specific APK (default: latest debug build)
#   --start             Start the app after installation
#   --clean             Uninstall first (WIPES app data), then install fresh
#   --list              List connected devices and exit
#   --device <serial>   Target specific device by serial number
#   -h, --help          Show this help message
#
# Examples:
#   ./install.sh --run              # Install debug APK
#   ./install.sh --start            # Install and start
#   ./install.sh --clean --start    # Wipe app data, install fresh, start

set -e

if [[ $# -eq 0 ]]; then
    head -17 "$0" | tail -16
    exit 0
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
APK_PATH=""
DEVICE_SERIAL=""
LIST_DEVICES=false
START_APP=false
CLEAN_INSTALL=false
PACKAGE_NAME="org.wyrdsekai.kmp"
ACTIVITY_CLASS="org.wyrdsekai.app.android.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
    case $1 in
        --run)      shift ;;
        --apk)      APK_PATH="$2"; shift 2 ;;
        --start)    START_APP=true; shift ;;
        --clean)    CLEAN_INSTALL=true; shift ;;
        --list)     LIST_DEVICES=true; shift ;;
        --device)   DEVICE_SERIAL="$2"; shift 2 ;;
        -h|--help)  head -17 "$0" | tail -16; exit 0 ;;
        *)          echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# Find adb
find_adb() {
    if command -v adb &>/dev/null; then
        ADB="adb"
        return
    fi
    for dir in "$ANDROID_HOME" "$HOME/Android/Sdk" /opt/android-sdk; do
        if [[ -x "$dir/platform-tools/adb" ]]; then
            ADB="$dir/platform-tools/adb"
            return
        fi
    done
    echo -e "${RED}Error: adb not found${NC}"
    exit 1
}

adb_cmd() {
    if [[ -n "$DEVICE_SERIAL" ]]; then
        $ADB -s "$DEVICE_SERIAL" "$@"
    else
        $ADB "$@"
    fi
}

find_adb

if [[ "$LIST_DEVICES" == true ]]; then
    $ADB devices -l
    exit 0
fi

# Check device
DEVICE_COUNT=$($ADB devices | grep -v "^List" | grep -v "^$" | wc -l)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo -e "${RED}No Android devices connected${NC}"
    exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 && -z "$DEVICE_SERIAL" ]]; then
    echo -e "${YELLOW}Multiple devices. Specify one with --device <serial>${NC}"
    $ADB devices -l
    exit 1
fi

# Find APK
if [[ -z "$APK_PATH" ]]; then
    # Prefer a DEBUG apk, and never auto-pick an unsigned one: the release
    # variant builds as androidApp-release-unsigned.apk, which `adb install`
    # always refuses (no signature). A bare `find ... | head -1` returned
    # exactly that, so `./install.sh --run` failed on a tree that had a
    # perfectly good debug APK sitting next to it (found 2026-07-29).
    APK_PATH=$(find "$SCRIPT_DIR/androidApp/build/outputs/apk" -name "*.apk" -type f 2>/dev/null \
                 | grep -v -- '-unsigned' | grep '/debug/' | head -1)
    # Fall back to any SIGNED apk if there is no debug build.
    if [[ -z "$APK_PATH" ]]; then
        APK_PATH=$(find "$SCRIPT_DIR/androidApp/build/outputs/apk" -name "*.apk" -type f 2>/dev/null \
                     | grep -v -- '-unsigned' | head -1)
    fi
fi

if [[ -n "$APK_PATH" && "$APK_PATH" == *-unsigned.apk ]]; then
    echo -e "${RED}Refusing to install an unsigned APK: $APK_PATH${NC}"
    echo -e "${YELLOW}Build a debug variant, or sign the release first.${NC}"
    exit 1
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
    echo -e "${RED}APK not found${NC}"
    echo "Run ./build-android.sh --run first"
    exit 1
fi

SIZE=$(du -h "$APK_PATH" | cut -f1)
echo -e "${GREEN}=== Wyrdsekai KMP Install ===${NC}"
echo -e "APK: ${YELLOW}$(basename "$APK_PATH")${NC} ($SIZE)"

# ── Install ───────────────────────────────────────────────────────────────────
# Two modes, because "install over the top" and "start from nothing" are
# genuinely different things and conflating them either loses a companion's
# data or leaves stale state behind:
#
#   default   reinstall in place, KEEPING app data (accounts, zone bank, the
#             local companion's soul + journal). This is what you want between
#             ordinary code changes.
#   --clean   uninstall first, which wipes app data, then install fresh. This
#             is what you want when testing onboarding, or when the reinstall
#             is refused (see below).
#
# `adb install -r` alone is not enough for a reliable reinstall:
#   * -r  reinstall keeping data
#   * -d  allow version DOWNGRADE — without it, installing an older build over
#         a newer one fails with INSTALL_FAILED_VERSION_DOWNGRADE, which
#         happens constantly when switching branches.
# Neither flag can cross a SIGNATURE change (debug key vs release key, or a
# regenerated debug keystore): Android refuses with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. That refusal is correct and we do NOT
# silently uninstall around it — wiping someone's companion because a build
# key changed is not a decision a build script gets to make. We say what
# happened and let the caller choose --clean.
is_installed() {
    adb_cmd shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$PACKAGE_NAME"
}

do_clean_uninstall() {
    if is_installed; then
        echo -e "${YELLOW}Clean install: uninstalling $PACKAGE_NAME (this WIPES app data)...${NC}"
        # `pm uninstall --user 0` also clears data and works where the package
        # was installed for a single user (the emulator case).
        if ! adb_cmd uninstall "$PACKAGE_NAME" 2>&1 | tr -d '\r' | grep -q '^Success'; then
            adb_cmd shell pm uninstall --user 0 "$PACKAGE_NAME" >/dev/null 2>&1 || true
        fi
        if is_installed; then
            echo -e "${RED}Could not uninstall $PACKAGE_NAME — aborting rather than installing over it${NC}"
            exit 1
        fi
        echo -e "${GREEN}Uninstalled (data wiped)${NC}"
    else
        echo -e "${YELLOW}Clean install: $PACKAGE_NAME was not installed — nothing to wipe${NC}"
    fi
}

if [[ "$CLEAN_INSTALL" == true ]]; then
    do_clean_uninstall
    echo -e "${YELLOW}Installing (fresh)...${NC}"
    INSTALL_OUT=$(adb_cmd install "$APK_PATH" 2>&1 | tr -d '\r') || true
else
    if is_installed; then
        echo -e "${YELLOW}Reinstalling over the existing app (data kept)...${NC}"
    else
        echo -e "${YELLOW}Installing...${NC}"
    fi
    INSTALL_OUT=$(adb_cmd install -r -d "$APK_PATH" 2>&1 | tr -d '\r') || true
fi

if ! grep -q '^Success' <<< "$INSTALL_OUT"; then
    echo "$INSTALL_OUT" | sed 's/^/    /'
    if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match' <<< "$INSTALL_OUT"; then
        echo -e "${RED}The installed app was signed with a different key.${NC}"
        echo -e "${YELLOW}Android will not reinstall over it. Re-run with --clean to uninstall"
        echo -e "first — that WIPES app data (accounts, zone bank, local companion).${NC}"
    fi
    echo -e "${RED}Install failed${NC}"
    exit 1
fi
echo -e "${GREEN}Installed!${NC}"

if [[ "$START_APP" == true ]]; then
    echo -e "${YELLOW}Starting...${NC}"
    adb_cmd shell am start -n "$PACKAGE_NAME/$ACTIVITY_CLASS"
    echo -e "${GREEN}Started${NC}"
fi

echo -e "${GREEN}=== Done ===${NC}"
