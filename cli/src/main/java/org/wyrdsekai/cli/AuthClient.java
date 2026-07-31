package org.wyrdsekai.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP client for authentication endpoints.
 * Calls the server's /api/auth/* routes.
 */
public class AuthClient {

    private final String baseUrl;
    private final HttpClient httpClient;

    public AuthClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.httpClient = HttpClient.newHttpClient();
    }

    /** Result of an auth operation. */
    public record AuthResult(boolean success, String token, String userId, String username, String error) {
        static AuthResult ok(String token, String userId, String username) {
            return new AuthResult(true, token, userId, username, null);
        }
        static AuthResult fail(String error) {
            return new AuthResult(false, null, null, null, error);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResponse(String token, String userId, String username) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(String error) {}

    public AuthResult login(String username, String password) {
        try {
            var body = Json.mapper().writeValueAsString(
                new LoginRequest(username, password));
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (Exception e) {
            return AuthResult.fail("Connection failed: " + e.getMessage());
        }
    }

    public AuthResult register(String username, String password, String displayName) {
        try {
            var body = Json.mapper().writeValueAsString(
                new RegisterRequest(username, password, displayName));
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (Exception e) {
            return AuthResult.fail("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Redeem an invitation code to join the household — the invite-only entry
     * path (parity with SSH's invite-as-password flow). {@code code} is the
     * 6-word invitation; {@code password} becomes the new account's password
     * (callers pass the code itself, matching "password = the code" from the
     * setup banner). The account's role (steward / member) comes from the
     * invite. Joining is invite-only: there is no inviteless self-registration
     * on this path.
     */
    public AuthResult redeem(String username, String code, String password) {
        try {
            var body = Json.mapper().writeValueAsString(
                new RedeemRequest(code, username, password, username));
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/redeem"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (Exception e) {
            return AuthResult.fail("Connection failed: " + e.getMessage());
        }
    }

    private AuthResult parseResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            var auth = Json.mapper().readValue(response.body(), AuthResponse.class);
            return AuthResult.ok(auth.token(), auth.userId(), auth.username());
        } else {
            try {
                var err = Json.mapper().readValue(response.body(), ErrorResponse.class);
                return AuthResult.fail(err.error());
            } catch (Exception e) {
                return AuthResult.fail("Server error: " + response.statusCode());
            }
        }
    }

    record LoginRequest(String username, String password) {}
    record RegisterRequest(String username, String password, String display_name) {}
    record RedeemRequest(String code, String username, String password, String displayName) {}
}
