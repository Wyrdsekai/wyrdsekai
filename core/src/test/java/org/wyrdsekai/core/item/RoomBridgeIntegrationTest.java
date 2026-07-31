package org.wyrdsekai.core.item;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomActor;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * proves that the per-call
 * {@link ItemWorldApiProviderImpl.RoomBridge} routes scripted-item writes
 * (room.emit / room.narrate / room.add_object / etc.) to the correct
 * {@link RoomActor}, via the {@link RoomCommand.ItemBridgeAction} fire-and-
 * forget protocol — and does NOT silently fall back to {@code agent.speak}.
 */
@Tag("integration")
class RoomBridgeIntegrationTest {

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

    @AfterAll static void tearDownAll() {
        testKit.shutdownTestKit();
    }

    /**
     * Build a {@link ItemWorldApiProviderImpl.RoomBridge} that targets a
     * concrete {@link ActorRef}.
     */
    private static ItemWorldApiProviderImpl.RoomBridge bridgeFor(
            ActorRef<RoomCommand> roomRef, String callerEntityId) {
        return new ItemWorldApiProviderImpl.RoomBridge() {
            @Override
            public void emit(String eventType, Map<String, Object> data) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.Emit(eventType, data)));
            }
            @Override
            public void narrate(String text) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.Narrate(text)));
            }
            @Override
            public void addObject(String id, String name, String description, boolean takeable) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.AddObject(
                        id, name, description, takeable)));
            }
            @Override
            public void removeObject(String id) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.RemoveObject(id)));
            }
            @Override
            public void setProperty(String key, Object value) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.SetProperty(
                        key, value == null ? "" : String.valueOf(value))));
            }
            @Override
            public void updateDescription(String text) {
                roomRef.tell(new RoomCommand.ItemBridgeAction(callerEntityId,
                    new RoomCommand.ItemBridgeSubAction.UpdateDescription(text)));
            }
        };
    }

    private ActorRef<RoomCommand> spawnInitializedRoom(String name) {
        var roomRef = testKit.spawn(
            RoomActor.create(name),
            name + "-" + System.nanoTime());
        var probe = testKit.<RoomResponse>createTestProbe();
        roomRef.tell(new RoomCommand.CreateRoom(name, "test", "test",
            List.of(), List.of(), probe.getRef()));
        probe.receiveMessage();
        return roomRef;
    }

    @Test
    void room_emit_persists_subscriber_event_via_bridge() {
        var roomRef = spawnInitializedRoom("bridge-real-room");
        var notifications = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifications.getRef(),
            VisibilityLevel.PRIVILEGED));

        var bridge = bridgeFor(roomRef, "scripter-1");
        bridge.emit("scrying.frame", Map.of("phase", "flicker", "x", 7));

        var notif = notifications.expectMessageClass(RoomNotification.class);
        assertThat(notif.event()).isInstanceOf(WorldEvent.ScriptTriggered.class);
        var triggered = (WorldEvent.ScriptTriggered) notif.event();
        assertEquals("scrying.frame", triggered.scriptName());
        assertEquals("scripter-1", triggered.trigger());
        assertEquals("flicker", triggered.context().get("phase"));
    }

    @Test
    void room_narrate_emits_said_event_with_caller_attribution() {
        var roomRef = spawnInitializedRoom("narrate-room");
        var notifications = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifications.getRef()));

        var bridge = bridgeFor(roomRef, "scripter-2");
        bridge.narrate("the wind rises");

        var notif = notifications.expectMessageClass(RoomNotification.class);
        assertThat(notif.event()).isInstanceOf(WorldEvent.Said.class);
        var said = (WorldEvent.Said) notif.event();
        assertEquals("scripter-2", said.entityId());
        assertEquals("narrator", said.entityName());
        assertEquals("the wind rises", said.text());
    }

    /**
     * Drive the actor via spawn + tell. Polls the snapshot until the bridge
     * action's effect is visible, with a generous timeout.
     */
    private static RoomSnapshot snapshotAfter(
            ActorRef<RoomCommand> roomRef,
            Predicate<RoomSnapshot> until) {
        long deadline = System.currentTimeMillis() + 3000;
        RoomSnapshot last = null;
        while (System.currentTimeMillis() < deadline) {
            var probe = testKit.<RoomSnapshot>createTestProbe();
            roomRef.tell(new RoomCommand.GetSnapshot(probe.getRef()));
            last = probe.expectMessageClass(
                RoomSnapshot.class);
            if (until.test(last)) return last;
            try { Thread.sleep(50); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        return last;
    }

    @Test
    void room_add_object_persists_and_appears_in_state() {
        var roomRef = spawnInitializedRoom("add-obj-room");
        roomRef.tell(new RoomCommand.ItemBridgeAction(
            "scripter-3",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                "talisman", "talisman", "A small disc of bronze.", true)));
        var snap = snapshotAfter(roomRef, s ->
            s.objects().stream().anyMatch(o -> "talisman".equals(o.id())));
        assertTrue(snap.objects().stream().anyMatch(o -> "talisman".equals(o.id())),
            "object should be in room state after AddObject");
    }

    @Test
    void room_set_property_emits_property_changed() {
        var roomRef = spawnInitializedRoom("setprop-room");
        var notifications = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifications.getRef(),
            VisibilityLevel.PRIVILEGED));
        roomRef.tell(new RoomCommand.ItemBridgeAction(
            "scripter-4",
            new RoomCommand.ItemBridgeSubAction.SetProperty(
                "lantern.dimness", "0.3")));
        long deadline = System.currentTimeMillis() + 3000;
        boolean saw = false;
        while (System.currentTimeMillis() < deadline) {
            var n = notifications.receiveMessage();
            if (n.event() instanceof WorldEvent.PropertyChanged pc
                    && "lantern.dimness".equals(pc.key())
                    && "0.3".equals(pc.newValue())) {
                saw = true; break;
            }
        }
        assertTrue(saw, "PropertyChanged notification should fire for set_property");
    }

    @Test
    void room_update_description_persists() {
        var roomRef = spawnInitializedRoom("desc-room");
        var notifications = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifications.getRef(),
            VisibilityLevel.PRIVILEGED));
        roomRef.tell(new RoomCommand.ItemBridgeAction(
            "scripter-5",
            new RoomCommand.ItemBridgeSubAction.UpdateDescription(
                "The room is suddenly bathed in violet light.")));
        long deadline = System.currentTimeMillis() + 3000;
        boolean saw = false;
        while (System.currentTimeMillis() < deadline) {
            var n = notifications.receiveMessage();
            if (n.event() instanceof WorldEvent.DescriptionChanged dc
                    && "The room is suddenly bathed in violet light."
                       .equals(dc.newDescription())) {
                saw = true; break;
            }
        }
        assertTrue(saw, "DescriptionChanged should fire for update_description");
    }

    @Test
    void room_remove_object_clears_from_state() {
        var roomRef = spawnInitializedRoom("remove-obj-room");
        roomRef.tell(new RoomCommand.ItemBridgeAction(
            "scripter-6",
            new RoomCommand.ItemBridgeSubAction.AddObject(
                "trinket", "trinket", "A bauble.", true)));
        snapshotAfter(roomRef, s ->
            s.objects().stream().anyMatch(o -> "trinket".equals(o.id())));
        roomRef.tell(new RoomCommand.ItemBridgeAction(
            "scripter-6",
            new RoomCommand.ItemBridgeSubAction.RemoveObject("trinket")));
        var snap = snapshotAfter(roomRef, s ->
            s.objects().stream().noneMatch(o -> "trinket".equals(o.id())));
        assertTrue(snap.objects().stream().noneMatch(o -> "trinket".equals(o.id())),
            "object should be gone from room state after RemoveObject");
    }

    @Test
    void script_with_wired_bridge_does_not_fall_back_to_speak() {
        // Drive the bridge through a real GraalJS execution to prove the
        // wired path is exercised end-to-end (no fallback to agent.speak).
        var roomRef = spawnInitializedRoom("script-room");
        var notifications = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifications.getRef(),
            VisibilityLevel.PRIVILEGED));

        var spoke = new AtomicReference<String>();
        var executor = new ItemScriptExecutor();
        var provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:scripter", "Scripter",
            spoke::set,                  // would capture any silent speak fallback
            content -> {},
            (target, msg) -> {},
            null, executor);

        provider.setRoomBridge(bridgeFor(roomRef, "did:wyrd:scripter"));
        try {
            var script = """
                function invoke(p) {
                  var a = world.room.emit('lantern.flicker', { brightness: 0.4 });
                  var b = world.room.narrate('the lantern dips');
                  return { a: a, b: b };
                }
                """;
            var caps = ItemCapabilitySet.of(List.of("room.emit", "room.narrate"));
            var result = executor.execute("test_lantern", script, Map.of(), provider, caps);

            @SuppressWarnings("unchecked")
            var a = (Map<String, Object>) result.get("a");
            @SuppressWarnings("unchecked")
            var b = (Map<String, Object>) result.get("b");
            assertEquals(true, a.get("ok"));
            assertEquals(true, a.get("queued"));
            assertEquals(true, b.get("ok"));
            assertEquals(true, b.get("queued"));

            // The room receives both events
            var n1 = notifications.expectMessageClass(RoomNotification.class);
            var n2 = notifications.expectMessageClass(RoomNotification.class);
            assertThat(List.of(n1.event().getClass(), n2.event().getClass()))
                .containsExactlyInAnyOrder(
                    WorldEvent.ScriptTriggered.class,
                    WorldEvent.Said.class);

            // CRITICAL: the speak callback was never invoked.
            assertThat(spoke.get())
                .as("speakCallback must never fire when bridge is wired")
                .isNull();
        } finally {
            provider.setRoomBridge(null);
            executor.close();
        }
    }
}
