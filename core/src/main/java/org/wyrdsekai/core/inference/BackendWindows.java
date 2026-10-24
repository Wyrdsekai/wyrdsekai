package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
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
    /** How many requests each backend serves at once (llama-server's {@code total_slots}); absent = unknown. */
    private static final Map<String, Integer> SLOTS = new ConcurrentHashMap<>();
    /** Which backend name answers at which address, so another part of the node can ask "is this ours?". */
    private static final Map<String, String> SERVED_AT = new ConcurrentHashMap<>();
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

    /** How many requests the backend serves at once; nothing is recorded for zero or less. */
    public static void learnedSlots(String backend, int slots, String how) {
        if (backend == null || slots <= 0) return;
        var before = SLOTS.put(backend, slots);
        if (before == null || before != slots) {
            log.info("Backend '{}' serves {} request(s) at once ({})", backend, slots, how);
        }
    }

    /** The known slot count, or 0. */
    public static int slots(String backend) {
        return backend == null ? 0 : SLOTS.getOrDefault(backend, 0);
    }

    /**
     * One request's fair share of the backend's context when every slot is busy: the window
     * divided by the slots. On a server whose slots share one pool ({@code --kv-unified}) a
     * request can be refused with "context size has been exceeded" and no number while the
     * other slots hold the rest; the share is what it can count on. Zero when unknown.
     */
    public static int shareOf(String backend) {
        int w = of(backend);
        int n = slots(backend);
        return w > 0 && n > 1 ? w / n : w;
    }

    /** Remember that {@code backend} answers at {@code url}. */
    public static void servedAt(String url, String backend) {
        var key = addressKey(url);
        if (key == null || backend == null) return;
        SERVED_AT.put(key, backend);
    }

    /** The backend the node's inference knows at this address, or null. */
    public static String nameServing(String url) {
        var key = addressKey(url);
        return key == null ? null : SERVED_AT.get(key);
    }

    /** {@code host:port} with every spelling of this machine folded to one; null when unreadable. */
    static String addressKey(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var u = URI.create(url.trim());
            var host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            if (host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]")
                    || host.equals("0.0.0.0") || host.equals("localhost")) {
                host = "localhost";
            }
            int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            return host + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the address is this machine's own. */
    public static boolean isThisMachine(String url) {
        var key = addressKey(url);
        return key != null && key.startsWith("localhost:");
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

    /** llama-server {@code GET /props}: {@code total_slots}, how many requests it serves at once. */
    static int slotsFromLlamaProps(String json) {
        try {
            var root = MAPPER.readTree(json);
            return positive(root.path("total_slots").asInt(0));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Window and slot count from one {@code /props} body, and the address it came from. */
    public static void learnedFromLlamaProps(String backend, String url, String json, String how) {
        learned(backend, windowFromLlamaProps(json), how);
        learnedSlots(backend, slotsFromLlamaProps(json), how);
        servedAt(url, backend);
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

    /** Tests: forget every window, slot count and address. */
    static void resetForTests() { KNOWN.clear(); SLOTS.clear(); SERVED_AT.clear(); }
}
