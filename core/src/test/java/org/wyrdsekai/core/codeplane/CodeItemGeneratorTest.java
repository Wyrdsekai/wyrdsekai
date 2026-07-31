package org.wyrdsekai.core.codeplane;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.CodePlaneBackend;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeItemGeneratorTest {

    private CodeItemStore store;
    private CodeItemGenerator generator;

    @BeforeEach void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("generator-test.db");
        store = new CodeItemStore("jdbc:sqlite:" + dbPath.toAbsolutePath());
        generator = new CodeItemGenerator(store);
    }

    @Test void generate_from_board_completion_with_build() {
        var result = generator.generateFromBoardCompletion(
            "board-1", "/tmp/ws", "gpu-host", "Java",
            List.of("Main.java", "Test.java"), "did:key:alice",
            10, 2, "success");

        assertThat(result.source()).isNotNull();
        assertThat(result.source().backend()).isEqualTo(CodePlaneBackend.NAME);
        assertThat(result.source().taskId()).isEqualTo("board-1");
        assertThat(result.source().workspacePath()).isEqualTo("/tmp/ws");
        assertThat(result.source().files()).hasSize(2);
        assertThat(result.source().backendMetadata())
            .containsEntry("hostNode", "gpu-host")
            .containsEntry("language", "Java")
            .containsEntry("createdBy", "did:key:alice");

        assertThat(result.build()).isNotNull();
        assertThat(result.build().backend()).isEqualTo(CodePlaneBackend.NAME);
        // Generator picks the legacy 8-hex codexId; sourceArtifactId points
        // back at it so the build can be looked up via findBuildsBySource.
        var codexId = (String) result.source().backendMetadata().get("codexId");
        assertThat(codexId).isNotBlank();
        assertThat(result.build().sourceArtifactId()).isEqualTo(codexId);
        assertThat(result.build().testsPassed()).isEqualTo(10);
        assertThat(result.build().testsFailed()).isEqualTo(2);
        assertThat(result.build().status()).isEqualTo("success");
    }

    @Test void generate_without_build_no_artifact() {
        var result = generator.generateFromBoardCompletion(
            "board-1", "/tmp/ws", "gpu-host", "Python",
            List.of("main.py"), "did:key:bob",
            0, 0, null);

        assertThat(result.source()).isNotNull();
        assertThat(result.build()).isNull();
    }

    @Test void generated_ids_are_unique() {
        var ids = new HashSet<String>();
        for (int i = 0; i < 10; i++) {
            var result = generator.generateFromBoardCompletion(
                "board-" + i, "/tmp/ws" + i, "node", "Java",
                List.of("File.java"), "alice", 0, 0, "success");
            ids.add((String) result.source().backendMetadata().get("codexId"));
            ids.add((String) result.build().backendMetadata().get("artifactId"));
        }
        // 10 codex IDs + 10 artifact IDs = 20 unique IDs
        assertThat(ids).hasSize(20);
    }

    @Test void items_persisted_to_store() {
        var result = generator.generateFromBoardCompletion(
            "board-1", "/tmp/ws", "gpu-host", "Java",
            List.of("Main.java"), "did:key:alice",
            5, 0, "success");

        var codexId = (String) result.source().backendMetadata().get("codexId");
        var artifactId = (String) result.build().backendMetadata().get("artifactId");
        assertThat(store.findSource(codexId)).isPresent();
        assertThat(store.findBuild(artifactId)).isPresent();
    }

    @Test void source_and_build_linked_by_codex_id() {
        var result = generator.generateFromBoardCompletion(
            "board-1", "/tmp/ws", "gpu-host", "Java",
            List.of("Main.java"), "did:key:alice",
            5, 1, "success");

        var codexId = (String) result.source().backendMetadata().get("codexId");
        var builds = store.findBuildsBySource(codexId);

        assertThat(builds).hasSize(1);
        assertThat(builds.getFirst().sourceArtifactId()).isEqualTo(codexId);
        assertThat((String) builds.getFirst().backendMetadata().get("artifactId"))
            .isEqualTo(result.build().backendMetadata().get("artifactId"));
    }
}
