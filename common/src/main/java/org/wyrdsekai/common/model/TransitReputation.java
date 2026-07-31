package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reputation summary that travels with an entity to a remote zone.
 *
 * <p>Included in the session.open payload so the destination zone can make
 * permission decisions beyond the base trust level. For v1, this is a
 * per-transit snapshot — full federation sync via NATS subjects is v2.</p>
 *
 * @param entityDid       DID of the entity (agent or player)
 * @param ageDays         days since entity was created
 * @param stewardCount    number of steward endorsements
 * @param peerCount       number of peer agent endorsements
 * @param externalCount   number of external attestations
 * @param compositeScore  computed reputation score (0.0-1.0)
 * @param homeZone        zone that vouches for this reputation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransitReputation(
    @JsonProperty("entityDid") String entityDid,
    @JsonProperty("ageDays") long ageDays,
    @JsonProperty("stewardCount") int stewardCount,
    @JsonProperty("peerCount") int peerCount,
    @JsonProperty("externalCount") int externalCount,
    @JsonProperty("compositeScore") double compositeScore,
    @JsonProperty("homeZone") String homeZone
) {

    public static TransitReputation empty(String entityDid, String homeZone) {
        return new TransitReputation(entityDid, 0, 0, 0, 0, 0.0, homeZone);
    }

    @JsonCreator
    public static TransitReputation create(
            @JsonProperty("entityDid") String entityDid,
            @JsonProperty("ageDays") long ageDays,
            @JsonProperty("stewardCount") int stewardCount,
            @JsonProperty("peerCount") int peerCount,
            @JsonProperty("externalCount") int externalCount,
            @JsonProperty("compositeScore") double compositeScore,
            @JsonProperty("homeZone") String homeZone) {
        return new TransitReputation(entityDid, ageDays, stewardCount, peerCount,
            externalCount, compositeScore, homeZone);
    }

    /** Permission tier based on composite score. */
    public String permissionTier() {
        if (compositeScore >= 0.8) return "trusted";
        if (compositeScore >= 0.5) return "verified";
        if (compositeScore >= 0.2) return "known";
        return "tourist";
    }
}
