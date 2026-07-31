package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for RoomActor using Pekko's EventSourcedBehaviorTestKit.
 * Tests the full event-sourced lifecycle without needing a real database.
 */
@Tag("integration")
class RoomActorTest {

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

    @BeforeEach void setUp() {
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("test-room"));
    }

    @AfterAll static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test void create_room() {
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom(
                "The Nexus", "A shimmering hub.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(new RoomObject("crystal", "Nexus Crystal", "Glowing.", false)),
                ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().name()).isEqualTo("The Nexus");
        assertThat(ok.snapshot().exits()).hasSize(1);
        assertThat(ok.snapshot().objects()).hasSize(1);

        assertThat(result.event().event()).isInstanceOf(WorldEvent.RoomCreated.class);
    }

    @Test void enter_room() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().entities()).anyMatch(e -> e.name().equals("Alice"));
        assertThat(result.event().event()).isInstanceOf(WorldEvent.EntityEntered.class);
    }

    @Test void leave_room() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LeaveRoom("player-1", "Alice", "south", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().entities()).noneMatch(e -> e.name().equals("Alice"));
        assertThat(result.event().event()).isInstanceOf(WorldEvent.EntityLeft.class);
    }

    @Test void say_in_room() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SayInRoom("player-1", "Alice", "Hello world!", ref));

        // Said is transient — no persisted event. The reply is Narrated, not
        // Ok(snapshot): the Said event is published to subscribers; the reply
        // doesn't need to (and shouldn't) carry a snapshot that would force a
        // client-side room redraw on top of the speech line.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test void take_object() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Treasure Room", "Full of treasure.", "test",
                List.of(),
                List.of(new RoomObject("key", "Golden Key", "An ornate key.", true)),
                ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("player-1", "Golden Key", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var ok = (RoomResponse.ObjectTakenOk) result.reply();
        assertThat(ok.takenObject().name()).isEqualTo("Golden Key");
        assertThat(ok.snapshot().objects()).noneMatch(o -> o.name().equals("Golden Key"));
    }

    @Test void take_untakeable_rejected() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(),
                List.of(new RoomObject("crystal", "Crystal", "Bolted down.", false)),
                ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("player-1", "Crystal", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
    }

    @Test void drop_object() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("player-1", "key", "Golden Key",
                "An ornate key.", true, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().objects()).anyMatch(o -> o.name().equals("Golden Key"));
    }

    @Test void drop_nontakeable_rejected_at_room_boundary() {
        // Why: client-side findTakeableByName is the primary guard, but
        // a malformed/older caller could still push a pinned scripted
        // furnishing into a room — which then corrupts subsequent takes.
        // The room must refuse.
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("player-1", "compass", "Compass",
                "A scripted Study furnishing.", false, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) result.reply()).code()).isEqualTo("not_takeable");
        // Room must not have absorbed the object.
        var look = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        assertThat(((RoomResponse.Ok) look.reply()).snapshot().objects())
            .noneMatch(o -> o.name().equalsIgnoreCase("compass"));
    }

    @Test void take_prefers_takeable_when_names_collide() {
        // Why: if a pre-fix drop corrupted the room state with a pinned
        // same-name object, the later `take compass` must still resolve
        // to the room's actual takeable compass, not reject with
        // "not_takeable" because resolveObject picked the wrong one.
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Docks", "By the sea.", "foundation",
                List.of(),
                List.of(
                    new RoomObject("leaked-compass", "Compass", "Pinned, leaked here.", false),
                    new RoomObject("docks-compass", "compass", "A brass compass.", true)),
                ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("player-1", "compass", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var ok = (RoomResponse.ObjectTakenOk) result.reply();
        assertThat(ok.takenObject().id()).isEqualTo("docks-compass");
        assertThat(ok.takenObject().takeable()).isTrue();
    }

    @Test void look_room() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Nexus", "A hub.", "foundation",
                List.of(new Exit("east", "terminal", "Terminal")),
                List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().name()).isEqualTo("Nexus");
        assertThat(ok.snapshot().exits()).hasSize(1);
    }

    @Test void add_exit() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.AddExit("west", "nexus", "The Nexus", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().exits()).anyMatch(e ->
            e.direction().equals("west") && e.targetRoom().equals("nexus"));
    }

    @Test void update_hints() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var hints = List.of(
            new Hint("Talk to Wyrd", "greet", "say:Hello"),
            new Hint("Look around", "explore", "look"));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UpdateHints(hints, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().hints()).hasSize(2);
        assertThat(ok.snapshot().hints().getFirst().label()).isEqualTo("Talk to Wyrd");
    }

    @Test void state_survives_restart() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Persistent Room", "Survives restart.", "test",
                List.of(new Exit("north", "hall", "The Hall")),
                List.of(new RoomObject("lamp", "Lamp", "A lamp.", true)),
                ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "south", ref));

        // Restart the actor (simulates crash + recovery from event journal)
        behaviorTestKit.restart();

        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));

        assertThat(lookResult.reply()).isInstanceOf(RoomResponse.Ok.class);
        var snapshot = ((RoomResponse.Ok) lookResult.reply()).snapshot();
        assertThat(snapshot.name()).isEqualTo("Persistent Room");
        assertThat(snapshot.exits()).hasSize(1);
        assertThat(snapshot.objects()).hasSize(1);
        assertThat(snapshot.entities()).anyMatch(e -> e.name().equals("Alice"));
    }

    @Test void whisper_is_transient() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-2", "Bob", "player", "south", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.WhisperInRoom("player-1", "Alice", "player-2", "Psst!", ref));

        // Whisper is transient — no persisted event, only reply
        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test void whisper_to_absent_entity_rejected() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.WhisperInRoom("player-1", "Alice", "ghost", "Hey!", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) result.reply();
        assertThat(rejected.code()).isEqualTo("not_found");
    }

    @Test void whisper_survives_restart() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-2", "Bob", "player", "south", ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.WhisperInRoom("player-1", "Alice", "player-2", "Secret!", ref));

        // Restart should recover fine (Whispered doesn't change state)
        behaviorTestKit.restart();
        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        assertThat(lookResult.reply()).isInstanceOf(RoomResponse.Ok.class);
    }

    @Test void use_object_partial_name_match() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Library", "A library.", "test",
                List.of(),
                List.of(new RoomObject("library-catalog", "card catalog",
                    "A brass card catalog.", false)),
                ref));

        // "catalog" should match "card catalog" via partial match.
        // UseObject returns Narrated (not Ok) — the ObjectUsed event is
        // broadcast to subscribers; the response itself doesn't carry a
        // snapshot (use is transient, no state change).
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "catalog", null, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test void take_object_partial_name_match() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(),
                List.of(new RoomObject("bridge-spyglass", "spyglass",
                    "A brass spyglass.", true)),
                ref));

        // Full name should still work
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("player-1", "spyglass", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);
    }

    @Test void use_object_exact_match_preferred_over_partial() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(),
                List.of(
                    new RoomObject("obj-key", "key", "A simple key.", false),
                    new RoomObject("obj-golden-key", "golden key", "A golden key.", false)),
                ref));

        // "key" should match "key" exactly, not "golden key"
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "key", null, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test void use_object_query_contains_object_name() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Library", "A library.", "test",
                List.of(),
                List.of(new RoomObject("library-catalog", "card catalog",
                    "A brass card catalog.", false)),
                ref));

        // "the card catalog over there" contains "card catalog" — should match
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UseObject("player-1", "the card catalog over there", null, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test void create_already_existing_room_idempotent() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "First.", "test",
                List.of(), List.of(), ref));

        // Second create is idempotent — returns Ok with existing state, no new event
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room2", "Second.", "test",
                List.of(), List.of(), ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        assertThat(ok.snapshot().name()).isEqualTo("Room"); // Original name preserved
        assertThat(result.hasNoEvents()).isTrue();
    }

    // ── Entity Description Tests ────────────────────────────────────

    @Test void enter_room_with_description() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-1", "Ember", "agent",
                "A newly aware entity, curious and drawn to building things",
                "materialization", "en", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        var entity = ok.snapshot().entities().stream()
            .filter(e -> e.name().equals("Ember")).findFirst().orElseThrow();
        assertThat(entity.description()).isEqualTo(
            "A newly aware entity, curious and drawn to building things");
    }

    @Test void enter_room_without_description_backward_compat() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        // 5-arg backward-compatible constructor (no description)
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        var entity = ok.snapshot().entities().stream()
            .filter(e -> e.name().equals("Alice")).findFirst().orElseThrow();
        assertThat(entity.description()).isEmpty();
    }

    @Test void update_entity_description() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-1", "Wyrd", "agent", "north", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UpdateEntityDescription("agent-1",
                "A luminous figure that shimmers at the edge of perception", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) result.reply();
        var entity = ok.snapshot().entities().stream()
            .filter(e -> e.name().equals("Wyrd")).findFirst().orElseThrow();
        assertThat(entity.description()).isEqualTo(
            "A luminous figure that shimmers at the edge of perception");
    }

    // ── Copy-on-Take Tests ───────────────────────────────────────

    @Test void copy_on_take_room_retains_object_after_take_and_redrop() {
        // Create a room with a takeable object
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Treasure Room", "A glittering room.", "test",
                List.of(),
                List.of(new RoomObject("scroll-1", "Ancient Scroll",
                    "A weathered parchment.", true)),
                ref));

        // Entity enters the room
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-1", "Ember", "agent", "north", ref));

        // Take the object
        var takeResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("agent-1", "Ancient Scroll", ref));

        assertThat(takeResult.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var taken = (RoomResponse.ObjectTakenOk) takeResult.reply();
        assertThat(taken.takenObject().name()).isEqualTo("Ancient Scroll");
        // Object is gone from the room after take
        assertThat(taken.snapshot().objects()).noneMatch(o -> o.name().equals("Ancient Scroll"));

        // CompanionActor would now drop a copy back with "item-cot-" prefix
        var dropCopyResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("agent-1",
                "item-cot-scroll-1", "Ancient Scroll",
                "A weathered parchment.", true, ref));

        assertThat(dropCopyResult.reply()).isInstanceOf(RoomResponse.Ok.class);
        var snapshot = ((RoomResponse.Ok) dropCopyResult.reply()).snapshot();
        // Room should have the copy-on-take object
        assertThat(snapshot.objects()).anyMatch(o ->
            o.name().equals("Ancient Scroll") && o.id().equals("item-cot-scroll-1"));
    }

    @Test void copy_on_take_look_shows_object_present() {
        // Full cycle: create, enter, take, drop-copy, then look
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Archive", "Rows of scrolls.", "test",
                List.of(),
                List.of(new RoomObject("tome-1", "Arcane Tome",
                    "A heavy leather-bound tome.", true)),
                ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-1", "Ember", "agent", "east", ref));

        // Take
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("agent-1", "Arcane Tome", ref));

        // Drop copy (simulating CompanionActor copy-on-take)
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("agent-1",
                "item-cot-tome-1", "Arcane Tome",
                "A heavy leather-bound tome.", true, ref));

        // Look at the room — the tome should still be visible
        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("agent-1", ref));

        assertThat(lookResult.reply()).isInstanceOf(RoomResponse.Ok.class);
        var snapshot = ((RoomResponse.Ok) lookResult.reply()).snapshot();
        assertThat(snapshot.objects()).anyMatch(o -> o.name().equals("Arcane Tome"));
        assertThat(snapshot.objects()).hasSize(1);
    }

    @Test void copy_on_take_multiple_agents_can_each_take() {
        // Two agents can each take a copy-on-take object
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Armory", "Weapons line the walls.", "test",
                List.of(),
                List.of(new RoomObject("sword-1", "Training Sword",
                    "A practice blade.", true)),
                ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-1", "Ember", "agent", "north", ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("agent-2", "Wyrd", "agent", "south", ref));

        // Agent 1 takes
        var take1 = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("agent-1", "Training Sword", ref));
        assertThat(take1.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);

        // CompanionActor drops copy back
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("agent-1",
                "item-cot-sword-1-a1", "Training Sword",
                "A practice blade.", true, ref));

        // Agent 2 takes the copy
        var take2 = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.TakeObject("agent-2", "Training Sword", ref));
        assertThat(take2.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);

        // CompanionActor drops another copy
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.DropObject("agent-2",
                "item-cot-sword-1-a2", "Training Sword",
                "A practice blade.", true, ref));

        // Look — sword is still there
        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("agent-1", ref));
        var snapshot = ((RoomResponse.Ok) lookResult.reply()).snapshot();
        assertThat(snapshot.objects()).anyMatch(o -> o.name().equals("Training Sword"));
    }

    @Test void update_entity_description_not_in_room_rejected() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Room", "A room.", "test",
                List.of(), List.of(), ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.UpdateEntityDescription("ghost-1",
                "Should not work", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
    }

    // --- Hint-select dispatch ---------------------------------------------
    // Why these matter: the action-menu path in production goes
    // VirtualSession/SSH/Telnet/WS → SelectHint → RoomActor.onSelectHint,
    // bypassing the typed `examine X` command path. A regression in the
    // examine/use branches will silently slip past the conformance suites
    // unless we lock the response shape here. (May 2026: examine was
    // returning Ok(snapshot), which made the session redraw the whole room
    // on top of the narration line.)

    @Test void examine_hint_returns_narrated_without_room_redraw() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Study", "A worn leather chair.", "test",
                List.of(),
                List.of(new RoomObject("scroll", "invitation scroll",
                    "A rolled parchment hanging from a brass peg.", false)),
                ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var look = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        var hints = ((RoomResponse.Ok) look.reply()).snapshot().hints();
        int examineIdx = -1;
        for (int i = 0; i < hints.size(); i++) {
            if (hints.get(i).action() != null
                    && hints.get(i).action().startsWith("examine:")) {
                examineIdx = i;
                break;
            }
        }
        assertThat(examineIdx).as("examine hint must exist for non-takeable object").isGreaterThanOrEqualTo(0);

        final int idx = examineIdx;
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SelectHint("player-1", idx, "en", ref));

        // Locks in the fix: examine must NOT return Ok(snapshot) — that would
        // make ClientSessionActor push S2CMessage.RoomState and redraw the
        // room over the description line. It must use Narrated.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.reply()).isNotInstanceOf(RoomResponse.Ok.class);

        // The Said narration carrying the description must still be persisted
        // (notifySubscribers piggybacks on the persisted event publish), so
        // the client receives the narration as a Prose message.
        assertThat(result.event().event()).isInstanceOf(WorldEvent.Said.class);
        var said = (WorldEvent.Said) result.event().event();
        assertThat(said.entityName()).isEqualTo("narrator");
        assertThat(said.text()).contains("rolled parchment");
    }

    @Test void out_of_range_hint_select_returns_rejected() {
        // The action-menu has historically off-by-oned at the transport layer
        // (batch L #349). The room must refuse out-of-range indices cleanly
        // rather than NPE or default into a stray verb.
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Empty", "Bare walls.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SelectHint("player-1", 999, "en", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) result.reply()).code()).isEqualTo("invalid_hint");
    }

    @Test void take_hint_still_returns_object_taken_ok() {
        // Sibling case to the examine fix: take must keep returning
        // ObjectTakenOk so the session redraws and inventory updates.
        // (If a future refactor accidentally swaps take to Narrated, the
        // inventory line stops updating client-side.)
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Vault", "Cool stone.", "test",
                List.of(),
                List.of(new RoomObject("coin", "gold coin", "A heavy disc.", true)),
                ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", ref));

        var look = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        var hints = ((RoomResponse.Ok) look.reply()).snapshot().hints();
        int takeIdx = -1;
        for (int i = 0; i < hints.size(); i++) {
            if (hints.get(i).action() != null
                    && hints.get(i).action().startsWith("take:")) {
                takeIdx = i;
                break;
            }
        }
        assertThat(takeIdx).as("take hint must exist for takeable object").isGreaterThanOrEqualTo(0);

        final int idx = takeIdx;
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SelectHint("player-1", idx, "en", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.ObjectTakenOk.class);
    }
}
