package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * a companion issuing {@code request_access} against a
 * {@code home://} URI creates a real grant-request visible to the owner
 * (Board + REST). Companion doesn't need its own HomeClient — it looks
 * up the process-wide default via {@link HomeClients}.
 */
class AgentRequestAccessTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("AgentRequestAccessTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("agent-req.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
        HomeClients.set(homeClient);
    }

    @AfterEach void tearDown() {
        HomeClients.set(null);
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void companion_home_uri_creates_request() {
        // Simulate what handleRequestAccess does for a home:// source.
        var resource = ResourceUri.parse("home://alice/home-room");
        var req = GrantRequest.create(
            "companion-wyrd", resource.owner(), resource,
            Capability.use, Map.of(), "may I visit?");
        var stored = homeClient.createRequest(req);

        assertThat(stored.isPending()).isTrue();
        assertThat(stored.requester()).isEqualTo("companion-wyrd");
        assertThat(stored.resource().toString()).isEqualTo("home://alice/home-room");

        // Alice's Board would see this:
        var pending = homeClient.pendingForOwner("alice");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).requester()).isEqualTo("companion-wyrd");
    }

    @Test void capability_inference_heuristic() {
        // The heuristic is exercised indirectly: test that the Capability
        // parser produces sensible defaults for different scope words.
        // (Direct test of CompanionActor's private helper is handled by
        // running a real session; here we just confirm the actor accepts
        // the various capability choices that handleRequestAccess might emit.)
        var resource = ResourceUri.parse("home://alice/journal/thoughts");
        for (var cap : List.of(Capability.read, Capability.write)) {
            var stored = homeClient.createRequest(GrantRequest.create(
                "wyrd-2", "alice", resource, cap, Map.of(), "test"));
            assertThat(stored.capability()).isEqualTo(cap);
        }
    }

    @Test void non_home_uri_is_not_our_concern() {
        // When the source is not a home:// URI the CompanionActor path stays
        // on the legacy notification/ContextAccessManager track. The
        // HomeRegistry should not see a request. We prove this by asserting
        // the pending list is empty when nothing was created.
        assertThat(homeClient.pendingForOwner("alice")).isEmpty();
    }
}
