package org.wyrdsekai.core.substrate.training;

import java.util.Comparator;
import java.util.List;

/**
 * Pure function: (this-node capacity, reachable peers, user policy) → strategy.
 *
 * <p>No IO, no actor refs, no logging side effects. Built so it can be
 * exhaustively unit-tested across the strategy space and so the same
 * decision logic runs identically in production and in regression suites.</p>
 *
 * <h3>Decision order</h3>
 * <ol>
 *   <li>{@code DISABLED} → always {@code Skip}.</li>
 *   <li>{@code PREFER_PEER} or {@code LOCAL_FORBIDDEN} → try peers first.</li>
 *   <li>{@code AUTO} / {@code PREFER_LOCAL}: if local can run parallel, do that.</li>
 *   <li>If local can run after pausing models, do that (pause heaviest first).</li>
 *   <li>Otherwise try peer delegation.</li>
 *   <li>Otherwise try cloud distillation (if {@code cloudAvailable}).</li>
 *   <li>Otherwise {@code Skip} with a diagnostic reason.</li>
 * </ol>
 *
 * <p>The {@code activeContainers} list lets the selector tell {@code LocalSerial}
 * which containers to pause. By default we pause all of them — but a future
 * smarter selector could choose just the largest one if pausing fewer
 * still frees enough VRAM.</p>
 */
public final class TrainingStrategySelector {

    private TrainingStrategySelector() {}

    /**
     * Pick the strategy.
     *
     * @param self            this node's capacity snapshot
     * @param peers           reachable peers (may be empty)
     * @param policy          steward preference
     * @param activeContainers names of currently-running inference containers
     *                         (passed to LocalSerial as the pause-list when chosen)
     * @param cloudAvailable  is a cloud API key + provider configured?
     */
    public static TrainingStrategy choose(
            NodeCapacity self,
            List<PeerCapacity> peers,
            UserTrainingPolicy policy,
            List<String> activeContainers,
            boolean cloudAvailable
    ) {
        if (policy == UserTrainingPolicy.DISABLED) {
            return new TrainingStrategy.Skip("policy=DISABLED");
        }

        var bestPeer = peers == null || peers.isEmpty() ? null
            : peers.stream()
                .filter(p -> p.canTrain(self.trainingEstimateGb()))
                .max(Comparator.comparingDouble(PeerCapacity::score))
                .orElse(null);

        // Peer-first policies — try peer before local, even if local could fit.
        if (policy == UserTrainingPolicy.PREFER_PEER || policy == UserTrainingPolicy.LOCAL_FORBIDDEN) {
            if (bestPeer != null) {
                return new TrainingStrategy.PeerDelegated(bestPeer.nodeId(), bestPeer.zone());
            }
            if (policy == UserTrainingPolicy.LOCAL_FORBIDDEN) {
                if (cloudAvailable) {
                    return new TrainingStrategy.CloudDistilled(defaultCloudProvider());
                }
                return new TrainingStrategy.Skip("LOCAL_FORBIDDEN + no peer + no cloud");
            }
            // PREFER_PEER falls through to local-when-no-peer
        }

        // Cloud-first policy — try cloud before local.
        if (policy == UserTrainingPolicy.PREFER_CLOUD) {
            if (cloudAvailable) {
                return new TrainingStrategy.CloudDistilled(defaultCloudProvider());
            }
            // fall through
        }

        // AUTO / PREFER_LOCAL / fallthroughs from above:
        // Try local strategies in order of preference (parallel > serial).
        if (self.canTrainInParallel()) {
            return new TrainingStrategy.LocalParallel();
        }
        if (self.canTrainAfterPause()) {
            // Pause every active inference container by default. A smarter
            // future selector could pick the minimum subset that frees
            // enough VRAM, but we prefer correctness over cleverness here:
            // pausing extras is harmless, leaving one running and OOMing is not.
            return new TrainingStrategy.LocalSerial(
                activeContainers == null ? List.of() : List.copyOf(activeContainers));
        }

        // Local won't fit — try peer.
        if (bestPeer != null) {
            return new TrainingStrategy.PeerDelegated(bestPeer.nodeId(), bestPeer.zone());
        }

        // No peer — try cloud.
        if (cloudAvailable) {
            return new TrainingStrategy.CloudDistilled(defaultCloudProvider());
        }

        // Nothing works.
        return new TrainingStrategy.Skip(
            String.format("no viable strategy: gpu=%s freeVram=%.1fGB trainingEst=%.1fGB peers=%d cloud=%s",
                self.hasGpu() ? "yes" : "no",
                self.freeGpuVramGb(),
                self.trainingEstimateGb(),
                peers == null ? 0 : peers.size(),
                cloudAvailable));
    }

    /** Default cloud provider when a CloudDistilled strategy is selected. */
    private static String defaultCloudProvider() {
        // Prefer Anthropic when both keys present — better at voice tasks.
        if (System.getenv("ANTHROPIC_API_KEY") != null) return "anthropic";
        if (System.getenv("OPENAI_API_KEY") != null) return "openai";
        return "anthropic";  // default name — actual call will fail gracefully if no key
    }
}
