package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enrolling a recipe that is already enrolled must update it, not fail.
 *
 * <p>{@code agent_did_key} exists because {@code agent_did} is nullable and SQL
 * {@code ON CONFLICT} cannot see NULL = NULL, so the nullable column is mirrored into a
 * non-null one that carries the primary key. The insert left that column to its
 * {@code ''} default and relied on an AFTER INSERT trigger to fill it in — which means
 * the insert never conflicted ({@code ''} was unique), the row landed, and the trigger
 * then rewrote the key into a collision with the row already there. A conflict raised
 * after the insert has already succeeded is past the point where {@code ON CONFLICT} can
 * handle it, so the statement aborted with SQLITE_CONSTRAINT_PRIMARYKEY.
 *
 * <p>Live consequence on a household node (found 2026-08-18): four warnings on every
 * start, and no enrollment could ever be updated in place. The first write for a
 * (recipe, agent) pair stuck permanently — so gap keys or a cadence change shipped in a
 * later release would silently never reach an install that already had the row.
 */
class EnrollmentUpsertSurvivesRebootTest {

    private static final String DID = "did:key:z6MkExampleCompanion";
    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");

    private Path db;
    private RecipeEnrollmentStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = Files.createTempFile("recipe-enrollments", ".db");
        Files.delete(db);
        store = new RecipeEnrollmentStore("jdbc:sqlite:" + db);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(db);
    }

    private static RecipeEnrollment enrollment(String did, Set<String> gapKeys,
            boolean enabled, CadenceTier tier) {
        return new RecipeEnrollment("retrain-classifier-head", did, tier, 0, T0,
            enabled, gapKeys);
    }

    @Test
    void enrolling_twice_updates_the_row_instead_of_failing() {
        store.upsert(enrollment(DID, Set.of("task_present.misroute"), true,
            CadenceTier.WARMUP));
        // Second boot: same recipe, same agent, new gap keys from a later release.
        store.upsert(enrollment(DID, Set.of("task_present.misroute",
            "cleanliness.misroute"), true, CadenceTier.SETTLING));

        var rows = store.listEnabled();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).gapKeys())
            .containsExactlyInAnyOrder("task_present.misroute", "cleanliness.misroute");
        assertThat(rows.get(0).cadenceTier()).isEqualTo(CadenceTier.SETTLING);
    }

    @Test
    void a_new_gap_key_reaches_an_install_that_already_had_the_row() {
        store.upsert(enrollment(DID, Set.of("task_present.misroute"), true,
            CadenceTier.WARMUP));
        store.upsert(enrollment(DID, Set.of("task_present.misroute",
            "request_type.misroute"), true, CadenceTier.WARMUP));

        assertThat(store.listByGapKey("request_type.misroute")).hasSize(1);
    }

    @Test
    void two_companions_keep_separate_enrollments_for_the_same_recipe() {
        // The whole reason for the synthetic key column: these must not collapse.
        store.upsert(enrollment(DID, Set.of("task_present.misroute"), true,
            CadenceTier.WARMUP));
        store.upsert(enrollment("did:key:z6MkSecondCompanion",
            Set.of("task_present.misroute"), true, CadenceTier.WARMUP));

        assertThat(store.listEnabled()).hasSize(2);
    }

    @Test
    void an_unscoped_enrollment_still_works_with_a_null_agent() {
        store.upsert(enrollment(null, Set.of("task_present.misroute"), true,
            CadenceTier.WARMUP));
        store.upsert(enrollment(null, Set.of("task_present.misroute"), true,
            CadenceTier.SETTLING));

        var rows = store.listEnabled();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).agentDid()).isNull();
        assertThat(rows.get(0).cadenceTier()).isEqualTo(CadenceTier.SETTLING);
    }

    @Test
    void an_existing_row_written_the_old_way_is_still_updatable() throws Exception {
        // Reproduces the live shape: rows already carrying agent_did_key = <did>,
        // written before this fix. A release must be able to update them.
        store.upsert(enrollment(DID, Set.of("task_present.misroute"), true,
            CadenceTier.WARMUP));
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = conn.createStatement()) {
            var keyed = st.executeQuery(
                "SELECT agent_did_key FROM recipe_enrollments");
            assertThat(keyed.next()).isTrue();
            assertThat(keyed.getString(1)).isEqualTo(DID);
        }

        store.upsert(enrollment(DID, Set.of("cleanliness.misroute"), false,
            CadenceTier.MATURE));
        assertThat(store.listEnabled()).isEmpty();          // now disabled
    }
}
