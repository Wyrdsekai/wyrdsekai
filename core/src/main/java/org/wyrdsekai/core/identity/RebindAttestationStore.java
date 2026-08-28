package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for {@link RebindAttestation}.
 *
 * <p>This table is <b>append-only</b>. An attestation is the record that lets a
 * historical row — an audit entry, a signed manifest — keep the identity it was
 * written with and still resolve to the right person. Deleting one would orphan
 * every record that predates it.</p>
 */
public class RebindAttestationStore {

    private static final Logger log = LoggerFactory.getLogger(RebindAttestationStore.class);

    private final String jdbcUrl;

    public RebindAttestationStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initSchema();
    }

    private void initSchema() {
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rebind_attestations(
                  from_did   TEXT NOT NULL,
                  to_did     TEXT NOT NULL,
                  issued_at  INTEGER NOT NULL,
                  signature  BLOB NOT NULL,
                  PRIMARY KEY (from_did, to_did)
                )
                """);
            // A witnessed attestation is a different claim from a self-issued one
            // and must survive a round-trip as such — an attestation that loses
            // who made it is worse than none. Added after the table shipped, so
            // widen in place rather than requiring a rebuild.
            try {
                stmt.execute("ALTER TABLE rebind_attestations ADD COLUMN attester_did TEXT");
            } catch (SQLException alreadyThere) {
                // Column exists — SQLite has no IF NOT EXISTS for ADD COLUMN.
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init rebind_attestations schema", e);
        }
    }

    public void save(RebindAttestation attestation) {
        var sql = """
            INSERT INTO rebind_attestations(from_did, to_did, issued_at, signature, attester_did)
            VALUES(?,?,?,?,?)
            ON CONFLICT(from_did, to_did) DO NOTHING
            """;
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, attestation.fromDid());
            ps.setString(2, attestation.toDid());
            ps.setLong(3, attestation.issuedAt().getEpochSecond());
            ps.setBytes(4, attestation.signature());
            ps.setString(5, attestation.attesterDid());
            ps.executeUpdate();
            log.info("Rebind attestation recorded ({}): {} -> {}",
                attestation.isWitnessed() ? "witnessed by " + attestation.attesterDid()
                    : "self-issued",
                attestation.fromDid(), attestation.toDid());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store rebind attestation", e);
        }
    }

    public List<RebindAttestation> all() {
        var out = new ArrayList<RebindAttestation>();
        var sql = "SELECT from_did, to_did, issued_at, signature, attester_did"
            + " FROM rebind_attestations";
        try (var conn = getConnection(); var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new RebindAttestation(
                    rs.getString("from_did"),
                    rs.getString("to_did"),
                    Instant.ofEpochSecond(rs.getLong("issued_at")),
                    rs.getBytes("signature"),
                    rs.getString("attester_did")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read rebind attestations", e);
        }
        return out;
    }

    /** Follow the chain from a historical identity to the person it is now. */
    public String resolveCurrent(String historicalDid) {
        return RebindAttestation.resolveCurrent(historicalDid, all());
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
