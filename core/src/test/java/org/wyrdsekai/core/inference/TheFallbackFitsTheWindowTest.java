package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household's report, 2026-10-05: a full turn's prompt, assembled for a large window, fell
 * back whole onto a small Ollama model and was refused with 400 on every turn. No backend had a
 * window in the router's eyes. Now each backend's window is learned at boot from the server
 * itself and from any overflow it answers with; a fallback whose window is too small is passed
 * over, the prompt is shrunk to the window of the one that is left, and a prompt larger than a
 * known window is shrunk before it is sent at all.
 */
class TheFallbackFitsTheWindowTest {

    @BeforeEach
    @AfterEach
    void clean() { BackendWindows.resetForTests(); }

    @Test
    @DisplayName("the window is read from what each server says about itself")
    void windowsAreRead() {
        assertThat(BackendWindows.windowFromLlamaProps(
            "{\"default_generation_settings\":{\"n_ctx\":8192,\"params\":{}},\"total_slots\":2}")).isEqualTo(8192);
        assertThat(BackendWindows.windowFromLlamaProps("{\"total_slots\":1}")).isZero();
        assertThat(BackendWindows.windowFromLlamaProps(null)).isZero();
        assertThat(BackendWindows.windowFromOllamaPs(
            "{\"models\":[{\"name\":\"qwen3.5:9b\",\"context_length\":4096},{\"name\":\"gpt-oss:20b\",\"context_length\":16384}]}"))
            .as("the smallest loaded window is the one to trust").isEqualTo(4096);
        assertThat(BackendWindows.windowFromOllamaPs("{\"models\":[]}")).isZero();
        assertThat(BackendWindows.windowFromOllamaShow(
            "{\"parameters\":\"num_ctx                        16384\\nstop                           \\\"<|im_end|>\\\"\",\"model_info\":{\"qwen3.context_length\":40960}}"))
            .isEqualTo(16384);
        assertThat(BackendWindows.windowFromOllamaShow("{\"parameters\":\"stop \\\"x\\\"\",\"model_info\":{\"qwen3.context_length\":40960}}"))
            .as("the model's own maximum is not the window Ollama runs it at").isZero();
    }

    @Test
    @DisplayName("a known window is remembered; nothing is remembered for zero")
    void learned() {
        BackendWindows.learned("ollama-remote", 0, "nothing");
        assertThat(BackendWindows.of("ollama-remote")).isZero();
        assertThat(BackendWindows.tooSmall("ollama-remote", 9000)).as("unknown is not too small").isFalse();
        BackendWindows.learned("ollama-remote", 4096, "/api/ps");
        assertThat(BackendWindows.of("ollama-remote")).isEqualTo(4096);
        assertThat(BackendWindows.tooSmall("ollama-remote", 9000)).isTrue();
        assertThat(BackendWindows.tooSmall("ollama-remote", 4000)).isFalse();
        BackendWindows.learned("ollama-remote", 2048, "an overflow error");
        assertThat(BackendWindows.of("ollama-remote")).as("what the server said last").isEqualTo(2048);
    }

    @Test
    @DisplayName("a request is measured the way the assembler measured it, with the reply reserved")
    void measuredLeniently() {
        var req = new InferenceClient.ChatRequest("m", List.of(
            new InferenceClient.ChatMessage("system", "x".repeat(4000)),
            new InferenceClient.ChatMessage("user", "y".repeat(400))), 1024, 0.3, null, null,
            null, null, null, null, null, null, null);
        assertThat(InferenceRouter.promptTokensLenient(req)).isEqualTo(1100 + 1 + 1024);
        assertThat(InferenceRouter.promptTokensLenient(null)).isZero();
        // The assembler keeps a full turn under 7,500 of these tokens: it fits an 8K window and not a 4K one.
        var full = new InferenceClient.ChatRequest("m", List.of(
            new InferenceClient.ChatMessage("system", "x".repeat(7400 * 4)),
            new InferenceClient.ChatMessage("user", "hello")), 512, 0.3, null, null,
            null, null, null, null, null, null, null);
        int needed = InferenceRouter.promptTokensLenient(full);
        BackendWindows.learned("voice-8k", 8192, "/props");
        BackendWindows.learned("ollama-4k", 4096, "/api/ps");
        assertThat(BackendWindows.tooSmall("voice-8k", needed)).isFalse();
        assertThat(BackendWindows.tooSmall("ollama-4k", needed)).isTrue();
        var fitted = InferenceRouter.compactToFit(full, 4096, 0, 1);
        assertThat(fitted).isNotNull();
        assertThat(InferenceRouter.promptTokensLenient(fitted)).isLessThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("the router learns from an overflow, passes over a too-small fallback, shrinks for the one left, and shrinks before sending")
    void theRouterIsWired() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java"));
        assertThat(src).contains("if (window > 0) BackendWindows.learned(failure.failedBackend(), window, \"an overflow error\");");
        assertThat(src).contains("var fallback = selectBackendExcluding(failure.failedBackend(), failure.localOnly(), needed);");
        assertThat(src).contains("var fitted = compactToFit(chatReq, w, 0, 1);");
        assertThat(src).contains("if (neededTokens > 0 && BackendWindows.tooSmall(b.name(), neededTokens)) continue;");
        assertThat(src).contains("final var req = fitted != null ? fitted : chatReq;\n        var call = backend.chatCompletion(req);");
        var cfg = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/inference/InferenceConfig.java"));
        assertThat(cfg).contains("BackendWindows.learned(name, BackendWindows.windowFromLlamaProps(readBody(url + \"/props\", 1500)), \"/props\");");
        assertThat(cfg).contains("int window = BackendWindows.windowFromOllamaPs(readBody(url + \"/api/ps\", 1500));");
        assertThat(cfg).contains("window = BackendWindows.windowFromOllamaShow(");
        // A configured backend learns it too, from the server's own word only: at boot when it is
        // up, and once more when it comes up. The configured size is never a stand-in — on the
        // household node it read 8192 while the brain ran with four times that (2026-10-09).
        assertThat(cfg).contains("BackendWindows.learned(name, BackendWindows.windowFromLlamaProps(readBody(client.getBaseUrl() + \"/props\", 1500)), \"/props\");");
        assertThat(cfg).contains("BackendWindows.learned(name, BackendWindows.windowFromLlamaProps(readBody(url + \"/props\", 1500)), \"/props\");");
        assertThat(cfg).doesNotContain("config context-size");
        assertThat(src).contains("if (result.healthy() && BackendWindows.of(result.backendName()) == 0\n"
            + "                && windowAsks.merge(result.backendName(), 1, Integer::sum) <= WINDOW_ASKS) {");
        assertThat(src).as("no up-transition is waited for: every backend starts healthy in the router's map")
            .doesNotContain("&& (prev == null || !prev) && windowAsked");
        assertThat(src).contains("static void learnWindow(InferenceBackend backend) {");
    }
}
