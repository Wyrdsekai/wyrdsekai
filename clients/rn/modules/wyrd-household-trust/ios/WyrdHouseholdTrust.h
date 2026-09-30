#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * The one server-trust decision for every connection this app pins: the relay
 * and home-bus websockets (WyrdRelaySocket) and fetch() (RN's
 * RCTHTTPRequestHandler, which gets this as its challenge handler at load).
 * Pins are looked up by the challenge's host AND port (WyrdPinKey), so a relay
 * and a home on one machine keep separate pins. An endpoint with pins must
 * satisfy them (WyrdTrustStore +evaluateServerTrust); a mismatch is refused and
 * reported to JS, never offered for acceptance. An endpoint without pins gets
 * the system's default handling, as before.
 */
void WyrdHandleServerTrustChallenge(NSURLAuthenticationChallenge *challenge,
                                    void (^completionHandler)(NSURLSessionAuthChallengeDisposition,
                                                              NSURLCredential *_Nullable));

NS_ASSUME_NONNULL_END

// Legacy bridge module (NOT a TurboModule): the JS layer reaches it via
// NativeModules.HouseholdTrust, mirroring the Android
// org.wyrdsekai.rn.HouseholdTrustModule. RCTEventEmitter base so we can emit
// the `wyrd_trust_pin_mismatch` DeviceEvent the same way the Android
// TrustEventEmitter does.
@interface WyrdHouseholdTrust : RCTEventEmitter <RCTBridgeModule>

// Called from the (module-less) TLS trust policy thread to route a
// pin-mismatch into JS via the live module instance. Mirrors the Android
// TrustEventEmitter.emitPinMismatch.
+ (void)emitPinMismatchForHost:(NSString *)host
                newFingerprint:(NSString *)newFingerprint
             pinnedFingerprint:(NSString *)pinnedFingerprint;
@end
