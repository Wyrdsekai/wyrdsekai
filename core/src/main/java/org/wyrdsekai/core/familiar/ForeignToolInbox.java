package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-DID dead-drop queue for cross-agent tool copies (SoulItems with
 * category="skill"). Mirror of {@link ForeignCopyInbox} but for tools.
 *
 * <p> — tool copies are content-addressed; this inbox
 * just queues the items for recipient pickup.</p>
 */
public final class ForeignToolInbox {

    private static final Logger log = LoggerFactory.getLogger(ForeignToolInbox.class);

    public record PendingTool(
        SoulItem item,
        String senderDid,
        String recipientDid,
        FormTransfer.Intent intent,
        String note,
        Instant queuedAt
    ) {
        public PendingTool {
            if (item == null) throw new IllegalArgumentException("item required");
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

    private static volatile ForeignToolInbox INSTANCE;

    public static ForeignToolInbox get() {
        var s = INSTANCE;
        if (s == null) {
            synchronized (ForeignToolInbox.class) {
                s = INSTANCE;
                if (s == null) {
                    s = new ForeignToolInbox();
                    INSTANCE = s;
                }
            }
        }
        return s;
    }

    public static void resetForTests() {
        synchronized (ForeignToolInbox.class) {
            INSTANCE = null;
        }
    }

    private final ConcurrentMap<String, Deque<PendingTool>> queues = new ConcurrentHashMap<>();

    public void deliver(PendingTool tool) {
        queues.computeIfAbsent(tool.recipientDid(),
            k -> new ArrayDeque<>()).offer(tool);
        log.info("ForeignToolInbox: {} → {} ({}:{})",
            tool.senderDid(), tool.recipientDid(), tool.intent(), tool.item().label());
    }

    public List<PendingTool> drain(String recipientDid) {
        var queue = queues.remove(recipientDid);
        if (queue == null || queue.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    public int pendingCount(String recipientDid) {
        var queue = queues.get(recipientDid);
        return queue == null ? 0 : queue.size();
    }
}
