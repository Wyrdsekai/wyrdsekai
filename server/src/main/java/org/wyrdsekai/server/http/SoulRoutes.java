package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.agent.LanguageHealLedger;
import org.wyrdsekai.core.agent.LanguageHealth;
import org.wyrdsekai.core.config.WyrdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;

import java.time.Instant;
import java.util.List;

/**
 * HTTP routes for client-side soul sync (Phase 9).
 * Phones download and upload soul manifests via these endpoints.
 *
 *   GET  /api/soul/{did}                 — latest manifest for a DID
 *   GET  /api/soul/{did}/history         — version listing (no full manifests)
 *   GET  /api/soul/{did}/version/{version} — specific version
 *   POST /api/soul/{did}                 — upload/sync a manifest from a phone
 */
public final class SoulRoutes {

    private static final Logger log = LoggerFactory.getLogger(SoulRoutes.class);

    private final SoulStore soulStore;
    private final AuthService auth;
    // #7-followup (2026-07-19 adversarial review) — phones authenticate soul
    // sync with their DEVICE (pairing) token, not a session token; without this
    // the phone→server soul push (CompanionEngine.serverSoulStore) 401s on every
    // save and silently no-ops, so on-device soul evolution never reaches the
    // household. Nullable — session auth still works when it's absent.
    private final PairingService pairingService;
    // Adversarial-review fix (2026-07-20): a soul manifest carries the companion's
    // private interior (inner-monologue fragments) and is authoritative state.
    // Reads/writes are scoped to the STEWARD or the companion's BONDHOLDER (the
    // bondholder DID is the user id — see BondAdminMain). Without this, ANY authed
    // household member could read another member's companion manifest or overwrite
    // it. Nullable: when absent, only the steward is authorized (fail-closed).
    private final BondStore bondStore;

    public SoulRoutes(SoulStore soulStore, AuthService auth) {
        this(soulStore, auth, null, null);
    }

    public SoulRoutes(SoulStore soulStore, AuthService auth, PairingService pairingService) {
        this(soulStore, auth, pairingService, null);
    }

    public SoulRoutes(SoulStore soulStore, AuthService auth, PairingService pairingService,
                      BondStore bondStore) {
        this.soulStore = soulStore;
        this.auth = auth;
        this.pairingService = pairingService;
        this.bondStore = bondStore;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/soul/list", this::listSouls);
        app.get("/api/soul/{did}", this::getLatest);
        app.get("/api/soul/{did}/history", this::getHistory);
        app.get("/api/soul/{did}/version/{version}", this::getVersion);
        app.get("/api/soul/{did}/language", this::languageHealth);
        app.post("/api/soul/{did}/language/heal", this::healLanguageNow);
        app.post("/api/soul/{did}", this::syncManifest);
        app.post("/api/soul/{did}/identity", this::setIdentity);
    }

    // --- Request/Response records ---

    record VersionEntry(
        int version,
        @JsonProperty("forged_at") Instant forgedAt,
        @JsonProperty("content_hash") String contentHash
    ) {}

    record SyncResponse(String status, int version) {}

    record ErrorResponse(String error) {}

    record SoulListEntry(
        String did,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("manifestVersion") int manifestVersion,
        @JsonProperty("forgedAt") long forgedAt
    ) {}

    // --- Handlers ---

    /**
     * GET /api/soul/list — list all available souls (latest version per DID).
     * Returns a JSON array of lightweight SoulListEntry objects for the
     * soul seed provisioning UI on phones.
     */
    private void listSouls(Context ctx) {
        var userId = requireAuth(ctx);
        if (userId == null) return;

        log.debug("Soul list requested by user={}", userId);

        var manifests = soulStore.listLatest();
        var entries = manifests.stream()
            .map(m -> new SoulListEntry(
                m.did(),
                m.profile() != null ? m.profile().name() : "",
                m.manifestVersion(),
                m.forgedAt() != null ? m.forgedAt().toEpochMilli() : 0L
            ))
            .toList();

        ctx.json(entries);
    }

    /**
     * GET /api/soul/{did}/language — what the language healer sees in this soul: the notes it
     * reads as another language than the household's, each with its strikes and whether it has
     * been left in peace. A steward whose node was busy re-rendering could not tell whether the
     * notes were really in another language or the detector misread them, since the soul files
     * are the node's own (a household's 0.5.4 report, 2026-10-05). Scoped like the manifest.
     */
    private void languageHealth(Context ctx) {
        var who = ctx.pathParam("did");
        // On the household machine the steward's hand is `sudo wyrd …` with the machine's operator
        // token, which the policy layer resolves as the steward. The soul routes otherwise take a
        // person's login or a paired device, scoped to the steward or the companion's bondholder.
        String userId = null;
        if (!ApiAuth.isOperator(ctx)) {
            userId = requireAuth(ctx);
            if (userId == null) return;
        }
        var did = resolveDid(who);
        if (did == null) {
            ctx.status(404).json(new ErrorResponse("No soul found for: " + who));
            return;
        }
        if (userId != null && !mayAccessSoul(userId, did)) {
            log.warn("Soul access denied: user={} did={}", userId, did);
            ctx.status(403).json(new ErrorResponse("Not authorized for this soul"));
            return;
        }
        var manifest = soulStore.latest(did);
        if (manifest.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No manifest found for DID: " + did));
            return;
        }
        var ledger = LanguageHealLedger.inMemory();
        try {
            var p = LanguageHealLedger.pathFor(WyrdConfig.get().dataDir(), did);
            if (p != null) ledger = LanguageHealLedger.load(p);
        } catch (RuntimeException e) {
            log.debug("Language heal ledger for {} not read: {}", did, e.toString());
        }
        var m = manifest.get();
        var name = m.profile() != null ? m.profile().name() : null;
        ctx.json(LanguageHealth.of(m, LanguageHealth.householdLanguage(), ledger, name));
    }

    /**
     * POST /api/soul/{did}/language/heal — mend her off-language notes now (`wyrd soul language
     * <name> heal`): the running companion runs the healer's passes back to back, without waiting
     * for the interval or a quiet lane, one rewrite at a time as always. Answers 202 with what the
     * healer sees at this moment; 409 when she is not running here. Scoped like the view.
     */
    private void healLanguageNow(Context ctx) {
        var who = ctx.pathParam("did");
        String userId = null;
        if (!ApiAuth.isOperator(ctx)) {
            userId = requireAuth(ctx);
            if (userId == null) return;
        }
        var did = resolveDid(who);
        if (did == null) {
            ctx.status(404).json(new ErrorResponse("No soul found for: " + who));
            return;
        }
        if (userId != null && !mayAccessSoul(userId, did)) {
            ctx.status(403).json(new ErrorResponse("Not authorized for this soul"));
            return;
        }
        var manifest = soulStore.latest(did);
        if (manifest.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No manifest found for DID: " + did));
            return;
        }
        var m = manifest.get();
        var entityId = m.profile() != null ? m.profile().entityId() : null;
        var ref = entityId != null ? ZoneGuardian.getCompanionRef(null, entityId) : null;
        if (ref == null) {
            ctx.status(409).json(new ErrorResponse("That companion is not running on this node"));
            return;
        }
        ref.tell(new CompanionActor.HealLanguageNow());
        var ledger = LanguageHealLedger.inMemory();
        try {
            var p = LanguageHealLedger.pathFor(WyrdConfig.get().dataDir(), did);
            if (p != null) ledger = LanguageHealLedger.load(p);
        } catch (RuntimeException ignored) { }
        var name = m.profile() != null ? m.profile().name() : null;
        log.info("Language healing asked for now on '{}' by {}", name, userId == null ? "the operator" : userId);
        ctx.status(202).json(LanguageHealth.of(m, LanguageHealth.householdLanguage(), ledger, name));
    }

    /** A DID as given, or the DID of the companion with this name, case-blind; null for neither. */
    private String resolveDid(String who) {
        if (who == null || who.isBlank()) return null;
        if (who.startsWith("did:")) return who;
        var want = who.strip();
        for (var m : soulStore.listLatest()) {
            if (m.profile() != null && m.profile().name() != null && m.profile().name().equalsIgnoreCase(want)) {
                return m.did();
            }
        }
        return null;
    }

    /**
     * GET /api/soul/{did} — download the latest manifest for a DID.
     * Scoped to the steward or the companion's bondholder (the manifest carries
     * private interior content).
     */
    private void getLatest(Context ctx) {
        var did = ctx.pathParam("did");
        var userId = requireSoulAccess(ctx, did);
        if (userId == null) return;

        log.debug("Soul manifest requested: did={}, by user={}", did, userId);

        var manifest = soulStore.latest(did);
        if (manifest.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No manifest found for DID: " + did));
            return;
        }

        ctx.contentType("application/json");
        ctx.result(toJson(manifest.get()));
    }

    /**
     * GET /api/soul/{did}/history — list all versions (lightweight, no full manifests).
     */
    private void getHistory(Context ctx) {
        var userId = requireAuth(ctx);
        if (userId == null) return;

        var did = ctx.pathParam("did");
        log.debug("Soul history requested: did={}, by user={}", did, userId);

        var manifests = soulStore.history(did);
        var entries = manifests.stream()
            .map(m -> new VersionEntry(m.manifestVersion(), m.forgedAt(), m.contentHash()))
            .toList();

        ctx.json(entries);
    }

    /**
     * GET /api/soul/{did}/version/{version} — download a specific version.
     */
    private void getVersion(Context ctx) {
        var did = ctx.pathParam("did");
        var userId = requireSoulAccess(ctx, did);
        if (userId == null) return;

        var versionStr = ctx.pathParam("version");

        int version;
        try {
            version = Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(new ErrorResponse("Invalid version number: " + versionStr));
            return;
        }

        log.debug("Soul manifest version requested: did={}, version={}, by user={}", did, version, userId);

        var manifest = soulStore.load(did, version);
        if (manifest.isEmpty()) {
            ctx.status(404).json(new ErrorResponse(
                "No manifest found for DID: " + did + " version: " + version));
            return;
        }

        ctx.contentType("application/json");
        ctx.result(toJson(manifest.get()));
    }

    /**
     * POST /api/soul/{did} — upload/sync a manifest from a phone.
     * Validates DID in URL matches DID in body.
     */
    /**
     * Her description of herself, written into the manifest as a new version: the move gate
     * (`wyrd brain move`) drafts it from her own record and the steward approves it. Until it is
     * set, the first-run greeter leads her prompt. Steward or bondholder only; takes effect when
     * she next loads her manifest (a restart).
     */
    private void setIdentity(Context ctx) {
        var did = ctx.pathParam("did");
        // The launcher runs the move on the node itself with the operator token (loopback); a
        // person's steward or bondholder session is the other door.
        var userId = ApiAuth.isOperator(ctx) ? "operator" : requireSoulAccess(ctx, did);
        if (userId == null) return;
        String text;
        try {
            var node = Json.mapper().readTree(ctx.body());
            text = node.path("text").asText("").strip();
        } catch (Exception e) {
            ctx.status(400).json(new ErrorResponse("Invalid JSON: " + e.getMessage()));
            return;
        }
        if (text.isEmpty() || text.length() > 2000) {
            ctx.status(400).json(new ErrorResponse("text: 1 to 2000 characters"));
            return;
        }
        var latest = soulStore.latest(did);
        if (latest.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No manifest for " + did));
            return;
        }
        var m = latest.get();
        var next = m.withResidentIdentity(text).withManifestVersion(m.manifestVersion() + 1, java.time.Instant.now());
        soulStore.store(next);
        log.info("Soul identity text set: did={}, version={} -> {}, by user={}", did, m.manifestVersion(),
            next.manifestVersion(), userId);
        ctx.json(new SyncResponse("stored", next.manifestVersion()));
    }

    private void syncManifest(Context ctx) throws Exception {
        var did = ctx.pathParam("did");
        var userId = requireSoulAccess(ctx, did);
        if (userId == null) return;

        SoulManifest manifest;
        try {
            manifest = Json.mapper().readValue(ctx.body(), SoulManifest.class);
        } catch (Exception e) {
            ctx.status(400).json(new ErrorResponse("Invalid manifest JSON: " + e.getMessage()));
            return;
        }

        // Validate DID in URL matches DID in body
        if (manifest.did() == null || !manifest.did().equals(did)) {
            ctx.status(400).json(new ErrorResponse(
                "DID mismatch: URL has '" + did + "' but body has '" + manifest.did() + "'"));
            return;
        }

        log.info("Soul manifest sync: did={}, version={}, by user={}",
            did, manifest.manifestVersion(), userId);

        soulStore.store(manifest);

        ctx.json(new SyncResponse("stored", manifest.manifestVersion()));
    }

    // --- Auth helper ---

    /**
     * Extract and validate the authenticated user. Returns userId if valid, null if rejected.
     * Sends 401 response on failure.
     */
    private String requireAuth(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authentication required"));
            return null;
        }
        var user = auth.validateSession(token);
        if (user.isPresent()) {
            return user.get().id();
        }
        // #7-followup — fall back to device-token auth so a paired phone can sync
        // its soul (it holds a device/pairing token, not a session token). The
        // device is bound to an account; resolve it and authorize as that user.
        if (pairingService != null) {
            var deviceUser = pairingService.findUserForDevice(token);
            if (deviceUser.isPresent()) {
                return deviceUser.get();
            }
        }
        ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
        return null;
    }

    /**
     * requireAuth + soul-access scope. Returns the caller's userId if they may
     * read/write the soul of {@code did}, else null (401/403 already sent).
     */
    private String requireSoulAccess(Context ctx, String did) {
        var userId = requireAuth(ctx);
        if (userId == null) return null;
        if (!mayAccessSoul(userId, did)) {
            log.warn("Soul access denied: user={} did={}", userId, did);
            ctx.status(403).json(new ErrorResponse("Not authorized for this soul"));
            return null;
        }
        return userId;
    }

    /**
     * True if {@code callerUserId} may access the soul of companion {@code did}:
     * the household steward, or the companion's bondholder (bondholder DID == user
     * id, per BondAdminMain). Fail-closed to steward-only when no BondStore is wired.
     */
    private boolean mayAccessSoul(String callerUserId, String did) {
        if (callerUserId == null || did == null) return false;
        // Steward — household admin, full access (browse/import/manage souls).
        if (auth.findUser(callerUserId)
                .map(u -> "steward".equals(u.role())).orElse(false)) {
            return true;
        }
        // Bondholder of this companion — the other party of a bond on this DID is
        // the bondholder's user id. Their own device sync stays authorized.
        if (bondStore != null) {
            for (var b : bondStore.bondsForAgent(did)) {
                if (b != null && callerUserId.equals(b.otherParty(did))) return true;
            }
        }
        return false;
    }

    /**
     * Serialize a SoulManifest to JSON string using the shared mapper.
     * We write directly to avoid Javalin's default mapper (which may not have JavaTimeModule).
     */
    private static String toJson(SoulManifest manifest) {
        try {
            return Json.mapper().writeValueAsString(manifest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SoulManifest", e);
        }
    }
}
