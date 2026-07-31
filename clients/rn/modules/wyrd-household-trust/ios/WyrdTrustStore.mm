#import "WyrdTrustStore.h"

#import <CommonCrypto/CommonDigest.h>

#pragma mark - Shared helpers (SHA-256 / PEM <-> DER)

/** SHA-256 of DER bytes -> UPPERCASE colon-separated hex (relay invite format). */
NSString *WyrdSha256ColonHex(NSData *der) {
  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(der.bytes, (CC_LONG)der.length, digest);
  NSMutableString *out = [NSMutableString stringWithCapacity:CC_SHA256_DIGEST_LENGTH * 3];
  for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
    if (i > 0) [out appendString:@":"];
    [out appendFormat:@"%02X", digest[i]];
  }
  return out;
}

/** DER bytes -> PEM string (64-col body) matching the Android toPem() shape. */
NSString *WyrdPemFromDer(NSData *der) {
  NSString *b64 = [der base64EncodedStringWithOptions:0];
  NSMutableString *body = [NSMutableString new];
  NSUInteger i = 0;
  while (i < b64.length) {
    NSUInteger len = MIN((NSUInteger)64, b64.length - i);
    [body appendString:[b64 substringWithRange:NSMakeRange(i, len)]];
    [body appendString:@"\n"];
    i += len;
  }
  return [NSString stringWithFormat:@"-----BEGIN CERTIFICATE-----\n%@-----END CERTIFICATE-----\n", body];
}

/** Parse a PEM cert -> SecCertificateRef (caller releases). Returns NULL on failure. */
SecCertificateRef WyrdCertFromPem(NSString *pem) {
  NSString *cleaned = [pem stringByReplacingOccurrencesOfString:@"-----BEGIN CERTIFICATE-----" withString:@""];
  cleaned = [cleaned stringByReplacingOccurrencesOfString:@"-----END CERTIFICATE-----" withString:@""];
  cleaned = [cleaned stringByReplacingOccurrencesOfString:@"\n" withString:@""];
  cleaned = [cleaned stringByReplacingOccurrencesOfString:@"\r" withString:@""];
  cleaned = [cleaned stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
  NSData *der = [[NSData alloc] initWithBase64EncodedString:cleaned options:NSDataBase64DecodingIgnoreUnknownCharacters];
  if (der.length == 0) return NULL;
  return SecCertificateCreateWithData(NULL, (__bridge CFDataRef)der);
}

/**
 * Copy the served leaf certificate (index 0) from a SecTrustRef, handling both
 * the newer SecTrustCopyCertificateChain (iOS 15+) and the deprecated
 * SecTrustGetCertificateAtIndex. Returns a +1 SecCertificateRef (caller
 * releases) or NULL.
 */
SecCertificateRef WyrdCopyLeafCert(SecTrustRef trust) {
  if (!trust) return NULL;
  if (@available(iOS 15.0, *)) {
    CFArrayRef chain = SecTrustCopyCertificateChain(trust);
    if (chain) {
      SecCertificateRef leaf = NULL;
      if (CFArrayGetCount(chain) > 0) {
        leaf = (SecCertificateRef)CFArrayGetValueAtIndex(chain, 0);
        if (leaf) CFRetain(leaf);
      }
      CFRelease(chain);
      return leaf;
    }
  }
  // Fallback for < iOS 15 SDKs / deploy targets.
#if !defined(__IPHONE_15_0) || __IPHONE_OS_VERSION_MIN_REQUIRED < __IPHONE_15_0
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
  SecCertificateRef leaf = SecTrustGetCertificateAtIndex(trust, 0);
#pragma clang diagnostic pop
  if (leaf) {
    CFRetain(leaf);
    return leaf;
  }
#endif
  return NULL;
}

#pragma mark - WyrdTrustStore

@implementation WyrdTrustStore {
  dispatch_queue_t _queue;
  // host -> NSMutableSet<NSString*> of UPPERCASE colon-hex SHA-256
  NSMutableDictionary<NSString *, NSMutableSet<NSString *> *> *_pins;
  // host -> last PEM seen (for the inspector); first cert pinned wins display.
  NSMutableDictionary<NSString *, NSString *> *_pems;
}

+ (instancetype)shared {
  static WyrdTrustStore *shared;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    shared = [WyrdTrustStore new];
  });
  return shared;
}

- (instancetype)init {
  if ((self = [super init])) {
    _queue = dispatch_queue_create("org.wyrdsekai.householdtrust.store", DISPATCH_QUEUE_SERIAL);
    _pins = [NSMutableDictionary new];
    _pems = [NSMutableDictionary new];
  }
  return self;
}

- (void)addFingerprint:(NSString *)fingerprint pem:(NSString *)pem forHost:(NSString *)host {
  if (host.length == 0 || fingerprint.length == 0) return;
  NSString *fp = fingerprint.uppercaseString;
  dispatch_sync(_queue, ^{
    NSMutableSet<NSString *> *set = self->_pins[host];
    if (!set) {
      set = [NSMutableSet new];
      self->_pins[host] = set;
    }
    [set addObject:fp];
    if (pem.length > 0 && self->_pems[host] == nil) {
      self->_pems[host] = pem;
    }
  });
}

- (void)removeHost:(NSString *)host {
  if (host.length == 0) return;
  dispatch_sync(_queue, ^{
    [self->_pins removeObjectForKey:host];
    [self->_pems removeObjectForKey:host];
  });
}

- (BOOL)hasPinsForHost:(NSString *)host {
  if (host.length == 0) return NO;
  __block BOOL has = NO;
  dispatch_sync(_queue, ^{
    has = self->_pins[host].count > 0;
  });
  return has;
}

- (BOOL)host:(NSString *)host trustsFingerprint:(NSString *)fingerprint {
  if (host.length == 0 || fingerprint.length == 0) return NO;
  NSString *fp = fingerprint.uppercaseString;
  __block BOOL trusts = NO;
  dispatch_sync(_queue, ^{
    trusts = [self->_pins[host] containsObject:fp];
  });
  return trusts;
}

- (NSArray<NSString *> *)pinnedFingerprintsForHost:(NSString *)host {
  if (host.length == 0) return @[];
  __block NSArray<NSString *> *out = @[];
  dispatch_sync(_queue, ^{
    out = self->_pins[host].allObjects ?: @[];
  });
  return out;
}

- (NSArray<NSDictionary *> *)listHosts {
  __block NSMutableArray<NSDictionary *> *out = [NSMutableArray new];
  dispatch_sync(_queue, ^{
    for (NSString *host in self->_pins) {
      NSString *pem = self->_pems[host];
      NSString *subject = host;
      double validUntil = 0;
      if (pem.length > 0) {
        SecCertificateRef cert = WyrdCertFromPem(pem);
        if (cert) {
          CFStringRef summary = SecCertificateCopySubjectSummary(cert);
          if (summary) {
            subject = (__bridge_transfer NSString *)summary;
          }
          CFRelease(cert);
        }
      }
      [out addObject:@{@"host" : host, @"subject" : subject, @"validUntil" : @(validUntil)}];
    }
  });
  return out;
}

+ (BOOL)isPinnedForHost:(NSString *)host certificate:(SecCertificateRef)cert {
  if (host.length == 0 || cert == NULL) return NO;
  CFDataRef derRef = SecCertificateCopyData(cert);
  if (!derRef) return NO;
  NSData *der = (__bridge_transfer NSData *)derRef;
  NSString *served = WyrdSha256ColonHex(der);
  return [[WyrdTrustStore shared] host:host trustsFingerprint:served];
}

@end
