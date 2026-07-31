package org.wyrdsekai.core.external.t;

import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * default {@link EmailPollListener.MailboxFetcher}
 * implementation backed by Jakarta Mail (IMAP/IMAPS).
 *
 * <p>Filter map (passed from the script's {@code world.inbound.email_watch}
 * call) carries the connection params:
 * <pre>
 *   host:     "imap.gmail.com"           // or auto-detected from username domain
 *   port:     993                          // default 993 for IMAPS, 143 for IMAP
 *   username: "alice@example.com"
 *   password: "&lt;app-password&gt;"            // resolved from The Safe by adapter caller
 *   folder:   "INBOX"                      // default INBOX
 *   ssl:      true                          // default true (IMAPS)
 *   from:     "boss@work.com"               // optional — narrow to messages from this addr
 *   subject:  "URGENT"                      // optional — narrow to subject substring
 * </pre>
 *
 * <p>Returns messages with UID strictly greater than {@code lastSeenUid}. The
 * caller persists the highest-seen UID through the registry's
 * {@code opts_json} blob so polls survive restart.</p>
 *
 * <p>The class is a stateless functional bean — one instance is shared across
 * all subscriptions; per-call cost is one IMAP connection. Long-lived
 * connections + IDLE polling are a future optimisation; for now we open +
 * close per fetch (~1s on warm DNS), which keeps the implementation
 * dependency-free and stateless.</p>
 *
 * <p><b>Security note</b>: passwords arrive in the filter map. The adapter
 * caller is expected to resolve them from The Safe via {@code CredentialResolver};
 * this fetcher does not log the password and does not retain it after the
 * call returns.</p>
 */
public final class JakartaMailFetcher implements EmailPollListener.MailboxFetcher {

    private static final Logger log = LoggerFactory.getLogger(JakartaMailFetcher.class);

    /** Singleton instance — shared across all email subscriptions. */
    public static final JakartaMailFetcher INSTANCE = new JakartaMailFetcher();

    private JakartaMailFetcher() {}

    @Override
    public List<EmailPollListener.EmailMessage> fetchSince(Map<String, Object> filter,
                                                             String lastSeenUid) {
        if (filter == null) return List.of();
        var host = stringOpt(filter, "host", null);
        var username = stringOpt(filter, "username", null);
        var password = stringOpt(filter, "password", null);
        if (username == null || password == null) {
            log.debug("email-poll: skipping — missing username/password in filter");
            return List.of();
        }
        if (host == null) {
            host = detectImapHost(username);
        }
        if (host == null) {
            log.debug("email-poll: skipping — no host and no autodetect for {}", username);
            return List.of();
        }
        var ssl = boolOpt(filter, "ssl", true);
        var port = intOpt(filter, "port", ssl ? 993 : 143);
        var folderName = stringOpt(filter, "folder", "INBOX");
        var fromFilter = stringOpt(filter, "from", null);
        var subjectFilter = stringOpt(filter, "subject", null);
        long lastSeenLong = parseUid(lastSeenUid);

        var props = new Properties();
        props.put("mail.store.protocol", ssl ? "imaps" : "imap");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", String.valueOf(ssl));
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", String.valueOf(port));
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "30000");

        var session = Session.getInstance(props);
        var out = new ArrayList<EmailPollListener.EmailMessage>();
        try (Store store = session.getStore(ssl ? "imaps" : "imap")) {
            store.connect(host, port, username, password);
            try (Folder folder = store.getFolder(folderName)) {
                folder.open(Folder.READ_ONLY);
                if (!(folder instanceof UIDFolder uf)) {
                    log.warn("email-poll: folder {} on {} is not a UIDFolder; skipping", folderName, host);
                    return List.of();
                }
                Message[] messages;
                if (lastSeenLong > 0) {
                    // Fetch all messages with UID > lastSeenLong. UIDFolder doesn't
                    // expose ">" directly, so use a generous range and filter
                    // server-side via the UID after fetch.
                    messages = uf.getMessagesByUID(lastSeenLong + 1, UIDFolder.LASTUID);
                } else {
                    // First fetch — return only the most recent ~10 to avoid
                    // dispatching a year of mail on a fresh subscription.
                    int count = folder.getMessageCount();
                    int from = Math.max(1, count - 9);
                    messages = folder.getMessages(from, count);
                }
                for (Message m : messages) {
                    long uid = uf.getUID(m);
                    if (uid <= lastSeenLong) continue;
                    if (fromFilter != null && !matchesFrom(m, fromFilter)) continue;
                    if (subjectFilter != null && !matchesSubject(m, subjectFilter)) continue;
                    out.add(toMessage(m, uid));
                }
            }
        } catch (Exception e) {
            log.warn("email-poll: fetch from {}@{} failed: {}", username, host, e.getMessage());
        }
        return out;
    }

    private static EmailPollListener.EmailMessage toMessage(Message m, long uid) throws Exception {
        var fromAddrs = m.getFrom();
        var from = fromAddrs != null && fromAddrs.length > 0
            ? ((InternetAddress) fromAddrs[0]).getAddress()
            : "";
        var toAddrs = m.getRecipients(Message.RecipientType.TO);
        var to = toAddrs != null && toAddrs.length > 0
            ? ((InternetAddress) toAddrs[0]).getAddress()
            : "";
        var subject = m.getSubject() == null ? "" : m.getSubject();
        var body = extractBody(m);
        var receivedAt = m.getReceivedDate() == null
            ? Instant.now()
            : m.getReceivedDate().toInstant();
        var headers = headersOf(m);
        return new EmailPollListener.EmailMessage(
            String.valueOf(uid), from, to, subject, body, receivedAt, headers);
    }

    private static String extractBody(Message m) {
        try {
            Object content = m.getContent();
            if (content instanceof String s) return s;
            if (content instanceof Multipart mp) return extractFromMultipart(mp);
            return String.valueOf(content);
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractFromMultipart(Multipart mp) throws Exception {
        var sb = new StringBuilder();
        for (int i = 0; i < mp.getCount(); i++) {
            var part = mp.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                sb.append(part.getContent()).append('\n');
            } else if (part.getDisposition() == null && part.isMimeType("text/*")) {
                sb.append(part.getContent()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static Map<String, String> headersOf(Message m) throws Exception {
        var out = new LinkedHashMap<String, String>();
        Enumeration<Header> hs = m.getAllHeaders();
        while (hs.hasMoreElements()) {
            var h = hs.nextElement();
            out.putIfAbsent(h.getName(), h.getValue());
        }
        return out;
    }

    private static boolean matchesFrom(Message m, String needle) throws Exception {
        var addrs = m.getFrom();
        if (addrs == null) return false;
        var lc = needle.toLowerCase();
        for (var a : addrs) {
            if (((InternetAddress) a).getAddress().toLowerCase().contains(lc)) return true;
        }
        return false;
    }

    private static boolean matchesSubject(Message m, String needle) throws Exception {
        var s = m.getSubject();
        return s != null && s.toLowerCase().contains(needle.toLowerCase());
    }

    /** Mirrors {@code EmailAlertChannel.detectSmtp} on the inbound side. */
    private static String detectImapHost(String email) {
        if (email == null || !email.contains("@")) return null;
        var domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        return switch (domain) {
            case "gmail.com", "googlemail.com" -> "imap.gmail.com";
            case "outlook.com", "hotmail.com", "live.com", "msn.com" -> "outlook.office365.com";
            case "yahoo.com", "yahoo.co.uk" -> "imap.mail.yahoo.com";
            case "icloud.com", "me.com", "mac.com" -> "imap.mail.me.com";
            case "fastmail.com", "fastmail.fm" -> "imap.fastmail.com";
            case "protonmail.com", "proton.me" -> "127.0.0.1"; // proton requires bridge
            default -> null;
        };
    }

    private static long parseUid(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static String stringOpt(Map<String, Object> m, String key, String fallback) {
        var v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int intOpt(Map<String, Object> m, String key, int fallback) {
        var v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static boolean boolOpt(Map<String, Object> m, String key, boolean fallback) {
        var v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
