package org.wyrdsekai.daemon

/**
 * JNI bindings to llama.cpp for on-device inference.
 *
 * The native library (libllama-jni.so) is loaded from jniLibs/arm64-v8a/.
 * Build instructions in llama-jni/CMakeLists.txt.
 *
 * NOTE: llama.cpp versions after b5028 have reported segfaults on Android.
 * Pin to a known-good version and test on target devices.
 */
object LlamaCppJni {

    init {
        System.loadLibrary("llama-jni")
    }

    data class ModelParams(
        val contextSize: Int = 2048,
        val threads: Int = 4,
        val gpuLayers: Int = 0,
        val flashAttention: Boolean = true,
        val mmap: Boolean = true,
    )

    data class ModelInfoJni(
        val paramCount: Long,
        val quantType: String,
        val contextLength: Int,
        val vocabSize: Int,
    )

    /**
     * Load a GGUF model. Returns a handle for subsequent operations.
     * @throws RuntimeException if model loading fails
     */
    @JvmStatic
    external fun loadModel(path: String, params: ModelParams): Long

    /** Unload a model and free resources. */
    @JvmStatic
    external fun unloadModel(handle: Long)

    /**
     * Run chat completion. Returns the generated text.
     * Blocks the calling thread until generation is complete.
     */
    @JvmStatic
    external fun complete(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopTokens: Array<String>,
    ): String

    /** Health check — returns true if the model is loaded and functional. */
    @JvmStatic
    external fun healthCheck(handle: Long): Boolean

    /** Get the loaded model's context size. */
    @JvmStatic
    external fun contextSize(handle: Long): Int

    /** Get model metadata. */
    @JvmStatic
    external fun modelInfo(handle: Long): ModelInfoJni
}
