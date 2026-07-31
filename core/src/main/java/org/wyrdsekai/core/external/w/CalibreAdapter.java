package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * local Calibre Content Server.
 *
 * <p>The Calibre Content Server exposes a JSON HTTP API under
 * {@code /ajax/...}. The adapter expects a {@code calibre.url} credential
 * pointing at the local instance ({@code http://home-server:8080}). Methods:</p>
 * <ul>
 *   <li>{@code library_list(filter?)} / {@code books_list} — list books in
 *       the active library.</li>
 *   <li>{@code book_info(bookId)} / {@code book_get} — full metadata for
 *       a single book.</li>
 * </ul>
 * Local-only — no cloud egress.
 */
public final class CalibreAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "calibre";

    private final HttpAdapterSupport http;

    public CalibreAdapter() { this(new HttpAdapterSupport()); }

    public CalibreAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("library_list", "books_list", "book_info", "book_get", "search");
    }

    @Override public String credentialSlot() { return "calibre.url"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "library_list", "books_list" -> listBooks(request);
            case "book_info", "book_get" -> bookInfo(request);
            case "search" -> searchBooks(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse listBooks(AdapterRequest request) {
        var base = http.resolveCredential(credentialSlot());
        if (base.isEmpty()) return http.missingCredential(credentialSlot());

        var library = (String) request.args().getOrDefault("library", "Calibre_Library");
        var num = request.args().getOrDefault("num", 50);
        var url = base.get() + "/ajax/books?library_id="
            + HttpAdapterSupport.urlEncode(library) + "&num=" + num;
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            var parsed = http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            if (parsed instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> bm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("bookId", e.getKey());
                        entry.put("title", bm.get("title"));
                        entry.put("authors", bm.get("authors"));
                        entry.put("formats", bm.get("formats"));
                        entry.put("tags", bm.get("tags"));
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse bookInfo(AdapterRequest request) {
        var base = http.resolveCredential(credentialSlot());
        if (base.isEmpty()) return http.missingCredential(credentialSlot());

        var bookId = String.valueOf(request.args().get("bookId"));
        if (bookId == null || bookId.isBlank() || "null".equals(bookId)) {
            return http.missingArg("bookId");
        }
        var library = (String) request.args().getOrDefault("library", "Calibre_Library");
        var url = base.get() + "/ajax/book/" + HttpAdapterSupport.urlEncode(bookId)
            + "?library_id=" + HttpAdapterSupport.urlEncode(library);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("bookId", bookId);
            out.put("title", parsed.get("title"));
            out.put("authors", parsed.get("authors"));
            out.put("metadata", parsed);
            return out;
        });
    }

    private AdapterResponse searchBooks(AdapterRequest request) {
        var base = http.resolveCredential(credentialSlot());
        if (base.isEmpty()) return http.missingCredential(credentialSlot());

        var query = (String) request.args().get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");
        var library = (String) request.args().getOrDefault("library", "Calibre_Library");
        var url = base.get() + "/ajax/search?library_id="
            + HttpAdapterSupport.urlEncode(library)
            + "&query=" + HttpAdapterSupport.urlEncode(query);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("totalNum", parsed.get("total_num"));
            out.put("bookIds", parsed.get("book_ids"));
            return out;
        });
    }

    @SuppressWarnings("unused")
    private static String join(List<?> parts, String sep) {
        var sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
