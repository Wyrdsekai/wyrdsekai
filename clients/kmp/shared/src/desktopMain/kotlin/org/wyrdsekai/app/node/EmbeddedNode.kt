package org.wyrdsekai.app.node

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages a Wyrdsekai server subprocess on desktop.
 *
 * Launches the server from its Gradle installDist output, polls /health
 * until it responds 200, then watches for unexpected process exit.
 */
class EmbeddedNode(private val scope: CoroutineScope) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val port: Int = 7070
    private var process: Process? = null
    private var watchJob: Job? = null

    fun start() {
        if (_state.value == State.RUNNING || _state.value == State.STARTING) return
        _state.value = State.STARTING
        _errorMessage.value = null

        scope.launch(Dispatchers.IO) {
            try {
                val serverDir = findServerDist()
                if (serverDir == null) {
                    _errorMessage.value =
                        "Server distribution not found. Run: ./gradlew :server:installDist"
                    _state.value = State.ERROR
                    return@launch
                }

                val javaExe = findJava()
                if (javaExe == null) {
                    _errorMessage.value = "Java not found in PATH or JAVA_HOME"
                    _state.value = State.ERROR
                    return@launch
                }

                // Build classpath from server/lib/*.jar
                val libDir = File(serverDir, "lib")
                val classpath = libDir.listFiles()
                    ?.filter { it.extension == "jar" }
                    ?.joinToString(File.pathSeparator) { it.absolutePath }
                    ?: run {
                        _errorMessage.value = "No JARs found in ${libDir.absolutePath}"
                        _state.value = State.ERROR
                        return@launch
                    }

                val pb = ProcessBuilder(
                    javaExe,
                    "-Xmx512m",
                    "-XX:+UseCompactObjectHeaders",
                    "-cp", classpath,
                    "org.wyrdsekai.server.Main",
                    "--port", port.toString()
                )
                pb.redirectErrorStream(true)
                pb.environment()["WYRDSEKAI_DATA_DIR"] =
                    File(System.getProperty("user.home"), ".wyrdsekai").absolutePath

                process = pb.start()

                // Redirect output to stderr (visible in console)
                watchJob = scope.launch(Dispatchers.IO) {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            System.err.println("[node] $line")
                        }
                    }
                }

                // Health check -- poll until server is ready (30 seconds max)
                val healthy = waitForHealth(maxAttempts = 60, intervalMs = 500)
                if (healthy) {
                    _state.value = State.RUNNING
                } else {
                    _errorMessage.value = "Server failed to start within 30 seconds"
                    stop()
                    _state.value = State.ERROR
                }

                // Watch for unexpected exit
                scope.launch(Dispatchers.IO) {
                    process?.waitFor()
                    if (_state.value == State.RUNNING) {
                        _errorMessage.value = "Server process exited unexpectedly"
                        _state.value = State.ERROR
                        process = null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to start server"
                _state.value = State.ERROR
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        process?.let { p ->
            p.destroyForcibly()
            p.waitFor()
        }
        process = null
        if (_state.value != State.ERROR) {
            _state.value = State.STOPPED
        }
    }

    fun restart() {
        stop()
        _state.value = State.STOPPED
        start()
    }

    private fun findServerDist(): File? {
        // Dev mode: look relative to KMP project root
        val candidates = listOf(
            // Relative to clients/kmp/
            File("../../server/build/install/server"),
            // Relative to working directory
            File("server/build/install/server"),
            // Absolute fallback
            File(System.getProperty("user.home"), "src/wyrdsekai/server/build/install/server"),
        )
        return candidates.firstOrNull { it.exists() && File(it, "lib").exists() }
    }

    private fun findJava(): String? {
        // Check JAVA_HOME first
        System.getenv("JAVA_HOME")?.let { javaHome ->
            val javaBin = File(javaHome, "bin/java")
            if (javaBin.exists()) return javaBin.absolutePath
        }
        // Fall back to PATH
        return try {
            val result = ProcessBuilder("which", "java").start()
            val path = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            if (result.exitValue() == 0 && path.isNotEmpty()) path else null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("SameParameterValue")
    private suspend fun waitForHealth(maxAttempts: Int, intervalMs: Long): Boolean {
        repeat(maxAttempts) {
            if (process?.isAlive != true) return false
            try {
                val url = URL("http://localhost:$port/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
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
}
