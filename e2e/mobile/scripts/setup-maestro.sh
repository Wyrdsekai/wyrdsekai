#!/usr/bin/env bash
# =============================================================================
# Maestro E2E Testing Setup — Installs Maestro CLI
# =============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[*]${NC} $1"; }
ok()    { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
fail()  { echo -e "${RED}[-]${NC} $1"; }

# Check Java 17+
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '\"(\d+)' | tr -d '"')
if [ -z "$JAVA_VER" ] || [ "$JAVA_VER" -lt 17 ]; then
    fail "Java 17+ required (found: ${JAVA_VER:-none})"
    exit 1
fi
ok "Java $JAVA_VER"

# Check/install Maestro
if command -v maestro &>/dev/null || [ -x "$HOME/.maestro/bin/maestro" ]; then
    MAESTRO_VER=$("${HOME}/.maestro/bin/maestro" --version 2>/dev/null | tail -1 || maestro --version 2>/dev/null | tail -1)
    ok "Maestro already installed: $MAESTRO_VER"
else
    info "Installing Maestro CLI..."
    curl -fsSL "https://get.maestro.mobile.dev" | bash
    export PATH="$PATH:$HOME/.maestro/bin"
    MAESTRO_VER=$(maestro --version 2>/dev/null | tail -1)
    ok "Maestro installed: $MAESTRO_VER"
fi

# Verify adb
if command -v adb &>/dev/null || [ -x "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" ]; then
    ok "adb available"
else
    warn "adb not found — install Android platform-tools"
fi

echo ""
ok "Maestro setup complete. Add to PATH if needed:"
echo "  export PATH=\"\$PATH:\$HOME/.maestro/bin\""
