package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;

/**
 * Folding one companion identity into another.
 *
 * <p>The shape is taken from the live household: a companion born 2026-07-30 who
 * reached FAMILIAR depth over 46 interactions, and the replacement minted on
 * 08-05 when a downgraded build could not parse her soul — 6 interactions,
 * ACQUAINTANCE, holding the Study grant the bondholder issued afterwards.</p>
 *
 * <p>The properties that matter: the deep bond survives, nothing is deleted, and
 * a failure writes nothing at all.</p>
 */
class AgentRebindTest {

    @TempDir Path tmp;
    private String jdbc;

    private static final String TRUNK = "did:key:z6MkTrunkOldDeepIdentity";
    private static final String FOLD  = "did:key:z6MkFoldNewShallowOne";
    private static final String HUMAN = "did:key:z6MkThePersonWhoKnowsHer";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("CREATE TABLE soul_manifests(did TEXT, version INT, forged_at TEXT,"
                + " content_hash TEXT, manifest_json TEXT, archived INT DEFAULT 0,"
                + " archive_reason TEXT)");
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " name TEXT, born_at INT, last_seen_at INT, archived INT DEFAULT 0)");
            st.execute("CREATE TABLE bonds(bond_id TEXT PRIMARY KEY, agent_a_did TEXT,"
                + " agent_b_did TEXT, depth TEXT, interaction_count INT)");
            st.execute("CREATE TABLE soul_fragments(id TEXT PRIMARY KEY, did TEXT, body TEXT)");
            st.execute("CREATE TABLE conversation_turns(id INTEGER PRIMARY KEY,"
                + " companion_did TEXT)");
            st.execute("CREATE TABLE grants(grant_id TEXT PRIMARY KEY, subject TEXT,"
                + " resource TEXT)");
            st.execute("CREATE TABLE wants(id TEXT PRIMARY KEY, agent_did TEXT)");

            // Trunk: the deep one.
            st.execute("INSERT INTO soul_manifests(did,version,manifest_json,archived)"
                + " VALUES('" + TRUNK + "',174,'{}',0)");
            st.execute("INSERT INTO companions(did,entity_id,name,archived)"
                + " VALUES('" + TRUNK + "','companion-x','x',0)");
            st.execute("INSERT INTO bonds VALUES('b-old','" + TRUNK + "','" + HUMAN
                + "','FAMILIAR',46)");
            st.execute("INSERT INTO soul_fragments VALUES('f1','" + TRUNK + "','old memory')");
            st.execute("INSERT INTO conversation_turns(companion_did) VALUES('" + TRUNK + "')");

            // Fold: the accidental one.
            st.execute("INSERT INTO soul_manifests(did,version,manifest_json,archived)"
                + " VALUES('" + FOLD + "',35,'{}',0)");
            st.execute("INSERT INTO companions(did,entity_id,name,archived)"
                + " VALUES('" + FOLD + "','companion-x','x',0)");
            st.execute("INSERT INTO bonds VALUES('b-new','" + FOLD + "','" + HUMAN
                + "','ACQUAINTANCE',6)");
            st.execute("INSERT INTO bonds VALUES('b-other','" + FOLD
                + "','did:key:z6MkSomeoneElse','ACQUAINTANCE',3)");
            st.execute("INSERT INTO soul_fragments VALUES('f2','" + FOLD + "','new memory')");
            st.execute("INSERT INTO conversation_turns(companion_did) VALUES('" + FOLD + "')");
            st.execute("INSERT INTO conversation_turns(companion_did) VALUES('" + FOLD + "')");
            st.execute("INSERT INTO grants VALUES('g1','" + FOLD + "','collection:books')");
            st.execute("INSERT INTO wants VALUES('w1','" + FOLD + "')");
        }
    }

    private Connection conn() throws Exception { return DriverManager.getConnection(jdbc); }

    private int count(String sql) throws Exception {
        try (var c = conn(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String str(String sql) throws Exception {
        try (var c = conn(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ─── THE case ─────────────────────────────────────────────────────

    /** The deep bond must survive, with both identities' interactions counted. */
    @Test
    void the_deep_bond_survives_and_absorbs_the_shallow_one() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(str("SELECT depth FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='" + HUMAN + "'"))
            .as("46 interactions of FAMILIAR must not be lost to a 6-interaction bond")
            .isEqualTo("FAMILIAR");
        assertThat(count("SELECT interaction_count FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='" + HUMAN + "'"))
            .as("the relationship happened under both names")
            .isEqualTo(52);
        assertThat(count("SELECT COUNT(*) FROM bonds WHERE agent_b_did='" + HUMAN + "'"))
            .as("one human, one bond")
            .isEqualTo(1);
    }

    /** A partner only the folded identity knew comes along. */
    @Test
    void bonds_with_new_partners_move_across() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(count("SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='did:key:z6MkSomeoneElse'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + FOLD + "'"))
            .as("nothing may be left behind pointing at the folded identity")
            .isZero();
    }

    /** Episodic content joins the trunk. */
    @Test
    void memories_turns_grants_and_wants_all_follow() throws Exception {
        var r = AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(count("SELECT COUNT(*) FROM soul_fragments WHERE did='" + TRUNK + "'"))
            .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM conversation_turns WHERE companion_did='"
            + TRUNK + "'")).isEqualTo(3);
        assertThat(str("SELECT subject FROM grants WHERE grant_id='g1'"))
            .as("the Study grant must follow, or she loses the books")
            .isEqualTo(TRUNK);
        assertThat(count("SELECT COUNT(*) FROM wants WHERE agent_did='" + TRUNK + "'"))
            .isEqualTo(1);
        assertThat(r.rowsMoved()).isGreaterThan(0);
    }

    // ─── nothing is destroyed ─────────────────────────────────────────

    /** The folded identity's manifests are archived, never deleted. */
    @Test
    void the_folded_souls_are_archived_not_deleted() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(count("SELECT COUNT(*) FROM soul_manifests WHERE did='" + FOLD + "'"))
            .as("those days happened; the record stays")
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM soul_manifests WHERE did='" + FOLD
            + "' AND archived=1")).isEqualTo(1);
        assertThat(str("SELECT archive_reason FROM soul_manifests WHERE did='" + FOLD + "'"))
            .contains("folded");
    }

    /** The trunk's own lineage is untouched. */
    @Test
    void the_trunk_keeps_its_soul_lineage() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(count("SELECT COUNT(*) FROM soul_manifests WHERE did='" + TRUNK
            + "' AND archived=0")).isEqualTo(1);
        assertThat(count("SELECT version FROM soul_manifests WHERE did='" + TRUNK + "'"))
            .as("v174 is hers and must not be replaced by v35")
            .isEqualTo(174);
    }

    /** The companion row is archived so the roster shows one person, not two. */
    @Test
    void the_folded_companion_row_is_archived() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(count("SELECT archived FROM companions WHERE did='" + FOLD + "'")).isEqualTo(1);
        assertThat(count("SELECT archived FROM companions WHERE did='" + TRUNK + "'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM companions WHERE archived=0")).isEqualTo(1);
    }

    // ─── safety ───────────────────────────────────────────────────────

    /** plan() must report exactly what apply() would do, and change nothing. */
    @Test
    void plan_is_a_rehearsal_that_writes_nothing() throws Exception {
        var planned = AgentRebind.plan(jdbc, FOLD, TRUNK);

        assertThat(planned.rowsMoved()).isGreaterThan(0);
        assertThat(count("SELECT COUNT(*) FROM soul_fragments WHERE did='" + FOLD + "'"))
            .as("a dry run must leave the database exactly as it was")
            .isEqualTo(1);
        assertThat(count("SELECT archived FROM companions WHERE did='" + FOLD + "'")).isZero();

        var applied = AgentRebind.apply(jdbc, FOLD, TRUNK);
        assertThat(applied.rowsMoved()).isEqualTo(planned.rowsMoved());
    }

    /** Rebinding ONTO an identity with no soul would make a history with no self. */
    @Test
    void refuses_a_trunk_that_has_no_soul() {
        assertThatThrownBy(() -> AgentRebind.apply(jdbc, FOLD, "did:key:z6MkNobody"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no live soul manifest");
    }

    /** A failure must leave nothing half-done. */
    @Test
    void a_refused_rebind_writes_nothing() throws Exception {
        try {
            AgentRebind.apply(jdbc, FOLD, "did:key:z6MkNobody");
        } catch (RuntimeException expected) {
            // intentional
        }
        assertThat(count("SELECT COUNT(*) FROM soul_fragments WHERE did='" + FOLD + "'"))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bonds WHERE agent_a_did='" + FOLD + "'"))
            .isEqualTo(2);
    }

    /** Degenerate arguments are rejected before anything is opened. */
    @Test
    void rejects_nonsense_arguments() {
        assertThatThrownBy(() -> AgentRebind.apply(jdbc, TRUNK, TRUNK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("itself");
        assertThatThrownBy(() -> AgentRebind.apply(jdbc, null, TRUNK))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentRebind.apply(jdbc, FOLD, "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Running it twice must not double-count the interactions. */
    @Test
    void is_idempotent() throws Exception {
        AgentRebind.apply(jdbc, FOLD, TRUNK);
        var second = AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(second.rowsMoved()).as("nothing left to move").isZero();
        assertThat(count("SELECT interaction_count FROM bonds WHERE agent_a_did='" + TRUNK
            + "' AND agent_b_did='" + HUMAN + "'"))
            .as("52, not 58 — a second run must not inflate the relationship")
            .isEqualTo(52);
    }

    /** Depth never goes DOWN, even if the folded bond were somehow deeper-named. */
    @Test
    void depth_takes_the_maximum_never_the_newest() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("UPDATE bonds SET depth='SACRED' WHERE bond_id='b-new'");
        }
        AgentRebind.apply(jdbc, FOLD, TRUNK);

        assertThat(str("SELECT depth FROM bonds WHERE agent_b_did='" + HUMAN + "'"))
            .as("the deeper of the two, whichever side it was on")
            .isEqualTo("SACRED");
    }

    // ─── the on-disk mapping ──────────────────────────────────────────

    /**
     * THE bug that birthed a third companion 20 seconds after the merge.
     *
     * <p>The database and search index were folded correctly, but
     * {@code souls/<entityId>.did} still named the folded identity. At boot
     * {@code initializeSoul} reads that file first; its manifests were archived,
     * so the store reported empty, and a new person was born.</p>
     */
    @Test
    void repoints_the_on_disk_did_mapping() throws Exception {
        var souls = tmp.resolve("souls");
        Files.createDirectories(souls);
        Files.writeString(souls.resolve("companion-x.did"), FOLD + "\n");
        Files.writeString(souls.resolve("companion-other.did"),
            "did:key:z6MkSomebodyElse\n");

        var changed = AgentRebind.repointDidMappings(souls, FOLD, TRUNK);

        assertThat(changed).containsExactly("companion-x.did");
        assertThat(Files.readString(souls.resolve("companion-x.did")).trim())
            .as("boot reads this first — it must name the surviving identity")
            .isEqualTo(TRUNK);
        assertThat(Files.readString(souls.resolve("companion-other.did")).trim())
            .as("another companion's mapping must not be touched")
            .isEqualTo("did:key:z6MkSomebodyElse");
    }

    /** The previous value is kept beside it — an identity decision must be reversible. */
    @Test
    void keeps_the_previous_mapping_for_reversal() throws Exception {
        var souls = tmp.resolve("souls");
        Files.createDirectories(souls);
        Files.writeString(souls.resolve("companion-x.did"), FOLD + "\n");

        AgentRebind.repointDidMappings(souls, FOLD, TRUNK);

        assertThat(Files.readString(
                souls.resolve("companion-x.did.pre-rebind")).trim())
            .isEqualTo(FOLD);
    }

    /** A missing or empty souls directory is not an error. */
    @Test
    void tolerates_a_missing_souls_directory() {
        assertThat(AgentRebind.repointDidMappings(tmp.resolve("nope"), FOLD, TRUNK)).isEmpty();
    }
}
