package org.wyrdsekai.app.engine.oracle

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.wyrdsekai.app.platform.AppFiles

/**
 * Live smoke test for the iOS ONNX Runtime binding (#1235): loads a model
 * with the exact TTM contract — input "context" float32 (1, 512, 1) →
 * output "forecast" (1, 96, 1) — and runs it through OnnxSession.
 *
 * Uses a tiny synthetic model (Reshape→MatMul→Reshape, weights embedded)
 * because the committed granite-ttm-r2.onnx is broken on EVERY platform:
 * it references an external-data sidecar (granite-ttm-r2.onnx.data) that
 * was never committed, so session init fails identically on desktop JVM
 * ORT and here (the prod forecaster catches this and silently falls back
 * to classical methods). Generate the smoke model with
 * scripts (see #1235 notes) and drop it at MODEL_PATH on the Mac running
 * the simulator; the test skips silently when absent.
 */
class OnnxLiveSmokeTest {

    private val modelPath = "/Users/you/models/ttm-shaped-smoke.onnx"

    @Test
    fun ttmForecastRunsOnSimulator() {
        if (!AppFiles.exists(modelPath)) {
            println("OnnxLiveSmokeTest: SKIP — no model at $modelPath")
            return
        }
        val bytes = loadModelBytes(modelPath, "ttm-shaped-smoke.onnx")
        assertTrue(bytes != null && bytes.isNotEmpty(), "loadModelBytes must read the model file")

        val session = OnnxSession(bytes!!)
        try {
            val context = FloatArray(512) { i -> sin(i / 16.0).toFloat() }
            val out = session.run("context", context, longArrayOf(1, 512, 1))
            println("OnnxLiveSmokeTest: forecast size=${out.size} head=${out.take(5)}")
            assertEquals(96, out.size, "TTM forecast must be 96 points")
            assertTrue(out.all { it.isFinite() }, "forecast must be finite")
        } finally {
            session.close()
        }
    }
}
