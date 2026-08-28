package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A good item written one directory down is still a good item.
 *
 * <h2>The live loss</h2>
 * 2026-08-21, the first time the generated adapter surface reached an authoring model:
 * goose wrote a genuinely correct weather item — {@code capabilities: ["nominatim.geocode",
 * "openweather.current"]}, embodiment, two commands — to {@code build/Weather.js}.
 *
 * <p>The bridge reduced the declared path to its BASENAME and resolved it against the
 * workspace root, which silently discards a subdirectory. It found nothing, and placed a
 * nameless <i>"A goose codex containing 1 file(s)"</i>. The work was done, correct, and
 * thrown away over a directory separator.
 *
 * <p>The workspace is the task's own scratch directory, so anything {@code .js} inside it
 * was put there by this run — there is no reason to insist it sit at the top.
 */
class AnItemInASubdirectoryStillCountsTest {

    @TempDir Path workspace;

    private static String corpus(String name) throws Exception {
        try (var in = AnItemInASubdirectoryStillCountsTest.class
                .getResourceAsStream("/items/corpus/" + name)) {
            if (in == null) throw new IllegalStateException("missing corpus: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SourceArtifact declaring(List<String> files) {
        return new SourceArtifact(UUID.randomUUID(), GooseBackend.NAME, "task-sub",
            workspace.toString(), files, null, Instant.now(), Map.of());
    }

    @Test
    void a_manifest_one_directory_down_is_found() throws Exception {
        var nested = Files.createDirectories(workspace.resolve("build"));
        Files.writeString(nested.resolve("Weather.js"), corpus("in_a_subdirectory.js"));

        var src = declaring(List.of("build/Weather.js"));
        assertThat(CodingTaskItemBridge.manifestNameOf(src))
            .as("the item names itself; the bridge must not fall back to 'codex'")
            .contains("weather-tool");
    }

    /** The exact shape that lost it: an ABSOLUTE path whose basename is not at the root. */
    @Test
    void an_absolute_path_into_a_subdirectory_survives() throws Exception {
        var nested = Files.createDirectories(workspace.resolve("build"));
        var file = nested.resolve("Weather.js");
        Files.writeString(file, corpus("in_a_subdirectory.js"));

        var src = declaring(List.of(file.toAbsolutePath().toString()));
        assertThat(CodingTaskItemBridge.manifestNameOf(src)).contains("weather-tool");
    }

    /** Even with nothing declared at all, the workspace is this task's own. */
    @Test
    void a_run_that_declared_nothing_is_still_searched() throws Exception {
        var nested = Files.createDirectories(workspace.resolve("out/deep"));
        Files.writeString(nested.resolve("thing.js"), corpus("in_a_subdirectory.js"));

        assertThat(CodingTaskItemBridge.manifestNameOf(declaring(List.of())))
            .contains("weather-tool");
    }

    /** A declared path that really is at the root is still preferred and found. */
    @Test
    void a_root_level_declared_file_still_works() throws Exception {
        Files.writeString(workspace.resolve("accepted.js"), corpus("accepted.js"));
        assertThat(CodingTaskItemBridge.manifestNameOf(declaring(List.of("accepted.js"))))
            .isPresent();
    }

    /** No script anywhere means no name — not an invented one. */
    @Test
    void an_empty_workspace_yields_nothing() {
        assertThat(CodingTaskItemBridge.manifestNameOf(declaring(List.of()))).isEmpty();
    }

    /** And this item really does use the household's keyed services. */
    @Test
    void the_recorded_item_reaches_for_the_adapters_not_the_open_web() throws Exception {
        var text = corpus("in_a_subdirectory.js");
        assertThat(text).contains("nominatim.geocode").contains("openweather.current");
        assertThat(text)
            .as("the generated surface did its job — no scraping")
            .doesNotContain("world.web.search");
    }
}
