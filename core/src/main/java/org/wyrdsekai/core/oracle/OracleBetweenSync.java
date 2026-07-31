package org.wyrdsekai.core.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Syncs Oracle predictions to phone nodes via Between (NATS).
 *
 * Subject: between.{householdId}.{nodeId}.*.oracle.predictions
 *
 * Phone nodes subscribe and merge server predictions with local
 * oracle-core-kt/ts predictions. Server predictions are richer
 * (more data sources, bigger models).
 */
public final class OracleBetweenSync {

    private static final Logger log = LoggerFactory.getLogger(OracleBetweenSync.class);

    private final String nodeId;
    private final String householdId;

    /** Callback to publish via NatsBridge or BetweenClient. */
    private final PublishFunction publisher;

    @FunctionalInterface
    public interface PublishFunction {
        void publish(String subject, byte[] data);
    }

    /** Production instance — wired by Main at boot (audit 2026-07-11: this class
     *  was constructed only in tests, so phones subscribed to predictions that
     *  were never published). Null when Between is disabled (single-node). */
    private static volatile OracleBetweenSync INSTALLED;

    public static void install(OracleBetweenSync sync) { INSTALLED = sync; }

    public static OracleBetweenSync installed() { return INSTALLED; }

    public OracleBetweenSync(String nodeId, String householdId, PublishFunction publisher) {
        this.nodeId = nodeId;
        this.householdId = householdId;
        this.publisher = publisher;
    }

    /**
     * Broadcast predictions to all household nodes.
     * Called after OracleForgeHook produces fresh predictions.
     */
    public void broadcastPredictions(List<OraclePrediction> predictions) {
        if (predictions.isEmpty()) return;

        var json = OracleForgeHook.predictionsToJson(predictions);
        var subject = String.format("between.%s.%s.*.oracle.predictions", householdId, nodeId);

        try {
            publisher.publish(subject, json.getBytes(StandardCharsets.UTF_8));
            log.info("Broadcast {} Oracle predictions to household", predictions.size());
        } catch (Exception e) {
            log.debug("Failed to broadcast Oracle predictions: {}", e.getMessage());
        }
    }
}
