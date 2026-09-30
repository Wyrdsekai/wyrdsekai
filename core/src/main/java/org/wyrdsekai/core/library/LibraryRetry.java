package org.wyrdsekai.core.library;

import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/**
 * Calls to the librarian ride out its restart.
 *
 * <p>ResearchZosho's update contract (2026-09-28, rule 7): after an update its service restarts and MCP
 * and HTTP calls on its port fail for a few seconds — retry with backoff. Only a failure to reach it is
 * retried: the request never arrived, so a retry cannot repeat anything. An answer, an error answer
 * included, is returned as it came. A librarian still unreachable after the whole backoff is remembered
 * for a minute, so a household whose librarian is not running is not made to wait on every call.</p>
 */
public final class LibraryRetry {

    /** Waits between attempts: about seven seconds in all, the length of a service restart. */
    static final long[] BACKOFF_MS = {1000, 2000, 4000};
    static final long DOWN_MEMO_MS = 60_000;

    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    static Sleeper sleeper = Thread::sleep;
    static LongSupplier clock = System::currentTimeMillis;
    private static volatile long downUntilMs = 0;

    private LibraryRetry() {}

    public static <T> T call(Callable<T> c) throws Exception {
        if (clock.getAsLong() < downUntilMs) {
            // Known down: one attempt, no waiting; an answer means it is back.
            T r = c.call();
            downUntilMs = 0;
            return r;
        }
        for (int attempt = 0; ; attempt++) {
            try {
                T r = c.call();
                downUntilMs = 0;
                return r;
            } catch (Exception e) {
                if (!unreachable(e)) throw e;
                if (attempt >= BACKOFF_MS.length) {
                    downUntilMs = clock.getAsLong() + DOWN_MEMO_MS;
                    throw e;
                }
                sleeper.sleep(BACKOFF_MS[attempt]);
            }
        }
    }

    /** The librarian could not be reached (as opposed to: it answered, possibly with an error). */
    static boolean unreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // The tool's own error answer (declined, confirm, …): whatever its message says.
            if (t instanceof McpToolException) return false;
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof NoRouteToHostException || t instanceof ClosedChannelException) {
                return true;
            }
            var m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("connection refused") || m.contains("connection reset")
                    || m.startsWith("http 502") || m.startsWith("http 503")) {
                return true;
            }
        }
        return false;
    }

    /** Test hook: forget a remembered outage. */
    static void resetForTests() { downUntilMs = 0; }
}
