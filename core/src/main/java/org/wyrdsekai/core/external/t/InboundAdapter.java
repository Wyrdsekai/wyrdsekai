package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * (Phase T) — single
 * {@link ExternalAdapter} that backs the {@code world.inbound.*} surface.
 *
 * <p>Each {@code world.inbound.<kind>(...)} call dispatches to the matching
 * listener:
 * <ul>
 *   <li>{@code webhook} → {@link WebhookListener}</li>
 *   <li>{@code email_watch} → {@link EmailPollListener}</li>
 *   <li>{@code mqtt} → {@link MqttListener}</li>
 *   <li>{@code file_watch} → {@link FilesystemWatchListener}</li>
 *   <li>{@code scheduled} → {@link ScheduledListenerBridge}</li>
 * </ul>
 * Unified ops ({@code list}, {@code cancel}, {@code pause}, {@code resume})
 * route through {@link InboundSubscriptionRegistry} regardless of kind.</p>
 *
 * <p>Capability gating happens at the script-API layer ({@code ItemWorldApi})
 * via the per-cap dotted name (e.g. {@code inbound.webhook} maps to a Tier 5
 * cap). This adapter assumes the caller already passed the gate.</p>
 */
public final class InboundAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(InboundAdapter.class);

    private static final Set<String> METHODS = Set.of(
        "webhook", "email_watch", "mqtt", "file_watch", "scheduled",
        "list", "cancel", "pause", "resume"
    );

    private final InboundSubscriptionRegistry registry;
    private final WebhookListener webhookListener;
    private final EmailPollListener emailListener;
    private final MqttListener mqttListener;
    private final FilesystemWatchListener fsListener;
    private final ScheduledListenerBridge scheduledBridge;

    public InboundAdapter(InboundSubscriptionRegistry registry,
                           WebhookListener webhookListener,
                           EmailPollListener emailListener,
                           MqttListener mqttListener,
                           FilesystemWatchListener fsListener,
                           ScheduledListenerBridge scheduledBridge) {
        this.registry = registry;
        this.webhookListener = webhookListener;
        this.emailListener = emailListener;
        this.mqttListener = mqttListener;
        this.fsListener = fsListener;
        this.scheduledBridge = scheduledBridge;
    }

    @Override public String namespace() { return "inbound"; }
    @Override public Set<String> capabilities() { return METHODS; }
    @Override public String credentialSlot() { return null; }
    @Override public String providerApiVersion() { return "1.0"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        var args = request.args();
        var itemId = request.itemId();
        var agentId = stringArg(args, "agentId");
        var hookName = stringArg(args, "hookName");
        try {
            return switch (request.method()) {
                case "webhook"     -> wrap(webhookListener == null
                    ? Map.of("ok", false, "error", "webhook listener not wired")
                    : webhookListener.subscribe(itemId, agentId,
                        stringArg(args, "path"), hookName, mapArg(args, "opts")));
                case "email_watch" -> wrap(emailListener == null
                    ? Map.of("ok", false, "error", "email listener not wired")
                    : emailListener.subscribe(itemId, agentId,
                        mapArg(args, "filter"), hookName, mapArg(args, "opts")));
                case "mqtt"        -> wrap(mqttListener == null
                    ? Map.of("ok", false, "error", "mqtt listener not wired")
                    : mqttListener.subscribe(itemId, agentId,
                        stringArg(args, "broker"), stringArg(args, "topic"),
                        hookName, mapArg(args, "opts")));
                case "file_watch"  -> wrap(fsListener == null
                    ? Map.of("ok", false, "error", "fs listener not wired")
                    : fsListener.subscribe(itemId, agentId,
                        stringArg(args, "relPath"), hookName, mapArg(args, "opts")));
                case "scheduled"   -> wrap(scheduledBridge == null
                    ? Map.of("ok", false, "error", "scheduled bridge not wired")
                    : scheduledBridge.subscribe(itemId, agentId,
                        stringArg(args, "cronExpr"), hookName, mapArg(args, "opts")));
                case "list"        -> AdapterResponse.ok(registry.list(agentId));
                case "cancel"      -> {
                    var subId = stringArg(args, "subscriptionId");
                    var sub = registry.find(subId).orElse(null);
                    if (sub != null && "scheduled".equals(sub.kind()) && scheduledBridge != null) {
                        var ok = scheduledBridge.cancel(agentId, subId);
                        yield AdapterResponse.ok(Map.of("ok", ok));
                    }
                    if (sub != null && "file_watch".equals(sub.kind()) && fsListener != null) {
                        fsListener.disarm(subId);
                    }
                    if (sub != null && "email".equals(sub.kind()) && emailListener != null) {
                        emailListener.disarm(subId);
                    }
                    if (sub != null && "mqtt".equals(sub.kind()) && mqttListener != null) {
                        mqttListener.disarm(subId);
                    }
                    yield AdapterResponse.ok(Map.of("ok", registry.cancel(agentId, subId)));
                }
                case "pause"       -> AdapterResponse.ok(Map.of(
                    "ok", registry.pause(agentId, stringArg(args, "subscriptionId"))));
                case "resume"      -> AdapterResponse.ok(Map.of(
                    "ok", registry.resume(agentId, stringArg(args, "subscriptionId"))));
                default            -> AdapterResponse.fail("unknown_method",
                    request.method() + " not in inbound adapter", false);
            };
        } catch (Exception e) {
            log.warn("inbound adapter {} failed: {}", request.method(), e.getMessage());
            return AdapterResponse.fail("inbound_error", e.getMessage(), true);
        }
    }

    private static AdapterResponse wrap(Map<String, Object> result) {
        if (result == null) return AdapterResponse.ok(Map.of("ok", false));
        if (Boolean.FALSE.equals(result.get("ok"))) {
            var msg = String.valueOf(result.getOrDefault("error", "unknown"));
            return AdapterResponse.fail("subscribe_failed", msg, false);
        }
        return AdapterResponse.ok(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(Map<String, Object> args, String key) {
        var v = args.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    private static String stringArg(Map<String, Object> args, String key) {
        var v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Diagnostics helper — small map for the {@code wyrd inbound stats} CLI. */
    public List<Map<String, Object>> snapshot() {
        return registry.list(null);
    }
}
