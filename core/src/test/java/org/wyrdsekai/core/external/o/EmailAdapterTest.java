package org.wyrdsekai.core.external.o;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EmailAdapterTest {

    private FakeCreds creds;
    private FakeTransport transport;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        transport = new FakeTransport();
    }

    private EmailAdapter adapter() {
        return new EmailAdapter(creds, transport);
    }

    @Test
    void namespace_and_capabilities() {
        var a = adapter();
        assertEquals("email", a.namespace());
        assertTrue(a.capabilities().contains("send"));
        assertTrue(a.capabilities().contains("send_html"));
        assertEquals("email.smtp_pass", a.credentialSlot());
    }

    @Test
    void send_happy_path() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret:starttls");
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("to", "bob@example.com", "subject", "hi", "body", "hello")));
        assertTrue(resp.success(), () -> resp.error() == null
            ? "<no error>" : resp.error().message());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("messageId"));
        assertEquals("bob@example.com", transport.lastTo);
        assertEquals("hi", transport.lastSubject);
        assertFalse(transport.lastHtml);
    }

    @Test
    void send_html_routes_html_path() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret");
        var resp = adapter().invoke(AdapterRequest.of("email", "send_html",
            Map.of("to", "bob@example.com", "subject", "hello",
                "body", "<p>hi</p>")));
        assertTrue(resp.success());
        assertTrue(transport.lastHtml);
    }

    @Test
    void send_missing_credentials() {
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("to", "x@x", "subject", "y", "body", "z")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void send_blank_to_rejected() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret");
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("subject", "y", "body", "z")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_smtp_failure_marked_retryable() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret");
        transport.failNext = new RuntimeException("boom");
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("to", "bob@example.com", "subject", "y", "body", "z")));
        assertFalse(resp.success());
        assertEquals("smtp_error", resp.error().code());
        assertTrue(resp.error().retryable());
    }

    @Test
    void invalid_credentials_blob_returns_missing() {
        creds.put("email.smtp_pass", "garbage");
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("to", "bob@example.com", "subject", "y", "body", "z")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void list_inbox_not_implemented() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret");
        var resp = adapter().invoke(AdapterRequest.of("email", "list_inbox", Map.of()));
        // list_inbox doesn't need creds before bailing — but we still fail cleanly.
        assertFalse(resp.success());
        assertEquals("not_implemented", resp.error().code());
    }

    @Test
    void unknown_method() {
        var resp = adapter().invoke(AdapterRequest.of("email", "delete_inbox", Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void cc_bcc_replyto_threaded_through() {
        creds.put("email.smtp_pass", "smtp.example.com:587:alice:secret");
        var resp = adapter().invoke(AdapterRequest.of("email", "send",
            Map.of("to", "bob@example.com",
                "subject", "y", "body", "z",
                "opts", Map.of(
                    "cc", "carol@example.com",
                    "bcc", "dave@example.com",
                    "replyTo", "noreply@example.com"))));
        assertTrue(resp.success());
        assertEquals("carol@example.com", transport.lastCc);
        assertEquals("dave@example.com", transport.lastBcc);
        assertEquals("noreply@example.com", transport.lastReplyTo);
    }

    // ─── Fakes ──────────────────────────────────────────────

    static final class FakeCreds implements Function<String, Optional<String>> {
        private final Map<String, String> values = new HashMap<>();
        void put(String k, String v) { values.put(k, v); }
        @Override public Optional<String> apply(String s) {
            return Optional.ofNullable(values.get(s));
        }
    }

    static final class FakeTransport implements EmailAdapter.SmtpTransport {
        String lastTo, lastCc, lastBcc, lastReplyTo, lastSubject, lastBody;
        boolean lastHtml;
        RuntimeException failNext;
        AtomicReference<EmailAdapter.SmtpCredentials> lastCreds = new AtomicReference<>();

        @Override
        public String send(EmailAdapter.SmtpCredentials creds, String to, String cc, String bcc,
                            String replyTo, String subject, String body, boolean html) {
            if (failNext != null) {
                var e = failNext; failNext = null; throw e;
            }
            lastCreds.set(creds);
            lastTo = to; lastCc = cc; lastBcc = bcc; lastReplyTo = replyTo;
            lastSubject = subject; lastBody = body; lastHtml = html;
            return "<test-id@wyrdsekai>";
        }
    }
}
