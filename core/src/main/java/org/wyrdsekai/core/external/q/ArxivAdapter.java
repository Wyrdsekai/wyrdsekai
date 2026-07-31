package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * arXiv adapter.
 *
 * <p>Exposes {@code world.arxiv.{search, abstract, full_text}}. arXiv's
 * public API requires no authentication. Responses come back as Atom XML
 * which we surface as raw text — items can use {@code llm.extract} or
 * {@code regex.match} to parse.</p>
 */
public final class ArxivAdapter extends AbstractHttpAdapter {

    private static final String BASE = "http://export.arxiv.org/api/query";
    private static final String ABS_BASE = "https://arxiv.org/abs/";
    private static final String PDF_BASE = "https://arxiv.org/pdf/";

    @Override public String namespace() { return "arxiv"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "abstract", "full_text");
    }

    /** No credentials needed — arXiv is open. */
    @Override public String credentialSlot() { return ""; }

    @Override protected List<String> defaultDomains() {
        return List.of("export.arxiv.org", "arxiv.org");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        return switch (req.method()) {
            case "search" -> search(req);
            case "abstract" -> fetchAbstract(req);
            case "full_text" -> fetchFullText(req);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(AdapterRequest req) {
        var query = requireString(req, "query");
        if (query == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var params = new LinkedHashMap<String, Object>();
        params.put("search_query", "all:" + query);
        if (req.args().get("max") != null) params.put("max_results", req.args().get("max"));
        else params.put("max_results", 10);
        if (req.args().get("category") != null) {
            params.put("search_query", "cat:" + req.args().get("category") + " AND all:" + query);
        }
        return httpGetJson(BASE, Map.of("Accept", "application/atom+xml"), params);
    }

    private AdapterResponse fetchAbstract(AdapterRequest req) {
        var paperId = requireString(req, "paperId");
        if (paperId == null) return AdapterResponse.fail("missing_arg", "paperId required", false);
        var params = Map.of("id_list", (Object) paperId);
        return httpGetJson(BASE, Map.of("Accept", "application/atom+xml"), params);
    }

    private AdapterResponse fetchFullText(AdapterRequest req) {
        var paperId = requireString(req, "paperId");
        if (paperId == null) return AdapterResponse.fail("missing_arg", "paperId required", false);
        // Surface the canonical PDF URL — actual PDF download exceeds 10MB cap commonly.
        var url = PDF_BASE + paperId + ".pdf";
        var absUrl = ABS_BASE + paperId;
        return AdapterResponse.ok(Map.of(
            "paperId", paperId,
            "pdfUrl", url,
            "absUrl", absUrl,
            "note", "use world.web.fetch to retrieve PDF if size permits"
        ));
    }
}
