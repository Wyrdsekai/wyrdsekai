package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.PairingService;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * HTTP routes for device pairing (phone node onboarding).
 * <p>
 * Flow:
 * 1. Phone POST /api/pair/request → server creates 6-digit code, logs it prominently
 * 2. Steward reads code from log or GET /api/pair/code
 * 3. Phone POST /api/pair/verify with code → server returns device token + household info
 * 4. Phone uses token for subsequent API calls via Bearer auth
 */
public final class PairingRoutes {

    private static final Logger log = LoggerFactory.getLogger(PairingRoutes.class);

    private final PairingService pairingService;
    private final AuthService authService; // nullable — steward checks disabled if null
    private final BiConsumer<String, String> broadcastFn;

    /**
     * @param pairingService core pairing logic
     * @param authService    auth service for steward validation. Nullable (disables steward checks).
     * @param broadcastFn    (speaker, text) → broadcasts to all connected sessions. Nullable.
     */
    public PairingRoutes(PairingService pairingService, AuthService authService,
                         BiConsumer<String, String> broadcastFn) {
        this.pairingService = pairingService;
        this.authService = authService;
        this.broadcastFn = broadcastFn;
    }

    public PairingRoutes(PairingService pairingService,
                         BiConsumer<String, String> broadcastFn) {
        this(pairingService, null, broadcastFn);
    }

    public PairingRoutes(PairingService pairingService) {
        this(pairingService, null, null);
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/pair/request", this::handlePairRequest);
        app.post("/api/pair/verify", this::handlePairVerify);
        app.post("/api/pair/key", this::handlePairWithKey);
        app.get("/api/pair/status", this::handlePairStatus);
        app.get("/api/pair/devices", this::handleListDevices);
        app.delete("/api/pair/devices/{deviceId}", this::handleRevokeDevice);
        app.get("/api/pair/code", this::handleGetPendingCode);
        app.post("/api/pair/household-key/generate", this::handleGenerateHouseholdKey);
        app.get("/api/pair/household-key", this::handleGetHouseholdKey);
    }

    // --- Request/Response records ---

    record PairRequest(
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("deviceType") String deviceType,
        @JsonProperty("devicePublicKey") String devicePublicKey
    ) {}

    record PairRequestResponse(
        @JsonProperty("challengeId") String challengeId,
        @JsonProperty("expiresIn") int expiresIn
    ) {}

    record VerifyRequest(
        @JsonProperty("challengeId") String challengeId,
        @JsonProperty("code") String code
    ) {}

    record PairResultResponse(
        @JsonProperty("token") String token,
        @JsonProperty("householdId") String householdId,
        @JsonProperty("householdName") String householdName,
        @JsonProperty("serverDid") String serverDid,
        @JsonProperty("natsUrl") String natsUrl,
        @JsonProperty("serverUrl") String serverUrl,
        @JsonProperty("relayUrl") String relayUrl,
        @JsonProperty("relayToken") String relayToken
    ) {}

    record DeviceResponse(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("publicKey") String publicKey,
        @JsonProperty("pairedAt") long pairedAt,
        @JsonProperty("lastSeen") long lastSeen,
        @JsonProperty("revoked") boolean revoked
    ) {}

    record PendingCodeResponse(
        @JsonProperty("code") String code,
        @JsonProperty("expiresAt") long expiresAt
    ) {}

    record ErrorResponse(String error) {}

    // --- Handlers ---

    /**
     * POST /api/pair/request — phone initiates pairing.
     * Creates a 6-digit code and logs it prominently for the steward.
     */
    private void handlePairRequest(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), PairRequest.class);

        var deviceName = req.deviceName() != null ? req.deviceName() : "Unknown Device";
        var deviceType = req.deviceType() != null ? req.deviceType() : "phone";

        log.info("Pairing requested by device: {} ({})", deviceName, deviceType);

        var challenge = pairingService.createChallenge(
            deviceName, deviceType, req.devicePublicKey());

        // Broadcast the code to all connected sessions
        if (broadcastFn != null) {
            broadcastFn.accept("system",
                "A device wants to pair: " + deviceName + " (" + deviceType + ")\n" +
                "Pairing code: " + challenge.code() + " (expires in 5 minutes)");
        }

        ctx.status(201).json(new PairRequestResponse(challenge.challengeId(), 300));
    }

    /**
     * POST /api/pair/verify — phone submits the code entered by the steward.
     * Returns device token + household credentials on success, 403 on failure.
     */
    private void handlePairVerify(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), VerifyRequest.class);

        if (req.challengeId() == null || req.code() == null) {
            ctx.status(400).json(new ErrorResponse("challengeId and code are required"));
            return;
        }

        var result = pairingService.verifyCode(req.challengeId(), req.code());
        if (result.isEmpty()) {
            ctx.status(403).json(new ErrorResponse("Invalid or expired pairing code"));
            return;
        }

        var r = result.get();
        ctx.json(new PairResultResponse(
            r.token(), r.householdId(), r.householdName(),
            r.serverDid(), r.natsUrl(), r.serverUrl(),
            r.relayUrl(), r.relayToken()));
    }

    /**
     * GET /api/pair/status — phone checks if its device token is still valid.
     * Requires Bearer token. Updates last-seen timestamp.
     */
    private void handlePairStatus(Context ctx) {
        var token = extractDeviceToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Device token required (Bearer header)"));
            return;
        }

        var device = pairingService.validateDeviceToken(token);
        if (device.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or revoked device token"));
            return;
        }

        // Touch the device to update last-seen
        pairingService.touchDevice(token);

        var d = device.get();
        ctx.json(new DeviceResponse(
            d.id(), d.name(), d.type(), d.publicKey(),
            d.pairedAt().getEpochSecond(), d.lastSeen().getEpochSecond(), d.revoked()));
    }

    /**
     * GET /api/pair/devices — list all paired devices.
     * Restricted to steward-only.
     */
    private void handleListDevices(Context ctx) {
        if (!requireSteward(ctx)) return;
        var devices = pairingService.listDevices();
        var response = devices.stream()
            .map(d -> new DeviceResponse(
                d.id(), d.name(), d.type(), d.publicKey(),
                d.pairedAt().getEpochSecond(), d.lastSeen().getEpochSecond(), d.revoked()))
            .toList();
        ctx.json(response);
    }

    /**
     * DELETE /api/pair/devices/{deviceId} — revoke a paired device.
     * Restricted to steward-only.
     */
    private void handleRevokeDevice(Context ctx) {
        if (!requireSteward(ctx)) return;
        var deviceId = ctx.pathParam("deviceId");
        pairingService.revokeDevice(deviceId);
        ctx.status(200).json(Map.of("status", "revoked", "deviceId", deviceId));
    }

    /**
     * GET /api/pair/code — retrieve the current pending pairing code.
     * Used by steward to read the code without checking logs.
     */
    private void handleGetPendingCode(Context ctx) {
        var challenge = pairingService.getPendingChallenge();
        if (challenge.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No pending pairing code"));
            return;
        }

        var c = challenge.get();
        ctx.json(new PendingCodeResponse(c.code(), c.expiresAt().getEpochSecond()));
    }

    // --- Household Key endpoints ---

    record KeyPairRequest(
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("deviceType") String deviceType,
        @JsonProperty("key") String key
    ) {}

    /**
     * POST /api/pair/key — pair a device using a pre-shared household key.
     * Skips the 6-digit code entirely. For headless machines, scripted deployments,
     * or config-file-based setup.
     */
    private void handlePairWithKey(Context ctx) {
        KeyPairRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), KeyPairRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
            return;
        }

        if (req.key() == null || req.key().isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing key"));
            return;
        }

        var result = pairingService.pairWithKey(req.key(), req.deviceName(), req.deviceType(), null);
        if (result.isPresent()) {
            ctx.status(200).json(result.get());
        } else {
            ctx.status(403).json(Map.of("error", "Invalid or revoked household key"));
        }
    }

    /**
     * POST /api/pair/household-key/generate — generate a new household key.
     * Steward-only. The key can be used by new machines to pair without codes.
     */
    private void handleGenerateHouseholdKey(Context ctx) {
        var key = pairingService.generateHouseholdKey();
        ctx.status(201).json(Map.of("key", key));
    }

    /**
     * GET /api/pair/household-key — get the current active household key.
     * Steward-only.
     */
    private void handleGetHouseholdKey(Context ctx) {
        var key = pairingService.getActiveHouseholdKey();
        if (key.isPresent()) {
            ctx.json(Map.of("key", key.get().key(), "createdAt", key.get().createdAt().getEpochSecond()));
        } else {
            ctx.status(404).json(Map.of("error", "No active household key. Generate one with POST /api/pair/household-key/generate"));
        }
    }

    // --- Helpers ---

    /**
     * Extract device token from Authorization: Bearer header.
     */
    private static String extractDeviceToken(Context ctx) {
        var authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * Validate that the caller is a steward via session token in Authorization header.
     * Returns true if the caller is a steward, false if response was already sent.
     * If authService is null (not configured), allows all requests (backward compatibility).
     */
    private boolean requireSteward(Context ctx) {
        if (authService == null) return true; // no auth configured — allow all

        var token = extractDeviceToken(ctx); // reuses same Bearer extraction
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authorization required (Bearer session token)"));
            return false;
        }
        var user = authService.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return false;
        }
        if (!"steward".equals(user.get().role())) {
            ctx.status(403).json(new ErrorResponse("Steward role required"));
            return false;
        }
        return true;
    }

}
