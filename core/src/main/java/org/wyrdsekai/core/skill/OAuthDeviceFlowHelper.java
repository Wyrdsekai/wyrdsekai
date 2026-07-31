package org.wyrdsekai.core.skill;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OAuth 2.0 Device Authorization Flow (RFC 8628).
 * Shared utility for Google, Microsoft, GitHub, and any OAuth provider.
 *
 * Flow:
 * 1. startFlow() → returns URL + user code for display
 * 2. Agent presents URL + code to user in-world
 * 3. User authorizes in browser on any device
 * 4. awaitAuthorization() polls for token completion
 * 5. Refresh token stored in The Safe (AES-256-GCM encrypted)
 */
public class OAuthDeviceFlowHelper {

    private final HttpClient httpClient;
    private final ObjectMapper json;

    /** Known provider configurations. */
    public enum Provider {
        GOOGLE("https://oauth2.googleapis.com/device/code",
               "https://oauth2.googleapis.com/token",
               "urn:ietf:params:oauth:grant-type:device_code"),
        MICROSOFT("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode",
                  "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                  "urn:ietf:params:oauth:grant-type:device_code"),
        GITHUB("https://github.com/login/device/code",
               "https://github.com/login/oauth/access_token",
               "urn:ietf:params:oauth:grant-type:device_code");

        public final String deviceCodeEndpoint;
        public final String tokenEndpoint;
        public final String grantType;

        Provider(String deviceCodeEndpoint, String tokenEndpoint, String grantType) {
            this.deviceCodeEndpoint = deviceCodeEndpoint;
            this.tokenEndpoint = tokenEndpoint;
            this.grantType = grantType;
        }
    }

    /** The challenge presented to the user. */
    public record DeviceFlowChallenge(
        Provider provider,
        String deviceCode,
        String userCode,
        String verificationUri,
        int expiresInSeconds,
        int intervalSeconds,
        Instant issuedAt,
        String clientId
    ) {
        /** Human-readable instruction for in-world display. */
        public String instruction() {
            return String.format("Please visit %s and enter code: %s", verificationUri, userCode);
        }
    }

    /** OAuth tokens returned on successful authorization. */
    public record OAuthTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresInSeconds,
        String scope,
        Instant obtainedAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(obtainedAt.plusSeconds(expiresInSeconds - 60));
        }
    }

    public OAuthDeviceFlowHelper() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.json = new ObjectMapper();
    }

    /** Start the device authorization flow. Returns URL + code for display. */
    public DeviceFlowChallenge startFlow(Provider provider, String clientId, String scopes)
            throws IOException, InterruptedException {
        String body = "client_id=" + clientId + "&scope=" + scopes;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(provider.deviceCodeEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = json.readTree(response.body());

        return new DeviceFlowChallenge(
            provider,
            node.path("device_code").asText(),
            node.path("user_code").asText(),
            node.path("verification_uri").asText(node.path("verification_url").asText()),
            node.path("expires_in").asInt(300),
            node.path("interval").asInt(5),
            Instant.now(),
            clientId
        );
    }

    /** Poll for authorization completion. Blocks until authorized or timeout. */
    public OAuthTokens awaitAuthorization(DeviceFlowChallenge challenge, Duration timeout)
            throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        int interval = Math.max(challenge.intervalSeconds(), 5);

        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(interval * 1000L);

            String body = "client_id=" + challenge.clientId()
                + "&device_code=" + challenge.deviceCode()
                + "&grant_type=" + challenge.provider().grantType;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(challenge.provider().tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(response.body());

            if (node.has("access_token")) {
                return new OAuthTokens(
                    node.path("access_token").asText(),
                    node.path("refresh_token").asText(null),
                    node.path("token_type").asText("Bearer"),
                    node.path("expires_in").asInt(3600),
                    node.path("scope").asText(""),
                    Instant.now()
                );
            }

            String error = node.path("error").asText("");
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                if ("slow_down".equals(error)) interval += 5;
                continue;
            }

            // Other errors: expired_token, access_denied, etc.
            throw new IOException("OAuth device flow failed: " + error
                + " — " + node.path("error_description").asText(""));
        }

        throw new IOException("OAuth device flow timed out after " + timeout);
    }

    /** Refresh an expired access token using a stored refresh token. */
    public OAuthTokens refresh(Provider provider, String clientId, String refreshToken)
            throws IOException, InterruptedException {
        String body = "client_id=" + clientId
            + "&refresh_token=" + refreshToken
            + "&grant_type=refresh_token";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(provider.tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = json.readTree(response.body());

        if (node.has("access_token")) {
            return new OAuthTokens(
                node.path("access_token").asText(),
                node.path("refresh_token").asText(refreshToken), // Some providers rotate
                node.path("token_type").asText("Bearer"),
                node.path("expires_in").asInt(3600),
                node.path("scope").asText(""),
                Instant.now()
            );
        }

        throw new IOException("Token refresh failed: " + node.path("error").asText());
    }
}
