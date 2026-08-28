package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OUR CodeZaikuBackend against the REAL codezaiku binary — the first
 * adapter-level live run (2026-08-15, after the by-hand runbook pass:
 * doctor → "SMOKE PASS: model emitted tool call" → hello.py).
 *
 * <p>Gates:</p>
 * <pre>
 *   WYRDSEKAI_CODEZAIKU_LIVE_BIN    path to the codezaiku executable
 *   WYRDSEKAI_CODEZAIKU_LIVE_DRIVE  OpenAI-compatible endpoint (the Ada
 *                                   drive via tunnel during the home-server-GPU
 *                                   embargo; server MUST run --jinja or
 *                                   tool calls come back as prose)
 * </pre>
 */
@Tag("live")
class CodeZaikuLiveE2ETest {

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CODEZAIKU_LIVE_BIN", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_CODEZAIKU_LIVE_DRIVE", matches = ".+")
    void real_codezaiku_run_yields_typed_artifacts() throws Exception {
        var workspace = Files.createTempDirectory("codezaiku-live");
        new ProcessBuilder("git", "init", "-q", workspace.toString()).start().waitFor();

        var config = new CodeZaikuRuntimeConfig(true,
            System.getenv("WYRDSEKAI_CODEZAIKU_LIVE_BIN"),
            System.getenv("WYRDSEKAI_CODEZAIKU_LIVE_DRIVE"),
            System.getenv().getOrDefault("WYRDSEKAI_CODEZAIKU_LIVE_MODEL",
                "wyrdsekai-3.5-9b-v5-q4km.gguf"),
            Duration.ofMinutes(10), List.of());
        var backend = new CodeZaikuBackend(config, null);

        assertThat(backend.healthCheck().get(10, TimeUnit.SECONDS))
            .as("codezaiku --version must probe healthy")
            .isTrue();

        var spec = new TaskSpec(UUID.randomUUID(), "did:live", "shell-exec",
            "Create hello.py with a function hello() returning 'hi'.",
            workspace.toString(), List.of(), 0L, null);
        var result = backend.submitTask(spec).get(10, TimeUnit.MINUTES);

        // status:"untested" on a completed run is the AGREED semantics —
        // task-level SUCCEEDED, artifact records that no oracle ran.
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var artifacts = backend.artifactsFor(spec.taskId().toString()).toList();
        assertThat(artifacts).hasSize(2);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("hello.py");
        assertThat(src.backendMetadata()).containsKey("__sibling_build");
        var build = (BuildArtifact) artifacts.get(1);
        assertThat(build.status()).isIn("untested", "success");
        // their extras ride into backendMetadata verbatim
        assertThat(build.backendMetadata()).containsKey("filesSource");

        assertThat(Files.exists(workspace.resolve("hello.py")))
            .as("the file must actually exist on disk")
            .isTrue();
    }
}
