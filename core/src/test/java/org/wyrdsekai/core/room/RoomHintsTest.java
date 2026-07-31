package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hint-surfacing tests for sittable furnishings and the
 * caller-gated Stand hint. Companion to {@link RoomActorPostureTest} which
 * covers the SetPosture / ClearPosture command handlers.
 */
@Tag("integration")
class RoomHintsTest {

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

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> behaviorTestKit;

    @BeforeEach
    void setUp() {
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("hints-test", null, null, null, null));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Hints Test Room",
                "A room for hint surfacing tests.", "test", List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("alice", "Alice", "player", "north", ref));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    /**
     * Helper: seed the room with a single sittable object by replacing the
     * room with a fresh CreateRoom that carries the object. This is the only
     * path that lets us pre-flag {@code state.sittable=true} on a RoomObject
     * without going through script-engine plumbing.
     */
    private void seedSittableRoom(String objId, String objName) {
        // Re-create the room with the object as a seed RoomObject carrying state
        var sittableObj = new RoomObject(
            objId, objName, "A " + objName, false, true, false,
            List.of(objName.toLowerCase()),
            Map.of("sittable", "true"));
        // Re-seed via a fresh actor — simpler than mutating in place
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("hints-test-" + objId, null, null, null, null));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Hints Test Room",
                "A room with a sittable object.", "test",
                List.of(), List.of(sittableObj), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("alice", "Alice", "player", "north", ref));
    }

    private List<Hint> currentHints() {
        var resp = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("alice", "en", ref)).reply();
        if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
            return ok.snapshot().hints();
        }
        return List.of();
    }

    @Test
    void sittable_object_surfaces_sit_hint() {
        seedSittableRoom("leather-chair-1", "leather chair");

        var hints = currentHints();
        var sitHint = hints.stream()
            .filter(h -> h.label() != null && h.label().toLowerCase().startsWith("sit "))
            .findFirst();
        assertThat(sitHint).as("a Sit hint should surface for a sittable object").isPresent();
        assertThat(sitHint.get().label()).isEqualTo("Sit in leather chair");
        assertThat(sitHint.get().action()).isEqualTo("sit in leather chair");
    }

    @Test
    void stand_hint_only_when_seated() {
        seedSittableRoom("leather-chair-2", "leather chair");

        // Before sitting: no Stand hint
        var before = currentHints();
        assertThat(before).as("Stand hint should NOT surface before sitting")
            .noneMatch(h -> h.label() != null && h.label().toLowerCase().startsWith("stand"));

        // Sit
        var posture = new Posture("sat", "leather-chair-2",
            "Alice settles into the leather chair.");
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("alice", posture, ref));

        // After sitting: Stand hint surfaces
        var after = currentHints();
        assertThat(after).as("Stand hint SHOULD surface after sitting")
            .anyMatch(h -> "Stand up".equals(h.label())
                && "stand".equals(h.action()));
    }

    @Test
    void hint_preposition_heuristic_chair_bench_table() {
        // chair → "in"
        assertThat(RoomActor.sitPreposition("leather chair")).isEqualTo("in");
        assertThat(RoomActor.sitPreposition("ArmChair")).isEqualTo("in");
        assertThat(RoomActor.sitPreposition("couch")).isEqualTo("in");
        assertThat(RoomActor.sitPreposition("sofa")).isEqualTo("in");

        // bench / floor / stool / cushion → "on"
        assertThat(RoomActor.sitPreposition("wooden bench")).isEqualTo("on");
        assertThat(RoomActor.sitPreposition("floor")).isEqualTo("on");
        assertThat(RoomActor.sitPreposition("stool")).isEqualTo("on");
        assertThat(RoomActor.sitPreposition("cushion")).isEqualTo("on");

        // table / hearth / unknown → "at"
        assertThat(RoomActor.sitPreposition("table")).isEqualTo("at");
        assertThat(RoomActor.sitPreposition("desk")).isEqualTo("at");
        assertThat(RoomActor.sitPreposition("hearth")).isEqualTo("at");
        assertThat(RoomActor.sitPreposition(null)).isEqualTo("at");
    }

    @Test
    void non_sittable_object_does_not_surface_sit_hint() {
        // Seed a room with a NON-sittable object (state.sittable not set)
        var plainObj = new RoomObject(
            "rock-1", "stone", "A heavy stone.", false, true, false,
            List.of("stone"),
            Map.of());
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("hints-test-nosit", null, null, null, null));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Plain Room",
                "A plain room with a stone.", "test",
                List.of(), List.of(plainObj), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("alice", "Alice", "player", "north", ref));

        var hints = currentHints();
        assertThat(hints).as("non-sittable objects should not get a Sit hint")
            .noneMatch(h -> h.label() != null && h.label().toLowerCase().startsWith("sit "));
    }

    /**
     * Items-as-tools contract — a TAKEABLE object whose id matches a loaded
     * scripted item with manifest-declared {@code commands} surfaces those
     * commands as discovery hints (previously only the non-takeable branch
     * called {@code appendScriptCommandHints}, so agent-built takeable tools
     * were undiscoverable).
     */
    @Test
    void takeable_scripted_item_surfaces_declared_command_hints(@TempDir Path itemsDir)
            throws Exception {
        Files.writeString(itemsDir.resolve("wind_gauge.js"), """
            exports.manifest = {
              name: "wind_gauge",
              version: "1.0.0",
              description: "Reads the wind.",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: { silent: true, reason: "measurement only" },
              commands: [
                { label: "Read the wind", args: "" },
                { label: "Read gust details", args: "details" }
              ]
            };
            function invoke(params) { return { ok: true }; }
            """);
        ScriptedItemLoader.get().setSearchDirs(List.of(itemsDir));
        ScriptedItemLoader.get().reloadAll();
        try {
            var takeable = new RoomObject(
                "wind_gauge", "wind gauge", "A brass wind gauge.", true, true, false,
                List.of("gauge"), Map.of());
            behaviorTestKit = EventSourcedBehaviorTestKit.create(
                testKit.system(),
                RoomActor.create("hints-test-takeable", null, null, null, null));
            behaviorTestKit.<RoomResponse>runCommand(
                ref -> new RoomCommand.CreateRoom("Hints Test Room",
                    "A room with a takeable scripted tool.", "test",
                    List.of(), List.of(takeable), ref));
            behaviorTestKit.<RoomResponse>runCommand(
                ref -> new RoomCommand.EnterRoom("alice", "Alice", "player", "north", ref));

            var hints = currentHints();
            assertThat(hints)
                .as("declared no-arg command surfaces as a hint: " + hints)
                .anyMatch(h -> "Read the wind".equals(h.label())
                    && "use:wind gauge".equals(h.action()));
            assertThat(hints)
                .as("declared args command dispatches as use:<name>|<args>: " + hints)
                .anyMatch(h -> "Read gust details".equals(h.label())
                    && "use:wind gauge|details".equals(h.action()));
            // The takeable basics stay intact alongside the command hints.
            assertThat(hints)
                .anyMatch(h -> h.action() != null && h.action().equals("take:wind gauge"));
        } finally {
            ScriptedItemLoader.get().setSearchDirs(List.of());
            ScriptedItemLoader.get().reloadAll();
        }
    }

    @Test
    void stand_hint_clears_when_posture_clears() {
        seedSittableRoom("leather-chair-3", "leather chair");

        // Sit, verify Stand present
        var posture = new Posture("sat", "leather-chair-3",
            "Alice settles into the leather chair.");
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("alice", posture, ref));
        assertThat(currentHints())
            .anyMatch(h -> "Stand up".equals(h.label()));

        // Clear posture, Stand hint disappears
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.ClearPosture("alice", ref));
        assertThat(currentHints())
            .noneMatch(h -> h.label() != null && h.label().toLowerCase().startsWith("stand"));
    }
}
