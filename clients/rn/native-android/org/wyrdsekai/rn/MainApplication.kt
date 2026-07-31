package org.wyrdsekai.rn

import android.app.Application
import android.content.res.Configuration

import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactPackage
import com.facebook.react.ReactHost
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.modules.network.OkHttpClientProvider

import expo.modules.ApplicationLifecycleDispatcher
import expo.modules.ExpoReactHostFactory

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    ExpoReactHostFactory.getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // HouseholdTrust lives in the app module — too small for autolinking,
          // and it has to register with the bridge before any HTTPS request
          // hits the OkHttp TrustManager.
          add(HouseholdTrustPackage())
        }
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Install the per-host TrustManager BEFORE loadReactNative. RN reads
    // OkHttpClientProvider at first NetworkingModule construction; if we
    // hand off too late, the default factory has already cached a client
    // without our pin lookup.
    HouseholdTrustStore.init(this)
    OkHttpClientProvider.setOkHttpClientFactory(WyrdOkHttpClientFactory())

    DefaultNewArchitectureEntryPoint.releaseLevel = try {
      ReleaseLevel.valueOf(BuildConfig.REACT_NATIVE_RELEASE_LEVEL.uppercase())
    } catch (e: IllegalArgumentException) {
      ReleaseLevel.STABLE
    }
    loadReactNative(this)
    ApplicationLifecycleDispatcher.onApplicationCreate(this)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
  }
}
