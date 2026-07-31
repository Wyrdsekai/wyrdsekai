package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedditAdapterTest {

    private MockServerSupport server;
    private RedditAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new RedditAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        MockServerSupport.wireCredential("reddit.refresh_token", "tok");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void submit_self_post_returns_id_and_permalink() {
        server.onJson("/api/submit", 200,
            "{\"json\":{\"data\":{\"id\":\"x1\",\"url\":\"https://reddit.com/r/abc/x1\"}}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "post",
            Map.of("subreddit", "test", "title", "hi", "text", "body"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("id", "x1");
        assertThat(server.lastRequest().body()).contains("kind=self");
    }

    @Test
    void submit_link_post_uses_kind_link() {
        server.onJson("/api/submit", 200,
            "{\"json\":{\"data\":{\"id\":\"x2\",\"url\":\"u\"}}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "post",
            Map.of("subreddit", "s", "title", "t", "url", "https://example.com"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().body()).contains("kind=link");
    }

    @Test
    void submit_with_reddit_error_envelope_fails() {
        server.onJson("/api/submit", 200,
            "{\"json\":{\"data\":{},\"errors\":[[\"NO_TEXT\",\"need text\",\"text\"]]}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "post",
            Map.of("subreddit", "s", "title", "t"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("reddit_error");
    }

    @Test
    void comment_returns_id_and_permalink() {
        server.onJson("/api/comment", 200,
            "{\"json\":{\"data\":{\"things\":[{\"data\":{\"id\":\"c1\",\"permalink\":\"/p/c1\"}}]}}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "comment",
            Map.of("parentId", "t3_abc", "text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("id", "c1");
    }

    @Test
    void search_returns_list_of_posts() {
        server.onJson("/search.json", 200,
            "{\"data\":{\"children\":[{\"data\":{\"id\":\"a\",\"title\":\"t\",\"author\":\"u\",\"score\":5}}]}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "search",
            Map.of("query", "java"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("title", "t").containsEntry("score", 5L);
    }

    @Test
    void search_with_subreddit_uses_restricted_path() {
        server.onJson("/r/", 200, "{\"data\":{\"children\":[]}}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "search",
            Map.of("query", "k", "subreddit", "Python"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().path()).contains("/r/Python/search.json");
        assertThat(server.lastRequest().path()).contains("restrict_sr=on");
    }

    @Test
    void subscribe_posts_form_action() {
        server.onJson("/api/subscribe", 200, "{}");
        var resp = adapter.invoke(new AdapterRequest("reddit", "subscribe",
            Map.of("subreddit", "java"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().body()).contains("action=sub");
        assertThat(server.lastRequest().body()).contains("sr_name=java");
    }

    @Test
    void missing_credential_blocks() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("reddit", "search",
            Map.of("query", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
    }
}
