package org.wyrdsekai.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log

/**
 * Starts the daemon on boot if auto-start is enabled.
 * Waits for charging state before starting inference (unless overridden).
 *
 * Declared in AndroidManifest.xml with android:enabled="false" —
 * enabled programmatically when user turns on auto-start in settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = DaemonPreferences(context)
        if (!prefs.autoStart()) {
            Log.d(TAG, "Auto-start disabled, skipping")
            return
        }

        // Check charging state
        if (!prefs.runOnBattery()) {
            val bm = context.getSystemService(BatteryManager::class.java)
            if (!bm.isCharging) {
                Log.i(TAG, "Not charging, deferring daemon start until charger connected")
                // TODO: register a charging state receiver to start when plugged in
                return
            }
        }

        Log.i(TAG, "Auto-starting inference daemon after boot")
        val serviceIntent = Intent(context, InferenceDaemonService::class.java).apply {
            action = InferenceDaemonService.ACTION_START
        }
        context.startForegroundService(serviceIntent)
    }
}
