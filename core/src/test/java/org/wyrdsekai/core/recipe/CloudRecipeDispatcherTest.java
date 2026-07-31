package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (option c — BYO cloud). The dispatcher only shells to the
 * steward launch script when the delegate RESOURCE_DENIES; otherwise it passes
 * through. It maps the script's JSON result back into a StartedRun.
 */
class CloudRecipeDispatcherTest {

    private static final String HEAVY_YAML = """
        recipe: heavy
        requires:
          - { kind: gpu_count, amount: 2, hard: true }
          - { kind: wall_clock_min, amount: 600, hard: false }
        steps:
          - id: s1
            kind: SHELL
            command: "echo {}"
        """;

    private static RecipeManifest heavy() { return RecipeParser.parseManifest(HEAVY_YAML); }

    /** Stub CommandRunner returning a canned result; records the command it saw. */
    static final class StubRunner implements CommandRunner {
        final CommandRunner.Result result;
        final AtomicReference<String> sawCommand = new AtomicReference<>();
        StubRunner(CommandRunner.Result r) { this.result = r; }
        public Result run(String command) { sawCommand.set(command); return result; }
    }

    private static RecipeService.StartedRun denied() {
        var d = ResourceRequisiteGate.evaluate(heavy().requires(),
            new ResourceRequisiteGate.Snapshot(List.of(), 64, 100, Set.of(), Set.of()));
        return new RecipeService.StartedRun("local-denied",
            new RecipeRunner.RecipeRun(RecipeRunner.Status.RESOURCE_DENIED, d.summary(),
                List.of(), new RecipeContext(), d));
    }

    private static Path execScript() throws Exception {
        Path p = Files.createTempFile("cloud-launch-", ".sh");
        Files.writeString(p, "#!/usr/bin/env bash\necho ok\n");
        p.toFile().setExecutable(true);
        return p;
    }

    @Test void localSuccessPassesThroughWithoutCloud() {
        RecipeScheduler.Dispatcher localOk = (did, name, params) ->
            new RecipeService.StartedRun("local-ok",
                new RecipeRunner.RecipeRun(RecipeRunner.Status.SUCCESS, "ran here", List.of(), new RecipeContext()));
        var runner = new StubRunner(new CommandRunner.Result(0, "{\"status\":\"SUCCESS\"}", ""));
        var x = new CloudRecipeDispatcher(localOk, () -> "/should/not/matter",
            n -> heavy(), ttl -> runner);
        var run = x.dispatch("did", "heavy", Map.of());
        assertEquals("local-ok", run.runId());
        assertEquals(RecipeRunner.Status.SUCCESS, run.run().status());
        // Cloud script never consulted.
        assertEquals(null, runner.sawCommand.get());
    }

    @Test void deniedWithNoScriptLeavesResourceDenied() {
        var runner = new StubRunner(new CommandRunner.Result(0, "{}", ""));
        var x = new CloudRecipeDispatcher(
            (did, name, params) -> denied(),
            () -> "",                       // no cloud configured
            n -> heavy(), ttl -> runner);
        var run = x.dispatch("did", "heavy", Map.of());
        assertEquals(RecipeRunner.Status.RESOURCE_DENIED, run.run().status());
        assertEquals("local-denied", run.runId());
        assertEquals(null, runner.sawCommand.get());
    }

    @Test void deniedWithScriptRunsCloudAndMapsResult() throws Exception {
        Path script = execScript();
        var runner = new StubRunner(new CommandRunner.Result(0,
            "rented vast box 123\n{\"status\":\"SUCCESS\",\"artifact\":\"/out/adapter.gguf\",\"message\":\"trained\"}",
            ""));
        var x = new CloudRecipeDispatcher(
            (did, name, params) -> denied(),
            script::toString,
            n -> heavy(),
            ttl -> { assertNotNull(ttl); return runner; });
        var run = x.dispatch("did", "heavy", Map.of("rollout_bank", "data/x.jsonl"));
        assertEquals(RecipeRunner.Status.SUCCESS, run.run().status());
        assertTrue(run.run().message().contains("trained"));
        assertEquals("/out/adapter.gguf", run.run().context().get("cloud_artifact"));
        // It shelled the configured script with a jobspec path.
        assertTrue(runner.sawCommand.get().contains(script.toString()));
        assertTrue(runner.sawCommand.get().contains("bash"));
        Files.deleteIfExists(script);
    }

    @Test void cloudScriptFailureMapsToError() throws Exception {
        Path script = execScript();
        var runner = new StubRunner(new CommandRunner.Result(1, "", "no rentable offer"));
        var x = new CloudRecipeDispatcher(
            (did, name, params) -> denied(),
            script::toString, n -> heavy(), ttl -> runner);
        var run = x.dispatch("did", "heavy", Map.of());
        assertEquals(RecipeRunner.Status.ERROR, run.run().status());
        assertTrue(run.run().message().contains("no rentable offer"));
        Files.deleteIfExists(script);
    }

    @Test void ttlDerivedFromWallClockRequirement() throws Exception {
        Path script = execScript();
        var seenTtl = new AtomicReference<Duration>();
        Function<Duration, CommandRunner> factory = ttl -> {
            seenTtl.set(ttl);
            return new StubRunner(new CommandRunner.Result(0, "{\"status\":\"SUCCESS\"}", ""));
        };
        var x = new CloudRecipeDispatcher((did, name, params) -> denied(),
            script::toString, n -> heavy(), factory);
        x.dispatch("did", "heavy", Map.of());
        // wall_clock_min=600 → ttl = ceil(600*1.2) = 720 min.
        assertEquals(Duration.ofMinutes(720), seenTtl.get());
        Files.deleteIfExists(script);
    }
}
