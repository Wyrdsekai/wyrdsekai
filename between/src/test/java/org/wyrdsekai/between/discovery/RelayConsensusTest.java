package org.wyrdsekai.between.discovery;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RelayConsensusTest {

    @Test
    void single_authority_signs_and_verifies() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var pubKeyB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        var consensus = RelayConsensus.singleAuthority(pubKeyB64);

        var relays = List.of(
            new RelayConsensus.RelayEntry("nats://relay1:4222", true, 500, 42, "us-east", "zone-1"),
            new RelayConsensus.RelayEntry("nats://relay2:4222", true, 300, 100, "eu-west", "zone-2")
        );

        var doc = RelayConsensus.createConsensus(relays, 1);
        var signed = RelayConsensus.signConsensus(doc, "auth-1", pubKeyB64, kp.getPrivate());

        assertThat(signed.votes()).hasSize(1);
        assertThat(consensus.verifyVotes(signed)).isEqualTo(1);
        assertThat(consensus.acceptConsensus(signed)).isTrue();

        var available = consensus.availableRelays();
        assertThat(available).hasSize(2);
    }

    @Test
    void two_of_three_authority_threshold() throws Exception {
        var kp1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var kp2 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var kp3 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var pk1 = Base64.getEncoder().encodeToString(kp1.getPublic().getEncoded());
        var pk2 = Base64.getEncoder().encodeToString(kp2.getPublic().getEncoded());
        var pk3 = Base64.getEncoder().encodeToString(kp3.getPublic().getEncoded());

        var consensus = new RelayConsensus(
            RelayConsensus.AuthorityConfig.defaultConfig(pk1, pk2, pk3));

        var relays = List.of(
            new RelayConsensus.RelayEntry("nats://relay:4222", true, 500, 0, "us", "z1"));
        var doc = RelayConsensus.createConsensus(relays, 1);

        // Sign with only 1 authority — should NOT be enough
        var signedBy1 = RelayConsensus.signConsensus(doc, "auth-1", pk1, kp1.getPrivate());
        assertThat(consensus.verifyVotes(signedBy1)).isEqualTo(1);
        assertThat(consensus.acceptConsensus(signedBy1)).isFalse();

        // Sign with 2 authorities — should be enough
        var signedBy2 = RelayConsensus.signConsensus(signedBy1, "auth-2", pk2, kp2.getPrivate());
        assertThat(consensus.verifyVotes(signedBy2)).isEqualTo(2);
        assertThat(consensus.acceptConsensus(signedBy2)).isTrue();
    }

    @Test
    void unknown_authority_vote_not_counted() throws Exception {
        var knownKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var unknownKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var knownPk = Base64.getEncoder().encodeToString(knownKp.getPublic().getEncoded());
        var unknownPk = Base64.getEncoder().encodeToString(unknownKp.getPublic().getEncoded());

        var consensus = RelayConsensus.singleAuthority(knownPk);

        var doc = RelayConsensus.createConsensus(List.of(), 1);
        var signedByUnknown = RelayConsensus.signConsensus(doc, "rogue", unknownPk, unknownKp.getPrivate());

        assertThat(consensus.verifyVotes(signedByUnknown)).isEqualTo(0);
        assertThat(consensus.acceptConsensus(signedByUnknown)).isFalse();
    }

    @Test
    void expired_consensus_falls_back_to_cache() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var pk = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        var consensus = RelayConsensus.singleAuthority(pk);

        // Accept a valid consensus first
        var relays = List.of(
            new RelayConsensus.RelayEntry("nats://cached:4222", true, 500, 0, "us", "z1"));
        var doc = RelayConsensus.createConsensus(relays, 1);
        var signed = RelayConsensus.signConsensus(doc, "auth-1", pk, kp.getPrivate());
        consensus.acceptConsensus(signed);

        // Now accept an already-expired one — cached should remain
        var expiredDoc = new RelayConsensus.ConsensusDocument(2, List.of(), List.of(),
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        var signedExpired = RelayConsensus.signConsensus(expiredDoc, "auth-1", pk, kp.getPrivate());
        consensus.acceptConsensus(signedExpired);

        // Should fall back to the cached version
        var current = consensus.currentConsensus();
        assertThat(current).isPresent();
    }

    @Test
    void relay_entry_capacity_check() {
        var full = new RelayConsensus.RelayEntry("nats://full:4222", true, 500, 500, "us", "z1");
        var available = new RelayConsensus.RelayEntry("nats://open:4222", true, 500, 200, "us", "z2");

        assertThat(full.hasCapacity()).isFalse();
        assertThat(available.hasCapacity()).isTrue();
        assertThat(full.utilizationPercent()).isCloseTo(100.0, offset(0.1));
        assertThat(available.utilizationPercent()).isCloseTo(40.0, offset(0.1));
    }

    @Test
    void available_relays_filters_full_and_private() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var pk = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        var consensus = RelayConsensus.singleAuthority(pk);

        var relays = List.of(
            new RelayConsensus.RelayEntry("nats://open:4222", true, 500, 100, "us", "z1"),
            new RelayConsensus.RelayEntry("nats://full:4222", true, 500, 500, "us", "z2"),
            new RelayConsensus.RelayEntry("nats://private:4222", false, 10, 1, "us", "z3")
        );
        var doc = RelayConsensus.createConsensus(relays, 1);
        var signed = RelayConsensus.signConsensus(doc, "auth-1", pk, kp.getPrivate());
        consensus.acceptConsensus(signed);

        var available = consensus.availableRelays();
        assertThat(available).hasSize(1);
        assertThat(available.get(0).url()).isEqualTo("nats://open:4222");
    }
}
