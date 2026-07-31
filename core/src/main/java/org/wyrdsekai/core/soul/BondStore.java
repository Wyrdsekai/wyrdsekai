package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for bonds ( BOND resource type, ).
 *
 * <p>Backs {@link BondRitual} so bonds survive restarts and can be queried
 * cross-session. SQL is compatible with both SQLite and PostgreSQL.</p>
 *
 * <p> canonical: world.db:bonds.
 * The {@code SoulManifest.bonds} field is a derived view rebuilt at
 * serialize time — never write to the manifest as the source of truth.
 * F7b Phase 2 will drop the manifest field entirely once all readers
 * route through {@link #bondsForAgent(String)}.</p>
 */
public final class BondStore {

    private static final Logger log = LoggerFactory.getLogger(BondStore.class);

    /**
     * Wave 1 (-§3) added two columns to the bonds
     * table. Idempotent migration mirrors the VitalityPersistence pattern —
     * each column is added only if missing. Pre-Wave-1 rows hydrate with
     * state derived from {@code active} (see Bond.canonicalState()).
     */
    private static final List<String[]> WAVE_1_COLUMNS = List.of(
        new String[]{"state",            "TEXT NOT NULL DEFAULT 'ACTIVE'"},
        new String[]{"cold_start_until", "INTEGER"}, // nullable; null = past cold-start
        // Wave 3.4: bondholder resource posture.
        new String[]{"posture",          "TEXT NOT NULL DEFAULT 'BOUNDED'"},
        // Arc 3: discriminator for BONDHOLDER / PEER /
        // FAMILIAR. Pre-Arc-3 rows hydrate as BONDHOLDER via canonicalKind().
        new String[]{"kind",             "TEXT NOT NULL DEFAULT 'BONDHOLDER'"}
    );

    private final String jdbcUrl;
    private volatile boolean migrated = false;

    public BondStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Wave 1 migration: idempotently add state + cold_start_until columns to
     * pre-Wave-1 bonds tables. Safe to call repeatedly. Called lazily before
     * each save/load to handle bootstrap cases where the table exists from
     * an older schema.
     */
    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        try (var rs = conn.getMetaData().getTables(null, null, "bonds", null)) {
            if (!rs.next()) {
                migrated = true; // schema initializer will create with columns
                return;
            }
        }
        for (var col : WAVE_1_COLUMNS) {
            boolean hasCol;
            try (var rs = conn.getMetaData().getColumns(null, null, "bonds", col[0])) {
                hasCol = rs.next();
            }
            if (!hasCol) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE bonds ADD COLUMN " + col[0] + " " + col[1]);
                    log.info("BondStore Wave 1 migration: added column {}", col[0]);
                } catch (SQLException e) {
                    // Race: another process added it. Re-check.
                    try (var rs = conn.getMetaData().getColumns(null, null, "bonds", col[0])) {
                        if (!rs.next()) throw e;
                    }
                }
            }
        }
        migrated = true;
    }

    /** Upsert a bond row. Bond.bondId is the primary key. */
    public void save(Bond bond) {
        var canonical = bond.canonicalState();
        var sql = "INSERT INTO bonds "
            + "(bond_id, agent_a_did, agent_b_did, depth, formed_at, last_interaction, "
            + " interaction_count, mutual_consent, active, scarred, state, cold_start_until, posture, kind) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (bond_id) DO UPDATE SET "
            + "  agent_a_did = excluded.agent_a_did, "
            + "  agent_b_did = excluded.agent_b_did, "
            + "  depth = excluded.depth, "
            + "  formed_at = excluded.formed_at, "
            + "  last_interaction = excluded.last_interaction, "
            + "  interaction_count = excluded.interaction_count, "
            + "  mutual_consent = excluded.mutual_consent, "
            + "  active = excluded.active, "
            + "  scarred = excluded.scarred, "
            + "  state = excluded.state, "
            + "  cold_start_until = excluded.cold_start_until, "
            + "  posture = excluded.posture, "
            + "  kind = excluded.kind";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, canonical.bondId());
                st.setString(2, canonical.agentADid());
                st.setString(3, canonical.agentBDid());
                st.setString(4, canonical.depth().name());
                st.setLong(5, canonical.formedAt().getEpochSecond());
                st.setLong(6, canonical.lastInteraction().getEpochSecond());
                st.setInt(7, canonical.interactionCount());
                st.setInt(8, canonical.mutualConsent() ? 1 : 0);
                st.setInt(9, canonical.active() ? 1 : 0);
                st.setInt(10, canonical.scarred() ? 1 : 0);
                st.setString(11, canonical.state().name());
                if (canonical.coldStartUntil() != null) {
                    st.setLong(12, canonical.coldStartUntil().getEpochSecond());
                } else {
                    st.setNull(12, Types.BIGINT);
                }
                st.setString(13, canonical.posture().name());
                // Arc 3: persist the kind discriminator.
                st.setString(14, canonical.canonicalKind().name());
                st.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to save bond {}: {}", bond.bondId(), e.getMessage());
        }
    }

    public Optional<Bond> get(String bondId) {
        var sql = "SELECT * FROM bonds WHERE bond_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, bondId);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get bond {}: {}", bondId, e.getMessage());
        }
        return Optional.empty();
    }

    /** All bonds where the given DID is one of the two parties. */
    public List<Bond> bondsForAgent(String agentDid) {
        var sql = "SELECT * FROM bonds WHERE agent_a_did = ? OR agent_b_did = ? "
            + "ORDER BY last_interaction DESC";
        var out = new ArrayList<Bond>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setString(2, agentDid);
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list bonds for {}: {}", agentDid, e.getMessage());
        }
        return out;
    }

    /** All bonds in the store. */
    public List<Bond> all() {
        var out = new ArrayList<Bond>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT * FROM bonds ORDER BY last_interaction DESC");
             var rs = st.executeQuery()) {
            while (rs.next()) out.add(fromRow(rs));
        } catch (SQLException e) {
            log.error("Failed to list bonds: {}", e.getMessage());
        }
        return out;
    }

    public int count() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM bonds");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count bonds: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * F7b Phase 2.3: idempotent reconcile of every manifest's embedded
     * bonds list into the canonical {@code bonds} table. Catches the
     * cross-zone self-heal case: a companion arrives in a new zone via
     * SoulStore replication, the manifest blob carries the bond list,
     * but the local {@code bonds} table is empty until something writes
     * to it. Walking manifests at boot lights up bond-aware features
     * (Hearth visit log, Shelf furnishing, knock cascades) immediately
     * instead of waiting for the next Forge cycle.
     *
     * <p>Idempotent — every {@link #save(Bond)} is an upsert keyed on
     * {@code bond_id}; running this on every boot is cheap and safe.
     *
     * @return number of bond rows reconciled (counted by save calls — may
     *         double-count repeated bond_ids across DIDs, which is fine)
     */
    public int backfillFromManifests(SoulStore manifestStore) {
        int reconciled = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "SELECT DISTINCT did FROM soul_manifests WHERE archived = 0");
             var rs = st.executeQuery()) {
            while (rs.next()) {
                var did = rs.getString(1);
                var manifestOpt = manifestStore.latest(did);
                if (manifestOpt.isEmpty()) continue;
                var bonds = manifestOpt.get().bonds();
                if (bonds == null || bonds.isEmpty()) continue;
                for (var bond : bonds) {
                    if (bond == null || bond.bondId() == null) continue;
                    save(bond);
                    reconciled++;
                }
            }
        } catch (SQLException e) {
            log.warn("bonds backfill query failed: {}", e.getMessage());
        }
        if (reconciled > 0) {
            log.info("BondStore: reconciled {} bond row(s) from soul_manifests", reconciled);
        }
        return reconciled;
    }

    private static Bond fromRow(ResultSet rs) throws SQLException {
        // Wave 1: state + cold_start_until are nullable on legacy rows.
        BondState parsedState = null;
        try {
            var raw = rs.getString("state");
            if (raw != null) parsedState = BondState.valueOf(raw);
        } catch (SQLException missing) {
            // Pre-Wave-1 row before migration ran — canonicalState() below will derive.
        }
        Instant coldStartUntil = null;
        try {
            var ts = rs.getLong("cold_start_until");
            if (!rs.wasNull() && ts > 0) coldStartUntil = Instant.ofEpochSecond(ts);
        } catch (SQLException missing) {
            // Pre-Wave-1 row, leave null.
        }
        // Wave 3.4: posture is nullable on legacy rows.
        BondholderPosture parsedPosture = null;
        try {
            var raw = rs.getString("posture");
            if (raw != null) parsedPosture = BondholderPosture.valueOf(raw);
        } catch (SQLException | IllegalArgumentException missing) {
            // Pre-Wave-3.4 row or unrecognized enum — canonicalState() will default BOUNDED.
        }
        // Arc 3: kind is nullable on legacy rows
        // canonicalKind() defaults to BONDHOLDER.
        BondKind parsedKind = null;
        try {
            var raw = rs.getString("kind");
            if (raw != null) parsedKind = BondKind.valueOf(raw);
        } catch (SQLException | IllegalArgumentException missing) {
            // Pre-Arc-3 row or unrecognized enum — canonicalKind() will default BONDHOLDER.
        }
        var bond = new Bond(
            rs.getString("bond_id"),
            rs.getString("agent_a_did"),
            rs.getString("agent_b_did"),
            Bond.BondDepth.valueOf(rs.getString("depth")),
            Instant.ofEpochSecond(rs.getLong("formed_at")),
            Instant.ofEpochSecond(rs.getLong("last_interaction")),
            rs.getInt("interaction_count"),
            rs.getInt("mutual_consent") == 1,
            rs.getInt("active") == 1,
            rs.getInt("scarred") == 1,
            parsedState,
            coldStartUntil,
            parsedPosture,
            // §E.3: pre-§E rows hydrate with null relationalState
            // canonicalState() defaults to OPEN.
            null,
            parsedKind);
        return bond.canonicalState();
    }
}
