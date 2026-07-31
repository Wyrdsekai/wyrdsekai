package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MastodonAdapterTest {

    private MockServerSupport server;
    private MastodonAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new MastodonAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        MockServerSupport.wireCredential("mastodon.access_token", "test-token");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void post_round_trip_returns_id_and_url() {
        server.onJson("/api/v1/statuses", 200,
            "{\"id\":\"42\",\"url\":\"https://m.test/@a/42\"}");
        var resp = adapter.invoke(new AdapterRequest("mastodon", "post",
            Map.of("text", "hello"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("id", "42")
            .containsEntry("url", "https://m.test/@a/42");
        assertThat(server.lastRequest().authorization()).isEqualTo("Bearer test-token");
        assertThat(server.lastRequest().body()).contains("\"status\":\"hello\"");
    }

    @Test
    void post_without_text_fails_with_missing_arg() {
        var resp = adapter.invoke(new AdapterRequest("mastodon", "post",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void reply_threads_in_reply_to_id() {
        server.onJson("/api/v1/statuses", 200, "{\"id\":\"99\",\"url\":\"u\"}");
        var resp = adapter.invoke(new AdapterRequest("mastodon", "reply",
            Map.of("text", "thanks", "statusId", "abc"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().body())
            .contains("\"in_reply_to_id\":\"abc\"");
    }

    @Test
    void search_returns_grouped_buckets() {
        server.onJson("/api/v2/search", 200,
            "{\"accounts\":[{\"id\":\"1\"}],\"statuses\":[],\"hashtags\":[]}");
        var resp = adapter.invoke(new AdapterRequest("mastodon", "search",
            Map.of("query", "abc"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsKeys("accounts", "statuses", "hashtags");
    }

    @Test
    void follow_resolves_handle_through_lookup_then_follows() {
        server.onJson("/api/v1/accounts/lookup", 200, "{\"id\":\"77\"}");
        server.onJson("/api/v1/accounts/77/follow", 200,
            "{\"following\":true}");
        var resp = adapter.invoke(new AdapterRequest("mastodon", "follow",
            Map.of("account", "@neko@m.test"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
    }

    @Test
    void missing_credential_returns_credentials_missing() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("mastodon", "post",
            Map.of("text", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
        assertThat(resp.error().retryable()).isFalse();
    }

    @Test
    void unknown_method_routes_through_invoke() {
        var resp = adapter.invoke(new AdapterRequest("mastodon", "boost",
            Map.of("statusId", "1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("unknown_method");
    }

    @Test
    void http_500_is_normalised_to_retryable_failure() {
        server.onJson("/api/v1/statuses", 500, "{\"error\":\"oops\"}");
        var resp = adapter.invoke(new AdapterRequest("mastodon", "post",
            Map.of("text", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).startsWith("http_5");
        assertThat(resp.error().retryable()).isTrue();
    }
}
