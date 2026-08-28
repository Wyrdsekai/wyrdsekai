package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

/**
 * THE single place that answers "which person is this?".
 *
 * <p><b>Why this exists.</b> One human on a household node was being referred to
 * by four different strings at once — a Unix username (the Study content owner),
 * a UUID from {@code AuthService.register()} (bonds, residency, inventory,
 * telemetry), a mobile placeholder {@code 'local-user'}, and in some places
 * nothing at all. Each arose the same way: code needed an owner, had no way to
 * resolve one, and picked a plausible string instead of failing. See
 * </p>
 *
 * <p><b>The contract that prevents it recurring:</b> this returns
 * {@link Optional#empty()} for anything it cannot resolve. It never invents,
 * never falls back, never defaults. Callers must treat empty as "refuse the
 * write", not as "use something else".</p>
 *
 * <p>Resolution order — first match wins:</p>
 * <ol>
 *   <li>a {@code did:key:…} that exists in {@code person_identities}</li>
 *   <li>a legacy account UUID ({@code users.id}) with a mapped person DID</li>
 *   <li>a username ({@code users.username}) with a mapped person DID</li>
 *   <li>a display name in {@code player_accounts}</li>
 * </ol>
 */
public class PersonIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonIdentityResolver.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final PersonIdentityStore identities;

    public PersonIdentityResolver(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        this.identities = new PersonIdentityStore(jdbcUrl);
        ensureUsersDidColumn();
    }

    /**
     * Add {@code users.did} — the foreign key from a local credential to the
     * person it belongs to. {@code users} holds password hashes, ssh keys and
     * roles: facts about one machine. The person is separate and portable, and
     * conflating the two is what let a local UUID escape into the world model.
     */
    private void ensureUsersDidColumn() {
        try (var conn = getConnection()) {
            if (!tableExists(conn, "users")) return;
            if (columnExists(conn, "users", "did")) return;
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN did TEXT");
                log.info("Added users.did — local credentials can now point at a person");
            }
        } catch (SQLException e) {
            log.warn("Could not ensure users.did column: {}", e.getMessage());
        }
    }

    /**
     * Resolve any identifier to a person DID.
     *
     * @param identifier a person DID, a legacy account UUID, a username, or a display name
     * @return the person's DID, or empty if it cannot be resolved — <b>never a guess</b>
     */
    public Optional<String> resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        var id = identifier.trim();

        // 1. Already a person DID we know about.
        if (id.startsWith("did:key:")) {
            return identities.exists(id) ? Optional.of(id) : Optional.empty();
        }

        // 2/3. A local credential — by UUID or by username.
        var viaUsers = lookupUsers(id);
        if (viaUsers.isPresent()) return viaUsers;

        // 4. A display name on the person record.
        return lookupPlayerAccountByName(id);
    }

    /** True when the identifier maps to a real person. Convenience for guards. */
    public boolean isResolvable(String identifier) {
        return resolve(identifier).isPresent();
    }

    /** Bind a local credential row to a person DID. */
    public void linkUserToPerson(String userIdOrName, String personDid) {
        if (!identities.exists(personDid)) {
            throw new IllegalArgumentException("Unknown person DID: " + personDid);
        }
        var sql = "UPDATE users SET did = ? WHERE id = ? OR username = ?";
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, personDid);
            ps.setString(2, userIdOrName);
            ps.setString(3, userIdOrName);
            var n = ps.executeUpdate();
            log.info("Linked {} local credential row(s) for '{}' to person {}",
                n, userIdOrName, personDid);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to link user to person", e);
        }
    }

    private Optional<String> lookupUsers(String id) {
        try (var conn = getConnection()) {
            if (!tableExists(conn, "users") || !columnExists(conn, "users", "did")) {
                return Optional.empty();
            }
            var sql = "SELECT did FROM users WHERE (id = ? OR username = ?) AND did IS NOT NULL";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, id);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var did = rs.getString("did");
                        if (did != null && !did.isBlank()) return Optional.of(did);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("users lookup failed for '{}': {}", id, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> lookupPlayerAccountByName(String name) {
        try (var conn = getConnection()) {
            if (!tableExists(conn, "player_accounts")) return Optional.empty();
            var sql = "SELECT did FROM player_accounts WHERE display_name = ?";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.ofNullable(rs.getString("did"));
                }
            }
        } catch (SQLException e) {
            log.warn("player_accounts lookup failed for '{}': {}", name, e.getMessage());
        }
        return Optional.empty();
    }

    // --- schema introspection (dialect-tolerant) ---

    private boolean tableExists(Connection conn, String table) {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean columnExists(Connection conn, String table, String column) {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
