package org.wyrdsekai.core.test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Test utility for creating in-memory SQLite databases with full schema.
 * Each call returns a unique shared-cache in-memory DB to avoid cross-test pollution.
 *
 * IMPORTANT: The returned JDBC URL uses SQLite shared cache mode. The schema
 * connection is kept alive so the in-memory DB persists across service connections.
 * Call the returned holder's close() in @AfterEach if cleanup is desired.
 */
public final class TestDb {

    private TestDb() {}

    /**
     * Create a shared in-memory SQLite database with schema initialized.
     * The database persists as long as the JVM is running (one keeper connection stays open).
     *
     * @return JDBC URL for the initialized database
     */
    public static String createInMemory() {
        var dbName = "test-" + UUID.randomUUID().toString().substring(0, 8);
        var jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        initializeSchema(jdbcUrl);
        return jdbcUrl;
    }

    @SuppressWarnings("resource") // Intentionally keeping connection open
    private static void initializeSchema(String jdbcUrl) {
        try {
            // This connection is intentionally NOT closed — it keeps the in-memory DB alive.
            // For tests this is fine; the JVM shuts down after tests complete.
            var keepAlive = DriverManager.getConnection(jdbcUrl);

            var sql = loadSchemaResource("/schema/sqlite-create-schema.sql");

            // Strip comment lines, split on semicolons
            var cleaned = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

            for (var statement : cleaned.split(";")) {
                var trimmed = statement.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("PRAGMA")) continue;
                try (var stmt = keepAlive.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize test database", e);
        }
    }

    private static String loadSchemaResource(String resource) {
        try (var is = TestDb.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new RuntimeException("Schema resource not found: " + resource);
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read schema resource", e);
        }
    }
}
