package org.wyrdsekai.app.inference

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over local inference capability, allowing test doubles
 * in commonTest where [LlamaServerManager] (expect class) cannot be faked.
 */
interface LocalInferenceProvider {
    /** "stopped" | "starting" | "running" | "error" */
    val state: StateFlow<String>

    /** Run chat completion locally. */
    suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse
}

/**
 * Which of the companion's two models a request is for.
 *
 * The zone runs these as separate models with different jobs — DRIVE
 * (9B: skills, planning, tool emission, the ReAct loop) and VOICE
 * (4B + steering vectors: register, presence, polish). The phone ships one
 * model and it is voice-class, so "can this device do it locally?" has two
 * different answers and one backend priority cannot express both.
 *
 * Mirrors ModelRole in the RN client's inference/InferenceRouter.ts.
 *e.
 */
enum class ModelRole { VOICE, DRIVE }

/**
 * Routes inference requests between available backends.
 *
 * Supports:
 * - "local": on-device inference (JNI on Android, llama-server on Desktop)
 * - "remote": HTTP to an Ollama/OpenAI-compatible endpoint
 * - "none": no backend available
 *
 * Fallback chain: local (if available and model loaded) -> remote (if URL configured) -> error
 *
 * Future phases will add:
 * - "household": Between-routed household cluster inference
 */
class InferenceRouter(
    private val localProvider: LocalInferenceProvider,
    private val remoteClient: InferenceClient? = null,
    private val remoteBaseUrl: String? = null,
) {
    /**
     * Convenience constructor that wraps a [LlamaServerManager] as the local provider.
     * Preserves backwards compatibility with existing call sites.
     */
    constructor(
        llamaServerManager: LlamaServerManager,
        remoteClient: InferenceClient? = null,
        remoteBaseUrl: String? = null,
    ) : this(
        localProvider = LlamaServerManagerProvider(llamaServerManager),
        remoteClient = remoteClient,
        remoteBaseUrl = remoteBaseUrl,
    )

    /**
     * Returns the currently active backend (the one that would be used for
     * the next [complete] call).
     *
     * @return "local" if model is loaded, "remote" if remote URL configured, "none" otherwise
     */
    fun getActiveBackend(): String {
        return when {
            canInferLocally() -> "local"
            canInferRemotely() -> "remote"
            else -> "none"
        }
    }

    /** True if a local model is loaded and ready. */
    fun canInferLocally(): Boolean = localProvider.state.value == "running"

    /** True if a remote inference endpoint is configured. */
    fun canInferRemotely(): Boolean = remoteClient != null && !remoteBaseUrl.isNullOrBlank()

    /**
     * Sends a chat completion request to the best backend for [role].
     *
     * VOICE prefers the device: the on-device model IS voice-class, and register
     * and presence are what it is actually good at.
     *
     * DRIVE borrows first. Planning and tool emission want the 9B and the phone
     * does not have it, so when there is anything to borrow from, borrow. Local
     * stays as the LAST resort rather than being removed: with no remote
     * configured the phone is genuinely standalone, and a 4B attempting drive is
     * far better than refusing to think. The "truly standalone attempts drive"
     * rule falls out of the ordering — there is no separate mode test to keep in
     * sync.
     *
     * @param preferRemote Deprecated precursor of [role]; true is equivalent to
     *   DRIVE. Honoured so existing call sites keep working.
     * @throws IllegalStateException if no backend is available
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        options: CompletionOptions = CompletionOptions(),
        preferRemote: Boolean = false,
        role: ModelRole = ModelRole.VOICE,
    ): ChatResponse {
        val borrowFirst = preferRemote || role == ModelRole.DRIVE
        if (borrowFirst && canInferRemotely()) {
            return try {
                completeRemote(messages, options)
            } catch (e: Exception) {
                // Fall back to local if remote fails
                if (canInferLocally()) {
                    localProvider.completeLocal(messages, options)
                } else {
                    throw e
                }
            }
        }

        if (canInferLocally()) {
            return try {
                localProvider.completeLocal(messages, options)
            } catch (e: Exception) {
                // Fall back to remote if local fails
                if (canInferRemotely()) {
                    completeRemote(messages, options)
                } else {
                    throw e
                }
            }
        }

        if (canInferRemotely()) {
            return completeRemote(messages, options)
        }

        error("No inference backend available. Load a model or configure a remote endpoint.")
    }

    private suspend fun completeRemote(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse {
        val client = remoteClient
            ?: error("Remote inference client not configured")
        val url = remoteBaseUrl
            ?: error("Remote inference URL not configured")
        return client.complete(url, messages, options)
    }
}

/**
 * Adapter that wraps [LlamaServerManager] as a [LocalInferenceProvider].
 */
private class LlamaServerManagerProvider(
    private val manager: LlamaServerManager,
) : LocalInferenceProvider {
    override val state: StateFlow<String> get() = manager.state
    override suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse = manager.completeLocal(messages, options)
}
