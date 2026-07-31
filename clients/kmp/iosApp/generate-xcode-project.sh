#!/usr/bin/env bash
# Generate Xcode project for KMP iOS app.
# Run on macOS with Xcode installed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
KMP_ROOT="$(dirname "$SCRIPT_DIR")"
APP_NAME="WyrdsekaiKMP"
BUNDLE_ID="org.wyrdsekai.kmp"

echo "=== Generating KMP iOS Xcode Project ==="

# Step 1: Build the shared framework for iOS simulator
echo "Building shared framework for iOS Simulator..."
cd "$KMP_ROOT"
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

FRAMEWORK_DIR="$KMP_ROOT/shared/build/bin/iosSimulatorArm64/debugFramework"
if [[ ! -d "$FRAMEWORK_DIR/shared.framework" ]]; then
    echo "ERROR: Framework not found at $FRAMEWORK_DIR/shared.framework"
    echo "Check ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 output"
    exit 1
fi
echo "Framework built at: $FRAMEWORK_DIR"

# Step 2: Create Xcode project using xcodegen if available, otherwise manual
if command -v xcodegen &>/dev/null; then
    echo "Using xcodegen to generate project..."

    cat > "$PROJECT_DIR/project.yml" <<YAML
name: $APP_NAME
options:
  bundleIdPrefix: org.wyrdsekai
  deploymentTarget:
    iOS: "16.0"
  xcodeVersion: "16.0"
targets:
  $APP_NAME:
    type: application
    platform: iOS
    sources:
      - WyrdsekaiApp
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: $BUNDLE_ID
        INFOPLIST_FILE: WyrdsekaiApp/Info.plist
        FRAMEWORK_SEARCH_PATHS:
          - "\$(inherited)"
          - "$FRAMEWORK_DIR"
        OTHER_LDFLAGS:
          - "\$(inherited)"
          - "-framework"
          - "shared"
    preBuildScripts:
      - name: "Build KMP Shared Framework"
        script: |
          cd "\$SRCROOT/.."
          ./gradlew :shared:embedAndSignAppleFrameworkForXcode
        basedOnDependencyAnalysis: false
YAML

    cd "$PROJECT_DIR"
    xcodegen generate
    echo "Xcode project generated: $PROJECT_DIR/$APP_NAME.xcodeproj"
else
    echo ""
    echo "xcodegen not found. Install it:"
    echo "  brew install xcodegen"
    echo ""
    echo "Or create project manually in Xcode:"
    echo "  1. File > New > Project > iOS App"
    echo "  2. Product Name: $APP_NAME"
    echo "  3. Bundle ID: $BUNDLE_ID"
    echo "  4. Language: Swift, Life Cycle: UIKit App Delegate"
    echo "  5. Save in: $PROJECT_DIR"
    echo "  6. Delete default Swift files, add files from WyrdsekaiApp/"
    echo "  7. Build Settings > Framework Search Paths: $FRAMEWORK_DIR"
    echo "  8. Build Phases > New Run Script:"
    echo "     cd \"\$SRCROOT/..\""
    echo "     ./gradlew :shared:embedAndSignAppleFrameworkForXcode"
    echo ""
    echo "The Swift source files are ready in: $PROJECT_DIR/WyrdsekaiApp/"
fi

echo ""
echo "=== Done ==="
echo "Next: open $APP_NAME.xcodeproj in Xcode, select simulator, build & run"
