package org.wyrdsekai.app.inference

/**
 * JNI bindings to llama.cpp for on-device inference on Android.
 *
 * The native library (libwyrd-llama.so) is loaded from jniLibs/arm64-v8a/.
 * Build instructions in llama-jni/CMakeLists.txt.
 *
 * This is the KMP app's own JNI binding — separate from the daemon app's
 * LlamaCppJni (different package = different JNI function names).
 */
object LlamaCppBridge {

    private var loaded = false

    /** Try to load the native library. Returns false if unavailable. */
    fun tryLoad(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("wyrd-llama")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Whether the native library was successfully loaded. */
    fun isAvailable(): Boolean = loaded

    data class ModelParams(
        val contextSize: Int = 2048,
        val threads: Int = 4,
        val gpuLayers: Int = 0,
        val flashAttention: Boolean = true,
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

    /**
     * Run chat completion with GBNF grammar constraint.
     * The model can ONLY output tokens that match the grammar.
     * Used for room action selection — speech remains free via the grammar's say: rule.
     */
    @JvmStatic
    external fun completeWithGrammar(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stopTokens: Array<String>,
        grammar: String,
    ): String

    /** Health check — returns true if the model is loaded and functional. */
    @JvmStatic
    external fun healthCheck(handle: Long): Boolean

    /** Get the loaded model's context size. */
    @JvmStatic
    external fun contextSize(handle: Long): Int
}
