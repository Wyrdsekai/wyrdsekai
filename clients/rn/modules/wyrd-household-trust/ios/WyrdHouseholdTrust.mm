#import "WyrdHouseholdTrust.h"
#import "WyrdTrustStore.h"

#import <Foundation/Foundation.h>
#import <Security/Security.h>
#import <objc/runtime.h>

// The pin store + cert helpers (SHA-256 / PEM<->DER / leaf extraction) now live
// in the shared WyrdTrustStore.{h,mm} so the WyrdRelaySocket WS delegate can
// read the SAME singleton this module populates. (Previously they were
// file-private here, alongside an SRSecurityPolicy provider hook that RN 0.83
// New Architecture silently ignores — RCTSetCustomSRWebSocketProvider is not
// honoured under New Arch, so the SocketRocket pinning path is GONE. The
// long-lived relay WebSocket is pinned by WyrdRelaySocket / NSURLSession
// instead; this module's only remaining job is to populate the store
// (addTrustedCert) and probe chains (fetchServerCertificates) for JS.)

#pragma mark - The shared server-trust decision

void WyrdHandleServerTrustChallenge(NSURLAuthenticationChallenge *challenge,
                                    void (^completionHandler)(NSURLSessionAuthChallengeDisposition,
                                                              NSURLCredential *_Nullable)) {
  if (![challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust]) {
    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
    return;
  }
  SecTrustRef trust = challenge.protectionSpace.serverTrust;
  // Pins are per host:port: the same machine may serve a relay and a home.
  NSString *host = WyrdPinKey(challenge.protectionSpace.host ?: @"", challenge.protectionSpace.port);
  if (!trust) {
    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
    return;
  }
  switch ([WyrdTrustStore evaluateServerTrust:trust forHost:host]) {
    case WyrdPinDecisionNoPins:
      completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
      return;
    case WyrdPinDecisionTrusted:
      completionHandler(NSURLSessionAuthChallengeUseCredential, [NSURLCredential credentialForTrust:trust]);
      return;
    case WyrdPinDecisionMismatch: {
      SecCertificateRef leaf = WyrdCopyLeafCert(trust);
      NSString *served = @"";
      if (leaf) {
        CFDataRef derRef = SecCertificateCopyData(leaf);
        if (derRef) served = WyrdSha256ColonHex((__bridge_transfer NSData *)derRef);
        CFRelease(leaf);
      }
      NSArray<NSString *> *pinned = [[WyrdTrustStore shared] caFingerprintsForHost:host];
      if (pinned.count == 0) pinned = [[WyrdTrustStore shared] pinnedFingerprintsForHost:host];
      NSLog(@"[WyrdHouseholdTrust] refused %@: served %@ does not satisfy its pins", host, served);
      [WyrdHouseholdTrust emitPinMismatchForHost:host
                                  newFingerprint:served
                               pinnedFingerprint:pinned.firstObject ?: @""];
      completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
      return;
    }
  }
}

#pragma mark - fetch() pinning

// RN's fetch/XMLHttpRequest on iOS runs through RCTHTTPRequestHandler, the
// delegate of its NSURLSession, which has no TLS challenge handler of its own
// (checked against react-native 0.83.4) and no hook for one. At load time we
// give it ours, so an https request to a pinned host (the home's lan_https,
// pinned to home_ca_fp) is held to its pins, and every other host keeps the
// system's default handling. If a future RN version implements the method,
// class_addMethod leaves it alone and says so in the log.
@interface WyrdFetchPinning : NSObject
@end

@implementation WyrdFetchPinning

+ (void)load {
  Class handler = NSClassFromString(@"RCTHTTPRequestHandler");
  SEL sel = @selector(URLSession:didReceiveChallenge:completionHandler:);
  Method m = class_getInstanceMethod(self, sel);
  if (!handler || !m) {
    NSLog(@"[WyrdHouseholdTrust] RCTHTTPRequestHandler not found; fetch() is not pinned");
    return;
  }
  if (!class_addMethod(handler, sel, method_getImplementation(m), method_getTypeEncoding(m))) {
    NSLog(@"[WyrdHouseholdTrust] RCTHTTPRequestHandler already handles TLS challenges; fetch() is not pinned");
  }
}

- (void)URLSession:(NSURLSession *)session
    didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
      completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler {
  WyrdHandleServerTrustChallenge(challenge, completionHandler);
}

@end

#pragma mark - Forward declarations

// Capture-only TLS-chain probe delegate (defined at the bottom) used by
// fetchServerCertificates.
@interface WyrdCertProbeDelegate : NSObject <NSURLSessionDelegate, NSURLSessionTaskDelegate>
- (instancetype)initWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject;
@end

#pragma mark - WyrdHouseholdTrust (RCTBridgeModule)

@implementation WyrdHouseholdTrust

// JS reaches this as NativeModules.HouseholdTrust — same name the Android
// module exports (getName() == "HouseholdTrust").
RCT_EXPORT_MODULE(HouseholdTrust)

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

#pragma mark Event emitter plumbing

// RCTEventEmitter requires supportedEvents. We route pin-mismatch through the
// SAME `wyrd_trust_pin_mismatch` DeviceEvent the Android TrustEventEmitter
// uses, so the existing installPinMismatchListener() in HouseholdTrust.ts
// (DeviceEventEmitter.addListener) receives it unchanged.
- (NSArray<NSString *> *)supportedEvents {
  return @[ @"wyrd_trust_pin_mismatch" ];
}

// The trust policy runs on SocketRocket's network thread with no module
// reference, so it emits via this class method into the live instance. We hold
// a weak-ish process-wide pointer set in init/dealloc.
static __weak WyrdHouseholdTrust *sActiveInstance = nil;

- (instancetype)init {
  if ((self = [super init])) {
    sActiveInstance = self;
  }
  return self;
}

+ (void)emitPinMismatchForHost:(NSString *)host
                newFingerprint:(NSString *)newFingerprint
             pinnedFingerprint:(NSString *)pinnedFingerprint {
  WyrdHouseholdTrust *inst = sActiveInstance;
  if (!inst) {
    // Pre-bridge or post-teardown — drop, mirroring the Android emitter. The
    // next connect attempt fails TLS and surfaces the error through the WS
    // close path, so the user isn't silently locked out.
    return;
  }
  NSDictionary *body = @{
    @"host" : host ?: @"",
    @"newFingerprint" : newFingerprint ?: @"",
    @"pinnedFingerprint" : pinnedFingerprint ?: @"",
  };
  // sendEventWithName must hit the JS thread; RCTEventEmitter handles
  // dispatch + the no-listener guard internally.
  dispatch_async(dispatch_get_main_queue(), ^{
    @try {
      [inst sendEventWithName:@"wyrd_trust_pin_mismatch" body:body];
    } @catch (__unused NSException *e) {
      // No JS listeners yet / bridge gone — best effort, same as Android.
    }
  });
}

#pragma mark Exported methods (match Android HouseholdTrustModule surface)

/**
 * Persist a CA/leaf PEM for a host. We parse PEM -> SecCertificateRef -> DER ->
 * SHA-256 and add that fingerprint to the host's pinned set. Mirrors the
 * Android addTrustedCert (which parses + persists the cert in
 * HouseholdTrustStore). Returns a JS boolean.
 */
// `host` in the exported methods below is a pin key, "host:port" (HouseholdTrust.ts pinKey).
RCT_EXPORT_METHOD(addTrustedCert:(NSString *)host
                  pem:(NSString *)pem
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  SecCertificateRef cert = WyrdCertFromPem(pem);
  if (!cert) {
    reject(@"ADD_TRUSTED_CERT_FAILED", @"could not parse PEM certificate", nil);
    return;
  }
  CFDataRef derRef = SecCertificateCopyData(cert);
  CFRelease(cert);
  if (!derRef) {
    reject(@"ADD_TRUSTED_CERT_FAILED", @"could not read certificate DER", nil);
    return;
  }
  NSData *der = (__bridge_transfer NSData *)derRef;
  NSString *fp = WyrdSha256ColonHex(der);
  [[WyrdTrustStore shared] addFingerprint:fp pem:pem forHost:host];
  resolve(@YES);
}

/**
 * Pin a fingerprint DIRECTLY (no cert fetch). A wyrdphone invite already carries
 * the relay's leaf+CA SHA-256, so we can seed the pin set from those without an
 * HTTPS round-trip — the round-trip (fetchServerCertificates) is fragile against
 * a relay that serves a TCP-TLS WebSocket but no HTTP/3, which iOS opportunistic-
 * QUIC probes and then fails to grab the chain from. Seeding directly is the
 * robust path: the WS serverTrust challenge later computes the served leaf's
 * SHA-256 and matches it against this set. `fingerprint` is colon-hex (any case;
 * the store uppercases). Mirrors the invite-fp seed KMP does in InvitePinning.ios.
 */
RCT_EXPORT_METHOD(pinFingerprint:(NSString *)host
                  fingerprint:(NSString *)fingerprint
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  if (host.length == 0 || fingerprint.length == 0) {
    reject(@"PIN_FINGERPRINT_FAILED", @"host and fingerprint are required", nil);
    return;
  }
  [[WyrdTrustStore shared] addFingerprint:fingerprint pem:nil forHost:host];
  resolve(@YES);
}

/**
 * Pin a host to its household CA by the CA certificate's SHA-256 (the invite's
 * `home_ca_fp`, D1). From then on that host is
 * accepted only with a chain that contains this CA and validates up to it.
 */
RCT_EXPORT_METHOD(pinCaFingerprint:(NSString *)host
                  fingerprint:(NSString *)fingerprint
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  if (host.length == 0 || WyrdPlainHex(fingerprint ?: @"").length != 64) {
    reject(@"PIN_CA_FINGERPRINT_FAILED", @"host and a SHA-256 fingerprint are required", nil);
    return;
  }
  [[WyrdTrustStore shared] addCaFingerprint:fingerprint forHost:host];
  resolve(@YES);
}

/** Forget every pin for a host. Mirrors Android removeTrustedCert. */
RCT_EXPORT_METHOD(removeTrustedCert:(NSString *)host
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  [[WyrdTrustStore shared] removeHost:host];
  resolve(@YES);
}

/** Inspector for the trust UI — [{host, subject, validUntil}]. Mirrors Android. */
RCT_EXPORT_METHOD(listTrustedHosts:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  resolve([[WyrdTrustStore shared] listHosts]);
}

/**
 * Retrieve the TLS chain a server presents WITHOUT validating it, so the JS
 * layer can match it against the invite's fingerprints.
 * Mirrors the Android fetchServerCertificates and the KMP iOS InvitePinning:
 * an NSURLSession whose delegate reads challenge.protectionSpace.serverTrust,
 * extracts each cert's DER -> {pem, fingerprint}, then CANCELS the challenge so
 * nothing rides past the handshake. 8s timeout.
 *
 * Resolves [{pem, fingerprint}] in chain order (leaf first). Fingerprints are
 * UPPERCASE colon-hex SHA-256 — the relay invite format the JS layer upcases
 * and compares against.
 */
RCT_EXPORT_METHOD(fetchServerCertificates:(NSString *)host
                  port:(nonnull NSNumber *)port
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  NSString *urlStr = [NSString stringWithFormat:@"https://%@:%@/", host, port];
  NSURL *url = [NSURL URLWithString:urlStr];
  if (!url) {
    reject(@"FETCH_SERVER_CERTS_FAILED", @"bad host/port", nil);
    return;
  }

  WyrdCertProbeDelegate *delegate = [[WyrdCertProbeDelegate alloc] initWithResolver:resolve rejecter:reject];
  NSURLSessionConfiguration *config = [NSURLSessionConfiguration ephemeralSessionConfiguration];
  config.timeoutIntervalForRequest = 8.0;
  config.timeoutIntervalForResource = 8.0;
  NSURLSession *session = [NSURLSession sessionWithConfiguration:config
                                                        delegate:delegate
                                                   delegateQueue:nil];
  NSURLSessionDataTask *task = [session dataTaskWithURL:url];
  [task resume];
  // Delegate retains itself via the session until it cancels the challenge or
  // the task errors; it invalidates the session in its terminal callbacks.
}

@end

#pragma mark - WyrdCertProbeDelegate (capture-only TLS chain grab)

@implementation WyrdCertProbeDelegate {
  RCTPromiseResolveBlock _resolve;
  RCTPromiseRejectBlock _reject;
  BOOL _settled;
}

- (instancetype)initWithResolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject {
  if ((self = [super init])) {
    _resolve = [resolve copy];
    _reject = [reject copy];
    _settled = NO;
  }
  return self;
}

- (void)settleResolve:(NSArray *)value session:(NSURLSession *)session {
  @synchronized(self) {
    if (_settled) return;
    _settled = YES;
    if (_resolve) _resolve(value);
  }
  [session finishTasksAndInvalidate];
}

- (void)settleRejectCode:(NSString *)code message:(NSString *)message session:(NSURLSession *)session {
  @synchronized(self) {
    if (_settled) return;
    _settled = YES;
    if (_reject) _reject(code, message, nil);
  }
  [session finishTasksAndInvalidate];
}

// TLS challenge: read the served chain, then CANCEL — never proceed past the
// handshake (read-only probe, identical intent to the KMP iOS InvitePinning).
- (void)URLSession:(NSURLSession *)session
    didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
      completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler {
  SecTrustRef trust = challenge.protectionSpace.serverTrust;
  if (trust) {
    NSMutableArray<NSDictionary *> *chain = [NSMutableArray new];
    NSArray *certs = nil;
    if (@available(iOS 15.0, *)) {
      CFArrayRef chainRef = SecTrustCopyCertificateChain(trust);
      if (chainRef) {
        certs = (__bridge_transfer NSArray *)chainRef;
      }
    }
    if (certs) {
      for (id obj in certs) {
        SecCertificateRef cert = (__bridge SecCertificateRef)obj;
        CFDataRef derRef = SecCertificateCopyData(cert);
        if (!derRef) continue;
        NSData *der = (__bridge_transfer NSData *)derRef;
        [chain addObject:@{
          @"pem" : WyrdPemFromDer(der),
          @"fingerprint" : WyrdSha256ColonHex(der),
        }];
      }
    } else {
#if !defined(__IPHONE_15_0) || __IPHONE_OS_VERSION_MIN_REQUIRED < __IPHONE_15_0
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
      CFIndex count = SecTrustGetCertificateCount(trust);
      for (CFIndex i = 0; i < count; i++) {
        SecCertificateRef cert = SecTrustGetCertificateAtIndex(trust, i);
        if (!cert) continue;
        CFDataRef derRef = SecCertificateCopyData(cert);
        if (!derRef) continue;
        NSData *der = (__bridge_transfer NSData *)derRef;
        [chain addObject:@{
          @"pem" : WyrdPemFromDer(der),
          @"fingerprint" : WyrdSha256ColonHex(der),
        }];
      }
#pragma clang diagnostic pop
#endif
    }
    [self settleResolve:chain session:session];
  }
  // Cancel the challenge: nothing is sent over the connection beyond the
  // handshake. The data task then completes with NSURLErrorCancelled, which we
  // swallow in didCompleteWithError since we've already settled.
  completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
}

- (void)URLSession:(NSURLSession *)session
                    task:(NSURLSessionTask *)task
    didCompleteWithError:(NSError *)error {
  if (error && error.code != NSURLErrorCancelled) {
    [self settleRejectCode:@"FETCH_SERVER_CERTS_FAILED"
                   message:error.localizedDescription
                   session:session];
  } else {
    // Cancelled after we captured the chain, or completed cleanly with no
    // challenge fired (unlikely for https). If we never settled, reject.
    [self settleRejectCode:@"FETCH_SERVER_CERTS_FAILED"
                   message:@"no TLS chain captured"
                   session:session];
  }
}

@end
