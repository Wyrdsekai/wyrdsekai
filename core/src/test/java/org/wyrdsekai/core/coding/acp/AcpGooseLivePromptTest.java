package org.wyrdsekai.core.coding.acp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FULL live ACP v1 prompt turn against real Goose driving the household's
 * drive-trained 9B — the production coding pairing, per operator's call
 * 2026-08-15 ("the coding backend and tool use is what is needed…the 9b").
 * A live turn only proves the protocol when the model actually MAKES tool
 * calls; the 9B does, reliably.
 *
 * <p>Gates (all required):</p>
 * <pre>
 *   WYRDSEKAI_ACP_LIVE_AGENT        path to the goose binary
 *   WYRDSEKAI_ACP_LIVE_OPENAI_HOST  BARE host of an OpenAI-compatible
 *                                   server (goose appends /v1 itself —
 *                                   the 2026-07-21 /v1/v1 trap)
 *   WYRDSEKAI_ACP_LIVE_MODEL        model id for GOOSE_MODEL
 * </pre>
 *
 * <p>GPU note: the endpoint should live on a box cleared for GPU work
 * (gpu-host Ada during the 2026-08-15 home-server-GPU embargo) — this test
 * itself spawns no local inference.</p>
 */
@Tag("live")
class AcpGooseLivePromptTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_ACP_LIVE_AGENT", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_ACP_LIVE_OPENAI_HOST", matches = ".+")
    void full_turn_streams_updates_and_touches_the_workspace() throws Exception {
        var agent = System.getenv("WYRDSEKAI_ACP_LIVE_AGENT");
        var host = System.getenv("WYRDSEKAI_ACP_LIVE_OPENAI_HOST");
        var model = System.getenv().getOrDefault(
            "WYRDSEKAI_ACP_LIVE_MODEL", "wyrdsekai-3.5-9b-v5-q4km.gguf");
        var workspace = Files.createTempDirectory("acp-live-turn");

        var pb = new ProcessBuilder(agent, "acp");
        var env = new HashMap<String, String>();
        env.put("GOOSE_PROVIDER", "openai");
        env.put("OPENAI_HOST", host.replaceAll("/v1/?$", ""));
        env.put("OPENAI_API_KEY", "not-required");
        env.put("GOOSE_MODEL", model);
        pb.environment().putAll(env);
        pb.directory(workspace.toFile());
        var process = pb.start();
        Thread.ofVirtual().start(() -> {
            try (var err = process.getErrorStream()) { err.readAllBytes(); }
            catch (Exception ignored) { }
        });

        try (var conn = new AcpConnection(
                process.getInputStream(), process.getOutputStream());
             var client = new AcpClient(conn, null)) {
            client.initialize("wyrdsekai-live-turn", "0");
            assertThat(client.negotiatedVersion()).isEqualTo(1);

            var sessionId = client.newSession(workspace.toString());
            var response = client.prompt(sessionId,
                "Create a file named hello.txt in the current directory "
                    + "containing exactly the single word: loomlight",
                Duration.ofMinutes(5));

            assertThat(response.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(client.updates())
                .as("a real turn must stream session/update notifications")
                .isNotEmpty();

            var hello = workspace.resolve("hello.txt");
            assertThat(Files.exists(hello))
                .as("the 9B should have used its file tools; updates seen: "
                    + client.updates().stream()
                        .map(u -> u.path("update").path("sessionUpdate").asText("?"))
                        .distinct().toList())
                .isTrue();
            assertThat(Files.readString(hello)).contains("loomlight");
        } finally {
            process.destroy();
        }
    }
}
