package org.wyrdsekai.daemon.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.daemon.common.DaemonConfig;
import org.wyrdsekai.daemon.common.InferenceRequest;
import org.wyrdsekai.daemon.common.InferenceResponse;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the local inference backend — either Ollama (preferred) or
 * llama-server (subprocess).
 *
 * Desktop strategy:
 * 1. If Ollama is running, use it (no subprocess needed)
 * 2. If not, spawn llama-server with the configured model
 *
 * Both serve OpenAI-compatible /v1/chat/completions.
 */
public final class InferenceProcess {

    private static final Logger log = LoggerFactory.getLogger(InferenceProcess.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DaemonConfig config;
    private final HttpClient httpClient;

    private Process llamaProcess;
    private String baseUrl;
    private String backend;

    public InferenceProcess(DaemonConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Start or detect the inference backend.
     */
    public void start() throws IOException {
        // 1. Check if Ollama is already running
        if (isOllamaRunning()) {
            baseUrl = "http://127.0.0.1:11434";
            backend = "ollama";
            log.info("Detected running Ollama at {}", baseUrl);
            return;
        }

        // 2. Check for llama-server binary
        var llamaBinary = findLlamaServer();
        if (llamaBinary == null) {
            throw new IOException(
                "No inference backend found. Install Ollama or llama-server.");
        }

        // 3. Start llama-server subprocess
        var modelPath = config.modelPath();
        if (modelPath.isEmpty()) {
            throw new IOException("No model path configured. Set --model-path or model.path preference.");
        }

        var port = config.inferencePort();
        var cmd = new ArrayList<>(List.of(
            llamaBinary,
            "--model", modelPath,
            "--port", String.valueOf(port),
            "--ctx-size", String.valueOf(config.contextSize()),
            "--threads", String.valueOf(config.maxThreads())
        ));

        if (config.gpuLayers() > 0) {
            cmd.addAll(List.of("--n-gpu-layers", String.valueOf(config.gpuLayers())));
        }
        if (config.flashAttention()) {
            cmd.add("--flash-attn");
        }

        log.info("Starting llama-server: {}", String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true);
        llamaProcess = pb.start();

        // Drain stdout/stderr in background
        Thread.ofVirtual().name("llama-stdout").start(() -> {
            try (var reader = llamaProcess.inputReader()) {
                reader.lines().forEach(line -> log.debug("[llama] {}", line));
            } catch (IOException ignored) {}
        });

        baseUrl = "http://127.0.0.1:" + port;
        backend = "llama-server";
        log.info("llama-server started on port {}", port);
    }

    /**
     * Wait for the inference backend to become healthy.
     */
    public boolean waitForHealth(int timeoutSeconds) {
        var healthUrl = backend.equals("ollama")
            ? baseUrl + "/api/tags"
            : baseUrl + "/health";

        log.info("Waiting for {} health at {} (timeout={}s)", backend, healthUrl, timeoutSeconds);
        var deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    log.info("{} healthy", backend);
                    return true;
                }
            } catch (Exception ignored) {}

            // Check if subprocess died
            if (llamaProcess != null && !llamaProcess.isAlive()) {
                log.error("llama-server process exited with code {}", llamaProcess.exitValue());
                return false;
            }

            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Forward an inference request to the local backend and return the response.
     */
    public InferenceResponse forwardRequest(InferenceRequest request) {
        try {
            var requestJson = buildChatCompletionRequest(request);
            var url = baseUrl + "/v1/chat/completions";

            var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

            var httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() != 200) {
                return InferenceResponse.error(request.requestId(),
                    "HTTP " + httpResp.statusCode() + ": " + httpResp.body());
            }

            return parseChatCompletionResponse(request.requestId(), httpResp.body());

        } catch (Exception e) {
            return InferenceResponse.error(request.requestId(), e.getMessage());
        }
    }

    /**
     * Stop the inference backend subprocess (if we started one).
     */
    public void stop() {
        if (llamaProcess != null && llamaProcess.isAlive()) {
            log.info("Stopping llama-server");
            llamaProcess.destroy();
            try {
                if (!llamaProcess.waitFor(10, TimeUnit.SECONDS)) {
                    llamaProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                llamaProcess.destroyForcibly();
            }
            llamaProcess = null;
        }
    }

    public String backendName() { return backend; }
    public String baseUrl() { return baseUrl; }

    // --- Private helpers ---

    private boolean isOllamaRunning() {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:11434/api/tags"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String findLlamaServer() {
        var isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        // Check PATH first
        var binary = isWindows ? "llama-server.exe" : "llama-server";
        var findCmd = isWindows
            ? new String[]{"where.exe", binary}
            : new String[]{"which", binary};

        try {
            var proc = new ProcessBuilder(findCmd)
                .redirectErrorStream(true)
                .start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.waitFor() == 0 && !output.isEmpty()) {
                return output.lines().findFirst().orElse(null);
            }
        } catch (Exception ignored) {}

        // Check common locations
        var candidates = isWindows
            ? List.of(
                System.getenv("LOCALAPPDATA") + "\\llama.cpp\\llama-server.exe",
                System.getenv("APPDATA") + "\\wyrdsekai\\bin\\llama-server.exe")
            : List.of(
                "/usr/local/bin/llama-server",
                "/opt/homebrew/bin/llama-server",
                System.getProperty("user.home") + "/.local/bin/llama-server");

        for (var path : candidates) {
            if (path != null && new File(path).canExecute()) {
                return path;
            }
        }

        return null;
    }

    private String buildChatCompletionRequest(InferenceRequest request) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        if (request.model() != null && !request.model().isEmpty()) {
            root.put("model", request.model());
        }
        root.put("max_tokens", request.maxTokens());
        root.put("temperature", request.temperature());

        ArrayNode messages = root.putArray("messages");
        for (var msg : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
        }

        ArrayNode stop = root.putArray("stop");
        stop.add("</s>");
        stop.add("<|endoftext|>");
        stop.add("<|im_end|>");

        return MAPPER.writeValueAsString(root);
    }

    private InferenceResponse parseChatCompletionResponse(String requestId, String body)
            throws Exception {
        var tree = MAPPER.readTree(body);
        var choices = tree.get("choices");
        if (choices == null || choices.isEmpty()) {
            return InferenceResponse.error(requestId, "No choices in response");
        }

        var content = choices.get(0).path("message").path("content").asText("");
        var usage = tree.path("usage");
        var promptTokens = usage.path("prompt_tokens").asInt(0);
        var completionTokens = usage.path("completion_tokens").asInt(0);

        return InferenceResponse.ok(requestId, content, promptTokens, completionTokens);
    }
}
