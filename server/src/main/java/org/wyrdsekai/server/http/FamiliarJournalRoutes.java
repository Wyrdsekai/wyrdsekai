package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.library.StudyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints for browsing the familiar journal.
 *
 * <p>Familiar-journal entries are written by {@code FamiliarJournal} into
 * the agent's private Study journal, tagged with prefixes like
 * {@code familiar.shaped}, {@code familiar.summoned}, {@code imprint.created}.
 * These endpoints surface them for the Study pinboard and the
 * {@code wyrd journal} CLI.</p>
 *
 * <ul>
 *   <li>{@code GET /api/familiar/journal?user={did}&limit={n}} — recent entries.</li>
 *   <li>{@code GET /api/familiar/journal?user={did}&tag=familiar.shaped} — tag-filtered.</li>
 *   <li>{@code GET /api/familiar/journal/search?user={did}&q={query}} — full-text.</li>
 * </ul>
 */
public final class FamiliarJournalRoutes {

    private static final Logger log = LoggerFactory.getLogger(FamiliarJournalRoutes.class);

    /** All familiar-system tag prefixes — see FamiliarJournal.Kind. */
    private static final List<String> FAMILIAR_TAGS = List.of(
        "familiar.shaped", "familiar.revised", "familiar.retired", "familiar.unretired",
        "familiar.summoned", "familiar.returned", "familiar.stuck", "familiar.cancelled",
        "bunshin.dispatch", "bunshin.return",
        "imprint.created", "imprint.restored");

    private final StudyService studyService;

    public FamiliarJournalRoutes(StudyService studyService) {
        this.studyService = studyService;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/familiar/journal", this::handleList);
        app.get("/api/familiar/journal/search", this::handleSearch);
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    private void handleList(Context ctx) {
        var userDid = callerOwn(ctx, ctx.queryParam("user"));
        if (userDid == null) return;
        var limit = parseLimit(ctx.queryParam("limit"), 20, 200);
        var tag = ctx.queryParam("tag");
        // The tag used to be any prefix the caller liked, and entries were matched with
        // startsWith: walking the alphabet returned the newest private entries decrypted.
        if (tag != null && !tag.isBlank()
                && FAMILIAR_TAGS.stream().noneMatch(t -> t.equals(tag) || t.startsWith(tag + "."))) {
            ctx.status(400).json(Map.of("error", "unknown_tag", "tags", FAMILIAR_TAGS));
            return;
        }

        // Strategy: full-text search either for the explicit tag or for the
        // union of familiar-system tags. Lucene query string search works.
        var query = tag != null && !tag.isBlank() ? tag : String.join(" OR ", FAMILIAR_TAGS);
        var raw = studyService.searchAllJournal(userDid, query, Math.max(limit, 50));
        var filtered = filterByFamiliarTag(raw, tag);
        if (filtered.size() > limit) filtered = filtered.subList(0, limit);

        ctx.json(Map.of(
            "user", userDid,
            "tag", tag == null ? "" : tag,
            "count", filtered.size(),
            "entries", filtered));
    }

    private void handleSearch(Context ctx) {
        var userDid = callerOwn(ctx, ctx.queryParam("user"));
        if (userDid == null) return;
        var q = ctx.queryParam("q");
        if (q == null || q.isBlank()) {
            ctx.status(400).json(Map.of("error", "q parameter required"));
            return;
        }
        var limit = parseLimit(ctx.queryParam("limit"), 20, 200);
        // Combine user's search terms with familiar-tag filter so only
        // familiar-system entries surface.
        var combined = "(" + q + ") AND (" + String.join(" OR ", FAMILIAR_TAGS) + ")";
        var results = studyService.searchAllJournal(userDid, combined, Math.max(limit, 50));
        var filtered = filterByFamiliarTag(results, null);
        if (filtered.size() > limit) filtered = filtered.subList(0, limit);
        ctx.json(Map.of(
            "user", userDid,
            "query", q,
            "count", filtered.size(),
            "entries", filtered));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * The journal read is the owner's decrypting path, so only the owner reaches it: a
     * person's own login (session or linked device). Until 2026-09-28 the owner came from
     * the request and no login was asked for.
     */
    private static String callerOwn(Context ctx, String requested) {
        if (!(ApiAuth.principal(ctx) instanceof ApiAuth.Person caller)) {
            ctx.status(401).json(Map.of("error", "person_required",
                "message", "A journal is read with its owner's own login (wyrd login)."));
            return null;
        }
        var id = caller.user().id();
        if (requested != null && !requested.isBlank() && !PersonIds.samePerson(requested, id)) {
            ctx.status(403).json(Map.of("error", "not_yours", "message", "You can only read your own journal."));
            return null;
        }
        return PersonIds.canonical(id);
    }

    private static int parseLimit(String raw, int def, int max) {
        if (raw == null) return def;
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1) return def;
            return Math.min(n, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Keep only entries whose content starts with a familiar-system tag.
     * Lucene-search returns anything matching the query string, so we
     * post-filter to defend against stray matches.
     */
    private static List<?> filterByFamiliarTag(List<?> results, String wantedTag) {
        var filtered = new ArrayList<Object>();
        for (var r : results) {
            String content = extractContent(r);
            if (content == null) continue;
            if (wantedTag != null && !wantedTag.isBlank()) {
                if (content.startsWith(wantedTag)) filtered.add(r);
            } else {
                boolean match = false;
                for (var t : FAMILIAR_TAGS) {
                    if (content.startsWith(t)) { match = true; break; }
                }
                if (match) filtered.add(r);
            }
        }
        return filtered;
    }

    private static String extractContent(Object searchResult) {
        try {
            // WyrdLuceneStore.SearchResult is a record with content() accessor
            var m = searchResult.getClass().getMethod("content");
            var value = m.invoke(searchResult);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
