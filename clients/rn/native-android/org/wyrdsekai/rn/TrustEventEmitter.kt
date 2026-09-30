package org.wyrdsekai.rn

import android.util.Log
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Bridges native TLS trust events (pin mismatch) to JS. The connection has
 * already been refused; the RN app shows a plain message telling the person to
 * pair again. The pin is never cleared from here (D6: no trust-new-cert path).
 *
 */
object TrustEventEmitter {
  private const val TAG = "TrustEventEmitter"
  private const val EVENT_PIN_MISMATCH = "wyrd_trust_pin_mismatch"

  @Volatile
  private var reactContext: ReactApplicationContext? = null

  /**
   * Called from MainApplication.onCreate or the RN module init. Stashes
   * the React context so events emitted from non-JS threads (OkHttp
   * background dispatcher) can route into the JS bridge.
   */
  fun init(ctx: ReactApplicationContext) {
    reactContext = ctx
  }

  fun emitPinMismatch(host: String, newFingerprint: String, pinnedFingerprint: String?) {
    val ctx = reactContext
    if (ctx == null || !ctx.hasActiveCatalystInstance()) {
      // Pre-bridge or post-teardown — log and drop. The next probe will
      // surface the same error through the exception path, so the user
      // isn't silently locked out.
      Log.w(TAG, "no active React context — dropping pin-mismatch event for $host")
      return
    }
    val params = Arguments.createMap().apply {
      putString("host", host)
      putString("newFingerprint", newFingerprint)
      putString("pinnedFingerprint", pinnedFingerprint ?: "")
    }
    try {
      ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(EVENT_PIN_MISMATCH, params)
    } catch (e: Throwable) {
      Log.w(TAG, "emit failed: ${e.message}")
    }
  }
}
