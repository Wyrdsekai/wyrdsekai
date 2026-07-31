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

class DiscordAdapterTest {

    private FakeCreds creds;
    private FakeHttp http;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        http = new FakeHttp();
    }

    private DiscordAdapter adapter() {
        return new DiscordAdapter(creds, http, "https://discord.test/api/v10/");
    }

    @Test
    void namespace_caps() {
        var a = adapter();
        assertEquals("discord", a.namespace());
        assertEquals("discord.bot_token", a.credentialSlot());
        assertTrue(a.capabilities().contains("send_message"));
        assertTrue(a.capabilities().contains("dm"));
    }

    @Test
    void send_message_happy_path() {
        creds.put("discord.bot_token", "fake");
        http.nextBody = "{\"id\":\"42\",\"channel_id\":\"C1\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("discord", "send_message",
            Map.of("channel", "C1", "message", "hi")));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.contains("/channels/C1/messages"));
        assertTrue(http.lastBody.contains("\"content\":\"hi\""));
        assertEquals("Bot fake", http.lastHeaders.get("Authorization"));
    }

    @Test
    void send_alias_send_message() {
        creds.put("discord.bot_token", "fake");
        http.nextBody = "{\"id\":\"42\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("discord", "send",
            Map.of("channel", "C1", "message", "hi")));
        assertTrue(resp.success());
    }

    @Test
    void send_missing_channel() {
        creds.put("discord.bot_token", "fake");
        var resp = adapter().invoke(AdapterRequest.of("discord", "send_message",
            Map.of("message", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("discord", "send_message",
            Map.of("channel", "C1", "message", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void list_channels_requires_guild() {
        creds.put("discord.bot_token", "fake");
        var resp = adapter().invoke(AdapterRequest.of("discord", "list_channels",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void list_channels_happy() {
        creds.put("discord.bot_token", "fake");
        http.nextBody = "[{\"id\":\"C1\",\"name\":\"general\"}]";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("discord", "list_channels",
            Map.of("guild", "G1")));
        assertTrue(resp.success());
        assertEquals("GET", http.lastVerb);
        assertTrue(http.lastUrl.contains("/guilds/G1/channels"));
    }

    @Test
    void unknown_method() {
        creds.put("discord.bot_token", "fake");
        var resp = adapter().invoke(AdapterRequest.of("discord", "nuke", Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void error_response_marked_retryable_on_5xx() {
        creds.put("discord.bot_token", "fake");
        http.nextBody = "{\"message\":\"server died\"}";
        http.nextStatus = 503;
        var resp = adapter().invoke(AdapterRequest.of("discord", "send_message",
            Map.of("channel", "C1", "message", "hi")));
        assertFalse(resp.success());
        assertEquals("http_503", resp.error().code());
        assertTrue(resp.error().retryable());
    }

    static final class FakeCreds implements Function<String, Optional<String>> {
        private final Map<String, String> values = new HashMap<>();
        void put(String k, String v) { values.put(k, v); }
        @Override public Optional<String> apply(String s) {
            return Optional.ofNullable(values.get(s));
        }
    }

    static final class FakeHttp implements SlackAdapter.HttpInvoker {
        String lastUrl, lastBody, lastVerb;
        Map<String, String> lastHeaders;
        String nextBody = "{}";
        int nextStatus = 200;

        @Override
        public HttpResponse<String> postJson(String url, String body, Map<String, String> headers) {
            lastUrl = url; lastBody = body; lastHeaders = headers; lastVerb = "POST";
            return new StubResponse(nextStatus, nextBody);
        }
        @Override
        public HttpResponse<String> get(String url, Map<String, String> headers) {
            lastUrl = url; lastHeaders = headers; lastVerb = "GET";
            return new StubResponse(nextStatus, nextBody);
        }
    }
}
