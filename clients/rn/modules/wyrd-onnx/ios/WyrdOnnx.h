#import <React/RCTBridgeModule.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <WyrdOnnxSpec/WyrdOnnxSpec.h>

@interface WyrdOnnx : NSObject <NativeWyrdOnnxSpec>
@end
#else

@interface WyrdOnnx : NSObject <RCTBridgeModule>
@end

#endif
