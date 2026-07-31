package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Kiwix offline encyclopedia skills via kiwix-serve HTTP API.
 * Provides search, article reading, suggestions, and random articles.
 * Runs locally — no internet required.
 */
public class KiwixSkillExecutor extends HttpSkillExecutor {

    private static final String DEFAULT_BASE_URL = "http://localhost:8888";

    public KiwixSkillExecutor() {
        this(DEFAULT_BASE_URL);
    }

    public KiwixSkillExecutor(String baseUrl) {
        super(baseUrl);

        define(new SkillDefinition("library.kiwix.search",
            "Kiwix Search", "Search offline encyclopedia articles",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("query", "string", "Search query")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("library.kiwix.read",
            "Kiwix Read", "Read a specific encyclopedia article",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("path", "string", "Article path")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("library.kiwix.suggest",
            "Kiwix Suggest", "Get article title suggestions",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("prefix", "string", "Search prefix")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("library.kiwix.random",
            "Kiwix Random", "Get a random encyclopedia article",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return notConfigured(skillId, "kiwix_url");
        }

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "library.kiwix.search" -> executeSearch(params, start, skillId, context);
            case "library.kiwix.read" -> executeRead(params, start, skillId, context);
            case "library.kiwix.suggest" -> executeSuggest(params, start, skillId, context);
            case "library.kiwix.random" -> executeRandom(start, skillId, context);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeSearch(Map<String, Object> params, long start,
                                       String skillId, SkillContext context) {
        String query = requireParam(params, "query");
        if (query == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "query"),
                0, SkillTier.NATIVE, skillId);
        }

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/search?pattern=" + encoded + "&pageLength=10";
        var result = httpGet(url, Map.of("Accept", "application/json"), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        String body = result.body();
        if (body == null || body.isBlank() || body.contains("\"results\":[]") || body.trim().equals("[]")) {
            return SkillResult.ok(
                I18n.get("skill.kiwix.no_results", query),
                Map.of("query", query, "results", List.of()),
                elapsed, SkillTier.NATIVE, skillId);
        }

        return SkillResult.ok(body, Map.of("query", query, "raw", body),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRead(Map<String, Object> params, long start,
                                     String skillId, SkillContext context) {
        String path = requireParam(params, "path");
        if (path == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "path"),
                0, SkillTier.NATIVE, skillId);
        }

        // Sanitize path — only allow alphanumeric, slashes, hyphens, underscores, dots
        String sanitized = path.replaceAll("[^a-zA-Z0-9/\\-_.]", "");
        String url = baseUrl + "/" + sanitized;
        var result = httpGet(url, Map.of("Accept", "text/plain"), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("path", sanitized, "content", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSuggest(Map<String, Object> params, long start,
                                        String skillId, SkillContext context) {
        String prefix = requireParam(params, "prefix");
        if (prefix == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "prefix"),
                0, SkillTier.NATIVE, skillId);
        }

        String encoded = URLEncoder.encode(prefix, StandardCharsets.UTF_8);
        String url = baseUrl + "/suggest?term=" + encoded + "&limit=10";
        var result = httpGet(url, Map.of("Accept", "application/json"), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("prefix", prefix, "raw", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRandom(long start, String skillId, SkillContext context) {
        String url = baseUrl + "/random";
        var result = httpGet(url, Map.of("Accept", "text/plain"), context.timeoutMs());
        long elapsed = System.currentTimeMillis() - start;

        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(), Map.of("content", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }
}
