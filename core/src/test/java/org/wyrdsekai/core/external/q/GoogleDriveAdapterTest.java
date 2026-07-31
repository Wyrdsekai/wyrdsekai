package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class GoogleDriveAdapterTest {

    private GoogleDriveAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new GoogleDriveAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("google.oauth_token", "drive_token");
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_gdrive() {
        assertEquals("gdrive", adapter.namespace());
    }

    @Test
    void list_without_folder_lists_root() {
        adapter.invoke(req("gdrive", "list", Map.of()));
        assertEquals("GET", rec.method.get());
        assertTrue(rec.url.get().contains("/drive/v3/files"));
    }

    @Test
    void list_with_folder_filters_by_parent() {
        adapter.invoke(req("gdrive", "list", Map.of("folderId", "abcd")));
        var u = rec.url.get();
        // URL-encoded: 'abcd' in parents → %27abcd%27+in+parents
        assertTrue(u.contains("abcd") && u.contains("parents"),
            "expected folder id and parents marker in URL: " + u);
    }

    @Test
    void search_requires_query() {
        var resp = adapter.invoke(req("gdrive", "search", Map.of()));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void read_doc_exports_as_text() {
        adapter.invoke(req("gdrive", "read_doc", Map.of("docId", "DOC1")));
        var u = rec.url.get();
        assertTrue(u.contains("/files/DOC1/export"));
        // URL-encoded mimeType param
        assertTrue(u.contains("text%2Fplain") || u.contains("text/plain"),
            "expected mimeType in URL: " + u);
    }

    @Test
    void create_doc_sets_doc_mime_type() {
        adapter.invoke(req("gdrive", "create_doc", Map.of("title", "Notes")));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rec.body.get();
        assertEquals("Notes", body.get("name"));
        assertEquals("application/vnd.google-apps.document", body.get("mimeType"));
    }

    @Test
    void missing_creds_short_circuits() {
        wireNoCreds();
        var resp = adapter.invoke(req("gdrive", "list", Map.of()));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }
}
