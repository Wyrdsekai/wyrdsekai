package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void household_recipe_shadows_bundled_name(@TempDir Path dir) throws IOException {
        // A household file named like a bundled recipe takes precedence.
        Files.writeString(dir.resolve("retrain-classifier-head.recipe.yaml"), """
            recipe: retrain-classifier-head
            description: local override
            steps:
              - { id: a, kind: SHELL, command: "echo override" }
            """);
        var svc = new RecipeService(dir, new RecipeRunner(okRunner()));
        assertEquals("local override", svc.inspect("retrain-classifier-head").description());
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
}
