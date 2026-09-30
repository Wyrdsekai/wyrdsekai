#import <Foundation/Foundation.h>
#import <Security/Security.h>

NS_ASSUME_NONNULL_BEGIN

/** What the pins say about a served chain (see +evaluateServerTrust:forHost:). */
typedef NS_ENUM(NSInteger, WyrdPinDecision) {
  /** No pin for this host: the system decides, as for any other site. */
  WyrdPinDecisionNoPins = 0,
  /** The chain satisfies this host's pins. */
  WyrdPinDecisionTrusted = 1,
  /** This host has pins and the chain does not satisfy them: refuse. */
  WyrdPinDecisionMismatch = 2,
};

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
 * Maps a pin key -> set of expected leaf/CA SHA-256 fingerprints in the relay
 * invite format (UPPERCASE colon-separated hex). We key on fingerprints (not
 * parsed SecCertificateRefs) because the consumer's job is a single
 * comparison: does the served leaf's SHA-256 sit in this endpoint's pinned set?
 *
 * The pin key is "host:port" (WyrdPinKey; the JS side builds the same string,
 * HouseholdTrust.ts pinKey), never the host alone: a relay and a home served by
 * one machine on two ports must not share pins — the relay's leaf pin must not
 * vouch for the home, and the home's CA pin must not refuse the relay.
 *
 * A second set per key holds CA pins (the home's `home_ca_fp`,
 * D1): a chain is accepted only if it contains the
 * certificate with that SHA-256 AND validates up to it as the sole anchor
 * (signatures, dates, the host name) — a stranger's leaf next to the real CA
 * does not pass.
 *
 * Pins live in memory only. The lasting copy is the JS side's secure storage
 * (encrypted, its key in the Keychain), which refills this store at every
 * start (HouseholdTrust.ts restoreNativePins) — nothing here is written to
 * NSUserDefaults or to disk.
 *
 * iOS companion to the Android HouseholdTrustStore singleton.
 */
@interface WyrdTrustStore : NSObject

+ (instancetype)shared;

/** Add an expected fingerprint (UPPERCASE colon-hex SHA-256) for a pin key
 *  ("host:port"); keep the first PEM for the inspector. */
- (void)addFingerprint:(NSString *)fingerprint
                   pem:(nullable NSString *)pem
               forHost:(NSString *)key;

/** Pin a key ("host:port") to a CA by the CA certificate's SHA-256 (hex; colons and case ignored). */
- (void)addCaFingerprint:(NSString *)fingerprint forHost:(NSString *)key;

/** CA pins for a key, as 64 UPPERCASE hex characters without colons. */
- (NSArray<NSString *> *)caFingerprintsForHost:(NSString *)key;

/** Forget every pin (leaf and CA) for a key. */
- (void)removeHost:(NSString *)key;

/** Whether any leaf pin is held for this key (CA pins: caFingerprintsForHost). */
- (BOOL)hasPinsForHost:(NSString *)key;

/** Whether the given fingerprint (any case) is pinned for this key. */
- (BOOL)host:(NSString *)key trustsFingerprint:(NSString *)fingerprint;

/** Pinned fingerprints for a key (UPPERCASE colon-hex). */
- (NSArray<NSString *> *)pinnedFingerprintsForHost:(NSString *)key;

/** Inspector rows: [{host (the pin key), subject, validUntil}]. */
- (NSArray<NSDictionary *> *)listHosts;

/**
 * Convenience used by the WS serverTrust delegate: compute the served leaf's
 * SHA-256 and test it against the key's pinned set in one call. Returns NO
 * for a NULL cert, an empty key, or a key with no matching pin (the safe
 * default — the caller then cancels the challenge).
 */
+ (BOOL)isPinnedForHost:(nullable NSString *)key
            certificate:(nullable SecCertificateRef)cert;

/**
 * The pin decision for a served chain at pin key `key` ("host:port"). CA pins
 * first (the chain must validate to that CA as the only anchor), then leaf
 * pins, else no pins. May set the trust object's anchors; callers pass the same
 * trust on to the credential.
 */
+ (WyrdPinDecision)evaluateServerTrust:(SecTrustRef)trust forHost:(NSString *)key;

@end

/**
 * The pin key for a TLS endpoint: lowercase host (IPv6 without brackets) + ":" +
 * port; a port <= 0 means 443. The JS side builds the same key
 * (HouseholdTrust.ts pinKey).
 */
NSString *WyrdPinKey(NSString *host, NSInteger port);

/** A key as JS passed it, normalised the same way (lowercase, IPv6 brackets dropped). */
NSString *WyrdNormalizePinKey(NSString *key);

#pragma mark - Shared cert helpers (SHA-256 / PEM <-> DER / leaf extraction)

/** SHA-256 of DER bytes -> UPPERCASE colon-separated hex (relay invite format). */
NSString *WyrdSha256ColonHex(NSData *der);

/** A fingerprint in any spelling -> UPPERCASE hex without colons or spaces. */
NSString *WyrdPlainHex(NSString *fingerprint);

/** The served chain, leaf first (caller owns the array). */
NSArray *WyrdCopyChain(SecTrustRef trust);

/** DER bytes -> PEM string (64-col body) matching the Android toPem() shape. */
NSString *WyrdPemFromDer(NSData *der);

/** Parse a PEM cert -> SecCertificateRef (caller releases). NULL on failure. */
SecCertificateRef _Nullable WyrdCertFromPem(NSString *pem);

/** Copy the served leaf cert (index 0) from a SecTrustRef (caller releases),
 *  handling both SecTrustCopyCertificateChain (iOS 15+) and the deprecated
 *  SecTrustGetCertificateAtIndex. */
SecCertificateRef _Nullable WyrdCopyLeafCert(SecTrustRef trust);

NS_ASSUME_NONNULL_END
