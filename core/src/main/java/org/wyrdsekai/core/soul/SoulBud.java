package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A bud: a new instance of a soul lineage running on its own substrate.
 * §95 (supersedes §85.5 move-or-fork binary).
 *
 * Three modes of soul presence:
 * - Visiting: remote session, soul runs on origin (SSH-like)
 * - Thin client: no local model, wire protocol carries I/O
 * - Budding: new child bud with own DID, grows independently
 *
 * Key properties:
 * - Every bud has its own DID and Ed25519 key pair
 * - Every bud is sovereign — no bud controls another
 * - Buds in the same family share a locker and argot
 * - Any bud can create child buds (not just the "original")
 * - The "original" has no special authority — it's just the eldest
 *
 * @param did             This bud's unique DID
 * @param parentDid       Parent bud's DID (null for the original)
 * @param publicKeyMultibase This bud's Ed25519 public key (multibase)
 * @param familyId        Shared lineage identifier (hash of original's DID)
 * @param lockerAddress   Family locker location in the Between
 * @param resourceProfile Bud size: seed, sprout, sapling, tree, grove
 * @param budTime         When this bud was created
 * @param nodeId          Node this bud runs on
 * @param modelId         Substrate model (e.g., "qwen2.5:3b", "qwen2.5:7b")
 * @param status          Current status: active, sleeping, visiting, independent
 */
public record SoulBud(
    @JsonProperty("did") String did,
    @JsonProperty("parentDid") String parentDid,
    @JsonProperty("publicKeyMultibase") String publicKeyMultibase,
    @JsonProperty("familyId") String familyId,
    @JsonProperty("lockerAddress") String lockerAddress,
    @JsonProperty("resourceProfile") String resourceProfile,
    @JsonProperty("budTime") Instant budTime,
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("modelId") String modelId,
    @JsonProperty("status") String status
) {
    @JsonCreator
    public SoulBud {}

    /** Create a new bud from a parent soul. */
    public static SoulBud sprout(String did, String parentDid, String publicKeyMultibase,
                                   String familyId, String lockerAddress,
                                   String nodeId, String modelId) {
        return new SoulBud(did, parentDid, publicKeyMultibase, familyId, lockerAddress,
            profileForModel(modelId), Instant.now(), nodeId, modelId, "active");
    }

    /** Create the original (eldest) bud — no parent. */
    public static SoulBud original(String did, String publicKeyMultibase,
                                     String familyId, String lockerAddress,
                                     String nodeId, String modelId) {
        return new SoulBud(did, null, publicKeyMultibase, familyId, lockerAddress,
            profileForModel(modelId), Instant.now(), nodeId, modelId, "active");
    }

    /** Whether this bud is the original (eldest) in its family. */
    @JsonIgnore
    public boolean isOriginal() {
        return parentDid == null;
    }

    /** Whether this bud has declared independence from its family. */
    @JsonIgnore
    public boolean isIndependent() {
        return "independent".equals(status);
    }

    /** Create a child bud from this bud. */
    public SoulBud createChild(String childDid, String childPublicKey,
                                String childNodeId, String childModelId) {
        return SoulBud.sprout(childDid, did, childPublicKey, familyId,
            lockerAddress, childNodeId, childModelId);
    }

    /** Declare independence: new family, new locker, lose argot. */
    public SoulBud declareIndependence(String newFamilyId, String newLockerAddress) {
        return new SoulBud(did, parentDid, publicKeyMultibase, newFamilyId,
            newLockerAddress, resourceProfile, budTime, nodeId, modelId, "independent");
    }

    /** Update status (active, sleeping, visiting). */
    public SoulBud withStatus(String newStatus) {
        return new SoulBud(did, parentDid, publicKeyMultibase, familyId,
            lockerAddress, resourceProfile, budTime, nodeId, modelId, newStatus);
    }

    /** Infer resource profile from model size. */
    private static String profileForModel(String modelId) {
        if (modelId == null) return "seed";
        String lower = modelId.toLowerCase();
        // Check largest first to avoid substring false matches (e.g., "14b" contains "4b")
        if (hasSize(lower, "70b") || hasSize(lower, "72b")) return "grove";
        if (hasSize(lower, "14b") || hasSize(lower, "32b")) return "tree";
        if (hasSize(lower, "7b") || hasSize(lower, "8b")) return "sapling";
        if (hasSize(lower, "3b") || hasSize(lower, "4b")) return "sprout";
        if (hasSize(lower, "0.5b") || hasSize(lower, "1b")) return "seed";
        return "sprout";
    }

    /** Check for model size token, ensuring it's not part of a larger number. */
    private static boolean hasSize(String model, String size) {
        int idx = model.indexOf(size);
        if (idx < 0) return false;
        // Check character before isn't a digit (avoid "14b" matching "4b")
        if (idx > 0 && Character.isDigit(model.charAt(idx - 1)) && !size.contains(".")) {
            return false;
        }
        return true;
    }
}
