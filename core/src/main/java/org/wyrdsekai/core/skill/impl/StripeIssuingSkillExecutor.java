package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stripe Issuing API skill executor.
 * Provides card creation, authorization management, transaction listing,
 * balance funding, and transfers via the Stripe REST API.
 * Uses form-encoded POST as required by Stripe.
 */
public class StripeIssuingSkillExecutor extends HttpSkillExecutor {

    private static final String DEFAULT_BASE_URL = "https://api.stripe.com/v1";
    private static final SkillAuth AUTH = SkillAuth.apiKey("stripe_api_key");

    public StripeIssuingSkillExecutor() {
        this(DEFAULT_BASE_URL);
    }

    public StripeIssuingSkillExecutor(String baseUrl) {
        super(baseUrl);

        define(SkillDefinition.native_("trading.stripe.create-card", "Stripe Create Card",
            "Create a Stripe Issuing virtual card", "trading-post",
            List.of(SkillParam.required("cardholder", "string", "Cardholder ID"),
                     SkillParam.optional("currency", "string", "Currency (default: usd)"),
                     SkillParam.optional("spending_limit", "number", "Spending limit in cents"),
                     SkillParam.optional("spending_interval", "string",
                         "Interval: per_authorization, daily, weekly, monthly, yearly, all_time")),
            AUTH));

        define(SkillDefinition.native_("trading.stripe.authorize", "Stripe Authorize",
            "Approve or decline an Issuing authorization", "trading-post",
            List.of(SkillParam.required("authorization_id", "string", "Authorization ID"),
                     SkillParam.required("action", "string", "Action: approve or decline")),
            AUTH));

        define(SkillDefinition.native_("trading.stripe.transactions", "Stripe Transactions",
            "List Issuing transactions", "trading-post",
            List.of(SkillParam.optional("card", "string", "Filter by card ID"),
                     SkillParam.optional("limit", "number", "Max transactions to return")),
            AUTH));

        define(SkillDefinition.native_("trading.stripe.fund", "Stripe Fund",
            "Add funds via Stripe Topup", "trading-post",
            List.of(SkillParam.required("amount", "number", "Amount in cents"),
                     SkillParam.optional("currency", "string", "Currency (default: usd)"),
                     SkillParam.optional("description", "string", "Funding description")),
            AUTH));

        define(SkillDefinition.native_("trading.stripe.transfer", "Stripe Transfer",
            "Transfer funds to a connected account", "trading-post",
            List.of(SkillParam.required("amount", "number", "Amount in cents"),
                     SkillParam.required("destination", "string", "Connected account ID"),
                     SkillParam.optional("currency", "string", "Currency (default: usd)"),
                     SkillParam.optional("description", "string", "Transfer description")),
            AUTH));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String apiKey = context.credentials().get("stripe_api_key");
        if (apiKey == null) return notConfigured(skillId, "Stripe API key");

        var headers = Map.of("Authorization", "Bearer " + apiKey);
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "trading.stripe.create-card" ->
                executeCreateCard(params, headers, start, skillId);
            case "trading.stripe.authorize" ->
                executeAuthorize(params, headers, start, skillId);
            case "trading.stripe.transactions" ->
                executeTransactions(params, headers, start, skillId);
            case "trading.stripe.fund" ->
                executeFund(params, headers, start, skillId);
            case "trading.stripe.transfer" ->
                executeTransfer(params, headers, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeCreateCard(Map<String, Object> params,
                                           Map<String, String> headers,
                                           long start, String skillId) {
        String cardholder = requireParam(params, "cardholder");
        if (cardholder == null) return SkillResult.error(
            I18n.get("skill.param_required", "cardholder"), 0, SkillTier.NATIVE, skillId);

        String currency = param(params, "currency", "usd");
        var form = new LinkedHashMap<String, String>();
        form.put("cardholder", cardholder);
        form.put("currency", currency);
        form.put("type", "virtual");
        form.put("status", "active");

        String limit = param(params, "spending_limit", null);
        String interval = param(params, "spending_interval", "all_time");
        if (limit != null) {
            form.put("spending_controls[spending_limits][0][amount]", limit);
            form.put("spending_controls[spending_limits][0][interval]", interval);
        }

        var result = httpFormPost(baseUrl + "/issuing/cards", form, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        String last4 = jsonString(result.body(), "last4");
        String label = last4 != null ? "****" + last4 : cardholder;
        return SkillResult.ok(I18n.get("skill.stripe.card_created", label),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeAuthorize(Map<String, Object> params,
                                          Map<String, String> headers,
                                          long start, String skillId) {
        String authId = requireParam(params, "authorization_id");
        String action = requireParam(params, "action");
        if (authId == null || action == null) return SkillResult.error(
            I18n.get("skill.param_required", "authorization_id, action"),
            0, SkillTier.NATIVE, skillId);

        String endpoint = "approve".equalsIgnoreCase(action)
            ? "/issuing/authorizations/" + authId + "/approve"
            : "/issuing/authorizations/" + authId + "/decline";

        var result = httpFormPost(baseUrl + endpoint,
            new LinkedHashMap<>(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(action + ": " + authId,
            Map.of("authorizationId", authId, "action", action, "body", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeTransactions(Map<String, Object> params,
                                             Map<String, String> headers,
                                             long start, String skillId) {
        int limit = intParam(params, "limit", 10);
        StringBuilder url = new StringBuilder(baseUrl)
            .append("/issuing/transactions?limit=").append(limit);
        String card = param(params, "card", null);
        if (card != null) url.append("&card=").append(enc(card));

        var result = httpGet(url.toString(), headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(result.body(),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeFund(Map<String, Object> params,
                                     Map<String, String> headers,
                                     long start, String skillId) {
        String amount = requireParam(params, "amount");
        if (amount == null) return SkillResult.error(
            I18n.get("skill.param_required", "amount"), 0, SkillTier.NATIVE, skillId);

        String currency = param(params, "currency", "usd");
        String desc = param(params, "description", "Skill-initiated topup");

        var form = new LinkedHashMap<String, String>();
        form.put("amount", amount);
        form.put("currency", currency);
        form.put("description", desc);
        form.put("statement_descriptor", "WYRDSEKAI FUND");

        var result = httpFormPost(baseUrl + "/topups", form, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.stripe.funded", amount + " " + currency),
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeTransfer(Map<String, Object> params,
                                         Map<String, String> headers,
                                         long start, String skillId) {
        String amount = requireParam(params, "amount");
        String destination = requireParam(params, "destination");
        if (amount == null || destination == null) return SkillResult.error(
            I18n.get("skill.param_required", "amount, destination"),
            0, SkillTier.NATIVE, skillId);

        String currency = param(params, "currency", "usd");
        String desc = param(params, "description", "Skill-initiated transfer");

        var form = new LinkedHashMap<String, String>();
        form.put("amount", amount);
        form.put("currency", currency);
        form.put("destination", destination);
        form.put("description", desc);

        var result = httpFormPost(baseUrl + "/transfers", form, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok("Transfer: " + amount + " " + currency + " to " + destination,
            Map.of("body", result.body()), elapsed, SkillTier.NATIVE, skillId);
    }

    protected HttpResult httpFormPost(String url, Map<String, String> formParams,
                                       Map<String, String> headers, long timeoutMs) {
        try {
            StringBuilder form = new StringBuilder();
            for (var entry : formParams.entrySet()) {
                if (!form.isEmpty()) form.append("&");
                form.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(timeoutMs));
            headers.forEach(builder::header);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body(), null);
        } catch (Exception e) {
            return new HttpResult(0, null, e);
        }
    }

    private String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
