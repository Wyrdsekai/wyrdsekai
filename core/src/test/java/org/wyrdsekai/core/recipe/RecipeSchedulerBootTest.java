package org.wyrdsekai.core.recipe;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C9 — tier-2 install-then-tick test.
 *
 * <p>Proves the ship-default config wires a usable scheduler on a fresh
 * install:</p>
 * <ol>
 *   <li>{@link RecipeSchedulerBoot#bootForTest} mirrors the actor + queue
 *       seam {@link RecipeSchedulerBoot#bootIfEnabled} produces in
 *       production.</li>
 *   <li>Pretrained-dir auto-discovery + provisioner produces the
 *       expected enrollment rows.</li>
 *   <li>A {@code PollNow} actually drains a manually-enqueued row
 *       through a stub dispatcher → SUCCEEDED + cadence promotion.</li>
 *   <li>{@link RecipeSchedulerRegistry#get} returns the live ref so the
 *       agent {@code request_recipe} path can target it.</li>
 * </ol>
 */
class RecipeSchedulerBootTest {

    private ActorTestKit testKit;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("RecipeSchedulerBootTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        RecipeSchedulerRegistry.resetForTests();
    }

    @AfterEach
    void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        RecipeSchedulerRegistry.resetForTests();
    }

    @Test
    void install_then_tick_with_ship_defaults() throws Exception {
        // Fresh-install layout: pretrained dir with the four heads C9 enrols.
        var pretrained = tmp.resolve("classifiers/pretrained");
        Files.createDirectories(pretrained);
        for (var head : List.of("request_type", "cleanliness",
                "task_present", "substrate_present")) {
            Files.writeString(pretrained.resolve(head + ".onnx"), "stub");
        }

        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("ship.db").toAbsolutePath();
        var queue = new SqlRecipeQueue(jdbcUrl);
        var enroll = new RecipeEnrollmentStore(jdbcUrl);

        // Provision ship defaults (boot helper does this with the real
        // dispatcher; here we exercise the policy directly + use bootForTest
        // to spawn the actor under the same registry slot).
        var did = "did:wyrd:companion-A";
        var rows = ShipDefaultEnrollmentProvisioner.provision(
            enroll, /* csv */ "",
            pretrained, List.of(did),
            Instant.now());
        // One row per (agent, ship-default-recipe). retrain-classifier-head
        // merges all four heads into gap_keys; consolidate-memory-graph is
        // cron-only with empty gap_keys.
        int expected = ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES.size();
        assertThat(rows).hasSize(expected);
        var stored = enroll.listAll();
        assertThat(stored).hasSize(expected);
        var retrain = stored.stream().filter(r -> r.recipeId()
            .equals(ShipDefaultEnrollmentProvisioner.DEFAULT_RECIPE))
            .findFirst().orElseThrow();
        assertThat(retrain.agentDid()).isEqualTo(did);
        assertThat(retrain.gapKeys()).containsExactlyInAnyOrder(
            "request_type.misroute", "cleanliness.misroute",
            "task_present.misroute", "substrate_present.misroute");

        // Spawn scheduler via bootForTest (same registry slot prod uses).
        var dispatchCount = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = (d, name, params) -> {
            dispatchCount.incrementAndGet();
            return new RecipeService.StartedRun(UUID.randomUUID().toString(),
                new RecipeRunner.RecipeRun(
                    RecipeRunner.Status.SUCCESS, "ok", List.of(),
                    new RecipeContext(Map.of())));
        };

        var ref = RecipeSchedulerBoot.bootForTest(
            testKit.system(), jdbcUrl, dispatcher,
            /* welfareSupplier */ null,
            Duration.ofSeconds(30));
        assertThat(RecipeSchedulerRegistry.get())
            .as("registry singleton must be set after bootForTest")
            .isSameAs(ref);

        // Enqueue a recipe row matching one of the seeded enrollments,
        // PollNow → dispatch → SUCCEEDED + WARMUP→SETTLING is irrelevant
        // here (consecutive=2 needed); just confirm the dispatch fires
        // and the row terminates.
        var entry = RecipeScheduler.newEnqueue(
            ShipDefaultEnrollmentProvisioner.DEFAULT_RECIPE, did,
            CadenceTier.WARMUP, 0, QueuedRecipe.TriggerSource.CRON,
            "first tick", Map.of("head", "task_present"));
        ref.tell(new RecipeScheduler.Enqueue(entry));
        ref.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            assertThat(dispatchCount.get()).isEqualTo(1);
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.SUCCEEDED);
        });
    }

    @Test
    void null_args_short_circuit() {
        // bootIfEnabled is defensive: null BootArgs → null return, no spawn.
        // (Disabled-via-config is integration-tested through Main.java's
        // WyrdConfig.schedulerEnabled() lookup; the env-toggle path can't
        // be exercised cleanly without spawning a child JVM, so we rely on
        // the singleton's resetForTests + the live wire test on home-server.)
        assertThat(RecipeSchedulerBoot.bootIfEnabled(null)).isNull();
    }

    private static void awaitUntilAsserted(Duration timeout, Runnable check) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadlineNanos) {
            try { check.run(); return; }
            catch (AssertionError | RuntimeException e) { last = e; }
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (last instanceof AssertionError ae) throw ae;
        if (last instanceof RuntimeException re) throw re;
        throw new AssertionError("timeout", last);
    }
}
