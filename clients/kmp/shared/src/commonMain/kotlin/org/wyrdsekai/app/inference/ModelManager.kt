package org.wyrdsekai.app.inference

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific model storage manager.
 *
 * Handles downloading GGUF models from HuggingFace, tracking download
 * progress, and managing the local models directory.
 *
 * On desktop: downloads to ~/.wyrdsekai/models/
 * On Android: downloads to app-specific files directory
 * On iOS: stub (no local inference yet)
 */
expect class ModelManager() {
    /** Live download progress per model ID (0.0 to 1.0). */
    val downloadProgress: StateFlow<Map<String, Float>>

    /** Returns catalog entries for models already present on disk. */
    suspend fun getDownloadedModels(): List<ModelInfo>

    /**
     * Downloads a model to the local models directory.
     *
     * @param modelId catalog ID from [ModelCatalog]
     * @param onProgress called with 0.0-1.0 as bytes arrive
     * @return absolute path to the downloaded GGUF file
     */
    suspend fun downloadModel(modelId: String, onProgress: (Float) -> Unit): String

    /** Deletes a previously downloaded model from disk. */
    suspend fun deleteModel(modelId: String)

    /** Returns the absolute path to a model if it exists on disk, null otherwise. */
    suspend fun getModelPath(modelId: String): String?

    /** Returns the absolute path to the models directory. */
    fun getModelsDirectory(): String
}
