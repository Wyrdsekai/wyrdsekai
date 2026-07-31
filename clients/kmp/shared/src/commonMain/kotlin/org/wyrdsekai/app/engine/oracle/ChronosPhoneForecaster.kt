package org.wyrdsekai.app.engine.oracle

import kotlin.math.abs
import kotlin.math.min

/**
 * IBM Granite TTM (TinyTimeMixer) forecaster for phone via ONNX Runtime.
 *
 * Loads the 805K param TTM ONNX model (~1.0MB) and runs zero-shot time series
 * forecasting on device. Pure MLP mixer architecture — no attention, no exotic
 * ops, trivial ONNX export. 0.7ms inference on CPU.
 *
 * Switched from Chronos-Bolt-Tiny (9M, ~35MB) to TTM (805K, ~1.0MB) on 2026-03-29.
 * Reason: Chronos ONNX export has unresolved operator issues (aten::nanmean
 * not supported in ONNX opset 17, github.com/amazon-science/chronos-forecasting/discussions/272).
 * TTM is 11x smaller, trivially exportable, and zero-shot competitive.
 *
 * Model: ibm-granite/granite-timeseries-ttm-r2 on HuggingFace
 * ONNX: opset 18, torch.export(strict=False)
 * Input:  float32 (1, 512, 1) named "context"
 * Output: float32 (1, 96, 1)  named "forecast"
 *
 * Platform support:
 * - Android: com.microsoft.onnxruntime:onnxruntime-android:1.23.2
 * - Desktop: com.microsoft.onnxruntime:onnxruntime:1.23.2
 * - iOS: stub (needs onnxruntime-swift cinterop, future work)
 *
 * Usage:
 *   val forecaster = TtmPhoneForecaster("granite-ttm-r2.onnx")
 *   forecaster.load()  // loads from classpath resource or file path
 *   val points = forecaster.forecast(values, horizon = 96)
 *   // points = List<Triple<predicted, lowerBound, upperBound>>
 *   forecaster.close()
 */
class TtmPhoneForecaster(
    private val modelPath: String,
) {
    private var session: OnnxSession? = null
    private var loaded = false

    /**
     * Load the ONNX model. Call once at startup.
     * Tries file path first, then classpath resource.
     * Returns false if ONNX Runtime is not available or model is missing.
     */
    fun load(): Boolean {
        try {
            val bytes = loadModelBytes(modelPath, MODEL_FILENAME)
            if (bytes == null || bytes.isEmpty()) {
                loaded = false
                return false
            }
            session = OnnxSession(bytes)
            loaded = true
            return true
        } catch (e: Exception) {
            println("[TtmForecaster] Failed to load model: ${e.message}")
            loaded = false
            return false
        }
    }

    /**
     * Run zero-shot forecast on a time series.
     *
     * Input values are zero-padded (left) or truncated (right) to CONTEXT_LENGTH (512).
     * The model outputs FORECAST_HORIZON (96) point forecasts.
     * Confidence intervals are approximated as +/- 10% of absolute predicted value.
     *
     * @param values Historical values (at least 10 points)
     * @param horizon Number of future points to return (capped at FORECAST_HORIZON)
     * @return List of (predicted, lowerBound, upperBound) triples, or empty if not loaded
     */
    fun forecast(
        values: List<Double>,
        horizon: Int = FORECAST_HORIZON,
    ): List<Triple<Double, Double, Double>> {
        val s = session
        if (!loaded || s == null || values.size < 10) return emptyList()

        // Prepare context window: left-pad with zeros if shorter than 512,
        // take the last 512 values if longer
        val context = FloatArray(CONTEXT_LENGTH)
        if (values.size >= CONTEXT_LENGTH) {
            // Take the most recent CONTEXT_LENGTH values
            val start = values.size - CONTEXT_LENGTH
            for (i in 0 until CONTEXT_LENGTH) {
                context[i] = values[start + i].toFloat()
            }
        } else {
            // Left-pad with zeros
            val offset = CONTEXT_LENGTH - values.size
            for (i in values.indices) {
                context[offset + i] = values[i].toFloat()
            }
        }

        // Run ONNX inference: input shape (1, 512, 1), output shape (1, 96, 1)
        val output = try {
            s.run(INPUT_NAME, context, longArrayOf(1, CONTEXT_LENGTH.toLong(), 1))
        } catch (e: Exception) {
            println("[TtmForecaster] Inference failed: ${e.message}")
            return emptyList()
        }

        if (output.isEmpty()) return emptyList()

        // Build result triples with simple confidence bands
        // TTM outputs point forecasts only — approximate confidence as +/- 10%
        val actualHorizon = min(horizon, min(output.size, FORECAST_HORIZON))
        val result = ArrayList<Triple<Double, Double, Double>>(actualHorizon)
        for (i in 0 until actualHorizon) {
            val predicted = output[i].toDouble()
            val margin = abs(predicted) * CONFIDENCE_MARGIN
            result.add(Triple(predicted, predicted - margin, predicted + margin))
        }
        return result
    }

    /** Whether the model is loaded and ready for inference. */
    fun isAvailable(): Boolean = loaded

    /** Release ONNX session resources. */
    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {}
        session = null
        loaded = false
    }

    // Model loading delegated to expect/actual loadModelBytes() in OnnxInference.kt

    companion object {
        /** Expected model filename. Export from ibm-granite/granite-timeseries-ttm-r2. */
        const val MODEL_FILENAME = "granite-ttm-r2.onnx"
        /** Actual model size: ~1.0MB (805K params, ONNX opset 18). */
        const val EXPECTED_SIZE_BYTES = 1_100_000L
        /** Fixed context length: 512 time steps input. */
        const val CONTEXT_LENGTH = 512
        /** Forecast horizon: 96 steps output. */
        const val FORECAST_HORIZON = 96
        /** ONNX input tensor name. */
        const val INPUT_NAME = "context"
        /** ONNX output tensor name. */
        const val OUTPUT_NAME = "forecast"
        /** Confidence band margin (10% of absolute value). */
        private const val CONFIDENCE_MARGIN = 0.1
    }
}

// Keep old name as typealias for backward compatibility
@Deprecated("Switched from Chronos to TTM. Use TtmPhoneForecaster.", replaceWith = ReplaceWith("TtmPhoneForecaster"))
typealias ChronosPhoneForecaster = TtmPhoneForecaster
