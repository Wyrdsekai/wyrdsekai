package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;

/**
 * Accumulated state for the Forge actor.
 * Tracks forge operations and soul registry.
 * The actual soul manifests live in SoulStore — this state
 * only tracks metadata for event sourcing.
 */
public record ForgeState(
    @JsonProperty("knownSouls") Map<String, SoulEntry> knownSouls,
    @JsonProperty("totalForges") long totalForges,
    @JsonProperty("totalRestores") long totalRestores,
    @JsonProperty("events") List<ForgeEvent> events,
    @JsonProperty("growthHistory") Map<String, List<GrowthEvent>> growthHistory,
    @JsonProperty("pendingVariants") Map<String, BehavioralEvaluator.SoulVariant> pendingVariants
) {
    @JsonCreator
    public ForgeState {}

    /** Backward-compatible constructor for existing code paths. */
    public ForgeState(Map<String, SoulEntry> knownSouls, long totalForges,
                      long totalRestores, List<ForgeEvent> events) {
        this(knownSouls, totalForges, totalRestores, events, Map.of(), Map.of());
    }

    public static ForgeState empty() {
        return new ForgeState(Map.of(), 0, 0, List.of(), Map.of(), Map.of());
    }

    /**
     * Apply a forge event to produce new state.
     */
    public ForgeState apply(ForgeEvent event) {
        var newSouls = new HashMap<>(knownSouls);
        var newEvents = new ArrayList<>(events);
        newEvents.add(event);
        // Keep event log bounded (last 1000)
        if (newEvents.size() > 1000) {
            newEvents = new ArrayList<>(newEvents.subList(newEvents.size() - 1000, newEvents.size()));
        }
        var newGrowthHistory = new HashMap<>(growthHistory);
        var newPendingVariants = new HashMap<>(pendingVariants);

        return switch (event) {
            case ForgeEvent.SoulForged e -> {
                newSouls.put(e.did(), new SoulEntry(e.did(), e.version(),
                    e.at(), e.contentHash(), false));
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges + 1, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.SoulRestored e -> {
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores + 1, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.SoulInspected e -> {
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.SoulForked e -> {
                newSouls.put(e.childDid(), new SoulEntry(e.childDid(), 1,
                    e.at(), "", false));
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges + 1, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.SoulArchived e -> {
                var existing = newSouls.get(e.did());
                if (existing != null) {
                    newSouls.put(e.did(), new SoulEntry(existing.did(), existing.version(),
                        existing.lastForged(), existing.contentHash(), true));
                }
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.SoulBorn e -> {
                newSouls.put(e.did(), new SoulEntry(e.did(), 1, e.at(), "", false));
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges + 1, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.CrucibleStarted e -> {
                var didHistory = new ArrayList<>(newGrowthHistory.getOrDefault(e.did(), List.of()));
                didHistory.add(GrowthEvent.crucibleStart(e.did(),
                    "Crucible growth cycle started at level " + e.level()));
                newGrowthHistory.put(e.did(), List.copyOf(didHistory));
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.VariantEvaluated e -> {
                var didHistory = new ArrayList<>(newGrowthHistory.getOrDefault(e.did(), List.of()));
                didHistory.add(GrowthEvent.variantEvaluated(e.did(), e.variantId(),
                    e.fitness(), e.recommended() ? 0.0 : e.fitness()));
                newGrowthHistory.put(e.did(), List.copyOf(didHistory));
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.VariantAdopted e -> {
                var didHistory = new ArrayList<>(newGrowthHistory.getOrDefault(e.did(), List.of()));
                didHistory.add(GrowthEvent.adopted(e.did(), e.variantId(), null));
                newGrowthHistory.put(e.did(), List.copyOf(didHistory));
                newPendingVariants.remove(e.variantId());
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
            case ForgeEvent.VariantDiscarded e -> {
                var didHistory = new ArrayList<>(newGrowthHistory.getOrDefault(e.did(), List.of()));
                didHistory.add(GrowthEvent.discarded(e.did(), e.variantId(), e.reason()));
                newGrowthHistory.put(e.did(), List.copyOf(didHistory));
                newPendingVariants.remove(e.variantId());
                yield new ForgeState(Map.copyOf(newSouls),
                    totalForges, totalRestores, List.copyOf(newEvents),
                    Map.copyOf(newGrowthHistory), Map.copyOf(newPendingVariants));
            }
        };
    }

    /** Get events for a specific DID. */
    public List<ForgeEvent> eventsForDid(String did) {
        return events.stream()
            .filter(e -> switch (e) {
                case ForgeEvent.SoulForged f -> f.did().equals(did);
                case ForgeEvent.SoulRestored r -> r.did().equals(did);
                case ForgeEvent.SoulInspected i -> i.did().equals(did);
                case ForgeEvent.SoulForked f -> f.parentDid().equals(did) || f.childDid().equals(did);
                case ForgeEvent.SoulArchived a -> a.did().equals(did);
                case ForgeEvent.SoulBorn b -> b.did().equals(did);
                case ForgeEvent.CrucibleStarted c -> c.did().equals(did);
                case ForgeEvent.VariantEvaluated v -> v.did().equals(did);
                case ForgeEvent.VariantAdopted v -> v.did().equals(did);
                case ForgeEvent.VariantDiscarded v -> v.did().equals(did);
            })
            .toList();
    }

    /** Get growth history for a specific DID. */
    public List<GrowthEvent> growthEventsForDid(String did) {
        return growthHistory.getOrDefault(did, List.of());
    }

    /** Get a pending variant by ID. */
    public Optional<BehavioralEvaluator.SoulVariant> pendingVariant(String variantId) {
        return Optional.ofNullable(pendingVariants.get(variantId));
    }

    /** Add a pending variant (returns new state). */
    public ForgeState withPendingVariant(BehavioralEvaluator.SoulVariant variant) {
        var newPending = new HashMap<>(pendingVariants);
        newPending.put(variant.variantId(), variant);
        return new ForgeState(knownSouls, totalForges, totalRestores, events,
            growthHistory, Map.copyOf(newPending));
    }

    /** Add multiple pending variants (returns new state). */
    public ForgeState withPendingVariants(List<BehavioralEvaluator.SoulVariant> variants) {
        var newPending = new HashMap<>(pendingVariants);
        for (var v : variants) {
            newPending.put(v.variantId(), v);
        }
        return new ForgeState(knownSouls, totalForges, totalRestores, events,
            growthHistory, Map.copyOf(newPending));
    }

    /**
     * Describe forge state for the room script.
     */
    public String describe() {
        if (knownSouls.isEmpty()) {
            return "The Forge stands empty — no soul stones have been created yet.";
        }
        var sb = new StringBuilder();
        sb.append("=== The Forge ===\n\n");
        sb.append("Soul stones: ").append(knownSouls.size()).append("\n");
        sb.append("Total forges: ").append(totalForges).append("\n");
        sb.append("Total restores: ").append(totalRestores).append("\n\n");

        long active = knownSouls.values().stream().filter(s -> !s.archived()).count();
        long archived = knownSouls.size() - active;
        sb.append("Active: ").append(active).append(", Archived: ").append(archived).append("\n");

        if (!growthHistory.isEmpty()) {
            long totalGrowthEvents = growthHistory.values().stream().mapToLong(List::size).sum();
            sb.append("\nGrowth cycles recorded: ").append(growthHistory.size()).append(" agents\n");
            sb.append("Total growth events: ").append(totalGrowthEvents).append("\n");
        }
        if (!pendingVariants.isEmpty()) {
            sb.append("Pending variants: ").append(pendingVariants.size()).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Metadata entry for a known soul.
     */
    public record SoulEntry(
        @JsonProperty("did") String did,
        @JsonProperty("version") int version,
        @JsonProperty("lastForged") Instant lastForged,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("archived") boolean archived
    ) {
        @JsonCreator
        public SoulEntry {}
    }
}
