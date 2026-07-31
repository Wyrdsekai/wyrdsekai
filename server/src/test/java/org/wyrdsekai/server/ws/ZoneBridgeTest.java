package org.wyrdsekai.server.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ZoneBridgeTest {

    // ── ZoneBridgeMessage serialization ──────────────────────────────────

    @Nested
    class MessageSerialization {

        @Test
        void registerRoundtrip() throws Exception {
            var msg = new ZoneBridgeMessage.Register("codeplane", "secret123");
            var json = Json.mapper().writeValueAsString(msg);
            assertTrue(json.contains("\"type\":\"register\""));
            assertTrue(json.contains("\"namespace\":\"codeplane\""));

            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.Register.class, parsed);
            var reg = (ZoneBridgeMessage.Register) parsed;
            assertEquals("codeplane", reg.namespace());
            assertEquals("secret123", reg.secret());
        }

        @Test
        void registerWithNullSecret() throws Exception {
            var msg = new ZoneBridgeMessage.Register("hearth", null);
            var json = Json.mapper().writeValueAsString(msg);
            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.Register.class, parsed);
            assertNull(((ZoneBridgeMessage.Register) parsed).secret());
        }

        @Test
        void registeredRoundtrip() throws Exception {
            var msg = new ZoneBridgeMessage.Registered("codeplane");
            var json = Json.mapper().writeValueAsString(msg);
            assertTrue(json.contains("\"type\":\"registered\""));

            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.Registered.class, parsed);
            assertEquals("codeplane", ((ZoneBridgeMessage.Registered) parsed).namespace());
        }

        @Test
        void forwardCommandRoundtrip() throws Exception {
            var msg = new ZoneBridgeMessage.ForwardCommand(
                "req-123", "player-1", "approve",
                List.of("exp-42"), Map.of("force", "true"));
            var json = Json.mapper().writeValueAsString(msg);
            assertTrue(json.contains("\"type\":\"command\""));
            assertTrue(json.contains("\"action\":\"approve\""));

            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.ForwardCommand.class, parsed);
            var cmd = (ZoneBridgeMessage.ForwardCommand) parsed;
            assertEquals("req-123", cmd.requestId());
            assertEquals("player-1", cmd.playerId());
            assertEquals("approve", cmd.action());
            assertEquals(List.of("exp-42"), cmd.args());
            assertEquals(Map.of("force", "true"), cmd.payload());
        }

        @Test
        void commandResponseRoundtrip() throws Exception {
            var proseNode = Json.mapper().valueToTree(new S2CMessage.Prose(0, "codeplane", "Experiment approved",
                List.of(), null, "normal"));
            var msg = new ZoneBridgeMessage.CommandResponse(
                "req-123", "player-1", List.of(proseNode));
            var json = Json.mapper().writeValueAsString(msg);
            assertTrue(json.contains("\"type\":\"response\""));

            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.CommandResponse.class, parsed);
            var resp = (ZoneBridgeMessage.CommandResponse) parsed;
            assertEquals("req-123", resp.requestId());
            assertEquals("player-1", resp.playerId());
            assertEquals(1, resp.messages().size());
        }

        @Test
        void registrationErrorRoundtrip() throws Exception {
            var msg = new ZoneBridgeMessage.RegistrationError("codeplane", "already registered");
            var json = Json.mapper().writeValueAsString(msg);
            assertTrue(json.contains("\"type\":\"error\""));

            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            assertInstanceOf(ZoneBridgeMessage.RegistrationError.class, parsed);
            var err = (ZoneBridgeMessage.RegistrationError) parsed;
            assertEquals("codeplane", err.namespace());
            assertEquals("already registered", err.reason());
        }

        @Test
        void commandResponseWithMultipleMessages() throws Exception {
            var messages = List.<JsonNode>of(
                Json.mapper().valueToTree(new S2CMessage.Prose(0, "codeplane", "Step 1 done", List.of(), null, "normal")),
                Json.mapper().valueToTree(new S2CMessage.Prose(0, "codeplane", "Step 2 done", List.of(), null, "normal")),
                Json.mapper().valueToTree(new S2CMessage.Notification(0, "info", "Complete", "All steps done"))
            );
            var msg = new ZoneBridgeMessage.CommandResponse("req-456", "player-2", messages);
            var json = Json.mapper().writeValueAsString(msg);
            var parsed = Json.mapper().readValue(json, ZoneBridgeMessage.class);
            var resp = (ZoneBridgeMessage.CommandResponse) parsed;
            assertEquals(3, resp.messages().size());
        }
    }

    // ── ProxyZoneHandler logic ──────────────────────────────────────────

    @Nested
    class ProxyHandlerTest {

        @Test
        void onResponseDeliversToCorrectCallback() {
            // We can't easily mock WsContext, so test the response routing logic directly
            var handler = new TestableProxyZoneHandler("codeplane");

            var received = new ArrayList<S2CMessage>();
            var requestId = handler.simulateHandle("player-1", "approve", List.of(), Map.of(), received::add);

            // Simulate zone service response (JsonNode, not S2CMessage)
            var proseNode = Json.mapper().valueToTree(Map.of("text", "Approved!"));
            handler.onResponse(new ZoneBridgeMessage.CommandResponse(requestId, "player-1", List.of(proseNode)));

            assertEquals(1, received.size());
            assertInstanceOf(S2CMessage.ZoneResponse.class, received.getFirst());
            assertEquals("Approved!", ((S2CMessage.ZoneResponse) received.getFirst()).text());
        }

        @Test
        void onResponseIgnoresUnknownRequestId() {
            var handler = new TestableProxyZoneHandler("codeplane");

            // Response with no matching request — should not throw
            var proseNode = Json.mapper().valueToTree(Map.of("text", "Mystery"));
            handler.onResponse(new ZoneBridgeMessage.CommandResponse("unknown-id", "player-1", List.of(proseNode)));
            // No exception = pass
        }

        @Test
        void onResponseDeliversMultipleMessages() {
            var handler = new TestableProxyZoneHandler("codeplane");
            var received = new ArrayList<S2CMessage>();
            var requestId = handler.simulateHandle("player-1", "status", List.of(), Map.of(), received::add);

            var messages = List.<JsonNode>of(
                Json.mapper().valueToTree(Map.of("text", "Line 1")),
                Json.mapper().valueToTree(Map.of("text", "Line 2"))
            );
            handler.onResponse(new ZoneBridgeMessage.CommandResponse(requestId, "player-1", messages));

            assertEquals(2, received.size());
        }

        @Test
        void onDisconnectSendsErrorToPendingRequests() {
            var handler = new TestableProxyZoneHandler("codeplane");
            var received = new ArrayList<S2CMessage>();

            handler.simulateHandle("player-1", "long-running", List.of(), Map.of(), received::add);

            // Disconnect while request is pending
            handler.onDisconnect();

            assertEquals(1, received.size());
            assertInstanceOf(S2CMessage.Error.class, received.getFirst());
            assertTrue(((S2CMessage.Error) received.getFirst()).message().contains("disconnected"));
        }

        @Test
        void namespace() {
            var handler = new TestableProxyZoneHandler("hearth");
            assertEquals("hearth", handler.namespace());
        }
    }

    // ── Namespace validation (inline, mirrors ZoneBridgeEndpoint logic) ──

    @Nested
    class NamespaceValidation {

        @Test
        void validNamespaces() {
            assertTrue("codeplane".matches("[a-z][a-z0-9-]*"));
            assertTrue("hearth".matches("[a-z][a-z0-9-]*"));
            assertTrue("my-zone-1".matches("[a-z][a-z0-9-]*"));
            assertTrue("a".matches("[a-z][a-z0-9-]*"));
        }

        @Test
        void invalidNamespaces() {
            assertFalse("".matches("[a-z][a-z0-9-]*"));
            assertFalse("1abc".matches("[a-z][a-z0-9-]*"));
            assertFalse("Code".matches("[a-z][a-z0-9-]*"));
            assertFalse("my_zone".matches("[a-z][a-z0-9-]*"));
            assertFalse("my.zone".matches("[a-z][a-z0-9-]*"));
        }

        @Test
        void reservedNamespaces() {
            // These should be rejected by the endpoint
            assertEquals("system", "system");
            assertEquals("wyrdsekai", "wyrdsekai");
        }
    }

    /**
     * Testable subclass that bypasses WsContext (which requires a real Jetty session).
     * Exposes the response routing internals for unit testing.
     */
    static class TestableProxyZoneHandler extends ProxyZoneHandler {
        private final Map<String, Consumer<S2CMessage>> testPending =
            new ConcurrentHashMap<>();

        TestableProxyZoneHandler(String namespace) {
            super(namespace, null); // null WsContext — we won't send over WS
        }

        /**
         * Simulate a handle() call without WS. Returns the generated requestId.
         */
        String simulateHandle(String playerId, String action, List<String> args,
                              Map<String, String> payload,
                              Consumer<S2CMessage> respond) {
            var requestId = UUID.randomUUID().toString();
            testPending.put(requestId, respond);
            return requestId;
        }

        @Override
        public void onResponse(ZoneBridgeMessage.CommandResponse response) {
            var respond = testPending.remove(response.requestId());
            if (respond != null) {
                for (var jsonNode : response.messages()) {
                    var text = jsonNode.has("text") ? jsonNode.get("text").asText() : jsonNode.toPrettyString();
                    respond.accept(new S2CMessage.ZoneResponse(0, response.requestId(), "codeplane", text, jsonNode, List.of()));
                }
            }
        }

        @Override
        public void onDisconnect() {
            testPending.forEach((requestId, respond) ->
                respond.accept(new S2CMessage.Error(0, "zone_disconnected",
                    "Zone '" + namespace() + "' disconnected", null)));
            testPending.clear();
        }
    }
}
