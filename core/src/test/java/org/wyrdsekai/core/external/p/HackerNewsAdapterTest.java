package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HackerNewsAdapterTest {

    private MockServerSupport server;
    private HackerNewsAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new HackerNewsAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());           // algolia
        adapter.setFirebaseBaseOverride(server.baseUrl());      // firebase
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void top_returns_id_list() {
        server.onJson("/v0/topstories.json", 200, "[1,2,3,4]");
        var resp = adapter.invoke(new AdapterRequest("hn", "top",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Long>) resp.data();
        assertThat(data).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void top_respects_limit() {
        server.onJson("/v0/topstories.json", 200, "[1,2,3,4,5,6]");
        var resp = adapter.invoke(new AdapterRequest("hn", "top",
            Map.of("limit", 2),
            ItemCapabilitySet.UNRESTRICTED, null));
        @SuppressWarnings("unchecked")
        var data = (List<Long>) resp.data();
        assertThat(data).containsExactly(1L, 2L);
    }

    @Test
    void search_returns_normalised_hits() {
        server.onJson("/api/v1/search", 200,
            "{\"hits\":[{\"objectID\":\"1\",\"title\":\"t\",\"author\":\"a\",\"points\":42,\"url\":\"u\"}]}");
        var resp = adapter.invoke(new AdapterRequest("hn", "search",
            Map.of("query", "wyrdsekai"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("title", "t")
            .containsEntry("points", 42L);
    }

    @Test
    void search_without_query_fails() {
        var resp = adapter.invoke(new AdapterRequest("hn", "search",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
    }

    @Test
    void comments_returns_kids_list() {
        server.onJson("/v0/item/", 200,
            "{\"id\":1,\"by\":\"a\",\"title\":\"t\",\"text\":\"body\",\"kids\":[7,8,9]}");
        var resp = adapter.invoke(new AdapterRequest("hn", "comments",
            Map.of("itemId", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        @SuppressWarnings("unchecked")
        var kids = (List<Long>) data.get("kids");
        assertThat(kids).containsExactly(7L, 8L, 9L);
    }

    @Test
    void public_reads_do_not_require_credential() {
        server.onJson("/v0/topstories.json", 200, "[10]");
        // No credential wired — should still succeed.
        var resp = adapter.invoke(new AdapterRequest("hn", "top",
            Map.of("limit", 1),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
    }
}
