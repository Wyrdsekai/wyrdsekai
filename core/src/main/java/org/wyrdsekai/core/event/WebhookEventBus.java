package org.wyrdsekai.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Event bus plugin that forwards agent events to a webhook URL via HTTP POST.
 *
 * <p>Configuration (from eventbus.json):
 * <pre>
 * {
 *   "type": "webhook",
 *   "url": "https://hooks.example.com/wyrdsekai",
 *   "events": ["speech", "oracle", "system"],
 *   "secret": "optional-hmac-secret"
 * }
 * </pre>
 *
 * <p>Events are serialized as JSON and POSTed asynchronously. Failures are logged
 * but do not block event delivery. A circuit breaker prevents flooding a down endpoint.</p>
 */
public class WebhookEventBus implements EventBusPlugin {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventBus.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String url;
    private final Set<String> eventFilter;
    private final String secret;
    private final HttpClient httpClient;

    // Simple circuit breaker: after 5 consecutive failures, stop for 60s
    private int consecutiveFailures = 0;
    private long circuitOpenUntil = 0;
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MS = 60_000;

    /**
     * @param url         webhook endpoint URL
     * @param eventFilter set of event type names to forward (null = all)
     * @param secret      optional HMAC secret for request signing (null = unsigned)
     */
    public WebhookEventBus(String url, Set<String> eventFilter, String secret) {
        this.url = url;
        this.eventFilter = eventFilter;
        this.secret = secret;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void initialize(EventBus bus) {
        bus.subscribe("webhook-" + url.hashCode(), this::matchesFilter, this::forward);
        log.info("Webhook plugin initialized: url={}, events={}", url,
            eventFilter != null ? eventFilter : "all");
    }

    @Override
    public void shutdown() {
        log.info("Webhook plugin shutting down: {}", url);
    }

    private boolean matchesFilter(AgentEvent event) {
        if (eventFilter == null || eventFilter.isEmpty()) return true;
        var typeName = eventTypeName(event);
        return eventFilter.contains(typeName);
    }

    private void forward(AgentEvent event) {
        // Circuit breaker check
        if (consecutiveFailures >= CIRCUIT_THRESHOLD
                && System.currentTimeMillis() < circuitOpenUntil) {
            return; // circuit open — skip
        }

        try {
            var json = serializeEvent(event);
            var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Wyrdsekai-Event", eventTypeName(event))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json));

            if (secret != null && !secret.isBlank()) {
                var signature = hmacSha256(secret, json);
                requestBuilder.header("X-Wyrdsekai-Signature", signature);
            }

            httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        consecutiveFailures = 0;
                    } else {
                        onFailure("HTTP " + resp.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    onFailure(ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            onFailure(e.getMessage());
        }
    }

    private void onFailure(String reason) {
        consecutiveFailures++;
        if (consecutiveFailures >= CIRCUIT_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
            log.warn("Webhook circuit open for {}ms after {} failures: {} (url={})",
                CIRCUIT_OPEN_MS, consecutiveFailures, reason, url);
        } else {
            log.debug("Webhook delivery failed: {} (url={}, failures={})",
                reason, url, consecutiveFailures);
        }
    }

    private String serializeEvent(AgentEvent event) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", eventTypeName(event));
            node.put("timestamp", System.currentTimeMillis());

            switch (event) {
                case AgentEvent.ZoneBroadcast zb -> {
                    node.put("namespace", zb.namespace());
                    node.put("roomId", zb.roomId());
                }
                case AgentEvent.SystemEvent se -> {
                    node.put("eventType", se.type().name());
                    node.put("source", se.source());
                    node.put("detail", se.detail());
                }
                case AgentEvent.AdjacentActivity aa -> {
                    node.put("sourceRoomId", aa.sourceRoomId());
                    node.put("sourceRoomName", aa.sourceRoomName());
                    node.put("activityType", aa.type().name());
                    node.put("entityCount", aa.entityCount());
                }
                case AgentEvent.AgentMessage am -> {
                    node.put("fromAgentId", am.fromAgentId());
                    node.put("fromAgentName", am.fromAgentName());
                    node.put("toAgentId", am.toAgentId());
                    node.put("message", am.message());
                }
                case AgentEvent.LocationUpdate lu -> {
                    node.put("latitude", lu.latitude());
                    node.put("longitude", lu.longitude());
                    node.put("locationName", lu.locationName());
                    node.put("state", lu.state() != null ? lu.state().name() : "unknown");
                }
                case AgentEvent.OraclePredictionsArrived op -> {
                    node.put("userId", op.userId());
                    node.put("count", op.count());
                    node.put("maxConfidence", op.maxConfidence());
                    node.put("hasActionable", op.hasActionable());
                }
                case AgentEvent.AbortSignal as -> {
                    node.put("fromPlayerId", as.fromPlayerId());
                    node.put("fromPlayerName", as.fromPlayerName());
                    node.put("roomId", as.roomId());
                }
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"detail\":\"serialization failed\"}";
        }
    }

    static String eventTypeName(AgentEvent event) {
        return switch (event) {
            case AgentEvent.ZoneBroadcast _ -> "zone_broadcast";
            case AgentEvent.SystemEvent _ -> "system";
            case AgentEvent.AdjacentActivity _ -> "adjacent_activity";
            case AgentEvent.AgentMessage _ -> "agent_message";
            case AgentEvent.LocationUpdate _ -> "location_update";
            case AgentEvent.OraclePredictionsArrived _ -> "oracle_predictions";
            case AgentEvent.AbortSignal _ -> "abort_signal";
        };
    }

    private static String hmacSha256(String secret, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "error";
        }
    }
}
