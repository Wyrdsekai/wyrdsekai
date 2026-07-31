package org.wyrdsekai.between.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.core.recipe.RecipeContext;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeScheduler;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.recipe.ResourceRequirement;
import org.wyrdsekai.core.recipe.ResourceRequisiteGate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Local-first, peer-fallback recipe dispatcher ( resource-requisites
 * option b — "borrow a trusted peer zone"). Implements the core
 * {@link RecipeScheduler.Dispatcher} seam so the scheduler is oblivious to whether
 * a run happened here or on a peer.
 *
 * <p>On {@link #dispatch}:
 * <ol>
 *   <li>Run locally via the wrapped {@code delegate} (which applies the local
 *       resource preflight).</li>
 *   <li>If the local run is anything other than {@code RESOURCE_DENIED} — success,
 *       a normal failure, or no-service — return it unchanged.</li>
 *   <li>If the local run was blocked for want of hardware, screen the gossiped
 *       <em>trusted</em> peers against the recipe's {@code requires:} and, if one
 *       is eligible, borrow it: the peer runs the recipe and its result is mapped
 *       back into a {@link RecipeService.StartedRun}.</li>
 *   <li>If no trusted peer is eligible, return the local {@code RESOURCE_DENIED}
 *       unchanged — so the steward-ask path (option a) still fires.</li>
 * </ol>
 * </p>
 *
 * <p>The peer screening is intentionally cheap: it builds a
 * {@link ResourceRequisiteGate.Snapshot} from a peer's last-gossiped
 * {@link NodeResources} and reuses the very same gate that blocked us locally.
 * Disk and data-file presence can't be known from gossip, so they're assumed
 * adequate during screening — the lender's own preflight re-checks for real and
 * can still refuse (e.g. if the data files aren't synced there).</p>
 */
public final class CrossZoneRecipeDispatcher implements RecipeScheduler.Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(CrossZoneRecipeDispatcher.class);

    private final RecipeScheduler.Dispatcher delegate;
    private final NatsRecipeClient client;
    private final String sourceZone;
    private final Supplier<Map<String, NodeResources>> peerInventory;
    private final Predicate<String> trustedZone;
    private final Function<String, RecipeManifest> manifestResolver;
    private final long borrowTimeoutSec;

    public CrossZoneRecipeDispatcher(RecipeScheduler.Dispatcher delegate,
                                     NatsRecipeClient client,
                                     String sourceZone,
                                     Supplier<Map<String, NodeResources>> peerInventory,
                                     Predicate<String> trustedZone,
                                     Function<String, RecipeManifest> manifestResolver,
                                     long borrowTimeoutSec) {
        this.delegate = delegate;
        this.client = client;
        this.sourceZone = sourceZone;
        this.peerInventory = peerInventory;
        this.trustedZone = trustedZone;
        this.manifestResolver = manifestResolver;
        this.borrowTimeoutSec = borrowTimeoutSec;
    }

    @Override
    public RecipeService.StartedRun dispatch(String agentDid, String recipeName,
                                             Map<String, Object> params) {
        RecipeService.StartedRun local = delegate.dispatch(agentDid, recipeName, params);

        // Only intervene when the local node was blocked purely for want of resources.
        if (local == null || local.run() == null
                || local.run().status() != RecipeRunner.Status.RESOURCE_DENIED) {
            return local;
        }

        RecipeManifest manifest = safeResolve(recipeName);
        if (manifest == null || manifest.requires().isEmpty()) {
            return local; // nothing to screen peers against — fall back to steward ask.
        }

        String peer = chooseEligiblePeer(manifest);
        if (peer == null) {
            log.info("No trusted peer can satisfy '{}' — leaving RESOURCE_DENIED for the steward ask", recipeName);
            return local;
        }

        log.info("Borrowing '{}' from trusted peer zone '{}' (local node can't meet its requisites)",
            recipeName, peer);
        var req = NatsRecipeClient.build(sourceZone, agentDid, recipeName, params,
            local.run().resourceDenial() != null ? local.run().resourceDenial().summary() : null);
        try {
            var resp = client.borrow(peer, req)
                .get(borrowTimeoutSec, TimeUnit.SECONDS);
            return mapResponse(recipeName, resp, local);
        } catch (Exception e) {
            log.warn("Cross-zone borrow of '{}' from '{}' failed: {} — falling back to steward ask",
                recipeName, peer, e.toString());
            return local;
        }
    }

    /** Screen trusted peers; return the first whose gossiped resources satisfy the requires. */
    private String chooseEligiblePeer(RecipeManifest manifest) {
        Map<String, NodeResources> peers = peerInventory.get();
        if (peers == null || peers.isEmpty()) return null;
        for (var entry : peers.entrySet()) {
            String zone = entry.getKey();
            if (zone == null || zone.equals(sourceZone) || !trustedZone.test(zone)) continue;
            var snap = peerSnapshot(entry.getValue(), manifest.requires());
            var decision = ResourceRequisiteGate.evaluate(manifest.requires(), snap);
            if (decision.allow()) return zone;
        }
        return null;
    }

    /**
     * Build a screening snapshot from a peer's gossiped {@link NodeResources}.
     * GPU VRAM is split evenly across the advertised card count; disk and the
     * declared data-file / cloud-key targets are assumed present (the lender's
     * own preflight is authoritative for those).
     */
    static ResourceRequisiteGate.Snapshot peerSnapshot(NodeResources r,
                                                       List<ResourceRequirement> requires) {
        List<Double> gpuVramGb = new ArrayList<>();
        if (r != null && r.gpuModels() != null && !r.gpuModels().isEmpty() && r.vramMb() > 0) {
            int cards = r.gpuModels().size();
            double perCardGb = (r.vramMb() / 1024.0) / cards;
            for (int i = 0; i < cards; i++) gpuVramGb.add(perCardGb);
        }
        double freeRamGb = r != null ? r.ramMb() / 1024.0 : 0.0;

        Set<String> files = requires.stream()
            .filter(req -> req.kind() == ResourceRequirement.Kind.DATA_FILE && req.target() != null)
            .map(ResourceRequirement::target)
            .collect(Collectors.toSet());
        Set<String> envKeys = requires.stream()
            .filter(req -> req.kind() == ResourceRequirement.Kind.CLOUD_KEY && req.target() != null)
            .map(ResourceRequirement::target)
            .collect(Collectors.toSet());

        // Disk unknown from gossip → assume adequate at screening time.
        return new ResourceRequisiteGate.Snapshot(
            gpuVramGb, freeRamGb, Double.MAX_VALUE, files, envKeys);
    }

    /** Map a lender's {@link NatsRecipeProtocol.Response} into a local StartedRun. */
    private RecipeService.StartedRun mapResponse(String recipeName,
                                                NatsRecipeProtocol.Response resp,
                                                RecipeService.StartedRun local) {
        if (resp == null || !resp.ok()) {
            // Transport/lender failure — keep the local RESOURCE_DENIED so the steward ask fires.
            return local;
        }
        RecipeRunner.Status status = parseStatus(resp.status());
        var ctx = new RecipeContext(buildBorrowContext(resp));
        var run = new RecipeRunner.RecipeRun(status,
            "[borrowed from " + resp.lenderZone() + "] "
                + (resp.message() == null ? "" : resp.message()),
            List.of(), ctx);
        String runId = resp.runId() != null ? resp.runId() : UUID.randomUUID().toString();
        log.info("Borrowed run of '{}' on '{}' returned status={} (runId={})",
            recipeName, resp.lenderZone(), status, runId);
        return new RecipeService.StartedRun(runId, run);
    }

    private static Map<String, Object> buildBorrowContext(NatsRecipeProtocol.Response resp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("borrowed_from", resp.lenderZone());
        if (resp.runId() != null) m.put("remote_run_id", resp.runId());
        return m;
    }

    /** "DENIED" or unknown → ERROR; otherwise the named RecipeRunner.Status. */
    private static RecipeRunner.Status parseStatus(String s) {
        if (s == null) return RecipeRunner.Status.ERROR;
        try {
            return RecipeRunner.Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            return RecipeRunner.Status.ERROR;
        }
    }

    private RecipeManifest safeResolve(String recipeName) {
        try {
            return manifestResolver.apply(recipeName);
        } catch (Exception e) {
            log.debug("manifest resolve for '{}' failed: {}", recipeName, e.toString());
            return null;
        }
    }
}
