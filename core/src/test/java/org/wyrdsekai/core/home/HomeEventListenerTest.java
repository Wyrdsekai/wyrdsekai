package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every lifecycle point fires a HomeEventListener event with the correct
 * owner / actor / subject / resource shape.
 */
class HomeEventListenerTest {

    record Event(
        HomeEventListener.Kind kind,
        String owner, String actor, String subject, String resource, String detail) {}

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private ActorRef<HomeRegistryActor.Command> registry;
    private final List<Event> captured = new CopyOnWriteArrayList<>();
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("HomeEventListenerTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("listener.db"));
        var store = new HomeStore(jdbc);
        HomeEventListener listener = (kind, owner, actor, subject, resource, detail) ->
            captured.add(new Event(kind, owner, actor, subject, resource, detail));
        registry = testKit.spawn(HomeRegistryActor.create(store, listener));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void grant_request_flow_fires_requested_and_approved() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), "visiting"));

        assertThat(captured).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_REQUESTED);
            assertThat(e.owner()).isEqualTo("alice");
            assertThat(e.subject()).isEqualTo("bob");
            assertThat(e.detail()).isEqualTo("visiting");
        });

        homeClient.approveRequest(req.id(), "alice", null, "come in");
        assertThat(captured).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_APPROVED);
            assertThat(e.actor()).isEqualTo("alice");
            assertThat(e.subject()).isEqualTo("bob");
        });
        // Issue also fires because the approve path mints a Grant.
        assertThat(captured).anySatisfy(e -> assertThat(e.kind())
            .isEqualTo(HomeEventListener.Kind.GRANT_ISSUED));
    }

    @Test void deny_fires_event() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), null));
        homeClient.denyRequest(req.id(), "alice", "not now");

        assertThat(captured).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_DENIED);
            assertThat(e.subject()).isEqualTo("bob");
        });
    }

    @Test void direct_issue_and_revoke_fire_events() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var g = homeClient.issue(Grant.issue(
            "alice", "bob", r, Capability.read, Map.of(),
            Instant.now(), null, null));
        assertThat(captured).anySatisfy(e ->
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_ISSUED));

        homeClient.revoke(g.id(), "alice");
        assertThat(captured).anySatisfy(e ->
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_REVOKED));
    }

    @Test void seal_and_unseal_fire_events() {
        homeClient.seal("alice", "bath time");
        homeClient.unseal("alice");
        var kinds = captured.stream().map(Event::kind).toList();
        assertThat(kinds).contains(
            HomeEventListener.Kind.HOME_SEALED,
            HomeEventListener.Kind.HOME_UNSEALED);
    }

    @Test void reap_fires_expired_events() throws Exception {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "old");
        homeClient.issue(Grant.issue(
            "alice", "bob", r, Capability.read, Map.of(),
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(30),
            "already stale"));
        AskPattern.<HomeRegistryActor.Command, HomeRegistryActor.ExpiryReport>ask(
                registry,
                replyTo -> new HomeRegistryActor.ReapExpiredGrants(replyTo),
                Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(captured).anySatisfy(e ->
            assertThat(e.kind()).isEqualTo(HomeEventListener.Kind.GRANT_EXPIRED));
    }

    @Test void failing_listener_does_not_break_actor() {
        // Replace listener with one that throws — writes still succeed.
        HomeEventListener failing = (k, a, b, c, d, e) -> {
            throw new RuntimeException("kaboom");
        };
        var store = new HomeStore(
            SchemaInitializer.initialize(
                workspace.resolve("listener-fail.db")));
        var reg = testKit.spawn(HomeRegistryActor.create(store, failing));
        var hc = new HomeClient(reg, testKit.system());

        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = hc.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), null));
        assertThat(req.isPending()).isTrue();
    }
}
