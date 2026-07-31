#import "WyrdRelaySocket.h"
#import "WyrdTrustStore.h"
// For emitPinMismatchForHost:newFingerprint:pinnedFingerprint: — reuse the
// existing wyrd_trust_pin_mismatch DeviceEvent path the JS listener consumes.
#import "WyrdHouseholdTrust.h"

#import <Foundation/Foundation.h>
#import <Security/Security.h>

// Native iOS relay WebSocket. See WyrdRelaySocket.h for why this exists
// (RN 0.83 New Arch ignores RCTSetCustomSRWebSocketProvider, so the
// SocketRocket pinning path is dead). One NSURLSession + one
// NSURLSessionWebSocketTask per socketId, keyed in a guarded map. The session
// delegate does serverTrust pinning against the shared WyrdTrustStore.

#pragma mark - WyrdRelaySocketDelegate

/**
 * One delegate instance per WyrdRelaySocket (shared across all sockets). Routes
 * the serverTrust challenge to the pinning logic and forwards the WS-task
 * open/close lifecycle to the module via blocks (so the module can emit RN
 * events with the right socketId; we resolve socketId from the task here).
 */
@class WyrdRelaySocket;

@interface WyrdRelaySocketDelegate : NSObject <NSURLSessionDelegate, NSURLSessionWebSocketDelegate>
- (instancetype)initWithModule:(WyrdRelaySocket *)module;
@end

#pragma mark - WyrdRelaySocket

@implementation WyrdRelaySocket {
  // socketId -> NSURLSession (one ephemeral session per socket so its delegate
  // queue / lifetime is independent and cancelWithCloseCode tears down cleanly).
  NSMutableDictionary<NSString *, NSURLSession *> *_sessions;
  // socketId -> NSURLSessionWebSocketTask
  NSMutableDictionary<NSString *, NSURLSessionWebSocketTask *> *_tasks;
  // Reverse map for delegate callbacks: taskIdentifier -> socketId.
  NSMutableDictionary<NSNumber *, NSString *> *_socketIdByTask;
  dispatch_queue_t _mapQueue;
  WyrdRelaySocketDelegate *_delegate;
  BOOL _hasListeners;
}

// JS reaches this as NativeModules.WyrdRelaySocket.
RCT_EXPORT_MODULE(WyrdRelaySocket)

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (instancetype)init {
  if ((self = [super init])) {
    _sessions = [NSMutableDictionary new];
    _tasks = [NSMutableDictionary new];
    _socketIdByTask = [NSMutableDictionary new];
    _mapQueue = dispatch_queue_create("org.wyrdsekai.relaysocket.map", DISPATCH_QUEUE_SERIAL);
    _delegate = [[WyrdRelaySocketDelegate alloc] initWithModule:self];
  }
  return self;
}

#pragma mark Event emitter plumbing

// One event name carries every lifecycle phase; the JS shim demultiplexes on
// `type` (open|message|closing|closed|error) and `id` (socketId). A single
// supported event keeps the NativeEventEmitter wiring trivial.
- (NSArray<NSString *> *)supportedEvents {
  return @[ @"wyrd_relay_socket_event" ];
}

- (void)startObserving {
  _hasListeners = YES;
}

- (void)stopObserving {
  _hasListeners = NO;
}

- (void)emitForSocket:(NSString *)socketId
                 type:(NSString *)type
                 body:(nullable NSDictionary *)extra {
  if (!_hasListeners || socketId == nil) return;
  NSMutableDictionary *body = [NSMutableDictionary dictionaryWithDictionary:@{
    @"id" : socketId,
    @"type" : type,
  }];
  if (extra) [body addEntriesFromDictionary:extra];
  @try {
    [self sendEventWithName:@"wyrd_relay_socket_event" body:body];
  } @catch (__unused NSException *e) {
    // No JS listeners / bridge gone — best effort.
  }
}

#pragma mark Internal map helpers (all on _mapQueue)

- (NSString *)socketIdForTask:(NSURLSessionTask *)task {
  __block NSString *sid = nil;
  dispatch_sync(_mapQueue, ^{
    sid = self->_socketIdByTask[@(task.taskIdentifier)];
  });
  return sid;
}

- (NSURLSessionWebSocketTask *)taskForSocket:(NSString *)socketId {
  __block NSURLSessionWebSocketTask *task = nil;
  dispatch_sync(_mapQueue, ^{
    task = self->_tasks[socketId];
  });
  return task;
}

#pragma mark Exported methods

/**
 * Open a pinned relay WebSocket. Creates an ephemeral NSURLSession whose
 * delegate (WyrdRelaySocketDelegate) does the serverTrust pin check, opens an
 * NSURLSessionWebSocketTask for `url`, and starts the recursive receive loop.
 */
RCT_EXPORT_METHOD(connect:(NSString *)socketId url:(NSString *)urlStr) {
  NSLog(@"[WyrdRelaySocket] connect socketId=%@ url=%@", socketId, urlStr);
  NSURL *url = [NSURL URLWithString:urlStr];
  if (!url) {
    [self emitForSocket:socketId type:@"error" body:@{@"message" : @"invalid url"}];
    return;
  }

  NSURLSessionConfiguration *config = [NSURLSessionConfiguration ephemeralSessionConfiguration];
  // Long-lived: do not let the resource timeout cap the connection. The request
  // timeout still guards a stuck handshake.
  config.timeoutIntervalForRequest = 30.0;
  config.timeoutIntervalForResource = 0; // 0 == no resource time limit.

  // A dedicated serial delegate queue per module so the receive loop and the
  // close callbacks are ordered.
  NSOperationQueue *delegateQueue = [NSOperationQueue new];
  delegateQueue.maxConcurrentOperationCount = 1;

  NSURLSession *session = [NSURLSession sessionWithConfiguration:config
                                                       delegate:_delegate
                                                  delegateQueue:delegateQueue];
  NSURLSessionWebSocketTask *task = [session webSocketTaskWithURL:url];

  dispatch_sync(_mapQueue, ^{
    // If a socket already exists for this id, tear it down first.
    NSURLSessionWebSocketTask *old = self->_tasks[socketId];
    if (old) {
      [self->_socketIdByTask removeObjectForKey:@(old.taskIdentifier)];
      [old cancelWithCloseCode:NSURLSessionWebSocketCloseCodeGoingAway reason:nil];
    }
    NSURLSession *oldSession = self->_sessions[socketId];
    if (oldSession) [oldSession finishTasksAndInvalidate];

    self->_sessions[socketId] = session;
    self->_tasks[socketId] = task;
    self->_socketIdByTask[@(task.taskIdentifier)] = socketId;
  });

  [task resume];
  [self receiveNextForSocket:socketId task:task];
}

/**
 * Recursive read loop. NATS frames arrive as binary messages; we UTF-8-decode
 * them and emit a STRING (the NATS wire protocol is UTF-8 text — subjects,
 * control verbs, and our JSON payloads are all UTF-8-safe, and NativeNatsClient
 * UTF-8-decodes either way). String messages (should not occur from nats-server
 * but handled for completeness) pass through verbatim.
 */
- (void)receiveNextForSocket:(NSString *)socketId task:(NSURLSessionWebSocketTask *)task {
  __weak WyrdRelaySocket *weakSelf = self;
  [task receiveMessageWithCompletionHandler:^(NSURLSessionWebSocketMessage *message, NSError *error) {
    WyrdRelaySocket *self_ = weakSelf;
    if (!self_) return;

    if (error) {
      // A cancelled task (close()) reports NSURLErrorCancelled — that's the
      // normal teardown path, the didCompleteWithError/closeCode delegate
      // already emits closed, so don't double-emit an error here.
      if (!(error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)) {
        [self_ emitForSocket:socketId
                        type:@"error"
                        body:@{@"message" : error.localizedDescription ?: @"receive failed"}];
      }
      return; // Stop the loop.
    }

    // Carry the frame's RAW BYTES to JS as base64 — never UTF-8-decode here.
    // The JS side (RelaySocket.ts) base64-decodes back to an ArrayBuffer and
    // delivers it as a binaryType='arraybuffer' WebSocket would. nats.ws needs
    // ArrayBuffers (and NATS payloads can be binary, so UTF-8 round-tripping
    // would corrupt them); NativeNatsClient handles ArrayBuffer too.
    NSData *bytes = nil;
    if (message.type == NSURLSessionWebSocketMessageTypeData) {
      bytes = message.data;
    } else if (message.type == NSURLSessionWebSocketMessageTypeString) {
      bytes = [message.string dataUsingEncoding:NSUTF8StringEncoding];
    }
    if (bytes != nil) {
      NSString *b64 = [bytes base64EncodedStringWithOptions:0];
      [self_ emitForSocket:socketId type:@"message" body:@{@"data" : b64}];
    }

    // Continue reading on the same task.
    [self_ receiveNextForSocket:socketId task:task];
  }];
}

/**
 * Send a frame. The JS side always hands us BASE64 of the raw bytes to send
 * (RelaySocket.ts encodes whatever the caller passed — a NATS text line from
 * NativeNatsClient, or a Uint8Array from nats.ws). We decode and send a BINARY
 * WebSocket message: nats-server accepts binary frames for the (UTF-8) NATS
 * control protocol, and binary preserves any byte payload exactly.
 */
RCT_EXPORT_METHOD(send:(NSString *)socketId base64:(NSString *)base64) {
  NSURLSessionWebSocketTask *task = [self taskForSocket:socketId];
  if (!task) return;
  NSData *data = [[NSData alloc] initWithBase64EncodedString:base64 options:0];
  if (!data) return;
  NSURLSessionWebSocketMessage *msg = [[NSURLSessionWebSocketMessage alloc] initWithData:data];
  [task sendMessage:msg
  completionHandler:^(NSError *error) {
    if (error) {
      [self emitForSocket:socketId
                     type:@"error"
                     body:@{@"message" : error.localizedDescription ?: @"send failed"}];
    }
  }];
}

/** Close a socket and tear down its session. */
RCT_EXPORT_METHOD(close:(NSString *)socketId) {
  __block NSURLSessionWebSocketTask *task = nil;
  __block NSURLSession *session = nil;
  dispatch_sync(_mapQueue, ^{
    task = self->_tasks[socketId];
    session = self->_sessions[socketId];
    if (task) [self->_socketIdByTask removeObjectForKey:@(task.taskIdentifier)];
    [self->_tasks removeObjectForKey:socketId];
    [self->_sessions removeObjectForKey:socketId];
  });
  if (task) {
    [task cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
  }
  if (session) {
    [session finishTasksAndInvalidate];
  }
}

#pragma mark Delegate callbacks (invoked by WyrdRelaySocketDelegate)

- (void)didOpenTask:(NSURLSessionWebSocketTask *)task {
  NSString *socketId = [self socketIdForTask:task];
  if (socketId) [self emitForSocket:socketId type:@"open" body:nil];
}

- (void)task:(NSURLSessionTask *)task didCloseWithCode:(NSInteger)code reason:(nullable NSString *)reason {
  NSString *socketId = [self socketIdForTask:task];
  if (!socketId) return;
  [self emitForSocket:socketId
                 type:@"closing"
                 body:@{@"code" : @(code), @"reason" : reason ?: @""}];
}

- (void)task:(NSURLSessionTask *)task didCompleteWithError:(nullable NSError *)error {
  NSString *socketId = [self socketIdForTask:task];
  if (!socketId) return;

  BOOL cleanCancel = (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled);
  if (error && !cleanCancel) {
    [self emitForSocket:socketId
                   type:@"error"
                   body:@{@"message" : error.localizedDescription ?: @"connection error"}];
  }
  [self emitForSocket:socketId type:@"closed" body:nil];

  // Drop the maps; the session is invalidated on close()/dealloc.
  dispatch_sync(_mapQueue, ^{
    [self->_socketIdByTask removeObjectForKey:@(task.taskIdentifier)];
    NSURLSessionWebSocketTask *held = self->_tasks[socketId];
    if (held == task) {
      [self->_tasks removeObjectForKey:socketId];
      [self->_sessions removeObjectForKey:socketId];
    }
  });
}

- (void)invalidate {
  // Bridge teardown: cancel every live socket.
  __block NSArray<NSString *> *ids;
  dispatch_sync(_mapQueue, ^{
    ids = self->_tasks.allKeys;
  });
  for (NSString *socketId in ids) {
    [self close:socketId];
  }
  [super invalidate];
}

@end

#pragma mark - WyrdRelaySocketDelegate

@implementation WyrdRelaySocketDelegate {
  __weak WyrdRelaySocket *_module;
}

- (instancetype)initWithModule:(WyrdRelaySocket *)module {
  if ((self = [super init])) {
    _module = module;
  }
  return self;
}

/**
 * serverTrust pinning. (a) Try default system trust; if it passes (public-CA
 * relay or a household CA the user installed via Settings) -> useCredential.
 * (b) Else extract the served leaf and, if the shared WyrdTrustStore has it
 * pinned for url.host, useCredential. (c) Else cancel — the safe default for a
 * self-signed cert that was never pinned (an empty store falls through here, so
 * a connect before the pin lands correctly fails closed).
 */
- (void)URLSession:(NSURLSession *)session
    didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
      completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler {
  NSString *chHost = challenge.protectionSpace.host ?: @"";
  NSLog(@"[WyrdRelaySocket] didReceiveChallenge host=%@ method=%@ hasPins=%d",
        chHost, challenge.protectionSpace.authenticationMethod,
        [[WyrdTrustStore shared] hasPinsForHost:chHost]);
  if (![challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust]) {
    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
    return;
  }

  SecTrustRef serverTrust = challenge.protectionSpace.serverTrust;
  if (!serverTrust) {
    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
    return;
  }

  // (a) System trust first.
  CFErrorRef sysError = NULL;
  BOOL systemOk = SecTrustEvaluateWithError(serverTrust, &sysError);
  if (sysError) CFRelease(sysError);
  if (systemOk) {
    completionHandler(NSURLSessionAuthChallengeUseCredential,
                      [NSURLCredential credentialForTrust:serverTrust]);
    return;
  }

  // (b) Pinned-leaf fallback.
  NSString *host = challenge.protectionSpace.host ?: @"";
  SecCertificateRef leaf = WyrdCopyLeafCert(serverTrust);
  BOOL pinned = NO;
  if (leaf) {
    pinned = [WyrdTrustStore isPinnedForHost:host certificate:leaf];
    if (!pinned && [[WyrdTrustStore shared] hasPinsForHost:host]) {
      // Host has pins but none matched -> possible rotation/MITM. Surface it
      // through the SAME wyrd_trust_pin_mismatch DeviceEvent the existing JS
      // listener (installPinMismatchListener) consumes.
      CFDataRef derRef = SecCertificateCopyData(leaf);
      NSString *served = derRef ? WyrdSha256ColonHex((__bridge_transfer NSData *)derRef) : @"";
      NSArray<NSString *> *pins = [[WyrdTrustStore shared] pinnedFingerprintsForHost:host];
      [WyrdHouseholdTrust emitPinMismatchForHost:host
                                  newFingerprint:served
                               pinnedFingerprint:pins.firstObject ?: @""];
    }
    CFRelease(leaf);
  }

  NSLog(@"[WyrdRelaySocket] pin decision host=%@ systemOk=%d pinned=%d", host, systemOk, pinned);
  if (pinned) {
    completionHandler(NSURLSessionAuthChallengeUseCredential,
                      [NSURLCredential credentialForTrust:serverTrust]);
  } else {
    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
  }
}

#pragma mark NSURLSessionWebSocketDelegate

- (void)URLSession:(NSURLSession *)session
      webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask
    didOpenWithProtocol:(nullable NSString *)protocol {
  [_module didOpenTask:webSocketTask];
}

- (void)URLSession:(NSURLSession *)session
       webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask
    didCloseWithCode:(NSURLSessionWebSocketCloseCode)closeCode
              reason:(nullable NSData *)reason {
  NSString *reasonStr = reason ? [[NSString alloc] initWithData:reason encoding:NSUTF8StringEncoding] : nil;
  [_module task:webSocketTask didCloseWithCode:closeCode reason:reasonStr];
}

#pragma mark NSURLSessionTaskDelegate

- (void)URLSession:(NSURLSession *)session
                    task:(NSURLSessionTask *)task
    didCompleteWithError:(nullable NSError *)error {
  [_module task:task didCompleteWithError:error];
}

@end
