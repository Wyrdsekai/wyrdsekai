package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.classifier.ClassifierArm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — service: load (dir + classpath), list, inspect, run, status. */
class RecipeServiceTest {

    private static CommandRunner okRunner() {
        return command -> command.contains("train")
                ? new CommandRunner.Result(0, "{\"val_accuracy\": 0.9}", "")
                : new CommandRunner.Result(0, "", "");
    }

    @Test void lists_and_inspects_bundled_classpath_recipe(@TempDir Path dir) {
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        List<RecipeService.Summary> all = svc.list();
        assertTrue(all.stream().anyMatch(s -> s.name().equals("retrain-classifier-head")),
                "bundled recipe should be listed even with an empty dir");
        RecipeManifest m = svc.inspect("retrain-classifier-head");
        assertTrue(m.deploys());
        assertEquals(RecipeManifest.Ownership.RUN, m.ownership());
    }

    @Test void household_dir_recipe_is_discovered_and_runs(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("hello.recipe.yaml"), """
            recipe: hello
            steps:
              - { id: a, kind: SHELL, command: "echo hi" }
            """);
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        assertTrue(svc.list().stream().anyMatch(s -> s.name().equals("hello")));

        var started = svc.run("hello", Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, started.run().status());
        // run is retained for status lookup
        assertTrue(svc.status(started.runId()).isPresent());
        assertEquals(RecipeRunner.Status.SUCCESS, svc.status(started.runId()).get().status());
    }

    @Test void household_recipe_cannot_shadow_bundled_name(@TempDir Path dir) throws IOException {
        // INTENT FLIPPED 2026-08-16 (was: "a household file takes precedence",
        // a dev convenience). Bundled recipes carry welfare:permanent gates —
        // sleep-forge writes companion WEIGHTS behind them — and a file dropped
        // straight into data/recipes/ bypasses AuthoredRecipeValidator's
        // shadow check entirely. So loadManifest now loads bundled names from
        // the classpath ONLY. Dev iteration edits the source resource instead.
        Files.writeString(dir.resolve("retrain-classifier-head.recipe.yaml"), """
            recipe: retrain-classifier-head
            description: local override
            steps:
              - { id: a, kind: SHELL, command: "echo override" }
            """);
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        assertNotEquals("local override", svc.inspect("retrain-classifier-head").description(),
                "bundled recipe must load from the classpath, never a household impostor");
    }

    @Test void unknown_recipe_throws(@TempDir Path dir) {
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        assertThrows(RecipeValidationException.class, () -> svc.inspect("does-not-exist"));
    }

    @Test void rejects_path_traversal_names(@TempDir Path dir) {
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        assertThrows(RecipeValidationException.class, () -> svc.inspect("../secrets"));
        assertThrows(RecipeValidationException.class, () -> svc.run("a/b", Map.of()));
    }

    @Test void a_run_bound_to_a_companion_knows_her_classifier_folder(@TempDir Path dir) throws IOException {
        // The scheduler binds a RecipeService to the companion a run belongs to;
        // the run then deploys into HER folder, the one her ClassifierArm reads.
        Files.writeString(dir.resolve("hers.recipe.yaml"), """
            recipe: hers
            params: { deploy_dir: { type: string, default: "{{agent_classifier_dir}}" } }
            steps:
              - { id: deploy, kind: SHELL, command: "cp new {{deploy_dir}}" }
            """);
        var ran = new ArrayList<String>();
        CommandRunner recording = command -> { ran.add(command); return new CommandRunner.Result(0, "", ""); };
        var did = "did:key:z6MkserviceTest";
        var svc = new RecipeService(dir, new RecipeRunner(recording), did);
        var started = svc.run("hers", Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, started.run().status(), started.run().message());
        assertEquals("cp new " + ClassifierArm.perAgentDirFor(did), ran.get(0));
        assertEquals(did, started.run().context().get("agent_did"));
    }
}
