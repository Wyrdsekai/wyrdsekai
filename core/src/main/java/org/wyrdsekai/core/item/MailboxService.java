package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.NotificationService;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.mail.MailAddress;
import org.wyrdsekai.core.mail.MailDirectory;
import org.wyrdsekai.core.persistence.MailStore;

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
 * Household mail: written to someone by name, kept until they read it, and announced when
 * it lands.
 *
 * <p>It was a {@code ConcurrentHashMap} with a note saying persistence would come later, and
 * that made it a queue rather than mail — a restart lost the lot. It writes to {@code world.db}
 * now (2026-09-15) whenever a node has installed a store; the map remains for tests and for
 * nodes with no database.</p>
 *
 * <p>Every identity that comes through a door here is put through {@link PersonIds#canonical}
 * first. The ssh and telnet corridors present a person's legacy login id while the web and
 * the phone present their DID, and the directory files mail under the DID — so without this
 * a letter sent from the browser was invisible from ssh (the 08-19 bond defect, in mail).
 * A companion's entity id resolves to nothing and passes through unchanged.</p>
 *
 * <p>What the scope rules mean is in {@link MailAddress}. Local mail costs no more than a
 * {@code tell}: the same tier, no grant, because a household that has to ask permission to
 * leave a note is not a household. Federated and external delivery are the next two phases
 * and say so plainly rather than accepting a message they cannot deliver.</p>
 */
public final class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);

    /** Per-spec — Tier 5 send may not exceed this many bytes (defensive cap). */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static volatile MailboxService instance;

    private final Map<String, List<Message>> byRecipient = new ConcurrentHashMap<>();
    private volatile MailStore store;
    private volatile MailDirectory directory = MailDirectory.EMPTY;
    private volatile String localZone = "home";

    public static MailboxService get() { return instance; }

    /**
     * How a companion is told a letter has landed: the server wires this to her actor. Until
     * 2026-09-16 mail to a companion sat in a table she never looked at. The notice carries the
     * sender's address and the subject; the letter itself waits in the box.
     */
    public interface CompanionNotice {
        void arrived(String entityId, String companionName, String fromAddress, String subject);
    }

    private volatile CompanionNotice companionNotice;

    public void setCompanionNotice(CompanionNotice notice) { this.companionNotice = notice; }

    public static MailboxService getOrCreate() {
        var i = instance;
        if (i == null) {
            synchronized (MailboxService.class) {
                if (instance == null) instance = new MailboxService();
                i = instance;
            }
        }
        return i;
    }

    public static void resetForTests() {
        instance = null;
    }

    public MailboxService() {
        instance = this;
    }

    /** Wire the node's store, its directory of addressable people, and its zone id. */
    public MailboxService install(MailStore store, MailDirectory directory, String localZone) {
        this.store = store;
        this.directory = directory == null ? MailDirectory.EMPTY : directory;
        if (localZone != null && !localZone.isBlank()) this.localZone = localZone.strip();
        return this;
    }

    public String localZone() { return localZone; }

    /** Who can be written to here — surfaces use it to tell a name from a subject. */
    public MailDirectory directory() { return directory; }

    /** One person, one key: the DID for anyone the resolver knows, else the id as given. */
    private static String person(String id) {
        return id == null ? null : PersonIds.canonical(id);
    }

    /** Internal record for a single message. Public-facing API converts to Map. */
    public record Message(
        String id, String from, String fromAddress, String to, String toAddress,
        String subject, String body, long timestamp, boolean read, boolean archived,
        String priority, Long expiresAt, Map<String, Object> attachments
    ) {
        /** Pre-addressing shape — identity doubles as the address. */
        public Message(String id, String from, String to, String subject, String body,
                       long timestamp, boolean read, boolean archived, String priority,
                       Long expiresAt, Map<String, Object> attachments) {
            this(id, from, from, to, to, subject, body, timestamp, read, archived,
                priority, expiresAt, attachments);
        }

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("from", from);
            m.put("fromAddress", fromAddress == null ? from : fromAddress);
            m.put("to", to);
            m.put("toAddress", toAddress == null ? to : toAddress);
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
     * Send a message. {@code to} is an address as a person would type it — a name, a
     * {@code name@zone}, or an external address — and is resolved here.
     */
    public Map<String, Object> send(String from, String to, String subject, String body,
                                      Map<String, Object> opts) {
        return sendFrom(from, null, to, subject, body, opts);
    }

    /**
     * @param fromAddress how the sender should appear to the recipient ({@code mia@neo});
     *                    null derives it from the sender's identity.
     */
    public Map<String, Object> sendFrom(String from, String fromAddress, String to,
                                          String subject, String body, Map<String, Object> opts) {
        if (from == null || from.isBlank()) return Map.of("ok", false, "error", "missing_sender");
        if (to == null || to.isBlank()) return Map.of("ok", false, "error", "missing_recipient");
        from = person(from);
        if (body == null) body = "";
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return Map.of("ok", false, "error", "body_too_large", "max_bytes", MAX_BODY_BYTES);
        }
        var addr = MailAddress.parse(to, localZone);
        if (addr == null) return Map.of("ok", false, "error", "bad_address", "address", to);
        switch (addr.scope()) {
            case EXTERNAL -> {
                return Map.of("ok", false, "error", "external_mail_not_configured",
                    "address", addr.display(), "scope", "external");
            }
            case ZONE -> {
                return Map.of("ok", false, "error", "federated_mail_not_yet",
                    "address", addr.display(), "scope", "zone", "zone", addr.zone());
            }
            default -> { }
        }
        var found = directory.byName(addr.name());
        String recipientId;
        String recipientAddress;
        if (found.isPresent()) {
            recipientId = person(found.get().identity());
            recipientAddress = found.get().name() + "@" + localZone;
        } else if (isDid(addr.name())) {
            // A DID nobody here lists — a visitor's, say. It is a real key; deliver to it.
            recipientId = addr.name();
            recipientAddress = addr.display();
        } else {
            var known = new ArrayList<String>();
            for (var r : directory.all()) known.add(r.name());
            return Map.of("ok", false, "error", "unknown_recipient",
                "address", addr.display(), "known", List.copyOf(known));
        }

        var id = UUID.randomUUID().toString();
        var ts = Instant.now().toEpochMilli();
        String priority = null;
        Long expiresAt = null;
        Map<String, Object> attachments = null;
        if (opts != null) {
            if (opts.get("priority") instanceof String ps && !ps.isBlank()) priority = ps;
            var e = opts.get("expires");
            if (e instanceof Number en) {
                expiresAt = en.longValue();
            } else if (e instanceof String es) {
                try { expiresAt = Long.parseLong(es); } catch (NumberFormatException _) { }
            }
            if (opts.get("attachments") instanceof Map<?, ?> am) {
                @SuppressWarnings("unchecked")
                var coerced = (Map<String, Object>) am;
                attachments = coerced;
            }
        }
        var senderAddress = fromAddress != null && !fromAddress.isBlank()
            ? fromAddress
            : directory.byName(from).map(r -> r.name() + "@" + localZone).orElse(from);
        var msg = new Message(id, from, senderAddress, recipientId, recipientAddress,
            subject, body, ts, false, false, priority, expiresAt, attachments);

        var s = store;
        if (s != null) {
            s.insert(new MailStore.Row(id, from, senderAddress, recipientId, recipientAddress,
                subject, body, ts, false, false, priority, expiresAt, null));
        } else {
            byRecipient.computeIfAbsent(recipientId, _ -> new ArrayList<>()).add(msg);
        }
        announce(recipientId, senderAddress, subject);
        log.debug("Mail {} -> {} (id={}, len={})", senderAddress, recipientAddress, id, body.length());
        return Map.of("ok", true, "id", id, "to", recipientAddress, "from", senderAddress);
    }

    /**
     * Old-style arrival: the recipient is told mail is here and who it is from. The message
     * itself waits in the mailbox — a notice is not a delivery.
     */
    private void announce(String recipientId, String fromAddress, String subject) {
        try {
            // The notice is for a person at a screen. A companion has no session to deliver
            // it to — the notification service would log a WARN and buffer it forever — and
            // she reads her box in-world; her own notice is the next phase.
            for (var r : directory.all()) {
                if (recipientId.equals(r.identity()) && "companion".equals(r.kind())) {
                    var notice = companionNotice;
                    if (notice != null) {
                        notice.arrived(r.identity(), r.name(), fromAddress, subject);
                    } else {
                        log.debug("Mail for companion {} waits in the box", r.name());
                    }
                    return;
                }
            }
            var notifications = NotificationService.get();
            if (notifications == null) return;
            var line = subject == null || subject.isBlank()
                ? "New mail from " + fromAddress + "."
                : "New mail from " + fromAddress + ": " + subject;
            notifications.notify(recipientId, line, "normal", "mail");
        } catch (RuntimeException e) {
            log.debug("Mail arrival notice not delivered: {}", e.toString());
        }
    }

    /**
     * Only a DID is taken on trust as a recipient. A bare {@code companion-x} or {@code u-x}
     * that the directory does not know is far more likely a typo than a person, and a letter
     * filed under a typo is a letter nobody will ever read.
     */
    private static boolean isDid(String s) {
        return s != null && s.startsWith("did:") && s.length() > 8;
    }

    /** List messages for the recipient, optionally filtered. */
    public List<Map<String, Object>> inbox(String recipient, Map<String, Object> filter) {
        if (recipient == null) return List.of();
        recipient = person(recipient);
        boolean unreadOnly = false;
        boolean includeArchived = false;
        String fromFilter = null;
        if (filter != null) {
            if (filter.get("unread") instanceof Boolean ub) unreadOnly = ub;
            if (filter.get("archived") instanceof Boolean ab) includeArchived = ab;
            if (filter.get("from") instanceof String fs && !fs.isBlank()) fromFilter = fs;
        }
        var s = store;
        if (s != null) {
            var out = new ArrayList<Map<String, Object>>();
            for (var r : s.inbox(recipient, unreadOnly, includeArchived, fromFilter,
                    Instant.now().toEpochMilli())) {
                out.add(toMessage(r).toMap());
            }
            return out;
        }
        var msgs = byRecipient.get(recipient);
        if (msgs == null || msgs.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        synchronized (msgs) {
            for (var m : msgs) {
                if (m.archived && !includeArchived) continue;
                if (unreadOnly && m.read) continue;
                if (fromFilter != null && !fromFilter.equals(m.from)
                    && !fromFilter.equals(m.fromAddress)) continue;
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
        recipient = person(recipient);
        var s = store;
        if (s != null) {
            var r = s.get(recipient, id);
            return r == null ? Map.of("error", "not_found") : toMessage(r).toMap();
        }
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
        return setFlag(recipient, id, true);
    }

    /** Archive a message. */
    public Map<String, Object> archive(String recipient, String id) {
        return setFlag(recipient, id, false);
    }

    private Map<String, Object> setFlag(String recipient, String id, boolean readFlag) {
        if (recipient == null || id == null) return Map.of("ok", false, "error", "missing_args");
        recipient = person(recipient);
        var s = store;
        if (s != null) {
            var existing = s.get(recipient, id);
            if (existing == null) return Map.of("ok", false, "error", "not_found");
            if (readFlag ? existing.read() : existing.archived()) {
                return Map.of("ok", true, "already", true);
            }
            var done = s.setFlag(recipient, id, readFlag ? "read_flag" : "archived", true);
            return done ? Map.of("ok", true) : Map.of("ok", false, "error", "not_found");
        }
        var msgs = byRecipient.get(recipient);
        if (msgs == null) return Map.of("ok", false, "error", "not_found");
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                var m = msgs.get(i);
                if (!m.id.equals(id)) continue;
                if (readFlag ? m.read : m.archived) return Map.of("ok", true, "already", true);
                msgs.set(i, new Message(m.id, m.from, m.fromAddress, m.to, m.toAddress, m.subject,
                    m.body, m.timestamp, readFlag || m.read, !readFlag || m.archived,
                    m.priority, m.expiresAt, m.attachments));
                return Map.of("ok", true);
            }
        }
        return Map.of("ok", false, "error", "not_found");
    }

    /** How many unread messages are waiting — what the mailbox shows at a glance. */
    public int unreadFor(String recipient) {
        recipient = person(recipient);
        var s = store;
        if (s != null) return s.unreadCount(recipient);
        var msgs = byRecipient.get(recipient);
        if (msgs == null) return 0;
        synchronized (msgs) {
            return (int) msgs.stream().filter(m -> !m.read && !m.archived).count();
        }
    }

    /**
     * The steward's view: who wrote to whom, when, read or not, how big. Never a subject and
     * never a body — a steward keeps the household, not its correspondence.
     */
    public List<Map<String, Object>> headers(int limit) {
        var s = store;
        if (s == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (var h : s.headers(limit)) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", h.id());
            m.put("from", h.fromAddress() == null ? h.from() : h.fromAddress());
            m.put("to", h.toAddress() == null ? h.to() : h.toAddress());
            m.put("ts", h.ts());
            m.put("read", h.read());
            m.put("archived", h.archived());
            m.put("bytes", h.bodyBytes());
            out.add(m);
        }
        return out;
    }

    /** Test convenience — inbox count regardless of state. */
    public int totalFor(String recipient) {
        recipient = person(recipient);
        var s = store;
        if (s != null) return s.inbox(recipient, false, true, null, Long.MAX_VALUE).size();
        var msgs = byRecipient.get(recipient);
        return msgs == null ? 0 : msgs.size();
    }

    private static Message toMessage(MailStore.Row r) {
        return new Message(r.id(), r.from(), r.fromAddress(), r.to(), r.toAddress(), r.subject(),
            r.body(), r.ts(), r.read(), r.archived(), r.priority(), r.expiresAt(), null);
    }
}
