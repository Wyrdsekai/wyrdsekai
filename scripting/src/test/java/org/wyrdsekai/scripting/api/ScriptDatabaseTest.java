package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ScriptDatabase — sandboxed SQLite access for scripts.
 */
class ScriptDatabaseTest {

    @TempDir
    Path workspace;

    private ScriptDatabase db;

    @BeforeEach
    void setUp() {
        db = new ScriptDatabase(workspace, "test.db");
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void create_table_and_insert() {
        db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
        int affected = db.execute("INSERT INTO items (name) VALUES (?)", "Sword");
        assertThat(affected).isEqualTo(1);
    }

    @Test
    void query_returns_results() {
        db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, value INTEGER)");
        db.execute("INSERT INTO items (name, value) VALUES (?, ?)", "Sword", 100);
        db.execute("INSERT INTO items (name, value) VALUES (?, ?)", "Shield", 50);

        var results = db.query("SELECT * FROM items ORDER BY name");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("name")).isEqualTo("Shield");
        assertThat(results.get(1).get("name")).isEqualTo("Sword");
        assertThat(results.get(1).get("value")).isEqualTo(100);
    }

    @Test
    void execute_returns_affected_rows() {
        db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
        db.execute("INSERT INTO items (name) VALUES (?)", "A");
        db.execute("INSERT INTO items (name) VALUES (?)", "B");
        db.execute("INSERT INTO items (name) VALUES (?)", "C");

        int deleted = db.execute("DELETE FROM items WHERE name IN (?, ?)", "A", "B");
        assertThat(deleted).isEqualTo(2);
    }

    @Test
    void close_releases_connection() {
        db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
        assertThat(db.isOpen()).isTrue();
        db.close();
        assertThat(db.isOpen()).isFalse();
    }

    @Test
    void parameterized_queries_prevent_injection() {
        db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        db.execute("INSERT INTO users (name) VALUES (?)", "Alice");

        // This should NOT be interpreted as SQL — the parameter is treated as a literal string
        var results = db.query("SELECT * FROM users WHERE name = ?",
            "'; DROP TABLE users; --");
        assertThat(results).isEmpty();

        // Table should still exist and contain data
        var all = db.query("SELECT * FROM users");
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().get("name")).isEqualTo("Alice");
    }

    @Test
    void path_traversal_blocked() {
        assertThatThrownBy(() -> new ScriptDatabase(workspace, "../escape.db"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path separators");
    }

    @Test
    void blank_db_name_blocked() {
        assertThatThrownBy(() -> new ScriptDatabase(workspace, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }
}
