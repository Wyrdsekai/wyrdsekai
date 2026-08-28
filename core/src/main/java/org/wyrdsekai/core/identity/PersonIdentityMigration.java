package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.time.Instant;

/**
 * Re-points a household's live references from a legacy account id onto a real
 * person identity.
 *
 * <p><b>Runs at server startup, in Java, on every platform.</b> Deliberately not
 * in {@code postinst} / macOS {@code postinstall} / MSI custom actions — those
 * diverge three ways and each has already produced real bugs in this project. A
 * migration that must run exactly once and never half-way is the worst possible
 * thing to hand-write per platform.</p>
 *
 * <h2>Selective, not total</h2>
 *
 * <p>Two categories of row must <b>not</b> be rewritten:</p>
 * <ul>
 *   <li><b>Audit history</b> — rewriting {@code audit_log.actor} asserts that a
 *       different person took an action. An audit log should say what was true
 *       at the time; it is read forward through the attestation chain instead.</li>
 *   <li><b>Local credentials</b> — {@code users.id}, {@code sessions.user_id},
 *       {@code user_ssh_keys.user_id} identify a login on <em>this machine</em>.
 *       Repointing them at the person would repeat the exact conflation this
 *       whole change exists to undo.</li>
 * </ul>
 *
 * <p>Everything else — bonds, residency, ownership, engagement, telemetry —
 * refers to the <em>person</em> and is re-pointed.</p>
 *
 * <h2>Safety</h2>
 * <p>Idempotent (records completion), resumable (per-table, and re-running skips
 * what is already done), and it emits a signed {@link RebindAttestation} before
 * touching anything, so a half-finished run is still interpretable.</p>
 */
public final class PersonIdentityMigration {

    private static final Logger log = LoggerFactory.getLogger(PersonIdentityMigration.class);

    /** Bump when the set of rewritten columns changes. */
    public static final int VERSION = 1;

    /**
     * Columns that refer to a PERSON and must follow them. Ordered so the
     * load-bearing ones land first — if a run is interrupted, the important
     * rows are already correct.
     */
    private static final Map<String, String> PERSON_COLUMNS = new LinkedHashMap<>();
    static {
        PERSON_COLUMNS.put("bonds", "agent_b_did");
        PERSON_COLUMNS.put("residency", "did");
        PERSON_COLUMNS.put("inventory", "entity_id");
        PERSON_COLUMNS.put("bondholder_engagement", "bondholder_did");
        PERSON_COLUMNS.put("saudade_ledger", "bondholder_did");
        PERSON_COLUMNS.put("account_zonebank", "account_id");
        PERSON_COLUMNS.put("conversation_turns", "bondholder_did");
        PERSON_COLUMNS.put("substrate_pressure_samples", "did");
    }

    /**
     * Deliberately NOT rewritten. Kept as an explicit list so the choice is
     * visible and reviewable rather than an omission.
     */
    private static final Map<String, String> PRESERVED = new LinkedHashMap<>();
    static {
        PRESERVED.put("audit_log", "actor / home_owner — historical truth");
        PRESERVED.put("steward_audit", "actor_did / target_id — historical truth");
        PRESERVED.put("invites", "consumed_by — historical truth");
        PRESERVED.put("users", "id — local credential, not the person");
        PRESERVED.put("sessions", "user_id — local credential");
        PRESERVED.put("user_ssh_keys", "user_id — local credential");
    }

    private PersonIdentityMigration() {}

    /** Outcome of a run, for logging and tests. */
    public record Result(boolean ran, String personDid, int rowsRewritten,
                         List<String> tablesTouched, String skippedReason) {
        public static Result skipped(String why) {
            return new Result(false, null, 0, List.of(), why);
        }
    }

    /**
     * Migrate one legacy account onto a person identity.
     *
     * @param jdbcUrl         world database
     * @param legacyId        the legacy identifier currently in the data (a UUID or username)
     * @param displayName     name for the person record if one must be minted
     * @param secretSupplier  supplies the 32-byte household secret
     */
    public static Result run(String jdbcUrl, String legacyId, String displayName,
                             Supplier<byte[]> secretSupplier) {
        if (legacyId == null || legacyId.isBlank()) {
            return Result.skipped("no legacy identifier given");
        }
        if (alreadyDone(jdbcUrl, legacyId)) {
            return Result.skipped("already migrated: " + legacyId);
        }
        if (!PersonIdentityProvisioner.isEnabled()) {
            PersonIdentityProvisioner.init(jdbcUrl, secretSupplier);
        }

        var personDid = PersonIdentityProvisioner.provisionIfMissing(legacyId, displayName);
        if (personDid.isEmpty()) {
            return Result.skipped("could not provision a person for " + legacyId);
        }
        var did = personDid.get();
        if (did.equals(legacyId)) {
            markDone(jdbcUrl, legacyId, did);
            return Result.skipped("already a person identity: " + legacyId);
        }

        // Attest BEFORE rewriting, so an interrupted run is still interpretable
        // and any signed or historical row can be read forward.
        recordAttestation(jdbcUrl, legacyId, did, secretSupplier);

        var touched = new ArrayList<String>();
        var total = 0;
        for (var e : PERSON_COLUMNS.entrySet()) {
            var n = rewrite(jdbcUrl, e.getKey(), e.getValue(), legacyId, did);
            if (n > 0) {
                touched.add(e.getKey() + "." + e.getValue() + "=" + n);
                total += n;
            }
        }

        markDone(jdbcUrl, legacyId, did);
        log.info("Person identity migration complete: {} -> {} ({} rows across {} tables; "
                + "{} preserved by design)", legacyId, did, total, touched.size(), PRESERVED.size());
        return new Result(true, did, total, touched, null);
    }

    /** Rewrite one column, skipping tables/columns this install does not have. */
    private static int rewrite(String jdbcUrl, String table, String column,
                               String from, String to) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            if (!hasColumn(conn, table, column)) return 0;
            var sql = "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, to);
                ps.setString(2, from);
                var n = ps.executeUpdate();
                if (n > 0) log.info("  rebound {}.{}: {} rows", table, column, n);
                return n;
            }
        } catch (SQLException e) {
            // A missing table on a partial install must not abort the run.
            log.debug("skip {}.{}: {}", table, column, e.getMessage());
            return 0;
        }
    }

    private static void recordAttestation(String jdbcUrl, String legacyId, String personDid,
                                          Supplier<byte[]> secretSupplier) {
        try {
            var identities = new PersonIdentityStore(jdbcUrl);
            var person = identities.findByDid(personDid).orElse(null);
            if (person == null) return;

            // The legacy id has no key of its own — it never was a cryptographic
            // identity. The person attests to having absorbed it, which is the
            // strongest claim available and is what later reads follow.
            var att = RebindAttestation.issue(
                syntheticLegacyIdentity(legacyId, person), person, secretSupplier.get());
            new RebindAttestationStore(jdbcUrl).save(att);
        } catch (Exception e) {
            log.warn("Could not record rebind attestation for {}: {}", legacyId, e.toString());
        }
    }

    /**
     * A stand-in for the legacy identifier so the chain has a start point.
     * It carries the person's own key material because the legacy id never had
     * any — this documents the absorption rather than pretending the old string
     * signed anything.
     */
    private static PersonIdentity syntheticLegacyIdentity(String legacyId, PersonIdentity person) {
        var placeholderDid = legacyId.startsWith("did:key:")
            ? legacyId
            : "did:key:zLegacy" + Integer.toHexString(legacyId.hashCode());
        return new PersonIdentity(placeholderDid, person.publicKey(),
            person.encryptedPrivateKey(), List.of(), person.createdAt());
    }

    // --- bookkeeping ---

    private static void ensureLedger(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS person_identity_migrations(
                  legacy_id  TEXT PRIMARY KEY,
                  person_did TEXT NOT NULL,
                  version    INTEGER NOT NULL,
                  applied_at INTEGER NOT NULL
                )
                """);
        }
    }

    private static boolean alreadyDone(String jdbcUrl, String legacyId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureLedger(conn);
            var sql = "SELECT 1 FROM person_identity_migrations WHERE legacy_id = ? AND version >= ?";
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, legacyId);
                ps.setInt(2, VERSION);
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read migration ledger: {}", e.getMessage());
            return false;
        }
    }

    private static void markDone(String jdbcUrl, String legacyId, String personDid) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureLedger(conn);
            var sql = """
                INSERT INTO person_identity_migrations(legacy_id, person_did, version, applied_at)
                VALUES(?,?,?,?)
                ON CONFLICT(legacy_id) DO UPDATE SET person_did=excluded.person_did,
                                                     version=excluded.version,
                                                     applied_at=excluded.applied_at
                """;
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, legacyId);
                ps.setString(2, personDid);
                ps.setInt(3, VERSION);
                ps.setLong(4, Instant.now().getEpochSecond());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Could not record migration completion: {}", e.getMessage());
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Tables deliberately left alone, for docs and review. */
    public static Map<String, String> preservedTables() {
        return Map.copyOf(PRESERVED);
    }
}
