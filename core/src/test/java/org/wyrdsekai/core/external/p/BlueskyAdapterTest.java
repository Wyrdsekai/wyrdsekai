package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlueskyAdapterTest {

    private MockServerSupport server;
    private BlueskyAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new BlueskyAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        // The Bluesky credential format is "{handle}|{jwt}"
        MockServerSupport.wireCredential("bluesky.app_password",
            "did:plc:test|jwt-token");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void post_creates_record_with_atproto_envelope() {
        server.onJson("/xrpc/com.atproto.repo.createRecord", 200,
            "{\"uri\":\"at://x/y\",\"cid\":\"bafy123\"}");
        var resp = adapter.invoke(new AdapterRequest("bluesky", "post",
            Map.of("text", "hello bluesky"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("uri", "at://x/y")
            .containsEntry("cid", "bafy123");
        assertThat(server.lastRequest().body())
            .contains("\"text\":\"hello bluesky\"")
            .contains("\"$type\":\"app.bsky.feed.post\"")
            .contains("\"repo\":\"did:plc:test\"");
        assertThat(server.lastRequest().authorization()).isEqualTo("Bearer jwt-token");
    }

    @Test
    void post_without_text_fails() {
        var resp = adapter.invoke(new AdapterRequest("bluesky", "post",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void post_with_langs_includes_them_in_record() {
        server.onJson("/xrpc/com.atproto.repo.createRecord", 200,
            "{\"uri\":\"at://x/y\",\"cid\":\"c\"}");
        var resp = adapter.invoke(new AdapterRequest("bluesky", "post",
            Map.of("text", "ja", "langs", List.of("ja")),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().body()).contains("\"langs\":[\"ja\"]");
    }

    @Test
    void search_returns_normalised_posts() {
        server.onJson("/xrpc/app.bsky.feed.searchPosts", 200,
            "{\"posts\":[{\"uri\":\"at://1\",\"cid\":\"c1\",\"record\":{\"text\":\"hi\"}}]}");
        var resp = adapter.invoke(new AdapterRequest("bluesky", "search",
            Map.of("query", "k"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsKeys("uri", "cid", "record");
    }

    @Test
    void search_without_query_fails() {
        var resp = adapter.invoke(new AdapterRequest("bluesky", "search",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void missing_credential_returns_credentials_missing() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("bluesky", "post",
            Map.of("text", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
    }
}
