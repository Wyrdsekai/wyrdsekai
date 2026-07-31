package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests dynamic remote backend add/remove in InferenceRouter.
 * Verifies that cross-node inference discovery commands work correctly.
 */
class InferenceRouterRemoteTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void add_remote_backend_appears_in_list() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "default", null));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();

        // Initially empty
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var initial = probe.receiveMessage();
        assertThat(initial.backends()).isEmpty();

        // Add remote backend
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-home-server-llama-server", "llama-server",
            "http://192.0.2.105:8200",
            List.of("wyrdsekai-3.5-4b-v10-q4km"), 110));

        // Wait a moment for the message to process
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // Should appear in list
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var after = probe.receiveMessage();
        assertThat(after.backends()).hasSize(1);
        assertThat(after.backends().getFirst().name()).isEqualTo("remote-home-server-llama-server");
        assertThat(after.backends().getFirst().type()).isEqualTo("llama-server");
        assertThat(after.backends().getFirst().url()).isEqualTo("http://192.0.2.105:8200");
        assertThat(after.backends().getFirst().priority()).isEqualTo(110);
    }

    @Test
    void remove_remote_backend() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "default", null));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();

        // Add then remove
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-relay-node-sglang", "sglang",
            "http://192.0.2.50:8000",
            List.of("qwen-4b"), 120));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        router.tell(new InferenceRouter.RemoveRemoteBackend("remote-relay-node-sglang"));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var result = probe.receiveMessage();
        assertThat(result.backends()).isEmpty();
    }

    @Test
    void remote_backend_sorted_by_priority() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "default", null));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();

        // Add high priority (low number)
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-mac-node", "llama-server",
            "http://192.0.2.200:8200",
            List.of("model-a"), 105));

        // Add low priority (high number)
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-home-server", "llama-server",
            "http://192.0.2.105:8200",
            List.of("model-b"), 150));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var result = probe.receiveMessage();
        assertThat(result.backends()).hasSize(2);
        // mac-node should be first (lower priority number = preferred)
        assertThat(result.backends().get(0).name()).isEqualTo("remote-mac-node");
        assertThat(result.backends().get(1).name()).isEqualTo("remote-home-server");
    }

    @Test
    void household_flag_round_trips_through_backend_info() {
        // a discovered backend tagged household=true
        // (the consumer-preferred household GPU) surfaces as BackendInfo.household().
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "default", null));
        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();

        // 5-arg back-compat ctor → not household.
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-stranger", "llama-server",
            "http://192.0.2.9:8200", List.of("m"), 110));
        // 6-arg with household=true → boosted household GPU.
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-fam-gpu", "llama-server",
            "http://192.0.2.2:8200", List.of("m"), 2, true));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var result = probe.receiveMessage();
        var fam = result.backends().stream()
            .filter(b -> b.name().equals("remote-fam-gpu")).findFirst().orElseThrow();
        var stranger = result.backends().stream()
            .filter(b -> b.name().equals("remote-stranger")).findFirst().orElseThrow();
        assertThat(fam.household()).isTrue();
        assertThat(fam.priority()).isEqualTo(2);
        assertThat(stranger.household()).isFalse();

        // Re-adding the household backend without the tag clears it (update path).
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-fam-gpu", "llama-server",
            "http://192.0.2.2:8200", List.of("m"), 110));
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var after = probe.receiveMessage();
        var famAfter = after.backends().stream()
            .filter(b -> b.name().equals("remote-fam-gpu")).findFirst().orElseThrow();
        assertThat(famAfter.household()).isFalse();
    }

    @Test
    void update_existing_remote_backend() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "default", null));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();

        // Add
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-home-server", "llama-server",
            "http://192.0.2.105:8200",
            List.of("model-old"), 110));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // Update (same name, different URL/model)
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-home-server", "sglang",
            "http://192.0.2.105:8000",
            List.of("model-new"), 115));

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var result = probe.receiveMessage();
        assertThat(result.backends()).hasSize(1);
        assertThat(result.backends().getFirst().type()).isEqualTo("sglang");
        assertThat(result.backends().getFirst().url()).isEqualTo("http://192.0.2.105:8000");
    }
}
