package org.wyrdsekai.app.inference

import kotlin.time.Clock
import org.wyrdsekai.app.platform.AppFiles
import org.wyrdsekai.app.platform.AppProps

/**
 * InferenceClient that routes through local JNI (LlamaServerManager) when
 * a model is loaded, falling back to HTTP when not.
 *
 * This bridges the gap between CompanionEngine (which uses InferenceClient)
 * and on-device inference (which uses LlamaServerManager JNI).
 *
 * Usage: pass this instead of plain InferenceClient to PhoneNode.
 *
 * Overrides [send], not [complete], so the JNI path formats the copy that
 * [complete] has already given one leading system message and stamped with what
 * the request knows about today.
 */
class LocalFirstInferenceClient(
    private val llamaServerManager: LlamaServerManager,
) : InferenceClient() {

    override suspend fun send(
        baseUrl: String,
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse {
        // If local model is loaded, use JNI directly (no HTTP)
        if (llamaServerManager.state.value == "running") {
            log("routing to JNI")
            return llamaServerManager.completeLocal(messages, options)
        }

        // Fall back to HTTP (remote Ollama, cloud, etc.)
        log("falling back to HTTP baseUrl=$baseUrl")
        return super.send(baseUrl, messages, options)
    }

    /**
     * One line per request in the companion's log, written as
     * CompanionEngine.debugLog writes it. This wrote two lines per request with
     * java.util.Date stamps, and into /tmp when wyrdsekai.data.dir was unset; with
     * no data dir it now writes nothing.
     */
    private fun log(msg: String) {
        try {
            val dir = AppProps.get("wyrdsekai.data.dir") ?: return
            AppFiles.appendText("$dir/wyrd-companion.log", "${Clock.System.now()}: LocalFirstInferenceClient: $msg\n")
        } catch (_: Exception) {}
    }
}
