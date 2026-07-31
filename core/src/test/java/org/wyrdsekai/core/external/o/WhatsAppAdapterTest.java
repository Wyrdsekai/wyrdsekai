package org.wyrdsekai.core.external.o;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppAdapterTest {

    private FakeCreds creds;
    private FakeHttp http;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        http = new FakeHttp();
    }

    private WhatsAppAdapter adapter() {
        return new WhatsAppAdapter(creds, http);
    }

    @Test
    void namespace_caps() {
        var a = adapter();
        assertEquals("whatsapp", a.namespace());
        assertEquals("whatsapp.session", a.credentialSlot());
        assertTrue(a.capabilities().contains("send_message"));
        assertTrue(a.capabilities().contains("send_media"));
    }

    @Test
    void send_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_message",
            Map.of("jid", "+15551234@s.whatsapp.net", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void send_message_happy_path() {
        creds.put("whatsapp.session", "http://localhost:9090");
        http.nextBody = "{\"messageId\":\"abc\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_message",
            Map.of("jid", "+15551234@s.whatsapp.net", "text", "hi")));
        assertTrue(resp.success());
        assertEquals("http://localhost:9090/send", http.lastUrl);
        assertTrue(http.lastBody.contains("\"recipient\":\"+15551234@s.whatsapp.net\""));
        assertTrue(http.lastBody.contains("\"body\":\"hi\""));
    }

    @Test
    void send_alias() {
        creds.put("whatsapp.session", "http://localhost:9090");
        http.nextBody = "{\"messageId\":\"abc\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send",
            Map.of("jid", "+15551234@s.whatsapp.net", "text", "hi")));
        assertTrue(resp.success());
    }

    @Test
    void send_missing_jid() {
        creds.put("whatsapp.session", "http://localhost:9090");
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_message",
            Map.of("text", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_media_requires_path() {
        creds.put("whatsapp.session", "http://localhost:9090");
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_media",
            Map.of("jid", "+15551234@s.whatsapp.net")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_media_happy_path() {
        creds.put("whatsapp.session", "http://localhost:9090");
        http.nextBody = "{\"messageId\":\"abc\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_media",
            Map.of("jid", "+15551234@s.whatsapp.net",
                "mediaPath", "/tmp/x.png",
                "opts", Map.of("caption", "yo"))));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.endsWith("/send_media"));
        assertTrue(http.lastBody.contains("\"caption\":\"yo\""));
    }

    @Test
    void session_url_trailing_slash_stripped() {
        creds.put("whatsapp.session", "http://localhost:9090/");
        http.nextBody = "{\"messageId\":\"abc\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_message",
            Map.of("jid", "+15551234@s.whatsapp.net", "text", "hi")));
        assertTrue(resp.success());
        assertEquals("http://localhost:9090/send", http.lastUrl);
    }

    @Test
    void sidecar_5xx_marked_retryable() {
        creds.put("whatsapp.session", "http://localhost:9090");
        http.nextBody = "{\"error\":\"sidecar down\"}";
        http.nextStatus = 503;
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "send_message",
            Map.of("jid", "+15551234@s.whatsapp.net", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("http_503", resp.error().code());
        assertTrue(resp.error().retryable());
    }

    @Test
    void unknown_method() {
        creds.put("whatsapp.session", "http://localhost:9090");
        var resp = adapter().invoke(AdapterRequest.of("whatsapp", "delete_account",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    static final class FakeCreds implements Function<String, Optional<String>> {
        private final Map<String, String> values = new HashMap<>();
        void put(String k, String v) { values.put(k, v); }
        @Override public Optional<String> apply(String s) {
            return Optional.ofNullable(values.get(s));
        }
    }

    static final class FakeHttp implements SlackAdapter.HttpInvoker {
        String lastUrl, lastBody;
        Map<String, String> lastHeaders;
        String nextBody = "{}";
        int nextStatus = 200;

        @Override
        public HttpResponse<String> postJson(String url, String body, Map<String, String> headers) {
            lastUrl = url; lastBody = body; lastHeaders = headers;
            return new StubResponse(nextStatus, nextBody);
        }
        @Override
        public HttpResponse<String> get(String url, Map<String, String> headers) {
            lastUrl = url; lastHeaders = headers;
            return new StubResponse(nextStatus, nextBody);
        }
    }
}
