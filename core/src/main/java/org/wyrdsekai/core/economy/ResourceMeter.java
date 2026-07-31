package org.wyrdsekai.core.economy;

import org.apache.pekko.actor.typed.ActorRef;

import java.time.Instant;

/**
 * Utility for recording resource usage to the Counting House.
 * Thread-safe — sends fire-and-forget messages to the CountingHouseActor.
 * Optionally normalizes costs via ComputeUnitNormalizer.
 */
public final class ResourceMeter {

    private final ActorRef<CountingHouseCommand> countingHouse;
    private final ComputeUnitNormalizer normalizer;

    public ResourceMeter(ActorRef<CountingHouseCommand> countingHouse) {
        this(countingHouse, null);
    }

    public ResourceMeter(ActorRef<CountingHouseCommand> countingHouse,
                         ComputeUnitNormalizer normalizer) {
        this.countingHouse = countingHouse;
        this.normalizer = normalizer;
    }

    /**
     * Record inference token usage.
     */
    public void recordInference(String agentId, String model,
                                int promptTokens, int completionTokens) {
        countingHouse.tell(new CountingHouseCommand.RecordUsage(
            new ResourceUsage(agentId, "inference",
                promptTokens, completionTokens, model, Instant.now())
        ));
    }

    /**
     * Get the normalized compute unit cost for an inference.
     * Returns raw token count if no normalizer is configured.
     *
     * @param tier   model tier (phone, edge, desktop, cluster, external)
     * @param tokens total tokens consumed
     * @return compute units (CU)
     */
    public double normalizedCost(String tier, int tokens) {
        if (normalizer == null) return tokens;
        return normalizer.toCU(tier, tokens);
    }

    /** Get the normalizer (may be null). */
    public ComputeUnitNormalizer normalizer() {
        return normalizer;
    }
}
