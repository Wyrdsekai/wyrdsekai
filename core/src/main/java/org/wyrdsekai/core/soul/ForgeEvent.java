package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Events persisted by the ForgeActor (soul operations log).
 * Event-sourced: these form the immutable audit trail of all
 * soul operations — forge, restore, inspect, fork, archive, birth.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ForgeEvent.SoulForged.class, name = "SoulForged"),
    @JsonSubTypes.Type(value = ForgeEvent.SoulRestored.class, name = "SoulRestored"),
    @JsonSubTypes.Type(value = ForgeEvent.SoulInspected.class, name = "SoulInspected"),
    @JsonSubTypes.Type(value = ForgeEvent.SoulForked.class, name = "SoulForked"),
    @JsonSubTypes.Type(value = ForgeEvent.SoulArchived.class, name = "SoulArchived"),
    @JsonSubTypes.Type(value = ForgeEvent.SoulBorn.class, name = "SoulBorn"),
    @JsonSubTypes.Type(value = ForgeEvent.CrucibleStarted.class, name = "CrucibleStarted"),
    @JsonSubTypes.Type(value = ForgeEvent.VariantEvaluated.class, name = "VariantEvaluated"),
    @JsonSubTypes.Type(value = ForgeEvent.VariantAdopted.class, name = "VariantAdopted"),
    @JsonSubTypes.Type(value = ForgeEvent.VariantDiscarded.class, name = "VariantDiscarded"),
})
public sealed interface ForgeEvent {

    /** A soul manifest was forged (new version created). */
    record SoulForged(String did, Instant at, int version,
                      String contentHash) implements ForgeEvent {}

    /** A soul was restored from storage. */
    record SoulRestored(String did, Instant at,
                        String fromZone) implements ForgeEvent {}

    /** A soul was inspected (read-only). */
    record SoulInspected(String did, String byDid,
                         Instant at) implements ForgeEvent {}

    /** A soul was forked into a new identity. */
    record SoulForked(String parentDid, String childDid,
                      Instant at) implements ForgeEvent {}

    /** A soul was archived (soft-delete). */
    record SoulArchived(String did, Instant at,
                        String reason) implements ForgeEvent {}

    /** A new soul was born with a fresh genome. */
    record SoulBorn(String did, Instant at,
                    String genomeName) implements ForgeEvent {}

    // --- Crucible Events (§85.16) ---

    /** A Crucible growth cycle was started. */
    record CrucibleStarted(String did, int level, int maxVariants,
                            Instant timestamp) implements ForgeEvent {}

    /** A variant was evaluated during a growth cycle. */
    record VariantEvaluated(String did, String variantId, double fitness,
                            boolean recommended, Instant timestamp) implements ForgeEvent {}

    /** A variant was adopted (applied to the soul manifest). */
    record VariantAdopted(String did, String variantId,
                          Instant timestamp) implements ForgeEvent {}

    /** A variant was discarded (rejected by the agent). */
    record VariantDiscarded(String did, String variantId, String reason,
                            Instant timestamp) implements ForgeEvent {}
}
