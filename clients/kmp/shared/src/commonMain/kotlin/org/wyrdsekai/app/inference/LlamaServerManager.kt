package org.wyrdsekai.app.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific local inference manager.
 *
 * On desktop: spawns llama-server as a child process, polls /health,
 * and watches for unexpected exit. Inference via HTTP to localhost.
 *
 * On Android: loads GGUF via JNI (llama.cpp). Inference via direct native call.
 *
 * On iOS: stub (Phase 4).
 */
expect class LlamaServerManager(scope: CoroutineScope) {
    /** "stopped" | "starting" | "running" | "error" */
    val state: StateFlow<String>

    /** null when healthy, otherwise a human-readable error description. */
    val errorMessage: StateFlow<String?>

    /** true if this platform can run local inference. */
    val isAvailable: Boolean

    /** The port the HTTP API listens on (0 = JNI direct, no HTTP). */
    val port: Int

    /** Loads a model and prepares for inference. */
    fun start(modelPath: String)

    /** Unloads the model and frees resources. */
    fun stop()

    /**
     * Run chat completion locally. Platform chooses the best path:
     * - Desktop: HTTP to local llama-server
     * - Android: JNI direct call to llama.cpp
     * - iOS: not available (throws)
     */
    suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse
}
