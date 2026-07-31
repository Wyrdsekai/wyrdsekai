package org.wyrdsekai.app.inference

/**
 * Kotlin seam for the Llamatik native library.
 *
 * The real implementation is injected at startup via
 * [LlamatikBridge.instance] — see [RealLlamatikBridge] and
 * [installLlamatikBridge] (called from MainViewController). If nothing
 * installs an instance, LlamaServerManager reports unavailable and the
 * app degrades to server/cloud inference.
 */
interface LlamatikBridge {
    fun initGenerateModel(modelPath: String)
    fun generateWithContext(system: String, context: String, user: String): String
    fun generateStreamWithContext(system: String, context: String, user: String, stream: LlamatikStreamHandler)
    fun shutdown()

    companion object {
        var instance: LlamatikBridge? = null
    }
}

/**
 * Callback interface for streaming token generation.
 */
interface LlamatikStreamHandler {
    fun onDelta(text: String)
    fun onComplete()
    fun onError(message: String)
}
