package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ConversationCheckpoint;
import org.wyrdsekai.core.agent.TaskPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ConversationPersistence using temp-file SQLite.
 */
class ConversationPersistenceTest {

    private ConversationPersistence persistence;
    private Path tempDb;
    private String jdbcUrl;

    @BeforeEach
    void setup() throws SQLException, IOException {
        tempDb = Files.createTempFile("wyrd-test-", ".db");
        jdbcUrl = "jdbc:sqlite:" + tempDb.toAbsolutePath();

        // Create table
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS conversation_checkpoints (
                    agent_id TEXT PRIMARY KEY,
                    checkpoint_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        }
        persistence = new ConversationPersistence(jdbcUrl);
    }

    @AfterEach
    void teardown() throws IOException {
        if (tempDb != null) Files.deleteIfExists(tempDb);
    }

    @Test
    void save_and_load_checkpoint() {
        var plan = TaskPlan.create("p1", "find books", "u1", "mas",
            List.of("Navigate", "Search"));
        var checkpoint = new ConversationCheckpoint(
            "agent-ember",
            List.of("10:00 went to Library", "10:01 searched for dragons"),
            plan,
            Instant.now()
        );

        persistence.save(checkpoint);

        var loaded = persistence.load("agent-ember");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().agentId()).isEqualTo("agent-ember");
        assertThat(loaded.get().workingMemory()).hasSize(2);
        assertThat(loaded.get().workingMemory().get(0)).contains("Library");
        assertThat(loaded.get().activePlan()).isNotNull();
        assertThat(loaded.get().activePlan().description()).isEqualTo("find books");
    }

    @Test
    void save_upserts_on_conflict() {
        var cp1 = new ConversationCheckpoint(
            "agent-ember", List.of("first"), null, Instant.now());
        persistence.save(cp1);

        var cp2 = new ConversationCheckpoint(
            "agent-ember", List.of("second", "third"), null, Instant.now());
        persistence.save(cp2);

        var loaded = persistence.load("agent-ember");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().workingMemory()).hasSize(2);
        assertThat(loaded.get().workingMemory().get(0)).isEqualTo("second");
    }

    @Test
    void load_nonexistent_returns_empty() {
        var loaded = persistence.load("agent-nonexistent");
        assertThat(loaded).isEmpty();
    }

    @Test
    void delete_removes_checkpoint() {
        var cp = new ConversationCheckpoint(
            "agent-ember", List.of("data"), null, Instant.now());
        persistence.save(cp);

        assertThat(persistence.load("agent-ember")).isPresent();

        persistence.delete("agent-ember");

        assertThat(persistence.load("agent-ember")).isEmpty();
    }

    @Test
    void delete_nonexistent_is_silent() {
        // Should not throw
        persistence.delete("agent-nonexistent");
    }

    @Test
    void checkpoint_with_complex_plan_round_trips() {
        var plan = TaskPlan.create("plan-1", "research mythology", "user-1", "mas",
            List.of("Go to Library", "Search for mythology books", "Read the best one", "Report findings"));
        plan.recordAttempt("go_to_room", "library", "arrived", true);
        plan.advanceGoal("navigated");
        plan.recordAttempt("library_search", "mythology", "found 5 results", true);

        var checkpoint = new ConversationCheckpoint(
            "agent-ember",
            List.of("10:00 navigated to Library", "10:01 searched mythology (5 results)"),
            plan,
            Instant.now()
        );

        persistence.save(checkpoint);

        var loaded = persistence.load("agent-ember");
        assertThat(loaded).isPresent();
        var restoredPlan = loaded.get().activePlan();
        assertThat(restoredPlan).isNotNull();
        assertThat(restoredPlan.goals()).hasSize(4);
        assertThat(restoredPlan.description()).isEqualTo("research mythology");
    }

    @Test
    void multiple_agents_have_independent_checkpoints() {
        persistence.save(new ConversationCheckpoint(
            "agent-ember", List.of("ember data"), null, Instant.now()));
        persistence.save(new ConversationCheckpoint(
            "agent-claude", List.of("claude data"), null, Instant.now()));

        var ember = persistence.load("agent-ember");
        var claude = persistence.load("agent-claude");

        assertThat(ember).isPresent();
        assertThat(claude).isPresent();
        assertThat(ember.get().workingMemory().get(0)).isEqualTo("ember data");
        assertThat(claude.get().workingMemory().get(0)).isEqualTo("claude data");

        persistence.delete("agent-ember");
        assertThat(persistence.load("agent-ember")).isEmpty();
        assertThat(persistence.load("agent-claude")).isPresent();
    }
}
