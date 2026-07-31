#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

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
