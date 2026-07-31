package org.wyrdsekai.app.engine.oracle

/**
 * Expect/actual wrapper around ONNX Runtime for KMP.
 *
 * Desktop + Android: real ONNX Runtime (JVM API, identical on both).
 * iOS: stub — ONNX Runtime Swift needs cinterop setup (future work).
 *
 * Used by TtmPhoneForecaster for on-device time series inference.
 */
expect class OnnxSession(modelBytes: ByteArray) {
    /**
     * Run inference on a single named input tensor.
     * @param inputName The ONNX input node name (e.g. "context")
     * @param input Flat float array (row-major)
     * @param inputShape Shape of the input tensor (e.g. [1, 512, 1])
     * @return Flat float array of the first output tensor
     */
    fun run(inputName: String, input: FloatArray, inputShape: LongArray): FloatArray

    /** Release ONNX Runtime session resources. */
    fun close()
}

/**
 * Load model bytes from a file path or classpath resource.
 * Desktop/Android: tries file path, then classpath resource.
 * iOS: returns null (model loading not yet supported).
 */
expect fun loadModelBytes(path: String, fallbackResource: String): ByteArray?
