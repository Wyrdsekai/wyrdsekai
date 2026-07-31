package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Wire protocol messages for zone-to-zone federation over NATS.
 * Serialized as JSON inside BetweenEnvelope payloads.
 *
 * NATS subjects: federation.{targetZoneId}.gate.{topic}
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FederationProtocol.ProposeMsg.class, name = "propose"),
    @JsonSubTypes.Type(value = FederationProtocol.AcceptMsg.class, name = "accept"),
    @JsonSubTypes.Type(value = FederationProtocol.RevokeMsg.class, name = "revoke"),
    @JsonSubTypes.Type(value = FederationProtocol.ManifestMsg.class, name = "manifest"),
    @JsonSubTypes.Type(value = FederationProtocol.TransitRequestMsg.class, name = "transit_request"),
    @JsonSubTypes.Type(value = FederationProtocol.TransitResponseMsg.class, name = "transit_response"),
    @JsonSubTypes.Type(value = FederationProtocol.CompanionRelocateMsg.class, name = "companion_relocate"),
    @JsonSubTypes.Type(value = FederationProtocol.CompanionRelocateAckMsg.class, name = "companion_relocate_ack")
})
public sealed interface FederationProtocol {

    /** Propose a bilateral agreement to a remote zone. */
    record ProposeMsg(
        @JsonProperty("proposer") ZoneManifest proposer,
        @JsonProperty("trustLevel") String trustLevel
    ) implements FederationProtocol {}

    /** Accept a pending proposal. */
    record AcceptMsg(
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("acceptor") ZoneManifest acceptor
    ) implements FederationProtocol {}

    /** Revoke an active agreement. */
    record RevokeMsg(
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("reason") String reason
    ) implements FederationProtocol {}

    /** Zone manifest exchange (periodic advertisement). */
    record ManifestMsg(
        @JsonProperty("manifest") ZoneManifest manifest
    ) implements FederationProtocol {}

    /** Request transit permission for an agent. */
    record TransitRequestMsg(
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("sourceZoneId") String sourceZoneId,
        @JsonProperty("trustLevel") String trustLevel
    ) implements FederationProtocol {}

    /** Response to a transit request. */
    record TransitResponseMsg(
        @JsonProperty("agentId") String agentId,
        @JsonProperty("allowed") boolean allowed,
        @JsonProperty("transitToken") String transitToken,
        @JsonProperty("targetUrl") String targetUrl,
        @JsonProperty("reason") String reason
    ) implements FederationProtocol {}

    /**
     * relocate an entire companion actor to the
     * target zone. The {@code transitToken} is signed by the source steward
     * (validated against {@code agentDid} = soul DID); {@code stateJson} is
     * a JSON-encoded {@code CompanionTransitState} captured at the source
     * just before the actor was stopped. {@code bondholderDid} names the
     * traveler the companion is following so the target can wire up presence.
     */
    record CompanionRelocateMsg(
        @JsonProperty("token") TransitToken token,
        @JsonProperty("stateJson") String stateJson,
        @JsonProperty("bondholderDid") String bondholderDid,
        @JsonProperty("targetRoomHint") String targetRoomHint
    ) implements FederationProtocol {}

    /**
     * Target zone's reply: did the relocate succeed, where did she land,
     * and what error if any. Source treats {@code accepted=false} as a
     * rollback signal — re-spawn locally if the companion was already
     * stopped.
     */
    record CompanionRelocateAckMsg(
        @JsonProperty("tokenId") String tokenId,
        @JsonProperty("agentDid") String agentDid,
        @JsonProperty("accepted") boolean accepted,
        @JsonProperty("landedRoomId") String landedRoomId,
        @JsonProperty("reason") String reason
    ) implements FederationProtocol {}
}
