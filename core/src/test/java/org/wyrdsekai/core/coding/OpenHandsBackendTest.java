package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2c — unit tests for {@link OpenHandsBackend} after the
 * 2026-05-05 V1 Agent Server live-verification.
 *
 * <p>Tests are network-free: a stub
 * {@link OpenHandsBackend.AgentServerClientFactory} supplies canned
 * responses so the parser + payload-building paths can be verified
 * without an actual Agent Server running. Auth-missing short-circuit +
 * Docker probe gate both have their own tests. Lifecycle calls
 * (create → run → poll → final → delete) are tracked on the stub so
 * the tests can assert each REST step actually fires.</p>
 */
class OpenHandsBackendTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_openhands() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(OpenHandsBackend.class);
        assertThat(permitted).contains(CodePlaneBackend.class);
        assertThat(permitted).contains(OpenCodeBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_openhands() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        assertThat(b.name()).isEqualTo("openhands");
        assertThat(b.name()).isEqualTo(OpenHandsBackend.NAME);
    }

    @Test void tier_is_local_heavy() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        assertThat(b.tier()).isEqualTo(BackendTier.LOCAL_HEAVY);
    }

    @Test void estimated_cu_varies_by_task_type() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        assertThat(b.estimatedCu(TaskSpec.create("did:c", "explore", "x")))
            .isEqualTo(50L);
        assertThat(b.estimatedCu(TaskSpec.create("did:c", "implement_feature", "x")))
            .isEqualTo(200L);
        assertThat(b.estimatedCu(TaskSpec.create("did:c", "refactor", "x")))
            .isEqualTo(150L);
        assertThat(b.estimatedCu(TaskSpec.create("did:c", "code", "x")))
            .isEqualTo(100L);
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = OpenHandsRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.agentServerUrl()).isEqualTo(OpenHandsRuntimeConfig.DEFAULT_AGENT_SERVER_URL);
        assertThat(cfg.dockerImage()).isEqualTo(OpenHandsRuntimeConfig.DEFAULT_DOCKER_IMAGE);
        assertThat(cfg.maxRamGb()).isEqualTo(2);
        assertThat(cfg.maxDiskGb()).isEqualTo(5);
        assertThat(cfg.maxWallclockMin()).isEqualTo(30);
        assertThat(cfg.maxIterations())
            .isEqualTo(OpenHandsRuntimeConfig.DEFAULT_MAX_ITERATIONS);
        assertThat(cfg.stuckDetection()).isTrue();
        assertThat(cfg.defaultWorkingDir())
            .isEqualTo(OpenHandsRuntimeConfig.DEFAULT_WORKING_DIR);
    }

    @Test void config_default_docker_image_is_v1_agent_server() {
        // 2026-05-05 reconciliation: the default image is the V1
        // standalone agent-server, not the V0 runtime image. Pin it so
        // a regression to ghcr.io/all-hands-ai/openhands:* fails loudly.
        assertThat(OpenHandsRuntimeConfig.DEFAULT_DOCKER_IMAGE)
            .isEqualTo("ghcr.io/openhands/agent-server:1.19.1-python");
    }

    @Test void config_overrides_apply_via_typesafe() {
        var raw = ""
            + "wyrdsekai.coding.backends.openhands {\n"
            + "  enabled = true\n"
            + "  agent-server-url = \"http://home-server:8000\"\n"
            + "  docker-image = \"ghcr.io/openhands/agent-server:1.19.1-python\"\n"
            + "  max-ram-gb = 4\n"
            + "  max-disk-gb = 10\n"
            + "  max-wallclock-min = 60\n"
            + "  default-provider = \"anthropic\"\n"
            + "  llm-base-url = \"http://host.docker.internal:8200/v1\"\n"
            + "  llm-model = \"openai/9b-v5-q4km\"\n"
            + "  llm-api-key = \"not-required\"\n"
            + "  max-iterations = 50\n"
            + "  stuck-detection = false\n"
            + "  default-working-dir = \"/repo\"\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = OpenHandsRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.agentServerUrl()).isEqualTo("http://home-server:8000");
        assertThat(cfg.dockerImage()).isEqualTo("ghcr.io/openhands/agent-server:1.19.1-python");
        assertThat(cfg.maxRamGb()).isEqualTo(4);
        assertThat(cfg.maxDiskGb()).isEqualTo(10);
        assertThat(cfg.maxWallclockMin()).isEqualTo(60);
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.defaultProvider()).isEqualTo("anthropic");
        assertThat(cfg.llmBaseUrl()).isEqualTo("http://host.docker.internal:8200/v1");
        assertThat(cfg.llmModel()).isEqualTo("openai/9b-v5-q4km");
        assertThat(cfg.llmApiKey()).isEqualTo("not-required");
        assertThat(cfg.maxIterations()).isEqualTo(50);
        assertThat(cfg.stuckDetection()).isFalse();
        assertThat(cfg.defaultWorkingDir()).isEqualTo("/repo");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.openhands {\n"
            + "  enabled = true\n"
            + "  agent_server_url = \"http://x:8000\"\n"
            + "  docker_image = \"img:1.0\"\n"
            + "  max_ram_gb = 8\n"
            + "  llm_model = \"local-model\"\n"
            + "}";
        var cfg = OpenHandsRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.agentServerUrl()).isEqualTo("http://x:8000");
        assertThat(cfg.dockerImage()).isEqualTo("img:1.0");
        assertThat(cfg.maxRamGb()).isEqualTo(8);
        assertThat(cfg.llmModel()).isEqualTo("local-model");
    }

    @Test void config_legacy_mcp_url_is_still_read_for_backcompat() {
        // Households whose config predates the 2026-05-04 reconciliation
        // still have `mcp_url` keys. The reader honours them so upgrades
        // don't break — fresh writes go to `agent_server_url`.
        var raw = "wyrdsekai.coding.backends.openhands { mcp_url = \"http://legacy:3000/mcp\" }";
        var cfg = OpenHandsRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.agentServerUrl()).isEqualTo("http://legacy:3000/mcp");
    }

    // ─── REST create-conversation payload construction ─────────────

    @Test void create_conversation_body_has_required_v1_fields() {
        // V1 StartConversationRequest requires `workspace` and `agent`
        // (per OpenAPI v1.19.1, live-verified 2026-05-05). Pin both so a
        // contract drift fails loud.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:c", "code", "fix bug X",
            "/tmp/repo", List.of(), 0L, null);

        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.ApiKey("sk-x"));

        assertThat(body).containsKey("workspace");
        @SuppressWarnings("unchecked")
        var workspace = (Map<String, Object>) body.get("workspace");
        assertThat(workspace).containsEntry("kind", "LocalWorkspace");
        assertThat(workspace).containsEntry("working_dir", "/tmp/repo");

        assertThat(body).containsKey("agent");
        @SuppressWarnings("unchecked")
        var agent = (Map<String, Object>) body.get("agent");
        assertThat(agent).containsKey("llm");
    }

    @Test void create_conversation_body_uses_default_working_dir_when_no_hint() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:c", "code", "do x",
            null, List.of(), 0L, null);

        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.ApiKey("sk-x"));
        @SuppressWarnings("unchecked")
        var workspace = (Map<String, Object>) body.get("workspace");
        assertThat(workspace).containsEntry("working_dir",
            OpenHandsRuntimeConfig.DEFAULT_WORKING_DIR);
    }

    @Test void create_conversation_body_includes_initial_message_when_description_set() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:c", "code", "do thing",
            "/tmp", List.of(), 0L, null);

        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.ApiKey("sk-x"));
        assertThat(body).containsKey("initial_message");
        @SuppressWarnings("unchecked")
        var msg = (Map<String, Object>) body.get("initial_message");
        assertThat(msg).containsEntry("role", "user");
        assertThat(msg).containsEntry("run", false);
        @SuppressWarnings("unchecked")
        var content = (List<Map<String, Object>>) msg.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "text");
        // The user description is wrapped with ITEMS_AS_TOOLS_PREAMBLE so
        // the agent produces a -shaped artifact.
        // Assert both pieces (preamble marker + description) survive the
        // wrap rather than exact-equal — that breaks on every preamble
        // revision.
        var text = (String) content.get(0).get("text");
        assertThat(text).contains("do thing");
        assertThat(text).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(text).contains("--- TASK ---");
    }

    @Test void create_conversation_body_omits_initial_message_when_description_blank() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:c", "code", "  ",
            "/tmp", List.of(), 0L, null);
        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.ApiKey("sk-x"));
        assertThat(body).doesNotContainKey("initial_message");
    }

    @Test void create_conversation_body_includes_caps_and_policy() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var body = b.buildCreateConversationBody(
            TaskSpec.create("did:c", "code", "x"), UUID.randomUUID(),
            new AuthMode.ApiKey("sk-x"));
        assertThat(body).containsEntry("max_iterations",
            OpenHandsRuntimeConfig.DEFAULT_MAX_ITERATIONS);
        assertThat(body).containsEntry("stuck_detection", true);
        assertThat(body).containsKey("confirmation_policy");
        @SuppressWarnings("unchecked")
        var policy = (Map<String, Object>) body.get("confirmation_policy");
        assertThat(policy).containsEntry("kind", "NeverConfirm");
    }

    @Test void create_conversation_body_emits_llm_block_when_configured() {
        var d = OpenHandsRuntimeConfig.defaults();
        var cfg = new OpenHandsRuntimeConfig(
            true, d.agentServerUrl(), d.dockerImage(),
            d.maxRamGb(), d.maxDiskGb(), d.maxWallclockMin(),
            d.defaultProvider(), d.requestTimeout(),
            "http://host.docker.internal:8200/v1",
            "openai/wyrd-9b",
            "not-required",                // llmApiKey
            d.maxIterations(), d.stuckDetection(), d.defaultWorkingDir(),
            d.nativeToolCalling());
        var b = new OpenHandsBackend(cfg, oauthResolver(),
            stubFactory(emptyEvents()), () -> true);

        var body = b.buildCreateConversationBody(
            TaskSpec.create("did:c", "code", "x"), UUID.randomUUID(),
            new AuthMode.ApiKey("sk-x"));
        @SuppressWarnings("unchecked")
        var agent = (Map<String, Object>) body.get("agent");
        @SuppressWarnings("unchecked")
        var llm = (Map<String, Object>) agent.get("llm");
        assertThat(llm).containsEntry("base_url", "http://host.docker.internal:8200/v1");
        assertThat(llm).containsEntry("model", "openai/wyrd-9b");
        assertThat(llm).containsEntry("api_key", "not-required");
    }

    @Test void create_conversation_body_falls_back_to_v1_default_model() {
        // V1 requires `agent.llm.model` on every create; an empty {}
        // surfaces as a 500 with "model must be specified in LLM"
        // (live-verified 2026-05-05). When the operator hasn't set
        // llmModel, the adapter falls back to V1_DEFAULT_MODEL so the
        // request is at least well-formed.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var body = b.buildCreateConversationBody(
            TaskSpec.create("did:c", "code", "x"), UUID.randomUUID(),
            new AuthMode.ApiKey("sk-x"));
        @SuppressWarnings("unchecked")
        var agent = (Map<String, Object>) body.get("agent");
        @SuppressWarnings("unchecked")
        var llm = (Map<String, Object>) agent.get("llm");
        assertThat(llm).containsEntry("model", OpenHandsRuntimeConfig.V1_DEFAULT_MODEL);
        assertThat(llm).doesNotContainKey("base_url"); // not configured
    }

    @Test void create_conversation_body_carries_task_identity_in_tags() {
        // V1 doesn't have first-class taskId/submittedBy fields, so the
        // adapter forwards those via the conversation `tags` map. V1
        // tag-key validation is strict: keys must be lowercase
        // alphanumeric only (no '_', '-', or punctuation). Live-verified
        // 2026-05-05 against v1.19.1.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:companion:nia", "explore",
            "survey the foo subsystem", "/work", List.of("src/Foo.java"), 0L, null);

        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.ApiKey("sk-x"));

        assertThat(body).containsKey("tags");
        @SuppressWarnings("unchecked")
        var tags = (Map<String, Object>) body.get("tags");
        assertThat(tags).containsEntry("taskid", taskId.toString());
        assertThat(tags).containsEntry("tasktype", "explore");
        assertThat(tags).containsEntry("submittedby", "did:companion:nia");
        assertThat(tags).containsEntry("provider", "local");
        assertThat(tags).containsEntry("authmode", "apikey");
        // Pin the validation rule that bit us live: ALL keys in `tags`
        // must match ^[a-z0-9]+$ — a regression here surfaces as a 500
        // from the agent-server.
        for (var key : tags.keySet()) {
            assertThat(key).matches("[a-z0-9]+");
        }
    }

    @Test void create_conversation_body_oauth_path_marks_auth_mode() {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var spec = TaskSpec.create("did:c", "code", "x");
        var body = b.buildCreateConversationBody(spec, taskId, new AuthMode.OAuthSession());
        @SuppressWarnings("unchecked")
        var tags = (Map<String, Object>) body.get("tags");
        assertThat(tags).containsEntry("authmode", "oauth");
    }

    @Test void create_conversation_body_does_not_include_api_key_value() {
        // The key must travel out-of-band (via the Agent Server's own
        // env). Including it in the JSON payload would leak it on every
        // submit log line — explicitly verified.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var body = b.buildCreateConversationBody(TaskSpec.create("did:c", "code", "x"),
            taskId, new AuthMode.ApiKey("super-secret-key-must-not-appear"));

        var serialized = body.toString();
        assertThat(serialized).doesNotContain("super-secret-key-must-not-appear");
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_calling_agent_server() throws Exception {
        // Prove the client factory is not invoked when the resolver says no.
        var factoryInvoked = new boolean[]{false};
        OpenHandsBackend.AgentServerClientFactory neverCalled = (url, timeout) -> {
            factoryInvoked[0] = true;
            throw new IllegalStateException("client factory must not be called when AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "wyrd setup openhands", "no key configured");

        var b = new OpenHandsBackend(enabledDefaults(), missing, neverCalled, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(factoryInvoked[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("wyrd setup openhands");
    }

    // ─── submitTask happy path ────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_event_stream() throws Exception {
        // V1 emits ActionEvents whose tool_call.function.arguments
        // contains a `path` field; the adapter walks them to assemble
        // the SourceArtifact's file list.
        var ev1 = MAPPER.readTree("""
            {
              "id": "ev-1",
              "kind": "ActionEvent",
              "tool_name": "FileEditorTool",
              "tool_call": {
                "function": {
                  "name": "FileEditorTool",
                  "arguments": "{\\"command\\":\\"create\\",\\"path\\":\\"src/foo.java\\"}"
                }
              }
            }""");
        var ev2 = MAPPER.readTree("""
            {
              "id": "ev-2",
              "kind": "ActionEvent",
              "tool_name": "FileEditorTool",
              "tool_call": {
                "function": {
                  "name": "FileEditorTool",
                  "arguments": "{\\"command\\":\\"str_replace\\",\\"path\\":\\"src/bar.java\\"}"
                }
              }
            }""");
        var ev3 = MAPPER.readTree("""
            {
              "id": "ev-3",
              "kind": "MessageEvent",
              "source": "agent",
              "llm_message": {"content": [{"type":"text","text":"all done"}]}
            }""");

        var fixedTaskId = UUID.randomUUID();
        var stub = new StubClient(List.of(ev1, ev2, ev3), "all done");
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            (url, timeout) -> stub, () -> true);

        var spec = new TaskSpec(fixedTaskId, "did:c", "code", "do stuff",
            "/tmp/repo", List.of(), 0L, null);
        var result = b.submitTask(spec).get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("openhands");
        assertThat(result.cuConsumed()).isEqualTo(0L);
        assertThat(result.summary()).contains("OpenHands");
        assertThat(result.summary()).contains("all done");

        // Single source artifact with the two unique file paths.
        var artifacts = b.artifactsFor(fixedTaskId.toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).containsExactly("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata())
            .containsEntry("final_response", "all done");

        // Lifecycle pinned: every REST step ran exactly once.
        assertThat(stub.startCount.get()).isEqualTo(1);
        assertThat(stub.runCount.get()).isEqualTo(1);
        assertThat(stub.streamCount.get()).isEqualTo(1);
        assertThat(stub.finalCount.get()).isEqualTo(1);
        assertThat(stub.statusCount.get()).isEqualTo(1);
        assertThat(stub.deleteCount.get()).isEqualTo(1);
    }

    // ─── submitTask negative paths ────────────────────────────────

    @Test void submit_task_marks_failed_on_rest_exception() throws Exception {
        OpenHandsBackend.AgentServerClientFactory throwing = (url, timeout) ->
            new ThrowingClient(new IOException("connection refused: " + url));
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            throwing, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("Agent Server error");
        assertThat(result.summary()).contains("connection refused");
    }

    @Test void submit_task_marks_timed_out_on_event_polling_timeout() throws Exception {
        OpenHandsBackend.AgentServerClientFactory timeoutFactory = (url, timeout) ->
            new TimingOutClient();
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            timeoutFactory, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new OpenHandsRuntimeConfig(
            false, null, null, 0, 0, 0, null, null,
            null, null, null, 0, true, null, false);
        // Fail loudly if the factory is called — disabled path must short-circuit.
        OpenHandsBackend.AgentServerClientFactory neverCalled = (url, timeout) -> {
            throw new IllegalStateException("client factory must not be called when disabled");
        };
        var b = new OpenHandsBackend(disabled, oauthResolver(), neverCalled, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    @Test void submit_task_handles_empty_event_stream() throws Exception {
        // Stream closed without any actionable events — task still
        // SUCCEEDS (terminal status was 'finished') but the file list is
        // empty and metadata flags it.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        var taskId = UUID.randomUUID();
        var result = b.submitTask(new TaskSpec(taskId, "did:c", "code", "x",
            null, List.of(), 0L, null)).get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var artifacts = b.artifactsFor(taskId.toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).isEmpty();
        assertThat(src.backendMetadata().get("note"))
            .as("opaque event stream flagged in metadata")
            .isEqualTo("trace was empty or opaque");
    }

    @Test void submit_task_maps_error_terminal_status_to_FAILED() throws Exception {
        // Live-verified 2026-05-05: an LLM auth failure surfaces as
        // execution_status=error. The adapter must map that to FAILED,
        // not SUCCEEDED — even if events were collected and the
        // conversation was technically "complete".
        var stub = new StubClient(List.of(), "", "error");
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            (url, t) -> stub, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("error");
    }

    @Test void submit_task_maps_stuck_terminal_status_to_FAILED() throws Exception {
        var stub = new StubClient(List.of(), "", "stuck");
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            (url, t) -> stub, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("stuck");
    }

    @Test void submit_task_cleanup_failure_does_not_fail_the_task() throws Exception {
        // delete-conversation throwing must not flip a SUCCEEDED into a
        // FAILED — cleanup is best-effort.
        var stub = new StubClient(List.of(), "") {
            @Override public void deleteConversation(String cid) throws Exception {
                throw new IOException("delete REST 500");
            }
        };
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            (url, t) -> stub, () -> true);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    // ─── Health check ──────────────────────────────────────────────

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new OpenHandsRuntimeConfig(
            false, null, null, 0, 0, 0, null, null,
            null, null, null, 0, true, null, false);
        var b = new OpenHandsBackend(disabled, oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_true_when_rest_ok_even_without_docker() throws Exception {
        // V1 Agent Server is pip-installable; Docker absence is a soft
        // hint, not a fail-closed gate.
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> false /* docker absent */);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_rest_probe_fails() throws Exception {
        OpenHandsBackend.AgentServerClientFactory throwing = (url, timeout) ->
            new ThrowingClient(new IOException("REST unreachable"));
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            throwing, () -> true);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_true_when_docker_and_rest_ok() throws Exception {
        var b = new OpenHandsBackend(enabledDefaults(), oauthResolver(),
            stubFactory(emptyEvents()), () -> true);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static OpenHandsRuntimeConfig enabledDefaults() {
        var d = OpenHandsRuntimeConfig.defaults();
        return new OpenHandsRuntimeConfig(
            true, d.agentServerUrl(), d.dockerImage(),
            d.maxRamGb(), d.maxDiskGb(), d.maxWallclockMin(),
            d.defaultProvider(), d.requestTimeout(),
            d.llmBaseUrl(), d.llmModel(), d.llmApiKey(),
            d.maxIterations(), d.stuckDetection(), d.defaultWorkingDir(),
            d.nativeToolCalling());
    }

    /** Resolver that always returns a live OAuth session. */
    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static List<JsonNode> emptyEvents() {
        return List.of();
    }

    /** Build a client factory whose calls return the given canned events. */
    private static OpenHandsBackend.AgentServerClientFactory stubFactory(
            List<JsonNode> cannedEvents) {
        return (url, timeout) -> new StubClient(cannedEvents, "");
    }

    /**
     * Minimal Agent Server client stub: returns canned events from
     * streamEvents and tracks per-method call counts so tests can assert
     * the lifecycle (create → run → poll → final → status → delete).
     * Defaults to a {@code finished} terminal status; tests that need
     * an error/stuck path override the constructor.
     */
    private static class StubClient implements OpenHandsBackend.AgentServerClient {
        private final List<JsonNode> events;
        private final String finalResponse;
        private final String terminalStatus;
        private final AtomicReference<String> conversationId = new AtomicReference<>();

        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger runCount = new AtomicInteger();
        final AtomicInteger streamCount = new AtomicInteger();
        final AtomicInteger finalCount = new AtomicInteger();
        final AtomicInteger statusCount = new AtomicInteger();
        final AtomicInteger deleteCount = new AtomicInteger();

        StubClient(List<JsonNode> events, String finalResponse) {
            this(events, finalResponse, "finished");
        }

        StubClient(List<JsonNode> events, String finalResponse, String terminalStatus) {
            this.events = events;
            this.finalResponse = finalResponse;
            this.terminalStatus = terminalStatus;
        }

        @Override public String startConversation(Map<String, Object> body) {
            startCount.incrementAndGet();
            String id = "conv-" + UUID.randomUUID();
            conversationId.set(id);
            return id;
        }

        @Override public void runConversation(String cid) {
            runCount.incrementAndGet();
        }

        @Override public List<JsonNode> streamEvents(String cid, Duration wallclock) {
            streamCount.incrementAndGet();
            return new ArrayList<>(events);
        }

        @Override public String fetchFinalResponse(String cid) {
            finalCount.incrementAndGet();
            return finalResponse == null ? "" : finalResponse;
        }

        @Override public String fetchTerminalStatus(String cid) {
            statusCount.incrementAndGet();
            return terminalStatus;
        }

        @Override public void deleteConversation(String cid) throws Exception {
            deleteCount.incrementAndGet();
        }

        @Override public boolean probeHealth() { return true; }

        @Override public void close() { /* no-op */ }
    }

    /** Throws on every call — used to verify failure paths. */
    private static final class ThrowingClient implements OpenHandsBackend.AgentServerClient {
        private final Exception err;
        ThrowingClient(Exception err) { this.err = err; }
        @Override public String startConversation(Map<String, Object> body) throws Exception {
            throw err;
        }
        @Override public void runConversation(String cid) throws Exception { throw err; }
        @Override public List<JsonNode> streamEvents(String cid, Duration w) throws Exception {
            throw err;
        }
        @Override public String fetchFinalResponse(String cid) throws Exception { throw err; }
        @Override public String fetchTerminalStatus(String cid) throws Exception { throw err; }
        @Override public void deleteConversation(String cid) throws Exception { throw err; }
        @Override public boolean probeHealth() { return false; }
        @Override public void close() { /* no-op */ }
    }

    /** Throws TimeoutException on streamEvents — covers the wallclock path. */
    private static final class TimingOutClient implements OpenHandsBackend.AgentServerClient {
        @Override public String startConversation(Map<String, Object> body) {
            return "conv-timeout";
        }
        @Override public void runConversation(String cid) { /* ok */ }
        @Override public List<JsonNode> streamEvents(String cid, Duration w) throws Exception {
            throw new TimeoutException(
                "OpenHands event polling exceeded wallclock cap of "
                    + w.toMinutes() + " min");
        }
        @Override public String fetchFinalResponse(String cid) { return ""; }
        @Override public String fetchTerminalStatus(String cid) { return ""; }
        @Override public void deleteConversation(String cid) { /* ok */ }
        @Override public boolean probeHealth() { return true; }
        @Override public void close() { /* no-op */ }
    }
}
