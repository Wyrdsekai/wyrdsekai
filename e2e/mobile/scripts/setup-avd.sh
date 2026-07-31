#!/usr/bin/env bash
# =============================================================================
# Create and boot the Wyrdsekai E2E test AVD
# =============================================================================
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

AVD_NAME="wyrd-e2e-api35"
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
DEVICE="pixel_9"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[*]${NC} $1"; }
ok()    { echo -e "${GREEN}[+]${NC} $1"; }
fail()  { echo -e "${RED}[-]${NC} $1"; exit 1; }

# Check prerequisites
[ -x "$SDKMANAGER" ] || fail "sdkmanager not found at $SDKMANAGER"
[ -x "$EMULATOR" ]   || fail "emulator not found at $EMULATOR"
[ -x "$ADB" ]        || fail "adb not found at $ADB"
[ -e /dev/kvm ]      || fail "KVM not available — emulator will be too slow"

# Download system image if needed
if ! $SDKMANAGER --list_installed 2>/dev/null | grep -q "system-images;android-35"; then
    info "Downloading system image..."
    yes | $SDKMANAGER "$SYSTEM_IMAGE" 2>&1 | tail -3
fi
ok "System image ready"

# Create AVD if it doesn't exist
if $AVDMANAGER list avd 2>/dev/null | grep -q "$AVD_NAME"; then
    ok "AVD '$AVD_NAME' already exists"
else
    info "Creating AVD '$AVD_NAME'..."
    echo "no" | $AVDMANAGER create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$DEVICE" --force
    ok "AVD created"
fi

# Boot if --boot flag passed
if [ "${1:-}" = "--boot" ]; then
    # Kill existing emulator if running
    if $ADB devices 2>/dev/null | grep -q "emulator"; then
        info "Killing existing emulator..."
        $ADB -s emulator-5554 emu kill 2>/dev/null || true
        sleep 3
    fi

    info "Booting emulator (you should see it on the desktop)..."
    $EMULATOR -avd "$AVD_NAME" -gpu auto -no-audio -no-snapshot-load &
    EMU_PID=$!

    info "Waiting for device..."
    $ADB wait-for-device
    # Wait for boot animation to finish
    WAITED=0
    while [ "$WAITED" -lt 120 ]; do
        BOOT=$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        if [ "$BOOT" = "1" ]; then
            ok "Emulator booted (PID $EMU_PID, ${WAITED}s)"
            exit 0
        fi
        sleep 2
        WAITED=$((WAITED + 2))
    done
    fail "Emulator boot timeout (120s)"
fi

ok "AVD ready. Boot with: $0 --boot"
