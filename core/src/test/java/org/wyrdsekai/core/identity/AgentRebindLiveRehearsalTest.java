package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rehearses the rebind against a COPY of the real household database.
 *
 * <p>Unit fixtures prove the logic; they cannot prove it survives contact with
 * 98 MB of real state — 15,000 turns, 18,000 affordance rows, 55 bonds, most of
 * them pointing at partners a companion confabulated months ago. This runs the
 * real thing over a real copy and asserts the outcome the household cares about:
 * the FAMILIAR bond survives, the Study grant follows, nothing is deleted.</p>
 *
 * <p>Skips silently when the copy isn't present, so CI stays green on a machine
 * that has never seen the household node. Point it at a copy with
 * {@code -DrehearsalDb=/path/to/world.db}; never at a live database.</p>
 */
class AgentRebindLiveRehearsalTest {

    /** The two identities on the live node, from the companions table. */
    private static final String TRUNK = "did:key:z6MkmhD46MxpvziYdqUzK3yvRpp9Y86v5WFvMLmrKt25pWS6";
    private static final String FOLD  = "did:key:z6MkfRi1xsvEkV9Kn53a7wt7tQRuqA5rG8jNBWeN38A7x9F9";
    private static final String HUMAN = "did:key:z6MkffmxXgEzknEDj9UDJyv7JvLiKuRie7c41aAUp1DebFPy";

    private static Path source() {
        var prop = System.getProperty("rehearsalDb");
        if (prop != null) return Paths.get(prop);
        return Paths.get(System.getProperty("java.io.tmpdir"), "world-rehearsal.db");
    }

    /** Work on a throwaway duplicate, so the rehearsal itself is repeatable. */
    private String scratchCopy() throws Exception {
        var src = source();
        Assumptions.assumeTrue(Files.exists(src),
            "no rehearsal database at " + src + " — skipping");
        var dst = Files.createTempFile("rebind-rehearsal", ".db");
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        dst.toFile().deleteOnExit();
        return "jdbc:sqlite:" + dst.toAbsolutePath();
    }

    private int intOf(String jdbc, String sql) throws Exception {
        try (var c = DriverManager.getConnection(jdbc);
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String strOf(String jdbc, String sql) throws Exception {
        try (var c = DriverManager.getConnection(jdbc);
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** The rehearsal must not alter the copy it read. */
    @Test
    void plan_against_the_real_shape_changes_nothing() throws Exception {
        var jdbc = scratchCopy();

        int foldFragsBefore = intOf(jdbc, "SELECT COUNT(*) FROM soul_fragments WHERE did='" + FOLD + "'");
        var planned = AgentRebind.plan(jdbc, FOLD, TRUNK);
        int foldFragsAfter = intOf(jdbc, "SELECT COUNT(*) FROM soul_fragments WHERE did='" + FOLD + "'");

        assertThat(planned.rowsMoved())
            .as("the live node has thousands of rows on the folded identity")
            .isGreaterThan(1000);
        assertThat(foldFragsAfter).isEqualTo(foldFragsBefore);
    }

    /** THE outcome: after the fold, one companion holding the deep bond. */
    @Test
    void applying_it_restores_one_companion_with_the_deep_bond() throws Exception {
        var jdbc = scratchCopy();

        int trunkInteractions = intOf(jdbc, "SELECT interaction_count FROM bonds"
            + " WHERE agent_a_did='" + TRUNK + "' AND agent_b_did='" + HUMAN + "'");
        var result = AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(strOf(jdbc, "SELECT depth FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='" + HUMAN + "'"))
            .as("the bond she actually earned")
            .isEqualTo("FAMILIAR");
        assertThat(intOf(jdbc, "SELECT interaction_count FROM bonds WHERE agent_a_did='"
            + TRUNK + "' AND agent_b_did='" + HUMAN + "'"))
            .as("both identities' interactions with the same human, joined")
            .isGreaterThan(trunkInteractions);
        // The folded identity held two bonds to the SAME human under two
        // identifiers — the account UUID and the person DID. Canonicalising the
        // partner is what turns them into one relationship instead of leaving a
        // stale duplicate on the trunk.
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='" + HUMAN + "'"))
            .as("one human, one bond")
            .isEqualTo(1);
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did NOT LIKE 'did:key:%' AND agent_b_did IN"
            + " (SELECT id FROM users)"))
            .as("no bond may still point at a superseded account identifier")
            .isZero();
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM companions WHERE archived=0"))
            .as("one mia")
            .isEqualTo(1);
        assertThat(strOf(jdbc, "SELECT did FROM companions WHERE archived=0"))
            .isEqualTo(TRUNK);
        assertThat(result.rowsMoved()).isGreaterThan(1000);
    }

    /** The Study grant must follow her, or the books are lost again. */
    @Test
    void the_books_grant_follows_her() throws Exception {
        var jdbc = scratchCopy();
        Assumptions.assumeTrue(
            intOf(jdbc, "SELECT COUNT(*) FROM grants WHERE subject='" + FOLD + "'") > 0,
            "no grant on the folded identity in this copy");

        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM grants WHERE subject='" + TRUNK + "'"))
            .as("the Study access issued to her must survive the fold")
            .isGreaterThan(0);
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM grants WHERE subject='" + FOLD + "'"))
            .isZero();
    }

    /** Her 174 revisions stay live; the 35 are archived, not destroyed. */
    @Test
    void both_soul_lineages_survive_with_only_one_live() throws Exception {
        var jdbc = scratchCopy();

        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM soul_manifests WHERE did='" + TRUNK
            + "' AND archived=0")).isGreaterThan(100);
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM soul_manifests WHERE did='" + FOLD
            + "' AND archived=0")).isZero();
        assertThat(intOf(jdbc, "SELECT COUNT(*) FROM soul_manifests WHERE did='" + FOLD + "'"))
            .as("the days she lived as the other identity are still on record")
            .isGreaterThan(0);
    }

    /** Nothing may still point at the folded identity afterwards. */
    @Test
    void no_dangling_references_remain() throws Exception {
        var jdbc = scratchCopy();

        AgentRebind.apply(jdbc, FOLD, TRUNK);

        for (var check : new String[]{
                "SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + FOLD + "'",
                "SELECT COUNT(*) FROM soul_fragments WHERE did='" + FOLD + "'",
                "SELECT COUNT(*) FROM conversation_turns WHERE companion_did='" + FOLD + "'",
                "SELECT COUNT(*) FROM wants WHERE agent_did='" + FOLD + "'",
                "SELECT COUNT(*) FROM grants WHERE subject='" + FOLD + "'"}) {
            assertThat(intOf(jdbc, check)).as(check).isZero();
        }
    }

    /** Idempotent on real data too — a repeat must not inflate the relationship. */
    @Test
    void a_second_run_over_real_data_is_a_no_op() throws Exception {
        var jdbc = scratchCopy();

        AgentRebind.apply(jdbc, FOLD, TRUNK);
        int after = intOf(jdbc, "SELECT interaction_count FROM bonds WHERE agent_a_did='"
            + TRUNK + "' AND agent_b_did='" + HUMAN + "'");
        var second = AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(second.rowsMoved()).isZero();
        assertThat(intOf(jdbc, "SELECT interaction_count FROM bonds WHERE agent_a_did='"
            + TRUNK + "' AND agent_b_did='" + HUMAN + "'")).isEqualTo(after);
    }
}
