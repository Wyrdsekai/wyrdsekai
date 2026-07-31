package org.wyrdsekai.core.external.o;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * outbound email via SMTP.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code send(to, subject, body, [opts])} — Tier 5 deliver-now.</li>
 *   <li>{@code send_html(to, subject, html, [opts])} — Tier 5 HTML form.</li>
 *   <li>{@code list_inbox([opts])} — Tier 4 IMAP fetch (optional, see notes).</li>
 *   <li>{@code mark_read(messageId)} — Tier 4 IMAP flag flip.</li>
 *   <li>{@code search(query, [opts])} — Tier 4 IMAP search.</li>
 * </ul>
 *
 * <p>Credentials slot: {@code email.smtp_pass} for SMTP send;
 * {@code email.imap_pass} for inbox reads. Host/port/user are read from the
 * credential blob as {@code host:port:user:pass} packed strings to avoid
 * spreading email config through the Safe.</p>
 *
 * <p>The adapter is intentionally stateless — every send opens and closes a
 * fresh SMTP session. That trades throughput for simplicity (and avoids the
 * connection-leak risk in a long-running JVM).</p>
 */
public final class EmailAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailAdapter.class);

    /** Lookup is injected so tests can supply a fake credential resolver. */
    private final Function<String, Optional<String>> credentials;
    /** Optional override hook for the SMTP transport (testing). */
    private final SmtpTransport transport;

    public EmailAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot), new JakartaSmtpTransport());
    }

    EmailAdapter(Function<String, Optional<String>> credentials, SmtpTransport transport) {
        this.credentials = credentials;
        this.transport = transport;
    }

    @Override public String namespace() { return "email"; }

    @Override public Set<String> capabilities() {
        return Set.of("send", "send_html", "list_inbox", "mark_read", "search", "reply");
    }

    @Override public String credentialSlot() { return "email.smtp_pass"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var args = req.args();
        return switch (req.method()) {
            case "send" -> send(args, false);
            case "send_html" -> send(args, true);
            case "reply" -> reply(args);
            case "list_inbox" -> AdapterResponse.fail("not_implemented",
                "IMAP read disabled in current build — wire ImapTransport to enable", false);
            case "mark_read" -> AdapterResponse.fail("not_implemented",
                "IMAP read disabled in current build — wire ImapTransport to enable", false);
            case "search" -> AdapterResponse.fail("not_implemented",
                "IMAP read disabled in current build — wire ImapTransport to enable", false);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    // ─── send ─────────────────────────────────────────────────

    private AdapterResponse send(Map<String, Object> args, boolean html) {
        var to = AdapterHttp.str(args, "to");
        var subject = AdapterHttp.str(args, "subject");
        var body = AdapterHttp.str(args, "body");
        if (to == null || to.isBlank()) {
            return AdapterResponse.fail("invalid_argument", "'to' is required", false);
        }
        if (subject == null) subject = "";
        if (body == null) body = "";

        var creds = resolveSmtpCredentials();
        if (creds == null) {
            return AdapterResponse.fail("credentials_missing",
                "email.smtp credentials not in Safe (slot=email.smtp_pass)", false);
        }

        var opts = AdapterHttp.asMap(args.get("opts"));
        var cc = AdapterHttp.str(opts, "cc");
        var bcc = AdapterHttp.str(opts, "bcc");
        var replyTo = AdapterHttp.str(opts, "replyTo");

        try {
            var messageId = transport.send(creds, to, cc, bcc, replyTo, subject, body, html);
            return AdapterResponse.ok(Map.of("messageId", messageId));
        } catch (Exception e) {
            log.warn("email send failed: {}", e.getMessage());
            return AdapterResponse.fail("smtp_error", e.getMessage(), true);
        }
    }

    private AdapterResponse reply(Map<String, Object> args) {
        // Without IMAP read, we can't fetch the original — surface threading
        // info from caller-provided headers instead.
        var threadId = AdapterHttp.str(args, "threadId");
        if (threadId == null || threadId.isBlank()) {
            return AdapterResponse.fail("invalid_argument", "'threadId' is required", false);
        }
        var to = AdapterHttp.str(args, "to");
        if (to == null) {
            return AdapterResponse.fail("invalid_argument",
                "'to' must be set when IMAP read is disabled", false);
        }
        var subject = AdapterHttp.str(args, "subject");
        if (subject == null || subject.isBlank()) subject = "Re: (thread " + threadId + ")";
        // Delegate to the same SMTP path with an In-Reply-To header.
        var newArgs = new LinkedHashMap<String, Object>(args);
        var opts = new LinkedHashMap<String, Object>(AdapterHttp.asMap(args.get("opts")));
        opts.put("inReplyTo", threadId);
        newArgs.put("opts", opts);
        newArgs.put("subject", subject);
        return send(newArgs, false);
    }

    // ─── credentials ──────────────────────────────────────────

    /**
     * Credential blob format (packed): {@code host:port:user:pass[:tlsMode]}
     * — tlsMode optional; defaults to STARTTLS on 587, SSL on 465.
     */
    private SmtpCredentials resolveSmtpCredentials() {
        var raw = credentials.apply("email.smtp_pass");
        if (raw.isEmpty()) return null;
        var parts = raw.get().split(":", 5);
        if (parts.length < 4) return null;
        var host = parts[0];
        int port;
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return null; }
        var user = parts[2];
        var pass = parts[3];
        var tls = parts.length >= 5 ? parts[4] : (port == 465 ? "ssl" : "starttls");
        return new SmtpCredentials(host, port, user, pass, tls);
    }

    record SmtpCredentials(String host, int port, String user, String pass, String tlsMode) {}

    /** Pluggable transport so tests can substitute a fake. */
    interface SmtpTransport {
        String send(SmtpCredentials creds, String to, String cc, String bcc,
                    String replyTo, String subject, String body, boolean html) throws Exception;
    }

    static final class JakartaSmtpTransport implements SmtpTransport {
        @Override
        public String send(SmtpCredentials creds, String to, String cc, String bcc,
                            String replyTo, String subject, String body, boolean html)
                throws MessagingException {
            var props = new Properties();
            props.put("mail.smtp.host", creds.host());
            props.put("mail.smtp.port", String.valueOf(creds.port()));
            props.put("mail.smtp.auth", "true");
            if ("ssl".equalsIgnoreCase(creds.tlsMode())) {
                props.put("mail.smtp.ssl.enable", "true");
            } else if ("starttls".equalsIgnoreCase(creds.tlsMode())) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "30000");

            var session = Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(creds.user(), creds.pass());
                }
            });

            var msg = new MimeMessage(session);
            var fromUser = creds.user();
            msg.setFrom(new InternetAddress(fromUser));
            msg.setRecipients(Message.RecipientType.TO, parseAddresses(to));
            if (cc != null && !cc.isBlank())
                msg.setRecipients(Message.RecipientType.CC, parseAddresses(cc));
            if (bcc != null && !bcc.isBlank())
                msg.setRecipients(Message.RecipientType.BCC, parseAddresses(bcc));
            if (replyTo != null && !replyTo.isBlank())
                msg.setReplyTo(parseAddresses(replyTo));
            msg.setSubject(subject == null ? "" : subject);
            if (html) {
                msg.setContent(body == null ? "" : body, "text/html; charset=utf-8");
            } else {
                msg.setText(body == null ? "" : body);
            }
            var messageId = "<" + UUID.randomUUID() + "@wyrdsekai>";
            msg.setHeader("Message-ID", messageId);
            Transport.send(msg);
            return messageId;
        }

        private static Address[] parseAddresses(String value) throws MessagingException {
            return InternetAddress.parse(value, false);
        }
    }
}
