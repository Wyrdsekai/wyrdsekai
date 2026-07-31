package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * grant-request lifecycle — create, approve, deny, cancel.
 */
class GrantRequestTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("GrantRequestTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("grants.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void approve_mints_grant_and_closes_request() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = GrantRequest.create("bob", "alice", resource,
            Capability.use, Map.of(), "visiting you");
        var stored = homeClient.createRequest(req);
        assertThat(stored.isPending()).isTrue();

        assertThat(homeClient.pendingForOwner("alice")).hasSize(1);

        var approved = homeClient.approveRequest(stored.id(), "alice", null, "come in");
        assertThat(approved.status()).isEqualTo(GrantRequest.Status.approved);
        assertThat(approved.issuedGrantId()).isNotNull();
        assertThat(approved.responderNote()).isEqualTo("come in");

        // Grant exists and bob now holds it.
        var held = homeClient.listHeldBy("bob");
        assertThat(held).anySatisfy(g -> {
            assertThat(g.id()).isEqualTo(approved.issuedGrantId());
            assertThat(g.resource().toString()).isEqualTo(resource.toString());
            assertThat(g.capability()).isEqualTo(Capability.use);
            assertThat(g.isActive(Instant.now())).isTrue();
        });

        // Pending list is empty.
        assertThat(homeClient.pendingForOwner("alice")).isEmpty();
    }

    @Test void deny_closes_request_without_grant() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = GrantRequest.create("bob", "alice", resource,
            Capability.use, Map.of(), "stranger");
        var stored = homeClient.createRequest(req);
        var denied = homeClient.denyRequest(stored.id(), "alice", "not now");

        assertThat(denied.status()).isEqualTo(GrantRequest.Status.denied);
        assertThat(denied.issuedGrantId()).isNull();
        assertThat(homeClient.listHeldBy("bob")).isEmpty();
        assertThat(homeClient.pendingForOwner("alice")).isEmpty();
    }

    @Test void cancel_closes_request_by_requester() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = GrantRequest.create("bob", "alice", resource,
            Capability.use, Map.of(), "on second thought");
        var stored = homeClient.createRequest(req);
        var cancelled = homeClient.cancelRequest(stored.id(), "bob");

        assertThat(cancelled.status()).isEqualTo(GrantRequest.Status.cancelled);
        assertThat(homeClient.pendingForOwner("alice")).isEmpty();
    }

    @Test void non_owner_cannot_approve() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var stored = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", resource, Capability.use, Map.of(), null));

        assertThatThrownBy(() -> homeClient.approveRequest(stored.id(), "carol", null, null))
            .hasMessageContaining("not the resource owner");
    }

    @Test void non_requester_cannot_cancel() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var stored = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", resource, Capability.use, Map.of(), null));

        assertThatThrownBy(() -> homeClient.cancelRequest(stored.id(), "carol"))
            .hasMessageContaining("not the requester");
    }

    @Test void cannot_approve_twice() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var stored = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", resource, Capability.use, Map.of(), null));
        homeClient.approveRequest(stored.id(), "alice", null, null);

        assertThatThrownBy(() -> homeClient.approveRequest(stored.id(), "alice", null, null))
            .hasMessageContaining("approved");
    }

    @Test void invalid_resource_type_rejected() {
        var resource = ResourceUri.of("alice", "not-a-real-type", "x");
        assertThatThrownBy(() -> homeClient.createRequest(
            GrantRequest.create("bob", "alice", resource, Capability.use, Map.of(), null)))
            .hasMessageContaining("unknown resource type");
    }

    @Test void requests_by_requester_returns_history() {
        var r1 = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var r2 = ResourceUri.of("carol", ResourceTypeRegistry.HOME_ROOM);
        homeClient.createRequest(GrantRequest.create("bob", "alice", r1, Capability.use, Map.of(), null));
        homeClient.createRequest(GrantRequest.create("bob", "carol", r2, Capability.use, Map.of(), null));

        var mine = homeClient.requestsByRequester("bob");
        assertThat(mine).hasSize(2);
    }

    @Test void audit_records_request_and_response() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var stored = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", resource, Capability.use, Map.of(), "hi"));
        homeClient.approveRequest(stored.id(), "alice", null, null);

        // Alice's audit log should contain GRANT_REQUESTED + GRANT_ISSUED + GRANT_APPROVED.
        var verbs = homeClient.pendingForOwner("alice");  // flush to actor
        // Actually fetch the audit log:
        var entries = AskPattern.<
                HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
            homeClient.registry(),
            replyTo -> new HomeRegistryActor.QueryAudit("alice", null, 50, replyTo),
            Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().join().entries();
        var verbSet = entries.stream().map(e -> e.verb()).toList();
        assertThat(verbSet).contains(
            AuditEntry.Verb.GRANT_REQUESTED,
            AuditEntry.Verb.GRANT_ISSUED,
            AuditEntry.Verb.GRANT_APPROVED);
    }
}
