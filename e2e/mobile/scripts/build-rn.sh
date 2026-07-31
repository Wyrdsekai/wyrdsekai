#!/usr/bin/env bash
# =============================================================================
# Build React Native APK for E2E testing (includes x86_64 for emulator)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RN_DIR="$PROJECT_ROOT/clients/rn"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

info() { echo -e "\033[0;34m[*]\033[0m $1"; }
ok()   { echo -e "\033[0;32m[+]\033[0m $1"; }

cd "$RN_DIR"

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    info "Installing dependencies..."
    pnpm install 2>&1 | tail -3
fi

# Prebuild Android if needed
if [ ! -d "android" ]; then
    info "Running expo prebuild..."
    npx expo prebuild --platform android 2>&1 | tail -5
fi

# Bundle JS into assets (debug builds normally load from Metro dev server,
# but E2E tests run without Metro, so we need the bundle embedded).
info "Bundling JS into assets..."
mkdir -p android/app/src/main/assets
npx react-native bundle \
    --platform android \
    --dev false \
    --entry-file index.js \
    --bundle-output android/app/src/main/assets/index.android.bundle \
    --assets-dest android/app/src/main/res 2>&1 | tail -3

# Build debug APK with x86_64 support (for emulator) + arm64 (for device)
# Use JDK 21 for RN Android build — JDK 25 triggers JEP 472 restricted method
# warnings that break CMake configuration in Gradle 9.0.
info "Building RN debug APK (x86_64 + arm64)..."
cd android
RN_JAVA_HOME="${JAVA_HOME:-}"
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
elif [ -d "/usr/lib/jvm/java-21-openjdk" ]; then
    export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"
fi
./gradlew assembleDebug \
    -PreactNativeArchitectures="x86_64,arm64-v8a" \
    --no-daemon 2>&1 | tail -5
# Restore JAVA_HOME
if [ -n "$RN_JAVA_HOME" ]; then export JAVA_HOME="$RN_JAVA_HOME"; else unset JAVA_HOME; fi

APK_PATH=$(find . -name "*.apk" -path "*/debug/*" | head -1)
if [ -z "$APK_PATH" ]; then
    echo "ERROR: No debug APK found" >&2
    exit 1
fi

FULL_PATH="$(cd "$(dirname "$APK_PATH")" && pwd)/$(basename "$APK_PATH")"
ok "APK: $FULL_PATH"
echo "$FULL_PATH"
