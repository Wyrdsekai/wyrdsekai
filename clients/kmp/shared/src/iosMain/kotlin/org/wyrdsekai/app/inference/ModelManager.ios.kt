@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.inference

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.*

/**
 * iOS ModelManager — downloads GGUF models to NSDocumentDirectory.
 *
 * Uses streaming download to avoid loading multi-GB GGUF files into memory.
 * Downloads to a temp file first, then atomically renames to the final path.
 */
actual class ModelManager actual constructor() {
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    actual val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val httpClient = HttpClient()

    actual suspend fun getDownloadedModels(): List<ModelInfo> {
        val dir = getModelsDirectory()
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) return emptyList()

        return ModelCatalog.models.filter { model ->
            fm.fileExistsAtPath("$dir/${model.filename}")
        }
    }

    actual suspend fun downloadModel(modelId: String, onProgress: (Float) -> Unit): String {
        val model = ModelCatalog.models.find { it.id == modelId }
            ?: error("Unknown model: $modelId")

        val dir = getModelsDirectory()
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)

        val targetPath = "$dir/${model.filename}"
        val tmpPath = "$dir/${model.filename}.download"

        // Remove any leftover temp file from a previous interrupted download
        if (fm.fileExistsAtPath(tmpPath)) {
            fm.removeItemAtPath(tmpPath, error = null)
        }

        // Stream download — write chunks to temp file instead of loading all into memory
        val outputHandle = run {
            fm.createFileAtPath(tmpPath, contents = null, attributes = null)
            NSFileHandle.fileHandleForWritingAtPath(tmpPath)
                ?: error("Failed to open temp file for writing: $tmpPath")
        }

        try {
            httpClient.prepareGet(model.url).execute { response ->
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val channel: ByteReadChannel = response.bodyAsChannel()
                var totalBytesRead = 0L

                val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead <= 0) continue

                    totalBytesRead += bytesRead

                    // Write chunk to file via NSData
                    buffer.usePinned { pinned ->
                        val nsData = NSData.create(
                            bytes = pinned.addressOf(0),
                            length = bytesRead.toULong(),
                        )
                        outputHandle.writeData(nsData)
                    }

                    // Report progress
                    val progress = if (contentLength > 0) {
                        (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    } else {
                        -1f // Indeterminate
                    }
                    _downloadProgress.value = _downloadProgress.value + (modelId to progress)
                    onProgress(progress)
                }
            }
        } catch (e: Exception) {
            // Clean up temp file on failure
            outputHandle.closeFile()
            fm.removeItemAtPath(tmpPath, error = null)
            _downloadProgress.value = _downloadProgress.value - modelId
            throw e
        }

        outputHandle.closeFile()

        // Atomic move: remove existing target (if any), rename temp to final
        if (fm.fileExistsAtPath(targetPath)) {
            fm.removeItemAtPath(targetPath, error = null)
        }
        fm.moveItemAtPath(tmpPath, toPath = targetPath, error = null)

        onProgress(1f)
        _downloadProgress.value = _downloadProgress.value - modelId
        return targetPath
    }

    actual suspend fun deleteModel(modelId: String) {
        val model = ModelCatalog.models.find { it.id == modelId } ?: return
        val path = "${getModelsDirectory()}/${model.filename}"
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual suspend fun getModelPath(modelId: String): String? {
        val model = ModelCatalog.models.find { it.id == modelId } ?: return null
        val path = "${getModelsDirectory()}/${model.filename}"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) path else null
    }

    actual fun getModelsDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        val docDir = paths.firstOrNull() as? String ?: "/tmp"
        return "$docDir/wyrdsekai/models"
    }

    companion object {
        /** 256 KB chunks — balances memory usage vs I/O overhead. */
        private const val DOWNLOAD_CHUNK_SIZE = 256 * 1024
    }
}
