package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.wyrdsekai.app.inference.*
import org.wyrdsekai.app.platform.AppProps

/**
 * UI-facing view model for the local inference subsystem.
 *
 * Exposes:
 * - Server lifecycle (start/stop, state, errors)
 * - Model management (download, delete, list)
 * - Active model tracking
 * - Inference status
 *
 * The UI should hide inference controls when [isAvailable] is false
 * (Android/iOS stubs).
 */
class InferenceViewModel(
    private val scope: CoroutineScope,
    private val llamaServerManager: LlamaServerManager,
    private val modelManager: ModelManager,
    private val inferenceRouter: InferenceRouter,
) {
    // -- Server state --

    val serverState: StateFlow<String> = llamaServerManager.state
    val serverError: StateFlow<String?> = llamaServerManager.errorMessage
    val isAvailable: Boolean = llamaServerManager.isAvailable

    // -- Download tracking --

    val downloadProgress: StateFlow<Map<String, Float>> = modelManager.downloadProgress

    // -- Active model --

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    // -- Inference status --

    private val _inferenceRunning = MutableStateFlow(false)
    val inferenceRunning: StateFlow<Boolean> = _inferenceRunning.asStateFlow()

    // -- Downloaded models --

    private val _downloadedModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val downloadedModels: StateFlow<List<ModelInfo>> = _downloadedModels.asStateFlow()

    /** Returns the currently active inference backend: "local", or "none". */
    fun getActiveBackend(): String = inferenceRouter.getActiveBackend()

    /**
     * Loads a previously downloaded model into llama-server.
     *
     * The model must already be on disk (see [downloadModel]).  Starting
     * the server is asynchronous; observe [serverState] for progress.
     */
    fun loadModel(modelId: String) {
        scope.launch {
            _modelLoading.value = true
            try {
                val path = modelManager.getModelPath(modelId)
                    ?: error("Model not downloaded: $modelId")
                llamaServerManager.start(path)
                _activeModelId.value = modelId

                // Set model tier so PhoneNode can activate Study command mode for tiny models
                val modelInfo = org.wyrdsekai.app.inference.ModelCatalog.findById(modelId)
                val tier = modelInfo?.tier ?: "phone"
                AppProps.set("wyrdsekai.model.tier", tier)
            } catch (_: Exception) {
                // Error will be reflected in llamaServerManager.errorMessage
            } finally {
                _modelLoading.value = false
            }
        }
    }

    /** Stops llama-server and clears the active model. */
    fun unloadModel() {
        llamaServerManager.stop()
        _activeModelId.value = null
    }

    /**
     * Downloads a model from HuggingFace.
     *
     * Progress is tracked via [downloadProgress].
     */
    fun downloadModel(modelId: String) {
        scope.launch {
            try {
                modelManager.downloadModel(modelId) { /* progress tracked via downloadProgress flow */ }
            } catch (_: Exception) {
                // Progress will be cleared on error
            }
        }
    }

    /** Deletes a downloaded model, unloading it first if active. */
    fun deleteModel(modelId: String) {
        scope.launch {
            if (_activeModelId.value == modelId) {
                unloadModel()
            }
            modelManager.deleteModel(modelId)
            refreshDownloadedModels()
        }
    }

    /** Refreshes the [downloadedModels] flow from disk. */
    fun refreshDownloadedModels() {
        scope.launch {
            _downloadedModels.value = modelManager.getDownloadedModels()
        }
    }

    // -- Smoke test --

    private val _smokeTestResult = MutableStateFlow<String?>(null)
    val smokeTestResult: StateFlow<String?> = _smokeTestResult.asStateFlow()

    private val _smokeTestRunning = MutableStateFlow(false)
    val smokeTestRunning: StateFlow<Boolean> = _smokeTestRunning.asStateFlow()

    /** Send a test prompt to the loaded model and store the response. */
    fun runSmokeTest() {
        scope.launch {
            _smokeTestRunning.value = true
            _smokeTestResult.value = null
            try {
                val messages = listOf(
                    ChatMessage("system", "You are a helpful assistant. Respond briefly. /no_think"),
                    ChatMessage("user", "Hello! Say one sentence about yourself."),
                )
                val response = llamaServerManager.completeLocal(
                    messages,
                    CompletionOptions(maxTokens = 128, temperature = 0.7),
                )
                // Strip Qwen3 <think>...</think> tags if present
                val clean = response.content
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .replace(Regex("<think>[\\s\\S]*"), "")
                    .trim()
                _smokeTestResult.value = clean
            } catch (e: Exception) {
                _smokeTestResult.value = "Error: ${e.message}"
            } finally {
                _smokeTestRunning.value = false
            }
        }
    }

    /** Clear the smoke test result dialog. */
    fun clearSmokeTestResult() {
        _smokeTestResult.value = null
    }
}
