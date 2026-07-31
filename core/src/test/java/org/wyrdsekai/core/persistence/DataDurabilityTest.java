package org.wyrdsekai.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pre-OSS data-durability plumbing (2026-07-09): schema-migration ledger, data-version
 * stamp + downgrade guard, and the authoring_model attribution column.
 */
class DataDurabilityTest {

    @Test
    void fresh_schema_records_all_migrations_in_ledger(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*), MAX(id) FROM schema_migrations");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("all migrations recorded")
                .isEqualTo(SchemaInitializer.SCHEMA_VERSION);
            assertThat(rs.getInt(2)).as("max id == SCHEMA_VERSION")
                .isEqualTo(SchemaInitializer.SCHEMA_VERSION);
        }
    }

    @Test
    void schema_init_is_idempotent_and_does_not_rerun_migrations(@TempDir Path tmp) throws Exception {
        var db = tmp.resolve("world.db");
        SchemaInitializer.initialize(db);
        var jdbc = SchemaInitializer.initialize(db);   // second run — must not duplicate/fail
        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_migrations");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(SchemaInitializer.SCHEMA_VERSION);
        }
    }

    @Test
    void soul_fragments_has_authoring_model_column(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc);
             var stmt = conn.createStatement()) {
            // Insert exercising the column proves it exists on a FRESH schema; migration 8
            // covers pre-existing databases.
            stmt.execute("INSERT INTO soul_fragments (did, fragment_id, authoring_model) "
                + "VALUES ('did:test', 'f1', 'drive=test-model')");
            var rs = stmt.executeQuery(
                "SELECT authoring_model FROM soul_fragments WHERE fragment_id='f1'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("drive=test-model");
        }
    }

    @Test
    void data_version_stamps_fresh_dir(@TempDir Path tmp) throws Exception {
        DataVersion.stampAndGuard(tmp);
        var file = tmp.resolve(DataVersion.FILE_NAME);
        assertThat(file).exists();
        Map<?, ?> info = new ObjectMapper().readValue(file.toFile(), Map.class);
        assertThat(((Number) info.get("schema_version")).intValue())
            .isEqualTo(SchemaInitializer.SCHEMA_VERSION);
        assertThat(info.get("created_by")).isNotNull();
        assertThat(info.get("last_opened_by")).isNotNull();
    }

    @Test
    void data_version_marks_preexisting_dirs_honestly(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("world.db"));   // data predates versioning
        DataVersion.stampAndGuard(tmp);
        Map<?, ?> info = new ObjectMapper()
            .readValue(tmp.resolve(DataVersion.FILE_NAME).toFile(), Map.class);
        assertThat((String) info.get("created_by")).contains("pre-versioning");
    }

    @Test
    void downgrade_guard_refuses_newer_data(@TempDir Path tmp) throws Exception {
        var info = new LinkedHashMap<String, Object>();
        info.put("schema_version", SchemaInitializer.SCHEMA_VERSION + 100);
        info.put("last_opened_by", "9.9.9");
        new ObjectMapper().writeValue(tmp.resolve(DataVersion.FILE_NAME).toFile(), info);
        assertThatThrownBy(() -> DataVersion.stampAndGuard(tmp))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WYRDSEKAI_ALLOW_DOWNGRADE");
    }
}
