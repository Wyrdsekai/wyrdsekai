#!/bin/bash
# iOS Build script for Wyrdsekai React Native client (EAS-based)
# Usage: ./build-ios.sh [options]
#
# Options:
#   --run               Run build with default options (local profile)
#   --profile <name>    EAS build profile (local, local-simulator, preview, production). Default: local
#   --output <path>     Output path for .ipa file
#   --clean             Run prebuild --clean before building
#   --no-keychain       Skip EXPO_NO_KEYCHAIN=1 (use if running locally with GUI)
#   -h, --help          Show this help message
#
# Prerequisites:
#   - macOS with Xcode
#   - EAS CLI: npm install -g eas-cli
#   - Apple Developer account (prompted during build)
#
# Examples:
#   ./build-ios.sh --run                    # Build with local profile
#   ./build-ios.sh --profile preview        # Build preview
#   ./build-ios.sh --clean --run            # Clean prebuild, then build
#   ./build-ios.sh --output ~/ota/app.ipa   # Build to specific location

set -e

if [[ $# -eq 0 ]]; then
    head -22 "$0" | tail -21
    exit 0
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
PROFILE="local"
OUTPUT_PATH=""
DO_CLEAN=false
USE_NO_KEYCHAIN=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
    case $1 in
        --run)          shift ;;
        --profile)      PROFILE="$2"; shift 2 ;;
        --output)       OUTPUT_PATH="$2"; shift 2 ;;
        --clean)        DO_CLEAN=true; shift ;;
        --no-keychain)  USE_NO_KEYCHAIN=false; shift ;;
        -h|--help)      head -22 "$0" | tail -21; exit 0 ;;
        *)              echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

echo -e "${GREEN}=== Wyrdsekai RN iOS Build ===${NC}"
echo "Profile: $PROFILE"

# --- Setup tools ---

setup_env() {
    # Activate brew
    if [[ -f /opt/homebrew/bin/brew ]]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    fi

    # Activate rbenv (for CocoaPods)
    if command -v rbenv &>/dev/null; then
        eval "$(rbenv init - bash 2>/dev/null || true)"
    fi

    # Activate mise from repo root
    local MISE_BIN="${HOME}/.local/bin/mise"
    if [[ -x "$MISE_BIN" ]]; then
        pushd "$SCRIPT_DIR/../.." >/dev/null 2>&1 || true
        eval "$("$MISE_BIN" hook-env -s bash 2>/dev/null || true)"
        popd >/dev/null 2>&1 || true
    elif command -v mise &>/dev/null; then
        pushd "$SCRIPT_DIR/../.." >/dev/null 2>&1 || true
        eval "$(mise hook-env -s bash 2>/dev/null || true)"
        popd >/dev/null 2>&1 || true
    fi
}

check_prereqs() {
    echo -e "${YELLOW}Checking prerequisites...${NC}"

    if ! command -v xcodebuild &>/dev/null; then
        echo -e "${RED}Error: Xcode not found${NC}"
        exit 1
    fi

    if ! command -v node &>/dev/null; then
        echo -e "${RED}Error: Node.js not found${NC}"
        exit 1
    fi

    # Ensure pnpm
    if ! command -v pnpm &>/dev/null; then
        echo -e "${YELLOW}Installing pnpm...${NC}"
        npm install -g pnpm
    fi

    # Ensure EAS CLI
    if ! command -v eas &>/dev/null; then
        echo -e "${YELLOW}Installing EAS CLI...${NC}"
        npm install -g eas-cli
    fi

    echo -e "${GREEN}Prerequisites OK (Xcode $(xcodebuild -version | head -1 | awk '{print $2}'), Node $(node --version))${NC}"
}

install_deps() {
    if [[ ! -d "$SCRIPT_DIR/node_modules" ]]; then
        echo -e "${YELLOW}Installing dependencies (pnpm)...${NC}"
        cd "$SCRIPT_DIR"
        pnpm install
    fi
}

run_clean_prebuild() {
    echo -e "${YELLOW}Running clean prebuild...${NC}"
    cd "$SCRIPT_DIR"
    npx expo prebuild --clean -p ios
    echo -e "${GREEN}Prebuild complete${NC}"
}

# --- Build ---

build() {
    echo -e "${YELLOW}Building iOS app via EAS...${NC}"
    cd "$SCRIPT_DIR"

    local EAS_CMD="eas build --profile $PROFILE --platform ios --local"

    if [[ -n "$OUTPUT_PATH" ]]; then
        EAS_CMD="$EAS_CMD --output $OUTPUT_PATH"
    fi

    # Skip keychain for SSH sessions
    if [[ "$USE_NO_KEYCHAIN" == true ]]; then
        echo -e "${YELLOW}Using EXPO_NO_KEYCHAIN=1 (for SSH sessions)${NC}"
        EAS_CMD="EXPO_NO_KEYCHAIN=1 $EAS_CMD"
    fi

    echo "Running: $EAS_CMD"
    eval "$EAS_CMD"

    echo -e "${GREEN}Build complete!${NC}"

    echo -e "${GREEN}.ipa location:${NC}"
    if [[ -n "$OUTPUT_PATH" ]]; then
        echo "  $OUTPUT_PATH"
    else
        find "$SCRIPT_DIR" -maxdepth 1 -name "*.ipa" -type f -mmin -10 | while read ipa; do
            local SIZE=$(du -h "$ipa" | cut -f1)
            echo "  $ipa ($SIZE)"
        done
    fi
}

# --- Main ---

cd "$SCRIPT_DIR"
setup_env
check_prereqs
install_deps

if [[ "$DO_CLEAN" == true ]]; then
    run_clean_prebuild
fi

build

echo -e "${GREEN}=== Done ===${NC}"
echo ""
echo "Next steps:"
echo "  1. Deploy: ./deploy-ios.sh --run"
echo "  2. Enable Developer Mode on iPhone (Settings > Privacy & Security > Developer Mode)"
echo "  3. Trust the certificate (Settings > General > VPN & Device Management)"
