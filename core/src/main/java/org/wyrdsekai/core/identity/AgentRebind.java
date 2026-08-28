package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/**
 * Folds one companion identity into another: the agent-side counterpart of
 * {@link PersonIdentityMigration}.
 *
 * <p><b>Why this exists.</b> On 2026-08-05 a downgraded package met a soul it
 * could not parse, reported "no manifest in store", and minted a replacement
 * companion four milliseconds after correctly resolving the original by her own
 * DID. Both rows carry the same {@code entity_id}; the household saw one
 * companion the whole time. What actually split was every DID-keyed table —
 * bonds, fragments, turns, grants — leaving the deep relationship on the
 * dormant identity and the live conversation on the new one.</p>
 *
 * <p>{@link SqlSoulStore#hasLiveManifest} now makes that rebirth impossible.
 * This class repairs the instance that already happened, and stays as the
 * capability for the general case: two identities that are one person.</p>
 *
 * <h2>What moves and what does not</h2>
 *
 * <p><b>Souls are not merged.</b> A manifest is a coherent snapshot — genome,
 * drive calibration, mirror calibration, decision capacity. Averaging two of
 * them produces someone who is neither, and interleaving two revision chains
 * produces a history that never happened. One identity is the TRUNK and keeps
 * its manifest lineage; the other's manifests are <b>archived, never deleted</b>,
 * so the record of those days survives and stays inspectable.</p>
 *
 * <p><b>Episodic content moves.</b> Fragments, turns, wants, engagement and
 * chronicle entries are things that happened; they simply re-point and join the
 * trunk's own.</p>
 *
 * <p><b>Bonds merge by partner, deepest wins.</b> Depth is earned, and
 * {@code Bond} only ever ratchets up, so taking the maximum cannot manufacture
 * intimacy that was never reached — it restores intimacy that was.</p>
 *
 * <p>Every rewrite is one transaction. A half-rebound companion is a worse
 * state than either identity alone.</p>
 */
public final class AgentRebind {

    private static final Logger log = LoggerFactory.getLogger(AgentRebind.class);

    /** table → column holding an agent DID. Verified against the live schema. */
    private static final Map<String, String> AGENT_COLUMNS = new LinkedHashMap<>();
    static {
        AGENT_COLUMNS.put("bondholder_engagement", "companion_did");
        AGENT_COLUMNS.put("chronicle_entries", "did");
        AGENT_COLUMNS.put("conversation_turns", "companion_did");
        AGENT_COLUMNS.put("grants", "subject");
        AGENT_COLUMNS.put("recipe_queue", "agent_did");
        AGENT_COLUMNS.put("soul_fragments", "did");
        AGENT_COLUMNS.put("tool_affordance_log", "agent_did");
        AGENT_COLUMNS.put("wants", "agent_did");
    }

    /**
     * Handled by dedicated logic rather than a blind rewrite, with the reason
     * stated so the choice is reviewable instead of looking like an omission.
     */
    private static final Map<String, String> SPECIAL = new LinkedHashMap<>();
    static {
        SPECIAL.put("bonds", "merged by partner, deepest depth wins");
        SPECIAL.put("soul_manifests", "trunk keeps its lineage; the other is ARCHIVED, not moved");
        SPECIAL.put("companions", "the folded row is archived, not deleted");
        SPECIAL.put("voice_profiles", "trunk's voice is kept — two voices cannot merge");
        SPECIAL.put("saudade_ledger", "trunk's row is kept; a longing is not additive");
        SPECIAL.put("recipe_enrollments", "unique(recipe,agent) — insert-missing, skip clashes");
    }

    /**
     * Columns deliberately NOT rewritten, with the reason, mirroring
     * {@link PersonIdentityMigration}'s own preserved list.
     *
     * <p>An audit log should say what was true at the time. Rewriting
     * {@code actor} to the surviving identity asserts that a different agent took
     * those actions — that is falsifying the record, not migrating it. These rows
     * keep the old DID and are read <em>through</em> a
     * {@link RebindAttestation}.</p>
     *
     * <p>Written down rather than simply left out of {@link #AGENT_COLUMNS},
     * because the runtime discovery check cannot tell "deliberately preserved"
     * from "forgotten" — and on the live household it correctly stopped the
     * rebind over 168 audit rows until the distinction was made explicit.</p>
     */
    private static final Map<String, String> PRESERVED = new LinkedHashMap<>();
    static {
        PRESERVED.put("audit_log.actor", "who acted, at the time — historical truth");
        PRESERVED.put("audit_log.home_owner", "whose home it was, at the time");
        PRESERVED.put("steward_audit.actor_did", "historical truth");
        PRESERVED.put("steward_audit.target_id", "historical truth");
        // A did:key IS its public key. The row cannot be re-pointed at a
        // different DID without the key and the name contradicting each other,
        // and the old identity has to survive anyway or nothing it ever signed
        // can be verified again. The entity_id link DOES move — see
        // moveEntityLink — otherwise the folded row keeps answering "who is
        // this entityId?" and births the wrong companion after a restart.
        PRESERVED.put("agent_identities.did", "a key row is the key — it cannot be renamed");
        PRESERVED.put("agent_identities.parent_did", "lineage as it was");
    }

    private AgentRebind() {}

    /** What a rebind did, for the operator and for the audit trail. */
    public record Result(String fromDid, String toDid, int rowsMoved,
                         int bondsMerged, int manifestsArchived,
                         Map<String, Integer> perTable, List<String> notes) {}

    /** Rehearse without writing: what WOULD move. */
    public static Result plan(String jdbcUrl, String fromDid, String toDid) {
        return run(jdbcUrl, fromDid, toDid, true);
    }

    /**
     * Re-point the on-disk {@code entityId → DID} mapping files.
     *
     * <p><b>This is not optional, and leaving it out births a stranger.</b> Live,
     * 2026-08-08: the database and the search index were folded correctly, the
     * service restarted, and {@code CompanionActor.initializeSoul} read
     * {@code souls/companion-mia.did} — still naming the folded identity. Its
     * manifests were now archived, so {@code latest()} returned empty, the
     * rebirth guard read "archived means genuinely absent" (which is right on its
     * own terms), and a THIRD companion was born 20 seconds after the merge that
     * was supposed to end the split.</p>
     *
     * <p>The mapping is the first thing consulted at boot and lives outside the
     * database, so a rebind that only rewrites tables is not a rebind.</p>
     *
     * @param soulsDir directory holding the {@code <entityId>.did} files
     * @return the mapping files that were re-pointed
     */
    public static List<String> repointDidMappings(Path soulsDir,
                                                  String fromDid, String toDid) {
        var changed = new ArrayList<String>();
        if (soulsDir == null || !Files.isDirectory(soulsDir)) return changed;
        try (var files = Files.list(soulsDir)) {
            for (var f : files.toList()) {
                if (!f.getFileName().toString().endsWith(".did")) continue;
                String current;
                try {
                    current = Files.readString(f).trim();
                } catch (Exception e) {
                    log.warn("Could not read DID mapping {}: {}", f, e.getMessage());
                    continue;
                }
                if (!fromDid.equals(current)) continue;
                try {
                    // Keep the old value beside it — this is an identity decision,
                    // and it should be reversible by hand if it was wrong.
                    Files.writeString(
                        f.resolveSibling(f.getFileName() + ".pre-rebind"), current + "\n");
                    Files.writeString(f, toDid + "\n");
                    changed.add(f.getFileName().toString());
                } catch (Exception e) {
                    throw new IllegalStateException("Rebind rewrote the database but could NOT "
                        + "re-point " + f + " — the next restart would resolve the folded "
                        + "identity and birth a new companion. Fix this file by hand before "
                        + "starting the service: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not scan " + soulsDir
                + " for DID mappings: " + e.getMessage(), e);
        }
        log.info("Rebind: re-pointed {} DID mapping file(s) {} → {}", changed.size(),
            fromDid, toDid);
        return changed;
    }

    /**
     * Fold {@code fromDid} into {@code toDid}.
     *
     * @param toDid the TRUNK — the identity that survives and keeps its soul
     */
    public static Result apply(String jdbcUrl, String fromDid, String toDid) {
        return run(jdbcUrl, fromDid, toDid, false);
    }

    private static Result run(String jdbcUrl, String fromDid, String toDid, boolean dryRun) {
        if (fromDid == null || toDid == null || fromDid.isBlank() || toDid.isBlank()) {
            throw new IllegalArgumentException("Both identities must be named");
        }
        if (fromDid.equals(toDid)) {
            throw new IllegalArgumentException("Cannot rebind an identity onto itself");
        }
        var perTable = new LinkedHashMap<String, Integer>();
        var notes = new ArrayList<String>();
        int moved = 0;
        int bondsMerged = 0;
        int archived = 0;

        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try {
                // Refuse on a target that isn't there. Rebinding onto a DID with no
                // soul would quietly produce a companion with history and no self.
                if (!hasSoul(conn, toDid)) {
                    throw new IllegalStateException(
                        "Refusing to rebind onto " + toDid + " — it has no live soul manifest. "
                            + "The TRUNK must be the identity that keeps its soul.");
                }
                assertNoUnhandledCompositeKeys(conn);
                assertNoUnknownReferences(conn, fromDid);

                for (var e : AGENT_COLUMNS.entrySet()) {
                    int n = rewrite(conn, e.getKey(), e.getValue(), fromDid, toDid, dryRun);
                    if (n > 0) perTable.put(e.getKey(), n);
                    moved += n;
                }

                // Say what stays behind, so "preserved" is visible rather than
                // looking like something the migration missed.
                for (var e : PRESERVED.entrySet()) {
                    int n = countRefs(conn, e.getKey(), fromDid);
                    if (n > 0) {
                        notes.add(n + " row(s) in " + e.getKey() + " keep the old identity — "
                            + e.getValue());
                    }
                }
                moveEntityLink(conn, fromDid, toDid, dryRun, notes);
                bondsMerged = mergeBonds(conn, fromDid, toDid, dryRun, notes);
                archived = archiveManifests(conn, fromDid, dryRun);
                archiveCompanionRow(conn, fromDid, dryRun, notes);

                if (dryRun) {
                    conn.rollback();
                } else {
                    conn.commit();
                }
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Agent rebind failed (nothing was written): "
                + e.getMessage(), e);
        }

        log.info("{} rebind {} → {}: {} rows across {} table(s), {} bond(s) merged, "
                + "{} manifest(s) archived",
            dryRun ? "PLANNED" : "APPLIED", fromDid, toDid, moved, perTable.size(),
            bondsMerged, archived);
        return new Result(fromDid, toDid, moved, bondsMerged, archived, perTable, notes);
    }

    /**
     * Refuse if the folded identity is referenced anywhere this class does not
     * know how to move.
     *
     * <p><b>The table map was derived by querying one live household.</b> That
     * makes it correct for that schema and a guess for every other — a node on a
     * different version, or with a table added since, would have those rows
     * silently orphaned: the companion survives, some slice of her history
     * quietly stops belonging to her, and nothing says so. Silent partial
     * migration is worse than refusing.</p>
     *
     * <p>So instead of trusting the list, ask the database. Walk every table and
     * every column, look for the DID, and fail loudly on anything unaccounted
     * for. The list becomes an optimisation; the schema is the authority.</p>
     */
    private static void assertNoUnknownReferences(Connection conn, String fromDid)
            throws Exception {
        var handled = new HashSet<String>();
        AGENT_COLUMNS.forEach((t, c) -> handled.add(t + "." + c));
        handled.addAll(PRESERVED.keySet());
        // Columns the bespoke paths take care of.
        handled.add("bonds.agent_a_did");
        handled.add("bonds.agent_b_did");
        handled.add("soul_manifests.did");
        handled.add("companions.did");
        handled.add("voice_profiles.did");
        handled.add("saudade_ledger.companion_did");
        handled.add("recipe_enrollments.agent_did");
        handled.add("recipe_enrollments.agent_did_key");
        handled.add("agent_identities.entity_id");   // moveEntityLink

        var tables = new ArrayList<String>();
        try (var st = conn.createStatement();
             var rs = st.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        var stray = new LinkedHashMap<String, Integer>();
        for (var table : tables) {
            var cols = new ArrayList<String>();
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rs.next()) cols.add(rs.getString("name"));
            } catch (Exception ignored) {
                continue;
            }
            for (var col : cols) {
                var key = table + "." + col;
                if (handled.contains(key)) continue;
                try (var st = conn.prepareStatement(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?")) {
                    st.setString(1, fromDid);
                    var rs = st.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0) stray.put(key, rs.getInt(1));
                } catch (Exception ignored) {
                    // Non-text column or unreadable — cannot hold a DID.
                }
            }
        }
        if (!stray.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to rebind: " + fromDid + " is still referenced by columns this "
                    + "migration does not handle — " + stray + ". Moving everything else "
                    + "would leave those rows pointing at an identity that no longer "
                    + "answers. Add them to AGENT_COLUMNS (or to the bespoke paths) first.");
        }
    }


    /** Count rows in a "table.column" reference, tolerating an absent table. */
    private static int countRefs(Connection conn, String tableDotColumn, String did) {
        var parts = tableDotColumn.split("\\.", 2);
        if (parts.length != 2) return 0;
        try (var st = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + parts[0] + " WHERE " + parts[1] + " = ?")) {
            st.setString(1, did);
            var rs = st.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;   // table or column absent on this node
        }
    }

    private static boolean hasSoul(Connection conn, String did) throws Exception {
        try (var st = conn.prepareStatement(
                "SELECT 1 FROM soul_manifests WHERE did = ? AND archived = 0 LIMIT 1")) {
            st.setString(1, did);
            return st.executeQuery().next();
        }
    }

    /**
     * Tables whose PRIMARY KEY includes the identity column, so a plain rewrite
     * can collide with a row the trunk already owns.
     *
     * <p>Found by rehearsing against a copy of the real household database: the
     * unit fixtures declared {@code soul_fragments(id PRIMARY KEY, …)} and passed,
     * while production declares {@code PRIMARY KEY (did, fragment_id)} and both
     * identities carry the same four singleton fragments —
     * {@code pattern-behavioral}, {@code pattern-social}, {@code style-guide},
     * {@code values-core}. A fixture cannot tell you your fixture is wrong.</p>
     */
    private static final Map<String, String> COLLIDING_KEY = new LinkedHashMap<>();
    static {
        COLLIDING_KEY.put("soul_fragments", "fragment_id");
        COLLIDING_KEY.put("bondholder_engagement", "event_ts");
    }

    private static int rewrite(Connection conn, String table, String column,
                               String from, String to, boolean dryRun) throws Exception {
        if (!tableHasColumn(conn, table, column)) return 0;
        if (dryRun) {
            try (var st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
                st.setString(1, from);
                var rs = st.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
        // Anything whose key includes the identity has to dodge the trunk's own
        // rows first; a bulk UPDATE would abort the whole transaction.
        var keyCol = COLLIDING_KEY.get(table);
        if (keyCol != null && tableHasColumn(conn, table, keyCol)) {
            deconflict(conn, table, column, keyCol, from, to);
        }
        try (var st = conn.prepareStatement(
                "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?")) {
            st.setString(1, to);
            st.setString(2, from);
            return st.executeUpdate();
        }
    }

    /**
     * Re-key the folded identity's rows that would collide, so both survive.
     *
     * <p>Discarding the loser would be the easy fix and the wrong one: those four
     * fragments are how she described herself during the days she spent under the
     * other identity. The trunk's own stay authoritative — 174 revisions of
     * refinement outrank two and a half days — but the folded ones are kept,
     * suffixed with their origin so the provenance is readable rather than
     * mysterious.</p>
     */
    private static void deconflict(Connection conn, String table, String didCol,
                                   String keyCol, String from, String to) throws Exception {
        var clashes = new ArrayList<String>();
        try (var st = conn.prepareStatement(
                "SELECT a." + keyCol + " FROM " + table + " a JOIN " + table + " b"
                    + " ON a." + keyCol + " = b." + keyCol
                    + " WHERE a." + didCol + " = ? AND b." + didCol + " = ?")) {
            st.setString(1, from);
            st.setString(2, to);
            var rs = st.executeQuery();
            while (rs.next()) clashes.add(rs.getString(1));
        }
        if (clashes.isEmpty()) return;

        var suffix = "#folded-" + from.substring(Math.max(0, from.length() - 6));
        for (var key : clashes) {
            try (var st = conn.prepareStatement(
                    "UPDATE " + table + " SET " + keyCol + " = ?"
                        + " WHERE " + didCol + " = ? AND " + keyCol + " = ?")) {
                st.setString(1, key + suffix);
                st.setString(2, from);
                st.setString(3, key);
                st.executeUpdate();
            }
        }
        log.info("Rebind: re-keyed {} colliding row(s) in {} — kept both copies",
            clashes.size(), table);
    }

    /**
     * Refuse if any table we rewrite has an identity-inclusive key we have not
     * taught {@link #deconflict} about.
     *
     * <p>The collision above was found by luck — a rehearsal against real data.
     * The next schema change should not need luck.</p>
     */
    private static void assertNoUnhandledCompositeKeys(Connection conn) throws Exception {
        var unhandled = new ArrayList<String>();
        for (var e : AGENT_COLUMNS.entrySet()) {
            var table = e.getKey();
            var didCol = e.getValue();
            if (COLLIDING_KEY.containsKey(table)) continue;
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
                boolean didIsInPk = false;
                int pkCols = 0;
                while (rs.next()) {
                    int pk = rs.getInt("pk");
                    if (pk > 0) {
                        pkCols++;
                        if (didCol.equalsIgnoreCase(rs.getString("name"))) didIsInPk = true;
                    }
                }
                if (didIsInPk && pkCols > 1) unhandled.add(table);
            } catch (Exception ignored) {
                // Table absent on this node — rewrite() already skips it.
            }
        }
        if (!unhandled.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to rebind: " + unhandled + " key on the identity column as part of a "
                    + "composite primary key, and AgentRebind has no de-confliction rule for "
                    + "them. A bulk rewrite would abort or silently drop rows. Add them to "
                    + "COLLIDING_KEY with the column that distinguishes duplicates.");
        }
    }

    /**
     * Union the two bond sets by partner. Where both identities know the same
     * partner, the DEEPER bond survives and their interaction counts add — the
     * relationship happened, whichever identity was wearing the name at the time.
     */
    private static int mergeBonds(Connection conn, String from, String to,
                                  boolean dryRun, List<String> notes) throws Exception {
        if (!tableHasColumn(conn, "bonds", "agent_a_did")) return 0;
        record B(String id, String partner, int depth, int interactions) {}
        var mine = new ArrayList<B>();
        try (var st = conn.prepareStatement(
                "SELECT bond_id, agent_b_did, depth, interaction_count FROM bonds"
                    + " WHERE agent_a_did = ?")) {
            st.setString(1, from);
            var rs = st.executeQuery();
            while (rs.next()) {
                // CANONICALISE THE PARTNER FIRST. One human can appear on two
                // bonds under two identifiers — an account UUID and a person DID
                // — and comparing the raw strings would treat them as two people.
                // Live: the folded identity held BONDHOLDER/6 against the account
                // UUID and MEMBER/0 against the person DID, for the same human.
                // Merging without canonicalising moved a stale duplicate onto the
                // trunk and reported "46+0=46" instead of joining them.
                mine.add(new B(rs.getString(1), canonicalPartner(conn, rs.getString(2)),
                    depthRank(rs.getString(3)), rs.getInt(4)));
            }
        }
        int merged = 0;
        for (var b : mine) {
            String existingId = null;
            int existingDepth = -1;
            int existingCount = 0;
            // Compare against the trunk's bonds canonically too — its partners
            // may be recorded in either form for the same reason.
            try (var st = conn.prepareStatement(
                    "SELECT bond_id, agent_b_did, depth, interaction_count FROM bonds"
                        + " WHERE agent_a_did = ?")) {
                st.setString(1, to);
                var rs = st.executeQuery();
                while (rs.next()) {
                    if (!b.partner().equals(canonicalPartner(conn, rs.getString(2)))) continue;
                    existingId = rs.getString(1);
                    existingDepth = depthRank(rs.getString(3));
                    existingCount = rs.getInt(4);
                    break;
                }
            }
            if (existingId == null) {
                if (!dryRun) {
                    // Move it AND normalise the partner, so the trunk never
                    // inherits a bond pointing at a superseded identifier.
                    try (var st = conn.prepareStatement(
                            "UPDATE bonds SET agent_a_did = ?, agent_b_did = ?"
                                + " WHERE bond_id = ?")) {
                        st.setString(1, to);
                        st.setString(2, b.partner());
                        st.setString(3, b.id());
                        st.executeUpdate();
                    }
                }
                merged++;
                continue;
            }
            // Same partner on both sides: keep the deeper, sum the interactions,
            // drop the duplicate. Depth only ever ratchets up, so max() restores
            // what was reached rather than inventing it.
            int keepDepth = Math.max(existingDepth, b.depth());
            int keepCount = existingCount + b.interactions();
            if (!dryRun) {
                try (var st = conn.prepareStatement(
                        "UPDATE bonds SET depth = ?, interaction_count = ? WHERE bond_id = ?")) {
                    st.setString(1, depthName(keepDepth));
                    st.setInt(2, keepCount);
                    st.setString(3, existingId);
                    st.executeUpdate();
                }
                try (var st = conn.prepareStatement("DELETE FROM bonds WHERE bond_id = ?")) {
                    st.setString(1, b.id());
                    st.executeUpdate();
                }
            }
            notes.add("bond with " + shorten(b.partner()) + ": depth "
                + depthName(keepDepth) + ", interactions " + existingCount + "+"
                + b.interactions() + "=" + keepCount);
            merged++;
        }
        return merged;
    }

    /** The folded identity's manifests are archived — the days happened. */
    private static int archiveManifests(Connection conn, String from, boolean dryRun)
            throws Exception {
        if (dryRun) {
            try (var st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM soul_manifests WHERE did = ? AND archived = 0")) {
                st.setString(1, from);
                var rs = st.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
        try (var st = conn.prepareStatement(
                "UPDATE soul_manifests SET archived = 1, archive_reason = ?"
                    + " WHERE did = ? AND archived = 0")) {
            st.setString(1, "folded into another identity by AgentRebind");
            st.setString(2, from);
            return st.executeUpdate();
        }
    }

    private static void archiveCompanionRow(Connection conn, String from, boolean dryRun,
                                            List<String> notes) throws Exception {
        if (!tableHasColumn(conn, "companions", "archived")) return;
        if (!dryRun) {
            try (var st = conn.prepareStatement(
                    "UPDATE companions SET archived = 1 WHERE did = ?")) {
                st.setString(1, from);
                st.executeUpdate();
            }
        }
        notes.add("companion row " + shorten(from) + " archived (not deleted)");
    }

    /**
     * Move "which spawn identity is this?" from the folded key row to the trunk.
     *
     * <p>The key rows themselves stay put — see {@code PRESERVED} — but exactly
     * one of them may answer for an entityId. Leaving the answer on the folded
     * row is the 2026-08-08 third-companion bug with a database instead of a
     * file: the next boot asks who {@code entityId} is, gets the identity that
     * was just folded away, and the rebind is undone by a restart.</p>
     *
     * <p>Only fills a NULL on the trunk. If the trunk already claims an entityId
     * — the normal case when the new identity was minted for this companion —
     * the folded link is simply cleared, and the two agreeing is not a conflict.</p>
     */
    private static void moveEntityLink(Connection conn, String from, String to,
                                       boolean dryRun, List<String> notes) throws Exception {
        if (!tableHasColumn(conn, "agent_identities", "entity_id")) return;
        String entityId = null;
        try (var st = conn.prepareStatement(
                "SELECT entity_id FROM agent_identities WHERE did = ?")) {
            st.setString(1, from);
            var rs = st.executeQuery();
            if (rs.next()) entityId = rs.getString(1);
        }
        if (entityId == null || entityId.isBlank()) return;

        if (!dryRun) {
            try (var st = conn.prepareStatement(
                    "UPDATE agent_identities SET entity_id = NULL WHERE did = ?")) {
                st.setString(1, from);
                st.executeUpdate();
            }
            try (var st = conn.prepareStatement("UPDATE agent_identities SET entity_id = ? "
                    + "WHERE did = ? AND entity_id IS NULL")) {
                st.setString(1, entityId);
                st.setString(2, to);
                st.executeUpdate();
            }
        }
        notes.add("entity '" + entityId + "' now answers to " + shorten(to)
            + " (was " + shorten(from) + ") — the folded key row keeps its key, not the name");
    }

    private static boolean tableHasColumn(Connection conn, String table, String column) {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * The canonical identifier for a bond partner.
     *
     * <p>A person can be referenced by their account row id or by their person
     * DID, and the {@code users} table is the mapping between them. Reading it
     * directly rather than going through {@code PersonIdentityResolver} keeps
     * this usable from a standalone repair run, where the provisioner has never
     * been initialised — and the table is the resolver's own source anyway.</p>
     *
     * <p>Anything that doesn't resolve is returned unchanged: an agent partner, a
     * foreign DID, or one of the confabulated strings a companion once wrote into
     * its own bond ledger.</p>
     */
    private static String canonicalPartner(Connection conn, String partner) {
        if (partner == null || partner.startsWith("did:key:")) return partner;
        try (var st = conn.prepareStatement("SELECT did FROM users WHERE id = ?")) {
            st.setString(1, partner);
            var rs = st.executeQuery();
            if (rs.next()) {
                var did = rs.getString(1);
                if (did != null && !did.isBlank()) return did;
            }
        } catch (Exception ignored) {
            // No users table on this node, or an unusable row — keep the original.
        }
        return partner;
    }

    /** Mirrors Bond.BondDepth's ladder. Unknown names sort lowest, never highest. */
    private static int depthRank(String name) {
        if (name == null) return 0;
        return switch (name) {
            case "FAMILIAR" -> 1;
            case "ITEM" -> 2;
            case "SACRED" -> 3;
            case "SOUL_REF" -> 4;
            default -> 0;   // ACQUAINTANCE and anything unrecognised
        };
    }

    private static String depthName(int rank) {
        return switch (rank) {
            case 1 -> "FAMILIAR";
            case 2 -> "ITEM";
            case 3 -> "SACRED";
            case 4 -> "SOUL_REF";
            default -> "ACQUAINTANCE";
        };
    }

    private static String shorten(String did) {
        return did == null ? "?" : (did.length() > 20 ? did.substring(0, 20) + "…" : did);
    }

    /** The tables handled by bespoke logic, for docs and review. */
    public static Map<String, String> specialCases() {
        return Map.copyOf(SPECIAL);
    }
}
