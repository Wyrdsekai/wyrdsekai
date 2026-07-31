package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-backed SQLite database exposed to GraalJS scripts.
 * Available at {@link org.wyrdsekai.scripting.sandbox.SandboxLevel#SKILL_DATA} and above.
 *
 * <p>The database file is confined to the workspace directory.
 * Uses parameterized queries to prevent SQL injection.
 *
 * <p>Scripts use this as:
 * <pre>
 *   var db = new Database("mydata.db");
 *   db.execute("CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY, name TEXT)");
 *   db.execute("INSERT INTO items (name) VALUES (?)", "Sword");
 *   var rows = db.query("SELECT * FROM items WHERE name = ?", "Sword");
 *   db.close();
 * </pre>
 */
public class ScriptDatabase {

    private static final Logger log = LoggerFactory.getLogger(ScriptDatabase.class);

    private final Path dbPath;
    private Connection connection;

    /**
     * Create a database connection to a SQLite file within the workspace.
     *
     * @param workspaceRoot The workspace root directory
     * @param dbName        The database file name (relative to workspace, no path separators)
     */
    public ScriptDatabase(Path workspaceRoot, String dbName) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("Workspace root must not be null");
        }
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("Database name must not be blank");
        }
        // Prevent path traversal
        if (dbName.contains("..") || dbName.contains("/") || dbName.contains("\\")) {
            throw new IllegalArgumentException("Database name must not contain path separators: " + dbName);
        }
        this.dbPath = workspaceRoot.resolve(dbName).normalize();
        // Verify the resolved path is still within workspace
        if (!this.dbPath.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Database path escapes workspace: " + dbName);
        }
    }

    private Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        }
        return connection;
    }

    /**
     * Execute a query and return results as a list of maps.
     * Uses parameterized queries for safety.
     *
     * @param sql    SQL SELECT statement with ? placeholders
     * @param params Parameters to bind (positional)
     * @return List of rows, each a map of column name to value
     */
    @HostAccess.Export
    public List<Map<String, Object>> query(String sql, Object... params) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        try {
            var conn = getConnection();
            try (var stmt = prepareStatement(conn, sql, params);
                 var rs = stmt.executeQuery()) {
                return resultSetToList(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a statement (INSERT, UPDATE, DELETE, CREATE, etc.).
     * Uses parameterized queries for safety.
     *
     * @param sql    SQL statement with ? placeholders
     * @param params Parameters to bind (positional)
     * @return Number of affected rows
     */
    @HostAccess.Export
    public int execute(String sql, Object... params) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        try {
            var conn = getConnection();
            try (var stmt = prepareStatement(conn, sql, params)) {
                return stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Execute failed: " + e.getMessage(), e);
        }
    }

    /**
     * Close the database connection. Should be called when done.
     */
    @HostAccess.Export
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Failed to close database: {}", e.getMessage());
            }
            connection = null;
        }
    }

    /**
     * Check if the database connection is open.
     */
    @HostAccess.Export
    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private static PreparedStatement prepareStatement(Connection conn, String sql, Object... params)
        throws SQLException {
        var stmt = conn.prepareStatement(sql);
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
        }
        return stmt;
    }

    private static List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        var results = new ArrayList<Map<String, Object>>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            var row = new HashMap<String, Object>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            results.add(row);
        }
        return results;
    }
}
