package org.wyrdsekai.core.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Hook into the Forge sleep cycle to trigger Oracle training and prediction.
 *
 * Called by CompanionActor.completeSleep():
 *   1. Triggers oracle-core training on accumulated events
 *   2. Retrieves fresh predictions
 *   3. Stores predictions as room properties for manifestation
 *
 * Non-fatal: if oracle-core is unavailable, sleep completes normally.
 */
public final class OracleForgeHook {

    private static final Logger log = LoggerFactory.getLogger(OracleForgeHook.class);

    private final OracleBridge bridge;

    public OracleForgeHook(OracleBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Run the Oracle cycle during Forge sleep.
     *
     * @param userId    The user/agent DID
     * @param rooms     Active room IDs (for manifestation targeting)
     * @return Fresh predictions, or empty list on failure
     */
    public CompletableFuture<List<OraclePrediction>> onForgeSleep(String userId, List<String> rooms) {
        log.info("Oracle Forge hook: training for user '{}'", userId);

        return bridge.train(userId)
            .thenCompose(trainResult -> {
                if (trainResult != null) {
                    log.info("Oracle training complete: {}", trainResult);
                }
                // Get fresh predictions
                return bridge.anticipate(userId, 0.5);
            })
            .thenApply(predictions -> {
                log.info("Oracle produced {} predictions for user '{}'", predictions.size(), userId);
                return predictions;
            })
            .exceptionally(e -> {
                log.debug("Oracle Forge hook failed (non-fatal): {}", e.getMessage());
                return List.of();
            });
    }

    /**
     * Convert predictions to JSON for storage as room properties.
     * Room scripts read this to manifest objects, narration, etc.
     */
    public static String predictionsToJson(List<OraclePrediction> predictions) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < predictions.size(); i++) {
            var p = predictions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"id\":\"").append(escape(p.id())).append("\",");
            sb.append("\"text\":\"").append(escape(p.text())).append("\",");
            sb.append("\"category\":\"").append(escape(p.category())).append("\",");
            sb.append("\"confidence\":").append(p.confidence()).append(",");
            sb.append("\"textKey\":\"").append(escape(p.textKey())).append("\",");
            sb.append("\"actionable\":").append(p.actionable());
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
