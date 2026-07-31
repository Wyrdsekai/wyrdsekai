package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Routes tell messages across zones via NATS relay.
 *
 * When a tell target is not found in the local EntityRegistry,
 * this service routes the message to the appropriate zone via federation subjects.
 *
 * NATS subjects:
 *   federation.{targetZone}.tell — deliver a tell to a zone
 *
 * Supports:
 *   "tell wyrd hello"         — local first, then player's home zone if traveling
 *   "tell alpha.wyrd hello"   — explicit zone prefix
 *   "tell my wyrd hello"      — always routes to player's home zone companion
 */
public final class CrossZoneTellService {

    private static final Logger log = LoggerFactory.getLogger(CrossZoneTellService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static volatile CrossZoneTellService instance;

    private final String localZoneId;
    /** Publishes a message to a NATS subject via relay. (subject, payload) */
    private volatile BiConsumer<String, byte[]> relayPublisher;
    /** Delivers a formatted tell line to a local player's session(s). */
    private volatile PlayerDeliverer playerDeliverer;

    /**
     * Delivers a formatted tell line to a local player's live session(s) —
     * any surface (WebSocket, SSH, Telnet). Rita re-verify 2026-07-11 (#29):
     * this used to be a {@code BiConsumer} whose acceptance said nothing
     * about whether ANY session actually received the line — the WS-only
     * deliverer silently no-oped for SSH players while callers treated the
     * call as delivered, which suppressed the teleport-and-speak fallback.
     * The boolean makes the contract honest: {@code true} ONLY when at
     * least one live session got the message.
     */
    @FunctionalInterface
    public interface PlayerDeliverer {
        /** @return true when the line reached at least one live session. */
        boolean deliver(String playerId, String text);
    }

    public CrossZoneTellService(String localZoneId) {
        this.localZoneId = localZoneId;
    }

    public static void init(String localZoneId) {
        instance = new CrossZoneTellService(localZoneId);
    }

    /**
     * Returns the global instance, or {@code null} if {@link #init(String)} has
     * not been called. A null return is a bootstrap bug — the only reason
     * production paths see one is that some entry point forgot to initialise
     * core services. Callers should not silently branch on null; they should
     * surface the condition.
     *
     * <p>We log at WARN on first uninitialised access (rate-limited to once
     * per minute per JVM) so the degradation is visible in logs instead of
     * presenting as a silent whisper fallback at the WebSocket layer.</p>
     */
    public static CrossZoneTellService get() {
        if (instance == null) {
            warnUninitialised();
        }
        return instance;
    }

    private static volatile long lastUninitialisedWarnMs = 0L;

    private static void warnUninitialised() {
        var now = System.currentTimeMillis();
        if (now - lastUninitialisedWarnMs < 60_000) return;
        lastUninitialisedWarnMs = now;
        log.warn("CrossZoneTellService.get() called before init() — tell routing will fall back to whisper-in-room. "
            + "This is a bootstrap bug; ensure CoreServices.init(zoneId) runs before handling WebSocket traffic.");
    }

    /** Set the relay publisher (wired after RelaySessionTransport connects). */
    public void setRelayPublisher(BiConsumer<String, byte[]> publisher) {
        this.relayPublisher = publisher;
    }

    /** Set the player deliverer — delivers tell text to a player's session(s) by playerId. */
    public void setPlayerDeliverer(PlayerDeliverer deliverer) {
        this.playerDeliverer = deliverer;
    }

    /** True when a player-session deliverer is wired (server boot done). */
    public boolean hasPlayerDeliverer() {
        return playerDeliverer != null;
    }

    /**
     * Deliver a tell-back line directly to a local player's session by entity
     * id — the same "[from X] …" wire format {@link #handleIncomingTell} uses
     * for cross-zone tells to players. Rita campaign 2026-07-11 (#27): a
     * companion's reply to a `tell` used to be spoken only into the
     * companion's CURRENT room, so a sender in another room/session never saw
     * the answer. This is the mirror leg: replies ride the sender's session,
     * exactly like an incoming cross-zone tell does.
     *
     * @return true when the deliverer reports the line actually reached at
     *         least one live session. Rita re-verify 2026-07-11 (#29): this
     *         used to return true whenever the deliverer was merely INVOKED,
     *         so an SSH-only player (no WS session) got a silent no-op that
     *         still suppressed the caller's teleport-and-speak fallback —
     *         worse than pre-fix. Callers may rely on false ⇒ nothing was
     *         delivered and a fallback is required.
     */
    public boolean deliverToPlayerSession(String playerId, String fromName, String text) {
        var deliverer = playerDeliverer;
        if (deliverer == null || playerId == null || playerId.isBlank()) return false;
        try {
            boolean delivered = deliverer.deliver(playerId, "[from " + fromName + "] " + text);
            if (delivered) {
                log.info("Tell-back from '{}' delivered to player session {}", fromName, playerId);
            } else {
                log.info("Tell-back from '{}' NOT delivered — no live session for player {}",
                    fromName, playerId);
            }
            return delivered;
        } catch (Exception e) {
            log.warn("Tell-back to player session {} failed: {}", playerId, e.getMessage());
            return false;
        }
    }

    /**
     * Optional tell-scope gate. When set, {@link #handleIncomingTell} uses it
     * to enforce (cross-zone tells only deliver if the
     * target is in-room with sender or if the sender's zone holds a
     * bilateral contract with tell-scope). When unset, pre-Wave-7 behaviour
     * is preserved (all incoming tells delivered) — this lets deployments
     * adopt enforcement incrementally. See {@code TellScopeGate.ContractLookup}.
     */
    public void setContractLookup(TellScopeGate.ContractLookup lookup) {
        this.contractLookup = lookup;
    }

    private volatile TellScopeGate.ContractLookup contractLookup;

    /**
     * Attempt to deliver a tell, routing cross-zone if needed.
     *
     * @param fromEntityId   sender entity ID
     * @param fromEntityName sender display name
     * @param fromZoneId     sender's current zone
     * @param targetQuery    target query (may include zone prefix: "alpha.wyrd")
     * @param text           message text
     * @return result of the tell attempt
     */
    public TellResult tell(String fromEntityId, String fromEntityName,
                           String fromZoneId, String targetQuery, String text) {
        return tell(fromEntityId, fromEntityName, fromZoneId, targetQuery, text, null);
    }

    /**
     * Locale-aware overload — carries the sender's UI locale through so the
     * recipient companion can tag the synthesized Said event correctly for
     * the translate-route-translate hop. Pass null when unknown.
     */
    public TellResult tell(String fromEntityId, String fromEntityName,
                           String fromZoneId, String targetQuery, String text,
                           String senderLocale) {
        // Parse zone prefix: "alpha.wyrd" → zone="alpha", name="wyrd"
        var parsed = parseZonePrefix(targetQuery);

        // "tell my wyrd" → route to sender's home zone
        if ("my".equalsIgnoreCase(parsed.zone())) {
            var registry = EntityRegistry.get();
            var homeZone = registry != null ? registry.homeZoneOf(fromEntityId).orElse(null) : null;
            if (homeZone == null || homeZone.equals(localZoneId)) {
                // Home zone is local — deliver locally
                return deliverLocal(fromEntityId, fromEntityName, parsed.name(), text, senderLocale);
            }
            return routeToZone(homeZone, fromEntityId, fromEntityName, fromZoneId, parsed.name(), text);
        }

        // Explicit zone prefix: "alpha.wyrd"
        if (parsed.zone() != null) {
            if (parsed.zone().equals(localZoneId)) {
                return deliverLocal(fromEntityId, fromEntityName, parsed.name(), text, senderLocale);
            }
            return routeToZone(parsed.zone(), fromEntityId, fromEntityName, fromZoneId, parsed.name(), text);
        }

        // No prefix — try local first
        var localResult = deliverLocal(fromEntityId, fromEntityName, parsed.name(), text, senderLocale);
        if (localResult.delivered()) return localResult;
        // Found locally but non-agent — let caller handle room delivery.
        // (TellResult.targetEntityId is non-null when the name resolved.)
        if (localResult.targetEntityId() != null) return localResult;

        // Not found locally — if sender is a proxied visitor, try their home zone
        var registry = EntityRegistry.get();
        if (registry != null && fromZoneId != null && !fromZoneId.equals(localZoneId)) {
            // Sender is visiting from another zone — route to sender's home zone
            return routeToZone(fromZoneId, fromEntityId, fromEntityName, fromZoneId, parsed.name(), text);
        }

        return new TellResult(false, "Nobody called '" + parsed.name() + "' is online.", null);
    }

    /** Deliver a tell to a local entity. */
    private TellResult deliverLocal(String fromEntityId, String fromEntityName,
                                     String targetName, String text) {
        return deliverLocal(fromEntityId, fromEntityName, targetName, text, null);
    }

    /** Locale-aware overload (see {@link #tell(String, String, String, String, String, String)}). */
    private TellResult deliverLocal(String fromEntityId, String fromEntityName,
                                     String targetName, String text,
                                     String senderLocale) {
        var registry = EntityRegistry.get();
        var eventStream = AgentEventStream.get();
        if (registry == null) return new TellResult(false, "Entity registry not available.", null);

        var targetId = registry.findByName(targetName);
        if (targetId.isEmpty()) return new TellResult(false, null, null); // not found locally

        // Agent target — deliver via AgentEventStream
        if (eventStream != null && registry.isAgent(targetId.get())) {
            boolean delivered = eventStream.publishAgentMessage(
                fromEntityId, fromEntityName, targetId.get(),
                "[from " + fromEntityName + "] " + text,
                senderLocale);
            if (delivered) {
                log.info("Tell '{}' → agent {} delivered locally", targetName, targetId.get());
                return new TellResult(true, null, targetId.get());
            }
            // #32 item 4 (NEVER-SILENT): an agent target that was found but whose
            // event queue rejected the message must NOT fall through to the
            // "non-agent, caller does room delivery" shape — the caller would
            // print a success line for a tell nobody will ever process. Surface
            // the failure to the sender instead.
            log.warn("Tell '{}' → agent {} DROPPED — subscriber queue rejected the message",
                targetName, targetId.get());
            return new TellResult(false,
                "Couldn't hand your message to " + targetName + " just now — "
                + "they're overloaded. Please try again in a moment.", targetId.get());
        }

        // Non-agent target — fall through (caller handles room delivery)
        return new TellResult(false, null, targetId.get());
    }

    /** Route a tell to a remote zone via relay. */
    private TellResult routeToZone(String targetZone, String fromEntityId, String fromEntityName,
                                    String fromZone, String targetName, String text) {
        if (relayPublisher == null) {
            log.warn("Cannot route tell to zone '{}' — relay not connected", targetZone);
            return new TellResult(false, "Cross-zone tell not available (relay not connected).", null);
        }

        // outbound tell-scope enforcement. Symmetric to
        // the incoming check in handleIncomingTell: if a contractLookup is
        // wired, reject outbound cross-zone tells when the sender's zone
        // doesn't have a contract with the target zone. Avoids silently
        // burning relay bandwidth on tells the receiver will drop anyway.
        // targetEntityId is unknown at this point (the receiver resolves the
        // name), so we pass the name — the Phase-1 lookup in server wiring
        // only checks zone-level agreement and ignores the entity parameter.
        if (contractLookup != null) {
            var senderZone = fromZone != null ? fromZone : localZoneId;
            if (!senderZone.equals(targetZone)
                    && !contractLookup.hasTellScope(senderZone, targetZone, targetName)) {
                log.warn("Outbound tell to {}@{} REJECTED: no contract with target zone (SPEC §6.9)",
                    targetName, targetZone);
                return new TellResult(false,
                    "No contract with zone '" + targetZone + "' — cross-zone tell denied.", null);
            }
        }

        try {
            var payload = MAPPER.createObjectNode();
            payload.put("fromEntityId", fromEntityId);
            payload.put("fromEntityName", fromEntityName);
            payload.put("fromZone", fromZone != null ? fromZone : localZoneId);
            payload.put("targetName", targetName);
            payload.put("text", text);
            payload.put("timestamp", System.currentTimeMillis());

            var subject = "federation." + targetZone + ".tell";
            relayPublisher.accept(subject, MAPPER.writeValueAsBytes(payload));
            log.info("Tell '{}' routed to zone '{}' via relay", targetName, targetZone);
            return new TellResult(true, null, null);
        } catch (Exception e) {
            log.error("Failed to route tell to zone '{}': {}", targetZone, e.getMessage());
            return new TellResult(false, "Failed to route cross-zone tell.", null);
        }
    }

    /**
     * Handle an incoming cross-zone tell from the relay.
     * Called by FederationActor when it receives a federation.{localZone}.tell message.
     */
    public void handleIncomingTell(String fromEntityId, String fromEntityName,
                                    String fromZone, String targetName, String text) {
        log.info("Incoming cross-zone tell from {}@{} to '{}'", fromEntityName, fromZone, targetName);

        // tell-scope enforcement. Only runs when a
        // contractLookup is wired — deployments opt-in by calling
        // setContractLookup(...) at init. Without it, we preserve pre-Wave-7
        // behaviour (no gating) so an upgrade doesn't silently drop tells
        // that were working yesterday.
        if (contractLookup != null) {
            var registry = EntityRegistry.get();
            if (registry != null) {
                // Resolve the target to an entity id — needed for the gate.
                // If the target can't be found we'll fail through to
                // deliverLocal's own lookup (same semantics as before).
                var targetId = registry.findByName(targetName).orElse(null);
                if (targetId != null) {
                    var decision = TellScopeGate.check(
                        fromEntityId, fromZone,
                        targetId, localZoneId,
                        registry, contractLookup);
                    if (decision instanceof TellScopeGate.Decision.Deny deny) {
                        log.warn("Cross-zone tell REJECTED by scope gate: from={}@{} to='{}' reason='{}'",
                            fromEntityName, fromZone, targetName, deny.reason());
                        return;
                    }
                }
            }
        }

        var result = deliverLocal(fromEntityId, fromEntityName, targetName, text);
        if (result.delivered()) return;

        // deliverLocal returns (delivered=false, targetEntityId=<id>) when the target exists
        // locally but is a player (non-agent). Route to their WS session via the deliverer hook.
        if (result.targetEntityId() != null && playerDeliverer != null) {
            var formatted = "[from " + fromEntityName + "@" + fromZone + "] " + text;
            boolean sessionDelivered = playerDeliverer.deliver(result.targetEntityId(), formatted);
            if (sessionDelivered) {
                log.info("Cross-zone tell from {}@{} delivered to player {}",
                    fromEntityName, fromZone, result.targetEntityId());
            } else {
                log.warn("Cross-zone tell from {}@{} to player {} — no live session took delivery",
                    fromEntityName, fromZone, result.targetEntityId());
            }
            return;
        }

        log.warn("Cross-zone tell from {}@{} to '{}' — target not found locally",
            fromEntityName, fromZone, targetName);
    }

    /** Parse zone prefix from "alpha.wyrd" → (zone=alpha, name=wyrd). */
    static ParsedTarget parseZonePrefix(String query) {
        if (query == null || query.isBlank()) return new ParsedTarget(null, "");
        var trimmed = query.trim();

        // "my wyrd" → zone="my", name="wyrd"
        if (trimmed.toLowerCase().startsWith("my ")) {
            return new ParsedTarget("my", trimmed.substring(3).trim());
        }

        // "alpha.wyrd" → zone="alpha", name="wyrd"
        var dotIdx = trimmed.indexOf('.');
        if (dotIdx > 0 && dotIdx < trimmed.length() - 1) {
            var prefix = trimmed.substring(0, dotIdx);
            var name = trimmed.substring(dotIdx + 1);
            // Don't confuse with MUD ordinals (2.sword) — check if prefix is numeric
            try {
                Integer.parseInt(prefix);
                // It's a number — this is an ordinal, not a zone prefix
                return new ParsedTarget(null, trimmed);
            } catch (NumberFormatException e) {
                // Not a number — it's a zone prefix
                return new ParsedTarget(prefix, name);
            }
        }

        return new ParsedTarget(null, trimmed);
    }

    record ParsedTarget(String zone, String name) {}
    public record TellResult(boolean delivered, String errorMessage, String targetEntityId) {}
}
