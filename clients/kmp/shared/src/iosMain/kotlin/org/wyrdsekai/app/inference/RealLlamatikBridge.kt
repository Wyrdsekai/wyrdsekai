package org.wyrdsekai.app.inference

import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

/**
 * Real Llamatik adapter — fills the [LlamatikBridge] seam with the
 * com.llamatik:library KMP artifact (llama.cpp with Metal/Accelerate).
 *
 * The seam was originally designed for Swift-side injection, but Llamatik
 * publishes ios klibs to Maven Central, so the whole wire stays in Kotlin:
 * [installLlamatikBridge] is called once from MainViewController at startup.
 */
internal object RealLlamatikBridge : LlamatikBridge {
    override fun initGenerateModel(modelPath: String) {
        check(LlamaBridge.initGenerateModel(modelPath)) {
            "Llamatik failed to load model at $modelPath"
        }
    }

    override fun generateWithContext(system: String, context: String, user: String): String =
        LlamaBridge.generateWithContext(system, context, user)

    override fun generateStreamWithContext(
        system: String,
        context: String,
        user: String,
        stream: LlamatikStreamHandler,
    ) {
        LlamaBridge.generateStreamWithContext(
            system,
            context,
            user,
            object : GenStream {
                override fun onDelta(text: String) = stream.onDelta(text)
                override fun onComplete() = stream.onComplete()
                override fun onError(message: String) = stream.onError(message)
            },
        )
    }

    override fun shutdown() = LlamaBridge.shutdown()
}

/** Idempotent; called from MainViewController before the first composition. */
fun installLlamatikBridge() {
    if (LlamatikBridge.instance == null) {
        LlamatikBridge.instance = RealLlamatikBridge
        println("Llamatik: bridge installed (on-device inference available)")
    }
}
