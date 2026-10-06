package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that ran out of our own wait does not put its backend on the bench.
 *
 * <p>Every failure used to mark the backend unhealthy for the next thirty seconds, a client
 * timeout included. On a slow brain (a household running the full model on a CPU, 2026-10-05)
 * one long turn then sent the next thirty seconds of turns to the small voice model with a
 * prompt made for a larger window, and those failed with context-overflow 400s. A backend that
 * is still working is not a backend that is down; only an answer that says so benches it.
 */
class ATimeoutDoesNotBenchTheBackendTest {

    @Test
    void our_own_wait_running_out_is_told_apart_from_a_backend_that_failed() {
        assertThat(InferenceRouter.isClientTimeout("java.net.http.HttpTimeoutException: request timed out")).isTrue();
        assertThat(InferenceRouter.isClientTimeout("Chat completion failed: request timed out")).isTrue();
        assertThat(InferenceRouter.isClientTimeout("Chat completion timed out after 180s")).isTrue();
        assertThat(InferenceRouter.isClientTimeout("Chat completion failed: HTTP 400 — context exceeded")).isFalse();
        assertThat(InferenceRouter.isClientTimeout("Chat completion failed: HTTP 503 — Loading model")).isFalse();
        assertThat(InferenceRouter.isClientTimeout("java.net.ConnectException: Connection refused")).isFalse();
        assertThat(InferenceRouter.isClientTimeout(null)).isFalse();
    }

    @Test
    void only_a_failure_that_was_not_our_timeout_benches_the_backend() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java";
        var from = Path.of("..", rel);
        var src = Files.readString(Files.exists(from) ? from : Path.of(rel));
        int failure = src.indexOf("private Behavior<Command> onInferFailure(InferFailure failure) {");
        int gate = src.indexOf("boolean clientTimeout = isClientTimeout(failure.error());");
        int bench = src.indexOf("if (!clientTimeout) healthStatus.put(failure.failedBackend(), false);");
        assertThat(failure).isGreaterThan(-1);
        assertThat(gate).as("the failure is classified first").isGreaterThan(failure);
        assertThat(bench).as("and benched only when it was not our own wait").isGreaterThan(gate);
        var body = src.substring(failure, src.indexOf("\n    }\n", failure));
        assertThat(body).as("no unconditional bench remains in the failure handler")
            .doesNotContain("\n        healthStatus.put(failure.failedBackend(), false);");
    }
}
