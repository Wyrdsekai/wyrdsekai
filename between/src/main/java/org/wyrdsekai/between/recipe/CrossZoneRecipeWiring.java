package org.wyrdsekai.between.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.layer.NodeCapabilities;
import org.wyrdsekai.between.layer.ResourceRegistry;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeScheduler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Production wiring for the cross-zone recipe-borrow path (
 * resource-requisites, option b). Lives in {@code between} because it bridges
 * the relay transport + federation trust + peer gossip (all {@code between}/
 * {@code common}) with the core {@link RecipeScheduler.Dispatcher} seam.
 *
 * <p>The transport connects <em>after</em> the scheduler boots, so the
 * {@link #decorator()} resolves the transport lazily on each dispatch: until the
 * relay is up it's a pass-through (local-only), and once connected it wraps the
 * local dispatcher with {@link CrossZoneRecipeDispatcher}. The lender side
 * ({@link NatsRecipeServer}) is started separately via {@link #startLender}
 * once the transport is connected.</p>
 *
 * <p>Trust is the federation bilateral agreement: a peer zone is borrowable
 * (and, on the lender side, allowed to borrow) only when
 * {@link FederationService#getAgreement} returns an agreement. Peers are keyed
 * by the gossiped node id; on a single-node-per-zone household this equals the
 * zone id, and where it doesn't the trust check fails closed (declines to
 * borrow → falls back to the steward ask, option a). Conservative by design.</p>
 */
public final class CrossZoneRecipeWiring {

    private static final Logger log = LoggerFactory.getLogger(CrossZoneRecipeWiring.class);

    private final String localZone;
    private final Supplier<RelaySessionTransport> transportSupplier;
    private final FederationService federation;
    private final Function<String, RecipeManifest> manifestResolver;
    private final long borrowTimeoutSec;

    /** One client per transport instance (rebuilt transparently across reconnects). */
    private final Map<RelaySessionTransport, NatsRecipeClient> clientCache = new ConcurrentHashMap<>();
    private volatile NatsRecipeServer lender;

    public CrossZoneRecipeWiring(String localZone,
                                 Supplier<RelaySessionTransport> transportSupplier,
                                 FederationService federation,
                                 Function<String, RecipeManifest> manifestResolver,
                                 long borrowTimeoutSec) {
        this.localZone = localZone;
        this.transportSupplier = transportSupplier;
        this.federation = federation;
        this.manifestResolver = manifestResolver;
        this.borrowTimeoutSec = borrowTimeoutSec;
    }

    /** Trust predicate: a standing bilateral agreement with the peer zone. */
    public boolean isTrusted(String peerZone) {
        if (peerZone == null || peerZone.equals(localZone)) return false;
        try {
            return federation.getAgreement(localZone, peerZone).isPresent();
        } catch (Exception e) {
            return false; // fail closed
        }
    }

    /** Peer inventory adapter: gossiped {@link NodeCapabilities.Snapshot} → zone-keyed {@link NodeResources}. */
    public Map<String, NodeResources> peerInventory() {
        Map<String, NodeResources> out = new LinkedHashMap<>();
        try {
            for (var e : ResourceRegistry.get().allSnapshots().entrySet()) {
                var s = e.getValue();
                if (s == null) continue;
                List<String> gpus = s.gpuVramMb() > 0 && s.gpuName() != null && !s.gpuName().isBlank()
                    ? List.of(s.gpuName()) : List.of();
                out.put(e.getKey(), new NodeResources(
                    s.gpuVramMb(), s.ramFreeMb(), gpus,
                    /* inferenceModels */ List.of(), /* loadPct */ 0.0, /* roomSlots */ 0));
            }
        } catch (Exception ex) {
            log.debug("peerInventory snapshot failed: {}", ex.toString());
        }
        return out;
    }

    /**
     * The decorator handed to {@link org.wyrdsekai.core.recipe.RecipeSchedulerBoot.BootArgs}.
     * Lazily resolves the transport so it's a no-op until the relay is connected.
     */
    public UnaryOperator<RecipeScheduler.Dispatcher> decorator() {
        Predicate<String> trust = this::isTrusted;
        Supplier<Map<String, NodeResources>> peers = this::peerInventory;
        return local -> (did, recipeName, params) -> {
            var transport = transportSupplier.get();
            if (transport == null || !transport.isConnected()) {
                return local.dispatch(did, recipeName, params); // relay not up → local only
            }
            var client = clientCache.computeIfAbsent(transport,
                t -> new NatsRecipeClient(t, borrowTimeoutSec));
            var crossZone = new CrossZoneRecipeDispatcher(local, client, localZone,
                peers, trust, manifestResolver, borrowTimeoutSec);
            return crossZone.dispatch(did, recipeName, params);
        };
    }

    /**
     * Start the lender side once the transport is connected. {@code executor}
     * runs a borrowed recipe locally (typically via this node's RecipeService)
     * and reports the outcome. Idempotent — a second call replaces the listener.
     */
    public synchronized void startLender(NatsRecipeServer.BorrowExecutor executor) {
        var transport = transportSupplier.get();
        if (transport == null || !transport.isConnected()) {
            log.warn("CrossZoneRecipeWiring.startLender: transport not connected — lender not started");
            return;
        }
        if (lender != null) lender.close();
        lender = new NatsRecipeServer(transport, localZone, this::isTrusted, executor);
        lender.start();
    }

    public void stopLender() {
        if (lender != null) { lender.close(); lender = null; }
    }
}
