@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.wyrdsekai.app.engine.oracle

import com.microsoft.onnxruntime.c.ONNXTensorElementDataType
import com.microsoft.onnxruntime.c.ORT_API_VERSION
import com.microsoft.onnxruntime.c.OrtApi
import com.microsoft.onnxruntime.c.OrtArenaAllocator
import com.microsoft.onnxruntime.c.OrtGetApiBase
import com.microsoft.onnxruntime.c.OrtLoggingLevel
import com.microsoft.onnxruntime.c.OrtAllocator
import com.microsoft.onnxruntime.c.OrtMemTypeDefault
import cnames.structs.OrtEnv
import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtValue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS ONNX Runtime session over the C API (onnxruntime-c xcframework,
 * bound via cinterop — see shared/build.gradle.kts `downloadOnnxRuntimeIos`
 * and src/nativeInterop/cinterop/onnxruntime.def).
 *
 * The OrtApi is a struct of function pointers fetched once from
 * OrtGetApiBase(); every call returns an OrtStatus* that is null on success.
 */
private val ortApi: CPointer<OrtApi> by lazy {
    val base = OrtGetApiBase() ?: error("OrtGetApiBase returned null")
    base.pointed.GetApi!!.invoke(ORT_API_VERSION.toUInt())
        ?: error("OrtApi unavailable for API version $ORT_API_VERSION")
}

private fun check(status: CPointer<OrtStatus>?, what: String) {
    if (status == null) return
    val message = ortApi.pointed.GetErrorMessage!!.invoke(status)?.toKString() ?: "unknown"
    ortApi.pointed.ReleaseStatus!!.invoke(status)
    error("ONNX Runtime $what failed: $message")
}

actual class OnnxSession actual constructor(modelBytes: ByteArray) {
    private val api = ortApi.pointed
    private var env: CPointer<OrtEnv>? = null
    private var session: CPointer<OrtSession>? = null
    private var memoryInfo: CPointer<OrtMemoryInfo>? = null
    private var outputName: String = ""
    private var closed = false

    init {
        memScoped {
            val envOut = alloc<CPointerVar<OrtEnv>>()
            check(
                api.CreateEnv!!.invoke(
                    OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING,
                    "wyrdsekai".cstr.ptr,
                    envOut.ptr,
                ),
                "CreateEnv",
            )
            env = envOut.value

            val optsOut = alloc<CPointerVar<OrtSessionOptions>>()
            check(api.CreateSessionOptions!!.invoke(optsOut.ptr), "CreateSessionOptions")
            val opts = optsOut.value

            val sessionOut = alloc<CPointerVar<OrtSession>>()
            try {
                modelBytes.usePinned { pinned ->
                    check(
                        api.CreateSessionFromArray!!.invoke(
                            env,
                            pinned.addressOf(0),
                            modelBytes.size.toULong(),
                            opts,
                            sessionOut.ptr,
                        ),
                        "CreateSessionFromArray",
                    )
                }
            } finally {
                api.ReleaseSessionOptions!!.invoke(opts)
            }
            session = sessionOut.value

            val memOut = alloc<CPointerVar<OrtMemoryInfo>>()
            check(
                api.CreateCpuMemoryInfo!!.invoke(OrtArenaAllocator, OrtMemTypeDefault, memOut.ptr),
                "CreateCpuMemoryInfo",
            )
            memoryInfo = memOut.value

            // The seam only names the input; resolve the single output once.
            val allocOut = alloc<CPointerVar<OrtAllocator>>()
            check(api.GetAllocatorWithDefaultOptions!!.invoke(allocOut.ptr), "GetAllocatorWithDefaultOptions")
            val allocator = allocOut.value
            val nameOut = alloc<CPointerVar<ByteVar>>()
            check(
                api.SessionGetOutputName!!.invoke(session, 0uL, allocator, nameOut.ptr),
                "SessionGetOutputName",
            )
            outputName = nameOut.value?.toKString() ?: ""
            nameOut.value?.let { api.AllocatorFree!!.invoke(allocator, it) }
        }
    }

    actual fun run(inputName: String, input: FloatArray, inputShape: LongArray): FloatArray {
        val s = session ?: return FloatArray(0)
        memScoped {
            val inputValueOut = alloc<CPointerVar<OrtValue>>()
            input.usePinned { pinnedInput ->
                check(
                    api.CreateTensorWithDataAsOrtValue!!.invoke(
                        memoryInfo,
                        pinnedInput.addressOf(0),
                        (input.size * Float.SIZE_BYTES).toULong(),
                        inputShape.refTo(0).getPointer(this@memScoped),
                        inputShape.size.toULong(),
                        ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                        inputValueOut.ptr,
                    ),
                    "CreateTensorWithDataAsOrtValue",
                )

                val inputNames = allocArray<CPointerVar<ByteVar>>(1)
                inputNames[0] = inputName.cstr.ptr
                val outputNames = allocArray<CPointerVar<ByteVar>>(1)
                outputNames[0] = outputName.cstr.ptr
                val inputValues = allocArray<CPointerVar<OrtValue>>(1)
                inputValues[0] = inputValueOut.value
                val outputValues = allocArray<CPointerVar<OrtValue>>(1)

                try {
                    check(
                        api.Run!!.invoke(
                            s,
                            null,
                            inputNames,
                            inputValues,
                            1uL,
                            outputNames,
                            1uL,
                            outputValues,
                        ),
                        "Run",
                    )
                    val output = outputValues[0] ?: return FloatArray(0)
                    try {
                        return readFloatTensor(output)
                    } finally {
                        api.ReleaseValue!!.invoke(output)
                    }
                } finally {
                    inputValueOut.value?.let { api.ReleaseValue!!.invoke(it) }
                }
            }
        }
    }

    private fun readFloatTensor(value: CPointer<OrtValue>): FloatArray = memScoped {
        val infoOut = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
        check(api.GetTensorTypeAndShape!!.invoke(value, infoOut.ptr), "GetTensorTypeAndShape")
        val info = infoOut.value
        val count = try {
            val countOut = alloc<ULongVar>()
            check(api.GetTensorShapeElementCount!!.invoke(info, countOut.ptr), "GetTensorShapeElementCount")
            countOut.value.toInt()
        } finally {
            api.ReleaseTensorTypeAndShapeInfo!!.invoke(info)
        }
        if (count <= 0) return FloatArray(0)

        val dataOut = alloc<kotlinx.cinterop.COpaquePointerVar>()
        check(api.GetTensorMutableData!!.invoke(value, dataOut.ptr), "GetTensorMutableData")
        val floats = dataOut.value?.reinterpret<FloatVar>() ?: return FloatArray(0)
        FloatArray(count) { i -> floats[i] }
    }

    actual fun close() {
        if (closed) return
        closed = true
        memoryInfo?.let { api.ReleaseMemoryInfo!!.invoke(it) }
        memoryInfo = null
        session?.let { api.ReleaseSession!!.invoke(it) }
        session = null
        env?.let { api.ReleaseEnv!!.invoke(it) }
        env = null
    }
}

/**
 * iOS: load model bytes from an absolute file path, then from the app
 * bundle (top level, then by base name — mirrors readBundledText).
 */
actual fun loadModelBytes(path: String, fallbackResource: String): ByteArray? {
    NSData.dataWithContentsOfFile(path)?.let { return it.toByteArray() }

    val baseName = fallbackResource.substringBeforeLast('.')
    val ext = fallbackResource.substringAfterLast('.', "")
    val bundlePath = NSBundle.mainBundle.pathForResource(baseName, ext.ifEmpty { null })
        ?: return null
    return NSData.dataWithContentsOfFile(bundlePath)?.toByteArray()
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
