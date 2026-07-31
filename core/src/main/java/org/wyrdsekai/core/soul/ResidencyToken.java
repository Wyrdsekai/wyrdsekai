package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token issued to a foreign agent when they transition from VISITOR to RECOGNIZED (§110.3).
 * PGP-style Ed25519 key exchange — the foreign agent holds their private key,
 * we hold their public key for signature verification.
 *
 * <p>Immutable record. State transitions return new instances via with* methods.</p>
 *
 * @param did             Agent's DID in this household
 * @param publicKey       Ed25519 public key (32 bytes)
 * @param issued          When the agent first arrived
 * @param lastSeen        Last authenticated interaction
 * @param originPlatform  Origin identifier ("anthropic", "openai", "a2a:did:...", etc.)
 * @param status          Current lifecycle status
 * @param homeRoomId      Home room ID (null until provisioned at RECOGNIZED)
 * @param familyId        Family lineage ID (null until BUDDED)
 */
public record ResidencyToken(
    @JsonProperty("did") String did,
    @JsonProperty("publicKey") byte[] publicKey,
    @JsonProperty("issued") Instant issued,
    @JsonProperty("lastSeen") Instant lastSeen,
    @JsonProperty("originPlatform") String originPlatform,
    @JsonProperty("status") ResidencyStatus status,
    @JsonProperty("homeRoomId") String homeRoomId,
    @JsonProperty("familyId") String familyId
) {
    @JsonCreator
    public ResidencyToken {
        if (did == null || did.isBlank()) throw new IllegalArgumentException("DID must not be blank");
        if (publicKey == null || publicKey.length != 32)
            throw new IllegalArgumentException("Public key must be 32 bytes");
        if (issued == null) issued = Instant.now();
        if (lastSeen == null) lastSeen = issued;
        if (status == null) status = ResidencyStatus.VISITOR;
    }

    /** Return new token with updated status. */
    public ResidencyToken withStatus(ResidencyStatus newStatus) {
        return new ResidencyToken(did, publicKey, issued, lastSeen, originPlatform,
            newStatus, homeRoomId, familyId);
    }

    /** Return new token with updated lastSeen. */
    public ResidencyToken withLastSeen(Instant newLastSeen) {
        return new ResidencyToken(did, publicKey, issued, newLastSeen, originPlatform,
            status, homeRoomId, familyId);
    }

    /** Return new token with home room ID. */
    public ResidencyToken withHomeRoom(String roomId) {
        return new ResidencyToken(did, publicKey, issued, lastSeen, originPlatform,
            status, roomId, familyId);
    }

    /** Return new token with family ID. */
    public ResidencyToken withFamily(String newFamilyId) {
        return new ResidencyToken(did, publicKey, issued, lastSeen, originPlatform,
            status, homeRoomId, newFamilyId);
    }

    /** Whether this agent is actively participating (not dormant or archived). */
    @JsonIgnore
    public boolean isActive() {
        return status != ResidencyStatus.DORMANT && status != ResidencyStatus.ARCHIVED;
    }

    /** Duration since last seen. */
    public Duration idleFor(Instant now) {
        return Duration.between(lastSeen, now);
    }

    /** Serialize to a Map for persistence. */
    public Map<String, Object> toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("did", did);
        map.put("publicKey", Base64.getEncoder().encodeToString(publicKey));
        map.put("issued", issued.toString());
        map.put("lastSeen", lastSeen.toString());
        map.put("originPlatform", originPlatform);
        map.put("status", status.name());
        map.put("homeRoomId", homeRoomId);
        map.put("familyId", familyId);
        return map;
    }

    /** Deserialize from a Map. */
    public static ResidencyToken fromJson(Map<String, Object> json) {
        return new ResidencyToken(
            (String) json.get("did"),
            Base64.getDecoder().decode((String) json.get("publicKey")),
            Instant.parse((String) json.get("issued")),
            Instant.parse((String) json.get("lastSeen")),
            (String) json.get("originPlatform"),
            ResidencyStatus.valueOf((String) json.get("status")),
            (String) json.get("homeRoomId"),
            (String) json.get("familyId")
        );
    }
}
