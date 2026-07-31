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

class SlackAdapterTest {

    private FakeCreds creds;
    private FakeHttp http;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        http = new FakeHttp();
    }

    private SlackAdapter adapter() {
        return new SlackAdapter(creds, http, "https://slack.test/api/");
    }

    @Test
    void namespace_and_capabilities() {
        var a = adapter();
        assertEquals("slack", a.namespace());
        assertTrue(a.capabilities().contains("post_message"));
        assertTrue(a.capabilities().contains("dm"));
        assertTrue(a.capabilities().contains("react"));
        assertEquals("slack.bot_token", a.credentialSlot());
    }

    @Test
    void post_message_happy_path() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":true,\"ts\":\"123.456\",\"channel\":\"C1\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "post_message",
            Map.of("channel", "C1", "text", "hi")));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.endsWith("chat.postMessage"));
        assertTrue(http.lastBody.contains("\"channel\":\"C1\""));
        assertTrue(http.lastHeaders.containsKey("Authorization"));
        assertTrue(http.lastHeaders.get("Authorization").startsWith("Bearer "));
    }

    @Test
    void post_message_slack_logical_failure_mapped() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":false,\"error\":\"channel_not_found\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "post_message",
            Map.of("channel", "C_NOPE", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("slack_channel_not_found", resp.error().code());
        assertFalse(resp.error().retryable());
    }

    @Test
    void post_message_rate_limit_marked_retryable() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":false,\"error\":\"rate_limited\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "post_message",
            Map.of("channel", "C1", "text", "hi")));
        assertFalse(resp.success());
        assertTrue(resp.error().retryable());
    }

    @Test
    void post_message_missing_channel() {
        creds.put("slack.bot_token", "xoxb-test");
        var resp = adapter().invoke(AdapterRequest.of("slack", "post_message",
            Map.of("text", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void post_message_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("slack", "post_message",
            Map.of("channel", "C1", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void dm_user() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":true,\"ts\":\"1.0\",\"channel\":\"D1\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "dm",
            Map.of("userId", "U123", "text", "hello")));
        assertTrue(resp.success());
        assertTrue(http.lastBody.contains("\"channel\":\"U123\""));
    }

    @Test
    void list_channels_uses_get() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":true,\"channels\":[]}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "list_channels", Map.of()));
        assertTrue(resp.success());
        assertEquals("GET", http.lastVerb);
        assertTrue(http.lastUrl.contains("conversations.list"));
    }

    @Test
    void react_requires_all_three_args() {
        creds.put("slack.bot_token", "xoxb-test");
        var resp = adapter().invoke(AdapterRequest.of("slack", "react",
            Map.of("channel", "C1", "ts", "1.0")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void react_happy_path() {
        creds.put("slack.bot_token", "xoxb-test");
        http.nextBody = "{\"ok\":true}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("slack", "react",
            Map.of("channel", "C1", "ts", "1.0", "emoji", "thumbsup")));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.endsWith("reactions.add"));
    }

    @Test
    void unknown_method() {
        creds.put("slack.bot_token", "xoxb-test");
        var resp = adapter().invoke(AdapterRequest.of("slack", "destroy_workspace",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void upload_file_returns_not_implemented_structured() {
        creds.put("slack.bot_token", "xoxb-test");
        var resp = adapter().invoke(AdapterRequest.of("slack", "upload_file",
            Map.of("channel", "C1", "file", "/tmp/x.txt")));
        assertFalse(resp.success());
        assertEquals("not_implemented", resp.error().code());
    }

    // ─── Fakes ──────────────────────────────────────────────

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
