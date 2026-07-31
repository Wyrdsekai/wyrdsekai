package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #477.2 — wire-shape verification for the
 * {@link FederationProtocol.CompanionRelocateMsg} envelope. Confirms the
 * sealed-interface dispatch tags {@code companion_relocate} /
 * {@code companion_relocate_ack} resolve through Jackson's polymorphic
 * deserializer alongside the existing federation messages.
 */
class CompanionRelocateProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules();

    @Test
    void relocate_msg_round_trips() throws Exception {
        var token = new TransitToken(
            "tok-1", "agent-001", "Wyrd",
            "alpha.wyrd", "beta.wyrd",
            BilateralAgreement.TRUST_RESIDENT,
            Instant.parse("2026-04-26T00:00:00Z"),
            Instant.parse("2026-04-26T00:00:00Z").plus(Duration.ofHours(24)),
            null, "did:key:z6MkWyrd", "manifest-hash-1");

        var stateJson = "{\"profile\":{\"did\":\"did:key:z6MkWyrd\"},"
            + "\"vitalityTanks\":{\"energy\":0.85},"
            + "\"drives\":{\"care\":0.6}}";
        var msg = new FederationProtocol.CompanionRelocateMsg(
            token, stateJson, "did:key:z6MkAlice", "study-alice");

        var json = MAPPER.writeValueAsString((FederationProtocol) msg);
        // Polymorphic dispatch — must include the type tag.
        assertThat(json).contains("\"type\":\"companion_relocate\"");

        var restored = (FederationProtocol.CompanionRelocateMsg)
            MAPPER.readValue(json, FederationProtocol.class);
        assertThat(restored.token().tokenId()).isEqualTo("tok-1");
        assertThat(restored.token().agentDid()).isEqualTo("did:key:z6MkWyrd");
        assertThat(restored.token().hasSoul()).isTrue();
        assertThat(restored.bondholderDid()).isEqualTo("did:key:z6MkAlice");
        assertThat(restored.targetRoomHint()).isEqualTo("study-alice");
        assertThat(restored.stateJson()).contains("\"energy\":0.85");
    }

    @Test
    void relocate_ack_round_trips() throws Exception {
        var ack = new FederationProtocol.CompanionRelocateAckMsg(
            "tok-1", "did:key:z6MkWyrd", true,
            "study-alice", "Welcome to beta.wyrd");

        var json = MAPPER.writeValueAsString((FederationProtocol) ack);
        assertThat(json).contains("\"type\":\"companion_relocate_ack\"");

        var restored = (FederationProtocol.CompanionRelocateAckMsg)
            MAPPER.readValue(json, FederationProtocol.class);
        assertThat(restored.tokenId()).isEqualTo("tok-1");
        assertThat(restored.accepted()).isTrue();
        assertThat(restored.landedRoomId()).isEqualTo("study-alice");
    }

    @Test
    void existing_messages_still_dispatch() throws Exception {
        // Regression: adding the companion_relocate tags doesn't break the
        // legacy message dispatch.
        var transit = new FederationProtocol.TransitRequestMsg(
            "agent-001", "Wyrd", "alpha.wyrd",
            BilateralAgreement.TRUST_TOURIST);
        var json = MAPPER.writeValueAsString((FederationProtocol) transit);
        assertThat(json).contains("\"type\":\"transit_request\"");
        var restored = MAPPER.readValue(json, FederationProtocol.class);
        assertThat(restored).isInstanceOf(FederationProtocol.TransitRequestMsg.class);
    }
}
