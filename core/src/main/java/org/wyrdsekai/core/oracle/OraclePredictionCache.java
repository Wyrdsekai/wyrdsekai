package org.wyrdsekai.core.oracle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of Oracle predictions per user.
 *
 * Populated by OracleForgeHook during sleep cycles.
 * Read by PromptAssembler (via OracleAgentContext) and room scripts.
 *
 * Singleton — initialized by Main.java at startup.
 */
public final class OraclePredictionCache {

    private static volatile OraclePredictionCache instance;

    private final Map<String, List<OraclePrediction>> cache = new ConcurrentHashMap<>();

    private OraclePredictionCache() {}

    public static OraclePredictionCache get() {
        if (instance == null) {
            synchronized (OraclePredictionCache.class) {
                if (instance == null) {
                    instance = new OraclePredictionCache();
                }
            }
        }
        return instance;
    }

    /** Store predictions for a user (replaces previous). */
    public void put(String userId, List<OraclePrediction> predictions) {
        cache.put(userId, List.copyOf(predictions));
    }

    /** Get cached predictions for a user, or empty list. */
    public List<OraclePrediction> get(String userId) {
        return cache.getOrDefault(userId, List.of());
    }

    /** Remove cached predictions for a specific user. */
    public void remove(String userId) {
        cache.remove(userId);
    }

    /** Clear all cached predictions. */
    public void clear() {
        cache.clear();
    }
}
