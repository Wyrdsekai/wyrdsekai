package org.wyrdsekai.core.nostr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEventStream;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * route inbound Nostr events to local agents
 * as tells via {@link AgentEventStream#publishAgentMessage}.
 *
 * <p>The bridge maintains a map {@code nostrPubkeyHex -> localAgentId}. When a
 * subscribed relay forwards a kind:1 (short text note) event whose {@code p}
 * tag matches one of the registered pubkeys, the bridge synthesises a tell
 * from {@code sender@nostr → localAgent} and delivers it via the event stream.
 *
 * <p>Event mapping:
 * <ul>
 *   <li>{@code fromId}   = {@code "nostr:" + sender.pubkeyHex}</li>
 *   <li>{@code fromName} = {@code "npub1…" (bech32, truncated to first 12 chars)}</li>
 *   <li>{@code toId}     = the registered local agent id</li>
 *   <li>{@code message}  = event.content</li>
 *   <li>{@code locale}   = null (Nostr events carry no locale tag)</li>
 * </ul>
 *
 * <p>Signature verification is the caller's responsibility — the relay-pool
 * subscription path already drops events with invalid signatures, so by the
 * time we reach {@link #handleInbound}, the event has been verified.
 *
 * <p>The bridge does not auto-register itself with the relay pool. Wiring
 * (subscribe per-pubkey, route filter, etc.) lives in {@code Main} / the
 * companion lifecycle, which know which agents to subscribe for.
 */
public final class NostrInboundTellBridge {

    private static final Logger log = LoggerFactory.getLogger(NostrInboundTellBridge.class);

    /** Map: Nostr pubkey (32 bytes hex) → local agent id (entity id). */
    private final ConcurrentHashMap<String, String> pubkeyToAgentId = new ConcurrentHashMap<>();
    private final AgentMessagePublisher publisher;

    public NostrInboundTellBridge(AgentMessagePublisher publisher) {
        this.publisher = publisher;
    }

    /** Default constructor that delegates to {@link AgentEventStream#get}. */
    public NostrInboundTellBridge() {
        this((fromId, fromName, toId, message) ->
            AgentEventStream.get().publishAgentMessage(fromId, fromName, toId, message, null));
    }

    /**
     * Register a Nostr pubkey (hex) as belonging to a local agent. After this,
     * inbound kind:1 events tagged with {@code ["p", pubkey]} will be routed
     * to {@code agentId} as tells.
     */
    public void register(String pubkeyHex, String agentId) {
        if (pubkeyHex == null || pubkeyHex.isBlank()) throw new IllegalArgumentException("pubkey required");
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId required");
        pubkeyToAgentId.put(pubkeyHex.toLowerCase(), agentId);
        log.info("NostrInboundTellBridge registered pubkey {} → agent {}",
            shortPubkey(pubkeyHex), agentId);
    }

    public void unregister(String pubkeyHex) {
        if (pubkeyHex == null) return;
        pubkeyToAgentId.remove(pubkeyHex.toLowerCase());
    }

    public int registrationCount() { return pubkeyToAgentId.size(); }

    /**
     * Process an inbound Nostr event. Returns true iff the event matched a
     * registered local agent and a tell was delivered.
     */
    public boolean handleInbound(NostrEvent event) {
        if (event == null) return false;
        if (event.kind() != 1) {
            // Only kind:1 (short text notes) become tells. Other kinds (0
            // metadata, 4 DMs, 30023 long-form, etc.) are out of scope.
            return false;
        }
        var targetAgent = resolveTarget(event);
        if (targetAgent == null) return false;

        var sender = event.pubkey();
        var fromId = "nostr:" + sender;
        var fromName = bech32Npub(sender);
        var content = event.content();
        if (content == null) content = "";

        var delivered = publisher.publish(fromId, fromName, targetAgent, content);
        if (!delivered) {
            log.warn("NostrInboundTellBridge: no subscriber for agent {} (event {})",
                targetAgent, shortPubkey(event.id()));
        }
        return delivered;
    }

    /** Look up the local agent whose registered pubkey appears in a {@code p} tag. */
    private String resolveTarget(NostrEvent event) {
        if (event.tags() == null) return null;
        for (var tag : event.tags()) {
            if (tag == null || tag.size() < 2) continue;
            if (!"p".equals(tag.get(0))) continue;
            var pubkey = tag.get(1);
            if (pubkey == null) continue;
            var match = pubkeyToAgentId.get(pubkey.toLowerCase());
            if (match != null) return match;
        }
        return null;
    }

    /** Diagnostic snapshot — used by wyrd doctor and tests. */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(new HashMap<>(pubkeyToAgentId));
    }

    private static String bech32Npub(String pubkeyHex) {
        try {
            return Bech32.encode32("npub", HexFormat.of().parseHex(pubkeyHex));
        } catch (Exception e) {
            return "nostr:" + shortPubkey(pubkeyHex);
        }
    }

    private static String shortPubkey(String hex) {
        if (hex == null) return "?";
        return hex.length() <= 12 ? hex : hex.substring(0, 12) + "…";
    }

    /** Minimal seam for tests — avoids dragging in AgentEventStream. */
    @FunctionalInterface public interface AgentMessagePublisher {
        boolean publish(String fromId, String fromName, String toId, String message);
    }

    /** Convenience: build a bridge from a {@code Map<pubkey,agentId>} snapshot. */
    public static NostrInboundTellBridge fromMap(Map<String, String> initial,
                                                  AgentMessagePublisher publisher) {
        var bridge = new NostrInboundTellBridge(publisher);
        if (initial != null) {
            initial.forEach(bridge::register);
        }
        return bridge;
    }

    /** Static helper for tag construction — used by inbound listeners and tests. */
    public static List<String> pTag(String pubkeyHex) {
        return List.of("p", pubkeyHex);
    }
}
