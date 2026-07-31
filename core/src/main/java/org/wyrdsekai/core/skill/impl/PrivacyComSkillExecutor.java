package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.List;
import java.util.Map;

/**
 * Privacy.com REST API skill executor.
 * Provides virtual card creation, listing, transaction history, pause, and limit updates.
 * Requires Privacy.com API key in The Safe.
 */
public class PrivacyComSkillExecutor extends HttpSkillExecutor {

    private static final String BASE = "https://api.privacy.com/v1";
    private static final SkillAuth AUTH = SkillAuth.apiKey("privacy_api_key");

    public PrivacyComSkillExecutor() {
        super(BASE);

        define(SkillDefinition.native_("trading.privacy.create-card", "Privacy Create Card",
            "Create a new Privacy.com virtual card", "trading-post",
            List.of(SkillParam.required("type", "string",
                         "Card type: SINGLE_USE, MERCHANT_LOCKED, UNLOCKED"),
                     SkillParam.optional("memo", "string", "Card memo/label"),
                     SkillParam.optional("spend_limit", "number", "Spend limit in cents"),
                     SkillParam.optional("spend_limit_duration", "string",
                         "Limit duration: TRANSACTION, MONTHLY, ANNUALLY, FOREVER")),
            AUTH));

        define(SkillDefinition.native_("trading.privacy.list-cards", "Privacy List Cards",
            "List Privacy.com virtual cards", "trading-post",
            List.of(SkillParam.optional("page", "number", "Page number"),
                     SkillParam.optional("page_size", "number", "Cards per page"),
                     SkillParam.optional("state", "string",
                         "Filter by state: OPEN, PAUSED, CLOSED")),
            AUTH));

        define(SkillDefinition.native_("trading.privacy.transactions", "Privacy Transactions",
            "List recent transactions", "trading-post",
            List.of(SkillParam.optional("card_token", "string", "Filter by card token"),
                     SkillParam.optional("page", "number", "Page number"),
                     SkillParam.optional("page_size", "number", "Results per page")),
            AUTH));

        define(SkillDefinition.native_("trading.privacy.pause", "Privacy Pause Card",
            "Pause or unpause a Privacy.com card", "trading-post",
            List.of(SkillParam.required("card_token", "string", "Card token to pause/unpause"),
                     SkillParam.required("state", "string", "New state: OPEN or PAUSED")),
            AUTH));

        define(SkillDefinition.native_("trading.privacy.update-limit", "Privacy Update Limit",
            "Update spend limit on a Privacy.com card", "trading-post",
            List.of(SkillParam.required("card_token", "string", "Card token"),
                     SkillParam.required("spend_limit", "number", "New spend limit in cents"),
                     SkillParam.optional("spend_limit_duration", "string",
                         "Limit duration: TRANSACTION, MONTHLY, ANNUALLY, FOREVER")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String apiKey = context.credentials().get("privacy_api_key");
        if (apiKey == null) return notConfigured(skillId, "Privacy.com API key");

        var headers = Map.of("Authorization", "api-key " + apiKey,
                              "Accept", "application/json");
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "trading.privacy.create-card" ->
                executeCreateCard(params, headers, start, skillId);
            case "trading.privacy.list-cards" ->
                executeListCards(params, headers, start, skillId);
            case "trading.privacy.transactions" ->
                executeTransactions(params, headers, start, skillId);
            case "trading.privacy.pause" ->
                executePause(params, headers, start, skillId);
            case "trading.privacy.update-limit" ->
                executeUpdateLimit(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeCreateCard(Map<String, Object> params, Map<String, String> headers,
                                           long start, String skillId) {
        String type = requireParam(params, "type");
        if (type == null) return SkillResult.error(I18n.get("skill.param_required", "type"),
            0, SkillTier.NATIVE, skillId);

        var json = new StringBuilder("{\"type\":\"").append(esc(type)).append("\"");
        String memo = param(params, "memo", null);
        if (memo != null) json.append(",\"memo\":\"").append(esc(memo)).append("\"");
        String limit = param(params, "spend_limit", null);
        if (limit != null) json.append(",\"spend_limit\":").append(limit);
        String duration = param(params, "spend_limit_duration", null);
        if (duration != null) json.append(",\"spend_limit_duration\":\"").append(esc(duration)).append("\"");
        json.append("}");

        var result = httpPost(baseUrl + "/card", json.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        String lastFour = jsonString(result.body(), "last_four");
        String label = lastFour != null ? "****" + lastFour : type;
        return SkillResult.ok(I18n.get("skill.privacy.card_created", label),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeListCards(Map<String, Object> params, Map<String, String> headers,
                                          long start, String skillId) {
        int page = intParam(params, "page", 1);
        int pageSize = intParam(params, "page_size", 10);
        var url = new StringBuilder(baseUrl)
            .append("/card?page=").append(page).append("&page_size=").append(pageSize);
        String state = param(params, "state", null);
        if (state != null) url.append("&state=").append(state);

        var result = httpGet(url.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeTransactions(Map<String, Object> params,
                                             Map<String, String> headers,
                                             long start, String skillId) {
        int page = intParam(params, "page", 1);
        int pageSize = intParam(params, "page_size", 20);
        var url = new StringBuilder(baseUrl)
            .append("/transaction?page=").append(page).append("&page_size=").append(pageSize);
        String cardToken = param(params, "card_token", null);
        if (cardToken != null) url.append("&card_token=").append(cardToken);

        var result = httpGet(url.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executePause(Map<String, Object> params, Map<String, String> headers,
                                      long start, String skillId) {
        String cardToken = requireParam(params, "card_token");
        String state = requireParam(params, "state");
        if (cardToken == null || state == null)
            return SkillResult.error(I18n.get("skill.param_required", "card_token, state"),
                0, SkillTier.NATIVE, skillId);

        String json = "{\"card_token\":\"" + esc(cardToken)
            + "\",\"state\":\"" + esc(state) + "\"}";
        var result = httpPost(baseUrl + "/card", json, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.privacy.card_paused", cardToken),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeUpdateLimit(Map<String, Object> params,
                                            Map<String, String> headers,
                                            long start, String skillId) {
        String cardToken = requireParam(params, "card_token");
        String limit = requireParam(params, "spend_limit");
        if (cardToken == null || limit == null)
            return SkillResult.error(I18n.get("skill.param_required", "card_token, spend_limit"),
                0, SkillTier.NATIVE, skillId);

        var json = new StringBuilder("{\"card_token\":\"").append(esc(cardToken))
            .append("\",\"spend_limit\":").append(limit);
        String duration = param(params, "spend_limit_duration", null);
        if (duration != null)
            json.append(",\"spend_limit_duration\":\"").append(esc(duration)).append("\"");
        json.append("}");

        var result = httpPost(baseUrl + "/card", json.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
