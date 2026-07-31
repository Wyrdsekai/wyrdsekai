package org.wyrdsekai.core.substrate.training;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Minimal NATS-shaped transport surface for {@link PeerDelegatedExecutor}.
 *
 * <p>Defined in {@code core} so the executor stays free of jnats imports;
 * implemented in {@code server} (where the {@code NatsBridge} dep lives)
 * by wiring these calls onto the existing NATS connection.</p>
 *
 * <p>Three primitives are enough to express the request-reply +
 * chunked-binary-shipback pattern in {@link PeerTrainingProtocol}:</p>
 * <ul>
 *   <li>{@link #requestReply} — submitter publishes its training Request
 *       and blocks until the peer's Response lands on the auto-generated
 *       reply inbox (or timeout).</li>
 *   <li>{@link #subscribe} — both sides use this: submitter to listen
 *       for adapter chunks, peer to listen for incoming training
 *       requests.</li>
 *   <li>{@link #publish} — fire-and-forget, used by the peer to push
 *       chunk data and to publish the Response back when subscribing
 *       directly (rather than via reply-inbox).</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe; the executor invokes them from
 * an actor's dispatcher thread.</p>
 */
public interface PeerTrainingTransport {

    /**
     * Process-wide accessor. Production wiring (Main.java) sets the
     * singleton once NATS is connected. Returns null if peer training
     * is not configured on this node — {@link PeerDelegatedExecutor}
     * skips cleanly when null.
     */
    final class Holder {
        private static volatile PeerTrainingTransport INSTANCE;
        private Holder() {}
        public static PeerTrainingTransport get() { return INSTANCE; }
        public static void setInstance(PeerTrainingTransport t) { INSTANCE = t; }
        public static void resetForTests() { INSTANCE = null; }
    }


    /**
     * Publish {@code payload} on {@code subject} and wait for a single
     * reply on the implicit reply inbox. Returns empty if the timeout
     * elapses without a response.
     */
    Optional<byte[]> requestReply(String subject, byte[] payload, Duration timeout);

    /** Fire-and-forget publish. Used to push adapter chunks. */
    void publish(String subject, byte[] payload);

    /**
     * Subscribe to {@code subject} (may include NATS wildcards like
     * {@code *}). The handler is invoked once per received message; the
     * raw subject is passed in case the wildcard matters (e.g. extracting
     * a chunk seq from the subject suffix).
     *
     * <p>The returned {@link Subscription} must be closed by the caller
     * when done. Closing detaches the handler and releases NATS resources.</p>
     */
    Subscription subscribe(String subject, MessageHandler handler);

    /** Handler signature — receives subject + reply-to + raw bytes.
     *
     * <p>{@code replyTo} is non-null when the message arrived as a NATS
     * request (via the submitter's {@link #requestReply}); the handler
     * publishes its response by calling {@link #publish} on this subject.
     * For non-request messages (e.g. adapter chunk broadcasts) {@code
     * replyTo} is null.</p> */
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String subject, String replyTo, byte[] payload);
    }

    /** Closeable handle returned from {@link #subscribe}. */
    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
