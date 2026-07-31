package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C9 — ship-default provisioner unit tests.
 */
class ShipDefaultEnrollmentProvisionerTest {

    @Test
    void discoverHeads_csv_overrides_everything(@TempDir Path tmp) throws Exception {
        // Even with a populated pretrained dir, the CSV override wins.
        var pretrained = tmp.resolve("pretrained");
        Files.createDirectories(pretrained);
        Files.writeString(pretrained.resolve("from_disk.onnx"), "x");

        var heads = ShipDefaultEnrollmentProvisioner.discoverHeads(
            " task_present , request_type ", pretrained);

        assertThat(heads).containsExactly("task_present", "request_type");
    }

    @Test
    void discoverHeads_disk_when_csv_blank(@TempDir Path tmp) throws Exception {
        var pretrained = tmp.resolve("pretrained");
        Files.createDirectories(pretrained);
        Files.writeString(pretrained.resolve("task_present.onnx"), "x");
        Files.writeString(pretrained.resolve("substrate_present.onnx"), "x");
        // Non-onnx siblings should be ignored.
        Files.writeString(pretrained.resolve("task_present.labels.json"), "x");

        var heads = ShipDefaultEnrollmentProvisioner.discoverHeads("", pretrained);

        assertThat(heads).containsExactly("substrate_present", "task_present");
    }

    @Test
    void discoverHeads_falls_back_to_baseline_when_both_empty(@TempDir Path tmp) {
        var heads = ShipDefaultEnrollmentProvisioner.discoverHeads(
            null, tmp.resolve("nope"));
        assertThat(heads).isEqualTo(ShipDefaultEnrollmentProvisioner.BASELINE_HEADS);
    }

    @Test
    void defaults_emits_one_row_per_agent_per_ship_default_recipe() {
        var t0 = Instant.parse("2026-05-25T12:00:00Z");
        var rows = ShipDefaultEnrollmentProvisioner.defaults(
            List.of("task_present", "request_type"),
            List.of("did:wyrd:companion-a", "did:wyrd:companion-b"),
            t0);

        // Store PK = (recipe_id, agent_did). For each agent, one row per
        // ship-default recipe: retrain-classifier-head (gap-keyed by every
        // head's misroute pattern) + the cron-only housekeeping recipes
        // (consolidate-memory-graph, consolidate-soul-fragments,
        // welfare-floor-checkup — no gap keys). 2 agents × |SHIP_DEFAULT_RECIPES|.
        int recipeCount = ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES.size();
        assertThat(rows).hasSize(2 * recipeCount);
        var retrainRows = rows.stream().filter(r -> r.recipeId()
                .equals(ShipDefaultEnrollmentProvisioner.DEFAULT_RECIPE)).toList();
        assertThat(retrainRows).hasSize(2);
        for (var r : retrainRows) {
            assertThat(r.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            assertThat(r.consecutiveSuccesses()).isZero();
            assertThat(r.enabled()).isTrue();
            assertThat(r.enrolledAt()).isEqualTo(t0);
            assertThat(r.gapKeys()).containsExactlyInAnyOrder(
                "task_present.misroute", "request_type.misroute");
        }
        var consolidateRows = rows.stream().filter(r -> r.recipeId()
                .equals("consolidate-memory-graph")).toList();
        assertThat(consolidateRows).hasSize(2);
        for (var r : consolidateRows) {
            assertThat(r.gapKeys()).isEmpty();
            assertThat(r.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            assertThat(r.enabled()).isTrue();
        }
    }

    @Test
    void provision_is_idempotent_against_store(@TempDir Path tmp) throws Exception {
        var dbPath = tmp.resolve("db.sqlite");
        var jdbcUrl = "jdbc:sqlite:" + dbPath;
        var store = new RecipeEnrollmentStore(jdbcUrl);
        var pretrained = tmp.resolve("pretrained");
        Files.createDirectories(pretrained);
        Files.writeString(pretrained.resolve("task_present.onnx"), "x");

        var dids = List.of("did:wyrd:companion-a");
        var t0 = Instant.parse("2026-05-25T12:00:00Z");
        var firstPass = ShipDefaultEnrollmentProvisioner.provision(
            store, "", pretrained, dids, t0);
        var secondPass = ShipDefaultEnrollmentProvisioner.provision(
            store, "", pretrained, dids, t0);

        int expected = ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES.size();
        assertThat(firstPass).hasSize(expected);
        assertThat(secondPass).hasSize(expected);
        // Store has one row per (recipe_id, agent_did) — upsert collapses repeats.
        assertThat(store.listAll()).hasSize(expected);
    }

    @Test
    void provision_no_companions_returns_empty() {
        var rows = ShipDefaultEnrollmentProvisioner.provision(
            /* store */ null, "task_present", null, List.of(), Instant.now());
        assertThat(rows).isEmpty();
    }

    // -- #1008: per-companion enrollment hook on spawn -----------------------

    @Test
    void provisionForCompanion_no_registry_returns_zero() {
        // Registry unset (pre-boot, scheduler disabled, unit-test default).
        // No-op + no exception — soul-birth must never crash on this path.
        RecipeEnrollmentRegistry.resetForTests();
        int rows = ShipDefaultEnrollmentProvisioner.provisionForCompanion("did:wyrd:x");
        assertThat(rows).isZero();
    }

    @Test
    void provisionForCompanion_blank_did_returns_zero() {
        RecipeEnrollmentRegistry.resetForTests();
        assertThat(ShipDefaultEnrollmentProvisioner.provisionForCompanion(null)).isZero();
        assertThat(ShipDefaultEnrollmentProvisioner.provisionForCompanion("")).isZero();
        assertThat(ShipDefaultEnrollmentProvisioner.provisionForCompanion("   ")).isZero();
    }

    @Test
    void provisionForCompanion_writes_row_when_registry_set(@TempDir Path tmp) {
        // Boot publishes a registry context; provisionForCompanion reads it
        // and writes one ship-default row for the new companion's DID. Same
        // shape as the boot-time bulk-provision path, just for one DID.
        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("recipes-1008.db");
        var store = new RecipeEnrollmentStore(jdbcUrl);
        try {
            RecipeEnrollmentRegistry.setInstance(
                new RecipeEnrollmentRegistry.Context(store, "task_present", null));
            int rows = ShipDefaultEnrollmentProvisioner.provisionForCompanion(
                "did:wyrd:new-companion");
            int expected = ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES.size();
            assertThat(rows).isEqualTo(expected);

            var enrollments = store.listAll();
            assertThat(enrollments).hasSize(expected);
            assertThat(enrollments).extracting(RecipeEnrollment::recipeId)
                .containsExactlyInAnyOrderElementsOf(
                    ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES);
            for (var e : enrollments) {
                assertThat(e.agentDid()).isEqualTo("did:wyrd:new-companion");
                assertThat(e.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            }
        } finally {
            RecipeEnrollmentRegistry.resetForTests();
        }
    }

    @Test
    void provisionForCompanion_is_idempotent(@TempDir Path tmp) {
        // Calling on a spawn that already has rows is a no-op upsert —
        // the (recipe_id, agent_did) composite key short-circuits the
        // store. Important because CompanionActor may call us on every
        // soul-birth path including re-resolutions.
        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("recipes-1008-idem.db");
        var store = new RecipeEnrollmentStore(jdbcUrl);
        try {
            RecipeEnrollmentRegistry.setInstance(
                new RecipeEnrollmentRegistry.Context(store, "task_present", null));
            ShipDefaultEnrollmentProvisioner.provisionForCompanion("did:wyrd:a");
            ShipDefaultEnrollmentProvisioner.provisionForCompanion("did:wyrd:a");
            ShipDefaultEnrollmentProvisioner.provisionForCompanion("did:wyrd:a");
            assertThat(store.listAll()).hasSize(
                ShipDefaultEnrollmentProvisioner.SHIP_DEFAULT_RECIPES.size());
        } finally {
            RecipeEnrollmentRegistry.resetForTests();
        }
    }
}
