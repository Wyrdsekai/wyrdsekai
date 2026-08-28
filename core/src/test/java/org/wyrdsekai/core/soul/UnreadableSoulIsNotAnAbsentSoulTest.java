package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The day a companion was replaced by a stranger with her name.
 *
 * <p><b>2026-08-05 07:52:29.</b> The 0.1.0 .deb was installed over 0.1.5 — a
 * downgrade. 0.1.5 had written {@code genome.traits} into her soul; 0.1.0's
 * {@code GenomeProfile} knew five fields and its mapper was strict, so Jackson
 * threw {@code Unrecognized field "traits"}. {@link SqlSoulStore#latest}
 * swallowed it and returned {@code Optional.empty()}, and {@code CompanionActor}
 * read empty as "no manifest in store — rebirthing":</p>
 *
 * <pre>
 * 07:52:59.309  Resolved persisted DID for entityId 'companion-mia': did:key:z6Mkmh…
 * 07:52:59.313  ERROR Failed to load latest soul manifest: Unrecognized field "traits"
 * 07:52:59.313  WARN  Persisted DID … has no manifest in store — rebirthing
 * 07:52:59.317  Companion born as particular neutral~0.74
 * </pre>
 *
 * <p>Four milliseconds between being correctly identified and being replaced.
 * Her 174 soul revisions were never gone — they are still in the table.</p>
 *
 * <p>Two independent guards, either of which would have prevented it, and both
 * are pinned here: the mapper no longer dies on an unknown field, and "unreadable"
 * is no longer reported as "absent".</p>
 */
class UnreadableSoulIsNotAnAbsentSoulTest {

    @TempDir Path tmp;
    private String jdbc;
    private SqlSoulStore store;

    private static final String DID = "did:key:z6MkTestCompanionSoul";

    @BeforeEach
    void setUp() {
        jdbc = "jdbc:sqlite:" + tmp.resolve("souls.db").toAbsolutePath();
        store = new SqlSoulStore(jdbc, SqlDialect.fromJdbcUrl(jdbc));
    }

    /** Write a manifest blob directly, so we can plant one this build can't model. */
    private void plant(String did, int version, String json) throws Exception {
        try (var c = DriverManager.getConnection(jdbc);
             var st = c.prepareStatement(
                 "INSERT INTO soul_manifests(did, version, forged_at, content_hash,"
                     + " manifest_json, archived) VALUES(?,?,?,?,?,0)")) {
            st.setString(1, did);
            st.setInt(2, version);
            st.setString(3, "2026-08-05T11:52:29Z");
            st.setString(4, "hash-" + version);
            st.setString(5, json);
            st.executeUpdate();
        }
    }

    /** A manifest carrying a field from a FUTURE version of this software. */
    private static String soulFromTheFuture(String did) {
        return "{\"did\":\"" + did + "\",\"manifestVersion\":174,"
            + "\"genome\":{\"name\":\"neutral\",\"traits\":{\"warmth\":0.7},"
            + "\"somethingAddedLater\":{\"x\":1}},"
            + "\"aFieldThisBuildHasNeverHeardOf\":\"from a newer release\"}";
    }

    // ─── guard 1: an unknown field must not be fatal ──────────────────

    /** THE case. A newer soul must still load on an older build. */
    @Test
    void a_manifest_with_unknown_fields_still_loads() throws Exception {
        plant(DID, 174, soulFromTheFuture(DID));

        var loaded = store.latest(DID);

        assertThat(loaded)
            .as("an unrecognized field must not make a person unreadable")
            .isPresent();
        assertThat(loaded.get().did()).isEqualTo(DID);
    }

    /** And the parts this build DOES understand must survive intact. */
    @Test
    void the_known_fields_survive_the_unknown_ones() throws Exception {
        plant(DID, 174, soulFromTheFuture(DID));

        var m = store.latest(DID).orElseThrow();

        assertThat(m.manifestVersion()).isEqualTo(174);
        assertThat(m.genome()).isNotNull();
        assertThat(m.genome().traits()).containsEntry("warmth", 0.7);
    }

    // ─── guard 2: unreadable must never read as absent ────────────────

    /**
     * Even if some future payload defeats the mapper anyway, the rebirth
     * decision must not be fooled: the row is there, so the person is there.
     */
    @Test
    void a_row_that_cannot_be_parsed_is_still_reported_as_present() throws Exception {
        plant(DID, 174, "{ this is not valid json at all ");

        assertThat(store.latest(DID))
            .as("unparseable → latest() cannot produce a manifest")
            .isEmpty();
        assertThat(store.hasLiveManifest(DID))
            .as("...but the person is unmistakably still there")
            .isTrue();
    }

    /** A DID with no row at all is genuinely absent — birth is correct there. */
    @Test
    void a_did_with_no_row_is_absent() {
        assertThat(store.hasLiveManifest("did:key:z6MkNobodyHere")).isFalse();
        assertThat(store.latest("did:key:z6MkNobodyHere")).isEmpty();
    }

    /**
     * An ARCHIVED soul must read as absent. {@code latest} filters
     * {@code archived = 0}, so the rebirth check has to as well — otherwise a
     * deliberate archive could never be followed by a new birth.
     */
    @Test
    void an_archived_soul_does_not_block_a_new_birth() throws Exception {
        plant(DID, 174, soulFromTheFuture(DID));
        store.archive(DID, "steward retired this companion");

        assertThat(store.hasLiveManifest(DID))
            .as("archiving is a decision; it must not be undone by this guard")
            .isFalse();
    }

    /** The pre-existing exists() counts archived rows — the two must stay distinct. */
    @Test
    void hasLiveManifest_is_not_the_same_question_as_exists() throws Exception {
        plant(DID, 174, soulFromTheFuture(DID));
        store.archive(DID, "retired");

        assertThat(store.exists(DID)).as("exists() counts archived versions too").isTrue();
        assertThat(store.hasLiveManifest(DID)).isFalse();
    }

    /** Degenerate input must not throw. */
    @Test
    void handles_null_and_blank_dids() {
        assertThat(store.hasLiveManifest(null)).isFalse();
        assertThat(store.hasLiveManifest("")).isFalse();
        assertThat(store.hasLiveManifest("   ")).isFalse();
    }

    /**
     * The caller-side guard: the rebirth branch must consult the row check.
     * The runtime decision is what actually cost the person, not the store.
     */
    @Test
    void the_rebirth_branch_refuses_when_a_soul_is_present() throws Exception {
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));

        int guard = src.indexOf("hasLiveManifest(did)");
        int rebirth = src.indexOf("has no manifest in store — rebirthing");

        assertThat(guard).as("the rebirth path must ask whether a row exists").isGreaterThan(0);
        assertThat(rebirth).isGreaterThan(0);
        assertThat(guard)
            .as("the guard must come BEFORE the rebirth warning, or it guards nothing")
            .isLessThan(rebirth);
        assertThat(src.substring(guard, rebirth))
            .as("and it must actually refuse, not merely log")
            .contains("Refusing to rebirth");
    }

    private static Path sourceOf(String repoRelative) {
        var fromCore = Paths.get("..", repoRelative);
        return Files.exists(fromCore)
            ? fromCore : Paths.get(repoRelative);
    }
}
