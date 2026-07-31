package org.wyrdsekai.between.training;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.training.PeerTrainingTransport;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adapts {@link PeerTrainingTransport} onto the live NATS connection
 * managed by {@code NatsBridge}. Implementation lives in the server
 * module so {@code core} stays free of jnats.
 *
 * <p>Maps:</p>
 * <ul>
 *   <li>{@link PeerTrainingTransport#requestReply} → {@code conn.request(...)}
 *       which uses NATS's auto-generated reply inbox.</li>
 *   <li>{@link PeerTrainingTransport#publish} → {@code conn.publish(...)}.</li>
 *   <li>{@link PeerTrainingTransport#subscribe} → a per-subscription
 *       {@link Dispatcher} so each subscription gets its own background
 *       thread. {@link Subscription#close} drains + closes the dispatcher.</li>
 * </ul>
 *
 * <p>Threading: jnats's dispatcher invokes handlers on its own thread.
 * The {@code MessageHandler} interface is expected to be non-blocking
 * or quick; long work should be punted onto a separate executor by the
 * caller (e.g. {@code TrainingPeerService}'s training pool).</p>
 */
public final class NatsPeerTrainingTransport implements PeerTrainingTransport {

    private static final Logger log = LoggerFactory.getLogger(NatsPeerTrainingTransport.class);

    private final Connection conn;

    public NatsPeerTrainingTransport(Connection conn) {
        if (conn == null) throw new IllegalArgumentException("conn must not be null");
        this.conn = conn;
    }

    @Override
    public Optional<byte[]> requestReply(String subject, byte[] payload, Duration timeout) {
        if (conn.getStatus() != Connection.Status.CONNECTED) {
            log.warn("NATS not connected — peer training requestReply failed");
            return Optional.empty();
        }
        try {
            // jnats request returns CompletableFuture<Message>. Block with timeout.
            var future = conn.request(subject, payload);
            var msg = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(msg).map(Message::getData);
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("requestReply failed on {}: {}", subject, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void publish(String subject, byte[] payload) {
        if (conn.getStatus() != Connection.Status.CONNECTED) return;
        conn.publish(subject, payload);
    }

    @Override
    public Subscription subscribe(String subject, MessageHandler handler) {
        var dispatcher = conn.createDispatcher(msg -> {
            try {
                handler.onMessage(msg.getSubject(), msg.getReplyTo(), msg.getData());
            } catch (Exception e) {
                log.warn("Subscription handler threw on {}: {}",
                    msg.getSubject(), e.getMessage());
            }
        });
        dispatcher.subscribe(subject);
        return () -> {
            try {
                conn.closeDispatcher(dispatcher);
            } catch (Exception e) {
                log.debug("Failed to close dispatcher for {}: {}",
                    subject, e.getMessage());
            }
        };
    }
}
