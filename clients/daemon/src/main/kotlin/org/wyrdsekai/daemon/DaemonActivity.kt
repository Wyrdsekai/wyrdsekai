package org.wyrdsekai.daemon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Minimal UI for the inference daemon.
 * Provides start/stop control, model selection, NATS configuration, and status display.
 *
 * The actual inference runs in InferenceDaemonService (foreground service).
 * This activity is just a control panel.
 */
class DaemonActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // TODO: Compose UI with 3 tabs (Status, Model, Settings)
            // Placeholder: will be implemented when Android build is set up
            // For now the service is controlled via notification actions
        }
    }

    /** Start the daemon foreground service. */
    fun startDaemon() {
        val intent = Intent(this, InferenceDaemonService::class.java).apply {
            action = InferenceDaemonService.ACTION_START
        }
        startForegroundService(intent)
    }

    /** Stop the daemon foreground service. */
    fun stopDaemon() {
        val intent = Intent(this, InferenceDaemonService::class.java).apply {
            action = InferenceDaemonService.ACTION_STOP
        }
        startService(intent)
    }
}
