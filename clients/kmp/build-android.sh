#!/bin/bash
# Android Build script for Wyrdsekai KMP (Compose Multiplatform) client
# Usage: ./build-android.sh [options]
#
# Options:
#   --run               Build with default options (debug)
#   --release           Build release variant
#   --clean             Clean before building
#   --install           Install APK after build (reinstall, KEEPS app data)
#   --clean-install     Install after build, uninstalling first (WIPES app data).
#                       NOTE: distinct from --clean, which is a clean BUILD.
#   --start             Install and start app after build
#   --output <path>     Copy APK to this path after build
#   -h, --help          Show this help message
#
# Prerequisites:
#   - Android SDK (ANDROID_HOME or ~/Android/Sdk)
#   - Java 21+ (mise manages this via .mise.toml)
#
# Examples:
#   ./build-android.sh --run               # Debug APK
#   ./build-android.sh --install           # Build and install
#   ./build-android.sh --start             # Build, install, and start
#   ./build-android.sh --release --run     # Release APK
#   ./build-android.sh --clean --run       # Clean + rebuild

set -e

if [[ $# -eq 0 ]]; then
    head -23 "$0" | tail -22
    exit 0
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
VARIANT="debug"
DO_CLEAN=false          # clean BUILD (gradle clean + .cxx)
DO_CLEAN_INSTALL=false  # clean INSTALL (adb uninstall → wipes app data)
DO_INSTALL=false
DO_START=false
OUTPUT_PATH=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
    case $1 in
        --run)       shift ;;
        --release)   VARIANT="release"; shift ;;
        --clean)     DO_CLEAN=true; shift ;;
        --install)   DO_INSTALL=true; shift ;;
        --clean-install) DO_INSTALL=true; DO_CLEAN_INSTALL=true; shift ;;
        --start)     DO_INSTALL=true; DO_START=true; shift ;;
        --output)    OUTPUT_PATH="$2"; shift 2 ;;
        -h|--help)   head -23 "$0" | tail -22; exit 0 ;;
        *)           echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

echo -e "${GREEN}=== Wyrdsekai KMP Android Build ===${NC}"
echo "Variant: $VARIANT"

# Activate mise (picks up .mise.toml for Java)
if command -v mise &>/dev/null; then
    pushd "$SCRIPT_DIR/../.." >/dev/null 2>&1 || true
    eval "$(mise hook-env -s bash 2>/dev/null || true)"
    popd >/dev/null 2>&1 || true
fi

# Find Android SDK
if [[ -z "$ANDROID_HOME" ]]; then
    for dir in ~/Android/Sdk /opt/android-sdk "$HOME/Library/Android/sdk"; do
        if [[ -d "$dir" ]]; then
            export ANDROID_HOME="$dir"
            break
        fi
    done
fi

if [[ -z "$ANDROID_HOME" || ! -d "$ANDROID_HOME" ]]; then
    echo -e "${RED}Error: Android SDK not found. Set ANDROID_HOME.${NC}"
    exit 1
fi

echo -e "${GREEN}Java: $(java -version 2>&1 | head -1)${NC}"
echo -e "${GREEN}Android SDK: $ANDROID_HOME${NC}"

cd "$SCRIPT_DIR"

# Ensure llama.cpp source is present (needed for on-device inference)
LLAMA_CPP_DIR="$SCRIPT_DIR/llama.cpp"
if [[ ! -d "$LLAMA_CPP_DIR" ]]; then
    echo -e "${YELLOW}llama.cpp not found — cloning for on-device inference...${NC}"
    git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$LLAMA_CPP_DIR"
    echo -e "${GREEN}llama.cpp ready${NC}"
else
    echo -e "${GREEN}llama.cpp: $LLAMA_CPP_DIR${NC}"
fi

VARIANT_CAP="${VARIANT^}"
GRADLE_CMD="./gradlew :androidApp:assemble${VARIANT_CAP}"

if [[ "$DO_CLEAN" == true ]]; then
    echo -e "${YELLOW}Cleaning...${NC}"
    # Clean native CMake build cache (ensures recompile of llama.cpp JNI)
    rm -rf shared/.cxx
    ./gradlew clean
fi

echo "Running: $GRADLE_CMD"
eval "$GRADLE_CMD"

# Find APK
APK=$(find "$SCRIPT_DIR/androidApp/build/outputs/apk" -name "*.apk" -type f | head -1)
if [[ -z "$APK" ]]; then
    echo -e "${RED}Build failed — no APK found${NC}"
    exit 1
fi

SIZE=$(du -h "$APK" | cut -f1)
echo -e "${GREEN}APK: $APK ($SIZE)${NC}"

if [[ -n "$OUTPUT_PATH" ]]; then
    cp "$APK" "$OUTPUT_PATH"
    echo -e "${GREEN}Copied to: $OUTPUT_PATH${NC}"
fi

# Install
if [[ "$DO_INSTALL" == true ]]; then
    if [[ -x "$SCRIPT_DIR/install.sh" ]]; then
        local_flags=""
        [[ "$DO_START" == true ]] && local_flags="--start"
        # --clean-install → uninstall first, wiping app data. Distinct from the
        # --clean build flag, which only nukes build caches.
        [[ "${DO_CLEAN_INSTALL:-false}" == true ]] && local_flags="$local_flags --clean"
        "$SCRIPT_DIR/install.sh" --apk "$APK" $local_flags
    else
        echo -e "${YELLOW}Installing via adb...${NC}"
        if [[ "${DO_CLEAN_INSTALL:-false}" == true ]]; then
            adb uninstall org.wyrdsekai.kmp >/dev/null 2>&1 || true
            adb install "$APK"
        else
            adb install -r -d "$APK"
        fi
        if [[ "$DO_START" == true ]]; then
            adb shell am start -n org.wyrdsekai.kmp/org.wyrdsekai.app.android.MainActivity
        fi
    fi
fi

echo -e "${GREEN}=== Done ===${NC}"
if [[ "$DO_INSTALL" != true ]]; then
    echo ""
    echo "Install: ./install.sh --run"
    echo "    or:  adb install -r $APK"
fi
