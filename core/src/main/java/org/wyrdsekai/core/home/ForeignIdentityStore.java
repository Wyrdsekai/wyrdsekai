package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed persistence for {@link ForeignIdentity} rows.
 *
 * <p>Written by {@code VirtualSessionHandler} after transit-token verification,
 * read by cross-zone tell / bond / roster logic.</p>
 *
 * <p> canonical: world.db:foreign_identities.
 * No shadow store. Cross-zone identity assertions are signed envelopes
 * (see {@code IdentityAssertion}) — verified at portal arrival, then
 * persisted here. The signed envelope is the wire format; this row is
 * the local canonical record.</p>
 */
public final class ForeignIdentityStore {

    private static final Logger log = LoggerFactory.getLogger(ForeignIdentityStore.class);

    private static volatile ForeignIdentityStore INSTANCE;

    public static ForeignIdentityStore get() { return INSTANCE; }

    public static void setInstance(ForeignIdentityStore store) { INSTANCE = store; }

    public static void resetForTests() { INSTANCE = null; }

    private final String jdbcUrl;

    public ForeignIdentityStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Insert or update; bumps {@code last_seen_at} on every call. */
    public void upsert(ForeignIdentity fi) {
        var sql = "INSERT INTO foreign_identities "
            + "(did, home_zone, display_name, first_seen_at, last_seen_at, last_token_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (did) DO UPDATE SET "
            + "  home_zone = excluded.home_zone, "
            + "  display_name = excluded.display_name, "
            + "  last_seen_at = excluded.last_seen_at, "
            + "  last_token_id = excluded.last_token_id";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, fi.did());
            st.setString(2, fi.homeZone());
            st.setString(3, fi.displayName());
            st.setLong(4, fi.firstSeenAt().getEpochSecond());
            st.setLong(5, fi.lastSeenAt().getEpochSecond());
            st.setString(6, fi.lastTokenId());
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert foreign identity {}: {}", fi.did(), e.getMessage());
        }
    }

    public Optional<ForeignIdentity> get(String did) {
        var sql = "SELECT * FROM foreign_identities WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ForeignIdentity(
                        rs.getString("did"),
                        rs.getString("home_zone"),
                        rs.getString("display_name"),
                        Instant.ofEpochSecond(rs.getLong("first_seen_at")),
                        Instant.ofEpochSecond(rs.getLong("last_seen_at")),
                        rs.getString("last_token_id")));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get foreign identity {}: {}", did, e.getMessage());
        }
        return Optional.empty();
    }

    public List<ForeignIdentity> listAll() {
        var sql = "SELECT * FROM foreign_identities ORDER BY last_seen_at DESC";
        var out = new ArrayList<ForeignIdentity>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql);
             var rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new ForeignIdentity(
                    rs.getString("did"),
                    rs.getString("home_zone"),
                    rs.getString("display_name"),
                    Instant.ofEpochSecond(rs.getLong("first_seen_at")),
                    Instant.ofEpochSecond(rs.getLong("last_seen_at")),
                    rs.getString("last_token_id")));
            }
        } catch (SQLException e) {
            log.error("Failed to list foreign identities: {}", e.getMessage());
        }
        return out;
    }

    public boolean delete(String did) {
        var sql = "DELETE FROM foreign_identities WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete foreign identity {}: {}", did, e.getMessage());
            return false;
        }
    }
}
