package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Drive adapter.
 *
 * <p>Exposes {@code world.gdrive.{list, search, read_doc, create_doc}}.
 * {@code read_doc} reads a Google Doc as plain text via the Docs export
 * endpoint. {@code create_doc} creates a new Doc via the Drive multipart
 * upload endpoint.</p>
 */
public final class GoogleDriveAdapter extends AbstractHttpAdapter {

    private static final String DRIVE = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3";

    @Override public String namespace() { return "gdrive"; }

    @Override public Set<String> capabilities() {
        return Set.of("list", "search", "read_doc", "create_doc");
    }

    @Override public String credentialSlot() { return "google.oauth_token"; }

    @Override protected List<String> defaultDomains() {
        return List.of("www.googleapis.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return missingCredentials();
        var headers = Map.of(
            "Authorization", "Bearer " + token.get(),
            "Accept", "application/json"
        );
        return switch (req.method()) {
            case "list" -> list(req, headers);
            case "search" -> search(req, headers);
            case "read_doc" -> readDoc(req, headers);
            case "create_doc" -> createDoc(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse list(AdapterRequest req, Map<String, String> headers) {
        var folderId = (String) req.args().get("folderId");
        var params = new LinkedHashMap<String, Object>();
        params.put("fields", "files(id,name,mimeType,modifiedTime,parents)");
        if (folderId != null) params.put("q", "'" + folderId + "' in parents");
        return httpGetJson(DRIVE + "/files", headers, params);
    }

    private AdapterResponse search(AdapterRequest req, Map<String, String> headers) {
        var query = requireString(req, "query");
        if (query == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("q", "name contains '" + query.replace("'", "\\'") + "'");
        params.put("fields", "files(id,name,mimeType,modifiedTime)");
        return httpGetJson(DRIVE + "/files", headers, params);
    }

    private AdapterResponse readDoc(AdapterRequest req, Map<String, String> headers) {
        var docId = requireString(req, "docId");
        if (docId == null) return AdapterResponse.fail("missing_arg", "docId required", false);
        // Export Google Doc as plain text
        var url = DRIVE + "/files/" + docId + "/export";
        return httpGetJson(url, headers, Map.of("mimeType", "text/plain"));
    }

    private AdapterResponse createDoc(AdapterRequest req, Map<String, String> headers) {
        var title = requireString(req, "title");
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", title);
        body.put("mimeType", "application/vnd.google-apps.document");
        if (req.args().get("folderId") != null) {
            body.put("parents", List.of(req.args().get("folderId")));
        }
        // Metadata-only create — content is appended via Docs API which is out of scope here.
        return httpPostJson(DRIVE + "/files", headers, body);
    }
}
