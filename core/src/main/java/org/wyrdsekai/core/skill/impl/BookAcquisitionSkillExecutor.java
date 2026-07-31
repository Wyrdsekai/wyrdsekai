package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Book search, details, download, purchase, and shelving skill executor.
 * Searches Project Gutenberg (gutendex.com) and Open Library (openlibrary.org).
 * Free downloads from Gutenberg; purchases delegate to Trading Post skills.
 */
public class BookAcquisitionSkillExecutor extends HttpSkillExecutor {

    private static final String GUTENDEX_BASE = "https://gutendex.com";
    private static final String OPENLIBRARY_BASE = "https://openlibrary.org";

    private final Set<String> shelved = ConcurrentHashMap.newKeySet();

    public BookAcquisitionSkillExecutor() {
        super(OPENLIBRARY_BASE);

        define(SkillDefinition.native_("library.books.search", "Book Search",
            "Search for books across Project Gutenberg and Open Library",
            "library",
            List.of(SkillParam.required("query", "string", "Search query (title, author, keyword)"),
                     SkillParam.optional("source", "string", "Source: gutenberg, openlibrary, all"),
                     SkillParam.optional("limit", "number", "Max results")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("library.books.details", "Book Details",
            "Get detailed information about a specific book",
            "library",
            List.of(SkillParam.required("key", "string",
                "Open Library work key (e.g., /works/OL123W) or Gutenberg ID")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("library.books.download", "Book Download",
            "Download a free book from Project Gutenberg",
            "library",
            List.of(SkillParam.required("id", "string", "Gutenberg book ID"),
                     SkillParam.optional("format", "string", "Format: txt, epub, html")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("library.books.purchase", "Book Purchase",
            "Purchase a book (delegates to Trading Post for payment)",
            "library",
            List.of(SkillParam.required("key", "string", "Book identifier"),
                     SkillParam.required("source", "string", "Purchase source")),
            SkillAuth.NONE));

        define(SkillDefinition.native_("library.books.shelve", "Book Shelve",
            "Add a book to the library shelf",
            "library",
            List.of(SkillParam.required("key", "string", "Book key or ID"),
                     SkillParam.optional("shelf", "string", "Shelf name")),
            SkillAuth.NONE));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "library.books.search" -> executeSearch(params, start, skillId, context);
            case "library.books.details" -> executeDetails(params, start, skillId, context);
            case "library.books.download" -> executeDownload(params, start, skillId, context);
            case "library.books.purchase" -> executePurchase(params, start, skillId);
            case "library.books.shelve" -> executeShelve(params, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeSearch(Map<String, Object> params, long start,
                                       String skillId, SkillContext context) {
        String query = requireParam(params, "query");
        if (query == null) return SkillResult.error(
            I18n.get("skill.param_required", "query"), 0, SkillTier.NATIVE, skillId);

        String source = param(params, "source", "all");
        int limit = intParam(params, "limit", 10);
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

        var results = new ArrayList<Map<String, String>>();

        // Search Project Gutenberg via gutendex
        if ("all".equals(source) || "gutenberg".equals(source)) {
            String gUrl = GUTENDEX_BASE + "/books?search=" + encoded;
            var gResult = httpGet(gUrl, Map.of(), context.timeoutMs());
            if (gResult.ok() && gResult.body() != null) {
                parseGutendexResults(gResult.body(), results, limit);
            }
        }

        // Search Open Library
        if (("all".equals(source) || "openlibrary".equals(source))
                && results.size() < limit) {
            String oUrl = OPENLIBRARY_BASE + "/search.json?q=" + encoded
                + "&limit=" + (limit - results.size());
            var oResult = httpGet(oUrl, Map.of(), context.timeoutMs());
            if (oResult.ok() && oResult.body() != null) {
                parseOpenLibraryResults(oResult.body(), results, limit);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(
            I18n.get("skill.books.found", results.size(), query),
            Map.of("results", results, "count", results.size(), "query", query),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeDetails(Map<String, Object> params, long start,
                                        String skillId, SkillContext context) {
        String key = requireParam(params, "key");
        if (key == null) return SkillResult.error(
            I18n.get("skill.param_required", "key"), 0, SkillTier.NATIVE, skillId);

        // Try Open Library first
        String url;
        if (key.startsWith("/works/")) {
            url = OPENLIBRARY_BASE + key + ".json";
        } else {
            // Assume Gutenberg ID
            url = GUTENDEX_BASE + "/books/" + key;
        }

        var result = httpGet(url, Map.of(), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        String title = jsonString(result.body(), "title");
        return SkillResult.ok(title != null ? title : result.body(),
            Map.of("body", result.body(), "key", key),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeDownload(Map<String, Object> params, long start,
                                         String skillId, SkillContext context) {
        String id = requireParam(params, "id");
        if (id == null) return SkillResult.error(
            I18n.get("skill.param_required", "id"), 0, SkillTier.NATIVE, skillId);

        String format = param(params, "format", "txt");
        // Gutenberg mirror URL pattern
        String url = "https://www.gutenberg.org/files/" + id + "/" + id + "-0." + format;

        var result = httpGet(url, Map.of(), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        // Shelve automatically after download
        shelved.add("gutenberg:" + id);

        return SkillResult.ok(I18n.get("skill.books.acquired", "Gutenberg #" + id),
            Map.of("id", id, "format", format,
                   "length", result.body() != null ? result.body().length() : 0),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executePurchase(Map<String, Object> params, long start, String skillId) {
        String key = requireParam(params, "key");
        String source = requireParam(params, "source");
        if (key == null || source == null)
            return SkillResult.error(I18n.get("skill.param_required", "key, source"),
                0, SkillTier.NATIVE, skillId);

        long elapsed = System.currentTimeMillis() - start;
        // Purchases delegate to Trading Post -- return instruction
        return SkillResult.ok("Purchase requires Trading Post payment skill. "
                + "Use trading.privacy.create-card or trading.stripe.create-card first.",
            Map.of("key", key, "source", source, "requiresPayment", true),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeShelve(Map<String, Object> params, long start, String skillId) {
        String key = requireParam(params, "key");
        if (key == null) return SkillResult.error(
            I18n.get("skill.param_required", "key"), 0, SkillTier.NATIVE, skillId);

        String shelf = param(params, "shelf", "default");
        shelved.add(key);
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok(I18n.get("skill.books.shelved", key),
            Map.of("key", key, "shelf", shelf, "totalShelved", shelved.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private void parseGutendexResults(String json, List<Map<String, String>> out, int max) {
        // Simple extraction of results array from gutendex JSON
        int idx = 0;
        while (out.size() < max) {
            int titleStart = json.indexOf("\"title\"", idx);
            if (titleStart < 0) break;
            String title = extractJsonValue(json, titleStart);
            int idStart = json.lastIndexOf("\"id\"", titleStart);
            String id = idStart >= 0 ? extractJsonNumber(json, idStart) : "";
            if (title != null) {
                out.add(Map.of("title", title, "source", "gutenberg",
                    "key", id != null ? id : "", "free", "true"));
            }
            idx = titleStart + 1;
        }
    }

    private void parseOpenLibraryResults(String json, List<Map<String, String>> out, int max) {
        int idx = 0;
        while (out.size() < max) {
            int titleStart = json.indexOf("\"title\"", idx);
            if (titleStart < 0) break;
            String title = extractJsonValue(json, titleStart);
            int keyStart = json.lastIndexOf("\"key\"", titleStart);
            String key = keyStart >= 0 ? extractJsonValue(json, keyStart) : "";
            if (title != null) {
                out.add(Map.of("title", title, "source", "openlibrary",
                    "key", key != null ? key : ""));
            }
            idx = titleStart + 1;
        }
    }

    private String extractJsonValue(String json, int keyStart) {
        int colon = json.indexOf(':', keyStart);
        if (colon < 0) return null;
        int qStart = json.indexOf('"', colon + 1);
        if (qStart < 0) return null;
        int qEnd = json.indexOf('"', qStart + 1);
        if (qEnd < 0) return null;
        return json.substring(qStart + 1, qEnd);
    }

    private String extractJsonNumber(String json, int keyStart) {
        int colon = json.indexOf(':', keyStart);
        if (colon < 0) return null;
        int numStart = colon + 1;
        while (numStart < json.length() && !Character.isDigit(json.charAt(numStart)))
            numStart++;
        int numEnd = numStart;
        while (numEnd < json.length() && Character.isDigit(json.charAt(numEnd)))
            numEnd++;
        return numStart < numEnd ? json.substring(numStart, numEnd) : null;
    }

    /** Direct access for testing. */
    public Set<String> shelvedBooks() { return Collections.unmodifiableSet(shelved); }
}
