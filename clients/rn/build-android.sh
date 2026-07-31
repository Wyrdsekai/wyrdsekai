#!/bin/bash
# Android Build script for Wyrdsekai React Native client
# Usage: ./build-android.sh [options]
#
# Options:
#   --run               Build with default options (debug, arm64)
#   --release           Build release variant
#   --clean             Clean before building
#   --all-arch          Build all architectures (slower, for distribution)
#   --install           Install APK after build (reinstall, KEEPS app data)
#   --clean-install     Install after build, uninstalling first (WIPES app data).
#                       NOTE: distinct from --clean, which is a clean BUILD.
#   --start             Install and start app after build
#   --prebuild          Force expo prebuild before building
#   --output <path>     Copy APK to this path after build
#   -h, --help          Show this help message
#
# Prerequisites:
#   - Android SDK (ANDROID_HOME or ~/Android/Sdk)
#   - Node.js 18+ (20+ recommended)
#   - pnpm (auto-installed if missing)
#
# Examples:
#   ./build-android.sh --run               # Debug APK, arm64 only
#   ./build-android.sh --install           # Build and install to device
#   ./build-android.sh --start             # Build, install, and start
#   ./build-android.sh --release --run     # Release APK
#   ./build-android.sh --clean --run       # Clean + rebuild

set -e

if [[ $# -eq 0 ]]; then
    head -25 "$0" | tail -24
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
ALL_ARCH=false
DO_INSTALL=false
DO_START=false
DO_PREBUILD=false
OUTPUT_PATH=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --run)       shift ;;
        --release)   VARIANT="release"; shift ;;
        --clean)     DO_CLEAN=true; shift ;;
        --all-arch)  ALL_ARCH=true; shift ;;
        --install)   DO_INSTALL=true; shift ;;
        --clean-install) DO_INSTALL=true; DO_CLEAN_INSTALL=true; shift ;;
        --start)     DO_INSTALL=true; DO_START=true; shift ;;
        --prebuild)  DO_PREBUILD=true; shift ;;
        --output)    OUTPUT_PATH="$2"; shift 2 ;;
        -h|--help)   head -25 "$0" | tail -24; exit 0 ;;
        *)           echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

echo -e "${GREEN}=== Wyrdsekai RN Android Build ===${NC}"
echo "Variant: $VARIANT"

# --- Setup Node ---

setup_node() {
    # Try mise first (project standard)
    if command -v mise &>/dev/null; then
        echo -e "${YELLOW}Setting up Node via mise...${NC}"
        eval "$(mise hook-env -s bash 2>/dev/null || true)"
    # Try fnm
    elif [[ -x "$HOME/.local/share/fnm/fnm" ]]; then
        echo -e "${YELLOW}Setting up Node via fnm...${NC}"
        eval "$("$HOME/.local/share/fnm/fnm" env)"
        "$HOME/.local/share/fnm/fnm" use 20 --silent-if-unchanged 2>/dev/null || true
    elif command -v fnm &>/dev/null; then
        eval "$(fnm env)"
        fnm use 20 --silent-if-unchanged 2>/dev/null || true
    # Try nvm
    elif [[ -f "$HOME/.nvm/nvm.sh" ]]; then
        source "$HOME/.nvm/nvm.sh"
        nvm use 20 2>/dev/null || true
    fi

    NODE_VERSION=$(node --version 2>/dev/null || echo "not found")
    echo -e "${GREEN}Node: $NODE_VERSION${NC}"

    # Ensure pnpm is available
    if ! command -v pnpm &>/dev/null; then
        echo -e "${YELLOW}Installing pnpm...${NC}"
        npm install -g pnpm
    fi
}

# --- Find Java 21 (Gradle 8.x) ---

find_java21() {
    # Check mise
    local MISE_JAVA=$(mise where java@temurin-21 2>/dev/null)
    if [[ -n "$MISE_JAVA" && -d "$MISE_JAVA" ]]; then
        export JAVA_HOME="$MISE_JAVA"
        return
    fi

    # Check common locations
    for dir in /usr/lib/jvm/temurin-21* /usr/lib/jvm/java-21* /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home; do
        if [[ -d "$dir" ]]; then
            export JAVA_HOME="$dir"
            return
        fi
    done

    # Current Java might work if it's 21
    local JV=$(java -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"')
    if [[ "$JV" == "21" ]]; then
        return
    fi

    echo -e "${YELLOW}Warning: Java 21 not found. Gradle 8.x may fail with Java 25.${NC}"
}

# --- Find Android SDK ---

find_sdk() {
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

    echo -e "${GREEN}Android SDK: $ANDROID_HOME${NC}"

    # Ensure local.properties exists
    if [[ -d "$SCRIPT_DIR/android" && ! -f "$SCRIPT_DIR/android/local.properties" ]]; then
        echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/android/local.properties"
    fi
}

# --- Install deps ---

install_deps() {
    # Reinstall when node_modules is missing OR stale (package.json newer
    # than the last install) OR on --clean. A stale tree fails late in the
    # Metro bundle with "Unable to resolve module <newly-added-dep>".
    if [[ ! -d "$SCRIPT_DIR/node_modules" ]] \
        || [[ "$SCRIPT_DIR/package.json" -nt "$SCRIPT_DIR/node_modules/.modules.yaml" ]] \
        || [[ "${DO_CLEAN:-false}" == "true" ]]; then
        echo -e "${YELLOW}Installing dependencies (pnpm)...${NC}"
        cd "$SCRIPT_DIR"
        pnpm install
    fi
}

# --- Generate android/ ---

generate_android() {
    # Detect broken/missing android project (no build.gradle = needs prebuild)
    if [[ ! -d "$SCRIPT_DIR/android" ]] || [[ ! -f "$SCRIPT_DIR/android/app/build.gradle" ]]; then
        echo -e "${YELLOW}Android project missing or incomplete — running expo prebuild...${NC}"
        DO_PREBUILD=true
    fi

    if [[ "$DO_PREBUILD" == true ]]; then
        cd "$SCRIPT_DIR"
        npx expo prebuild --platform android --no-install

        # Ensure gradle.properties exists and has required settings
        local GP="$SCRIPT_DIR/android/gradle.properties"
        if [[ ! -f "$GP" ]]; then
            touch "$GP"
        fi
        # JVM heap for release builds (native C++ compilation needs memory)
        if ! grep -q "org.gradle.jvmargs" "$GP"; then
            echo "org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m" >> "$GP"
        fi
        # newArchEnabled for llama.rn TurboModules
        if ! grep -q "newArchEnabled=true" "$GP"; then
            echo "newArchEnabled=true" >> "$GP"
        fi
        # arm64 only by default
        if ! grep -q "reactNativeArchitectures" "$GP"; then
            echo "reactNativeArchitectures=arm64-v8a" >> "$GP"
        fi

        # Ensure local.properties
        if [[ -n "$ANDROID_HOME" && ! -f "$SCRIPT_DIR/android/local.properties" ]]; then
            echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/android/local.properties"
        fi
    fi

    # Create llama.rn build dirs so Metro file watcher doesn't crash
    mkdir -p "$SCRIPT_DIR/node_modules/llama.rn/android/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug" 2>/dev/null || true

    overlay_native_android
}

# --- Overlay hand-authored native Android sources ---
#
# The generated `android/` tree is gitignored, so our custom Kotlin (household-CA
# TLS pinning + the OkHttp factory wiring in MainApplication) is NOT versioned
# there. The source of truth is the TRACKED `native-android/` dir; copy it into
# the app package on EVERY build, AFTER prebuild, so:
#   • `git pull` delivers changes to these files (fixing "reinstall doesn't pick
#     up the fix" — the android/ copy is untracked and never updated by git), and
#   • a fresh clone / `expo prebuild --clean` gets the wired MainApplication +
#     trust code back instead of a default one with no household TLS at all.
overlay_native_android() {
    local src="$SCRIPT_DIR/native-android/org/wyrdsekai/rn"
    local dst="$SCRIPT_DIR/android/app/src/main/java/org/wyrdsekai/rn"
    [[ -d "$src" ]] || return 0
    mkdir -p "$dst"
    cp "$src"/*.kt "$dst"/ 2>/dev/null || true
    echo -e "${GREEN}Overlaid tracked native Android sources (household TLS + wiring)${NC}"
}

# --- Pre-bundle JS for debug (no Metro needed on device) ---

bundle_js_for_debug() {
    if [[ "$VARIANT" != "debug" ]]; then
        return  # Release builds bundle JS automatically
    fi

    local ASSETS_DIR="$SCRIPT_DIR/android/app/src/main/assets"
    local BUNDLE_FILE="$ASSETS_DIR/index.android.bundle"

    mkdir -p "$ASSETS_DIR"

    echo -e "${YELLOW}Bundling JS for debug (no Metro needed)...${NC}"
    cd "$SCRIPT_DIR"
    # --dev false (2026-07-19): this embedded bundle is the NO-METRO fallback that
    # ships inside the APK. Built with `--dev true`, RN's new bridgeless runtime
    # tries to open a devtools websocket at startup, throws "Cannot create devtools
    # websocket connections in embedded environments", and aborts BEFORE
    # AppRegistry.registerComponent — so "main" never registers and the app opens
    # to the giant red "main has not been registered" screen. A production-mode
    # bundle runs standalone with no dev-server dependency. (Developing WITH Metro
    # still works — Metro serves its own dev bundle over the network and the
    # embedded one is ignored.)
    npx react-native bundle \
        --platform android \
        --dev false \
        --entry-file index.js \
        --bundle-output "$BUNDLE_FILE" \
        --assets-dest "$SCRIPT_DIR/android/app/src/main/res/"

    local BSIZE=$(du -h "$BUNDLE_FILE" | cut -f1)
    echo -e "${GREEN}JS bundle: $BUNDLE_FILE ($BSIZE)${NC}"
}

# --- Build ---

build() {
    cd "$SCRIPT_DIR/android"

    local VARIANT_CAP="${VARIANT^}"
    local GRADLE_CMD="./gradlew assemble${VARIANT_CAP} -x lint -x test --build-cache"

    if [[ "$ALL_ARCH" == false ]]; then
        GRADLE_CMD="$GRADLE_CMD -PreactNativeArchitectures=arm64-v8a"
        echo -e "${YELLOW}Building arm64 only (use --all-arch for all)${NC}"
    fi

    if [[ "$DO_CLEAN" == true ]]; then
        echo -e "${YELLOW}Cleaning...${NC}"
        # Nuke the CMake/ninja caches BEFORE gradle clean: after a pnpm
        # install churns node_modules, the stale .cxx cache references
        # codegen jni dirs that no longer exist, and ninja re-runs CMake
        # against them — so even `gradlew clean` fails. With .cxx gone,
        # externalNativeBuildClean is a no-op and codegen regenerates on
        # the next build.
        rm -rf "$SCRIPT_DIR/android/app/.cxx" \
               "$SCRIPT_DIR/android/app/build/generated/autolinking"
        ./gradlew clean
    fi

    echo "Running: $GRADLE_CMD"
    eval "$GRADLE_CMD"

    cd "$SCRIPT_DIR"

    # Find APK
    APK=$(find "$SCRIPT_DIR/android/app/build/outputs/apk" -name "*.apk" -path "*/$VARIANT/*" -type f | head -1)
    if [[ -z "$APK" ]]; then
        echo -e "${RED}Build failed — no APK found${NC}"
        exit 1
    fi

    local SIZE=$(du -h "$APK" | cut -f1)
    echo -e "${GREEN}APK: $APK ($SIZE)${NC}"

    if [[ -n "$OUTPUT_PATH" ]]; then
        cp "$APK" "$OUTPUT_PATH"
        echo -e "${GREEN}Copied to: $OUTPUT_PATH${NC}"
    fi
}

# --- Install ---

install_apk() {
    if [[ -z "$APK" ]]; then
        echo -e "${RED}No APK to install${NC}"
        exit 1
    fi

    local INSTALL_FLAGS=""
    if [[ "$DO_START" == true ]]; then
        INSTALL_FLAGS="--start"
    fi
    # --clean-install → uninstall first, wiping app data. Distinct from the
    # --clean build flag above, which only nukes build caches.
    if [[ "${DO_CLEAN_INSTALL:-false}" == true ]]; then
        INSTALL_FLAGS="$INSTALL_FLAGS --clean"
    fi

    if [[ -x "$SCRIPT_DIR/install.sh" ]]; then
        "$SCRIPT_DIR/install.sh" --apk "$APK" $INSTALL_FLAGS
    else
        echo -e "${YELLOW}Installing via adb...${NC}"
        if [[ "${DO_CLEAN_INSTALL:-false}" == true ]]; then
            adb uninstall org.wyrdsekai.rn >/dev/null 2>&1 || true
            adb install "$APK"
        else
            adb install -r -d "$APK"
        fi
        if [[ "$DO_START" == true ]]; then
            adb shell am start -n org.wyrdsekai.rn/.MainActivity
        fi
    fi
}

# --- Main ---

cd "$SCRIPT_DIR"
setup_node
find_java21
find_sdk
install_deps
generate_android
bundle_js_for_debug
build

if [[ "$DO_INSTALL" == true ]]; then
    install_apk
fi

echo -e "${GREEN}=== Done ===${NC}"
if [[ "$DO_INSTALL" != true ]]; then
    echo ""
    echo "Install: ./install.sh --run"
    echo "    or:  adb install -r $APK"
fi
