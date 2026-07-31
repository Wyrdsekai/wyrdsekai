#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

// Native iOS WebSocket for the RN relay leg, backed by NSURLSessionWebSocketTask
// with a URLSessionDelegate that does serverTrust pinning against the shared
// WyrdTrustStore (the household-CA allowlist the HouseholdTrust module
// populates). Reached from JS as NativeModules.WyrdRelaySocket.
//
// This REPLACES the SocketRocket SRSecurityPolicy provider approach: under RN
// 0.83 New Architecture RCTSetCustomSRWebSocketProvider is not honoured, so the
// custom SRSecurityPolicy never ran and every relay connect died with
// `Trust evaluate failure: [root AnchorTrusted]`. NativeNatsClient drives this
// module (via the RelaySocket TS shim) on iOS only; Android keeps the OkHttp-
// pinned JS WebSocket.
//
// Legacy RCTEventEmitter bridge module (NOT a TurboModule): like
// WyrdHouseholdTrust, it registers fine under New Arch via the interop layer.
@interface WyrdRelaySocket : RCTEventEmitter <RCTBridgeModule>
@end
