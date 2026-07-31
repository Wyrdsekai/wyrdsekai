package org.wyrdsekai.app.inference

/**
 * InferenceClient that routes through local JNI (LlamaServerManager) when
 * a model is loaded, falling back to HTTP when not.
 *
 * This bridges the gap between CompanionEngine (which uses InferenceClient)
 * and on-device inference (which uses LlamaServerManager JNI).
 *
 * Usage: pass this instead of plain InferenceClient to PhoneNode.
 */
class LocalFirstInferenceClient(
    private val llamaServerManager: LlamaServerManager,
) : InferenceClient() {

    override suspend fun complete(
        baseUrl: String,
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse {
        val lsmState = llamaServerManager.state.value
        val logFile = java.io.File(System.getProperty("wyrdsekai.data.dir") ?: "/tmp", "wyrd-companion.log")
        try { logFile.appendText("${java.util.Date()}: LocalFirstInferenceClient: lsmState=$lsmState\n") } catch (_: Exception) {}

        // If local model is loaded, use JNI directly (no HTTP)
        if (lsmState == "running") {
            try { logFile.appendText("${java.util.Date()}: LocalFirstInferenceClient: routing to JNI\n") } catch (_: Exception) {}
            return llamaServerManager.completeLocal(messages, options)
        }

        // Fall back to HTTP (remote Ollama, cloud, etc.)
        try { logFile.appendText("${java.util.Date()}: LocalFirstInferenceClient: falling back to HTTP baseUrl=$baseUrl\n") } catch (_: Exception) {}
        return super.complete(baseUrl, messages, options)
    }
}
