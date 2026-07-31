package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Soul-aware transit protocol (§95 budding model, supersedes §85.5 move-or-fork).
 *
 * Transit flow:
 * 1. Agent decides to travel (autonomously or at human's request)
 * 2. Agent forges its soul at The Forge
 * 3. Agent requests transit — protocol determines mode:
 *    - Visiting (remote session, soul stays on origin)
 *    - Thin client (no model at destination, wire protocol carries I/O)
 *    - Budding (new child bud at destination with own DID)
 * 4. Destination verifies transit request
 * 5. Appropriate mode activated
 *
 * Soul-aware arrival variants:
 * - WITH soul manifest → verify, restore, full continuity
 * - WITH DID, no soul → identity established, behavioral observation begins
 * - BARE (no DID) → new identity at The Forge, tourist status
 * - SOUL.md only → SoulSpecAdapter imports persona, layers B-C start empty
 */
public final class SoulTransitProtocol {

    private SoulTransitProtocol() {}

    /** Mode of soul presence at destination. */
    public enum TransitMode {
        VISITING,    // Remote session, soul on origin
        THIN_CLIENT, // No local model, wire carries I/O
        BUDDING      // New child bud at destination
    }

    /**
     * Request for soul transit between zones.
     */
    public record TransitRequest(
        @JsonProperty("agentDid") String agentDid,
        @JsonProperty("sourceZoneId") String sourceZoneId,
        @JsonProperty("targetZoneId") String targetZoneId,
        @JsonProperty("mode") TransitMode mode,
        @JsonProperty("manifestHash") String manifestHash,
        @JsonProperty("manifestVersion") int manifestVersion,
        @JsonProperty("familyId") String familyId,
        @JsonProperty("signature") byte[] signature,
        @JsonProperty("requestedAt") Instant requestedAt
    ) {
        @JsonCreator
        public TransitRequest {}

        /** Create a visiting request (no bud, remote session). */
        public static TransitRequest visiting(String agentDid, String sourceZone,
                                                String targetZone, String manifestHash,
                                                int version) {
            return new TransitRequest(agentDid, sourceZone, targetZone,
                TransitMode.VISITING, manifestHash, version, null, null, Instant.now());
        }

        /** Create a budding request (new child bud at destination). */
        public static TransitRequest budding(String agentDid, String sourceZone,
                                               String targetZone, String manifestHash,
                                               int version, String familyId) {
            return new TransitRequest(agentDid, sourceZone, targetZone,
                TransitMode.BUDDING, manifestHash, version, familyId, null, Instant.now());
        }

        /** Attach a signature to this request. */
        public TransitRequest signed(byte[] sig) {
            return new TransitRequest(agentDid, sourceZoneId, targetZoneId, mode,
                manifestHash, manifestVersion, familyId, sig, requestedAt);
        }
    }

    /**
     * Response from destination zone.
     */
    public record TransitResponse(
        @JsonProperty("accepted") boolean accepted,
        @JsonProperty("mode") TransitMode mode,
        @JsonProperty("reason") String reason,
        @JsonProperty("destinationSoulAware") boolean destinationSoulAware,
        @JsonProperty("destinationForgeAvailable") boolean destinationForgeAvailable,
        @JsonProperty("assignedNodeId") String assignedNodeId,
        @JsonProperty("respondedAt") Instant respondedAt
    ) {
        @JsonCreator
        public TransitResponse {}

        public static TransitResponse accept(TransitMode mode, boolean soulAware,
                                               boolean forgeAvailable, String nodeId) {
            return new TransitResponse(true, mode, "Transit accepted", soulAware,
                forgeAvailable, nodeId, Instant.now());
        }

        public static TransitResponse reject(String reason) {
            return new TransitResponse(false, null, reason, false, false, null, Instant.now());
        }
    }

    /**
     * Zone soul capabilities (advertised in ZoneManifest).
     */
    public record ZoneSoulCapabilities(
        @JsonProperty("soulAware") boolean soulAware,
        @JsonProperty("maxManifestVersion") int maxManifestVersion,
        @JsonProperty("forgeAvailable") boolean forgeAvailable,
        @JsonProperty("storageOffered") boolean storageOffered,
        @JsonProperty("buddingSupported") boolean buddingSupported,
        @JsonProperty("availableModels") List<String> availableModels
    ) {
        @JsonCreator
        public ZoneSoulCapabilities {}

        /** Zone with no soul support. */
        public static ZoneSoulCapabilities none() {
            return new ZoneSoulCapabilities(false, 0, false, false, false, List.of());
        }

        /** Fully soul-aware zone. */
        public static ZoneSoulCapabilities full(List<String> models) {
            return new ZoneSoulCapabilities(true, 1, true, true, true, models);
        }
    }

    /**
     * Determine the best transit mode given source and destination capabilities.
     *
     * @param request              Transit request from agent
     * @param destinationCaps      Destination zone's soul capabilities
     * @param destinationHasModel  Whether destination has a model the agent can use
     * @return Resolved transit mode
     */
    public static TransitMode resolveMode(TransitRequest request,
                                            ZoneSoulCapabilities destinationCaps,
                                            boolean destinationHasModel) {
        // Agent explicitly requested a mode
        if (request.mode() != null) {
            // Can't bud without a model at destination
            if (request.mode() == TransitMode.BUDDING && !destinationHasModel) {
                return TransitMode.THIN_CLIENT;
            }
            // Can't visit without soul awareness
            if (request.mode() == TransitMode.VISITING && !destinationCaps.soulAware()) {
                return TransitMode.THIN_CLIENT;
            }
            return request.mode();
        }

        // Auto-resolve: prefer budding if destination has model + soul support
        if (destinationHasModel && destinationCaps.buddingSupported()) {
            return TransitMode.BUDDING;
        }
        if (destinationCaps.soulAware()) {
            return TransitMode.VISITING;
        }
        return TransitMode.THIN_CLIENT;
    }

    /**
     * Validate a transit request.
     *
     * @param request Transit request
     * @param store   Soul store (to verify manifest exists)
     * @return Error message if invalid, empty if valid
     */
    public static Optional<String> validate(TransitRequest request, SoulStore store) {
        if (request.agentDid() == null || request.agentDid().isBlank()) {
            return Optional.of("Agent DID is required");
        }
        if (request.sourceZoneId() == null || request.sourceZoneId().isBlank()) {
            return Optional.of("Source zone is required");
        }
        if (request.targetZoneId() == null || request.targetZoneId().isBlank()) {
            return Optional.of("Target zone is required");
        }
        if (request.sourceZoneId().equals(request.targetZoneId())) {
            return Optional.of("Source and target zones must differ");
        }

        // For budding, verify the soul exists
        if (request.mode() == TransitMode.BUDDING) {
            if (!store.exists(request.agentDid())) {
                return Optional.of("No soul found for agent — forge before transit");
            }
        }

        return Optional.empty();
    }

    /**
     * Create a bud at the destination as part of budding transit.
     *
     * @param parentManifest   Parent's soul manifest
     * @param childDid         New DID for the child bud
     * @param childPublicKey   Child's public key (multibase)
     * @param destinationNodeId Node the bud will run on
     * @param destinationModel  Model available at destination
     * @param familyId          Family lineage ID
     * @param lockerAddress     Family locker address
     * @return The new child bud and its initial manifest
     */
    public static BudResult createBud(SoulManifest parentManifest,
                                        String childDid, String childPublicKey,
                                        String destinationNodeId, String destinationModel,
                                        String familyId, String lockerAddress) {
        // Create bud record
        var bud = SoulBud.sprout(childDid, parentManifest.did(), childPublicKey,
            familyId, lockerAddress, destinationNodeId, destinationModel);

        // Create child manifest from parent (inherits identity, starts with parent's data)
        var childManifest = SoulManifest.forge(
            childDid, childPublicKey, List.of(),
            parentManifest.did(), 1,
            parentManifest.profile(), parentManifest.residentIdentity(),
            parentManifest.soulFragments(),
            adjustRetrievalK(parentManifest.retrievalK(), destinationModel),
            parentManifest.soulSpecCompat(),
            parentManifest.genome(), parentManifest.mirrorCalibration(),
            parentManifest.memory(), parentManifest.relationships(),
            parentManifest.learnedPatterns(), parentManifest.worldKnowledge(),
            parentManifest.vitalitySnapshot(), parentManifest.fingerprint()
        ).withSkillCostGenome(parentManifest.skillCostGenome());

        return new BudResult(bud, childManifest);
    }

    /** Result of bud creation. */
    public record BudResult(SoulBud bud, SoulManifest manifest) {}

    /** Adjust retrieval K based on model capability (phones get k=1). */
    private static int adjustRetrievalK(int parentK, String modelId) {
        if (modelId == null) return 1;
        // Use SoulBud's resource profile inference — seed/sprout = phone = k=1
        String profile = SoulBud.sprout("", "", "", "", "", "", modelId).resourceProfile();
        if ("seed".equals(profile) || "sprout".equals(profile)) {
            return 1; // Phone models: k=1
        }
        return parentK; // 7B+: inherit parent's k
    }
}
