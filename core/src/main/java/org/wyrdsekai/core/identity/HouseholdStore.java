package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for the households
 * table. Mirrors the public half of {@code node-identity.json} into
 * {@code world.db:households} for queries.
 *
 * <p> canonical: world.db:households (public-key
 * mirror). The private key stays in the on-disk file — only the
 * public-key bytes, fingerprint, and {@code did:key} derivation are
 * mirrored here. Other nodes' households (federation peers) can also be
 * inserted via {@link #upsert} as their identities are observed.
 *
 * <p>Schema (see {@code sqlite-create-schema.sql} /
 * {@code postgresql-create-schema.sql}):
 * <pre>
 *   households(
 *     household_id    TEXT PRIMARY KEY,    -- NodeIdentity.nodeId (UUID)
 *     public_key      BLOB NOT NULL,       -- DER-encoded X.509 SPKI
 *     fingerprint     TEXT NOT NULL,       -- SHA-256 hex with colons
 *     did_key         TEXT,                -- did:key:... derivation
 *     registered_at   INTEGER NOT NULL,
 *     updated_at      INTEGER NOT NULL
 *   )
 * </pre>
 */
public final class HouseholdStore {

    private static final Logger log = LoggerFactory.getLogger(HouseholdStore.class);

    /**
     * Snapshot record returned by reads. Mirrors the column layout but
     * stays decoupled from any JDBC API.
     */
    public record Row(
        String householdId,
        byte[] publicKey,
        String fingerprint,
        String didKey,
        byte[] x25519PublicKey,   // #1184 grant key (X.509 SPKI); null on legacy rows
        Instant registeredAt,
        Instant updatedAt
    ) {}

    private final String jdbcUrl;

    public HouseholdStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Idempotent upsert keyed on {@code householdId}. Public key,
     * fingerprint, and DID are refreshed on every call (so a key
     * rotation is reflected immediately).
     */
    public void upsert(String householdId, byte[] publicKey,
                        String fingerprint, String didKey) {
        upsert(householdId, publicKey, fingerprint, didKey, null);
    }

    /**
     * As {@link #upsert(String, byte[], String, String)} but also mirrors this node's X25519 grant
     * public key ( #1184) so a zone holder can ECDH-wrap the zone master to it. A null
     * {@code x25519PublicKey} preserves any previously-stored value rather than clearing it.
     */
    public void upsert(String householdId, byte[] publicKey,
                        String fingerprint, String didKey, byte[] x25519PublicKey) {
        if (householdId == null || householdId.isBlank()) {
            log.warn("HouseholdStore.upsert called with blank householdId — skipping");
            return;
        }
        if (publicKey == null || publicKey.length == 0) {
            log.warn("HouseholdStore.upsert called with empty public key — skipping");
            return;
        }
        var sql = "INSERT INTO households "
            + "(household_id, public_key, fingerprint, did_key, x25519_public_key, registered_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (household_id) DO UPDATE SET "
            + "  public_key = excluded.public_key, "
            + "  fingerprint = excluded.fingerprint, "
            + "  did_key = excluded.did_key, "
            // null on this call must not wipe a previously-mirrored grant key.
            + "  x25519_public_key = COALESCE(excluded.x25519_public_key, households.x25519_public_key), "
            + "  updated_at = excluded.updated_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            long now = Instant.now().getEpochSecond();
            st.setString(1, householdId);
            st.setBytes(2, publicKey);
            st.setString(3, fingerprint);
            st.setString(4, didKey);
            st.setBytes(5, x25519PublicKey);
            st.setLong(6, now);
            st.setLong(7, now);
            st.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to upsert household {}: {}", householdId, e.getMessage());
        }
    }

    /** This node/peer's X25519 grant public key (X.509 SPKI), if mirrored #1184. */
    public Optional<byte[]> x25519PublicKey(String householdId) {
        return get(householdId).map(Row::x25519PublicKey).filter(b -> b != null && b.length > 0);
    }

    public Optional<Row> get(String householdId) {
        if (householdId == null || householdId.isBlank()) return Optional.empty();
        var sql = "SELECT public_key, fingerprint, did_key, x25519_public_key, registered_at, updated_at "
            + "FROM households WHERE household_id = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, householdId);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Row(
                    householdId,
                    rs.getBytes(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBytes(4),
                    Instant.ofEpochSecond(rs.getLong(5)),
                    Instant.ofEpochSecond(rs.getLong(6))
                ));
            }
        } catch (Exception e) {
            log.error("Failed to load household {}: {}", householdId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Lookup by {@code did:key:...} — useful for federation peer matching. */
    public Optional<Row> findByDid(String didKey) {
        if (didKey == null || didKey.isBlank()) return Optional.empty();
        var sql = "SELECT household_id, public_key, fingerprint, x25519_public_key, registered_at, updated_at "
            + "FROM households WHERE did_key = ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, didKey);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Row(
                    rs.getString(1),
                    rs.getBytes(2),
                    rs.getString(3),
                    didKey,
                    rs.getBytes(4),
                    Instant.ofEpochSecond(rs.getLong(5)),
                    Instant.ofEpochSecond(rs.getLong(6))
                ));
            }
        } catch (Exception e) {
            log.error("Failed to find household by did {}: {}", didKey, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Row> all() {
        var out = new ArrayList<Row>();
        var sql = "SELECT household_id, public_key, fingerprint, did_key, "
            + "x25519_public_key, registered_at, updated_at FROM households ORDER BY registered_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql);
             var rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Row(
                    rs.getString(1),
                    rs.getBytes(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBytes(5),
                    Instant.ofEpochSecond(rs.getLong(6)),
                    Instant.ofEpochSecond(rs.getLong(7))
                ));
            }
        } catch (SQLException e) {
            log.warn("households scan failed: {}", e.getMessage());
        }
        return out;
    }

    public int count() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM households");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("households count failed: {}", e.getMessage());
            return 0;
        }
    }
}
