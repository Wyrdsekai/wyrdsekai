// Explicit dependency declaration for the in-tree wyrd-onnx TurboModule.
//
// pnpm hardlinks file:./modules/wyrd-onnx into node_modules so RN autolinking
// can find it, but listing it here makes the wiring deterministic regardless
// of node_modules layout (Expo prebuild, EAS, dev shell). Mirrors the shape
// any external community module would use.
//
// IMPORTANT: sourceDir must be RELATIVE to the module's `root`, not absolute.
// Expo's autolinking resolver does `path.join(packageRoot, sourceDir)` and an
// absolute sourceDir breaks the join (silently returns null and the Android
// platform block disappears from the autolinking output). This bit us once;
// keep it relative.
module.exports = {
  dependencies: {
    "wyrd-onnx": {
      root: require("path").join(__dirname, "modules/wyrd-onnx"),
      platforms: {
        android: {
          sourceDir: "android",
          packageImportPath: "import org.wyrdsekai.onnx.WyrdOnnxPackage;",
          packageInstance: "new WyrdOnnxPackage()",
        },
        ios: {
          podspecPath: require("path").join(
            __dirname,
            "modules/wyrd-onnx/WyrdOnnx.podspec"
          ),
        },
      },
    },
    // iOS-only household-CA cert pinning for the SocketRocket WebSocket (#733).
    // Android pins via the in-app org.wyrdsekai.rn.HouseholdTrust* classes, so
    // this module deliberately declares NO android platform block — its native
    // code is iOS-only. Null the android platform so autolinking doesn't try to
    // build an absent Android source dir.
    "wyrd-household-trust": {
      root: require("path").join(__dirname, "modules/wyrd-household-trust"),
      platforms: {
        android: null,
        ios: {
          podspecPath: require("path").join(
            __dirname,
            "modules/wyrd-household-trust/WyrdHouseholdTrust.podspec"
          ),
        },
      },
    },
  },
};
