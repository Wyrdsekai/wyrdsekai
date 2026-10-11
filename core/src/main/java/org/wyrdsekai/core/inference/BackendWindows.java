package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The context window each backend is known to run with, in tokens, by backend name. Learned at
 * boot from what the server says about itself (llama-server's {@code /props}, Ollama's
 * {@code /api/ps} and {@code /api/show}) and again from any overflow error it answers with. Zero
 * means unknown.
 *
 * <p>Until 0.5.6 no backend had a window at all in the router's eyes: a prompt assembled for a
 * 16K drive model fell back, unchanged, onto whatever healthy backend came next, and a small
 * Ollama model answered 400 to every full turn (a household's report, 2026-10-05).</p>
 */
public final class BackendWindows {

    private static final Logger log = LoggerFactory.getLogger(BackendWindows.class);
    private static final Map<String, Integer> KNOWN = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUM_CTX = Pattern.compile("(?m)^\\s*num_ctx\\s+(\\d{3,7})\\s*$");

    private BackendWindows() {}

    /** A window learned for a backend; nothing is recorded for zero or less. */
    public static void learned(String backend, int window, String how) {
        if (backend == null || window <= 0) return;
        var before = KNOWN.put(backend, window);
        if (before == null || before != window) {
            log.info("Backend '{}' runs with a {}-token context window ({})", backend, window, how);
        }
    }

    /** The known window, or 0. */
    public static int of(String backend) {
        return backend == null ? 0 : KNOWN.getOrDefault(backend, 0);
    }

    /** True when the backend's window is known and smaller than what this request needs. */
    public static boolean tooSmall(String backend, int neededTokens) {
        int w = of(backend);
        return w > 0 && neededTokens > w;
    }

    /** llama-server {@code GET /props}: {@code default_generation_settings.n_ctx}, the per-slot window. */
    static int windowFromLlamaProps(String json) {
        try {
            var root = MAPPER.readTree(json);
            return positive(root.path("default_generation_settings").path("n_ctx").asInt(0));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Ollama {@code GET /api/ps}: every loaded model reports {@code context_length}, the window it
     * is actually running with. The smallest of them is the one to trust.
     */
    static int windowFromOllamaPs(String json) {
        try {
            var root = MAPPER.readTree(json);
            int best = 0;
            for (JsonNode m : root.path("models")) {
                int c = m.path("context_length").asInt(0);
                if (c > 0 && (best == 0 || c < best)) best = c;
            }
            return best;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Ollama {@code POST /api/show}: a {@code num_ctx} in the model's own parameters is the window
     * it will be loaded with. The model's {@code context_length} in {@code model_info} is only what
     * it could take, not what Ollama runs it at, so it is not read.
     */
    static int windowFromOllamaShow(String json) {
        try {
            var root = MAPPER.readTree(json);
            var params = root.path("parameters").asText("");
            var m = NUM_CTX.matcher(params);
            return m.find() ? positive(Integer.parseInt(m.group(1))) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int positive(int n) { return Math.max(0, n); }

    /** Tests: forget every window. */
    static void resetForTests() { KNOWN.clear(); }
}
