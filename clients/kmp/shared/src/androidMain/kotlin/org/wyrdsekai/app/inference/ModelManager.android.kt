package org.wyrdsekai.app.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

actual class ModelManager actual constructor() {
    // Android: use app's files directory (set via system property) or fallback
    private val modelsDir = File(
        System.getProperty("wyrdsekai.models.dir") ?: "/data/local/tmp/wyrdsekai/models"
    ).also { it.mkdirs() }

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    actual val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    actual suspend fun getDownloadedModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        ModelCatalog.models.filter { File(modelsDir, it.filename).exists() }
    }

    actual suspend fun downloadModel(
        modelId: String,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val model = ModelCatalog.findById(modelId)
            ?: error("Unknown model: $modelId")
        val targetFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        try {
            val url = URL(model.url)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: model.size
            var downloaded = 0L

            conn.inputStream.buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                        onProgress(progress)
                        _downloadProgress.value = _downloadProgress.value + (modelId to progress)
                    }
                }
            }

            tempFile.renameTo(targetFile)
            _downloadProgress.value = _downloadProgress.value - modelId
            targetFile.absolutePath
        } catch (e: Exception) {
            tempFile.delete()
            _downloadProgress.value = _downloadProgress.value - modelId
            throw e
        }
    }

    actual suspend fun deleteModel(modelId: String): Unit = withContext(Dispatchers.IO) {
        val model = ModelCatalog.findById(modelId) ?: return@withContext
        File(modelsDir, model.filename).delete()
    }

    actual suspend fun getModelPath(modelId: String): String? = withContext(Dispatchers.IO) {
        val model = ModelCatalog.findById(modelId) ?: return@withContext null
        val file = File(modelsDir, model.filename)
        if (file.exists()) file.absolutePath else null
    }

    actual fun getModelsDirectory(): String = modelsDir.absolutePath
}
