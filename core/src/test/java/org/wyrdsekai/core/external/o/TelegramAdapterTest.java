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

class TelegramAdapterTest {

    private FakeCreds creds;
    private FakeHttp http;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        http = new FakeHttp();
    }

    private TelegramAdapter adapter() {
        return new TelegramAdapter(creds, http, "https://api.telegram.test/");
    }

    @Test
    void namespace_caps() {
        var a = adapter();
        assertEquals("telegram", a.namespace());
        assertEquals("telegram.bot_token", a.credentialSlot());
        assertTrue(a.capabilities().contains("send_message"));
        assertTrue(a.capabilities().contains("send_photo"));
    }

    @Test
    void send_message_happy_path() {
        creds.put("telegram.bot_token", "12345:ABC");
        http.nextBody = "{\"ok\":true,\"result\":{\"message_id\":99}}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_message",
            Map.of("chatId", "@channel", "text", "hi")));
        assertTrue(resp.success());
        // Token embedded in URL path
        assertTrue(http.lastUrl.contains("/bot12345:ABC/sendMessage"));
        assertTrue(http.lastBody.contains("\"text\":\"hi\""));
    }

    @Test
    void send_alias_send_message() {
        creds.put("telegram.bot_token", "12345:ABC");
        http.nextBody = "{\"ok\":true,\"result\":{\"message_id\":99}}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send",
            Map.of("chatId", "@channel", "text", "hi")));
        assertTrue(resp.success());
    }

    @Test
    void send_message_missing_chatid() {
        creds.put("telegram.bot_token", "12345:ABC");
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_message",
            Map.of("text", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_message",
            Map.of("chatId", "@channel", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void send_photo_requires_photo() {
        creds.put("telegram.bot_token", "12345:ABC");
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_photo",
            Map.of("chatId", "@channel")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_photo_happy_path() {
        creds.put("telegram.bot_token", "12345:ABC");
        http.nextBody = "{\"ok\":true,\"result\":{\"message_id\":42}}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_photo",
            Map.of("chatId", "@channel", "photo", "https://x/y.jpg",
                "opts", Map.of("caption", "k"))));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.endsWith("/sendPhoto"));
        assertTrue(http.lastBody.contains("\"caption\":\"k\""));
    }

    @Test
    void api_failure_returns_described_error() {
        creds.put("telegram.bot_token", "12345:ABC");
        http.nextBody = "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}";
        http.nextStatus = 400;
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_message",
            Map.of("chatId", "@nope", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("telegram_400", resp.error().code());
        assertFalse(resp.error().retryable());
        assertTrue(resp.error().message().contains("chat not found"));
    }

    @Test
    void rate_limit_marked_retryable() {
        creds.put("telegram.bot_token", "12345:ABC");
        http.nextBody = "{\"ok\":false,\"description\":\"Too Many Requests\"}";
        http.nextStatus = 429;
        var resp = adapter().invoke(AdapterRequest.of("telegram", "send_message",
            Map.of("chatId", "@x", "text", "hi")));
        assertFalse(resp.success());
        assertTrue(resp.error().retryable());
    }

    @Test
    void unknown_method() {
        creds.put("telegram.bot_token", "12345:ABC");
        var resp = adapter().invoke(AdapterRequest.of("telegram", "spam_everyone",
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
