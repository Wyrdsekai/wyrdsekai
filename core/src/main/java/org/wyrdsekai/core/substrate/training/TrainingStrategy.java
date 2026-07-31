package org.wyrdsekai.core.substrate.training;

import java.util.List;

/**
 * The chosen plan for how a deep-sleep training cycle will execute.
 *
 * <p>Sealed interface — each variant is one of the five strategies, with
 * variant-specific parameters. The {@link TrainingStrategySelector} picks
 * one; {@link TrainingExecutor#forStrategy} dispatches to a concrete
 * executor.</p>
 *
 * <p>Why a sealed interface and not an enum: each strategy carries
 * different parameters (which containers to pause, which peer to route to,
 * which cloud provider to use). Records-as-variants give us pattern
 * matching + per-strategy data without inventing a parallel parameter
 * struct.</p>
 */
public sealed interface TrainingStrategy {

    /**
     * Train alongside running inference — no pause. Used when the node has
     * enough VRAM headroom that training fits without evicting anything.
     */
    record LocalParallel() implements TrainingStrategy {}

    /**
     * Pause specified inference containers, run training, resume. Used when
     * VRAM is tight enough that inference + training don't co-exist but
     * training alone fits.
     *
     * @param containersToPause docker container names (or wyrd-cli backend
     *   identifiers) that the executor should stop before training and
     *   restart afterward. The list lets the selector decide nuance —
     *   pause all, pause just the heaviest, pause only voice.
     */
    record LocalSerial(List<String> containersToPause) implements TrainingStrategy {}

    /**
     * Ship corpus to a peer node that has spare GPU, run training there,
     * ship the resulting adapter back. Used when a peer outclasses this
     * host or when the local node is CPU-class.
     *
     * @param peerNodeId routing identifier from ResourceRegistry
     * @param peerZone   peer's zone (cross-zone routing key)
     */
    record PeerDelegated(String peerNodeId, String peerZone) implements TrainingStrategy {}

    /**
     * Use a cloud LLM to distil voice-shaped (prompt, response) pairs from
     * the corpus, then train a small local adapter on those pairs. Used
     * when there's no local GPU and no reachable training peer, but a
     * cloud API key is configured.
     *
     * @param cloudProvider e.g. "anthropic", "openai" — determines which
     *                      ApiProvider the distill step uses
     */
    record CloudDistilled(String cloudProvider) implements TrainingStrategy {}

    /**
     * No training this cycle. Logged + retried next cycle.
     *
     * @param reason short human-readable reason, surfaced in logs and
     *               soak reports. e.g. "no GPU; LOCAL_FORBIDDEN; cycle 3"
     */
    record Skip(String reason) implements TrainingStrategy {}

    /** Short label for logging / metrics. */
    default String label() {
        return switch (this) {
            case LocalParallel  __ -> "local-parallel";
            case LocalSerial    s  -> "local-serial(" + s.containersToPause().size() + " paused)";
            case PeerDelegated  p  -> "peer-delegated(" + p.peerNodeId() + ")";
            case CloudDistilled c  -> "cloud-distilled(" + c.cloudProvider() + ")";
            case Skip           s  -> "skip: " + s.reason();
        };
    }
}
