package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XAdapterTest {

    private MockServerSupport server;
    private XAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new XAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        MockServerSupport.wireCredential("x.bearer_token", "x-tok");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
        adapter.clearRateLimitForTests();
    }

    @Test
    void post_returns_id_and_text() {
        server.onJson("/2/tweets", 200,
            "{\"data\":{\"id\":\"1\",\"text\":\"hi\"}}");
        var resp = adapter.invoke(new AdapterRequest("x", "post",
            Map.of("text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("id", "1");
    }

    @Test
    void post_thread_includes_reply_in_reply_to_tweet_id() {
        server.onJson("/2/tweets", 200, "{\"data\":{\"id\":\"2\",\"text\":\"x\"}}");
        var resp = adapter.invoke(new AdapterRequest("x", "post",
            Map.of("text", "thread", "replyTo", "999"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().body())
            .contains("\"in_reply_to_tweet_id\":\"999\"");
    }

    @Test
    void search_returns_tweets_list() {
        server.onJson("/2/tweets/search/recent", 200,
            "{\"data\":[{\"id\":\"1\",\"text\":\"a\"},{\"id\":\"2\",\"text\":\"b\"}]}");
        var resp = adapter.invoke(new AdapterRequest("x", "search",
            Map.of("query", "tag"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(2);
    }

    @Test
    void dm_posts_to_conversations_endpoint() {
        server.onJson("/2/dm_conversations/with/U/messages", 200,
            "{\"data\":{\"dm_event_id\":\"dm1\"}}");
        var resp = adapter.invoke(new AdapterRequest("x", "dm",
            Map.of("recipient", "U", "text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        assertThat(server.lastRequest().path())
            .contains("/2/dm_conversations/with/U/messages");
    }

    @Test
    void dm_without_recipient_fails() {
        var resp = adapter.invoke(new AdapterRequest("x", "dm",
            Map.of("text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void missing_credential_blocks() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("x", "post",
            Map.of("text", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
    }

    @Test
    void unknown_method_fails() {
        var resp = adapter.invoke(new AdapterRequest("x", "delete_account",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("unknown_method");
    }
}
