package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — RecipeRunLog (the sleep-pass Forge seam) + RecipeService recording. */
class RecipeRunLogTest {

    private static RecipeForgeIngester.CompletedRun completedRun(String name) {
        var run = new RecipeRunner.RecipeRun(
                RecipeRunner.Status.SUCCESS, "ok", List.of(), new RecipeContext());
        return new RecipeForgeIngester.CompletedRun(name, false, run);
    }

    @Test void record_then_drain_returns_runs_then_empties() {
        String did = "did:key:log-" + UUID.randomUUID();
        var log = RecipeRunLog.get();
        log.record(did, completedRun("recipe-a"));
        log.record(did, completedRun("recipe-b"));

        var drained = log.drain(did);
        assertEquals(2, drained.size());
        assertEquals("recipe-a", drained.get(0).recipeName());
        assertEquals("recipe-b", drained.get(1).recipeName());

        // Destructive: a second drain sees nothing (each run feeds one Forge pass).
        assertTrue(log.drain(did).isEmpty());
        assertEquals(0, log.pending(did));
    }

    @Test void null_or_blank_did_is_a_noop() {
        var log = RecipeRunLog.get();
        log.record(null, completedRun("x"));
        log.record("  ", completedRun("x"));
        assertTrue(log.drain(null).isEmpty());
        assertTrue(log.drain("").isEmpty());
    }

    @Test void recipe_service_with_agent_did_records_completed_run() {
        String did = "did:key:svc-" + UUID.randomUUID();
        // Stub command runner (exit 0); classpath-bundled recipe is loaded by name.
        // Run status is irrelevant here — the point is the completed run is recorded
        // under the agent DID so completeSleep can ingest it.
        var svc = new RecipeService(null,
                new RecipeRunner(c -> new CommandRunner.Result(0, "", "")), did);
        svc.run("retrain-classifier-head", Map.of());

        var drained = RecipeRunLog.get().drain(did);
        assertEquals(1, drained.size());
        assertEquals("retrain-classifier-head", drained.get(0).recipeName());
    }

    @Test void recipe_service_without_agent_did_does_not_record() {
        // The 2-arg constructor (tests / non-agent contexts) must not touch the log.
        String probeDid = "did:key:none-" + UUID.randomUUID();
        var svc = new RecipeService(null,
                new RecipeRunner(c -> new CommandRunner.Result(0, "", "")));
        svc.run("retrain-classifier-head", Map.of());
        assertTrue(RecipeRunLog.get().drain(probeDid).isEmpty());
    }
}
