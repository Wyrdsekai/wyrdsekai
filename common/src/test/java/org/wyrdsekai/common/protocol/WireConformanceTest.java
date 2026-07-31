package org.wyrdsekai.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.*;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire protocol conformance tests.
 * Validates that Jackson serialization/deserialization matches the JSON fixtures
 * in protocol-tests/fixtures/. These fixtures are the contract — both KMP and RN
 * clients test against the same fixtures.
 *
 * <p>The server (this code) is the reference implementation. If a fixture doesn't
 * roundtrip through Jackson, the fixture or the Java type is wrong.
 */
class WireConformanceTest {

    private static final ObjectMapper mapper = Json.mapper();

    // --- Fixture loading ---

    private static final Path FIXTURES_ROOT = findFixturesRoot();

    private static Path findFixturesRoot() {
        // Walk up from the test class to find protocol-tests/fixtures/
        var candidate = Path.of("protocol-tests/fixtures");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = Path.of("../protocol-tests/fixtures");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = Path.of("../../protocol-tests/fixtures");
        if (Files.isDirectory(candidate)) return candidate;
        throw new RuntimeException("Cannot find protocol-tests/fixtures/ directory");
    }

    private String loadFixture(String subpath) throws IOException {
        return Files.readString(FIXTURES_ROOT.resolve(subpath));
    }

    // ========================================================================
    // S2C Fixtures — Deserialize from fixture JSON, verify fields
    // ========================================================================

    @Nested
    class S2CFixtures {

        @Test void roomState() throws Exception {
            var json = loadFixture("s2c/room_state.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.RoomState.class);
            var rs = (S2CMessage.RoomState) msg;
            assertThat(rs.seq()).isEqualTo(1);
            assertThat(rs.room().roomId()).isEqualTo("nexus");
            assertThat(rs.room().name()).isEqualTo("The Nexus");
            assertThat(rs.room().zone()).isEqualTo("home");
            assertThat(rs.room().exits()).hasSize(2);
            assertThat(rs.room().exits().getFirst().direction()).isEqualTo("north");
            assertThat(rs.room().exits().getFirst().targetRoom()).isEqualTo("terminal");
            assertThat(rs.room().entities()).hasSize(1);
            assertThat(rs.room().entities().getFirst().name()).isEqualTo("Guide");
            assertThat(rs.room().objects()).hasSize(2);
            assertThat(rs.room().objects().getFirst().takeable()).isTrue();
            assertThat(rs.room().hints()).hasSize(3);
            assertThat(rs.room().hints().getFirst().labelKey()).isEqualTo("hint.greet_guide");
            assertThat(rs.inventory()).hasSize(1);
            assertThat(rs.inventory().getFirst().name()).isEqualTo("brass key");
        }

        @Test void roomStateEmpty() throws Exception {
            var json = loadFixture("s2c/room_state-empty.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.RoomState.class);
            var rs = (S2CMessage.RoomState) msg;
            assertThat(rs.room().exits()).isEmpty();
            assertThat(rs.room().entities()).isEmpty();
            assertThat(rs.room().objects()).isEmpty();
        }

        @Test void prose() throws Exception {
            var json = loadFixture("s2c/prose.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.seq()).isEqualTo(2);
            assertThat(p.speaker()).isEqualTo("Guide");
            assertThat(p.text()).contains("Welcome, traveler");
            assertThat(p.priority()).isEqualTo("normal");
            assertThat(p.lang()).isEqualTo("en");
            assertThat(p.isAiGenerated()).isTrue();
            assertThat(p.hints()).hasSize(1);
            assertThat(p.hints().getFirst().label()).isEqualTo("Ask about rooms");
            assertThat(p.hints().getFirst().action()).isEqualTo("say");
            assertThat(p.blocks()).isEmpty();
        }

        @Test void proseMinimal() throws Exception {
            var json = loadFixture("s2c/prose-minimal.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.speaker()).isEqualTo("narrator");
            // Optional fields should have defaults
            assertThat(p.isAiGenerated()).isFalse();
        }

        @Test void proseCritical() throws Exception {
            var json = loadFixture("s2c/prose-critical.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.priority()).isEqualTo("critical");
        }

        @Test void proseAmbient() throws Exception {
            var json = loadFixture("s2c/prose-ambient.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.priority()).isEqualTo("ambient");
            assertThat(p.isAiGenerated()).isTrue();
        }

        @Test void proseWithBlocks() throws Exception {
            var json = loadFixture("s2c/prose-with-blocks.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.seq()).isEqualTo(42);
            assertThat(p.blocks()).hasSize(2);

            var diffBlock = p.blocks().getFirst();
            assertThat(diffBlock.format()).isEqualTo("codeplane.diff");
            assertThat(diffBlock.fallback()).isEqualTo("auth.js: +12 -5 lines changed");
            assertThat(diffBlock.data().get("filePath").asText()).isEqualTo("auth.js");
            assertThat(diffBlock.data().get("additions").asInt()).isEqualTo(12);

            var costBlock = p.blocks().get(1);
            assertThat(costBlock.format()).isEqualTo("codeplane.cost");
            assertThat(costBlock.data().get("tokensIn").asInt()).isEqualTo(4200);
        }

        @Test void proseWithStructured() throws Exception {
            var json = loadFixture("s2c/prose-with-structured.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var p = (S2CMessage.Prose) msg;
            assertThat(p.structured()).isNotNull();
            assertThat(p.structured().name()).isEqualTo("Workshop");
            assertThat(p.structured().zone()).isEqualTo("home");
        }

        @Test void agentAction() throws Exception {
            var json = loadFixture("s2c/agent_action.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.AgentAction.class);
            var aa = (S2CMessage.AgentAction) msg;
            assertThat(aa.agentName()).isEqualTo("Guide");
            assertThat(aa.action()).isEqualTo("take");
            assertThat(aa.description()).contains("scroll");
        }

        @Test void stateChange() throws Exception {
            var json = loadFixture("s2c/state_change.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.StateChange.class);
            var sc = (S2CMessage.StateChange) msg;
            assertThat(sc.description()).isEqualTo("The northern door swings open with a creak.");
        }

        @Test void stateChangeWithBlocks() throws Exception {
            var json = loadFixture("s2c/state_change-with-blocks.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.StateChange.class);
            var sc = (S2CMessage.StateChange) msg;
            assertThat(sc.blocks()).hasSize(1);
            assertThat(sc.blocks().getFirst().format()).isEqualTo("codeplane.board_card");
        }

        @Test void replayDone() throws Exception {
            var json = loadFixture("s2c/replay_done.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.ReplayDone.class);
            var rd = (S2CMessage.ReplayDone) msg;
            assertThat(rd.fromSeq()).isEqualTo(42);
            assertThat(rd.toSeq()).isEqualTo(55);
            assertThat(rd.count()).isEqualTo(13);
        }

        @Test void error() throws Exception {
            var json = loadFixture("s2c/error.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Error.class);
            var e = (S2CMessage.Error) msg;
            assertThat(e.code()).isEqualTo("no_exit");
            assertThat(e.message()).isEqualTo("There is no exit in that direction.");
            assertThat(e.requestId()).isEqualTo("msg-002");
        }

        @Test void notification() throws Exception {
            var json = loadFixture("s2c/notification.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Notification.class);
            var n = (S2CMessage.Notification) msg;
            assertThat(n.level()).isEqualTo("info");
            assertThat(n.title()).isEqualTo("Welcome");
        }

        @Test void notificationWarning() throws Exception {
            var json = loadFixture("s2c/notification-warning.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Notification.class);
            var n = (S2CMessage.Notification) msg;
            assertThat(n.level()).isEqualTo("warning");
        }

        @Test void transit() throws Exception {
            var json = loadFixture("s2c/transit.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Transit.class);
            var t = (S2CMessage.Transit) msg;
            assertThat(t.targetZoneId()).isEqualTo("neighbor-zone");
            assertThat(t.targetUrl()).isEqualTo("wss://neighbor.example.com/ws");
            assertThat(t.transitToken()).startsWith("tt-");
        }

        @Test void tokenStream() throws Exception {
            var json = loadFixture("s2c/token_stream.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.TokenStream.class);
            var ts = (S2CMessage.TokenStream) msg;
            assertThat(ts.source()).isEqualTo("Guide");
            assertThat(ts.token()).isEqualTo("The ancient");
            assertThat(ts.done()).isFalse();
            assertThat(ts.context()).isNull();
        }

        @Test void tokenStreamDone() throws Exception {
            var json = loadFixture("s2c/token_stream-done.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.TokenStream.class);
            var ts = (S2CMessage.TokenStream) msg;
            assertThat(ts.done()).isTrue();
        }

        @Test void tokenStreamWithContext() throws Exception {
            var json = loadFixture("s2c/token_stream-with-context.json");
            var msg = mapper.readValue(json, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.TokenStream.class);
            var ts = (S2CMessage.TokenStream) msg;
            assertThat(ts.context()).isEqualTo("board-7");
        }
    }

    // ========================================================================
    // C2S Fixtures — Deserialize from fixture JSON, verify fields
    // ========================================================================

    @Nested
    class C2SFixtures {

        @Test void say() throws Exception {
            var json = loadFixture("c2s/say.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Say.class);
            var s = (C2SMessage.Say) msg;
            assertThat(s.id()).isEqualTo("msg-001");
            assertThat(s.roomId()).isEqualTo("nexus");
            assertThat(s.text()).isEqualTo("Hello everyone!");
        }

        @Test void go() throws Exception {
            var json = loadFixture("c2s/go.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Go.class);
            assertThat(((C2SMessage.Go) msg).direction()).isEqualTo("north");
        }

        @Test void take() throws Exception {
            var json = loadFixture("c2s/take.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Take.class);
            assertThat(((C2SMessage.Take) msg).objectName()).isEqualTo("scroll");
        }

        @Test void drop() throws Exception {
            var json = loadFixture("c2s/drop.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Drop.class);
        }

        @Test void use() throws Exception {
            var json = loadFixture("c2s/use.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Use.class);
            var u = (C2SMessage.Use) msg;
            assertThat(u.objectName()).isEqualTo("key");
            assertThat(u.target()).isEqualTo("locked_door");
        }

        @Test void useNoTarget() throws Exception {
            var json = loadFixture("c2s/use-no-target.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Use.class);
            var u = (C2SMessage.Use) msg;
            assertThat(u.target()).isNull();
        }

        @Test void look() throws Exception {
            var json = loadFixture("c2s/look.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Look.class);
        }

        @Test void hintSelect() throws Exception {
            var json = loadFixture("c2s/hint_select.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.HintSelect.class);
            assertThat(((C2SMessage.HintSelect) msg).index()).isEqualTo(0);
        }

        @Test void reconnect() throws Exception {
            var json = loadFixture("c2s/reconnect.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Reconnect.class);
            assertThat(((C2SMessage.Reconnect) msg).lastSeenSeq()).isEqualTo(42);
        }

        @Test void command() throws Exception {
            var json = loadFixture("c2s/command.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Command.class);
            var cmd = (C2SMessage.Command) msg;
            assertThat(cmd.command()).isEqualTo("inventory");
            assertThat(cmd.args()).isEmpty();
            assertThat(cmd.payload()).isEmpty();
        }

        @Test void commandNamespaced() throws Exception {
            var json = loadFixture("c2s/command-namespaced.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Command.class);
            var cmd = (C2SMessage.Command) msg;
            assertThat(cmd.command()).isEqualTo("codeplane.approve");
            assertThat(cmd.payload()).containsEntry("eventId", "evt-42");
            assertThat(cmd.payload()).containsEntry("decision", "approve");
        }

        @Test void commandWithArgs() throws Exception {
            var json = loadFixture("c2s/command-with-args.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.Command.class);
            var cmd = (C2SMessage.Command) msg;
            assertThat(cmd.args()).hasSize(2);
            assertThat(cmd.args().getFirst()).isEqualTo("alice");
        }

        @Test void setPreference() throws Exception {
            var json = loadFixture("c2s/set_preference.json");
            var msg = mapper.readValue(json, C2SMessage.class);

            assertThat(msg).isInstanceOf(C2SMessage.SetPreference.class);
            var sp = (C2SMessage.SetPreference) msg;
            assertThat(sp.key()).isEqualTo("locale");
            assertThat(sp.value()).isEqualTo("es");
        }
    }

    // ========================================================================
    // Roundtrip tests — Java object → JSON → back to Java
    // ========================================================================

    @Nested
    class Roundtrips {

        @Test void tokenStreamRoundtrip() throws Exception {
            var msg = new S2CMessage.TokenStream(14, "Guide", "The ancient", false, null);
            var json = mapper.writeValueAsString(msg);
            var result = mapper.readValue(json, S2CMessage.class);

            assertThat(result).isInstanceOf(S2CMessage.TokenStream.class);
            var ts = (S2CMessage.TokenStream) result;
            assertThat(ts.source()).isEqualTo("Guide");
            assertThat(ts.token()).isEqualTo("The ancient");
            assertThat(ts.done()).isFalse();
            assertThat(ts.context()).isNull();
        }

        @Test void proseWithBlocksRoundtrip() throws Exception {
            var data = mapper.createObjectNode()
                .put("filePath", "auth.js")
                .put("additions", 12);
            var block = new ContentBlock("codeplane.diff", data, "auth.js: +12 lines");
            var msg = new S2CMessage.Prose(1, "Agent", "Review done",
                List.of(), null, "normal", "en", true, List.of(block));

            var json = mapper.writeValueAsString(msg);
            var result = (S2CMessage.Prose) mapper.readValue(json, S2CMessage.class);

            assertThat(result.blocks()).hasSize(1);
            assertThat(result.blocks().getFirst().format()).isEqualTo("codeplane.diff");
            assertThat(result.blocks().getFirst().data().get("filePath").asText()).isEqualTo("auth.js");
        }

        @Test void commandWithPayloadRoundtrip() throws Exception {
            var msg = new C2SMessage.Command("cmd-1", "codeplane.approve",
                List.of(), Map.of("eventId", "evt-42", "decision", "approve"));

            var json = mapper.writeValueAsString(msg);
            var result = (C2SMessage.Command) mapper.readValue(json, C2SMessage.class);

            assertThat(result.command()).isEqualTo("codeplane.approve");
            assertThat(result.payload()).containsEntry("eventId", "evt-42");
        }

        @Test void stateChangeWithBlocksRoundtrip() throws Exception {
            var data = mapper.createObjectNode().put("status", "done");
            var block = new ContentBlock("codeplane.board_card", data, "Card done");
            var msg = new S2CMessage.StateChange(5, "Board updated", null, List.of(block));

            var json = mapper.writeValueAsString(msg);
            var result = (S2CMessage.StateChange) mapper.readValue(json, S2CMessage.class);

            assertThat(result.blocks()).hasSize(1);
            assertThat(result.blocks().getFirst().format()).isEqualTo("codeplane.board_card");
        }

        @Test void allC2STypesRoundtrip() throws Exception {
            List<C2SMessage> messages = List.of(
                new C2SMessage.Say("1", "r1", "hello"),
                new C2SMessage.Go("2", "r1", "north"),
                new C2SMessage.Take("3", "r1", "key"),
                new C2SMessage.Drop("4", "r1", "key"),
                new C2SMessage.Use("5", "r1", "key", "door"),
                new C2SMessage.Look("6", "r1"),
                new C2SMessage.HintSelect("7", "r1", 2),
                new C2SMessage.Reconnect("8", "r1", 10),
                new C2SMessage.Command("9", "who", List.of()),
                new C2SMessage.SetPreference("10", "lang", "es")
            );

            for (var original : messages) {
                var json = mapper.writeValueAsString(original);
                var result = mapper.readValue(json, C2SMessage.class);
                assertThat(result.getClass()).isEqualTo(original.getClass());
                assertThat(result.id()).isEqualTo(original.id());
            }
        }

        @Test void allS2CTypesRoundtrip() throws Exception {
            var room = new RoomSnapshot("nexus", "The Nexus", "A hub.", "home",
                List.of(), List.of(), List.of(), List.of());

            List<S2CMessage> messages = List.of(
                new S2CMessage.RoomState(1, room, List.of()),
                new S2CMessage.Prose(2, "narrator", "Hello", List.of(), null, "normal"),
                new S2CMessage.AgentAction(3, "Guide", "emote", "smiles"),
                new S2CMessage.StateChange(4, "Door opens", null),
                new S2CMessage.ReplayDone(5, 1, 4, 4),
                new S2CMessage.Error(6, "NOT_FOUND", "Not found", "req-1"),
                new S2CMessage.Notification(7, "info", "Welcome", "Hello!"),
                new S2CMessage.Transit(8, "zone-b", "wss://host/ws", "tok-1", "Traveling..."),
                new S2CMessage.TokenStream(9, "Guide", "Hello", false, null)
            );

            for (var original : messages) {
                var json = mapper.writeValueAsString(original);
                var result = mapper.readValue(json, S2CMessage.class);
                assertThat(result.getClass()).isEqualTo(original.getClass());
                assertThat(result.seq()).isEqualTo(original.seq());
            }
        }
    }

    // ========================================================================
    // Scenario tests
    // ========================================================================

    @Nested
    class Scenarios {

        @Test void tokenStreamAssembly() throws Exception {
            var scenarioJson = loadFixture("scenarios/token_stream_sequence.json");
            var scenario = mapper.readTree(scenarioJson);

            var sb = new StringBuilder();
            for (var node : scenario.get("messages")) {
                var msg = mapper.treeToValue(node, S2CMessage.class);
                assertThat(msg).isInstanceOf(S2CMessage.TokenStream.class);
                sb.append(((S2CMessage.TokenStream) msg).token());
            }

            assertThat(sb.toString()).isEqualTo(
                scenario.get("expected_assembled_text").asText());
        }

        @Test void unknownContentBlockFormat() throws Exception {
            var scenarioJson = loadFixture("scenarios/unknown_content_block.json");
            var scenario = mapper.readTree(scenarioJson);

            var messageNode = scenario.get("message");
            var msg = mapper.treeToValue(messageNode, S2CMessage.class);

            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            var prose = (S2CMessage.Prose) msg;
            assertThat(prose.blocks()).hasSize(1);
            assertThat(prose.blocks().getFirst().format()).isEqualTo("future.unknown_format");
            assertThat(prose.blocks().getFirst().fallback())
                .isEqualTo("Results: 42 items processed, 3 errors");
        }

        @Test void reconnectionScenario() throws Exception {
            var scenarioJson = loadFixture("scenarios/reconnection.json");
            var scenario = mapper.readTree(scenarioJson);

            // Verify initial messages parse
            for (var node : scenario.get("initial_messages")) {
                var msg = mapper.treeToValue(node, S2CMessage.class);
                assertThat(msg.seq()).isGreaterThan(0);
            }

            // Verify client reconnect message
            var clientSends = scenario.get("client_sends");
            var reconnect = mapper.treeToValue(clientSends, C2SMessage.class);
            assertThat(reconnect).isInstanceOf(C2SMessage.Reconnect.class);
            assertThat(((C2SMessage.Reconnect) reconnect).lastSeenSeq()).isEqualTo(3);

            // Verify expected replay
            var expectedReplay = scenario.get("expected_replay");
            assertThat(expectedReplay).hasSize(2);
            assertThat(expectedReplay.get(0).get("seq").asLong()).isEqualTo(4);
            assertThat(expectedReplay.get(1).get("seq").asLong()).isEqualTo(5);

            // Verify expected replay_done
            var replayDone = scenario.get("expected_replay_done");
            assertThat(replayDone.get("fromSeq").asLong()).isEqualTo(3);
            assertThat(replayDone.get("toSeq").asLong()).isEqualTo(5);
            assertThat(replayDone.get("count").asInt()).isEqualTo(2);
        }
    }

    // ========================================================================
    // Forward compatibility — unknown fields don't break deserialization
    // ========================================================================

    @Nested
    class ForwardCompat {

        @Test void unknownFieldsIgnored() throws Exception {
            var json = """
                {
                  "type": "prose",
                  "seq": 99,
                  "speaker": "narrator",
                  "text": "Hello.",
                  "hints": [],
                  "structured": null,
                  "priority": "normal",
                  "futureField": "ignored",
                  "anotherFutureField": 42
                }
                """;

            var msg = mapper.readValue(json, S2CMessage.class);
            assertThat(msg).isInstanceOf(S2CMessage.Prose.class);
            assertThat(((S2CMessage.Prose) msg).text()).isEqualTo("Hello.");
        }

        @Test void unknownC2SFieldsIgnored() throws Exception {
            var json = """
                {
                  "type": "say",
                  "id": "msg-99",
                  "roomId": "nexus",
                  "text": "Hello",
                  "futureField": true
                }
                """;

            var msg = mapper.readValue(json, C2SMessage.class);
            assertThat(msg).isInstanceOf(C2SMessage.Say.class);
        }
    }
}
