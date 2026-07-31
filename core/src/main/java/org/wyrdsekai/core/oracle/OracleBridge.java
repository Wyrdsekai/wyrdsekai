package org.wyrdsekai.core.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the oracle-core Python sidecar.
 *
 * Sends events, triggers training, and retrieves predictions.
 * All calls are async (CompletableFuture). Non-fatal on failure —
 * the Oracle is an enhancement, not a requirement.
 */
public final class OracleBridge {

    private static final Logger log = LoggerFactory.getLogger(OracleBridge.class);
    private static volatile OracleBridge instance;
    /** Cached result of the most recent {@link #isHealthy()} probe (advisory). */
    private static volatile boolean lastReachable = false;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper;

    /** Get the global instance (null if not initialized). */
    public static OracleBridge getInstance() { return instance; }

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init(String baseUrl) {
        instance = new OracleBridge(baseUrl);
        log.info("OracleBridge initialized: {}", baseUrl);
    }

    public OracleBridge(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.mapper = new ObjectMapper();
    }

    public OracleBridge() {
        this("http://localhost:7073");
    }

    // ── Health ──────────────────────────────────────────────────────────

    public CompletableFuture<Boolean> isHealthy() {
        return get("/health").thenApply(resp -> {
            boolean ok = resp != null && resp.has("status");
            lastReachable = ok;
            return ok;
        });
    }

    /**
     * Last known reachability of the sidecar, cached from the most recent
     * {@link #isHealthy()} probe. Advisory only — {@code false} until the first
     * probe completes. Used to advertise prediction capability across the mesh.
     */
    public static boolean isReachable() { return lastReachable; }

    // ── Ingest ─────────────────────────────────────────────────────────

    /**
     * Send events to oracle-core for ingestion.
     * @return number of events ingested, or -1 on failure
     */
    public CompletableFuture<Integer> ingest(String userId, List<OracleEvent> events) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        var arr = body.putArray("events");
        for (var event : events) {
            arr.add(eventToJson(event));
        }
        return post("/v1/ingest", body).thenApply(resp -> {
            if (resp != null && resp.has("ingested")) {
                return resp.get("ingested").asInt(-1);
            }
            return -1;
        });
    }

    // ── Analysis ───────────────────────────────────────────────────────

    public CompletableFuture<List<OraclePrediction>> anticipate(String userId, double minConfidence) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        body.put("min_confidence", minConfidence);
        return post("/v1/analyze/anticipate", body).thenApply(this::parsePredictions);
    }

    public CompletableFuture<List<OraclePrediction>> analyzePatterns(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/analyze/patterns", body).thenApply(this::parsePredictions);
    }

    public CompletableFuture<List<OraclePrediction>> analyzeAnomalies(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/analyze/anomalies", body).thenApply(this::parsePredictions);
    }

    public CompletableFuture<List<OraclePrediction>> analyzeForecast(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/analyze/forecast", body).thenApply(this::parsePredictions);
    }

    public CompletableFuture<List<OraclePrediction>> analyzeTopics(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/analyze/topics", body).thenApply(this::parsePredictions);
    }

    public CompletableFuture<List<OraclePrediction>> analyzeCorrelations(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/analyze/correlations", body).thenApply(this::parsePredictions);
    }

    // ── Training ───────────────────────────────────────────────────────

    public CompletableFuture<JsonNode> train(String userId) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        return post("/v1/train", body);
    }

    // ── Feedback ───────────────────────────────────────────────────────

    public CompletableFuture<Void> feedback(String userId, String predictionId,
                                              String outcome, boolean userEngaged) {
        var body = mapper.createObjectNode();
        body.put("user_id", userId);
        body.put("prediction_id", predictionId);
        body.put("outcome", outcome);
        body.put("user_engaged", userEngaged);
        return post("/v1/feedback", body).thenApply(r -> null);
    }

    // ── Internal ───────────────────────────────────────────────────────

    private CompletableFuture<JsonNode> post(String path, ObjectNode body) {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        log.warn("Oracle {} returned {}: {}", path, resp.statusCode(), resp.body());
                        return null;
                    }
                    try {
                        return mapper.readTree(resp.body());
                    } catch (Exception e) {
                        log.warn("Failed to parse Oracle response for {}: {}", path, e.getMessage());
                        return null;
                    }
                })
                .exceptionally(e -> {
                    log.debug("Oracle {} failed: {}", path, e.getMessage());
                    return null;
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<JsonNode> get(String path) {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) return null;
                    try {
                        return mapper.readTree(resp.body());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .exceptionally(e -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private ObjectNode eventToJson(OracleEvent event) {
        var node = mapper.createObjectNode();
        node.put("timestamp", event.timestamp().toString());
        node.put("source", event.source());
        node.put("event_type", event.eventType());
        node.put("content", event.content());
        node.put("entity_id", event.entityId());
        node.put("room_id", event.roomId());
        if (event.numericValue() != null) {
            node.put("numeric_value", event.numericValue());
        }
        return node;
    }

    private List<OraclePrediction> parsePredictions(JsonNode resp) {
        if (resp == null) return List.of();

        // Response has either "predictions" or "insights" array
        var arr = resp.has("insights") ? resp.get("insights") : resp.get("predictions");
        if (arr == null || !arr.isArray()) return List.of();

        var predictions = new ArrayList<OraclePrediction>();
        for (var node : arr) {
            predictions.add(new OraclePrediction(
                node.path("id").asText(""),
                node.path("text").asText(""),
                node.path("category").asText("pattern"),
                node.path("confidence").asDouble(0.0),
                node.path("text_key").asText(""),
                node.path("evidence").asText(""),
                node.path("actionable").asBoolean(false)
            ));
        }
        return predictions;
    }
}
