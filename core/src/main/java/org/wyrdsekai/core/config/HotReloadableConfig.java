package org.wyrdsekai.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Generic hot-reloadable config file watcher. Checks file modification time
 * on each access — cheap (stat only, no polling thread). Reloads when changed.
 *
 * <p>Thread-safe: uses volatile fields for lock-free reads. The worst case
 * under concurrent access is a redundant reload (harmless).</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   var config = new HotReloadableConfig<>(path, HouseholdPolicy::fromFile, HouseholdPolicy.defaults());
 *   HouseholdPolicy policy = config.get(); // reloads if file changed
 * }</pre>
 *
 * @param <T> The configuration type
 */
public class HotReloadableConfig<T> {

    private static final Logger log = LoggerFactory.getLogger(HotReloadableConfig.class);

    private final Path path;
    private final Function<Path, T> loader;
    private final T defaultValue;
    private volatile T cached;
    private volatile long lastModified;

    /**
     * Create a hot-reloadable config.
     *
     * @param path         Path to the config file (may be null — always returns default)
     * @param loader       Function that loads config from a Path. Must not return null.
     * @param defaultValue Value to return when file is missing or load fails
     */
    public HotReloadableConfig(Path path, Function<Path, T> loader, T defaultValue) {
        this.path = path;
        this.loader = loader;
        this.defaultValue = defaultValue;
    }

    /**
     * Get the current value, reloading from disk if the file was modified.
     * The file modification time check is a single stat() call — very cheap.
     *
     * @return Current config value (reloaded if file changed), or default if file missing/error
     */
    public T get() {
        if (path == null || !Files.exists(path)) {
            return cached != null ? cached : defaultValue;
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified != lastModified) {
                lastModified = modified;
                cached = loader.apply(path);
                log.info("Hot-reloaded config from {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to reload config from {}: {}", path, e.getMessage());
        }
        return cached != null ? cached : defaultValue;
    }

    /**
     * Force reload on next {@link #get()} call. Resets the last-modified
     * timestamp so the next access re-reads the file.
     *
     * @return The reloaded value
     */
    public T reload() {
        lastModified = 0;
        return get();
    }

    /**
     * Get the cached value without checking the file. Use this in tight loops
     * where the stat() overhead matters. Returns default if nothing cached yet.
     *
     * @return Last cached value, or default if never loaded
     */
    public T getCached() {
        return cached != null ? cached : defaultValue;
    }

    /**
     * Get the path being watched.
     *
     * @return Config file path, or null if constructed with null
     */
    public Path path() {
        return path;
    }

    /**
     * Get the default value.
     *
     * @return The default value provided at construction
     */
    public T defaultValue() {
        return defaultValue;
    }
}
