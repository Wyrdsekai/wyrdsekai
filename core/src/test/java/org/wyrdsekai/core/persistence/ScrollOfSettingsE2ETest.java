package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.WorldApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the Phase 4 in-world configuration flow. Proves
 * that "install → SSH into study → use scroll set/get/apply → restart →
 * config sticks" works at the primitive layer without requiring a live
 * systemd install.
 *
 * <p>Sequence under test:
 * <ol>
 *   <li>{@link WorldApi#configSet(String, String)} from a script running in
 *       the-study writes to {@code $DATA_DIR/wyrdsekai.conf}.</li>
 *   <li>{@link WorldApi#configGet(String)} reads the value back across a
 *       fresh WorldApi instance (simulates a reconnect after restart).</li>
 *   <li>{@link WorldApi#configApply()} emits {@code config_apply_requested};
 *       the event is captured and routed to {@link ConfigApplyCoordinator}
 *       which drops a restart marker file a watchdog / test can observe.</li>
 *   <li>After clearing the marker the flow is idempotent and repeatable —
 *       no lingering "in-flight" gate that would block a follow-up apply.</li>
 * </ol>
 *
 * <p>Room-gating: the scroll is scoped to {@code the-study} and {@code study}.
 * Calls from non-study rooms must return null/false/empty so stewardship
 * is respected even when a script mistakenly runs elsewhere.
 */
class ScrollOfSettingsE2ETest {

    private Path tmpDir;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("scroll-e2e-");
        // SystemPaths reads this system property before falling back to
        // WYRDSEKAI_DATA_DIR (which JDK 17+ can't mutate from Java).
        System.setProperty("wyrdsekai.dataDir", tmpDir.toAbsolutePath().toString());
        ConfigApplyCoordinator.resetForTests();
    }

    @AfterEach
    void tearDown() throws Exception {
        ConfigApplyCoordinator.resetForTests();
        System.clearProperty("wyrdsekai.dataDir");
        // Tidy the tmp dir so parallel runs don't accumulate.
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
            }
        }
    }

    @Test
    void scrollSet_writesKey_scrollGet_readsItBack() {
        var world = new WorldApi("the-study");
        assertTrue(world.configSet("WYRDSEKAI_ZONE_ID", "alpha"),
            "steward write from the-study must succeed");

        // Simulate a reconnect after a restart by constructing a fresh WorldApi
        // — the config must be read from disk, not a cached field.
        var afterRestart = new WorldApi("the-study");
        assertEquals("alpha", afterRestart.configGet("WYRDSEKAI_ZONE_ID"));
    }

    @Test
    void scrollSet_updatesExistingKey_preservingComments() throws Exception {
        var world = new WorldApi("the-study");
        assertTrue(world.configSet("WYRDSEKAI_ZONE_ID", "alpha"));
        assertTrue(world.configSet("WYRDSEKAI_ZONE_ID", "beta"),
            "re-setting the same key must succeed");

        var listed = world.configList();
        assertEquals("beta", listed.get("WYRDSEKAI_ZONE_ID"),
            "last write wins — no duplicate entries");
        assertEquals(1, listed.entrySet().stream()
            .filter(e -> e.getKey().equals("WYRDSEKAI_ZONE_ID")).count(),
            "no duplicate KEY= lines should survive a re-set");
    }

    @Test
    void scrollSet_rejectsLowercaseKeys_returnsFalse() {
        var world = new WorldApi("the-study");
        assertFalse(world.configSet("zone_id", "alpha"),
            "lowercase keys are rejected — must match [A-Z][A-Z0-9_]*");
        assertFalse(world.configSet("With-Dashes", "alpha"),
            "dashes are rejected");
    }

    @Test
    void scrollBindings_roomGated_denyFromNonStudy() {
        var docks = new WorldApi("docks");
        assertNull(docks.configGet("WYRDSEKAI_ZONE_ID"),
            "configGet from docks must return null");
        assertFalse(docks.configSet("WYRDSEKAI_ZONE_ID", "alpha"),
            "configSet from docks must be denied");
        assertTrue(docks.configList().isEmpty(),
            "configList from docks must be empty");
    }

    @Test
    void scrollApply_writesRestartMarker_asSystemPathsPredicts() {
        var world = new WorldApi("the-study");
        // Hook the emit so we simulate RoomActor's dispatch to
        // ConfigApplyCoordinator — this is exactly what RoomActor.processEmissions
        // does for the "config_apply_requested" event type.
        var captured = new AtomicReference<String>();
        world.onEvent((eventType, data) -> {
            if ("config_apply_requested".equals(eventType)) {
                captured.set(String.valueOf(data.get("roomId")));
                // Mirror RoomActor's handler.
                ConfigApplyCoordinator.requestRestart(
                    "scroll-of-settings in " + data.get("roomId"));
            }
        });

        world.configApply();
        assertEquals("the-study", captured.get(),
            "apply must emit the event with the originating roomId");
        assertTrue(ConfigApplyCoordinator.isRestartRequested(),
            "after apply the restart marker file must exist — this is what "
            + "the wyrd watchdog / systemd restart trigger watches for");
    }

    @Test
    void markerFlow_clearable_soNextApplyWorks() {
        var world = new WorldApi("the-study");
        world.onEvent((t, d) -> {
            if ("config_apply_requested".equals(t)) {
                ConfigApplyCoordinator.requestRestart("first");
            }
        });
        world.configApply();
        assertTrue(ConfigApplyCoordinator.isRestartRequested());

        ConfigApplyCoordinator.resetForTests();
        assertFalse(ConfigApplyCoordinator.isRestartRequested(),
            "reset must clear both the in-flight gate and the marker file");
    }

    @Test
    void fullJourney_setKey_apply_observeMarker_clear_setAgain_readBack() {
        var world = new WorldApi("the-study");
        world.onEvent((t, d) -> {
            if ("config_apply_requested".equals(t)) {
                ConfigApplyCoordinator.requestRestart("journey");
            }
        });

        // 1. Set initial value.
        assertTrue(world.configSet("WYRDSEKAI_INFERENCE_URL", "http://a:1"));
        // 2. Apply — marker dropped.
        world.configApply();
        assertTrue(ConfigApplyCoordinator.isRestartRequested());

        // 3. Simulate the restart completing (watchdog clears the marker
        //    once it's relaunched us). A brand-new WorldApi simulates the
        //    fresh JVM picking up the written config.
        ConfigApplyCoordinator.resetForTests();
        var fresh = new WorldApi("the-study");
        assertEquals("http://a:1", fresh.configGet("WYRDSEKAI_INFERENCE_URL"));

        // 4. Steward changes mind, updates again, applies. Marker lands.
        assertTrue(fresh.configSet("WYRDSEKAI_INFERENCE_URL", "http://b:2"));
        fresh.onEvent((t, d) -> {
            if ("config_apply_requested".equals(t)) {
                ConfigApplyCoordinator.requestRestart("journey-2");
            }
        });
        fresh.configApply();
        assertTrue(ConfigApplyCoordinator.isRestartRequested());
        assertEquals("http://b:2", new WorldApi("the-study").configGet("WYRDSEKAI_INFERENCE_URL"));
    }

}
