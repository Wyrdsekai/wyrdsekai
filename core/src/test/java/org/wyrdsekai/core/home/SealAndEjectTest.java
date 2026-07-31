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
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.StudyProvisioner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent Protection:
 *  - seal/unseal blocks/admits new grant-requests
 *  - eject revokes active home-room use-grants + silent-revokes the ward
 */
class SealAndEjectTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private WardService wardService;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("SealAndEjectTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("seal.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
        wardService = new WardService(jdbc);
        wardService.setGrantSync(new WardGrantSync(homeClient));
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void seal_blocks_new_requests() {
        homeClient.seal("alice", "I'm working");

        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        assertThatThrownBy(() -> homeClient.createRequest(GrantRequest.create(
                "bob", "alice", r, Capability.use, Map.of(), "visit")))
            .hasMessageContaining("sealed");
    }

    @Test void unseal_reopens_home() {
        homeClient.seal("alice", null);
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        assertThatThrownBy(() -> homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), null))).isInstanceOf(Exception.class);

        homeClient.unseal("alice");
        var stored = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), null));
        assertThat(stored.isPending()).isTrue();
    }

    @Test void self_requests_bypass_seal() {
        homeClient.seal("alice", "quiet hours");
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        // Alice can still request things on her own Home (e.g., internal tooling).
        var stored = homeClient.createRequest(GrantRequest.create(
            "alice", "alice", r, Capability.read, Map.of(), "refresh"));
        assertThat(stored.isPending()).isTrue();
    }

    @Test void seal_state_is_queryable() {
        var before = homeClient.sealState("alice");
        assertThat(before.sealed()).isFalse();

        homeClient.seal("alice", "hiding");
        var after = homeClient.sealState("alice");
        assertThat(after.sealed()).isTrue();
        assertThat(after.reason()).isEqualTo("hiding");
        assertThat(after.sealedAt()).isNotNull();

        homeClient.unseal("alice");
        assertThat(homeClient.sealState("alice").sealed()).isFalse();
    }

    @Test void seal_audits_on_alice_home() throws Exception {
        homeClient.seal("alice", "bath time");
        homeClient.unseal("alice");
        var entries = fetchAudit("alice");
        var verbs = entries.stream().map(AuditEntry::verb).toList();
        assertThat(verbs).contains(
            AuditEntry.Verb.HOME_SEALED,
            AuditEntry.Verb.HOME_UNSEALED);
    }

    @Test void seal_is_expressed_as_a_self_grant() {
        // §22.4 principle: boundary held as a grant, not a separate mechanism.
        // A sealed Home shows a self-grant on home-room with scope.sealed=true,
        // visible to the owner on their own Board.
        homeClient.seal("alice", "quiet hours");
        var now = Instant.now();
        var sealGrants = homeClient.listIssuedBy("alice").stream()
            .filter(g -> g.isActive(now))
            .filter(g -> "alice".equals(g.subject()))
            .filter(g -> Boolean.TRUE.equals(g.scope().get("sealed")))
            .toList();
        assertThat(sealGrants).hasSize(1);
        assertThat(sealGrants.get(0).resource().type())
            .isEqualTo(ResourceTypeRegistry.HOME_ROOM);
        assertThat(sealGrants.get(0).scope()).containsEntry("sealReason", "quiet hours");

        homeClient.unseal("alice");
        var after = homeClient.listIssuedBy("alice").stream()
            .filter(g -> g.isActive(Instant.now()))
            .filter(g -> Boolean.TRUE.equals(g.scope().get("sealed")))
            .toList();
        assertThat(after).isEmpty();
    }

    @Test void reseal_replaces_prior_grant() {
        homeClient.seal("alice", "first");
        homeClient.seal("alice", "second");
        var active = homeClient.listIssuedBy("alice").stream()
            .filter(g -> g.isActive(Instant.now()))
            .filter(g -> Boolean.TRUE.equals(g.scope().get("sealed")))
            .toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).scope()).containsEntry("sealReason", "second");
    }

    @Test void eject_revokes_grant_and_ward() throws Exception {
        // Setup: alice has warded her Study; bob has been approved.
        var studyRoom = StudyProvisioner.studyRoomId("alice");
        wardService.grant(studyRoom, "alice", "admin", "alice");
        wardService.grant(studyRoom, "bob", "enter", "alice");
        assertThat(wardService.isAllowed(studyRoom, "bob", "enter")).isTrue();

        // Sanity: bob holds a grant (via the mirror).
        var aliceGrants = homeClient.listIssuedBy("alice").stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(aliceGrants).anySatisfy(g -> assertThat(g.subject()).isEqualTo("bob"));

        // Eject: revoke grant + silent-revoke ward.
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var revoked = homeClient.revokeByKey("alice", "bob", resource, Capability.use);
        assertThat(revoked).isTrue();
        wardService.revokeSilent(studyRoom, "bob", "enter");

        // Bob can no longer enter.
        assertThat(wardService.isAllowed(studyRoom, "bob", "enter")).isFalse();

        // Active grants for bob on alice's Home: none.
        var active = homeClient.listHeldBy("bob").stream()
            .filter(g -> g.isActive(Instant.now()))
            .filter(g -> g.resource().owner().equals("alice"))
            .toList();
        assertThat(active).isEmpty();
    }

    private List<AuditEntry> fetchAudit(String owner) throws Exception {
        return AskPattern
            .<HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
                homeClient.registry(),
                replyTo -> new HomeRegistryActor.QueryAudit(owner, null, 100, replyTo),
                Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS).entries();
    }
}
