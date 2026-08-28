package org.wyrdsekai.core.coding.acp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Our ACP v1 client against REAL {@code codezaiku acp} — the second live
 * agent after Goose, and the one that exercises the {@code _meta.codezaiku}
 * result-document channel (their ACP carries the same JSON the CLI prints,
 * attached to the PromptResponse — one shape, two surfaces).
 *
 * <p>Gates: same as {@code CodeZaikuLiveE2ETest} —
 * {@code WYRDSEKAI_CODEZAIKU_LIVE_BIN} + {@code WYRDSEKAI_CODEZAIKU_LIVE_DRIVE}.</p>
 */
@Tag("live")
class AcpCodeZaikuLiveTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CODEZAIKU_LIVE_BIN", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CODEZAIKU_LIVE_DRIVE", matches = ".+")
    void acp_turn_carries_the_result_document_in_meta() throws Exception {
        var workspace = Files.createTempDirectory("codezaiku-acp-live");
        new ProcessBuilder("git", "init", "-q", workspace.toString()).start().waitFor();

        var pb = new ProcessBuilder(
            System.getenv("WYRDSEKAI_CODEZAIKU_LIVE_BIN"), "acp");
        var env = new HashMap<String, String>();
        env.put("CODEZAIKU_DRIVE", System.getenv("WYRDSEKAI_CODEZAIKU_LIVE_DRIVE"));
        env.put("CODEZAIKU_MODEL", System.getenv().getOrDefault(
            "WYRDSEKAI_CODEZAIKU_LIVE_MODEL", "wyrdsekai-3.5-9b-v5-q4km.gguf"));
        pb.environment().putAll(env);
        var process = pb.start();
        Thread.ofVirtual().start(() -> {
            try (var err = process.getErrorStream()) { err.readAllBytes(); }
            catch (Exception ignored) { }
        });

        try (var conn = new AcpConnection(
                process.getInputStream(), process.getOutputStream());
             var client = new AcpClient(conn, null)) {
            client.initialize("wyrdsekai-acp-live", "0");
            assertThat(client.negotiatedVersion()).isEqualTo(1);

            var sessionId = client.newSession(workspace.toString());
            var response = client.prompt(sessionId,
                "Create hello.py with a function hello() returning 'hi'.",
                Duration.ofMinutes(10));

            assertThat(response.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(client.updates())
                .as("codezaiku acp must stream tool_call updates")
                .isNotEmpty();

            var meta = response.path("_meta").path("codezaiku");
            assertThat(meta.isObject())
                .as("result document must ride at _meta.codezaiku; response: "
                    + response)
                .isTrue();
            assertThat(meta.path("files").toString()).contains("hello.py");
            assertThat(meta.path("status").asText()).isIn("untested", "success");

            assertThat(Files.exists(workspace.resolve("hello.py"))).isTrue();
        } finally {
            process.destroy();
        }
    }
}
