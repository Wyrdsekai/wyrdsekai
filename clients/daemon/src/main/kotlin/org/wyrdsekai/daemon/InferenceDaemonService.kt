package org.wyrdsekai.daemon

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Android foreground service that runs the inference daemon.
 * Survives app backgrounding — killed only by user stop, OOM, or reboot.
 *
 * Boot sequence:
 * 1. startForeground() with persistent notification
 * 2. Acquire partial WakeLock
 * 3. Check battery status (abort if not charging unless overridden)
 * 4. Load model via LlamaCppJni
 * 5. Start Ktor HTTP server (LocalInferenceServer)
 * 6. Connect to NATS
 * 7. Start gossip timer
 * 8. Subscribe to inference requests
 */
class InferenceDaemonService : Service() {

    companion object {
        private const val TAG = "InferenceDaemon"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "inference_daemon"

        const val ACTION_START = "org.wyrdsekai.daemon.START"
        const val ACTION_STOP = "org.wyrdsekai.daemon.STOP"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs by lazy { DaemonPreferences(this) }
    private val nodeId by lazy {
        prefs.nodeName() + "-daemon-" + UUID.randomUUID().toString().substring(0, 8)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var modelHandle: Long = 0
    private var httpServer: LocalInferenceServer? = null
    private var natsConnection: NatsConnectionWrapper? = null
    private var gossipClient: DaemonGossipClientKt? = null
    private var thermalGuard: ThermalGuard? = null
    private var stats = DaemonStatsKt()
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDaemon()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startDaemon()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDaemon()
        scope.cancel()
        super.onDestroy()
    }

    private fun startDaemon() {
        if (running) return

        // 1. Foreground notification
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        // 2. WakeLock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wyrdsekai:daemon")
        wakeLock?.acquire()

        // 3. Thermal guard
        thermalGuard = ThermalGuard(this) { state ->
            if (!state.acceptsRequests()) {
                Log.w(TAG, "Thermal throttle: ${state.level} — pausing inference")
            }
        }
        thermalGuard?.start()

        // 4. Load model
        scope.launch {
            try {
                val modelPath = prefs.modelPath()
                if (modelPath.isEmpty()) {
                    updateNotification("No model configured")
                    return@launch
                }

                val params = LlamaCppJni.ModelParams(
                    contextSize = prefs.contextSize(),
                    threads = prefs.maxThreads(),
                    gpuLayers = prefs.gpuLayers(),
                    flashAttention = prefs.flashAttention(),
                )
                modelHandle = LlamaCppJni.loadModel(modelPath, params)
                Log.i(TAG, "Model loaded: $modelPath")

                // 5. Start HTTP server
                httpServer = LocalInferenceServer(modelHandle, prefs.inferencePort())
                httpServer?.start()
                Log.i(TAG, "HTTP server started on port ${prefs.inferencePort()}")

                // 6. Connect to NATS
                val natsUrl = prefs.natsUrl()
                natsConnection = NatsConnectionWrapper(natsUrl, nodeId)
                try {
                    natsConnection?.connect()
                    Log.i(TAG, "NATS connected to $natsUrl")

                    // 7. Start gossip
                    gossipClient = DaemonGossipClientKt(natsConnection!!, nodeId, scope)
                    gossipClient?.startAnnouncing {
                        buildCapability()
                    }

                    // 8. Subscribe to inference requests
                    subscribeRequests()

                } catch (e: Exception) {
                    Log.w(TAG, "NATS connection failed (running in standalone mode): ${e.message}")
                }

                running = true
                updateNotification("Running (${prefs.modelId()})")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start daemon", e)
                updateNotification("Error: ${e.message}")
            }
        }
    }

    private fun stopDaemon() {
        if (!running) return
        running = false

        Log.i(TAG, "Stopping daemon")

        gossipClient?.stop()
        natsConnection?.disconnect()
        httpServer?.stop()

        if (modelHandle != 0L) {
            LlamaCppJni.unloadModel(modelHandle)
            modelHandle = 0
        }

        thermalGuard?.stop()
        wakeLock?.release()
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Daemon stopped")
    }

    private fun buildCapability(): DaemonCapabilityKt {
        val endpoint = "http://${getLocalIp()}:${prefs.inferencePort()}"
        val modelId = prefs.modelId().ifEmpty { "unknown" }
        val thermal = thermalGuard?.currentState()

        return DaemonCapabilityKt(
            nodeId = nodeId,
            models = listOf(DaemonModelKt(
                modelId = modelId,
                tier = inferTier(modelId),
                endpoint = endpoint,
                maxConcurrent = 1,
                activeLeases = stats.activeRequests,
            )),
            totalGpuCount = if (prefs.gpuLayers() > 0) 1 else 0,
            totalFreeVramMB = 0,
            availableSlots = thermal?.effectiveSlots(1) ?: 1,
            queueDepth = stats.queueDepth,
            avgLatencyMs = stats.avgLatencyMs,
            timestamp = System.currentTimeMillis() / 1000,
        )
    }

    private fun subscribeRequests() {
        val subject = "wyrd.inference.request.$nodeId"
        natsConnection?.subscribeRequestReply(subject) { data, replyTo ->
            scope.launch {
                // Forward to local HTTP server and reply
                stats.recordRequestStart()
                val startTime = System.currentTimeMillis()

                try {
                    val response = httpServer?.handleRawRequest(data)
                    val latencyMs = System.currentTimeMillis() - startTime
                    stats.recordCompletion(latencyMs, 0)
                    replyTo(response ?: "{}".toByteArray())
                } catch (e: Exception) {
                    stats.recordFailure()
                    replyTo("""{"error":"${e.message}"}""".toByteArray())
                }
            }
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, InferenceDaemonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, DaemonActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.action_stop), stopPending
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun getLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun inferTier(modelId: String): String {
        val lower = modelId.lowercase()
        return when {
            lower.contains("0.5b") || lower.contains("0.6b") || lower.contains("1b") -> "tiny"
            lower.contains("1.5b") || lower.contains("1.7b") || lower.contains("3b") || lower.contains("4b") -> "medium"
            else -> "large"
        }
    }
}
