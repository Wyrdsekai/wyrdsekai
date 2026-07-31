package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.Map;

/**
 * HTTP search endpoint for querying WyrdLuceneStore collections.
 *
 *   GET /api/search?q={query}&collection={collection}&limit={limit}
 *
 * Parameters:
 *   q          — required search query text
 *   collection — optional collection name (default: room_content).
 *                Supported: room_content, library, soul_fragments, memory_items, world_dna
 *   limit      — optional max results (default: 10, max: 100)
 */
public final class SearchRoutes {

    private static final Logger log = LoggerFactory.getLogger(SearchRoutes.class);

    private final WyrdLuceneStore store;

    public SearchRoutes(WyrdLuceneStore store) {
        this.store = store;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/search", this::handleSearch);
    }

    private void handleSearch(Context ctx) {
        var q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "q parameter required"));
            return;
        }

        var collection = ctx.queryParamAsClass("collection", String.class)
            .getOrDefault(SearchCollections.ROOM_CONTENT);
        var limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;

        var results = switch (collection) {
            case SearchCollections.ROOM_CONTENT -> store.searchRooms(q, limit);
            case SearchCollections.LIBRARY -> store.searchCapabilities(q, limit);
            case SearchCollections.SOUL_FRAGMENTS -> store.searchFragments(null, q, null, limit);
            case SearchCollections.MEMORY_ITEMS -> store.searchMemory(null, q, null, limit);
            case SearchCollections.WORLD_DNA -> store.searchWorldDna(q, null, limit);
            default -> {
                ctx.status(400).json(Map.of("error", "Unknown collection: " + collection,
                    "supported", SearchCollections.ALL));
                yield null;
            }
        };

        if (results != null) {
            ctx.json(Map.of("query", q, "collection", collection, "results", results));
        }
    }
}
