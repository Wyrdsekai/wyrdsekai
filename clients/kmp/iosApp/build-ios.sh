#!/bin/bash
# iOS Build script for Wyrdsekai KMP (Compose Multiplatform) client
# Uses Fastlane for signing + building (certs stored locally)
#
# Usage: ./build-ios.sh [options]
#
# Options:
#   --run               Build with default options (debug)
#   --release           Build release configuration
#   --certs             Only fetch/create signing certs (first-time setup)
#   --clean             Clean before building
#   --simulator         Build for iOS Simulator only (no signing needed)
#   -h, --help          Show this help message
#
# First-time setup:
#   ./build-ios.sh --certs       # Creates signing cert + provisioning profile
#   ./build-ios.sh --run         # Build .ipa
#
# Prerequisites:
#   - macOS with Xcode
#   - Fastlane (brew install fastlane)
#   - Java 21+ (for KMP shared framework via Gradle)
#   - Apple Developer account
#
# Examples:
#   ./build-ios.sh --run                # Debug .ipa
#   ./build-ios.sh --release --run      # Release .ipa
#   ./build-ios.sh --certs              # Setup signing only
#   ./build-ios.sh --simulator          # Simulator build (no signing)
#   ./build-ios.sh --clean --run        # Clean + build

set -e

if [[ $# -eq 0 ]]; then
    head -29 "$0" | tail -28
    exit 0
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
LANE="build"
CERTS_ONLY=false
DO_CLEAN=false
SIMULATOR=false
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KMP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="$SCRIPT_DIR/WyrdsekaiKMP.xcodeproj"
SCHEME="WyrdsekaiKMP"

while [[ $# -gt 0 ]]; do
    case $1 in
        --run)       shift ;;
        --release)   LANE="release"; shift ;;
        --certs)     CERTS_ONLY=true; shift ;;
        --clean)     DO_CLEAN=true; shift ;;
        --simulator) SIMULATOR=true; shift ;;
        -h|--help)   head -29 "$0" | tail -28; exit 0 ;;
        *)           echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# Prompt for Apple ID if not set
if [[ -z "$WYRD_APPLE_ID" ]]; then
    read -p "Apple ID (email): " WYRD_APPLE_ID
    export WYRD_APPLE_ID
fi

echo -e "${GREEN}=== Wyrdsekai KMP iOS Build ===${NC}"

# Unlock keychain so Fastlane can import certificates without UI prompts
# (required for SSH sessions and non-GUI terminals)
if [[ "$SIMULATOR" != true ]]; then
    echo -e "${YELLOW}Unlocking keychain...${NC}"
    security unlock-keychain ~/Library/Keychains/login.keychain-db
fi

# --- Setup tools ---

setup_env() {
    # Activate brew
    if [[ -f /opt/homebrew/bin/brew ]]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    fi

    # Activate rbenv
    if command -v rbenv &>/dev/null; then
        eval "$(rbenv init - bash 2>/dev/null || true)"
    fi

    # Activate mise from repo root
    local MISE_BIN="${HOME}/.local/bin/mise"
    if [[ -x "$MISE_BIN" ]]; then
        pushd "$KMP_ROOT/../.." >/dev/null 2>&1 || true
        eval "$("$MISE_BIN" hook-env -s bash 2>/dev/null || true)"
        popd >/dev/null 2>&1 || true
    elif command -v mise &>/dev/null; then
        pushd "$KMP_ROOT/../.." >/dev/null 2>&1 || true
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

    if ! command -v java &>/dev/null; then
        echo -e "${RED}Error: Java not found (needed for KMP shared framework)${NC}"
        exit 1
    fi

    if [[ ! -f "$KMP_ROOT/gradlew" ]]; then
        echo -e "${RED}Error: gradlew not found at $KMP_ROOT${NC}"
        exit 1
    fi

    if [[ "$SIMULATOR" != true ]] && ! command -v fastlane &>/dev/null; then
        echo -e "${RED}Error: Fastlane not found. Install: brew install fastlane${NC}"
        exit 1
    fi

    echo -e "${GREEN}Prerequisites OK (Xcode $(xcodebuild -version | head -1 | awk '{print $2}'), Java $(java -version 2>&1 | head -1))${NC}"
}

# --- Simulator build (no signing needed) ---

build_simulator() {
    echo -e "${YELLOW}Building for iOS Simulator...${NC}"

    xcodebuild \
        -project "$PROJECT" \
        -scheme "$SCHEME" \
        -configuration "Debug" \
        -sdk iphonesimulator \
        -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
        build \
        2>&1 | tail -20

    echo -e "${GREEN}Simulator build complete!${NC}"
    exit 0
}

# --- Main ---

cd "$SCRIPT_DIR"
setup_env
check_prereqs

if [[ "$SIMULATOR" == true ]]; then
    build_simulator
fi

if [[ "$DO_CLEAN" == true ]]; then
    echo -e "${YELLOW}Cleaning...${NC}"
    rm -rf "$SCRIPT_DIR/build" 2>/dev/null || true
    cd "$KMP_ROOT"
    ./gradlew :shared:clean 2>/dev/null || true
    cd "$SCRIPT_DIR"
fi

if [[ "$CERTS_ONLY" == true ]]; then
    echo -e "${YELLOW}Fetching/creating signing certificates...${NC}"
    fastlane certs
    echo -e "${GREEN}Certs ready! Now run: ./build-ios.sh --run${NC}"
    exit 0
fi

echo -e "${YELLOW}Building (lane: $LANE)...${NC}"
echo -e "${YELLOW}This will build the KMP shared framework via Gradle, then archive via Xcode${NC}"
fastlane "$LANE"

# Show output
IPA="$SCRIPT_DIR/build/wyrdsekai-kmp.ipa"
if [[ -f "$IPA" ]]; then
    SIZE=$(du -h "$IPA" | cut -f1)
    echo -e "${GREEN}IPA: $IPA ($SIZE)${NC}"
    echo ""
    echo "Next: ./deploy-ios.sh --run"
else
    echo -e "${RED}Build completed but no .ipa found${NC}"
fi

echo -e "${GREEN}=== Done ===${NC}"
