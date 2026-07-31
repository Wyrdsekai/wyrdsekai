package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.FragmentKind;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-B B1 — seed loader unit tests.
 */
class ReleaseBakeSeedLoaderTest {

    @Test
    void loadSeeds_empty_dir_returns_empty(@TempDir Path tmp) {
        var seeds = ReleaseBakeSeedLoader.loadSeeds(tmp);
        assertThat(seeds).isEmpty();
    }

    @Test
    void loadSeeds_nonexistent_dir_returns_empty(@TempDir Path tmp) {
        var seeds = ReleaseBakeSeedLoader.loadSeeds(tmp.resolve("does-not-exist"));
        assertThat(seeds).isEmpty();
    }

    @Test
    void loadSeeds_well_formed_seed_produces_DEXTERITY_fragment(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("task_present-soul-fragment-seed.json"),
            """
            {
              "schema": "wyrdsekai.release-evidence.soul-fragment-seed.v1",
              "head": "task_present",
              "recipe": "retrain-classifier-head",
              "bake_did": "did:wyrd:release-bake",
              "baked_at": "2026-05-25T09:00:00Z",
              "baseline_sha256": "abc",
              "evolved_sha256": "def",
              "fragment": {
                "id": "recipe-retrain-classifier-head-20260525090000",
                "kind": "DEXTERITY",
                "category": "procedure",
                "label": "Recipe run: retrain-classifier-head",
                "text": "I ran the recipe and it succeeded. val_accuracy 0.95."
              }
            }
            """);
        var seeds = ReleaseBakeSeedLoader.loadSeeds(tmp);
        assertThat(seeds).hasSize(1);
        var f = seeds.get(0);
        assertThat(f.id()).startsWith("recipe-retrain-classifier-head-");
        assertThat(f.kind()).isEqualTo(FragmentKind.DEXTERITY);
        assertThat(f.category()).isEqualTo("procedure");
        assertThat(f.text()).contains("val_accuracy 0.95");
    }

    @Test
    void loadSeeds_skips_wrong_schema(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a-soul-fragment-seed.json"),
            """
            {"schema": "something-else", "fragment": {"id": "x", "text": "y"}}
            """);
        assertThat(ReleaseBakeSeedLoader.loadSeeds(tmp)).isEmpty();
    }

    @Test
    void loadSeeds_skips_missing_fragment_block(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("b-soul-fragment-seed.json"),
            """
            {"schema": "wyrdsekai.release-evidence.soul-fragment-seed.v1"}
            """);
        assertThat(ReleaseBakeSeedLoader.loadSeeds(tmp)).isEmpty();
    }

    @Test
    void loadSeeds_skips_fragment_with_no_id_or_text(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("c-soul-fragment-seed.json"),
            """
            {"schema": "wyrdsekai.release-evidence.soul-fragment-seed.v1",
             "fragment": {"label": "no id no text"}}
            """);
        assertThat(ReleaseBakeSeedLoader.loadSeeds(tmp)).isEmpty();
    }

    @Test
    void loadSeeds_results_are_deterministic_across_filename_order(@TempDir Path tmp) throws Exception {
        // Write in reverse alphabetical order — loader must sort by name.
        Files.writeString(tmp.resolve("zeta-soul-fragment-seed.json"),
            seedJson("zeta-id", "zeta text"));
        Files.writeString(tmp.resolve("alpha-soul-fragment-seed.json"),
            seedJson("alpha-id", "alpha text"));
        var seeds = ReleaseBakeSeedLoader.loadSeeds(tmp);
        assertThat(seeds).extracting(f -> f.id())
            .containsExactly("alpha-id", "zeta-id");
    }

    @Test
    void loadSeeds_ignores_non_matching_filenames(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("baseline-task_present.onnx"), "binary");
        Files.writeString(tmp.resolve("task_present-recipe-run-1.json"), "{}");
        Files.writeString(tmp.resolve("task_present-soul-fragment-seed.json"),
            seedJson("x", "x"));
        var seeds = ReleaseBakeSeedLoader.loadSeeds(tmp);
        assertThat(seeds).hasSize(1);
    }

    @Test
    void defaultEvidenceDir_resolves_relative_to_data_dir() {
        var dataDir = Path.of("/var/wyrdsekai");
        assertThat(ReleaseBakeSeedLoader.defaultEvidenceDir(dataDir).toString())
            .isEqualTo("/var/wyrdsekai/release-evidence");
        assertThat(ReleaseBakeSeedLoader.defaultEvidenceDir(null)).isNull();
    }

    private static String seedJson(String id, String text) {
        return """
            {
              "schema": "wyrdsekai.release-evidence.soul-fragment-seed.v1",
              "fragment": {
                "id": "%s",
                "kind": "DEXTERITY",
                "category": "procedure",
                "label": "label",
                "text": "%s"
              }
            }
            """.formatted(id, text);
    }
}
