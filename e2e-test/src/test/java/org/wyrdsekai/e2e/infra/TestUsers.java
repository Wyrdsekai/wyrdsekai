package org.wyrdsekai.e2e.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Shared helpers for creating test users against the household auth model.
 *
 * <p>Since the F4 OSS-hardening, {@code /api/auth/register} is open ONLY for the
 * very first user (who becomes the steward); every subsequent member must come
 * through a steward-issued invite ({@code /api/auth/invite}) redeemed via
 * {@code /api/auth/redeem}. The {@code open_registration} config key is no longer
 * consulted ({@link org.wyrdsekai.core.persistence.AuthService#isOpenRegistrationAllowed}).
 *
 * <p>Multi-user tests must therefore mint+redeem an invite for their second and
 * later users rather than hitting {@code register} twice. These helpers wrap that
 * flow so a test's {@code @BeforeAll} reads as two lines.
 */
public final class TestUsers {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TestUsers() {}

    /**
     * Register the first user — the steward. Open registration always permits the
     * first account. Returns the full register response ({@code token}, {@code userId},
     * {@code username}, {@code role}).
     *
     * @throws IllegalStateException if registration doesn't return 201
     */
    public static JsonNode registerSteward(String baseUrl, String username,
                                           String password, String displayName) throws Exception {
        var resp = post(baseUrl + "/api/auth/register", String.format(
            "{\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
            username, password, displayName), null);
        if (resp.statusCode() != 201) {
            throw new IllegalStateException(
                "steward registration failed (" + resp.statusCode() + "): " + resp.body());
        }
        return MAPPER.readTree(resp.body());
    }

    /** Convenience: register the steward and return just its session token. */
    public static String registerStewardToken(String baseUrl, String username,
                                              String password, String displayName) throws Exception {
        return registerSteward(baseUrl, username, password, displayName).get("token").asText();
    }

    /**
     * Create a member account via the steward's invite/redeem flow — the only
     * supported path to add a user after the steward exists. Returns the redeem
     * response ({@code token}, {@code userId}, {@code username}, {@code role}).
     *
     * @param stewardToken a steward session token (from {@link #registerSteward})
     * @throws IllegalStateException if minting or redeeming the invite fails
     */
    public static JsonNode inviteAndRedeem(String baseUrl, String stewardToken, String username,
                                           String password, String displayName) throws Exception {
        var inviteResp = post(baseUrl + "/api/auth/invite", String.format(
            "{\"name\":\"%s-invite\",\"role\":\"member\",\"expiryHours\":24}", username),
            stewardToken);
        if (inviteResp.statusCode() != 201) {
            throw new IllegalStateException(
                "invite mint failed (" + inviteResp.statusCode() + "): " + inviteResp.body());
        }
        var code = MAPPER.readTree(inviteResp.body()).get("code").asText();

        var redeemResp = post(baseUrl + "/api/auth/redeem", String.format(
            "{\"code\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
            code, username, password, displayName), null);
        if (redeemResp.statusCode() != 201) {
            throw new IllegalStateException(
                "invite redeem failed (" + redeemResp.statusCode() + "): " + redeemResp.body());
        }
        return MAPPER.readTree(redeemResp.body());
    }

    private static HttpResponse<String> post(String url, String body, String bearer) throws Exception {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
