#import <Foundation/Foundation.h>
#import <Security/Security.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Process-wide, thread-safe pin store shared by the two native modules in this
 * pod:
 *   - WyrdHouseholdTrust (NativeModules.HouseholdTrust) POPULATES it via
 *     addTrustedCert (called from JS openZone -> trustFromInviteFingerprints
 *     -> fetchServerCertificates -> addTrustedCert), and inspects it via
 *     listTrustedHosts.
 *   - WyrdRelaySocket (NativeModules.WyrdRelaySocket) READS it from its
 *     NSURLSession serverTrust challenge to decide whether a self-signed
 *     household relay leaf may be trusted for a long-lived NATS WebSocket.
 *
 * Both reach the SAME singleton via +shared, so a pin written by the
 * HouseholdTrust module before connect is visible to the WS delegate at
 * challenge time. (The store was previously a file-private class inside
 * WyrdHouseholdTrust.mm; it was lifted here so the WS delegate can read it.)
 *
 * Maps host -> set of expected leaf/CA SHA-256 fingerprints in the relay
 * invite format (UPPERCASE colon-separated hex). We key on fingerprints (not
 * parsed SecCertificateRefs) because the consumer's job is a single
 * comparison: does the served leaf's SHA-256 sit in this host's pinned set?
 *
 * iOS companion to the Android HouseholdTrustStore singleton.
 */
@interface WyrdTrustStore : NSObject

+ (instancetype)shared;

/** Add an expected fingerprint (UPPERCASE colon-hex SHA-256) for a host; keep
 *  the first PEM for the inspector. */
- (void)addFingerprint:(NSString *)fingerprint
                   pem:(nullable NSString *)pem
               forHost:(NSString *)host;

/** Forget every pin for a host. */
- (void)removeHost:(NSString *)host;

/** Whether any pin is held for this host. */
- (BOOL)hasPinsForHost:(NSString *)host;

/** Whether the given fingerprint (any case) is pinned for this host. */
- (BOOL)host:(NSString *)host trustsFingerprint:(NSString *)fingerprint;

/** Pinned fingerprints for a host (UPPERCASE colon-hex). */
- (NSArray<NSString *> *)pinnedFingerprintsForHost:(NSString *)host;

/** Inspector rows: [{host, subject, validUntil}]. */
- (NSArray<NSDictionary *> *)listHosts;

/**
 * Convenience used by the WS serverTrust delegate: compute the served leaf's
 * SHA-256 and test it against the host's pinned set in one call. Returns NO
 * for a NULL cert, an empty host, or a host with no matching pin (the safe
 * default — the caller then cancels the challenge).
 */
+ (BOOL)isPinnedForHost:(nullable NSString *)host
            certificate:(nullable SecCertificateRef)cert;

@end

#pragma mark - Shared cert helpers (SHA-256 / PEM <-> DER / leaf extraction)

/** SHA-256 of DER bytes -> UPPERCASE colon-separated hex (relay invite format). */
NSString *WyrdSha256ColonHex(NSData *der);

/** DER bytes -> PEM string (64-col body) matching the Android toPem() shape. */
NSString *WyrdPemFromDer(NSData *der);

/** Parse a PEM cert -> SecCertificateRef (caller releases). NULL on failure. */
SecCertificateRef _Nullable WyrdCertFromPem(NSString *pem);

/** Copy the served leaf cert (index 0) from a SecTrustRef (caller releases),
 *  handling both SecTrustCopyCertificateChain (iOS 15+) and the deprecated
 *  SecTrustGetCertificateAtIndex. */
SecCertificateRef _Nullable WyrdCopyLeafCert(SecTrustRef trust);

NS_ASSUME_NONNULL_END
