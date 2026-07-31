package org.wyrdsekai.scripting.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads room scripts from the filesystem.
 * Scripts are loaded by room ID from a configurable base directory,
 * with an optional secondary directory for user-generated scripts.
 * Supports hot-reload by watching file modification times.
 */
public class ScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(ScriptLoader.class);

    private final Path baseDir;
    private final Path userScriptsDir;  // nullable — for companion-generated scripts
    private final Map<String, CachedScript> cache = new ConcurrentHashMap<>();

    private record CachedScript(String source, long lastModified) {}

    public ScriptLoader(Path baseDir) {
        this(baseDir, null);
    }

    public ScriptLoader(Path baseDir, Path userScriptsDir) {
        this.baseDir = baseDir;
        this.userScriptsDir = userScriptsDir;
    }

    /**
     * Load a script for a room. Returns null if no script exists.
     * Checks user scripts first (overrides built-in), then base directory.
     * Caches scripts and reloads if the file has been modified.
     */
    public String load(String roomId) {
        // Check user-generated scripts first (companion-created rooms)
        if (userScriptsDir != null) {
            var userScript = tryLoad(userScriptsDir, roomId);
            if (userScript != null) return userScript;
        }
        // Fall back to built-in scripts
        var script = tryLoad(baseDir, roomId);
        if (script != null) return script;
        // Template fallback: per-player rooms carry an instance suffix
        // ("study-<userId>") and matched NOTHING above, so the rich study.js
        // control-panel branches (scroll of settings, dashboard, desk
        // schedule, bookshelf) never ran in any real player Study — only a
        // room literally named "study" ever got them (second-node, 2026-07-04).
        var template = templateFor(roomId);
        if (template != null) {
            if (userScriptsDir != null) {
                var userScript = tryLoad(userScriptsDir, template);
                if (userScript != null) return userScript;
            }
            return tryLoad(baseDir, template);
        }
        return null;
    }

    /** Template script id for per-player room instances, or null. */
    private static String templateFor(String roomId) {
        if (roomId == null) return null;
        if (roomId.startsWith("study-")) return "study";
        return null;
    }

    private String tryLoad(Path dir, String roomId) {
        var scriptFile = dir.resolve(roomId + ".js");
        if (!Files.exists(scriptFile)) {
            return null;
        }

        var cacheKey = dir.toString().replace('\\', '/') + "/" + roomId;
        try {
            long lastMod = Files.getLastModifiedTime(scriptFile).toMillis();
            var cached = cache.get(cacheKey);
            if (cached != null && cached.lastModified() == lastMod) {
                return cached.source();
            }

            var source = Files.readString(scriptFile);
            cache.put(cacheKey, new CachedScript(source, lastMod));
            log.info("Loaded script for room {}: {} bytes ({})", roomId, source.length(), dir);
            return source;
        } catch (IOException e) {
            log.error("Failed to load script for room {} from {}", roomId, dir, e);
            return null;
        }
    }

    /** Get the user scripts directory (for dynamic script creation). Nullable. */
    public Path getUserScriptsDir() {
        return userScriptsDir;
    }

    /** Base directory the loader resolves room scripts from. Never null. */
    public Path getBaseDir() {
        return baseDir;
    }

    /** Invalidate cache for a room (used on hot reload signal). */
    public void invalidate(String roomId) {
        cache.entrySet().removeIf(e -> e.getKey().endsWith("/" + roomId));
    }

    /** Invalidate all cached scripts. */
    public void invalidateAll() {
        cache.clear();
    }
}
