package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-world mailbox service.
 *
 * <p>Offline-tolerant message store, scoped per-recipient DID. Messages sent
 * to entities currently online are still recorded here so the recipient can
 * read history; live tells flow through the existing AgentEventStream path
 * — this service is for the persistent inbox surface.</p>
 *
 * <p>Phase C lands the in-process implementation. Persistence comes in
 * Phase D when steward review surfaces the mailbox in the world. For now,
 * messages live in a ConcurrentHashMap keyed by recipient DID.</p>
 */
public final class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);

    /** Per-spec — Tier 5 send may not exceed this many bytes (defensive cap). */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static volatile MailboxService instance;

    public static MailboxService get() { return instance; }

    public static MailboxService getOrCreate() {
        var i = instance;
        if (i != null) return i;
        synchronized (MailboxService.class) {
            if (instance == null) instance = new MailboxService();
            return instance;
        }
    }

    /** Reset the singleton — test convenience only. */
    public static void resetForTests() {
        synchronized (MailboxService.class) {
            instance = null;
        }
    }

    private final Map<String, List<Message>> byRecipient = new ConcurrentHashMap<>();

    public MailboxService() {
        instance = this;
    }

    /**
     * Internal record for a single message. Public-facing API converts to Map.
     */
    public record Message(
        String id,
        String from,
        String to,
        String subject,
        String body,
        long timestamp,
        boolean read,
        boolean archived,
        String priority,
        Long expiresAt,
        Map<String, Object> attachments
    ) {
        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("from", from);
            m.put("to", to);
            if (subject != null) m.put("subject", subject);
            m.put("content", body);
            m.put("body", body);
            m.put("ts", timestamp);
            m.put("read", read);
            m.put("archived", archived);
            if (priority != null) m.put("priority", priority);
            if (expiresAt != null) m.put("expiresAt", expiresAt);
            if (attachments != null && !attachments.isEmpty()) m.put("attachments", attachments);
            return m;
        }
    }

    /**
     * Send a message to a recipient. Returns {ok:true, id} on success.
     * Sender + recipient are both required; opts may carry priority/expires/attachments.
     */
    public Map<String, Object> send(String from, String to, String subject, String body,
                                      Map<String, Object> opts) {
        if (from == null || from.isBlank()) {
            return Map.of("ok", false, "error", "missing_sender");
        }
        if (to == null || to.isBlank()) {
            return Map.of("ok", false, "error", "missing_recipient");
        }
        if (body == null) body = "";
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return Map.of("ok", false, "error", "body_too_large",
                "max_bytes", MAX_BODY_BYTES);
        }
        var id = UUID.randomUUID().toString();
        var ts = Instant.now().toEpochMilli();
        String priority = null;
        Long expiresAt = null;
        Map<String, Object> attachments = null;
        if (opts != null) {
            var p = opts.get("priority");
            if (p instanceof String ps && !ps.isBlank()) priority = ps;
            var e = opts.get("expires");
            if (e instanceof Number en) {
                expiresAt = en.longValue();
            } else if (e instanceof String es) {
                try { expiresAt = Long.parseLong(es); } catch (NumberFormatException _) {}
            }
            var a = opts.get("attachments");
            if (a instanceof Map<?, ?> am) {
                @SuppressWarnings("unchecked")
                var coerced = (Map<String, Object>) am;
                attachments = coerced;
            }
        }
        var msg = new Message(id, from, to, subject, body, ts, false, false,
            priority, expiresAt, attachments);
        byRecipient.computeIfAbsent(to, _ -> new ArrayList<>()).add(msg);
        log.debug("Mailbox: {} -> {} (id={}, len={})", from, to, id, body.length());
        return Map.of("ok", true, "id", id);
    }

    /** List messages for the recipient, optionally filtered. */
    public List<Map<String, Object>> inbox(String recipient, Map<String, Object> filter) {
        if (recipient == null) return List.of();
        var msgs = byRecipient.get(recipient);
        if (msgs == null || msgs.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        boolean unreadOnly = false;
        boolean includeArchived = false;
        String fromFilter = null;
        if (filter != null) {
            var u = filter.get("unread");
            if (u instanceof Boolean ub) unreadOnly = ub;
            var arch = filter.get("archived");
            if (arch instanceof Boolean ab) includeArchived = ab;
            var f = filter.get("from");
            if (f instanceof String fs && !fs.isBlank()) fromFilter = fs;
        }
        synchronized (msgs) {
            for (var m : msgs) {
                if (m.archived && !includeArchived) continue;
                if (unreadOnly && m.read) continue;
                if (fromFilter != null && !fromFilter.equals(m.from)) continue;
                out.add(m.toMap());
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) ->
            ((Number) m.getOrDefault("ts", 0L)).longValue()).reversed());
        return out;
    }

    /** Read a message by id (does not mark as read — that's a separate verb). */
    public Map<String, Object> read(String recipient, String id) {
        if (recipient == null || id == null) return Map.of("error", "missing_args");
        var msgs = byRecipient.get(recipient);
        if (msgs == null) return Map.of("error", "not_found");
        synchronized (msgs) {
            for (var m : msgs) {
                if (m.id.equals(id)) return m.toMap();
            }
        }
        return Map.of("error", "not_found");
    }

    /** Mark a message as read. */
    public Map<String, Object> markRead(String recipient, String id) {
        if (recipient == null || id == null) return Map.of("ok", false, "error", "missing_args");
        var msgs = byRecipient.get(recipient);
        if (msgs == null) return Map.of("ok", false, "error", "not_found");
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                var m = msgs.get(i);
                if (m.id.equals(id)) {
                    if (m.read) return Map.of("ok", true, "already", true);
                    msgs.set(i, new Message(m.id, m.from, m.to, m.subject, m.body, m.timestamp,
                        true, m.archived, m.priority, m.expiresAt, m.attachments));
                    return Map.of("ok", true);
                }
            }
        }
        return Map.of("ok", false, "error", "not_found");
    }

    /** Archive a message. */
    public Map<String, Object> archive(String recipient, String id) {
        if (recipient == null || id == null) return Map.of("ok", false, "error", "missing_args");
        var msgs = byRecipient.get(recipient);
        if (msgs == null) return Map.of("ok", false, "error", "not_found");
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                var m = msgs.get(i);
                if (m.id.equals(id)) {
                    if (m.archived) return Map.of("ok", true, "already", true);
                    msgs.set(i, new Message(m.id, m.from, m.to, m.subject, m.body, m.timestamp,
                        m.read, true, m.priority, m.expiresAt, m.attachments));
                    return Map.of("ok", true);
                }
            }
        }
        return Map.of("ok", false, "error", "not_found");
    }

    /** Test convenience — inbox count regardless of state. */
    public int totalFor(String recipient) {
        var msgs = byRecipient.get(recipient);
        return msgs == null ? 0 : msgs.size();
    }
}
