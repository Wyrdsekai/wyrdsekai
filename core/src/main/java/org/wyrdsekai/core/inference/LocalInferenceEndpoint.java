package org.wyrdsekai.core.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.util.List;
import java.util.Optional;

/**
 * The OpenAI-compatible endpoint this node actually serves — for anything that needs to
 * talk to the local model but is not the inference router.
 *
 * <h2>Why this exists</h2>
 * Every coding backend carried its own idea of where the local model lives, and they all
 * agreed with each other and not with the node: goose {@code http://localhost:8200} with
 * a 9B model name, OpenCode {@code http://localhost:8200/v1}, CodeZaiku
 * {@code http://localhost:8200}. Those are one developer machine's port layout, written
 * down as defaults. Production happened to have a 9B on 8200, so it worked there and
 * nobody noticed.
 *
 * <p>Staged fresh on 2026-08-21: the install itself correctly auto-detected the only
 * llama-server on the box ({@code :8201}, the 4B) and routed the companion to it. The
 * coding backends never asked. goose was sent to {@code :8200}, answered
 * <i>"Network error: Could not connect to localhost:8200"</i> in 7 seconds with exit 0,
 * and the backend reported SUCCEEDED. The companion told the steward the workshop had
 * finished, touching 0 files, and handed him an empty codex.
 *
 * <p>One resolver, so a backend with no explicitly configured endpoint asks the node
 * instead of assuming. The order is the same the inference router uses: an operator's
 * {@code WYRDSEKAI_INFERENCE_URL} wins; otherwise the conventional local ports are probed
 * for a live {@code /v1/models}. Explicit backend config still overrides all of this —
 * this is the DEFAULT, not the authority.
 */
public final class LocalInferenceEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LocalInferenceEndpoint.class);

    /** Same candidates, same order, as {@code InferenceConfig.autoDetectLlamaServers}. */
    static final List<String> CANDIDATES = List.of(
        "http://127.0.0.1:8200", "http://127.0.0.1:8201", "http://127.0.0.1:11525");

    private LocalInferenceEndpoint() {}

    /**
     * @param url     base URL without {@code /v1}
     * @param modelId the id the server reports for its model — what a client should send
     */
    public record Endpoint(String url, String modelId) {}

    /** How to ask a URL what it serves. Injectable so tests never touch real ports. */
    @FunctionalInterface
    public interface Prober {
        List<String> modelsAt(String url);
    }

    private static final Prober REAL = InferenceConfig::modelsServedNow;
    private static final long CACHE_MS = 30_000;
    private static volatile Endpoint cached;
    private static volatile long cachedAt;
    private static volatile Endpoint override;
    private static volatile boolean nothingLive;

    /** What is live right now, or empty when this node serves nothing locally. */
    public static Optional<Endpoint> resolve() {
        if (nothingLive) return Optional.empty();
        var o = override;
        if (o != null) return Optional.of(o);
        var now = System.currentTimeMillis();
        var c = cached;
        if (c != null && now - cachedAt < CACHE_MS) return Optional.of(c);
        var found = resolve(configuredUrl(), REAL);
        found.ifPresent(e -> { cached = e; cachedAt = now; });
        return found;
    }

    /** Pure resolution: configured URL first, then the conventional local ports. */
    static Optional<Endpoint> resolve(String configured, Prober prober) {
        if (configured != null) {
            var served = firstModel(configured, prober);
            if (served != null) return Optional.of(new Endpoint(configured, served));
            log.debug("[local-inference] {} is configured but serves no model", configured);
        }
        for (var url : CANDIDATES) {
            var served = firstModel(url, prober);
            if (served != null) {
                log.info("[local-inference] resolved {} (model {})", url, served);
                return Optional.of(new Endpoint(url, served));
            }
        }
        return Optional.empty();
    }

    /** Test seam: pin what resolve() answers. Null clears it. */
    public static void overrideForTests(Endpoint e) {
        override = e;
        cached = null;
    }

    /**
     * Test seam: pretend this node serves nothing, so the compiled-in defaults are what
     * a test sees. Without it a test of the FALLBACK passes or fails on whether the
     * developer happens to have a model running — which is exactly what happened the
     * first time the fallback tests ran beside a live staging server.
     */
    public static void pinNothingLiveForTests(boolean on) {
        nothingLive = on;
        cached = null;
    }

    private static String configuredUrl() {
        try {
            var v = WyrdConfig.get().resolve("WYRDSEKAI_INFERENCE_URL", "inference.url",
                () -> null);
            return v == null || v.isBlank() ? null : v.replaceAll("/v1/?$", "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstModel(String url, Prober prober) {
        try {
            var models = prober.modelsAt(url);
            return models == null || models.isEmpty() ? null : models.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
