package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model server that is up and still loading its model is waited for, not failed.
 *
 * <p>Each start of the node lost the first two turns of every companion: the node asked while the
 * model server answered HTTP 503 "Loading model". 84 turns in a week on the household node, every
 * one of them inside a minute when the node booted (traced 2026-10-03; the server itself never
 * restarted between boots).
 */
class AModelStillLoadingIsWaitedForTest {

    @Test
    void the_servers_own_word_for_loading_is_recognised_and_nothing_else_is() {
        assertThat(InferenceRouter.isStillLoading(
            "org.wyrdsekai.core.inference.InferenceClient$InferenceException: Chat completion failed: HTTP 503 — "
                + "{\"error\":{\"message\":\"Loading model\",\"type\":\"unavailable_error\",\"code\":503}}")).isTrue();
        assertThat(InferenceRouter.isStillLoading("Chat completion failed: HTTP 503 — {\"error\":{\"message\":\"Slot busy\"}}")).isFalse();
        assertThat(InferenceRouter.isStillLoading("Chat completion failed: HTTP 400 — context exceeded while loading model")).isFalse();
        assertThat(InferenceRouter.isStillLoading(null)).isFalse();
    }

    @Test
    void the_wait_is_long_enough_for_a_large_model_and_bounded() {
        var wait = InferenceRouter.LOADING_RETRY_DELAY.multipliedBy(InferenceRouter.LOADING_RETRIES_MAX);
        assertThat(wait.toMinutes()).as("a 35B loads in a minute or two").isBetween(3L, 10L);
    }

    @Test
    void the_request_is_asked_again_on_the_same_backend_before_any_fallback() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java";
        var from = Path.of("..", rel);
        var src = Files.readString(Files.exists(from) ? from : Path.of(rel));
        int loading = src.indexOf("if (isStillLoading(failure.error()) && failure.chatReq() != null) {");
        int permanent = src.indexOf("if (isPermanentInferenceError(failure.error())) {");
        int fallback = src.indexOf("var fallback = selectBackendExcluding(failure.failedBackend(), failure.localOnly());");
        assertThat(loading).isGreaterThan(-1);
        assertThat(loading).as("before the permanent-error and fallback branches").isLessThan(permanent);
        assertThat(permanent).isLessThan(fallback);
        assertThat(src).contains("healthStatus.put(failure.failedBackend(), true);   // loading is not unhealthy");
        assertThat(src).contains(".onMessage(RetryAfterLoading.class, this::onRetryAfterLoading)");
        assertThat(src).contains("loadingRetries.remove(result.requestId());");
    }
}
