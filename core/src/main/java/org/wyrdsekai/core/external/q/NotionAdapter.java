package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notion adapter.
 *
 * <p>Exposes {@code world.notion.{search, read_page, create_page,
 * update_page, append_block}}. The Notion API requires the
 * {@code Notion-Version} header on every request.</p>
 */
public final class NotionAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://api.notion.com/v1";
    private static final String API_VERSION = "2022-06-28";

    @Override public String namespace() { return "notion"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "read_page", "create_page", "update_page", "append_block");
    }

    @Override public String credentialSlot() { return "notion.token"; }

    @Override protected List<String> defaultDomains() {
        return List.of("api.notion.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return missingCredentials();
        var headers = Map.of(
            "Authorization", "Bearer " + token.get(),
            "Notion-Version", API_VERSION,
            "Accept", "application/json"
        );
        return switch (req.method()) {
            case "search" -> search(req, headers);
            case "read_page" -> readPage(req, headers);
            case "create_page" -> createPage(req, headers);
            case "update_page" -> updatePage(req, headers);
            case "append_block" -> appendBlock(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(AdapterRequest req, Map<String, String> headers) {
        var body = new LinkedHashMap<String, Object>();
        if (req.args().get("query") != null) body.put("query", req.args().get("query"));
        if (req.args().get("filter") != null) body.put("filter", req.args().get("filter"));
        return httpPostJson(BASE + "/search", headers, body);
    }

    private AdapterResponse readPage(AdapterRequest req, Map<String, String> headers) {
        var pageId = requireString(req, "pageId");
        if (pageId == null) return AdapterResponse.fail("missing_arg", "pageId required", false);
        return httpGetJson(BASE + "/pages/" + pageId, headers, Map.of());
    }

    private AdapterResponse createPage(AdapterRequest req, Map<String, String> headers) {
        var parent = req.args().get("parent");
        if (parent == null) return AdapterResponse.fail("missing_arg", "parent required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("parent", parent);
        if (req.args().get("properties") != null) body.put("properties", req.args().get("properties"));
        if (req.args().get("children") != null) body.put("children", req.args().get("children"));
        return httpPostJson(BASE + "/pages", headers, body);
    }

    private AdapterResponse updatePage(AdapterRequest req, Map<String, String> headers) {
        var pageId = requireString(req, "pageId");
        if (pageId == null) return AdapterResponse.fail("missing_arg", "pageId required", false);
        var body = new LinkedHashMap<>(req.args());
        body.remove("pageId");
        return httpPatchJson(BASE + "/pages/" + pageId, headers, body);
    }

    private AdapterResponse appendBlock(AdapterRequest req, Map<String, String> headers) {
        var blockId = requireString(req, "blockId");
        if (blockId == null) return AdapterResponse.fail("missing_arg", "blockId required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("children", req.args().getOrDefault("children", List.of()));
        return httpPatchJson(BASE + "/blocks/" + blockId + "/children", headers, body);
    }
}
