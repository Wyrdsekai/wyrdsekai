package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResearchZosho's update contract, rule 7: after an update its service restarts and calls on its port
 * fail for a few seconds — retry with backoff. Only a failure to reach it is retried.
 */
class LibraryRetryTest {

    private final List<Long> slept = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void fakeTime() {
        LibraryRetry.resetForTests();
        LibraryRetry.sleeper = ms -> { slept.add(ms); now.addAndGet(ms); };
        LibraryRetry.clock = now::get;
    }

    @AfterEach
    void realTime() {
        LibraryRetry.sleeper = Thread::sleep;
        LibraryRetry.clock = System::currentTimeMillis;
        LibraryRetry.resetForTests();
    }

    private static IOException refused() {
        return new IOException("tools/call failed", new ConnectException("Connection refused"));
    }

    @Test
    void aRestartingLibrarianIsWaitedFor() throws Exception {
        var calls = new AtomicInteger();
        String answer = LibraryRetry.call(() -> {
            if (calls.incrementAndGet() < 3) throw refused();
            return "{\"entries\":[]}";
        });
        assertThat(answer).isEqualTo("{\"entries\":[]}");
        assertThat(calls).hasValue(3);
        assertThat(slept).containsExactly(1000L, 2000L);
    }

    @Test
    void anAnswerIsNotRetriedEvenAnErrorAnswer() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> LibraryRetry.call(() -> {
            calls.incrementAndGet();
            throw new IOException("tools/call failed for 'library_search': query too long");
        })).hasMessageContaining("query too long");
        assertThat(calls).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void aLibrarianThatStaysDownIsRememberedForAMinute() throws Exception {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> LibraryRetry.call(() -> { calls.incrementAndGet(); throw refused(); }))
            .isInstanceOf(IOException.class);
        assertThat(calls).hasValue(4);                 // one try and three retries
        assertThat(slept).containsExactly(1000L, 2000L, 4000L);

        // Within the minute: one attempt, no waiting.
        slept.clear(); calls.set(0);
        assertThatThrownBy(() -> LibraryRetry.call(() -> { calls.incrementAndGet(); throw refused(); }));
        assertThat(calls).hasValue(1);
        assertThat(slept).isEmpty();

        // Back within the minute: the answer comes, and the memory is cleared.
        assertThat(LibraryRetry.call(() -> "ok")).isEqualTo("ok");

        // After the minute a new outage is waited for again.
        now.addAndGet(LibraryRetry.DOWN_MEMO_MS + 1);
        calls.set(0);
        assertThat(LibraryRetry.call(() -> { if (calls.incrementAndGet() < 2) throw refused(); return "back"; }))
            .isEqualTo("back");
        assertThat(slept).containsExactly(1000L);
    }

    @Test
    void aDeclinedOrConfirmAnswerIsNeverRetriedWhateverItsMessageSays() {
        // ResearchZosho 0.5.0: the library never retries after a decline and asks hosts not to.
        // A model's words can read like an outage; the typed answer is what counts.
        for (var answer : new McpToolException[]{
                new McpToolException("library_explain", McpToolException.DECLINED, "declined",
                    "model m1 declined and said: connection refused", 200),
                new McpToolException("library_research", McpToolException.CONFIRM, "confirm",
                    "HTTP 503 is not what this is; call 988", 200)}) {
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> LibraryRetry.call(() -> { calls.incrementAndGet(); throw answer; }))
                .isSameAs(answer);
            assertThat(calls).hasValue(1);
            assertThat(slept).isEmpty();
            assertThat(LibraryRetry.unreachable(new IOException("wrapped", answer))).isFalse();
        }
    }

    @Test
    void whatCountsAsUnreachable() {
        assertThat(LibraryRetry.unreachable(refused())).isTrue();
        assertThat(LibraryRetry.unreachable(new IOException("HTTP 503: restarting"))).isTrue();
        assertThat(LibraryRetry.unreachable(new IOException("Connection reset by peer"))).isTrue();
        assertThat(LibraryRetry.unreachable(new IOException("HTTP 400: bad arguments"))).isFalse();
        assertThat(LibraryRetry.unreachable(new IllegalStateException("MCP server 'rz' not connected"))).isFalse();
    }
}
