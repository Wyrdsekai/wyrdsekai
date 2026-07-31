package org.wyrdsekai.app.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS LlamaServerManager — on-device inference via Llamatik.
 *
 * Uses [LlamatikBridge] for direct llama.cpp inference on iOS.
 * No subprocess, no HTTP server — direct native calls through KMP bindings.
 *
 * The real Llamatik implementation is injected from the iOS app via
 * [LlamatikBridge.instance]. If not set, inference is unavailable.
 */
actual class LlamaServerManager actual constructor(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow("stopped")
    actual val state: StateFlow<String> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    actual val isAvailable: Boolean get() = LlamatikBridge.instance != null

    actual val port: Int = 0  // No HTTP server — direct native calls

    private var modelLoaded = false

    actual fun start(modelPath: String) {
        if (_state.value == "running" || _state.value == "starting") return

        val bridge = LlamatikBridge.instance
        if (bridge == null) {
            _state.value = "error"
            _errorMessage.value = "Llamatik not available. Set LlamatikBridge.instance from the iOS app."
            return
        }

        _state.value = "starting"
        _errorMessage.value = null

        scope.launch(Dispatchers.Default) {
            try {
                bridge.initGenerateModel(modelPath)
                modelLoaded = true
                _state.value = "running"
            } catch (e: Exception) {
                _state.value = "error"
                _errorMessage.value = "Failed to load model: ${e.message}"
                modelLoaded = false
            }
        }
    }

    actual suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse = withContext(Dispatchers.Default) {
        val bridge = LlamatikBridge.instance
            ?: error("Llamatik not available. Set LlamatikBridge.instance from the iOS app.")
        if (!modelLoaded) error("No model loaded. Call start() first.")

        val (system, context, user) = formatMessages(messages)

        if (options.onToken != null) {
            // Streaming mode
            val builder = StringBuilder()
            var streamError: String? = null

            bridge.generateStreamWithContext(
                system = system,
                context = context,
                user = user,
                stream = object : LlamatikStreamHandler {
                    override fun onDelta(text: String) {
                        builder.append(text)
                        options.onToken.invoke(text)
                    }

                    override fun onComplete() {
                        // Generation finished
                    }

                    override fun onError(message: String) {
                        streamError = message
                    }
                },
            )

            if (streamError != null) {
                error("Inference stream error: $streamError")
            }

            val content = builder.toString()
            val words = content.trim().split("\\s+".toRegex()).size
            ChatResponse(
                content = content,
                promptTokens = (system.length + context.length + user.length) / 4,
                completionTokens = words,
            )
        } else {
            // Blocking mode
            val content = bridge.generateWithContext(
                system = system,
                context = context,
                user = user,
            )
            val words = content.trim().split("\\s+".toRegex()).size
            ChatResponse(
                content = content,
                promptTokens = (system.length + context.length + user.length) / 4,
                completionTokens = words,
            )
        }
    }

    actual fun stop() {
        if (modelLoaded) {
            try {
                LlamatikBridge.instance?.shutdown()
            } catch (_: Exception) {
                // Best effort
            }
            modelLoaded = false
        }
        _state.value = "stopped"
        _errorMessage.value = null
    }

    /**
     * Split a ChatMessage list into Llamatik's (system, context, user) triple.
     */
    private fun formatMessages(messages: List<ChatMessage>): Triple<String, String, String> {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content ?: ""

        val nonSystem = messages.filter { it.role != "system" }

        if (nonSystem.isEmpty()) {
            return Triple(systemMsg, "", "")
        }

        val lastUser = if (nonSystem.last().role == "user") nonSystem.last().content else ""
        val contextMessages = if (nonSystem.last().role == "user") {
            nonSystem.dropLast(1)
        } else {
            nonSystem
        }

        val context = contextMessages.joinToString("\n") { msg ->
            "${msg.role}: ${msg.content}"
        }

        return Triple(systemMsg, context, lastUser)
    }
}
