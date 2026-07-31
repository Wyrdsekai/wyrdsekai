package org.wyrdsekai.core.room;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.soul.JsonAtomicWriter;
import org.wyrdsekai.core.util.LanguageHeuristics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM-rewritten room descriptions, baked lazily in the active theme's voice.
 *
 * <p>Where {@link ZoneAestheticDescriber} does a cheap deterministic restyle
 * (lexicon substitution + atmosphere line) synchronously, this service produces
 * a <em>genuine rewrite</em>: it hands the room's authored description plus the
 * theme's {@code stylePrompt} to the 4B voice backend and stores the result.
 * The model invents new prose in the theme's voice rather than swapping words.</p>
 *
 * <p><b>Why a service and not an inline call:</b> {@code RoomActor.onLookRoom} is
 * an event-sourced actor handler and must not block on inference. So this is
 * <b>progressive enhancement</b>: {@link #resolve} returns a cached rewrite if
 * one exists (instant, synchronous read) or {@code null} after scheduling a
 * background bake. The caller renders the deterministic restyle meanwhile; the
 * next look — once the bake lands — serves the rich version. No look ever waits
 * on the model.</p>
 *
 * <p><b>Cache key</b> = {@code roomId|theme|lang|hash(baseDescription)}. Changing
 * the theme misses on a new key (re-bake under the new voice); editing the base
 * description changes the hash (re-bake). The cache persists to
 * {@code <dataDir>/themed-descriptions.json} via {@link JsonAtomicWriter}.</p>
 *
 * <p><b>Graceful degradation:</b> if the voice backend is down, the bake fails,
 * the entry stays absent, and the room keeps reading via the deterministic
 * restyle forever — no error surfaces to the visitor. Disabled entirely via
 * {@link WyrdConfig#themedRoomDescriptionsEnabled()}.</p>
 */
public final class ThemedDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(ThemedDescriptionService.class);
    private static volatile ThemedDescriptionService instance;

    /** Tokens to allow for a rewrite (room descriptions are short prose). */
    private static final int MAX_TOKENS = 240;
    private static final double TEMPERATURE = 0.7;
    /** Discard a rewrite shorter/longer than these (defends against refusals / runaways). */
    private static final int MIN_CHARS = 20;
    private static final int MAX_CHARS = 1200;
    /** Per-bake hard cap; the voice 4B answers well inside this. */
    private static final Duration BAKE_TIMEOUT = Duration.ofSeconds(45);

    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path cacheFile;
    private final InferenceClient client;
    private final String model;

    /** key → rewritten prose. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** keys with a bake in flight (dedup so a hammered room enqueues once). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService bakePool;

    private ThemedDescriptionService() {
        var cfg = WyrdConfig.get();
        this.enabled = cfg.themedRoomDescriptionsEnabled();
        var dataDir = cfg.dataDir();
        var root = (dataDir != null && !dataDir.isBlank())
            ? Path.of(dataDir)
            : Path.of(System.getProperty("user.home"), ".wyrdsekai");
        this.cacheFile = root.resolve("themed-descriptions.json");
        // backendHint "llama-server" injects chat_template_kwargs.enable_thinking=false —
        // without it a Qwen3.x voice model (V10) spends the whole token budget inside a
        // <think> block and returns empty content. mlx respects the same body shape, so
        // this hint is correct on both Linux (llama-server) and macOS (MLX) voice backends.
        this.client = new InferenceClient(
            cfg.voiceUrl(), null, BAKE_TIMEOUT, new ApiProvider.OpenAI("llama-server"));
        this.model = "default";  // llama-server ignores; MLX maps to the loaded voice model
        var seq = new AtomicInteger();
        this.bakePool = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "themed-desc-bake-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        loadCache();
    }

    public static void init() { instance = new ThemedDescriptionService(); }
    public static ThemedDescriptionService get() { return instance; }

    /**
     * Return the cached LLM rewrite for this room under this aesthetic, or
     * {@code null} (after scheduling a background bake) when none is ready yet.
     * No-ops to {@code null} for the default aesthetic or a blank description —
     * the caller then renders the deterministic {@link ZoneAestheticDescriber}.
     */
    public String resolve(String roomId, String baseDescription,
                          ZoneAesthetic aesthetic, String locale) {
        if (!enabled) return null;
        if (roomId == null || baseDescription == null || baseDescription.isBlank()) return null;
        if (aesthetic == null) return null;
        var theme = aesthetic.name();
        if (theme == null || theme.isBlank() || "default".equals(theme)) return null;
        if (aesthetic.stylePrompt() == null || aesthetic.stylePrompt().isBlank()) return null;

        var lang = (locale == null || locale.isBlank()) ? "en" : locale;
        var key = cacheKey(roomId, theme, lang, hash(baseDescription));
        var hit = cache.get(key);
        if (hit != null) {
            if (matchesLanguage(hit, lang)) return hit;
            // A pre-guard bake persisted a wrong-language rewrite (the Spanglish
            // drift). Evict it so we stop serving it and re-bake in the right
            // language; render the deterministic base this turn.
            log.info("Evicting cached themed description in wrong language (wanted {}): {}",
                lang, key);
            cache.remove(key);
            persist();
        }

        scheduleBake(key, baseDescription, aesthetic, lang);
        return null;
    }

    private void scheduleBake(String key, String baseDescription,
                              ZoneAesthetic aesthetic, String lang) {
        if (!inFlight.add(key)) return;  // already baking
        try {
            bakePool.submit(() -> {
                try {
                    var rewritten = bake(baseDescription, aesthetic, lang);
                    if (rewritten != null) {
                        cache.put(key, rewritten);
                        persist();
                        log.info("Baked themed description ({}): {} chars",
                            aesthetic.name(), rewritten.length());
                    }
                } catch (Exception e) {
                    log.debug("Themed-description bake failed ({}): {}",
                        aesthetic.name(), e.getMessage());
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (Exception e) {
            // pool rejected (shutting down) — drop the in-flight marker
            inFlight.remove(key);
        }
    }

    /** Blocking inference call — runs only on the bake thread. */
    private String bake(String baseDescription, ZoneAesthetic aesthetic, String lang)
            throws Exception {
        var system = buildSystemPrompt(aesthetic, lang);
        var raw = client.complete(model, system, baseDescription, MAX_TOKENS, TEMPERATURE)
            .get(BAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        var out = sanitize(raw);
        // Language guard (2026-07-07): the voice 4B sometimes ignores the prompt's
        // language anchor and code-switches — observed live, an English rewrite
        // drifted into Spanglish ("Tu room es un nodo cerrado — viejo leather
        // chair frente al hearth…") and got cached + persisted, so every later
        // look rendered Spanish. Reject a drifted rewrite so the caller falls back
        // to the deterministic base description instead of baking wrong-language prose.
        if (out != null && !matchesLanguage(out, lang)) {
            log.info("Themed-description bake drifted language (wanted {}) — rejecting rewrite: {}",
                lang, out.substring(0, Math.min(60, out.length())));
            return null;
        }
        return out;
    }

    /**
     * Heuristic guard: does {@code text} read as the requested {@code lang}? Not a
     * full language detector — just enough to catch the 4B's code-switch drift
     * across the three supported locales (en/es/ja). Japanese is unambiguous
     * (kana/kanji script); Spanish is flagged by function-word density + its
     * distinctive characters. Errs toward accepting for es/ja (only the observed
     * en→es drift is strict) so we never reject a legitimately-themed rewrite.
     */
    static boolean matchesLanguage(String text, String lang) {
        // Logic lives in LanguageHeuristics (2026-07-31) so the CONVERSATION
        // path can share the same drift signal; this delegate keeps the
        // original call sites and tests intact.
        return LanguageHeuristics.matches(text, lang);
    }

    static String buildSystemPrompt(ZoneAesthetic aesthetic, String lang) {
        // Always anchor the output language — the voice 4B is multilingual-steered and
        // some style registers (formal/warm) otherwise drift the rewrite into another
        // language. State it explicitly for English too.
        var langClause = " Write in " + languageName(lang) + ".";
        return aesthetic.stylePrompt() + "\n\n"
            + "Rewrite the room description the user gives you in that voice and style. "
            + "Preserve every physical fact: the same exits, objects, furniture, layout, "
            + "and anyone or anything present — do not invent new objects or exits, and do "
            + "not remove any. Keep it to 2-4 sentences." + langClause + " "
            + "Output ONLY the rewritten description — no preamble, no quotation marks, "
            + "no commentary, no lists.";
    }

    private static String languageName(String lang) {
        if (lang == null || lang.isBlank()) return "English";
        return switch (lang.toLowerCase(Locale.ROOT)) {
            case "es" -> "Spanish";
            case "ja" -> "Japanese";
            default -> "English";
        };
    }

    /** Trim, strip wrapping quotes, drop a leaked "Here is…:" preamble; length-gate. */
    static String sanitize(String raw) {
        if (raw == null) return null;
        var s = raw.strip();
        if (s.isEmpty()) return null;
        // Drop a leading meta line like "Here is the rewritten description:".
        int nl = s.indexOf('\n');
        if (nl > 0 && nl < 80) {
            var firstLine = s.substring(0, nl).toLowerCase();
            if (firstLine.endsWith(":") && (firstLine.contains("rewrit")
                    || firstLine.contains("here is") || firstLine.contains("description"))) {
                s = s.substring(nl + 1).strip();
            }
        }
        // Strip a single layer of wrapping quotes.
        if (s.length() >= 2
                && (s.charAt(0) == '"' || s.charAt(0) == '“')
                && (s.charAt(s.length() - 1) == '"' || s.charAt(s.length() - 1) == '”')) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.length() < MIN_CHARS || s.length() > MAX_CHARS) return null;
        return s;
    }

    private static String cacheKey(String roomId, String theme, String lang, String hash) {
        return roomId + "|" + theme + "|" + lang + "|" + hash;
    }

    /** Short content hash so a base-description edit invalidates the cached rewrite. */
    static String hash(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private void loadCache() {
        try {
            if (Files.exists(cacheFile)) {
                Map<String, String> loaded = mapper.readValue(
                    Files.readString(cacheFile), new TypeReference<Map<String, String>>() {});
                cache.putAll(loaded);
                log.info("Loaded {} themed room descriptions from {}", cache.size(), cacheFile);
            }
        } catch (Exception e) {
            log.warn("Failed to load themed-description cache ({}): {}",
                cacheFile, e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            JsonAtomicWriter.write(cacheFile, cache);
        } catch (Exception e) {
            log.warn("Failed to persist themed-description cache: {}", e.getMessage());
        }
    }

    /** Test/diagnostic: number of cached rewrites. */
    public int cachedCount() { return cache.size(); }

    /** Whether the feature is on (config-gated). */
    public boolean isEnabled() { return enabled; }
}
