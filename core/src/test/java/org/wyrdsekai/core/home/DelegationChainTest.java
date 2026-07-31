package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.RevocationMode;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * delegated grants must be subsets of their parent.
 * The registry rejects delegations that broaden capability, drop scope
 * keys, or extend expiry past the parent.
 */
class DelegationChainTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("DelegationChainTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("delegation.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private Grant issueAliceDelegateToBob(Map<String, Object> scope, Instant expiresAt) {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        return homeClient.issue(Grant.issue(
            "alice", "bob", resource, Capability.delegate,
            scope, Instant.now(), expiresAt, "delegate to bob"));
    }

    @Test void child_within_parent_expiry_accepted() {
        var parent = issueAliceDelegateToBob(Map.of(),
            Instant.now().plusSeconds(86400));
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        var child = homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null, "sub-delegate", null, parent.id()));
        assertThat(child.delegatedFrom()).isEqualTo(parent.id());
    }

    @Test void child_outlives_parent_rejected() {
        var parent = issueAliceDelegateToBob(Map.of(),
            Instant.now().plusSeconds(3600));
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        assertThatThrownBy(() -> homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(),
            Instant.now().plusSeconds(86400),
            null, null, null, parent.id())))
            .hasMessageContaining("after parent");
    }

    @Test void child_without_expiry_when_parent_has_one_rejected() {
        var parent = issueAliceDelegateToBob(Map.of(),
            Instant.now().plusSeconds(3600));
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        assertThatThrownBy(() -> homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(), null, null, null, null, parent.id())))
            .hasMessageContaining("expire no later than parent");
    }

    @Test void child_drops_parent_scope_key_rejected() {
        var parent = issueAliceDelegateToBob(Map.of("collection", "library"), null);
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        assertThatThrownBy(() -> homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(), null, null, null, null, parent.id())))
            .hasMessageContaining("missing parent key");
    }

    @Test void child_narrows_scope_accepted() {
        var parent = issueAliceDelegateToBob(Map.of("collection", "library"), null);
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        var child = homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read,
            Map.of("collection", "library", "shelf", "fiction"),
            RevocationMode.standard,
            Instant.now(), null, null, null, null, parent.id()));
        assertThat(child.scope()).containsEntry("shelf", "fiction");
    }

    @Test void unknown_parent_rejected() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        assertThatThrownBy(() -> homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "bob", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(), null, null, null, null, "nonexistent-parent-id")))
            .hasMessageContaining("unknown grant");
    }

    @Test void non_matching_parent_subject_rejected() {
        // Parent gives delegate to Bob, but issuer tries to claim it as Dave.
        var parent = issueAliceDelegateToBob(Map.of(), null);
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library");
        assertThatThrownBy(() -> homeClient.issue(new Grant(
            UUID.randomUUID().toString(),
            "dave", "carol", resource, Capability.read, Map.of(),
            RevocationMode.standard,
            Instant.now(), null, null, null, null, parent.id())))
            .hasMessageContaining("does not hold parent grant");
    }
}
