package org.wyrdsekai.app.inference

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop implementation of [LlamaServerManager].
 *
 * Spawns llama-server as a subprocess using ProcessBuilder, polls
 * `/health` until the server is ready, then watches for unexpected exit.
 * Follows the same pattern as [org.wyrdsekai.app.node.EmbeddedNode].
 */
actual class LlamaServerManager actual constructor(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow("stopped")
    actual val state: StateFlow<String> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    actual val isAvailable: Boolean = true  // Desktop can always run llama-server
    actual val port: Int = 8080

    private var process: Process? = null
    private var watchJob: Job? = null

    actual fun start(modelPath: String) {
        if (_state.value == "running" || _state.value == "starting") return

        _state.value = "starting"
        _errorMessage.value = null

        scope.launch(Dispatchers.IO) {
            try {
                val executable = findLlamaServer()
                    ?: throw IllegalStateException(
                        "llama-server not found. Install via: " +
                            "brew install llama.cpp (macOS) or download from GitHub."
                    )

                val cmd = listOf(
                    executable,
                    "--model", modelPath,
                    "--port", port.toString(),
                    "--ctx-size", "2048",
                    "--threads", Runtime.getRuntime().availableProcessors()
                        .coerceAtMost(8).toString(),
                    "--flash-attn",
                )

                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc

                // Watch stdout in background
                scope.launch(Dispatchers.IO) {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        println("[llama-server] $line")
                    }
                }

                // Health check loop -- 30 seconds max
                val healthy = pollHealth(maxAttempts = 60, intervalMs = 500)
                if (healthy) {
                    _state.value = "running"
                    watchProcess(proc)
                } else {
                    proc.destroyForcibly()
                    process = null
                    _state.value = "error"
                    _errorMessage.value = "llama-server failed to start within 30s"
                }
            } catch (e: Exception) {
                _state.value = "error"
                _errorMessage.value = e.message
            }
        }
    }

    private val inferenceClient = InferenceClient()

    actual suspend fun completeLocal(
        messages: List<ChatMessage>,
        options: CompletionOptions,
    ): ChatResponse {
        return inferenceClient.complete(
            baseUrl = "http://localhost:$port",
            messages = messages,
            options = options,
        )
    }

    actual fun stop() {
        watchJob?.cancel()
        watchJob = null
        process?.let { proc ->
            proc.destroyForcibly()
            proc.waitFor()
        }
        process = null
        _state.value = "stopped"
        _errorMessage.value = null
    }

    private fun findLlamaServer(): String? {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

        // Platform-specific search paths
        val candidates = if (isWindows) {
            listOf(
                "llama-server",  // On PATH
                "${System.getenv("LOCALAPPDATA") ?: ""}\\llama.cpp\\llama-server.exe",
                "${System.getenv("WYRDSEKAI_DATA_DIR")
                    ?: "${System.getenv("APPDATA") ?: System.getProperty("user.home")}\\wyrdsekai"
                }\\bin\\llama-server.exe",
            )
        } else {
            listOf(
                "llama-server",  // On PATH
                "/usr/local/bin/llama-server",
                "/opt/homebrew/bin/llama-server",
                "${System.getProperty("user.home")}/.local/bin/llama-server",
                "${System.getenv("WYRDSEKAI_DATA_DIR")
                    ?: "${System.getProperty("user.home")}/.wyrdsekai"}/bin/llama-server",
            )
        }

        // Binary discovery: where.exe (Windows) or which (Unix)
        val findCmd = if (isWindows) "where.exe" else "which"

        for (candidate in candidates) {
            try {
                val result = ProcessBuilder(findCmd, candidate)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                if (result == 0) return candidate
            } catch (_: Exception) {
                // findCmd not available or candidate not found
            }

            // Check if the file exists and is executable
            if (File(candidate).canExecute()) return candidate
        }
        return null
    }

    @Suppress("SameParameterValue")
    private suspend fun pollHealth(maxAttempts: Int, intervalMs: Long): Boolean {
        repeat(maxAttempts) {
            if (process?.isAlive != true) return false
            try {
                val conn = URL("http://localhost:$port/health")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {
                // Not ready yet
            }
            delay(intervalMs)
        }
        return false
    }

    private fun watchProcess(proc: Process) {
        watchJob = scope.launch(Dispatchers.IO) {
            proc.waitFor()
            if (_state.value == "running") {
                _state.value = "error"
                _errorMessage.value =
                    "llama-server exited unexpectedly (code ${proc.exitValue()})"
            }
        }
    }
}
