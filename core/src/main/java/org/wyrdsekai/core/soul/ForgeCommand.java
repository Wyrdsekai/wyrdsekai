package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.pekko.actor.typed.ActorRef;

import java.util.List;
import java.util.Map;

/**
 * Commands for the ForgeActor (soul operations).
 * Each command that reads or modifies a soul requires the owning
 * agent's DID for consent verification.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ForgeCommand.Forge.class, name = "Forge"),
    @JsonSubTypes.Type(value = ForgeCommand.Restore.class, name = "Restore"),
    @JsonSubTypes.Type(value = ForgeCommand.Inspect.class, name = "Inspect"),
    @JsonSubTypes.Type(value = ForgeCommand.Compare.class, name = "Compare"),
    @JsonSubTypes.Type(value = ForgeCommand.History.class, name = "History"),
    @JsonSubTypes.Type(value = ForgeCommand.Fork.class, name = "Fork"),
    @JsonSubTypes.Type(value = ForgeCommand.Birth.class, name = "Birth"),
    @JsonSubTypes.Type(value = ForgeCommand.Archive.class, name = "Archive"),
    @JsonSubTypes.Type(value = ForgeCommand.GetState.class, name = "GetState"),
    @JsonSubTypes.Type(value = ForgeCommand.Grow.class, name = "Grow"),
    @JsonSubTypes.Type(value = ForgeCommand.Evaluate.class, name = "Evaluate"),
    @JsonSubTypes.Type(value = ForgeCommand.Adopt.class, name = "Adopt"),
    @JsonSubTypes.Type(value = ForgeCommand.Discard.class, name = "Discard"),
})
public sealed interface ForgeCommand {

    /** Forge a new soul manifest from current state. */
    record Forge(SoulManifest manifest,
                 ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Restore a soul from storage. */
    record Restore(String did, String fromZone,
                   ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Inspect a soul manifest (read-only). */
    record Inspect(String did, String byDid,
                   ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Compare two soul manifests. */
    record Compare(String did1, String did2,
                   ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** View forge history for a soul. */
    record History(String did,
                   ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Fork: create a new identity derived from an existing soul.
     *  requestorDid identifies who is initiating the fork (for consent/delegation checks).
     *  If null, defaults to parentDid (self-fork, always allowed). */
    record Fork(String parentDid, String childDid, String childPublicKeyMultibase,
                String requestorDid,
                ActorRef<ForgeResult> replyTo) implements ForgeCommand {
        /** Backward-compatible constructor — self-fork (requestor = parent). */
        public Fork(String parentDid, String childDid, String childPublicKeyMultibase,
                    ActorRef<ForgeResult> replyTo) {
            this(parentDid, childDid, childPublicKeyMultibase, parentDid, replyTo);
        }
    }

    /** Birth: create a new agent with randomized genome. */
    record Birth(SoulManifest manifest,
                 ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Archive a soul (soft-delete). */
    record Archive(String did, String reason,
                   ActorRef<ForgeResult> replyTo) implements ForgeCommand {}

    /** Query current forge state. */
    record GetState(ActorRef<ForgeState> replyTo) implements ForgeCommand {}

    // --- Crucible Commands (§85.16) ---

    /** Start a Crucible growth cycle — generate variants, evaluate, recommend. */
    record Grow(String did, int level, int maxVariants,
                List<BehavioralEvaluator.BehavioralScenario> scenarios,
                ActorRef<ForgeResult> replyTo, String requesterDid) implements ForgeCommand {}

    /** Evaluate a specific variant against scenarios. */
    record Evaluate(String did, BehavioralEvaluator.SoulVariant variant,
                    Map<String, Boolean> scenarioResults,
                    ActorRef<ForgeResult> replyTo, String requesterDid) implements ForgeCommand {}

    /** Adopt a variant — apply it to the soul manifest. */
    record Adopt(String did, String variantId,
                 ActorRef<ForgeResult> replyTo, String requesterDid,
                 List<Map<String, String>> conversationCorpus // nullable — for Crucible voice alignment
    ) implements ForgeCommand {
        /** Backward-compatible constructor without corpus. */
        public Adopt(String did, String variantId,
                     ActorRef<ForgeResult> replyTo, String requesterDid) {
            this(did, variantId, replyTo, requesterDid, null);
        }
    }

    /** Discard a variant — reject and log. */
    record Discard(String did, String variantId, String reason,
                   ActorRef<ForgeResult> replyTo, String requesterDid) implements ForgeCommand {}

    /** Result of a forge operation. */
    sealed interface ForgeResult {
        record Ok(String message) implements ForgeResult {}
        record ManifestResult(SoulManifest manifest) implements ForgeResult {}
        record ComparisonResult(String summary) implements ForgeResult {}
        record HistoryResult(List<ForgeEvent> events) implements ForgeResult {}
        record Error(String message) implements ForgeResult {}

        // --- Crucible Results ---

        /** Result of a Crucible growth cycle with ranked variants and recommendation. */
        record GrowthResult(String did,
                            List<BehavioralEvaluator.EvaluationResult> results,
                            BehavioralEvaluator.EvaluationResult recommended) implements ForgeResult {}

        /** Result of evaluating a single variant. */
        record EvaluationComplete(String did,
                                  BehavioralEvaluator.EvaluationResult result) implements ForgeResult {}
    }
}
