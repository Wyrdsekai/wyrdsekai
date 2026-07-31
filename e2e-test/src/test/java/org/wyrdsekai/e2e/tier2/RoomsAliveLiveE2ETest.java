package org.wyrdsekai.e2e.tier2;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.core.room.RoomActor;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomEvent;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.RoomState;
import org.wyrdsekai.core.room.StandardRoomLibrary;
import org.wyrdsekai.scripting.loader.ScriptLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROOMS-ALIVE live E2E (W2, task #23) — the newly wired
 * room-script surfaces exercised with the REAL shipped scripts, not synthetic
 * fixtures. {@code RoomActorRoomsAliveTest} (core) proves the mechanisms with
 * inline test scripts; this suite proves the actual std library files that
 * production rooms are born with work end-to-end through the same wiring:
 *
 * <ol>
 *   <li><b>Template room</b> — {@link StandardRoomLibrary} materializes the
 *       {@code std/room/empty.js} base script (resolveBaseScript — the wiring
 *       that had zero callers before W2), it is delivered through
 *       {@code SetBehaviorScript} exactly like the room-creation path does,
 *       and its {@code onEnter} narration + {@code getHints} hints fire.</li>
 *   <li><b>§31 timer</b> — the real {@code std/behavior/narrator.js} mixin is
 *       appended, self-schedules its timer on entry, and a timer-driven
 *       narration lands within the deadline (~2 min budget; the interval is
 *       configured down to seconds via the persisted room property the mixin
 *       reads).</li>
 *   <li><b>onLeave</b> — the real {@code std/behavior/recorder.js} mixin
 *       observes a departure: walking the entity out lands a durable
 *       {@code recorder.log} property write with a {@code "leave"} entry
 *       (PropertyChanged is both persisted and notified).</li>
 * </ol>
 *
 * <p>No LLM needed — every assertion is a deterministic script-machinery
 * outcome. Uses the repo's real {@code scripts/} tree; self-skips if it can't
 * be located (never the case in a checkout).</p>
 */
@Tag("tier2")
class RoomsAliveLiveE2ETest {

    private static ActorTestKit testKit;
    private static Path scriptsRoot;

    @BeforeAll
    static void setUp() {
        scriptsRoot = locateScriptsRoot();
        assumeTrue(scriptsRoot != null,
            "repo scripts/ directory not found — cannot exercise real std scripts");
        testKit = ActorTestKit.create("rooms-alive-live-e2e",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.serialization-bindings {
                  "org.wyrdsekai.core.room.RoomEvent" = jackson-json
                  "org.wyrdsekai.core.room.RoomState" = jackson-json
                  "org.wyrdsekai.core.room.RoomCommand" = jackson-json
                  "org.wyrdsekai.core.room.RoomNotification" = jackson-json
                  "org.wyrdsekai.core.room.RoomResponse" = jackson-json
                }
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private static Path locateScriptsRoot() {
        for (var candidate : List.of(
                Path.of("scripts"), Path.of("../scripts"), Path.of("../../scripts"))) {
            if (Files.isDirectory(candidate.resolve("std/room"))
                    && Files.isDirectory(candidate.resolve("std/behavior"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    // ─── harness helpers (RoomActorRoomsAliveTest pattern) ──────────

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kitFor(
            String roomId, Path userScriptsDir) {
        // Base dir = the real repo room-script dir; user dir = temp, where
        // SetBehaviorScript materializes the template base + mixins.
        var loader = new ScriptLoader(scriptsRoot.resolve("rooms"), userScriptsDir);
        return EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create(roomId, loader));
    }

    private void createRoom(
            EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
            String name) {
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom(name, "A room born from a template.",
                "player", List.of(), List.of(), ref));
    }

    /** Install script source on the room via the production SetBehaviorScript path. */
    private void installScript(
            EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
            String roomId, String source, boolean append) {
        var result = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetBehaviorScript(roomId, source, "steward", append, ref));
        assertThat(result.reply())
            .as("SetBehaviorScript(append=%s) must be accepted", append)
            .isInstanceOf(RoomResponse.Ok.class);
    }

    /** Fish the probe until a Said event containing {@code needle} arrives. */
    private static WorldEvent.Said expectSaidContaining(
            TestProbe<RoomNotification> probe, String needle, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            var notification = probe.expectMessageClass(RoomNotification.class, remaining);
            if (notification.event() instanceof WorldEvent.Said said
                    && said.text() != null && said.text().contains(needle)) {
                return said;
            }
        }
        throw new AssertionError("No Said containing '" + needle + "' within " + timeout);
    }

    /** Fish the probe until a PropertyChanged for {@code key} whose value contains {@code needle}. */
    private static WorldEvent.PropertyChanged expectPropertyContaining(
            TestProbe<RoomNotification> probe, String key, String needle, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            var notification = probe.expectMessageClass(RoomNotification.class, remaining);
            if (notification.event() instanceof WorldEvent.PropertyChanged changed
                    && key.equals(changed.key())
                    && changed.newValue() != null && changed.newValue().contains(needle)) {
                return changed;
            }
        }
        throw new AssertionError(
            "No PropertyChanged '" + key + "' containing '" + needle + "' within " + timeout);
    }

    private void setRoomProperty(
            EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
            String key, String value) {
        kit.runCommand(new RoomCommand.ItemBridgeAction(
            "test", new RoomCommand.ItemBridgeSubAction.SetProperty(key, value)));
    }

    // ─── 1. Template room: real base script → onEnter + getHints ────

    @Test
    void template_room_base_script_fires_onEnter_and_getHints(@TempDir Path userDir) {
        var library = new StandardRoomLibrary(scriptsRoot);
        assertThat(library.templates())
            .as("StandardRoomLibrary must register templates from the real scripts tree")
            .isNotEmpty();

        // The wiring under test: resolveBaseScript actually materializes the
        // std/room source (this had ZERO callers before W2).
        var baseScript = library.baseScriptFor("empty");
        assertThat(baseScript)
            .as("std/room/empty.js base script must resolve from the scripts root")
            .isNotBlank()
            .contains("function onEnter")
            .contains("function getHints");

        var roomId = "e2e-template-empty";
        var kit = kitFor(roomId, userDir);
        createRoom(kit, "Template Room");
        // Deliver the template's base script the way room creation does.
        installScript(kit, roomId, baseScript, false);

        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // onEnter — the real script narrates "<name> enters An Empty Room. …"
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));
        var said = expectSaidContaining(probe, "enters An Empty Room", Duration.ofSeconds(10));
        assertThat(said.text()).contains("Alice");

        // getHints — LookRoom builds hints through scriptEngine.getHints();
        // the real script offers exactly "Look around".
        var look = kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("p1", "en", ref));
        assertThat(look.reply()).isInstanceOf(RoomResponse.Ok.class);
        var snapshot = ((RoomResponse.Ok) look.reply()).snapshot();
        assertThat(snapshot.hints())
            .as("script getHints() must surface in the room snapshot")
            .extracting(Hint::label)
            .contains("Look around");
    }

    // ─── 2. §31 timer: real narrator mixin self-schedules + narrates ─

    @Test
    void narrator_mixin_timer_narration_lands(@TempDir Path userDir) throws Exception {
        var narratorSource = Files.readString(
            scriptsRoot.resolve("std/behavior/narrator.js"));

        var roomId = "e2e-narrator-timer";
        var kit = kitFor(roomId, userDir);
        createRoom(kit, "Narrated Room");

        // Install the REAL narrator mixin through the append path (the
        // add_script install shape). No base script — mixin standalone.
        installScript(kit, roomId, narratorSource, true);

        // Configure via the persisted room properties the mixin reads:
        // a seconds-scale interval so the test doesn't sit for 300s, and a
        // recognizable ambient line.
        var ambient = "The rafters creak as a wind no one summoned passes through.";
        setRoomProperty(kit, "narrator.interval", "3");
        setRoomProperty(kit, "narrator.descriptions", ambient);

        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Entry → _narrator_schedule() → world.scheduleTimer("narrator", 3, "onTimer")
        // → RoomActor drains the request, starts the actor timer → TimerFired
        // → invokeTimer → onTimer → narrate emission → Said to subscribers.
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));

        // §31 assertion: the timer-driven narration must land well within 2 minutes.
        var said = expectSaidContaining(probe, ambient, Duration.ofSeconds(120));
        assertThat(said.entityId()).isEqualTo("narrator");
    }

    // ─── 3. onLeave: real recorder mixin observes the departure ─────

    @Test
    void recorder_mixin_onLeave_records_departure(@TempDir Path userDir) throws Exception {
        var recorderSource = Files.readString(
            scriptsRoot.resolve("std/behavior/recorder.js"));

        var roomId = "e2e-recorder-leave";
        var kit = kitFor(roomId, userDir);
        createRoom(kit, "Recorded Room");
        installScript(kit, roomId, recorderSource, true);

        // Property writes are steward-level detail — PropertyChanged notifies at
        // PRIVILEGED visibility, so subscribe the way a warden/system actor would.
        var probe = testKit.<RoomNotification>createTestProbe();
        kit.runCommand(new RoomCommand.Subscribe(probe.ref(), VisibilityLevel.PRIVILEGED));

        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("p1", "Alice", "player", "north", ref));
        // The mixin records the arrival — proves the enter leg too.
        expectPropertyContaining(probe, "recorder.log", "\"enter\"", Duration.ofSeconds(10));

        // Walk the entity out — the newly wired onLeave dispatch must reach the
        // mixin, whose durable record is the observable effect.
        kit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LeaveRoom("p1", "Alice", "south", ref));
        var changed = expectPropertyContaining(
            probe, "recorder.log", "\"leave\"", Duration.ofSeconds(10));
        assertThat(changed.newValue())
            .as("recorder.log must carry the departure with its direction")
            .contains("Alice")
            .contains("south");

        // Durability corroboration: the record is in the persisted room state,
        // not just the notification stream.
        assertThat(kit.getState().properties().get("recorder.log"))
            .contains("\"leave\"")
            .contains("south");
    }
}
