package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Base class for HTTP REST-based skill executors.
 * Handles HTTP plumbing, timeouts, error handling.
 * Subclasses define endpoints and response parsing.
 */
public abstract class HttpSkillExecutor implements SkillExecutor {

    protected final HttpClient http;
    protected final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    protected String baseUrl;

    protected HttpSkillExecutor() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    protected HttpSkillExecutor(String baseUrl) {
        this();
        this.baseUrl = baseUrl;
    }

    /** Register a skill definition this executor provides. */
    protected void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean supports(String skillId) {
        return skills.containsKey(skillId);
    }

    @Override
    public SkillTier tier() {
        return SkillTier.NATIVE;
    }

    /** Execute a GET request and return the response body. */
    protected HttpResult httpGet(String url, Map<String, String> headers, long timeoutMs) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofMillis(timeoutMs));
            headers.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new HttpResult(0, null, e);
        }
    }

    /** Execute a POST request with JSON body. */
    protected HttpResult httpPost(String url, String jsonBody, Map<String, String> headers, long timeoutMs) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs));
            headers.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new HttpResult(0, null, e);
        }
    }

    /** Standard error result for HTTP failures. */
    protected SkillResult httpError(String skillId, HttpResult result, long elapsed) {
        if (result.error() != null) {
            return SkillResult.error(
                I18n.get("skill.http.connection_failed", result.error().getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
        return SkillResult.error(
            I18n.get("skill.http.status_error", result.statusCode()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Standard error for missing configuration. */
    protected SkillResult notConfigured(String skillId, String what) {
        return SkillResult.error(
            I18n.get("skill.not_configured", what),
            0, SkillTier.NATIVE, skillId);
    }

    /** Extract a string parameter with default. */
    protected String param(Map<String, Object> params, String key, String defaultValue) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : defaultValue;
    }

    /** Extract a required string parameter. Returns null if missing. */
    protected String requireParam(Map<String, Object> params, String key) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }

    /** Extract an int parameter with default. */
    protected int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    /** Simple JSON value extraction (avoids Jackson dependency for simple cases). */
    protected String jsonString(String json, String key) {
        // Simple regex-based extraction for flat JSON
        var pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"";
        var matcher = Pattern.compile(pattern).matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Simple JSON number extraction. */
    protected Double jsonNumber(String json, String key) {
        var pattern = "\"" + key + "\"\\s*:\\s*([\\d.\\-]+)";
        var matcher = Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            try { return Double.parseDouble(matcher.group(1)); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** HTTP response wrapper. */
    public record HttpResult(int statusCode, String body, Exception error) {
        public boolean ok() { return statusCode >= 200 && statusCode < 300 && error == null; }
    }
}
