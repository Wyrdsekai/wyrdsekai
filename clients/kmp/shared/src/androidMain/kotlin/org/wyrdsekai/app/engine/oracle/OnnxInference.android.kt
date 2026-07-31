package org.wyrdsekai.app.engine.oracle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Android ONNX Runtime session.
 *
 * onnxruntime-android mirrors the JVM API exactly — same classes,
 * same package (ai.onnxruntime), same tensor handling.
 */
actual class OnnxSession actual constructor(modelBytes: ByteArray) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes)

    actual fun run(inputName: String, input: FloatArray, inputShape: LongArray): FloatArray {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape)
        try {
            val results = session.run(mapOf(inputName to tensor))
            try {
                val outputTensor = results[0]
                val value = outputTensor.value

                return when (value) {
                    is Array<*> -> flatten3d(value)
                    is FloatArray -> value
                    else -> FloatArray(0)
                }
            } finally {
                results.close()
            }
        } finally {
            tensor.close()
        }
    }

    actual fun close() {
        session.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun flatten3d(value: Any): FloatArray {
        val batch = value as? Array<*> ?: return FloatArray(0)
        val steps = batch[0] as? Array<*> ?: return FloatArray(0)
        return FloatArray(steps.size) { i ->
            val channel = steps[i] as? FloatArray
            channel?.get(0) ?: 0f
        }
    }
}

/**
 * Android: load model from file path or classpath resource.
 */
actual fun loadModelBytes(path: String, fallbackResource: String): ByteArray? {
    // Try as a file path first (e.g. app-specific storage)
    try {
        val file = File(path)
        if (file.exists() && file.length() > 0) {
            return file.readBytes()
        }
    } catch (_: Exception) {}

    // Try classpath resource
    try {
        val resourcePath = if (path.startsWith("/")) path else "/$path"
        val stream = TtmPhoneForecaster::class.java.getResourceAsStream(resourcePath)
        if (stream != null) {
            return stream.use { it.readBytes() }
        }
    } catch (_: Exception) {}

    // Try with fallback resource name
    try {
        val stream = TtmPhoneForecaster::class.java.getResourceAsStream("/$fallbackResource")
        if (stream != null) {
            return stream.use { it.readBytes() }
        }
    } catch (_: Exception) {}

    return null
}
