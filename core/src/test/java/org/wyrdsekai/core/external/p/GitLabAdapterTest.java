package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabAdapterTest {

    private MockServerSupport server;
    private GitLabAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new GitLabAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        MockServerSupport.wireCredential("gitlab.token", "glpat_test");
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void create_issue_with_numeric_project_id() {
        server.onJson("/api/v4/projects/42/issues", 200,
            "{\"iid\":7,\"web_url\":\"https://gitlab.com/o/r/-/issues/7\"}");
        var resp = adapter.invoke(new AdapterRequest("gitlab", "create_issue",
            Map.of("project", "42", "title", "T", "body", "B"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("iid", 7L);
        assertThat(server.lastRequest().authorization()).isEqualTo("glpat_test");
    }

    @Test
    void create_issue_with_named_project_url_encodes_path() {
        server.onJson("/api/v4/projects/", 200, "{\"iid\":1,\"web_url\":\"u\"}");
        adapter.invoke(new AdapterRequest("gitlab", "create_issue",
            Map.of("project", "namespace/repo", "title", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        // The wire path encodes the slash in project — the JDK HTTP server
        // normalises path before exposing it via getRequestURI().getPath(),
        // so we inspect the raw URI to verify the encoding was applied.
        assertThat(server.lastRequest().rawUri())
            .contains("namespace%2Frepo");
    }

    @Test
    void add_comment_posts_to_notes_path() {
        server.onJson("/api/v4/projects/1/issues/2/notes", 200,
            "{\"id\":555}");
        var resp = adapter.invoke(new AdapterRequest("gitlab", "comment",
            Map.of("project", "1", "issueIid", "2", "body", "lgtm"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("noteId", 555L);
    }

    @Test
    void list_mrs_returns_normalised_summary() {
        server.onJson("/api/v4/projects/1/merge_requests", 200,
            "[{\"iid\":3,\"title\":\"x\",\"state\":\"opened\",\"web_url\":\"u\",\"author\":{\"username\":\"a\"}}]");
        var resp = adapter.invoke(new AdapterRequest("gitlab", "list_mrs",
            Map.of("project", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("iid", 3L)
            .containsEntry("author", "a");
    }

    @Test
    void list_mrs_state_default_is_opened() {
        server.onJson("/api/v4/projects/1/merge_requests", 200, "[]");
        adapter.invoke(new AdapterRequest("gitlab", "list_mrs",
            Map.of("project", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(server.lastRequest().path()).contains("state=opened");
    }

    @Test
    void create_issue_without_title_fails() {
        var resp = adapter.invoke(new AdapterRequest("gitlab", "create_issue",
            Map.of("project", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void missing_credential_blocks() {
        MockServerSupport.clearCredentials();
        var resp = adapter.invoke(new AdapterRequest("gitlab", "list_mrs",
            Map.of("project", "1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("credentials_missing");
    }
}
