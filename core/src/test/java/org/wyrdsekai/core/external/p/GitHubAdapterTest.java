package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAdapterTest {

    private MockServerSupport server;
    private GitHubAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new GitHubAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        MockServerSupport.wireCredential("github.token", "ghp_test");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void create_issue_returns_number_and_url() {
        server.onJson("/repos/o/r/issues", 200,
            "{\"number\":42,\"html_url\":\"https://github.com/o/r/issues/42\"}");
        var resp = adapter.invoke(new AdapterRequest("github", "create_issue",
            Map.of("repo", "o/r", "title", "bug", "body", "details"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("number", 42L);
        assertThat(server.lastRequest().authorization()).isEqualTo("Bearer ghp_test");
        assertThat(server.lastRequest().body()).contains("\"title\":\"bug\"");
    }

    @Test
    void create_issue_passes_labels_when_provided() {
        server.onJson("/repos/o/r/issues", 200, "{\"number\":1,\"html_url\":\"u\"}");
        adapter.invoke(new AdapterRequest("github", "create_issue",
            Map.of("repo", "o/r", "title", "t",
                "labels", List.of("bug", "p1")),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(server.lastRequest().body()).contains("\"labels\":[\"bug\",\"p1\"]");
    }

    @Test
    void create_issue_without_repo_fails() {
        var resp = adapter.invoke(new AdapterRequest("github", "create_issue",
            Map.of("title", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void add_comment_posts_body_to_comments_path() {
        server.onJson("/repos/o/r/issues/5/comments", 200,
            "{\"id\":99,\"html_url\":\"u\"}");
        var resp = adapter.invoke(new AdapterRequest("github", "comment",
            Map.of("repo", "o/r", "issueNumber", "5", "body", "+1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("commentId", 99L);
    }

    @Test
    void list_prs_returns_normalised_summary() {
        server.onJson("/repos/o/r/pulls", 200,
            "[{\"number\":1,\"title\":\"a\",\"state\":\"open\",\"html_url\":\"u\",\"user\":{\"login\":\"x\"}}]");
        var resp = adapter.invoke(new AdapterRequest("github", "list_prs",
            Map.of("repo", "o/r"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("user", "x").containsEntry("state", "open");
    }

    @Test
    void list_prs_passes_state_query() {
        server.onJson("/repos/o/r/pulls", 200, "[]");
        adapter.invoke(new AdapterRequest("github", "list_prs",
            Map.of("repo", "o/r", "state", "closed"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(server.lastRequest().path()).contains("state=closed");
    }

    @Test
    void search_code_returns_path_and_repo() {
        server.onJson("/search/code", 200,
            "{\"items\":[{\"path\":\"a.java\",\"repository\":{\"full_name\":\"o/r\"},\"html_url\":\"u\"}]}");
        var resp = adapter.invoke(new AdapterRequest("github", "search_code",
            Map.of("query", "Llama"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("path", "a.java")
            .containsEntry("repo", "o/r");
    }

    @Test
    void create_pr_returns_number_and_url() {
        server.onJson("/repos/o/r/pulls", 200,
            "{\"number\":7,\"html_url\":\"https://github.com/o/r/pull/7\"}");
        var resp = adapter.invoke(new AdapterRequest("github", "create_pr",
            Map.of("repo", "o/r", "head", "feat", "base", "main", "title", "T"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("number", 7L);
    }

    @Test
    void create_pr_without_base_fails() {
        var resp = adapter.invoke(new AdapterRequest("github", "create_pr",
            Map.of("repo", "o/r", "head", "h", "title", "t"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void missing_credential_returns_credentials_missing() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("github", "list_prs",
            Map.of("repo", "o/r"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
    }
}
