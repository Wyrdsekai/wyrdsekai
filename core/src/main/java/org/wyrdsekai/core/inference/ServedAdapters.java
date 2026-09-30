package org.wyrdsekai.core.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How many LoRA adapters the local model server has loaded, from its {@code /lora-adapters}
 * endpoint. A request that names an adapter id the server has not loaded is rejected, so the
 * single-model profile asks before it raises the night's adapter (id 1) on a conversation turn.
 *
 * <p>The answer is refreshed in the background at most once a minute and the last known value is
 * returned immediately; callers are on an actor thread and must not wait on the network. Unknown
 * is reported as {@code -1}, and callers then send no adapter field at all — the server's own
 * defaults apply (honesty adapter on, night's adapter off).
 */
public final class ServedAdapters {

    private static final Logger log = LoggerFactory.getLogger(ServedAdapters.class);
    private static final Duration MAX_AGE = Duration.ofSeconds(60);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile int count = -1;
    /** Adapter id → the path the server loaded it from; empty until the first answer. */
    private static volatile Map<Integer, String> paths = Map.of();
    private static volatile long checkedAtMs = 0;

    private ServedAdapters() {}

    /** Last known adapter count at {@code baseUrl}, or -1 when not yet known. Never blocks. */
    public static int count(String baseUrl) {
        if (baseUrl != null && System.currentTimeMillis() - checkedAtMs > MAX_AGE.toMillis()
                && IN_FLIGHT.compareAndSet(false, true)) {
            refresh(baseUrl);
        }
        return count;
    }

    /**
     * The id under which {@code entityId}'s night adapter is loaded, or -1 when the server has
     * none for her (or has not answered yet). A being's adapter is served from her own directory,
     * {@code …/brainwrite/<entityId>/current.gguf}, and that is how it is told from another's.
     * Never blocks.
     */
    public static int nightAdapterOf(String baseUrl, String entityId) {
        count(baseUrl);   // refreshes when stale
        return nightAdapterOf(paths, entityId);
    }

    static int nightAdapterOf(Map<Integer, String> loaded, String entityId) {
        if (entityId == null || entityId.isBlank()) return -1;
        var marker = "/brainwrite/" + entityId + "/";
        for (var e : loaded.entrySet()) {
            if (e.getValue() != null && e.getValue().contains(marker)) return e.getKey();
        }
        return -1;
    }

    /** The styled species adapter's id ({@code adapters/brain/styled.gguf}), or -1 when it is not loaded. */
    public static int styledAdapterOf(String baseUrl) {
        count(baseUrl);
        return styledAdapterOf(paths);
    }

    static int styledAdapterOf(Map<Integer, String> loaded) {
        for (var e : loaded.entrySet()) {
            var p = e.getValue();
            if (p != null && p.replace('\\', '/').endsWith("/brain/styled.gguf")) return e.getKey();
        }
        return -1;
    }

    /** True when adapter 0 is the species floor ({@code adapters/brain/species.gguf}). */
    public static boolean slotZeroIsSpeciesFloor(String baseUrl) {
        count(baseUrl);
        return slotZeroIsSpeciesFloor(paths);
    }

    static boolean slotZeroIsSpeciesFloor(Map<Integer, String> loaded) {
        var p = loaded.get(0);
        return p != null && p.replace('\\', '/').endsWith("/brain/species.gguf");
    }

    /**
     * The working-turn adapter's id ({@code adapters/brain/work.gguf}), or -1 when it is not loaded.
     * Raised on the turns that call tools and zeroed when she speaks as herself — the drive
     * adapter of the two-model stack, inside the one model (measured 2026-09-26: the honesty
     * adapter made 108 tool dispatches over the live suites, the bare model 74, a species floor 11).
     */
    public static int workAdapterOf(String baseUrl) {
        count(baseUrl);
        return workAdapterOf(paths);
    }

    static int workAdapterOf(Map<Integer, String> loaded) {
        for (var e : loaded.entrySet()) {
            var p = e.getValue();
            if (p != null && p.replace('\\', '/').endsWith("/brain/work.gguf")) return e.getKey();
        }
        return -1;
    }

    private static void refresh(String baseUrl) {
        try {
            var url = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "") + "/lora-adapters";
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                try {
                    if (err == null && resp.statusCode() == 200) {
                        int n = parseCount(resp.body());
                        if (n != count) log.info("Model server at {} has {} adapter(s) loaded", baseUrl, n);
                        count = n;
                        paths = parsePaths(resp.body());
                    }
                } finally {
                    checkedAtMs = System.currentTimeMillis();
                    IN_FLIGHT.set(false);
                }
            });
        } catch (RuntimeException e) {
            checkedAtMs = System.currentTimeMillis();
            IN_FLIGHT.set(false);
        }
    }

    /** Adapter id → path from a {@code /lora-adapters} reply; empty when it is not a JSON array. */
    static Map<Integer, String> parsePaths(String body) {
        try {
            var node = Json.mapper().readTree(body);
            if (node == null || !node.isArray()) return Map.of();
            var out = new HashMap<Integer, String>();
            for (var a : node) {
                if (a.has("id")) out.put(a.get("id").asInt(), a.path("path").asText(""));
            }
            return Map.copyOf(out);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** The number of entries in a {@code /lora-adapters} reply; -1 when it is not a JSON array. */
    static int parseCount(String body) {
        try {
            var node = Json.mapper().readTree(body);
            return node != null && node.isArray() ? node.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
