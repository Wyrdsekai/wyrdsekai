package org.wyrdsekai.app.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android implementation of [LlamaServerManager].
 *
 * Uses JNI bindings to llama.cpp via [LlamaCppBridge] for on-device inference.
 * No subprocess, no HTTP server — direct native calls.
 *
 * The native library (libwyrd-llama.so) must be present in jniLibs/arm64-v8a/.
 * If absent, [isAvailable] returns false and [start] reports an error.
 */
actual class LlamaServerManager actual constructor(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow("stopped")
    actual val state: StateFlow<String> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    actual val isAvailable: Boolean = LlamaCppBridge.tryLoad()
    actual val port: Int = 0  // No HTTP server — JNI direct

    private var modelHandle: Long = 0L
    private var loadedModelPath: String? = null

    actual suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse = withContext(Dispatchers.IO) {
        completeJni(messages, options)
    }

    /** Direct JNI completion — blocks, must be called from IO dispatcher. */
    private fun completeJni(
        messages: List<ChatMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): ChatResponse {
        if (modelHandle == 0L) error("No model loaded")

        // Detect model type from loaded model path and use appropriate format
        val isGemma = loadedModelPath?.contains("gemma", ignoreCase = true) == true
        val prompt = if (isGemma) {
            formatGemmaFunctionCall(messages, STUDY_TOOLS)
        } else {
            formatChatMLNoThink(messages)
        }
        val stopTokens = if (isGemma) {
            arrayOf("<end_of_turn>", "<end_function_call>", "</s>")
        } else {
            arrayOf("</s>", "<|endoftext|>", "<|im_end|>", "<think>")
        }
        // Use grammar-constrained generation when grammar is provided
        val rawResult = if (options.grammar != null) {
            try {
                LlamaCppBridge.completeWithGrammar(
                    modelHandle, prompt, options.maxTokens,
                    options.temperature.toFloat(), 0.9f, stopTokens, options.grammar,
                )
            } catch (_: UnsatisfiedLinkError) {
                // NDK build without grammar support — fall back to unconstrained
                LlamaCppBridge.complete(
                    modelHandle, prompt, options.maxTokens,
                    options.temperature.toFloat(), 0.9f, stopTokens,
                )
            }
        } else {
            LlamaCppBridge.complete(
                modelHandle, prompt, options.maxTokens,
                options.temperature.toFloat(), 0.9f, stopTokens,
            )
        }

        // Strip any <think>...</think> blocks (Qwen3 thinking mode)
        val result = rawResult
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<think>[\\s\\S]*"), "")  // unclosed think tag
            .trim()

        // Approximate token counts from word count
        val words = result.split("\\s+".toRegex()).size
        return ChatResponse(
            content = result,
            promptTokens = prompt.length / 4,  // rough estimate
            completionTokens = words,
        )
    }

    actual fun start(modelPath: String) {
        if (_state.value == "running" || _state.value == "starting") return

        if (!isAvailable) {
            _state.value = "error"
            _errorMessage.value = "Native library (libwyrd-llama.so) not found. " +
                "Build with NDK or install prebuilt .so."
            return
        }

        _state.value = "starting"
        _errorMessage.value = null

        scope.launch(Dispatchers.IO) {
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
                val params = LlamaCppBridge.ModelParams(
                    contextSize = 2048,
                    threads = threads,
                    gpuLayers = 0,  // CPU only on most Android (Vulkan unreliable)
                    flashAttention = true,
                )
                modelHandle = LlamaCppBridge.loadModel(modelPath, params)
                loadedModelPath = modelPath
                _state.value = "running"
            } catch (e: Exception) {
                _state.value = "error"
                _errorMessage.value = "Failed to load model: ${e.message}"
                modelHandle = 0L
            }
        }
    }

    actual fun stop() {
        if (modelHandle != 0L) {
            try {
                LlamaCppBridge.unloadModel(modelHandle)
            } catch (_: Exception) {
                // Best effort
            }
            modelHandle = 0L
        }
        _state.value = "stopped"
        _errorMessage.value = null
    }

    private fun formatChatML(messages: List<ChatMessage>): String {
        val formatted = messages.joinToString("\n") { msg ->
            "<|im_start|>${msg.role}\n${msg.content}<|im_end|>"
        }
        // For Qwen3: start assistant turn with content immediately to bypass thinking mode.
        // If the model sees "assistant\n<think>" it enters thinking. If it sees
        // "assistant\n" followed by content prefix, it skips thinking and responds directly.
        return "$formatted\n<|im_start|>assistant\n"
    }

    /**
     * Format prompt with thinking mode explicitly disabled for Qwen3.
     * Adds /no_think to the last user message and prefills assistant response.
     */
    private fun formatChatMLNoThink(messages: List<ChatMessage>): String {
        val modified = messages.mapIndexed { i, msg ->
            if (i == messages.lastIndex && msg.role == "user") {
                ChatMessage(msg.role, msg.content + " /no_think")
            } else msg
        }
        return modified.joinToString("\n") { msg ->
            "<|im_start|>${msg.role}\n${msg.content}<|im_end|>"
        } + "\n<|im_start|>assistant\n"
    }

    /**
     * Format prompt for FunctionGemma (Gemma chat format with developer role + tool schemas).
     */
    private fun formatGemmaFunctionCall(messages: List<ChatMessage>, tools: String): String {
        val sb = StringBuilder()
        // Developer turn with tool definitions
        sb.append("<start_of_turn>developer\n")
        sb.append("You are a function calling model. Use the provided functions to respond.\n")
        sb.append("Available functions:\n$tools\n")
        sb.append("<end_of_turn>\n")
        // User/assistant turns
        for (msg in messages) {
            val role = if (msg.role == "system") "developer" else msg.role
            sb.append("<start_of_turn>$role\n${msg.content}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>assistant\n")
        return sb.toString()
    }

    /** Study function schemas for FunctionGemma */
    companion object {
        val STUDY_TOOLS = """
[{"type":"function","function":{"name":"journal_write","description":"Write a journal entry","parameters":{"type":"object","properties":{"content":{"type":"string","description":"The journal entry text"}},"required":["content"]}}},
{"type":"function","function":{"name":"journal_search","description":"Search journal entries","parameters":{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}}},
{"type":"function","function":{"name":"note_add","description":"Add a quick note","parameters":{"type":"object","properties":{"content":{"type":"string","description":"Note text"}},"required":["content"]}}},
{"type":"function","function":{"name":"note_search","description":"Search notes","parameters":{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}}},
{"type":"function","function":{"name":"respond","description":"Respond conversationally to the user","parameters":{"type":"object","properties":{"text":{"type":"string","description":"Response text"}},"required":["text"]}}}]
""".trim()
    }
}
