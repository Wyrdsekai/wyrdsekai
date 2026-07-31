package org.wyrdsekai.between.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;

class RelayTrustGraphTest {

    private RelayTrustGraph graph;

    @BeforeEach
    void setup() {
        graph = new RelayTrustGraph("home-zone");
    }

    @Test
    void direct_bond_gives_full_trust() {
        graph.addBond("home-zone", "friend-zone");
        assertThat(graph.trustForZone("friend-zone")).isEqualTo(1.0);
    }

    @Test
    void two_hop_bond_gives_reduced_trust() {
        graph.addBond("home-zone", "friend-zone");
        graph.addBond("friend-zone", "friend-of-friend");
        assertThat(graph.trustForZone("friend-of-friend")).isEqualTo(0.6);
    }

    @Test
    void three_hop_bond_gives_minimal_trust() {
        graph.addBond("home-zone", "a");
        graph.addBond("a", "b");
        graph.addBond("b", "c");
        assertThat(graph.trustForZone("c")).isEqualTo(0.3);
    }

    @Test
    void four_hop_gives_zero_trust() {
        graph.addBond("home-zone", "a");
        graph.addBond("a", "b");
        graph.addBond("b", "c");
        graph.addBond("c", "d");
        assertThat(graph.trustForZone("d")).isEqualTo(0.0);
    }

    @Test
    void unknown_zone_gives_zero_trust() {
        assertThat(graph.trustForZone("stranger")).isEqualTo(0.0);
    }

    @Test
    void self_trust_is_full() {
        assertThat(graph.trustForZone("home-zone")).isEqualTo(1.0);
    }

    @Test
    void relay_attested_by_direct_bond_gets_high_trust() {
        graph.addBond("home-zone", "friend-zone");
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://relay.example.com:4222", "friend-zone", "pk-friend", 1.0, "good relay"));

        assertThat(graph.trustForRelay("nats://relay.example.com:4222"))
            .isCloseTo(1.0, offset(0.01));
    }

    @Test
    void relay_attested_by_two_hop_gets_reduced_trust() {
        graph.addBond("home-zone", "a");
        graph.addBond("a", "b");
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://relay.far.com:4222", "b", "pk-b", 1.0, "relay"));

        assertThat(graph.trustForRelay("nats://relay.far.com:4222"))
            .isCloseTo(0.6, offset(0.01));
    }

    @Test
    void relay_with_no_attestations_gets_zero_trust() {
        assertThat(graph.trustForRelay("nats://unknown:4222")).isEqualTo(0.0);
    }

    @Test
    void multiple_attestations_use_best_trust() {
        graph.addBond("home-zone", "close");
        graph.addBond("home-zone", "a");
        graph.addBond("a", "far");

        // Two attestors: close (1 hop) and far (2 hops)
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://relay:4222", "close", "pk-close", 0.8, "ok"));
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://relay:4222", "far", "pk-far", 1.0, "great"));

        // Best = close (1.0 hop trust * 0.8 attestation = 0.8)
        // far = (0.6 hop trust * 1.0 attestation = 0.6)
        assertThat(graph.trustForRelay("nats://relay:4222"))
            .isCloseTo(0.8, offset(0.01));
    }

    @Test
    void score_relays_returns_sorted_by_trust() {
        graph.addBond("home-zone", "friend");
        graph.addBond("home-zone", "a");
        graph.addBond("a", "acquaintance");

        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://trusted:4222", "friend", "pk", 1.0, ""));
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://less-trusted:4222", "acquaintance", "pk2", 1.0, ""));

        var scored = graph.scoreRelays();
        assertThat(scored).hasSize(2);
        assertThat(scored.get(0).relayUrl()).isEqualTo("nats://trusted:4222");
        assertThat(scored.get(0).trustScore()).isGreaterThan(scored.get(1).trustScore());
    }

    @Test
    void select_best_relay() {
        graph.addBond("home-zone", "friend");
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://best:4222", "friend", "pk", 1.0, ""));

        var best = graph.selectBestRelay();
        assertThat(best).isPresent();
        assertThat(best.get().relayUrl()).isEqualTo("nats://best:4222");
    }

    @Test
    void select_relay_with_minimum_trust() {
        graph.addBond("home-zone", "a");
        graph.addBond("a", "b");
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://lowish:4222", "b", "pk", 0.5, ""));

        // b is 2 hops (0.6 trust) × 0.5 attestation = 0.3
        assertThat(graph.selectRelay(0.5)).isEmpty(); // 0.3 < 0.5
        assertThat(graph.selectRelay(0.2)).isPresent(); // 0.3 >= 0.2
    }

    @Test
    void remove_bond_breaks_trust() {
        graph.addBond("home-zone", "friend");
        assertThat(graph.trustForZone("friend")).isEqualTo(1.0);

        graph.removeBond("home-zone", "friend");
        assertThat(graph.trustForZone("friend")).isEqualTo(0.0);
    }

    @Test
    void stats() {
        graph.addBond("home-zone", "a");
        graph.addBond("home-zone", "b");
        graph.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://r:4222", "a", "pk", 1.0, ""));

        assertThat(graph.bondCount()).isEqualTo(2);
        assertThat(graph.attestationCount()).isEqualTo(1);
        assertThat(graph.knownRelayCount()).isEqualTo(1);
    }
}
