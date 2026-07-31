package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Posture;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-07-11 follow-through — the four commands the wiring audit found
 * falling to "unhandled" in {@link RoomProxy} (sit/stand timed out, renames
 * vanished, scripted-item world effects silently dropped in remote rooms):
 * SetPosture, ClearPosture, UpdateEntityName, ItemBridgeAction.
 *
 * <p>Mirrors {@link RoomProxyTest}'s mock-transport harness: each command
 * must (a) serialize through the proxy's transport function, and (b) for the
 * three reply-bearing commands, route the primary's response back to
 * {@code replyTo}; ItemBridgeAction is fire-and-forget — it must reach the
 * transport and must never throw, even when the transport fails.</p>
 */
class RoomProxyAuditCommandForwardTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static Posture sat() {
        return new Posture("sat", "study-chair",
            "settles into the leather chair", Instant.now(), null);
    }

    @Test
    void set_posture_forwards_and_routes_reply() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                sent.add(cmdJson);
                return CompletableFuture.completedFuture("{\"type\":\"narrated\"}");
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.SetPosture("companion-wyrd", sat(), probe.ref()));

        // Reply from the primary is routed back to the caller (the audit bug:
        // this command fell to unhandled, so sit/stand TIMED OUT here).
        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Narrated.class);

        assertThat(sent).hasSize(1);
        var node = MAPPER.readTree(sent.get(0));
        // NOTE (known gap, found while writing this test): SetPosture and
        // ClearPosture are NOT registered in RoomCommand's @JsonSubTypes, so
        // Jackson falls back to the default id ("RoomCommand$SetPosture") —
        // and RoomCommandDispatcher (the primary-side receiver) has no case
        // for ANY of the four audit commands, so a real cross-node primary
        // still rejects them as unknown_command. This test pins the proxy
        // side only (serialize + reply routing); it deliberately does not
        // pin the unstable default type id.
        assertThat(node.path("type").asText()).isNotEmpty();
        assertThat(node.path("entityId").asText()).isEqualTo("companion-wyrd");
        assertThat(node.path("posture").path("verb").asText()).isEqualTo("sat");
        assertThat(node.path("posture").path("atObject").asText()).isEqualTo("study-chair");
    }

    @Test
    void clear_posture_forwards_and_routes_reply() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                sent.add(cmdJson);
                return CompletableFuture.completedFuture("{\"type\":\"narrated\"}");
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.ClearPosture("companion-wyrd", probe.ref()));

        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Narrated.class);

        assertThat(sent).hasSize(1);
        var node = MAPPER.readTree(sent.get(0));
        assertThat(node.path("type").asText()).isNotEmpty();
        assertThat(node.path("entityId").asText()).isEqualTo("companion-wyrd");
    }

    @Test
    void update_entity_name_forwards_with_registered_wire_name_and_routes_reply() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                sent.add(cmdJson);
                return CompletableFuture.completedFuture("{\"type\":\"narrated\"}");
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.UpdateEntityName("companion-wyrd", "Wyrd the Renamed",
            probe.ref()));

        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Narrated.class);

        assertThat(sent).hasSize(1);
        var node = MAPPER.readTree(sent.get(0));
        // Registered in RoomCommand's @JsonSubTypes — pin the wire name.
        assertThat(node.path("type").asText()).isEqualTo("update_entity_name");
        assertThat(node.path("entityId").asText()).isEqualTo("companion-wyrd");
        assertThat(node.path("newName").asText()).isEqualTo("Wyrd the Renamed");
    }

    @Test
    void reply_bearing_audit_commands_get_rejected_on_transport_failure() {
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.failedFuture(
                new RuntimeException("Connection lost"))));

        var probe = testKit.<RoomResponse>createTestProbe();

        proxy.tell(new RoomCommand.SetPosture("e1", sat(), probe.ref()));
        var r1 = probe.receiveMessage();
        assertThat(r1).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) r1).code()).isEqualTo("unavailable");

        proxy.tell(new RoomCommand.ClearPosture("e1", probe.ref()));
        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Rejected.class);

        proxy.tell(new RoomCommand.UpdateEntityName("e1", "New Name", probe.ref()));
        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Rejected.class);
    }

    @Test
    void item_bridge_action_is_fire_and_forget_through_transport() throws Exception {
        var sent = new CopyOnWriteArrayList<String>();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                sent.add(cmdJson);
                return CompletableFuture.completedFuture("{\"type\":\"narrated\"}");
            }));

        proxy.tell(new RoomCommand.ItemBridgeAction("companion-wyrd",
            new RoomCommand.ItemBridgeSubAction.Emit("glow",
                Map.of("intensity", "soft"))));

        // No replyTo — verify arrival at the transport instead.
        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (sent.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(sent).hasSize(1);
        var node = MAPPER.readTree(sent.get(0));
        assertThat(node.path("type").asText()).isEqualTo("item_bridge");
        assertThat(node.path("callerEntityId").asText()).isEqualTo("companion-wyrd");
        assertThat(node.path("action").path("kind").asText()).isEqualTo("emit");
        assertThat(node.path("action").path("eventType").asText()).isEqualTo("glow");
    }

    @Test
    void item_bridge_action_transport_failure_does_not_kill_the_proxy() {
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.failedFuture(
                new RuntimeException("Connection lost"))));

        // Fire-and-forget failure must be swallowed (logged only)...
        proxy.tell(new RoomCommand.ItemBridgeAction("companion-wyrd",
            new RoomCommand.ItemBridgeSubAction.Narrate("the lamp flickers")));

        // ...and the proxy must still be alive and serving afterwards.
        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.LookRoom("player-1", "en", probe.ref()));
        var response = probe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) response).code()).isEqualTo("unavailable");
    }

    @Test
    void every_audit_command_reaches_the_transport_exactly_once() {
        var sent = new CopyOnWriteArrayList<String>();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                sent.add(cmdJson);
                return CompletableFuture.completedFuture("{\"type\":\"narrated\"}");
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.SetPosture("e1", sat(), probe.ref()));
        probe.receiveMessage();
        proxy.tell(new RoomCommand.ClearPosture("e1", probe.ref()));
        probe.receiveMessage();
        proxy.tell(new RoomCommand.UpdateEntityName("e1", "N", probe.ref()));
        probe.receiveMessage();
        proxy.tell(new RoomCommand.ItemBridgeAction("e1",
            new RoomCommand.ItemBridgeSubAction.Narrate("hum")));

        var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (sent.size() < 4 && System.nanoTime() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        assertThat(sent).hasSize(4);
        assertThat(List.copyOf(sent)).allSatisfy(json ->
            assertThat(json).contains("\"type\""));
    }
}
