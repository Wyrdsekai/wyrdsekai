package org.wyrdsekai.between.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the full relay discovery cascade.
 */
class RelayDiscoveryServiceTest {

    @TempDir
    Path tempDir;

    private KademliaTable dht;
    private RelayTrustGraph trust;
    private RelayConsensus consensus;
    private RelayDiscoveryService service;

    @BeforeEach
    void setup() {
        dht = KademliaTable.create("test-node");
        trust = new RelayTrustGraph("home-zone");
        consensus = RelayConsensus.singleAuthority("dummy-key");

        service = new RelayDiscoveryService(tempDir, dht, trust, consensus,
            List.of("nats://seed.example.com:4222"), "nonexistent.example.com");
    }

    @Test
    void empty_discovery_falls_back_to_seeds() {
        var result = service.discover();

        assertThat(result.relays()).hasSize(1);
        assertThat(result.method()).isEqualTo("seed");
        assertThat(result.relays().get(0).url()).isEqualTo("nats://seed.example.com:4222");
    }

    @Test
    void dht_relays_discovered_before_seeds() {
        dht.storeRelay("nats://dht-relay:4222", true, 500, 42,
            dht.localNodeId(), null);

        var result = service.discover();

        assertThat(result.method()).isEqualTo("dht");
        assertThat(result.relays()).hasSize(1);
        assertThat(result.relays().get(0).url()).isEqualTo("nats://dht-relay:4222");
    }

    @Test
    void cache_is_used_on_second_discover() {
        // First discover — goes to seeds
        dht.storeRelay("nats://cached-relay:4222", true, 500, 42,
            dht.localNodeId(), null);
        var first = service.discover();
        assertThat(first.method()).isEqualTo("dht");

        // Second discover — should use cache
        var second = service.discover();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.relays()).hasSize(1);
    }

    @Test
    void trust_score_applied_to_discovered_relays() {
        trust.addBond("home-zone", "friend-zone");
        trust.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://trusted-relay:4222", "friend-zone", "pk", 1.0, "good"));

        dht.storeRelay("nats://trusted-relay:4222", true, 500, 42,
            dht.localNodeId(), null);

        var result = service.discover();
        assertThat(result.relays()).hasSize(1);
        assertThat(result.relays().get(0).trustScore()).isGreaterThan(0.5);
    }

    @Test
    void dns_txt_parsing() {
        var relay = service.parseDnsTxtRelay(
            "relay=nats://relay.example.com:4222 region=us-east capacity=1000");

        assertThat(relay).isNotNull();
        assertThat(relay.url()).isEqualTo("nats://relay.example.com:4222");
        assertThat(relay.region()).isEqualTo("us-east");
        assertThat(relay.capacity()).isEqualTo(1000);
    }

    @Test
    void dns_txt_parsing_minimal() {
        var relay = service.parseDnsTxtRelay("relay=nats://minimal:4222");

        assertThat(relay).isNotNull();
        assertThat(relay.url()).isEqualTo("nats://minimal:4222");
        assertThat(relay.region()).isEqualTo("unknown");
        assertThat(relay.capacity()).isEqualTo(500);
    }

    @Test
    void dns_txt_parsing_no_relay_returns_null() {
        assertThat(service.parseDnsTxtRelay("garbage data")).isNull();
    }

    @Test
    void relay_config_public_vs_private() {
        var pub = RelayDiscoveryService.RelayConfig.publicRelay(500, "us-east");
        assertThat(pub.publicRelay()).isTrue();
        assertThat(pub.capacity()).isEqualTo(500);
        assertThat(pub.announceToNetwork()).isTrue();

        var priv = RelayDiscoveryService.RelayConfig.privateRelay();
        assertThat(priv.publicRelay()).isFalse();
        assertThat(priv.capacity()).isEqualTo(1);
        assertThat(priv.announceToNetwork()).isFalse();
    }

    @Test
    void full_discovery_integration() {
        // Set up bonds and attestations
        trust.addBond("home-zone", "friend");
        trust.addAttestation(RelayTrustGraph.TrustAttestation.create(
            "nats://friend-relay:4222", "friend", "pk", 1.0, "reliable"));

        // Store relay in DHT
        dht.storeRelay("nats://friend-relay:4222", true, 500, 100,
            dht.localNodeId(), null);
        dht.storeRelay("nats://unknown-relay:4222", true, 500, 50,
            dht.localNodeId(), null);

        var result = service.discover();
        assertThat(result.method()).isEqualTo("dht");
        assertThat(result.relays()).hasSize(2);

        // The friend-attested relay should have higher trust
        var friendRelay = result.relays().stream()
            .filter(r -> r.url().equals("nats://friend-relay:4222"))
            .findFirst();
        var unknownRelay = result.relays().stream()
            .filter(r -> r.url().equals("nats://unknown-relay:4222"))
            .findFirst();

        assertThat(friendRelay).isPresent();
        assertThat(unknownRelay).isPresent();
        assertThat(friendRelay.get().trustScore())
            .isGreaterThan(unknownRelay.get().trustScore());
    }
}
