package org.wyrdsekai.rn

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Manually-registered ReactPackage for the HouseholdTrust native module. Added
 * to the package list in MainApplication — bypassing autolinking because the
 * module lives in the app module itself, not a separate package.
 */
class HouseholdTrustPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
    listOf(HouseholdTrustModule(reactContext))

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
    emptyList()
}
