#!/bin/bash
# iOS OTA Deploy script for Wyrdsekai KMP client
# Usage: ./deploy-ios.sh [options]
#
# Serves the .ipa via Cloudflare Quick Tunnel for OTA install on iPhone.
# Open the URL in Safari on iPhone → tap Install.
#
# Options:
#   --run               Run deploy with default options
#   --ipa <path>        Use specific .ipa file. Default: ./build-wyrdsekai-kmp.ipa
#   --port <port>       HTTP server port (default: 8090)
#   --keep              Keep server running after download
#   -h, --help          Show this help message
#
# Prerequisites:
#   - cloudflared: brew install cloudflared
#   - Python 3 (for HTTP server)
#   - A built .ipa file (run ./build-ios.sh --run first)
#
# Examples:
#   ./deploy-ios.sh --run                        # Deploy latest build
#   ./deploy-ios.sh --ipa custom.ipa --run       # Deploy specific .ipa

set -e

if [[ $# -eq 0 ]]; then
    head -19 "$0" | tail -18
    exit 0
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Defaults
PORT=8090
KEEP_RUNNING=false
IPA_PATH=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OTA_DIR="$HOME/ota-server-kmp"
BUNDLE_ID="org.wyrdsekai.kmp"
APP_NAME="Wyrdsekai KMP"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --run)  shift ;;
        --ipa)  IPA_PATH="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --keep) KEEP_RUNNING=true; shift ;;
        -h|--help) head -19 "$0" | tail -18; exit 0 ;;
        *)      echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# Activate brew tools
if [[ -f /opt/homebrew/bin/brew ]]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
fi

# Check prerequisites
if ! command -v cloudflared &>/dev/null; then
    echo -e "${RED}Error: cloudflared not found${NC}"
    echo "Install with: brew install cloudflared"
    exit 1
fi

# Find .ipa
if [[ -z "$IPA_PATH" ]]; then
    IPA_PATH=$(ls -t "$SCRIPT_DIR"/build/*.ipa "$SCRIPT_DIR"/build-*.ipa "$SCRIPT_DIR"/*.ipa 2>/dev/null | head -1)
fi

if [[ -z "$IPA_PATH" || ! -f "$IPA_PATH" ]]; then
    echo -e "${RED}Error: No .ipa found in $SCRIPT_DIR${NC}"
    echo "Run ./build-ios.sh --run first"
    exit 1
fi

IPA_SIZE=$(du -h "$IPA_PATH" | cut -f1)
APP_VERSION="0.1.0"
GIT_HASH=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo "dev")

echo -e "${GREEN}=== Wyrdsekai KMP iOS OTA Deploy ===${NC}"
echo -e "IPA: ${CYAN}$IPA_PATH${NC} ($IPA_SIZE)"
echo -e "Version: ${CYAN}${APP_VERSION} (${GIT_HASH})${NC}"

# Setup OTA directory
mkdir -p "$OTA_DIR"
cp "$IPA_PATH" "$OTA_DIR/app.ipa"

# Kill stale processes
pkill -f "python3.*HTTPServer.*$PORT" 2>/dev/null || true
lsof -ti :$PORT | xargs kill 2>/dev/null || true
sleep 0.5

# Start HTTP server
cd "$OTA_DIR"
python3 -c "
from http.server import HTTPServer, SimpleHTTPRequestHandler
s = HTTPServer(('127.0.0.1', $PORT), SimpleHTTPRequestHandler)
s.serve_forever()
" > /tmp/wyrdsekai-kmp-ota-server.log 2>&1 &
HTTP_PID=$!

echo -n "Starting HTTP server on port $PORT"
for i in {1..15}; do
    if nc -z 127.0.0.1 $PORT 2>/dev/null; then
        echo -e " ${GREEN}OK${NC}"
        break
    fi
    echo -n "."
    sleep 1
    [[ $i -eq 15 ]] && echo -e " ${RED}FAILED${NC}" && exit 1
done

cleanup() {
    echo -e "\n${YELLOW}Cleaning up...${NC}"
    kill $HTTP_PID 2>/dev/null || true
    [[ -n "${TUNNEL_PID:-}" ]] && kill $TUNNEL_PID 2>/dev/null || true
    echo -e "${GREEN}Done${NC}"
}
trap cleanup EXIT

# Start Cloudflare quick tunnel
echo -e "${YELLOW}Starting Cloudflare quick tunnel...${NC}"
TUNNEL_LOG=$(mktemp)
cloudflared tunnel --url "http://localhost:$PORT" > "$TUNNEL_LOG" 2>&1 &
TUNNEL_PID=$!

echo -n "Waiting for tunnel URL"
TUNNEL_URL=""
for i in {1..30}; do
    TUNNEL_URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$TUNNEL_LOG" 2>/dev/null | grep -v 'api\.trycloudflare' | head -1)
    [[ -n "$TUNNEL_URL" ]] && break
    echo -n "."
    sleep 1
done
echo ""

if [[ -z "$TUNNEL_URL" ]]; then
    echo -e "${RED}Error: Failed to get tunnel URL${NC}"
    cat "$TUNNEL_LOG"
    exit 1
fi

# Verify tunnel
TUNNEL_HOST=$(echo "$TUNNEL_URL" | sed 's|https://||' | sed 's|/.*||')
CF_IP="104.16.230.132"
echo -n "Verifying tunnel"
for i in {1..30}; do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 -L \
        --resolve "${TUNNEL_HOST}:443:${CF_IP}" "$TUNNEL_URL" 2>/dev/null || echo "000")
    if [[ "$HTTP_CODE" =~ ^(200|404)$ ]]; then
        echo -e " ${GREEN}OK${NC}"
        break
    fi
    [[ "$HTTP_CODE" == "502" ]] && echo -n "!" || echo -n "."
    sleep 1
    [[ $i -eq 30 ]] && echo -e " ${YELLOW}timeout${NC}"
done

echo -e "${GREEN}Tunnel: ${CYAN}$TUNNEL_URL${NC}"

# Create manifest.plist
cat > "$OTA_DIR/manifest.plist" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>items</key>
  <array>
    <dict>
      <key>assets</key>
      <array>
        <dict>
          <key>kind</key>
          <string>software-package</string>
          <key>url</key>
          <string>${TUNNEL_URL}/app.ipa</string>
        </dict>
      </array>
      <key>metadata</key>
      <dict>
        <key>bundle-identifier</key>
        <string>${BUNDLE_ID}</string>
        <key>bundle-version</key>
        <string>${APP_VERSION}</string>
        <key>kind</key>
        <string>software</string>
        <key>title</key>
        <string>${APP_NAME}</string>
      </dict>
    </dict>
  </array>
</dict>
</plist>
EOF

# Create landing page
cat > "$OTA_DIR/index.html" << EOF
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Install ${APP_NAME}</title>
    <style>
        body { font-family: -apple-system, sans-serif; padding: 40px; text-align: center; background: #0d1117; color: #e6edf3; }
        .install-btn {
            display: inline-block; padding: 15px 30px;
            background: #6200EA; color: white;
            border-radius: 10px; text-decoration: none;
            font-size: 18px; margin: 20px 0;
        }
        .install-btn:active { background: #3700B3; }
        .url { font-size: 12px; color: #6b7280; word-break: break-all; margin: 20px; }
        .info { color: #9ca3af; font-size: 14px; margin-top: 20px; }
        h1 { color: #BB86FC; }
        .badge { display: inline-block; background: #1a1a2e; padding: 4px 12px; border-radius: 6px; font-size: 12px; color: #BB86FC; }
    </style>
</head>
<body>
    <h1>${APP_NAME}</h1>
    <p><span class="badge">Compose Multiplatform</span></p>
    <p>Version ${APP_VERSION} <span style="color:#6b7280">(${GIT_HASH})</span></p>
    <a class="install-btn" href="itms-services://?action=download-manifest&url=${TUNNEL_URL}/manifest.plist">
        Install App
    </a>
    <p class="url">${TUNNEL_URL}</p>
    <p class="info">
        Open this page in <b>Safari</b> on your iPhone.<br><br>
        After installing:<br>
        1. Settings → Privacy & Security → Developer Mode → ON<br>
        2. Settings → General → VPN & Device Management → Trust certificate
    </p>
</body>
</html>
EOF

echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Ready to install!${NC}"
echo ""
echo -e "On your iPhone, open ${YELLOW}Safari${NC} and go to:"
echo -e "${CYAN}$TUNNEL_URL${NC}"
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
echo ""

if [[ "$KEEP_RUNNING" == true ]]; then
    echo -e "${YELLOW}Running until Ctrl+C...${NC}"
    wait $TUNNEL_PID
else
    echo -e "${YELLOW}Watching for download...${NC}"
    echo "(Ctrl+C to stop)"
    tail -f /tmp/wyrdsekai-kmp-ota-server.log 2>/dev/null | while read line; do
        echo "$line"
        if [[ "$line" == *"GET /app.ipa"*"200"* ]]; then
            echo ""
            echo -e "${GREEN}Download complete!${NC}"
            echo -e "${YELLOW}Shutting down in 5 seconds...${NC}"
            sleep 5
            kill $$ 2>/dev/null
            break
        fi
    done
fi
