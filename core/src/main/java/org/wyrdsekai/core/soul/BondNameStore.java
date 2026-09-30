package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The name two parties gave their bond: the naming ritual a companion proposes when a bond becomes
 * sacred. One row per companion and person in {@code world.db} (migration 18), kept apart from the
 * bond row so the views that list bonds (the crystal, the state dump, the chapel's cabinet) do not
 * carry it: the name is between the two of them. A name is given once and kept.
 *
 * <p>Without a database (tests) the names are held in memory.
 */
public final class BondNameStore {

    private static final Logger log = LoggerFactory.getLogger(BondNameStore.class);

    /** A bond's name: whose bond, the name, who offered it, and when the other took it. */
    public record Named(String companionDid, String partnerKey, String name, String offeredBy, Instant namedAt) {}

    private static volatile BondNameStore installed = new BondNameStore(null);

    private final String jdbcUrl;
    private final Map<String, Named> memory = new ConcurrentHashMap<>();

    public BondNameStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl == null || jdbcUrl.isBlank() ? null : jdbcUrl;
    }

    /** The node's store, set at boot. */
    public static void install(String jdbcUrl) {
        installed = new BondNameStore(jdbcUrl);
    }

    public static BondNameStore get() {
        return installed;
    }

    /** The database the names are kept in, or null when they are held in memory. */
    String url() {
        return jdbcUrl;
    }

    public static void ensureTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bond_names (
                  companion_did TEXT NOT NULL,
                  partner_key   TEXT NOT NULL,
                  name          TEXT NOT NULL,
                  offered_by    TEXT NOT NULL,
                  named_at      TEXT NOT NULL,
                  PRIMARY KEY (companion_did, partner_key))""");
        }
    }

    /** The name of the bond between this companion and this person, when it has one. */
    public Optional<Named> find(String companionDid, String partnerKey) {
        if (companionDid == null || partnerKey == null) return Optional.empty();
        if (jdbcUrl == null) return Optional.ofNullable(memory.get(companionDid + "|" + partnerKey));
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT name, offered_by, named_at FROM bond_names WHERE companion_did = ? AND partner_key = ?")) {
            ps.setString(1, companionDid);
            ps.setString(2, partnerKey);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Named(companionDid, partnerKey, rs.getString(1), rs.getString(2),
                    Instant.parse(rs.getString(3))));
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("bond name lookup failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Keep a name. False when the bond already has one (a name is given once) or it could not be kept. */
    public boolean save(Named named) {
        if (find(named.companionDid(), named.partnerKey()).isPresent()) return false;
        if (jdbcUrl == null) {
            return memory.putIfAbsent(named.companionDid() + "|" + named.partnerKey(), named) == null;
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO bond_names (companion_did, partner_key, name, offered_by, named_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, named.companionDid());
            ps.setString(2, named.partnerKey());
            ps.setString(3, named.name());
            ps.setString(4, named.offeredBy());
            ps.setString(5, named.namedAt().toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.warn("bond name not kept: {}", e.toString());
            return false;
        }
    }
}
