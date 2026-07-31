package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.scripting.loader.ScriptLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W15 — the Scroll of Settings config catalog, driven
 * through the REAL {@code scripts/rooms/study.js} in a live RoomActor
 * (same harness as {@link RoomActorRoomsAliveTest}):
 * <ul>
 *   <li>{@code use scroll keys} — grouped discovery index</li>
 *   <li>{@code use scroll list <group>} — one group's catalog with
 *       descriptions, defaults, and live current values</li>
 *   <li>teaching-get — {@code use scroll get KEY} on an UNSET catalog key
 *       explains the key (meaning, default, group) instead of shrugging</li>
 *   <li>set keys surface as {@code (set)} in the group catalog</li>
 * </ul>
 * Config reads are hermetic: {@code wyrdsekai.dataDir} system property points
 * SystemPaths.configFile() at a per-test temp dir (WorldApi's config bindings
 * are room-gated to the study, which this room id satisfies).
 */
@Tag("integration")
class ScrollOfSettingsCatalogTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    /** Repo scripts dir — tests run with cwd = core/, like StandardRoomLibraryTest. */
    private static final Path ROOM_SCRIPTS = Files.exists(Path.of("scripts/rooms"))
        ? Path.of("scripts/rooms")
        : Path.of("../scripts/rooms");

    @TempDir
    Path dataDir;

    @AfterAll
    static void shutdown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void hermeticConfig() {
        // SystemPaths honors this property first — keeps the test away from
        // any real ~/.wyrdsekai or /etc/wyrdsekai config on the dev box.
        System.setProperty("wyrdsekai.dataDir", dataDir.toString());
    }

    @AfterEach
    void clearConfigOverride() {
        System.clearProperty("wyrdsekai.dataDir");
    }

    /** Boot a live "study" RoomActor running the real study.js, furnished
     *  with the Scroll of Settings object, and a subscribed event probe. */
    private Harness studyRoom() {
        assumeTrue(Files.exists(ROOM_SCRIPTS.resolve("study.js")),
            "repo scripts/rooms/study.js not found — run from core/ or repo root");
        var loader = new ScriptLoader(ROOM_SCRIPTS, null);
        var kit = EventSourcedBehaviorTestKit
            .<RoomCommand, RoomEvent, RoomState>create(
                testKit.system(), RoomActor.create("study", loader));
        kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "The Study", "A steward's study.", "test", List.of(),
            List.of(new RoomObject("scroll-of-settings", "Scroll of Settings",
                "The household's configuration, readable in-world.", false)),
            ref));
        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));
        return new Harness(kit, probe);
    }

    private record Harness(
        EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
        org.apache.pekko.actor.testkit.typed.javadsl.TestProbe<RoomNotification> probe) {

        void useScroll(String target) {
            kit.<RoomResponse>runCommand(ref -> new RoomCommand.UseObject(
                "steward-1", "scroll of settings", target, "en", ref));
        }

        /** Fish the probe until a Said containing {@code needle} arrives. */
        WorldEvent.Said expectSaidContaining(String needle) {
            var timeout = Duration.ofSeconds(5);
            var deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var remaining = Duration.ofNanos(
                    Math.max(1, deadline - System.nanoTime()));
                var notification =
                    probe.expectMessageClass(RoomNotification.class, remaining);
                if (notification.event() instanceof WorldEvent.Said said
                        && said.text() != null && said.text().contains(needle)) {
                    return said;
                }
            }
            throw new AssertionError(
                "No Said containing '" + needle + "' within " + timeout);
        }
    }

    @Test
    void scroll_keys_shows_the_grouped_discovery_index() {
        var room = studyRoom();
        room.useScroll("keys");

        var said = room.expectSaidContaining("scroll's index");
        // The group index names every group with its key count — spot-check
        // the load-bearing ones and the how-to-open instruction.
        assertThat(said.text())
            .contains("use scroll list <group>")
            .contains("security")
            .contains("inference")
            .contains("network")
            .contains("identity");
    }

    @Test
    void scroll_list_group_opens_one_groups_catalog_with_defaults() {
        var room = studyRoom();
        room.useScroll("list security");

        var said = room.expectSaidContaining("security & privacy");
        assertThat(said.text())
            .contains("WYRDSEKAI_OFFLINE")
            .contains("hard offline switch")
            // Nothing overridden in the hermetic config → defaults shown.
            .contains("false (default)")
            // Keys from OTHER groups must not bleed into this view.
            .doesNotContain("WYRDSEKAI_INFERENCE_URL");
    }

    @Test
    void bare_group_word_also_opens_the_catalog() {
        var room = studyRoom();
        room.useScroll("voice");

        var said = room.expectSaidContaining("WYRDSEKAI_VOICE_ENABLED");
        assertThat(said.text()).contains("WYRDSEKAI_VOICE_URL");
    }

    @Test
    void teaching_get_on_unset_catalog_key_explains_meaning_default_and_group() {
        var room = studyRoom();
        room.useScroll("get WYRDSEKAI_TELNET_PORT");

        var said = room.expectSaidContaining("WYRDSEKAI_TELNET_PORT is not set");
        assertThat(said.text())
            .contains("telnet port")           // the catalog description
            .contains("default: 7071")         // the catalog default
            .contains("group: network")        // where to find siblings
            .contains("use scroll set WYRDSEKAI_TELNET_PORT=VALUE");
    }

    @Test
    void overridden_key_shows_as_set_in_its_group_catalog() throws Exception {
        Files.writeString(dataDir.resolve("wyrdsekai.conf"),
            "WYRDSEKAI_SSH_PORT=7522\n");

        var room = studyRoom();
        room.useScroll("list network");

        var said = room.expectSaidContaining("network & doors");
        assertThat(said.text()).contains("7522  (set)");
    }

    @Test
    void bare_scroll_on_fresh_install_teaches_the_catalog_vocabulary() {
        var room = studyRoom();
        room.useScroll("");

        // Fresh install = nothing overridden. The second-node 2026-07-04 finding:
        // this must NOT be a dead end — it has to point at 'use scroll keys'.
        var said = room.expectSaidContaining("blank");
        assertThat(said.text()).contains("use scroll keys");
    }
}
