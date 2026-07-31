package org.wyrdsekai.core.external.t;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * (Phase T) — MQTT broker subscriber.
 *
 * <p>{@code world.inbound.mqtt(topic, hookName, opts?)} maintains a per-broker
 * connection (in production via Eclipse Paho); the in-process default uses a
 * {@link MqttClient} functional injector so tests can publish messages
 * without spinning up Mosquitto.</p>
 *
 * <p>Subscriptions are keyed by {@code (broker, topic)} to a list of inbound
 * subscription ids — multiple items can subscribe to the same topic and each
 * gets its own delivery + rate-limit window.</p>
 */
public final class MqttListener {

    private static final Logger log = LoggerFactory.getLogger(MqttListener.class);

    /**
     * Functional MQTT client — production wires Paho; tests substitute a
     * deterministic in-memory broker.
     */
    public interface MqttClient {
        /** Subscribe and start delivering messages to {@code onMessage}. */
        void subscribe(String topic, BiConsumer<String, byte[]> onMessage);
        /** Unsubscribe — best-effort. */
        void unsubscribe(String topic);
        /** Close the underlying connection. */
        void close();
    }

    private final InboundSubscriptionRegistry registry;
    private final InboundDispatchService dispatch;
    /** Broker URL → client. Production keeps one per broker; tests share a stub. */
    private final Function<String, MqttClient> clientFactory;
    private final ConcurrentHashMap<String, MqttClient> clients = new ConcurrentHashMap<>();

    public MqttListener(InboundSubscriptionRegistry registry,
                          InboundDispatchService dispatch,
                          Function<String, MqttClient> clientFactory) {
        this.registry = registry;
        this.dispatch = dispatch;
        this.clientFactory = clientFactory != null ? clientFactory : _ -> null;
    }

    public Map<String, Object> subscribe(String itemId, String agentId, String broker,
                                           String topic, String hookName,
                                           Map<String, Object> opts) {
        if (broker == null || broker.isBlank()) {
            return Map.of("ok", false, "error", "broker required");
        }
        if (topic == null || topic.isBlank()) {
            return Map.of("ok", false, "error", "topic required");
        }
        var combined = new LinkedHashMap<String, Object>();
        if (opts != null) combined.putAll(opts);
        combined.put("broker", broker);
        combined.put("topic", topic);
        var subId = registry.add(itemId, agentId, "mqtt", hookName, topic, combined, null,
            opts == null ? null : (opts.get("capPerHour") instanceof Number n ? n.intValue() : null));
        var client = clients.computeIfAbsent(broker, clientFactory);
        if (client == null) {
            log.warn("mqtt: no client for broker={}, subscription registered but inactive", broker);
            return Map.of("ok", true, "subscriptionId", subId, "warning", "client_unavailable");
        }
        try {
            client.subscribe(topic, (t, payload) -> deliver(subId, t, payload));
        } catch (Exception e) {
            log.warn("mqtt subscribe failed: {}", e.getMessage());
            return Map.of("ok", true, "subscriptionId", subId, "warning", e.getMessage());
        }
        return Map.of("ok", true, "subscriptionId", subId);
    }

    private void deliver(String subId, String topic, byte[] payload) {
        var bodyStr = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
        Object decoded = bodyStr;
        if (!bodyStr.isEmpty() && (bodyStr.charAt(0) == '{' || bodyStr.charAt(0) == '[')) {
            try {
                decoded = new ObjectMapper().readValue(bodyStr, Object.class);
            } catch (Exception e) {
                // raw string fallback
            }
        }
        var event = InboundEvent.of("mqtt", topic, Map.of(
            "topic", topic,
            "body", decoded,
            "rawBody", bodyStr));
        dispatch.dispatch(subId, event);
    }

    /** Test/operator hook — synthesise an incoming message for one subscription. */
    public void deliverTestMessage(String subscriptionId, String topic, byte[] payload) {
        deliver(subscriptionId, topic, payload);
    }

    public void disarm(String subscriptionId) {
        var sub = registry.find(subscriptionId).orElse(null);
        if (sub == null) return;
        var broker = String.valueOf(sub.opts().getOrDefault("broker", ""));
        var topic = sub.target();
        var client = clients.get(broker);
        if (client != null) {
            try { client.unsubscribe(topic); } catch (Exception _) {}
        }
    }

    /** Test-only — drop all clients (forces a fresh connection on next subscribe). */
    public void closeAll() {
        for (var c : clients.values()) {
            try { c.close(); } catch (Exception _) {}
        }
        clients.clear();
    }
}
