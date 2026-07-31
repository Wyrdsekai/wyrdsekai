package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-DID dead-drop queue for cross-agent form copies.
 *
 * <p> — giving a copy is a social act, not a
 * direct mutation of the recipient's locker. When agent A gives a form to
 * agent B, the fork is dropped into this inbox against B's DID. When B's
 * CompanionActor next spawns (or at periodic tick), it drains pending
 * copies and accepts them into its own FamilyLocker via the sanctioned
 * {@code acceptCopy} entry point.</p>
 *
 * <p>Process-scoped singleton today; Trading Post + cross-zone delivery
 * replace this backing when they land.</p>
 */
public final class ForeignCopyInbox {

    private static final Logger log = LoggerFactory.getLogger(ForeignCopyInbox.class);

    /** A queued form copy destined for a specific recipient. */
    public record PendingCopy(
        ThoughtForm form,
        String senderDid,
        String recipientDid,
        FormTransfer.Intent intent,
        String note,
        Instant queuedAt
    ) {
        public PendingCopy {
            if (form == null) throw new IllegalArgumentException("form required");
            if (senderDid == null || senderDid.isBlank()) {
                throw new IllegalArgumentException("senderDid required");
            }
            if (recipientDid == null || recipientDid.isBlank()) {
                throw new IllegalArgumentException("recipientDid required");
            }
            if (intent == null) intent = FormTransfer.Intent.GIFT;
            if (queuedAt == null) queuedAt = Instant.now();
        }
    }

    // Singleton
    private static volatile ForeignCopyInbox INSTANCE;

    public static ForeignCopyInbox get() {
        var s = INSTANCE;
        if (s == null) {
            synchronized (ForeignCopyInbox.class) {
                s = INSTANCE;
                if (s == null) {
                    s = new ForeignCopyInbox();
                    INSTANCE = s;
                }
            }
        }
        return s;
    }

    public static void resetForTests() {
        synchronized (ForeignCopyInbox.class) {
            INSTANCE = null;
        }
    }

    // Keyed by recipientDid
    private final ConcurrentMap<String, Deque<PendingCopy>> queues = new ConcurrentHashMap<>();

    /** Drop a copy into the inbox for the given recipient. */
    public void deliver(PendingCopy copy) {
        queues.computeIfAbsent(copy.recipientDid(),
            k -> new ArrayDeque<>()).offer(copy);
        log.info("ForeignCopyInbox: {} → {} ({}:{})",
            copy.senderDid(), copy.recipientDid(), copy.intent(), copy.form().name());
    }

    /**
     * Drain all pending copies for a recipient. Returns the list in
     * queue order and clears the queue atomically.
     */
    public List<PendingCopy> drain(String recipientDid) {
        var queue = queues.remove(recipientDid);
        if (queue == null || queue.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    /** Peek without consuming. */
    public int pendingCount(String recipientDid) {
        var queue = queues.get(recipientDid);
        return queue == null ? 0 : queue.size();
    }

    /** First pending copy for diagnostics. */
    public Optional<PendingCopy> peek(String recipientDid) {
        var queue = queues.get(recipientDid);
        return (queue == null || queue.isEmpty())
            ? Optional.empty() : Optional.of(queue.peek());
    }
}
