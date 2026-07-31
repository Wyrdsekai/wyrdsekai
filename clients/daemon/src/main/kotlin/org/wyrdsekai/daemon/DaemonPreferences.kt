package org.wyrdsekai.daemon

import android.content.Context
import android.content.SharedPreferences

/**
 * Android SharedPreferences wrapper for daemon settings.
 * Keys and defaults match daemon-common's DaemonConfig.
 */
class DaemonPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wyrdsekai_daemon", Context.MODE_PRIVATE)

    fun natsUrl(): String = prefs.getString("nats.url", "nats://127.0.0.1:4222") ?: "nats://127.0.0.1:4222"
    fun nodeName(): String = prefs.getString("node.name", android.os.Build.MODEL) ?: android.os.Build.MODEL
    fun modelId(): String = prefs.getString("model.id", "") ?: ""
    fun modelPath(): String = prefs.getString("model.path", "") ?: ""
    fun inferencePort(): Int = prefs.getInt("inference.port", 8080)
    fun maxThreads(): Int {
        val configured = prefs.getInt("max.threads", 0)
        return if (configured <= 0) {
            when {
                Runtime.getRuntime().availableProcessors() >= 8 -> 4
                Runtime.getRuntime().availableProcessors() >= 4 -> 2
                else -> 1
            }
        } else configured
    }
    fun contextSize(): Int = prefs.getInt("context.size", 2048)
    fun gpuLayers(): Int = prefs.getInt("gpu.layers", 0)
    fun runOnBattery(): Boolean = prefs.getBoolean("run.on.battery", false)
    fun autoStart(): Boolean = prefs.getBoolean("auto.start", false)
    fun flashAttention(): Boolean = prefs.getBoolean("flash.attention", true)

    fun setNatsUrl(url: String) = prefs.edit().putString("nats.url", url).apply()
    fun setNodeName(name: String) = prefs.edit().putString("node.name", name).apply()
    fun setModelId(id: String) = prefs.edit().putString("model.id", id).apply()
    fun setModelPath(path: String) = prefs.edit().putString("model.path", path).apply()
    fun setInferencePort(port: Int) = prefs.edit().putInt("inference.port", port).apply()
    fun setMaxThreads(threads: Int) = prefs.edit().putInt("max.threads", threads).apply()
    fun setContextSize(size: Int) = prefs.edit().putInt("context.size", size).apply()
    fun setGpuLayers(layers: Int) = prefs.edit().putInt("gpu.layers", layers).apply()
    fun setRunOnBattery(run: Boolean) = prefs.edit().putBoolean("run.on.battery", run).apply()
    fun setAutoStart(auto: Boolean) = prefs.edit().putBoolean("auto.start", auto).apply()
}
