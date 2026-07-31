package org.wyrdsekai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Native bridge to onnxruntime-android.
 *
 * The KMP Android client uses the same ai.onnxruntime API directly — this is the
 * RN-facing wrapper. Sessions are pinned in a handle map; JS side holds an opaque
 * integer it passes back into run() / close().
 *
 * @ReactModule(name = NAME) is read by the autolinking codegen so the module
 * is discoverable on both the legacy bridge and the New Architecture.
 */
@ReactModule(name = WyrdOnnxModule.NAME)
class WyrdOnnxModule(reactContext: ReactApplicationContext) : NativeWyrdOnnxSpec(reactContext) {

    companion object {
        const val NAME = NativeWyrdOnnxSpec.NAME
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = ConcurrentHashMap<Long, OrtSession>()
    private val nextHandle = AtomicLong(1)

    override fun loadModel(modelPath: String, promise: Promise) {
        try {
            val file = File(modelPath)
            if (!file.exists() || file.length() == 0L) {
                promise.reject("E_NOT_FOUND", "Model file not found or empty: $modelPath")
                return
            }
            val session = env.createSession(file.readBytes())
            val handle = nextHandle.getAndIncrement()
            sessions[handle] = session
            promise.resolve(handle.toDouble())
        } catch (e: Exception) {
            promise.reject("E_LOAD_FAILED", e.message, e)
        }
    }

    override fun run(
        handle: Double,
        inputName: String,
        inputData: ReadableArray,
        inputShape: ReadableArray,
        promise: Promise,
    ) {
        val session = sessions[handle.toLong()]
        if (session == null) {
            promise.reject("E_BAD_HANDLE", "No session for handle: $handle")
            return
        }

        val flat = FloatArray(inputData.size()) { i -> inputData.getDouble(i).toFloat() }
        val shape = LongArray(inputShape.size()) { i -> inputShape.getInt(i).toLong() }

        var tensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
            results = session.run(mapOf(inputName to tensor))
            val outputTensor = results[0]
            val value = outputTensor.value

            val outFlat: FloatArray = when (value) {
                is Array<*> -> flattenAny(value)
                is FloatArray -> value
                else -> FloatArray(0)
            }
            val outShape: LongArray = outputTensor.info.let { info ->
                (info as? ai.onnxruntime.TensorInfo)?.shape ?: longArrayOf(outFlat.size.toLong())
            }

            val dataArr: WritableArray = Arguments.createArray()
            for (v in outFlat) dataArr.pushDouble(v.toDouble())
            val shapeArr: WritableArray = Arguments.createArray()
            for (d in outShape) shapeArr.pushInt(d.toInt())

            val map: WritableMap = Arguments.createMap()
            map.putArray("data", dataArr)
            map.putArray("shape", shapeArr)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("E_RUN_FAILED", e.message, e)
        } finally {
            results?.close()
            tensor?.close()
        }
    }

    override fun close(handle: Double, promise: Promise) {
        try {
            sessions.remove(handle.toLong())?.close()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("E_CLOSE_FAILED", e.message, e)
        }
    }

    /**
     * Recursively flatten an N-d Array<*> tree of Float / FloatArray leaves into a
     * single FloatArray. Handles common ORT output shapes like (1, N, 1), (1, N),
     * (N,), as well as scalar singletons.
     */
    @Suppress("UNCHECKED_CAST")
    private fun flattenAny(value: Any?): FloatArray {
        val out = ArrayList<Float>()
        flattenInto(value, out)
        val arr = FloatArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return arr
    }

    private fun flattenInto(value: Any?, out: ArrayList<Float>) {
        when (value) {
            null -> {}
            is FloatArray -> for (v in value) out.add(v)
            is Float -> out.add(value)
            is Number -> out.add(value.toFloat())
            is Array<*> -> for (v in value) flattenInto(v, out)
        }
    }
}
