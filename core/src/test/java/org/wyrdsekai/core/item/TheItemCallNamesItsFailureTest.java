package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "LLM call timed out or failed: null" was what an item got back when the brain did not
 * answer in time (household node, 2026-10-23) — a timed-out wait carries no message. The
 * cause is named in words now.
 */
class TheItemCallNamesItsFailureTest {

    @Test
    void a_timed_out_wait_says_how_long_it_waited() {
        assertThat(ItemWorldApiProviderImpl.describeFailure(new TimeoutException(), Duration.ofSeconds(90)))
            .isEqualTo("the brain did not answer within 90 s");
    }

    @Test
    void a_wrapped_cause_is_unwrapped_and_a_bare_class_is_named() {
        assertThat(ItemWorldApiProviderImpl.describeFailure(
                new ExecutionException(new IOException("connection refused")), Duration.ofSeconds(90)))
            .isEqualTo("the brain call failed: connection refused");
        assertThat(ItemWorldApiProviderImpl.describeFailure(new IllegalStateException(), Duration.ofSeconds(90)))
            .isEqualTo("the brain call failed (IllegalStateException)");
        assertThat(ItemWorldApiProviderImpl.describeFailure(new IllegalStateException(" "), Duration.ofSeconds(90)))
            .doesNotContain("null").contains("IllegalStateException");
    }
}
