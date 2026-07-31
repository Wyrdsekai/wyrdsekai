package org.wyrdsekai.core.skill.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenClawGatewayExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ScheduledExecutorService scheduler;
    private OpenClawGatewayExecutor executor;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executor = new OpenClawGatewayExecutor(null, mapper, scheduler);
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isClosed()) {
            executor.close();
        }
        scheduler.shutdownNow();
    }

    // ---- Helper methods ----

    private SkillContext testContext() {
        return testContext(5_000);
    }

    private SkillContext testContext(long timeoutMs) {
        return new SkillContext("did:test:agent1", "test-room", Map.of(), 1000,
            timeoutMs, false, false, null);
    }

    private String catalogueJson(ObjectNode... skills) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "catalogue");
        ArrayNode arr = root.putArray("skills");
        for (ObjectNode s : skills) {
            arr.add(s);
        }
        return mapper.writeValueAsString(root);
    }

    private ObjectNode skillJson(String id, String name, String description) {
        ObjectNode s = mapper.createObjectNode();
        s.put("id", id);
        s.put("name", name);
        s.put("description", description);
        return s;
    }

    private ObjectNode skillJsonWithParams(String id, String name, String description,
                                           ObjectNode... params) {
        ObjectNode s = skillJson(id, name, description);
        ArrayNode paramsArr = s.putArray("params");
        for (ObjectNode p : params) {
            paramsArr.add(p);
        }
        return s;
    }

    private ObjectNode paramJson(String name, String type, String desc, boolean required) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name", name);
        p.put("type", type);
        p.put("description", desc);
        p.put("required", required);
        return p;
    }

    private String resultJson(String requestId, String skillId, boolean success,
                               String output, long latencyMs) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "result");
        root.put("requestId", requestId);
        root.put("skillId", skillId);
        root.put("success", success);
        root.put("output", output);
        root.put("latencyMs", latencyMs);
        ObjectNode meta = root.putObject("meta");
        meta.put("version", "1.0");
        return mapper.writeValueAsString(root);
    }

    private String errorJson(String requestId, String skillId, String message)
            throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "error");
        root.put("requestId", requestId);
        root.put("skillId", skillId);
        root.put("message", message);
        return mapper.writeValueAsString(root);
    }

    // ---- Test classes ----

    @Nested
    class Registration {
        @Test
        void initially_empty_skills() {
            assertTrue(executor.availableSkills().isEmpty());
        }

        @Test
        void does_not_support_unknown_skill() {
            assertFalse(executor.supports("some.unknown.skill"));
        }

        @Test
        void register_skill_makes_it_available() {
            SkillDefinition def = new SkillDefinition(
                "test.skill.one", "Test Skill", "A test skill", "test-room",
                SkillTier.OPENCLAW, "openclaw", "MIT", List.of(),
                SkillAuth.NONE, SkillLocality.ANY, false);
            executor.registerSkill(def);

            assertTrue(executor.supports("test.skill.one"));
            assertEquals(1, executor.availableSkills().size());
            assertEquals("Test Skill", executor.availableSkills().get(0).name());
        }
    }

    @Nested
    class Tier {
        @Test
        void tier_is_openclaw() {
            assertEquals(SkillTier.OPENCLAW, executor.tier());
        }
    }

    @Nested
    class Defaults {
        @Test
        void default_gateway_url() {
            var exec = new OpenClawGatewayExecutor(null);
            assertEquals("ws://localhost:18789", exec.gatewayUrl());
            exec.close();
        }

        @Test
        void custom_gateway_url() {
            var exec = new OpenClawGatewayExecutor("ws://gateway:9999");
            assertEquals("ws://gateway:9999", exec.gatewayUrl());
            exec.close();
        }

        @Test
        void initial_state_is_disconnected() {
            assertEquals(OpenClawGatewayExecutor.ConnectionState.DISCONNECTED,
                executor.connectionState());
        }

        @Test
        void initially_not_closed() {
            assertFalse(executor.isClosed());
        }

        @Test
        void initially_zero_pending() {
            assertEquals(0, executor.pendingCount());
        }
    }

    @Nested
    class Execute {
        @Test
        void unknown_skill_returns_unavailable() {
            SkillResult result = executor.execute("unknown.skill.id", Map.of(), testContext());
            assertFalse(result.success());
        }

        @Test
        void execute_when_disconnected_returns_not_running() {
            // Register a skill but stay disconnected
            executor.registerSkill(new SkillDefinition(
                "test.echo", "Echo", "Echoes input", "test",
                SkillTier.OPENCLAW, "openclaw", "MIT", List.of(),
                SkillAuth.NONE, SkillLocality.ANY, false));

            SkillResult result = executor.execute("test.echo", Map.of(), testContext());
            assertFalse(result.success());
            assertEquals(SkillTier.OPENCLAW, result.executorTier());
        }

        @Test
        void execute_when_connected_but_no_websocket_returns_not_running() {
            executor.registerSkill(new SkillDefinition(
                "test.echo", "Echo", "Echoes input", "test",
                SkillTier.OPENCLAW, "openclaw", "MIT", List.of(),
                SkillAuth.NONE, SkillLocality.ANY, false));
            executor.setConnectionState(OpenClawGatewayExecutor.ConnectionState.CONNECTED);
            // webSocket is still null

            SkillResult result = executor.execute("test.echo", Map.of(), testContext());
            assertFalse(result.success());
        }
    }

    @Nested
    class CatalogueHandling {
        @Test
        void parse_empty_catalogue() throws Exception {
            String msg = catalogueJson();
            executor.handleMessage(msg);
            assertTrue(executor.availableSkills().isEmpty());
        }

        @Test
        void parse_single_skill_catalogue() throws Exception {
            String msg = catalogueJson(
                skillJson("openclaw.curl.get", "HTTP GET", "Fetch a URL"));
            executor.handleMessage(msg);

            assertEquals(1, executor.skillCount());
            assertTrue(executor.supports("openclaw.curl.get"));

            SkillDefinition def = executor.availableSkills().get(0);
            assertEquals("openclaw.curl.get", def.id());
            assertEquals("HTTP GET", def.name());
            assertEquals("Fetch a URL", def.description());
            assertEquals(SkillTier.OPENCLAW, def.tier());
            assertEquals("openclaw", def.origin());
            assertEquals("MIT", def.license());
        }

        @Test
        void parse_multi_skill_catalogue() throws Exception {
            String msg = catalogueJson(
                skillJson("openclaw.curl.get", "HTTP GET", "Fetch a URL"),
                skillJson("openclaw.curl.post", "HTTP POST", "Post to a URL"),
                skillJson("openclaw.jq.query", "JQ Query", "Query JSON"));
            executor.handleMessage(msg);

            assertEquals(3, executor.skillCount());
            assertTrue(executor.supports("openclaw.curl.get"));
            assertTrue(executor.supports("openclaw.curl.post"));
            assertTrue(executor.supports("openclaw.jq.query"));
        }

        @Test
        void parse_skill_with_params() throws Exception {
            ObjectNode param1 = paramJson("url", "string", "The URL to fetch", true);
            ObjectNode param2 = paramJson("timeout", "number", "Timeout in seconds", false);

            String msg = catalogueJson(
                skillJsonWithParams("openclaw.curl.get", "HTTP GET", "Fetch a URL",
                    param1, param2));
            executor.handleMessage(msg);

            SkillDefinition def = executor.availableSkills().get(0);
            assertEquals(2, def.params().size());
            assertEquals("url", def.params().get(0).name());
            assertEquals("string", def.params().get(0).type());
            assertTrue(def.params().get(0).required());
            assertEquals("timeout", def.params().get(1).name());
            assertEquals("number", def.params().get(1).type());
            assertFalse(def.params().get(1).required());
        }

        @Test
        void parse_skill_with_room() throws Exception {
            ObjectNode skill = skillJson("hearth.openhue.set-light", "Set Light", "Control a light");
            skill.put("room", "hearth");

            String msg = catalogueJson(skill);
            executor.handleMessage(msg);

            assertEquals("hearth", executor.availableSkills().get(0).room());
        }

        @Test
        void skip_skill_without_id() throws Exception {
            ObjectNode noId = mapper.createObjectNode();
            noId.put("name", "Missing ID");

            String msg = catalogueJson(
                noId,
                skillJson("openclaw.valid", "Valid", "A valid skill"));
            executor.handleMessage(msg);

            assertEquals(1, executor.skillCount());
            assertTrue(executor.supports("openclaw.valid"));
        }

        @Test
        void skip_skill_without_name() throws Exception {
            ObjectNode noName = mapper.createObjectNode();
            noName.put("id", "openclaw.no-name");

            String msg = catalogueJson(
                noName,
                skillJson("openclaw.valid", "Valid", "A valid skill"));
            executor.handleMessage(msg);

            assertEquals(1, executor.skillCount());
        }

        @Test
        void catalogue_replaces_previous_entries() throws Exception {
            // Load first catalogue
            executor.handleMessage(catalogueJson(
                skillJson("openclaw.a", "A", "First A")));
            assertEquals(1, executor.skillCount());

            // Load second catalogue — same ID, different description
            executor.handleMessage(catalogueJson(
                skillJson("openclaw.a", "A Updated", "New A"),
                skillJson("openclaw.b", "B", "New B")));
            assertEquals(2, executor.skillCount());
            assertEquals("A Updated",
                executor.availableSkills().stream()
                    .filter(s -> s.id().equals("openclaw.a"))
                    .findFirst().orElseThrow().name());
        }

        @Test
        void catalogue_missing_skills_array_is_handled() throws Exception {
            String bad = mapper.writeValueAsString(Map.of("type", "catalogue"));
            // Should not throw
            executor.handleMessage(bad);
            assertEquals(0, executor.skillCount());
        }

        @Test
        void default_room_is_openclaw() throws Exception {
            String msg = catalogueJson(
                skillJson("test.skill", "Test", "A test"));
            executor.handleMessage(msg);
            assertEquals("openclaw", executor.availableSkills().get(0).room());
        }

        @Test
        void default_description_is_name_when_missing() throws Exception {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("id", "test.nodesc");
            skill.put("name", "NoDesc");
            // no description field
            String msg = catalogueJson(skill);
            executor.handleMessage(msg);

            assertEquals("NoDesc", executor.availableSkills().get(0).description());
        }
    }

    @Nested
    class ResultHandling {
        @Test
        void successful_result_completes_future() throws Exception {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            String msg = resultJson(requestId, "openclaw.echo", true, "Hello World", 42);
            executor.handleMessage(msg);

            assertTrue(future.isDone());
            SkillResult result = future.get();
            assertTrue(result.success());
            assertEquals("Hello World", result.output());
            assertEquals(42, result.durationMs());
            assertEquals("openclaw.echo", result.skillId());
            assertEquals(SkillTier.OPENCLAW, result.executorTier());
        }

        @Test
        void failed_result_completes_future_with_error() throws Exception {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            String msg = resultJson(requestId, "openclaw.fail", false, "Command failed", 100);
            executor.handleMessage(msg);

            assertTrue(future.isDone());
            SkillResult result = future.get();
            assertFalse(result.success());
            assertEquals("Command failed", result.output());
        }

        @Test
        void result_with_meta_is_parsed() throws Exception {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            ObjectNode root = mapper.createObjectNode();
            root.put("type", "result");
            root.put("requestId", requestId);
            root.put("skillId", "openclaw.test");
            root.put("success", true);
            root.put("output", "ok");
            root.put("latencyMs", 10);
            ObjectNode meta = root.putObject("meta");
            meta.put("exitCode", 0);
            meta.put("version", "2.1");
            meta.put("cached", true);

            executor.handleMessage(mapper.writeValueAsString(root));

            SkillResult result = future.get();
            assertEquals(0, result.data().get("exitCode"));
            assertEquals("2.1", result.data().get("version"));
            assertEquals(true, result.data().get("cached"));
        }

        @Test
        void result_without_request_id_is_ignored() throws Exception {
            // Should not throw
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "result");
            root.put("skillId", "orphan");
            root.put("success", true);
            root.put("output", "nobody asked");
            executor.handleMessage(mapper.writeValueAsString(root));
        }

        @Test
        void result_with_unknown_request_id_is_ignored() throws Exception {
            String msg = resultJson("nonexistent-id", "openclaw.test", true, "ok", 5);
            // Should not throw
            executor.handleMessage(msg);
        }
    }

    @Nested
    class ErrorHandling {
        @Test
        void error_message_completes_future() throws Exception {
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            String msg = errorJson(requestId, "openclaw.bad", "Container crashed");
            executor.handleMessage(msg);

            assertTrue(future.isDone());
            SkillResult result = future.get();
            assertFalse(result.success());
            assertEquals(SkillTier.OPENCLAW, result.executorTier());
        }

        @Test
        void error_without_request_id_does_not_throw() throws Exception {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "error");
            root.put("skillId", "openclaw.orphan");
            root.put("message", "unexpected error");
            executor.handleMessage(mapper.writeValueAsString(root));
            // Should not throw
        }

        @Test
        void unknown_message_type_does_not_throw() throws Exception {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "heartbeat");
            root.put("ts", System.currentTimeMillis());
            executor.handleMessage(mapper.writeValueAsString(root));
            // Should not throw
        }

        @Test
        void malformed_json_does_not_throw() {
            executor.handleMessage("this is not json at all{{{");
            // Should not throw
        }
    }

    @Nested
    class MessageBuilding {
        @Test
        void build_invoke_message_has_correct_structure() throws Exception {
            String msg = executor.buildInvokeMessage(
                "openclaw.curl.get",
                Map.of("url", "https://example.com", "timeout", 30),
                "req-123",
                5000);

            JsonNode node = mapper.readTree(msg);
            assertEquals("invoke", node.get("type").asText());
            assertEquals("openclaw.curl.get", node.get("skillId").asText());
            assertEquals("req-123", node.get("requestId").asText());
            assertEquals(5000, node.get("timeout").asLong());
            assertEquals("https://example.com", node.get("params").get("url").asText());
            assertEquals(30, node.get("params").get("timeout").asInt());
        }

        @Test
        void build_invoke_message_empty_params() throws Exception {
            String msg = executor.buildInvokeMessage(
                "openclaw.uptime", Map.of(), "req-456", 10000);

            JsonNode node = mapper.readTree(msg);
            assertEquals("invoke", node.get("type").asText());
            assertEquals("openclaw.uptime", node.get("skillId").asText());
            assertTrue(node.get("params").isEmpty());
        }
    }

    @Nested
    class SkillParsing {
        @Test
        void parseCatalogueSkill_full() {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("id", "hearth.openhue.set-light");
            skill.put("name", "Set Light");
            skill.put("description", "Control a Hue light");
            skill.put("room", "hearth");
            ArrayNode params = skill.putArray("params");
            ObjectNode p = params.addObject();
            p.put("name", "light");
            p.put("type", "string");
            p.put("description", "Light name");
            p.put("required", true);

            SkillDefinition def = executor.parseCatalogueSkill(skill);

            assertNotNull(def);
            assertEquals("hearth.openhue.set-light", def.id());
            assertEquals("Set Light", def.name());
            assertEquals("Control a Hue light", def.description());
            assertEquals("hearth", def.room());
            assertEquals(SkillTier.OPENCLAW, def.tier());
            assertEquals("openclaw", def.origin());
            assertEquals(1, def.params().size());
            assertEquals("light", def.params().get(0).name());
            assertTrue(def.params().get(0).required());
        }

        @Test
        void parseCatalogueSkill_minimal() {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("id", "test.minimal");
            skill.put("name", "Minimal");

            SkillDefinition def = executor.parseCatalogueSkill(skill);

            assertNotNull(def);
            assertEquals("test.minimal", def.id());
            assertEquals("Minimal", def.name());
            assertEquals("Minimal", def.description()); // falls back to name
            assertEquals("openclaw", def.room());
            assertTrue(def.params().isEmpty());
        }

        @Test
        void parseCatalogueSkill_null_id_returns_null() {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("name", "No ID");

            assertNull(executor.parseCatalogueSkill(skill));
        }

        @Test
        void parseCatalogueSkill_null_name_returns_null() {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("id", "has.id.no.name");

            assertNull(executor.parseCatalogueSkill(skill));
        }

        @Test
        void parseCatalogueSkill_with_enum_param() {
            ObjectNode skill = mapper.createObjectNode();
            skill.put("id", "test.enum");
            skill.put("name", "Enum Test");
            ArrayNode params = skill.putArray("params");
            ObjectNode p = params.addObject();
            p.put("name", "color");
            p.put("type", "enum");
            p.put("description", "Light color");
            p.put("required", true);
            ArrayNode enumValues = p.putArray("enumValues");
            enumValues.add("red");
            enumValues.add("green");
            enumValues.add("blue");

            SkillDefinition def = executor.parseCatalogueSkill(skill);

            assertNotNull(def);
            assertEquals(1, def.params().size());
            SkillParam param = def.params().get(0);
            assertEquals("enum", param.type());
            assertEquals(List.of("red", "green", "blue"), param.enumValues());
        }
    }

    @Nested
    class ConnectionState {
        @Test
        void state_transitions() {
            assertEquals(OpenClawGatewayExecutor.ConnectionState.DISCONNECTED,
                executor.connectionState());

            executor.setConnectionState(OpenClawGatewayExecutor.ConnectionState.CONNECTING);
            assertEquals(OpenClawGatewayExecutor.ConnectionState.CONNECTING,
                executor.connectionState());

            executor.setConnectionState(OpenClawGatewayExecutor.ConnectionState.CONNECTED);
            assertEquals(OpenClawGatewayExecutor.ConnectionState.CONNECTED,
                executor.connectionState());

            executor.setConnectionState(OpenClawGatewayExecutor.ConnectionState.RECONNECTING);
            assertEquals(OpenClawGatewayExecutor.ConnectionState.RECONNECTING,
                executor.connectionState());
        }
    }

    @Nested
    class Shutdown {
        @Test
        void close_sets_state_to_disconnected() {
            executor.setConnectionState(OpenClawGatewayExecutor.ConnectionState.CONNECTED);
            executor.close();

            assertTrue(executor.isClosed());
            assertEquals(OpenClawGatewayExecutor.ConnectionState.DISCONNECTED,
                executor.connectionState());
        }

        @Test
        void close_fails_pending_invocations() throws Exception {
            String requestId = "pending-1";
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            executor.close();

            assertTrue(future.isCompletedExceptionally());
            assertEquals(0, executor.pendingCount());
        }

        @Test
        void close_is_idempotent() {
            executor.close();
            executor.close(); // Should not throw
            assertTrue(executor.isClosed());
        }
    }

    @Nested
    class ProtocolIntegration {
        @Test
        void full_catalogue_then_invoke_lifecycle() throws Exception {
            // Step 1: Load catalogue
            String catalogue = catalogueJson(
                skillJsonWithParams("openclaw.echo", "Echo", "Echo input",
                    paramJson("text", "string", "Text to echo", true)));
            executor.handleMessage(catalogue);

            assertEquals(1, executor.skillCount());
            assertTrue(executor.supports("openclaw.echo"));

            // Step 2: Simulate invoke + response
            String requestId = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future = new CompletableFuture<>();
            executor.pendingInvocations().put(requestId, future);

            // Simulate gateway response
            String response = resultJson(requestId, "openclaw.echo", true,
                "Hello World", 15);
            executor.handleMessage(response);

            SkillResult result = future.get(1, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals("Hello World", result.output());
            assertEquals(15, result.durationMs());
            assertEquals(SkillTier.OPENCLAW, result.executorTier());
        }

        @Test
        void multiple_concurrent_invocations_resolved_independently() throws Exception {
            String req1 = UUID.randomUUID().toString();
            String req2 = UUID.randomUUID().toString();
            CompletableFuture<SkillResult> future1 = new CompletableFuture<>();
            CompletableFuture<SkillResult> future2 = new CompletableFuture<>();
            executor.pendingInvocations().put(req1, future1);
            executor.pendingInvocations().put(req2, future2);

            // Complete req2 first
            executor.handleMessage(resultJson(req2, "skill.b", true, "Result B", 20));
            // Then req1
            executor.handleMessage(resultJson(req1, "skill.a", true, "Result A", 10));

            assertEquals("Result A", future1.get(1, TimeUnit.SECONDS).output());
            assertEquals("Result B", future2.get(1, TimeUnit.SECONDS).output());
        }
    }

    @Nested
    class Constants {
        @Test
        void reconnect_min_is_1_second() {
            assertEquals(1_000, OpenClawGatewayExecutor.RECONNECT_MIN_MS);
        }

        @Test
        void reconnect_max_is_30_seconds() {
            assertEquals(30_000, OpenClawGatewayExecutor.RECONNECT_MAX_MS);
        }

        @Test
        void catalogue_timeout_is_10_seconds() {
            assertEquals(10_000, OpenClawGatewayExecutor.CATALOGUE_TIMEOUT_MS);
        }
    }

    @Nested
    class LargeCatalogue {
        @Test
        void handles_large_catalogue() throws Exception {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "catalogue");
            ArrayNode arr = root.putArray("skills");
            int count = 500;
            for (int i = 0; i < count; i++) {
                ObjectNode s = arr.addObject();
                s.put("id", "openclaw.skill." + i);
                s.put("name", "Skill " + i);
                s.put("description", "Description for skill " + i);
            }

            executor.handleMessage(mapper.writeValueAsString(root));
            assertEquals(count, executor.skillCount());
        }
    }
}
