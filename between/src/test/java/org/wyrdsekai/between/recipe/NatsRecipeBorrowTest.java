package org.wyrdsekai.between.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.core.recipe.RecipeContext;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeParser;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeScheduler;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.recipe.ResourceRequirement;
import org.wyrdsekai.core.recipe.ResourceRequisiteGate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resource-requisites (option b — peer-zone borrow). Locks the
 * contract that matters: trust gating refuses strangers, an eligible trusted peer
 * runs the recipe and its result maps home, and the local-first / peer-fallback
 * dispatcher only borrows when the local node was RESOURCE_DENIED.
 */
class NatsRecipeBorrowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** In-memory relay routing publishes back to same-subject subscribers, in-thread. */
    static final class FakeTransport extends RelaySessionTransport {
        final Map<String, Consumer<byte[]>> subs = new ConcurrentHashMap<>();
        @Override public boolean isConnected() { return true; }
        @Override public Object subscribe(String subject, Consumer<byte[]> handler) {
            subs.put(subject, handler); return subject;
        }
        @Override public void publish(String subject, byte[] data) {
            var s = subs.get(subject); if (s != null) s.accept(data);
        }
        @Override public void closeDispatcherObj(Object token) {
            if (token instanceof String s) subs.remove(s);
        }
    }

    private static RecipeManifest heavyManifest() {
        return RecipeParserBridge.parse("""
            recipe: run-emit-rft
            requires:
              - { kind: gpu_count, amount: 2, hard: true }
              - { kind: gpu_vram_gb, amount: 48, hard: true }
            steps:
              - id: s1
                kind: SHELL
                command: "echo {}"
            """);
    }

    /** Small indirection so the test reads as intent (parse a recipe by YAML). */
    static final class RecipeParserBridge {
        static RecipeManifest parse(String yaml) {
            return RecipeParser.parseManifest(yaml);
        }
    }

    @Test void protocol_round_trips_request_and_response() throws Exception {
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft",
            Map.of("grpo_steps", 300), "needs 2×48GB");
        var bytes = MAPPER.writeValueAsBytes(req);
        var back = MAPPER.readValue(bytes, NatsRecipeProtocol.Request.class);
        assertThat(back.recipeName()).isEqualTo("run-emit-rft");
        assertThat(back.sourceZone()).isEqualTo("alpha");
        assertThat(back.params()).containsEntry("grpo_steps", 300);
    }

    @Test void trusted_peer_runs_and_result_maps_home() throws Exception {
        var transport = new FakeTransport();
        // Lender side: trust 'alpha', run → SUCCESS.
        var server = new NatsRecipeServer(transport, "beta",
            zone -> zone.equals("alpha"),
            r -> new NatsRecipeServer.Outcome("SUCCESS", "trained ckpt-225", "run-xyz"));
        server.start();

        var client = new NatsRecipeClient(transport, 5);
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft", Map.of(), null);
        var resp = client.borrow("beta", req).get(5, TimeUnit.SECONDS);

        assertThat(resp.ok()).isTrue();
        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(resp.lenderZone()).isEqualTo("beta");
        assertThat(resp.runId()).isEqualTo("run-xyz");
    }

    @Test void untrusted_zone_is_denied_without_reaching_executor() throws Exception {
        var transport = new FakeTransport();
        boolean[] executorRan = {false};
        var server = new NatsRecipeServer(transport, "beta",
            zone -> false,  // trust nobody
            r -> { executorRan[0] = true; return new NatsRecipeServer.Outcome("SUCCESS", "", "x"); });
        server.start();

        var client = new NatsRecipeClient(transport, 5);
        var req = NatsRecipeClient.build("stranger", "did:agent", "run-emit-rft", Map.of(), null);
        var resp = client.borrow("beta", req).get(5, TimeUnit.SECONDS);

        assertThat(resp.status()).isEqualTo("DENIED");
        assertThat(executorRan[0]).as("executor must not run for untrusted zone").isFalse();
    }

    @Test void peer_snapshot_screens_two_big_gpus_in_one_small_out() {
        var requires = heavyManifest().requires();
        // 2 cards × 48GB total 96GB → 48 each → satisfies gpu_count 2 + vram 48.
        var bigPeer = new NodeResources(96L * 1024, 64L * 1024,
            List.of("A6000", "A6000"), List.of(), 10.0, 4);
        var ok = ResourceRequisiteGate.evaluate(requires,
            CrossZoneRecipeDispatcher.peerSnapshot(bigPeer, requires));
        assertThat(ok.allow()).isTrue();

        // 1 card only → fails gpu_count.
        var smallPeer = new NodeResources(48L * 1024, 64L * 1024,
            List.of("A6000"), List.of(), 10.0, 4);
        var no = ResourceRequisiteGate.evaluate(requires,
            CrossZoneRecipeDispatcher.peerSnapshot(smallPeer, requires));
        assertThat(no.allow()).isFalse();
    }

    @Test void dispatcher_borrows_only_when_local_resource_denied() throws Exception {
        var transport = new FakeTransport();
        var server = new NatsRecipeServer(transport, "beta",
            zone -> zone.equals("alpha"),
            r -> new NatsRecipeServer.Outcome("SUCCESS", "borrowed run", "remote-1"));
        server.start();
        var client = new NatsRecipeClient(transport, 5);

        Map<String, NodeResources> peers = Map.of("beta",
            new NodeResources(96L * 1024, 64L * 1024, List.of("A6000", "A6000"), List.of(), 5.0, 4));
        Function<String, RecipeManifest> resolver = name -> heavyManifest();

        // Local delegate that always RESOURCE_DENIES (this node has no GPU).
        RecipeScheduler.Dispatcher localDenies = (did, name, params) -> {
            var denial = ResourceRequisiteGate.evaluate(heavyManifest().requires(),
                new ResourceRequisiteGate.Snapshot(List.of(), 64, 100, Set.of(), Set.of()));
            var run = new RecipeRunner.RecipeRun(RecipeRunner.Status.RESOURCE_DENIED,
                denial.summary(), List.of(), new RecipeContext(), denial);
            return new RecipeService.StartedRun("local-denied", run);
        };

        var x = new CrossZoneRecipeDispatcher(localDenies, client, "alpha",
            () -> peers, zone -> zone.equals("beta"), resolver, 5);
        var started = x.dispatch("did:agent", "run-emit-rft", Map.of());

        assertThat(started.run().status()).isEqualTo(RecipeRunner.Status.SUCCESS);
        assertThat(started.run().message()).contains("borrowed from beta");
        assertThat(started.runId()).isEqualTo("remote-1");

        // When local SUCCEEDS, the dispatcher must NOT borrow.
        RecipeScheduler.Dispatcher localOk = (did, name, params) ->
            new RecipeService.StartedRun("local-ok",
                new RecipeRunner.RecipeRun(RecipeRunner.Status.SUCCESS, "ran here", List.of(),
                    new RecipeContext()));
        var y = new CrossZoneRecipeDispatcher(localOk, client, "alpha",
            () -> peers, zone -> zone.equals("beta"), resolver, 5);
        var localRun = y.dispatch("did:agent", "run-emit-rft", Map.of());
        assertThat(localRun.runId()).isEqualTo("local-ok");
        assertThat(localRun.run().status()).isEqualTo(RecipeRunner.Status.SUCCESS);
    }

    @Test void no_eligible_peer_leaves_resource_denied_for_steward_ask() throws Exception {
        var transport = new FakeTransport();
        var client = new NatsRecipeClient(transport, 5);
        // Only a 1-GPU peer exists → can't satisfy gpu_count 2.
        Map<String, NodeResources> peers = Map.of("beta",
            new NodeResources(48L * 1024, 64L * 1024, List.of("A6000"), List.of(), 5.0, 4));
        Function<String, RecipeManifest> resolver = name -> heavyManifest();
        RecipeScheduler.Dispatcher localDenies = (did, name, params) -> {
            var denial = ResourceRequisiteGate.evaluate(heavyManifest().requires(),
                new ResourceRequisiteGate.Snapshot(List.of(), 64, 100, Set.of(), Set.of()));
            return new RecipeService.StartedRun("local-denied",
                new RecipeRunner.RecipeRun(RecipeRunner.Status.RESOURCE_DENIED, denial.summary(),
                    List.of(), new RecipeContext(), denial));
        };
        var x = new CrossZoneRecipeDispatcher(localDenies, client, "alpha",
            () -> peers, zone -> zone.equals("beta"), resolver, 5);
        var started = x.dispatch("did:agent", "run-emit-rft", Map.of());
        // Unchanged local RESOURCE_DENIED so option (a) — the steward ask — still fires.
        assertThat(started.run().status()).isEqualTo(RecipeRunner.Status.RESOURCE_DENIED);
        assertThat(started.runId()).isEqualTo("local-denied");
    }
}
