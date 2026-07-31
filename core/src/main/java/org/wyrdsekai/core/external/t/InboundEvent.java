package org.wyrdsekai.core.external.t;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * a single inbound event to be delivered
 * to a subscribed scripted item's hook callback.
 *
 * <p>Listeners (webhook, RSS poller, IMAP, MQTT, file watcher, etc.) build
 * one of these per upstream event and hand it to
 * {@link InboundDispatchService#dispatch}. The service consults
 * {@link InboundSubscriptionRegistry}, applies per-subscription rate limits,
 * and invokes the item's named hook function via {@link HookCallbackInvoker}.</p>
 *
 * <p>Fields mirror the event-shape from §4.34 prose:
 * <ul>
 *   <li>{@code kind} — one of {@code webhook|rss|atom|email|mqtt|file_watch|sse|websocket|scheduled|slack|github|...}.
 *       Matches the {@code world.inbound.<kind>} surface that minted the
 *       subscription.</li>
 *   <li>{@code source} — provider-specific identifier (URL, broker, repo, etc.)
 *       so items can re-issue the call without losing context.</li>
 *   <li>{@code payload} — the event body, already decoded into Java types
 *       (Map / List / String / Number / Boolean) so the GraalJS sandbox sees
 *       it as a plain JS object.</li>
 *   <li>{@code timestamp} — when the listener observed the event (not the
 *       upstream timestamp; that goes inside {@code payload} when present).</li>
 *   <li>{@code correlationId} — opaque uuid so audit + duplicate-detect can
 *       follow a single delivery across the registry/dispatch/hook layers.</li>
 * </ul>
 */
public record InboundEvent(
    String kind,
    String source,
    Map<String, Object> payload,
    Instant timestamp,
    String correlationId
) {

    public InboundEvent {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind required");
        }
        source = source == null ? "" : source;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        timestamp = timestamp == null ? Instant.now() : timestamp;
        correlationId = correlationId == null || correlationId.isBlank()
            ? UUID.randomUUID().toString()
            : correlationId;
    }

    /** Convenience: minimal event with auto-stamped timestamp + correlation id. */
    public static InboundEvent of(String kind, String source, Map<String, Object> payload) {
        return new InboundEvent(kind, source, payload, Instant.now(), UUID.randomUUID().toString());
    }

    /**
     * JS-projection: shape the script's {@code onWebhook(event)} hook receives.
     * Keeps the four canonical fields from §4.34 plus the correlation id so
     * scripts can log / tie back to the runtime audit trail.
     */
    public Map<String, Object> toScriptObject() {
        var out = new LinkedHashMap<String, Object>();
        out.put("kind", kind);
        out.put("source", source);
        out.put("payload", payload);
        out.put("timestamp", timestamp.toEpochMilli());
        out.put("correlationId", correlationId);
        return out;
    }
}
