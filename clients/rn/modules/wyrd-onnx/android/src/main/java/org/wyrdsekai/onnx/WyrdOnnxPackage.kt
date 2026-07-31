package org.wyrdsekai.onnx

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * BaseReactPackage = New Architecture-aware. Reports module info for the codegen
 * registry without instantiating until a JS caller actually requires it.
 */
class WyrdOnnxPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return if (name == WyrdOnnxModule.NAME) WyrdOnnxModule(reactContext) else null
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
        return ReactModuleInfoProvider {
            mapOf(
                WyrdOnnxModule.NAME to ReactModuleInfo(
                    /* name = */ WyrdOnnxModule.NAME,
                    /* className = */ WyrdOnnxModule::class.java.name,
                    /* canOverrideExistingModule = */ false,
                    /* needsEagerInit = */ false,
                    /* isCxxModule = */ false,
                    /* isTurboModule = */ true,
                ),
            )
        }
    }
}
