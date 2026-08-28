package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link PersonIdentity} — the key material behind a person.
 *
 * <p>Kept in its own table rather than folded into {@code player_accounts}
 * because they are different concerns and have different blast radii:
 * {@code player_accounts} is the person's <em>profile</em> (display name,
 * devices, preferences) and is read all over; this table holds an encrypted
 * private key and should be touched by as little code as possible.</p>
 *
 * <p>Uses the same JDBC pattern as {@link AccountStore}.</p>
 */
public class PersonIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(PersonIdentityStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public PersonIdentityStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        initSchema();
    }

    private void initSchema() {
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS person_identities(
                  did                    TEXT PRIMARY KEY,
                  public_key             BLOB NOT NULL,
                  encrypted_private_key  BLOB NOT NULL,
                  key_log                TEXT NOT NULL,
                  created_at             INTEGER NOT NULL
                )
                """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init person_identities schema", e);
        }
    }

    /** Persist a freshly minted identity. Idempotent on DID. */
    public void save(PersonIdentity identity) {
        var sql = """
            INSERT INTO person_identities(did, public_key, encrypted_private_key, key_log, created_at)
            VALUES(?,?,?,?,?)
            ON CONFLICT(did) DO NOTHING
            """;
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, identity.did());
            ps.setBytes(2, identity.publicKey());
            ps.setBytes(3, identity.encryptedPrivateKey());
            ps.setString(4, serializeKeyLog(identity.keyLog()));
            ps.setLong(5, identity.createdAt().getEpochSecond());
            ps.executeUpdate();
            log.info("Person identity stored: {}", identity.did());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store person identity " + identity.did(), e);
        }
    }

    public Optional<PersonIdentity> findByDid(String did) {
        if (did == null || did.isBlank()) return Optional.empty();
        var sql = """
            SELECT did, public_key, encrypted_private_key, key_log, created_at
            FROM person_identities WHERE did = ?
            """;
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, did);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PersonIdentity(
                    rs.getString("did"),
                    rs.getBytes("public_key"),
                    rs.getBytes("encrypted_private_key"),
                    deserializeKeyLog(rs.getString("key_log")),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load person identity " + did, e);
        }
    }

    /** Every person identity on this node. */
    public List<String> listDids() {
        var out = new ArrayList<String>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement("SELECT did FROM person_identities ORDER BY created_at");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("did"));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list person identities", e);
        }
        return out;
    }

    public boolean exists(String did) {
        return findByDid(did).isPresent();
    }

    // --- key log (de)serialisation ---

    private static String serializeKeyLog(List<ObjectNode> keyLog) {
        try {
            return MAPPER.writeValueAsString(keyLog);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise KERI key log", e);
        }
    }

    private static List<ObjectNode> deserializeKeyLog(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var arr = MAPPER.readTree(json);
            var out = new ArrayList<ObjectNode>();
            arr.forEach(n -> out.add((ObjectNode) n));
            return out;
        } catch (Exception e) {
            log.warn("Could not parse stored KERI key log — treating as empty: {}", e.getMessage());
            return List.of();
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
