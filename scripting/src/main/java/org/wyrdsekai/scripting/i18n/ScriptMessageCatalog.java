package org.wyrdsekai.scripting.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Message catalog for room scripts, backed by JSON files.
 * Loads from scripts/i18n/{lang}.json on the classpath or filesystem.
 * Uses a simple regex parser for flat key-value JSON (no Jackson dependency).
 */
public final class ScriptMessageCatalog {

    private static final Logger log = LoggerFactory.getLogger(ScriptMessageCatalog.class);
    private static final Map<String, ScriptMessageCatalog> CACHE = new ConcurrentHashMap<>();
    private static final Pattern KV_PATTERN = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final String lang;
    private final Map<String, String> messages;
    private final ScriptMessageCatalog fallback;

    private ScriptMessageCatalog(String lang, Map<String, String> messages,
                                  ScriptMessageCatalog fallback) {
        this.lang = lang;
        this.messages = messages;
        this.fallback = fallback;
    }

    /** Get or create a catalog for a language. Falls back to English. */
    public static ScriptMessageCatalog forLang(String lang) {
        return CACHE.computeIfAbsent(lang, ScriptMessageCatalog::load);
    }

    /** Get the English catalog. */
    public static ScriptMessageCatalog english() {
        return forLang("en");
    }

    /** Create a catalog from an in-memory map (for testing). */
    public static ScriptMessageCatalog ofMap(String lang, Map<String, String> messages) {
        var fallback = "en".equals(lang) ? null : forLang("en");
        return new ScriptMessageCatalog(lang, new HashMap<>(messages), fallback);
    }

    /** Clear all cached catalogs (for testing). */
    public static void clearCaches() {
        CACHE.clear();
    }

    /** Look up a message by key. Returns the key itself if no translation found.
     *
     * <p>If the resolved pattern contains the MessageFormat single-quote escape
     * {@code ''} (used to literalize an apostrophe inside a MessageFormat
     * string), we pass it through {@link MessageFormat#format} with no
     * arguments so the escape resolves to a single {@code '}. Without this,
     * un-parameterized messages like {@code "You don''t have that."} leak as
     * literal {@code "You don''t have that."} to the user — see
     * </p>
     *
     * <p>Patterns without {@code ''} skip the format pass — keeps strings with
     * literal curly-brace chars (rare, but possible in user-facing copy)
     * from accidentally tripping MessageFormat's placeholder parser.</p>
     */
    public String get(String key) {
        var msg = messages.get(key);
        if (msg == null) {
            if (fallback != null) return fallback.get(key);
            return key;
        }
        if (msg.indexOf("''") < 0) return msg;
        try {
            return MessageFormat.format(msg, EMPTY_ARGS);
        } catch (IllegalArgumentException e) {
            return msg;
        }
    }

    private static final Object[] EMPTY_ARGS = new Object[0];

    /** Look up a message by key with MessageFormat arguments. */
    public String get(String key, Object... args) {
        // Fetch the RAW pattern and format it EXACTLY ONCE. We must NOT route
        // through the no-arg get(key): that pre-formats any string containing the
        // MessageFormat '' escape with EMPTY_ARGS, which resolves a real {N}
        // placeholder against no arguments BEFORE our real args arrive — the
        // "...searches for {0}..." leak observed in the multi-agent soak. The
        // single format pass here both unescapes '' → ' and substitutes {N}.
        var pattern = messages.get(key);
        if (pattern == null) {
            return fallback != null ? fallback.get(key, args) : key;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern; // malformed pattern
        }
    }

    /** Whether this catalog has a translation for the given key. */
    public boolean hasKey(String key) {
        return messages.containsKey(key) ||
               (fallback != null && fallback.hasKey(key));
    }

    public String getLang() { return lang; }
    public int size() { return messages.size(); }

    /**
     * Merge additional translations from an i18n JSON file (extension support).
     * New keys are added; existing keys are NOT overwritten (core translations take precedence).
     *
     * @param path path to a flat key-value JSON file
     */
    public void mergeFromFile(Path path) {
        try {
            var json = Files.readString(path);
            var extra = parseJson(json);
            int added = 0;
            for (var entry : extra.entrySet()) {
                if (!messages.containsKey(entry.getKey())) {
                    messages.put(entry.getKey(), entry.getValue());
                    added++;
                }
            }
            if (added > 0) {
                log.info("Merged {} i18n keys from {} into '{}' catalog", added, path, lang);
            }
        } catch (IOException e) {
            log.warn("Failed to merge i18n from {}: {}", path, e.getMessage());
        }
    }

    private static ScriptMessageCatalog load(String lang) {
        var fallback = "en".equals(lang) ? null : forLang("en");
        var messages = loadMessages(lang);
        log.info("Loaded script i18n catalog for '{}': {} keys", lang, messages.size());
        return new ScriptMessageCatalog(lang, messages, fallback);
    }

    private static Map<String, String> loadMessages(String lang) {
        // Try classpath first (for testing / JAR packaging)
        var resource = "/i18n/" + lang + ".json";
        try (var is = ScriptMessageCatalog.class.getResourceAsStream(resource)) {
            if (is != null) {
                return parseJson(new String(is.readAllBytes()));
            }
        } catch (IOException e) {
            log.warn("Failed to load script i18n from classpath {}: {}", resource, e.getMessage());
        }

        // Try filesystem (scripts/i18n/{lang}.json — multiple search paths)
        var searchPaths = new ArrayList<>(List.of("scripts", "../scripts"));
        // Add user scripts dir from system property or env var
        var userScripts = System.getProperty("wyrdsekai.scripts.dir");
        if (userScripts == null) userScripts = System.getenv("WYRDSEKAI_SCRIPTS_DIR");
        if (userScripts != null) searchPaths.add(0, userScripts);
        // Add install dir default
        var homeDir = System.getProperty("user.home");
        if (homeDir != null) searchPaths.add(homeDir + "/.wyrdsekai/scripts");
        // Package install locations (deb: /opt/wyrdsekai, pkg: /usr/local/wyrdsekai)
        searchPaths.add("/opt/wyrdsekai/scripts");
        searchPaths.add("/usr/local/wyrdsekai/scripts");
        for (var base : searchPaths) {
            var path = Path.of(base, "i18n", lang + ".json");
            if (Files.exists(path)) {
                try {
                    return parseJson(Files.readString(path));
                } catch (IOException e) {
                    log.warn("Failed to load script i18n from {}: {}", path, e.getMessage());
                }
            }
        }

        log.debug("No script i18n file found for '{}'", lang);
        return Map.of();
    }

    /** Simple JSON string-to-string parser for flat key-value objects. */
    static Map<String, String> parseJson(String json) {
        var result = new HashMap<String, String>();
        var matcher = KV_PATTERN.matcher(json);
        while (matcher.find()) {
            var key = matcher.group(1);
            var value = unescapeJson(matcher.group(2));
            result.put(key, value);
        }
        return result;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\/", "/");
    }
}
