#import "WyrdOnnx.h"

#import <Foundation/Foundation.h>
// onnxruntime-objc publishes its umbrella header under the pod-named dir
// (Pods/Headers/Public/onnxruntime-objc/onnxruntime.h), so the import prefix
// must be the pod name, not a bare "onnxruntime". The bare path compiled
// nowhere — wyrd-onnx had only ever been built on the Android emulator, so
// this iOS-only header miss went unnoticed until the first simulator build.
#import <onnxruntime-objc/onnxruntime.h>

// Real onnxruntime-objc-backed TurboModule. Mirrors the JVM-side semantics in
// android/src/.../WyrdOnnxModule.kt: a handle map of ORTSessions identified by
// a numeric token returned to JS at loadModel().

@interface WyrdOnnx ()
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, ORTSession *> *sessions;
@property(nonatomic, strong) ORTEnv *env;
@property(nonatomic, assign) int64_t nextHandle;
@end

@implementation WyrdOnnx

RCT_EXPORT_MODULE()

- (instancetype)init {
  if ((self = [super init])) {
    NSError *error = nil;
    _env = [[ORTEnv alloc] initWithLoggingLevel:ORTLoggingLevelWarning error:&error];
    _sessions = [NSMutableDictionary new];
    _nextHandle = 1;
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup { return NO; }

#pragma mark - loadModel

- (void)loadModel:(NSString *)modelPath
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject {
  if (!self.env) {
    reject(@"E_NO_ENV", @"ORTEnv failed to initialize", nil);
    return;
  }
  NSError *error = nil;
  ORTSessionOptions *options = [[ORTSessionOptions alloc] initWithError:&error];
  if (error) {
    reject(@"E_OPTIONS", error.localizedDescription, error);
    return;
  }

  ORTSession *session = [[ORTSession alloc] initWithEnv:self.env
                                              modelPath:modelPath
                                         sessionOptions:options
                                                  error:&error];
  if (error || !session) {
    reject(@"E_LOAD_FAILED", error.localizedDescription ?: @"unknown", error);
    return;
  }

  int64_t h;
  @synchronized(self) {
    h = self.nextHandle++;
    self.sessions[@(h)] = session;
  }
  resolve(@(h));
}

#pragma mark - run

- (void)run:(double)handle
  inputName:(NSString *)inputName
  inputData:(NSArray *)inputData
 inputShape:(NSArray *)inputShape
    resolve:(RCTPromiseResolveBlock)resolve
     reject:(RCTPromiseRejectBlock)reject {
  ORTSession *session;
  @synchronized(self) {
    session = self.sessions[@((int64_t)handle)];
  }
  if (!session) {
    reject(@"E_BAD_HANDLE", [NSString stringWithFormat:@"No session for handle: %f", handle], nil);
    return;
  }

  // Pack JS number[] → NSData<float>
  NSUInteger n = inputData.count;
  NSMutableData *bytes = [NSMutableData dataWithLength:n * sizeof(float)];
  float *fp = (float *)bytes.mutableBytes;
  for (NSUInteger i = 0; i < n; i++) fp[i] = [(NSNumber *)inputData[i] floatValue];

  // Shape as NSArray<NSNumber*> for ORTValue.
  NSMutableArray<NSNumber *> *shape = [NSMutableArray arrayWithCapacity:inputShape.count];
  for (id d in inputShape) [shape addObject:@([d longLongValue])];

  NSError *error = nil;
  ORTValue *inputValue = [[ORTValue alloc] initWithTensorData:bytes
                                                  elementType:ORTTensorElementDataTypeFloat
                                                        shape:shape
                                                        error:&error];
  if (error || !inputValue) {
    reject(@"E_TENSOR", error.localizedDescription ?: @"tensor create failed", error);
    return;
  }

  // Run inference. We don't know the output name a priori — discover via outputNamesWithError.
  NSArray<NSString *> *outputNames = [session outputNamesWithError:&error];
  if (error || outputNames.count == 0) {
    reject(@"E_OUTPUT_NAMES", error.localizedDescription ?: @"no outputs", error);
    return;
  }
  NSSet<NSString *> *outputSet = [NSSet setWithArray:outputNames];

  NSDictionary<NSString *, ORTValue *> *outputs =
      [session runWithInputs:@{inputName : inputValue}
                 outputNames:outputSet
                  runOptions:nil
                       error:&error];
  if (error || !outputs) {
    reject(@"E_RUN_FAILED", error.localizedDescription ?: @"run failed", error);
    return;
  }

  ORTValue *output = outputs[outputNames.firstObject];
  ORTTensorTypeAndShapeInfo *info = [output tensorTypeAndShapeInfoWithError:&error];
  if (error || !info) {
    reject(@"E_OUTPUT_INFO", error.localizedDescription ?: @"info failed", error);
    return;
  }
  NSData *outBytes = [output tensorDataWithError:&error];
  if (error || !outBytes) {
    reject(@"E_OUTPUT_DATA", error.localizedDescription ?: @"data failed", error);
    return;
  }

  NSUInteger outCount = outBytes.length / sizeof(float);
  const float *outFp = (const float *)outBytes.bytes;
  NSMutableArray<NSNumber *> *outArr = [NSMutableArray arrayWithCapacity:outCount];
  for (NSUInteger i = 0; i < outCount; i++) [outArr addObject:@(outFp[i])];

  NSMutableArray<NSNumber *> *outShape = [NSMutableArray arrayWithCapacity:info.shape.count];
  for (NSNumber *d in info.shape) [outShape addObject:@(d.intValue)];

  resolve(@{@"data" : outArr, @"shape" : outShape});
}

#pragma mark - close

- (void)close:(double)handle
      resolve:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject {
  @synchronized(self) {
    [self.sessions removeObjectForKey:@((int64_t)handle)];
  }
  resolve(nil);
}

#pragma mark - TurboModule conformance

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)
    getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeWyrdOnnxSpecJSI>(params);
}
#endif

@end
