package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.ConversationCheckpoint;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * JDBC persistence for {@link ConversationCheckpoint}.
 *
 * <p>Single row per agent (upsert). Used for crash recovery of volatile
 * agent state: working memory, active plan, and vitality snapshot.</p>
 */
public final class ConversationPersistence {

    private static final Logger log = LoggerFactory.getLogger(ConversationPersistence.class);

    private final String jdbcUrl;

    public ConversationPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Save (upsert) a conversation checkpoint.
     */
    public void save(ConversationCheckpoint checkpoint) {
        var sql = """
            INSERT INTO conversation_checkpoints (agent_id, checkpoint_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(agent_id) DO UPDATE SET checkpoint_json = excluded.checkpoint_json,
                                                 updated_at = excluded.updated_at
            """;
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, checkpoint.agentId());
            stmt.setString(2, checkpoint.toJson());
            stmt.setString(3, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to save conversation checkpoint for {}: {}", checkpoint.agentId(), e.getMessage());
        }
    }

    /**
     * Load the most recent checkpoint for an agent.
     */
    public Optional<ConversationCheckpoint> load(String agentId) {
        var sql = "SELECT checkpoint_json FROM conversation_checkpoints WHERE agent_id = ?";
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, agentId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var json = rs.getString("checkpoint_json");
                var checkpoint = ConversationCheckpoint.fromJson(json);
                return Optional.ofNullable(checkpoint);
            }
        } catch (SQLException e) {
            log.warn("Failed to load conversation checkpoint for {}: {}", agentId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Delete the checkpoint for an agent (called on sleep or plan completion).
     */
    public void delete(String agentId) {
        var sql = "DELETE FROM conversation_checkpoints WHERE agent_id = ?";
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, agentId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete conversation checkpoint for {}: {}", agentId, e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
