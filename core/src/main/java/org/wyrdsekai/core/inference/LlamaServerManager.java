package org.wyrdsekai.core.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.core.gpu.GpuProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages llama-server as a child process.
 * Auto-detects hardware capabilities for model selection.
 *
 * llama-server (from llama.cpp) exposes an OpenAI-compatible API
 * at http://localhost:{port}/v1/chat/completions.
 */
public final class LlamaServerManager {

    private static final Logger log = LoggerFactory.getLogger(LlamaServerManager.class);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(30);
    private static final int HEALTH_CHECK_INTERVAL_MS = 500;

    private final String executable;
    private final String modelPath;
    private final int port;
    private final int contextSize;
    private final int gpuLayers;

    private Process process;
    private InferenceClient client;

    /**
     * @param executable  path to llama-server binary (or "llama-server" if on PATH)
     * @param modelPath   path to GGUF model file
     * @param port        HTTP port for the server
     * @param contextSize context window size
     * @param gpuLayers   number of layers to offload to GPU (0 = CPU only)
     */
    public LlamaServerManager(String executable, String modelPath, int port,
                               int contextSize, int gpuLayers) {
        this.executable = executable;
        this.modelPath = modelPath;
        this.port = port;
        this.contextSize = contextSize;
        this.gpuLayers = gpuLayers;
    }

    /**
     * Start llama-server and wait for it to become healthy.
     *
     * @return InferenceClient connected to the server
     * @throws IOException if the server fails to start
     */
    public InferenceClient start() throws IOException {
        if (process != null && process.isAlive()) {
            log.info("llama-server already running (pid={})", process.pid());
            return client;
        }

        // The check above only sees THIS instance's child. Backend construction
        // is static and unsynchronised, so a second call builds a second manager
        // whose `process` is null — it then spawned a duplicate llama-server that
        // lost the race for the port and died with "couldn't bind HTTP server
        // socket", one second after the first one came up (home-server, 2026-07-29).
        // The survivor served, so nothing downstream noticed; the only trace was
        // a stray "main: exiting due to HTTP server error" in the journal.
        //
        // Adopt a healthy server already on the port instead of racing it. This
        // also covers the operator who started llama-server by hand.
        var existing = new InferenceClient("http://127.0.0.1:" + port);
        try {
            if (Boolean.TRUE.equals(existing.healthCheck().get(3, TimeUnit.SECONDS))) {
                log.info("llama-server already listening on port {} — adopting it "
                    + "rather than starting a second one", port);
                client = existing;
                return client;
            }
        } catch (Exception ignored) {
            // Nothing healthy there — fall through and start our own.
        }

        // Validate model file exists
        if (!Files.isRegularFile(Path.of(modelPath))) {
            throw new IOException("Model file not found: " + modelPath);
        }

        var cmd = new ArrayList<String>();
        cmd.add(executable);
        cmd.add("--model"); cmd.add(modelPath);
        cmd.add("--port"); cmd.add(String.valueOf(port));
        if (gpuLayers > 0) {
            cmd.add("--n-gpu-layers"); cmd.add(String.valueOf(gpuLayers));
        }
        // Calculate parallel slots based on available VRAM
        int parallel = calculateParallel();
        cmd.add("--parallel"); cmd.add(String.valueOf(parallel));

        // llama.cpp's --ctx-size is the TOTAL context, which the server divides
        // evenly across --parallel slots: n_ctx_slot = n_ctx / n_parallel. Every
        // other part of this class treats `contextSize` as the PER-SLOT window —
        // GpuProbe.suggestParallelSlots budgets "~1.5GB per slot at 16K context"
        // and derives the slot count from it — so passing it through raw silently
        // divided the real window by the slot count.
        //
        // On a fresh packaged install that meant `--ctx-size 4096 --parallel 8`
        // → 512 tokens per slot, against a ~4000-token companion prompt. Every
        // turn came back HTTP 400 "exceeds the available context size (512
        // tokens)" and the companion could not answer at all. The server was
        // otherwise healthy, so nothing else looked wrong (home-server, 2026-07-29).
        //
        // Multiply so each slot actually gets `contextSize`. This is exactly the
        // total GpuProbe already sized VRAM for, so it does not overcommit.
        int totalContext = Math.multiplyExact(contextSize, parallel);
        cmd.add("--ctx-size"); cmd.add(String.valueOf(totalContext));
        log.info("llama-server context: {} per slot x {} slot(s) = --ctx-size {}",
            contextSize, parallel, totalContext);

        log.info("Starting llama-server: {}", String.join(" ", cmd));

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .inheritIO();
        process = pb.start();

        // Create client
        client = new InferenceClient("http://127.0.0.1:" + port);

        // Wait for health check
        var deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("llama-server exited with code " + process.exitValue());
            }
            try {
                var healthy = client.healthCheck().get(3, TimeUnit.SECONDS);
                if (healthy) {
                    log.info("llama-server healthy on port {} (pid={})", port, process.pid());
                    return client;
                }
            } catch (Exception ignored) {
                // Server not ready yet
            }
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for llama-server", e);
            }
        }

        // Timeout — kill and fail
        process.destroyForcibly();
        throw new IOException("llama-server failed to become healthy within " +
            HEALTH_CHECK_TIMEOUT.toSeconds() + "s");
    }

    /**
     * Stop the llama-server process.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping llama-server (pid={})", process.pid());
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    /**
     * Check if the server process is running.
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Get the InferenceClient (null if not started).
     */
    public InferenceClient getClient() {
        return client;
    }

    public int getPort() {
        return port;
    }

    // --- Hardware detection ---

    /**
     * Calculate safe --parallel value using GpuProbe.
     * Falls back to 1 if no GPU detected or model path not parseable.
     */
    private int calculateParallel() {
        List<GpuProbe.GpuInfo> gpus = GpuProbe.detect();
        if (gpus.isEmpty()) {
            log.info("No GPU detected, using --parallel 1");
            return 1;
        }

        double paramBillions = estimateParamsFromPath(modelPath);
        String quant = estimateQuantFromPath(modelPath);
        long freeVram = gpus.getFirst().freeVramMB();

        int parallel = GpuProbe.suggestParallelSlots(paramBillions, quant, freeVram, contextSize);
        log.info("GpuProbe suggests --parallel {} (model ~{}B {}, free VRAM {}MB, ctx {})",
            parallel, paramBillions, quant, freeVram, contextSize);
        return parallel;
    }

    /** Best-effort parameter count from model filename. */
    static double estimateParamsFromPath(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("70b") || lower.contains("72b")) return 70.0;
        if (lower.contains("32b") || lower.contains("34b")) return 32.0;
        if (lower.contains("14b")) return 14.0;
        if (lower.contains("7b") || lower.contains("8b")) return 7.0;
        if (lower.contains("4b")) return 4.0;
        if (lower.contains("3b")) return 3.0;
        if (lower.contains("1.5b") || lower.contains("1b")) return 1.5;
        if (lower.contains("0.5b")) return 0.5;
        return 7.0; // conservative default
    }

    /** Best-effort quantization from model filename. */
    static String estimateQuantFromPath(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("q4_k_m") || lower.contains("q4_0") || lower.contains("q4")) return "q4";
        if (lower.contains("q8") || lower.contains("q8_0")) return "q8";
        if (lower.contains("fp16") || lower.contains("f16")) return "fp16";
        if (lower.contains("fp32") || lower.contains("f32")) return "fp32";
        return "q4"; // most common GGUF quantization
    }

    /**
     * Detect recommended model tier based on available hardware.
     * Uses GpuProbe for GPU detection.
     *
     * @return "large" (7B+, GPU available), "medium" (1.5B-3B, CPU with decent RAM),
     *         "small" (0.5B-1B, low-end devices)
     */
    public static String detectModelTier() {
        var totalMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        var availProcessors = Runtime.getRuntime().availableProcessors();

        List<GpuProbe.GpuInfo> gpus = GpuProbe.detect();

        if (!gpus.isEmpty()) {
            return "large"; // 7B+ with GPU offload
        } else if (totalMemMb >= 4096 && availProcessors >= 4) {
            return "medium"; // 1.5B-3B on CPU
        } else {
            return "small"; // 0.5B on constrained hardware
        }
    }
}
