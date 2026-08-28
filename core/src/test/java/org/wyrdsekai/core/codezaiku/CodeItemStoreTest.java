package org.wyrdsekai.core.codezaiku;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.BuildArtifact;
import org.wyrdsekai.core.coding.CodeZaikuBackend;
import org.wyrdsekai.core.coding.SourceArtifact;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence tests for {@link CodeItemStore} after the Phase 2 cleanup.
 * The store now reads / writes {@link SourceArtifact} / {@link BuildArtifact}
 * directly; the {@code codex_items} / {@code artifact_items} SQL tables
 * survive intact for disk-compat with in-flight households.
 */
class CodeItemStoreTest {

    private CodeItemStore store;
    private Path dbPath;

    @BeforeEach void setUp(@TempDir Path tempDir) {
        dbPath = tempDir.resolve("code-items-test.db");
        store = new CodeItemStore("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static SourceArtifact source(String codexId, String workspace,
            String boardId, String language, List<String> files) {
        return new SourceArtifact(
            UUID.nameUUIDFromBytes(("codeplane-codex-" + codexId).getBytes()),
            CodeZaikuBackend.NAME,
            boardId,
            workspace,
            files,
            null,
            Instant.parse("2026-03-17T12:00:00Z"),
            Map.of(
                "codexId", codexId,
                "boardId", boardId,
                "hostNode", "node-1",
                "language", language != null ? language : "",
                "createdBy", "did:key:alice"
            )
        );
    }

    private static BuildArtifact build(String artifactId, String codexId,
            String boardId, String status, int passed, int failed) {
        return new BuildArtifact(
            UUID.nameUUIDFromBytes(("codeplane-artifact-" + artifactId).getBytes()),
            CodeZaikuBackend.NAME,
            boardId,
            codexId,
            status,
            passed,
            failed,
            Instant.parse("2026-03-17T12:00:00Z"),
            Map.of(
                "artifactId", artifactId,
                "boardId", boardId,
                "hostNode", "node-1",
                "artifactType", "jar",
                "artifactPath", "/tmp/build"
            )
        );
    }

    @Test void save_and_find_source_artifact() {
        var src = source("c1", "/tmp/ws", "board-1", "Java", List.of("Main.java"));

        store.saveSource(src);
        var found = store.findSource("c1");

        assertThat(found).isPresent();
        assertThat(found.get().backend()).isEqualTo(CodeZaikuBackend.NAME);
        assertThat(found.get().workspacePath()).isEqualTo("/tmp/ws");
        assertThat(found.get().taskId()).isEqualTo("board-1");
        assertThat(found.get().files()).containsExactly("Main.java");
        // Legacy CodeZaiku-specific extras land in backendMetadata.
        assertThat(found.get().backendMetadata())
            .containsEntry("codexId", "c1")
            .containsEntry("hostNode", "node-1")
            .containsEntry("language", "Java")
            .containsEntry("createdBy", "did:key:alice");
    }

    @Test void save_and_find_build_artifact() {
        var b = build("a1", "c1", "board-1", "success", 10, 2);

        store.saveBuild(b);
        var found = store.findBuild("a1");

        assertThat(found).isPresent();
        assertThat(found.get().backend()).isEqualTo(CodeZaikuBackend.NAME);
        assertThat(found.get().sourceArtifactId()).isEqualTo("c1");
        assertThat(found.get().testsPassed()).isEqualTo(10);
        assertThat(found.get().testsFailed()).isEqualTo(2);
        assertThat(found.get().status()).isEqualTo("success");
        assertThat(found.get().backendMetadata())
            .containsEntry("artifactPath", "/tmp/build")
            .containsEntry("artifactType", "jar");
    }

    @Test void find_builds_by_source_id() {
        store.saveBuild(build("a1", "c1", "board-1", "success", 5, 0));
        store.saveBuild(build("a2", "c1", "board-1", "failed", 3, 1));
        store.saveBuild(build("a3", "c2", "board-2", "untested", 0, 0));

        var builds = store.findBuildsBySource("c1");
        assertThat(builds).hasSize(2);
        assertThat(builds).extracting(b -> (String) b.backendMetadata().get("artifactId"))
            .containsExactlyInAnyOrder("a1", "a2");
    }

    @Test void list_all_sources() {
        store.saveSource(source("c1", "/tmp/ws1", "board-1", "Java", List.of("A.java")));
        store.saveSource(source("c2", "/tmp/ws2", "board-2", "Python", List.of("main.py")));

        var sources = store.listSources();
        assertThat(sources).hasSize(2);
    }

    @Test void delete_source_artifact() {
        store.saveSource(source("c1", "/tmp/ws", "board-1", "Java", List.of()));

        assertThat(store.findSource("c1")).isPresent();
        assertThat(store.deleteSource("c1")).isTrue();
        assertThat(store.findSource("c1")).isEmpty();
    }

    @Test void delete_build_artifact() {
        store.saveBuild(build("a1", "c1", "board-1", "untested", 0, 0));

        assertThat(store.findBuild("a1")).isPresent();
        assertThat(store.deleteBuild("a1")).isTrue();
        assertThat(store.findBuild("a1")).isEmpty();
    }

    @Test void unknown_id_returns_empty() {
        assertThat(store.findSource("nonexistent")).isEmpty();
        assertThat(store.findBuild("nonexistent")).isEmpty();
        assertThat(store.findBuildsBySource("nonexistent")).isEmpty();
    }

    @Test void multiple_sources_with_different_metadata() {
        store.saveSource(source("c1", "/tmp/ws1", "board-1", "Java", List.of("A.java", "B.java")));
        store.saveSource(source("c2", "/tmp/ws2", "board-2", "Python", List.of("main.py")));
        store.saveSource(source("c3", "/tmp/ws3", "board-3", null, List.of()));

        var sources = store.listSources();
        assertThat(sources).hasSize(3);

        var c1 = store.findSource("c1");
        assertThat(c1).isPresent();
        assertThat(c1.get().files()).hasSize(2);

        var c3 = store.findSource("c3");
        assertThat(c3).isPresent();
        assertThat(c3.get().files()).isEmpty();
    }

    /**
     * Migration-path test: write a row using the legacy schema (no
     * {@code backend} / {@code metadata_json} columns), then re-init the
     * store and confirm the dual-read shim hands the row back as a
     * {@link SourceArtifact} with sensible defaults.
     */
    @Test void legacy_row_without_backend_columns_reads_as_codezaiku_source(
            @TempDir Path legacyDir) throws Exception {
        var legacyPath = legacyDir.resolve("legacy.db");
        var legacyUrl = "jdbc:sqlite:" + legacyPath.toAbsolutePath();

        // Hand-craft a pre-Phase-2 schema (no backend / metadata_json
        // columns) and insert a single row.
        try (var conn = DriverManager.getConnection(legacyUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE codex_items (
                    codex_id TEXT PRIMARY KEY,
                    workspace_path TEXT NOT NULL,
                    host_node TEXT NOT NULL,
                    board_id TEXT NOT NULL,
                    language TEXT,
                    files_json TEXT,
                    git_ref TEXT,
                    created_at TEXT,
                    created_by TEXT
                )""");
            stmt.execute("""
                INSERT INTO codex_items VALUES (
                    'legacy-1', '/old/ws', 'old-node', 'old-board', 'Java',
                    '["Foo.java"]', 'abc1234',
                    '2025-01-01T00:00:00Z', 'did:key:legacy'
                )""");
        }

        // Re-open through the new store. Schema migration should add the
        // missing columns and the row should round-trip as a CodeZaiku
        // SourceArtifact.
        var migrated = new CodeItemStore(legacyUrl);
        var found = migrated.findSource("legacy-1");
        assertThat(found).isPresent();
        assertThat(found.get().backend()).isEqualTo(CodeZaikuBackend.NAME);
        assertThat(found.get().workspacePath()).isEqualTo("/old/ws");
        assertThat(found.get().taskId()).isEqualTo("old-board");
        assertThat(found.get().files()).containsExactly("Foo.java");
        assertThat(found.get().gitRef()).isEqualTo("abc1234");
        assertThat(found.get().backendMetadata())
            .containsEntry("codexId", "legacy-1")
            .containsEntry("boardId", "old-board")
            .containsEntry("hostNode", "old-node")
            .containsEntry("language", "Java")
            .containsEntry("createdBy", "did:key:legacy");
    }
}
