package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles zone creation and join flows for the Room-Node Topology.
 *
 * <p>A zone is a household's shared world — the set of nodes that cooperate
 * through the Between. Zone creation is a one-time setup; joining is an
 * approval-based flow using short-lived tokens.</p>
 *
 * <p>Join token format: Base64(JSON) signed with HMAC-SHA256 using the zone's secret.
 * Tokens expire after 15 minutes by default.</p>
 */
public final class ZoneSetup {

    /** Default token expiry: 15 minutes. */
    static final Duration TOKEN_EXPIRY = Duration.ofMinutes(15);

    private ZoneSetup() {}

    /**
     * Zone identity info.
     */
    public record ZoneInfo(
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("zoneName") String zoneName,
        @JsonProperty("creatorDid") String creatorDid,
        @JsonProperty("secret") byte[] secret,
        @JsonProperty("createdAt") Instant createdAt
    ) {
        public ZoneInfo {
            if (zoneId == null || zoneId.isBlank()) throw new IllegalArgumentException("Zone ID required");
            if (zoneName == null || zoneName.isBlank()) throw new IllegalArgumentException("Zone name required");
            if (creatorDid == null || creatorDid.isBlank()) throw new IllegalArgumentException("Creator DID required");
            if (secret == null || secret.length != 32) throw new IllegalArgumentException("Secret must be 32 bytes");
            if (createdAt == null) createdAt = Instant.now();
        }
    }

    /**
     * Request to join a zone.
     */
    public record JoinRequest(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("hostname") String hostname,
        @JsonProperty("requestingDid") String requestingDid
    ) {}

    /**
     * Approval to join a zone (included in the token).
     */
    public record JoinApproval(
        @JsonProperty("zoneId") String zoneId,
        @JsonProperty("approvedBy") String approvedBy
    ) {}

    /**
     * Create a new zone with a generated ID and 32-byte secret.
     *
     * @param zoneName human-readable name (e.g., "Operator's Zone")
     * @param creator  the player account creating the zone
     * @return the new zone info including the HMAC secret
     */
    public static ZoneInfo createZone(String zoneName, PlayerAccount creator) {
        var zoneId = "zone-" + UUID.randomUUID().toString().substring(0, 12);
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new ZoneInfo(zoneId, zoneName, creator.did(), secret, Instant.now());
    }

    /**
     * Generate a join token that a new node can use to join the zone.
     * The token is HMAC-signed and expires after {@link #TOKEN_EXPIRY}.
     *
     * @param zone       the zone to join
     * @param approverDid DID of the steward approving the join
     * @return Base64-encoded join token
     */
    public static String generateJoinToken(ZoneInfo zone, String approverDid) {
        var expiresAt = Instant.now().plus(TOKEN_EXPIRY);
        var payload = zone.zoneId() + "|" + zone.zoneName() + "|" + approverDid + "|"
            + expiresAt.getEpochSecond();
        var signature = hmacSign(payload.getBytes(StandardCharsets.UTF_8), zone.secret());
        var token = payload + "|" + Base64.getEncoder().encodeToString(signature);
        return Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate a join token and extract the zone info.
     * Returns empty if the token is invalid, expired, or tampered with.
     *
     * @param token      Base64-encoded join token
     * @param zoneSecret the 32-byte zone secret to verify the HMAC
     * @return the zone ID + approver if valid, empty otherwise
     */
    public static Optional<JoinApproval> validateJoinToken(String token, byte[] zoneSecret) {
        try {
            var decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            var lastPipe = decoded.lastIndexOf('|');
            if (lastPipe < 0) return Optional.empty();

            var payload = decoded.substring(0, lastPipe);
            var signatureB64 = decoded.substring(lastPipe + 1);
            var expectedSig = hmacSign(payload.getBytes(StandardCharsets.UTF_8), zoneSecret);

            // Constant-time comparison
            var actualSig = Base64.getDecoder().decode(signatureB64);
            if (!constantTimeEquals(expectedSig, actualSig)) {
                return Optional.empty();
            }

            // Parse payload: zoneId|zoneName|approverDid|expiresAt
            var parts = payload.split("\\|");
            if (parts.length != 4) return Optional.empty();

            var expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
            if (Instant.now().isAfter(expiresAt)) {
                return Optional.empty(); // expired
            }

            return Optional.of(new JoinApproval(parts[0], parts[2]));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // --- Crypto helpers ---

    private static byte[] hmacSign(byte[] data, byte[] secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
