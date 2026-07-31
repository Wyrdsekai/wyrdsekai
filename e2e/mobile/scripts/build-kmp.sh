#!/usr/bin/env bash
# =============================================================================
# Build KMP Android APK for E2E testing
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
KMP_DIR="$PROJECT_ROOT/clients/kmp"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

info() { echo -e "\033[0;34m[*]\033[0m $1"; }
ok()   { echo -e "\033[0;32m[+]\033[0m $1"; }

cd "$KMP_DIR"

# Build debug APK (universal — includes x86_64 by default unless filtered)
info "Building KMP debug APK..."
./gradlew :androidApp:assembleDebug --no-daemon 2>&1 | tail -5

APK_PATH=$(find androidApp/build -name "*.apk" -path "*/debug/*" | head -1)
if [ -z "$APK_PATH" ]; then
    echo "ERROR: No debug APK found" >&2
    exit 1
fi

FULL_PATH="$(cd "$(dirname "$APK_PATH")" && pwd)/$(basename "$APK_PATH")"
ok "APK: $FULL_PATH"
echo "$FULL_PATH"
