package org.wyrdsekai.core.nostr;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import java.util.List;
import java.util.Map;

/**
 * register the {@link NostrAdapter} with the
 * {@link ExternalAdapterRegistry}.
 *
 * <p>Opt-in via {@code wyrdsekai.nostr.enabled = true}. When disabled (default),
 * the adapter is not registered and {@code world.nostr.publish(...)} calls
 * return {@code unknown_namespace} at the proxy layer.
 *
 * <p>Wired from {@code CoreServices.init()} after the other adapter bootstraps.
 * Holds the {@link NostrRelayPool} singleton so process shutdown can close it.
 */
public final class NostrAdapterBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NostrAdapterBootstrap.class);
    private static volatile boolean initialised = false;
    private static volatile NostrRelayPool activePool;
    private static volatile NostrAdapter activeAdapter;
    private static volatile NostrInboundTellBridge activeBridge;

    private NostrAdapterBootstrap() {}

    /**
     * Initialize from HOCON config. Reads {@code wyrdsekai.nostr.*}:
     * <ul>
     *   <li>{@code enabled} (boolean, default false)</li>
     *   <li>{@code publish_relays} (list of wss:// URLs)</li>
     *   <li>{@code max_events_per_minute} (int, default 60)</li>
     * </ul>
     *
     * @param config the root {@code wyrdsekai} HOCON config
     * @param seedResolver pluggable bridge from DID → Ed25519 seed (the
     *                     companion's private-key bytes). Production wiring
     *                     reads from the soul store; tests pass a lambda.
     */
    public static synchronized void init(Config config, NostrAdapter.SeedResolver seedResolver) {
        if (initialised) {
            log.debug("NostrAdapterBootstrap.init called twice — skipping");
            return;
        }
        Config nostrCfg = config.hasPath("nostr") ? config.getConfig("nostr") : null;
        if (nostrCfg == null || !nostrCfg.getBoolean("enabled")) {
            log.info("Nostr bridge disabled (wyrdsekai.nostr.enabled=false); adapter not registered");
            initialised = true;
            return;
        }
        var relays = nostrCfg.getStringList("publish_relays");
        if (relays.isEmpty()) {
            log.warn("Nostr bridge enabled but publish_relays is empty; adapter not registered");
            initialised = true;
            return;
        }
        var max = nostrCfg.hasPath("max_events_per_minute")
            ? nostrCfg.getInt("max_events_per_minute") : 60;

        var pool = new NostrRelayPool(List.copyOf(relays));
        pool.start();
        var adapter = new NostrAdapter(pool, seedResolver, max);
        ExternalAdapterRegistry.get().register(adapter);
        activePool = pool;
        activeAdapter = adapter;
        activeBridge = new NostrInboundTellBridge();
        Runtime.getRuntime().addShutdownHook(new Thread(pool::close, "nostr-pool-shutdown"));

        initialised = true;
        log.info("Nostr adapter registered: relays={}, max_events_per_minute={}",
            relays.size(), max);
    }

    /**
     * Subscribe a local agent for inbound tells from Nostr. Future {@code kind:1}
     * events with a {@code ["p", pubkeyHex]} tag will be delivered to this
     * agent via {@link org.wyrdsekai.core.agent.AgentEventStream}.
     *
     * <p>No-op if Nostr is disabled. Idempotent — calling twice for the same
     * (pubkey, agent) pair only registers once.
     *
     * @param pubkeyHex 64-char hex secp256k1 x-only pubkey
     * @param agentId   local entity id of the agent
     * @return a subscription id that can be passed to {@link #unsubscribeAgent}
     *         (returns null if Nostr is disabled)
     */
    public static synchronized String subscribeAgent(String pubkeyHex, String agentId) {
        if (activePool == null || activeBridge == null) {
            log.debug("subscribeAgent({}) — Nostr disabled, ignoring", agentId);
            return null;
        }
        activeBridge.register(pubkeyHex, agentId);
        var subId = "wyrd-inbound-" + agentId + "-" + Integer.toHexString(pubkeyHex.hashCode());
        var filter = Map.<String, Object>of(
            "kinds", List.of(1),
            "#p", List.of(pubkeyHex)
        );
        activePool.subscribe(subId, filter, new NostrRelayPool.NostrEventListener() {
            @Override public void onEvent(String relay, String sub, NostrEvent event) {
                try {
                    activeBridge.handleInbound(event);
                } catch (Exception e) {
                    log.warn("Nostr inbound dispatch failed for agent {}: {}",
                        agentId, e.getMessage());
                }
            }
        });
        return subId;
    }

    /** Drop an inbound subscription. No-op if Nostr is disabled. */
    public static synchronized void unsubscribeAgent(String pubkeyHex, String subId) {
        if (activeBridge != null && pubkeyHex != null) {
            activeBridge.unregister(pubkeyHex);
        }
        if (activePool != null && subId != null) {
            activePool.unsubscribe(subId);
        }
    }

    /** Accessor for diagnostics + tests. */
    public static NostrInboundTellBridge getInboundBridge() { return activeBridge; }

    /**
     * Upgrade the active adapter's seed resolver. Called by {@code Main} after
     * {@code NodeIdentity} is loaded to swap out the bootstrap-time placeholder
     * for a resolver that can produce real keys. No-op if Nostr is disabled.
     */
    public static synchronized void setSeedResolver(NostrAdapter.SeedResolver resolver) {
        if (activeAdapter != null) {
            activeAdapter.setSeedResolver(resolver);
            log.info("Nostr adapter SeedResolver upgraded ({} -> {})",
                "<placeholder>",
                resolver == null ? "<null>" : resolver.getClass().getSimpleName());
        }
    }

    /** Test-only — clear initialisation state. */
    public static synchronized void resetForTests() {
        if (activePool != null) {
            activePool.close();
            activePool = null;
        }
        activeAdapter = null;
        activeBridge = null;
        initialised = false;
    }

    /** Access the pool (mostly for diagnostics / wyrd doctor in Phase 2b). */
    public static NostrRelayPool getActivePool() { return activePool; }
}
