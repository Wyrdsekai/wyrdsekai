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

class MatrixAdapterTest {

    private FakeCreds creds;
    private FakeHttp http;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        http = new FakeHttp();
    }

    private MatrixAdapter adapter() {
        return new MatrixAdapter(creds, http);
    }

    @Test
    void namespace_caps() {
        var a = adapter();
        assertEquals("matrix", a.namespace());
        assertEquals("matrix.access_token", a.credentialSlot());
        assertTrue(a.capabilities().contains("send"));
        assertTrue(a.capabilities().contains("invite"));
        assertTrue(a.capabilities().contains("join"));
    }

    @Test
    void send_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("matrix", "send",
            Map.of("roomId", "!abc:server", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void send_creds_invalid_format() {
        creds.put("matrix.access_token", "no-pipe-separator");
        var resp = adapter().invoke(AdapterRequest.of("matrix", "send",
            Map.of("roomId", "!a:s", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_invalid", resp.error().code());
    }

    @Test
    void send_missing_room() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        var resp = adapter().invoke(AdapterRequest.of("matrix", "send",
            Map.of("text", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void invite_requires_args() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        var resp = adapter().invoke(AdapterRequest.of("matrix", "invite",
            Map.of("roomId", "!abc:server")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void invite_happy_path() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        http.nextBody = "{}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("matrix", "invite",
            Map.of("roomId", "!abc:server", "userId", "@user:server")));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.contains("/_matrix/client/v3/rooms/"));
        assertTrue(http.lastUrl.endsWith("/invite"));
        assertEquals("Bearer tok", http.lastHeaders.get("Authorization"));
    }

    @Test
    void join_requires_alias() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        var resp = adapter().invoke(AdapterRequest.of("matrix", "join",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void join_happy_path() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        http.nextBody = "{\"room_id\":\"!abc:server\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("matrix", "join",
            Map.of("roomIdOrAlias", "#room:server")));
        assertTrue(resp.success());
        assertTrue(http.lastUrl.contains("/_matrix/client/v3/join/"));
    }

    @Test
    void unknown_method() {
        creds.put("matrix.access_token", "https://matrix.test|tok");
        var resp = adapter().invoke(AdapterRequest.of("matrix", "kick",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void creds_strip_trailing_slash() {
        creds.put("matrix.access_token", "https://matrix.test/|tok");
        http.nextBody = "{\"room_id\":\"!abc:server\"}";
        http.nextStatus = 200;
        var resp = adapter().invoke(AdapterRequest.of("matrix", "join",
            Map.of("roomIdOrAlias", "#room:server")));
        assertTrue(resp.success());
        assertFalse(http.lastUrl.contains("//_matrix"));
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
