package org.wyrdsekai.between.federation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.core.soul.SoulTransitProtocol;
import org.wyrdsekai.core.soul.SoulTransitProtocol.TransitMode;
import org.wyrdsekai.core.soul.SoulTransitProtocol.TransitRequest;
import org.wyrdsekai.core.soul.SoulTransitProtocol.ZoneSoulCapabilities;
import org.wyrdsekai.core.soul.SoulVerifier;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC persistence for federation state: bilateral agreements, zone manifests, transit tokens.
 * Uses standard SQL compatible with both SQLite and PostgreSQL.
 *
 * Also serves as the integration point for soul-aware transit (Phase 5):
 * when a SoulStore is available, transit operations resolve mode via
 * SoulTransitProtocol and attach soul identity to transit tokens.
 *
 * <p> canonical: world.db:bilateral_agreements
 * world.db:zone_manifests, world.db:transit_tokens. No shadow stores —
 * these tables are already single-source-of-truth. Reconciliation across
 * peer zones happens via the federation protocol (F6 probe + reconcile),
 * not via duplicated local state.</p>
 */
public final class FederationService {

    private static final Logger log = LoggerFactory.getLogger(FederationService.class);
    private final String jdbcUrl;

    /** Optional soul store — when set, enables soul-aware transit. */
    private volatile SoulStore soulStore;

    /** Zone soul capabilities for this zone (advertised to peers). */
    private volatile ZoneSoulCapabilities localSoulCapabilities = ZoneSoulCapabilities.none();

    /** In-memory visit counter: key = "agentId:zoneId", value = visit count. */
    private final ConcurrentHashMap<String, AtomicInteger> visitCounts
        = new ConcurrentHashMap<>();

    /**
     * Optional grant-sync listener: mirrors agreement writes into the HomeRegistry
     * as {@code home://did:zone:{local}/agreement/{remote}} grants.
     */
    private volatile AgreementGrantSync grantSync;

    public FederationService(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        migrateEpochColumns();
    }

    /**
     * Idempotent in-place migration: add the fencing columns (epoch, epoch_owner)
     * to bilateral_agreements if a pre-fence DB doesn't already have them. The
     * create-schema SQL ships them for fresh installs; this covers existing rows.
     * Best-effort: a "duplicate column" failure means they already exist (SQLite
     * has no ADD COLUMN IF NOT EXISTS), which is fine — we swallow it.
     */
    private void migrateEpochColumns() {
        addColumnIfMissing("epoch", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("epoch_owner", "TEXT NOT NULL DEFAULT ''");
    }

    private void addColumnIfMissing(String column, String columnDef) {
        var sql = "ALTER TABLE bilateral_agreements ADD COLUMN " + column + " " + columnDef;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            log.info("Federation: migrated bilateral_agreements — added '{}' column", column);
        } catch (SQLException e) {
            // Column already present (duplicate-column error) — the common, fine case.
            log.debug("Federation: bilateral_agreements.{} already present ({})", column, e.getMessage());
        }
    }

    /**
     * Attach a HomeRegistry sync so saved/revoked agreements are materialized
     * as Grants on the steward's Home. Idempotent — last attachment wins.
     */
    public void setGrantSync(AgreementGrantSync sync) {
        this.grantSync = sync;
        log.info("FederationService: agreement→grant sync attached");
    }

    // --- Soul-Aware Transit (Phase 5) ---

    /** Set the soul store for soul-aware transit operations. */
    public void setSoulStore(SoulStore soulStore) {
        this.soulStore = soulStore;
        log.info("Federation: soul store attached — soul-aware transit enabled");
    }

    /** Set the local zone's soul capabilities (advertised during transit negotiation). */
    public void setLocalSoulCapabilities(ZoneSoulCapabilities capabilities) {
        this.localSoulCapabilities = capabilities;
        log.info("Federation: local soul capabilities set — soulAware={}, forgeAvailable={}, buddingSupported={}",
            capabilities.soulAware(), capabilities.forgeAvailable(), capabilities.buddingSupported());
    }

    /** Get the local zone's soul capabilities. */
    public ZoneSoulCapabilities getLocalSoulCapabilities() {
        return localSoulCapabilities;
    }

    /** Whether soul-aware transit is available (soul store attached + capabilities set). */
    public boolean isSoulAwareTransitEnabled() {
        return soulStore != null && localSoulCapabilities.soulAware();
    }

    /**
     * Resolve the transit mode for an inbound soul-aware transit request.
     * Uses SoulTransitProtocol.resolveMode to determine visiting/thin-client/budding.
     *
     * @param request            The soul transit request from the agent
     * @param destinationHasModel Whether this zone has a model the agent can use
     * @return Resolved transit mode
     */
    public TransitMode resolveSoulTransitMode(TransitRequest request, boolean destinationHasModel) {
        var mode = SoulTransitProtocol.resolveMode(request, localSoulCapabilities, destinationHasModel);
        log.info("Federation: resolved soul transit mode for agent {} — requested={}, resolved={}",
            request.agentDid(), request.mode(), mode);
        return mode;
    }

    /**
     * Validate a soul transit request against the local soul store.
     *
     * @param request The transit request
     * @return Error message if invalid, empty if valid
     */
    public Optional<String> validateSoulTransit(TransitRequest request) {
        if (soulStore == null) {
            return Optional.of("Soul store not available — soul-aware transit disabled");
        }
        return SoulTransitProtocol.validate(request, soulStore);
    }

    /**
     * Attach soul identity to a transit token when the agent has a soul manifest.
     * Called during token creation to produce a soul-enriched token.
     *
     * @param token    The base transit token
     * @param agentDid Agent's DID (may be null for non-souled agents)
     * @return Token with soul fields attached, or original token if no soul data available
     */
    public TransitToken attachSoulToToken(TransitToken token, String agentDid) {
        if (agentDid == null || soulStore == null) {
            return token;
        }
        var manifest = soulStore.latest(agentDid);
        if (manifest.isPresent()) {
            var m = manifest.get();
            log.info("Federation: attaching soul identity to transit token — did={}, manifestHash={}",
                agentDid, m.contentHash());
            return token.withSoul(agentDid, m.contentHash());
        }
        return token;
    }

    /**
     * Verify a soul manifest arriving via transit.
     *
     * Delegates to SoulVerifier.verifyInbound which reconstructs a minimal
     * AgentIdentity from the manifest's publicKeyMultibase and keyLog, then
     * runs the verification chain (signature, KERI, parent chain).
     *
     * Behavioral verification is skipped since the agent hasn't been observed yet.
     *
     * @param manifest The inbound soul manifest to verify
     * @return Verification result with trust level
     */
    public SoulVerifier.VerificationResult verifySoulManifest(SoulManifest manifest) {
        var result = SoulVerifier.verifyInbound(manifest, soulStore);
        log.info("Federation: soul manifest verified — did={}, trust={}, passed={}, failed={}",
            manifest.did(), result.trustLevel(), result.passed(), result.failed());
        return result;
    }

    // --- Visit Tracking ---

    /** Records a visit by an agent to a zone. */
    public void recordVisit(String agentId, String zoneId) {
        var key = agentId + ":" + zoneId;
        visitCounts.computeIfAbsent(key, k -> new AtomicInteger(0))
            .incrementAndGet();
    }

    /** Returns the number of times an agent has visited a zone. */
    public int getVisitCount(String agentId, String zoneId) {
        var key = agentId + ":" + zoneId;
        var counter = visitCounts.get(key);
        return counter != null ? counter.get() : 0;
    }

    // --- Bilateral Agreements ---

    public void saveAgreement(BilateralAgreement agreement) {
        var sql = "INSERT INTO bilateral_agreements "
            + "(local_zone_id, remote_zone_id, remote_public_key, status, trust_level, agreed_at, expires_at, epoch, epoch_owner) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (local_zone_id, remote_zone_id) DO UPDATE SET "
            + "remote_public_key = excluded.remote_public_key, "
            + "status = excluded.status, "
            + "trust_level = excluded.trust_level, "
            + "agreed_at = excluded.agreed_at, "
            + "expires_at = excluded.expires_at, "
            + "epoch = excluded.epoch, "
            + "epoch_owner = excluded.epoch_owner";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, agreement.localZoneId());
            stmt.setString(2, agreement.remoteZoneId());
            stmt.setString(3, agreement.remotePublicKey());
            stmt.setString(4, agreement.status());
            stmt.setString(5, agreement.trustLevel());
            stmt.setLong(6, agreement.agreedAt().getEpochSecond());
            if (agreement.expiresAt() != null) {
                stmt.setLong(7, agreement.expiresAt().getEpochSecond());
            } else {
                stmt.setNull(7, Types.BIGINT);
            }
            stmt.setLong(8, agreement.epoch());
            stmt.setString(9, agreement.epochOwner() == null ? "" : agreement.epochOwner());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save agreement: {}", e.getMessage());
            return;
        }
        // Mirror to HomeRegistry if configured.
        var sync = grantSync;
        if (sync != null) sync.onSaved(agreement);
    }

    public Optional<BilateralAgreement> getAgreement(String localZoneId, String remoteZoneId) {
        var sql = "SELECT * FROM bilateral_agreements WHERE local_zone_id = ? AND remote_zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, localZoneId);
            stmt.setString(2, remoteZoneId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(agreementFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get agreement: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public List<BilateralAgreement> listAgreements(String localZoneId) {
        var sql = "SELECT * FROM bilateral_agreements WHERE local_zone_id = ? ORDER BY agreed_at DESC";
        var result = new ArrayList<BilateralAgreement>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, localZoneId);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(agreementFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list agreements: {}", e.getMessage());
        }
        return result;
    }

    public void updateAgreementStatus(String localZoneId, String remoteZoneId, String newStatus) {
        var sql = "UPDATE bilateral_agreements SET status = ? "
            + "WHERE local_zone_id = ? AND remote_zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newStatus);
            stmt.setString(2, localZoneId);
            stmt.setString(3, remoteZoneId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update agreement status: {}", e.getMessage());
            return;
        }
        var sync = grantSync;
        if (sync != null) {
            if (BilateralAgreement.STATUS_ACTIVE.equals(newStatus)) {
                getAgreement(localZoneId, remoteZoneId).ifPresent(sync::onSaved);
            } else {
                sync.onStatusChanged(localZoneId, remoteZoneId, newStatus);
            }
        }
    }

    /**
     * Causality-guarded activation: transition an agreement to ACTIVE only if it is
     * currently PENDING. Returns {@code true} iff a pending agreement was activated.
     *
     * <p>This is the fix for the half-open / resurrect-revoked finding surfaced by the
     * {@code PeerHandshake.tla} model (see {@code spec/tla/FINDINGS.md}, P0 #1): the bare
     * {@link #updateAgreementStatus} is a blind {@code SET status=?} with no check on the
     * current status, so a <em>stale or redelivered</em> Accept (we have a JetStream
     * redelivery history — #264) would overwrite a local {@code REVOKED}, resurrecting a
     * revoked agreement. By making the SQL itself conditional on {@code status='pending'},
     * the transition is monotone with respect to the local intent: a Revoke that already
     * landed cannot be undone by a late Accept, and an Accept for which we hold no pending
     * proposal (status NONE) is a no-op rather than a spurious activation.</p>
     */
    public boolean activateAgreementIfPending(String localZoneId, String remoteZoneId) {
        var sql = "UPDATE bilateral_agreements SET status = ? "
            + "WHERE local_zone_id = ? AND remote_zone_id = ? AND status = ?";
        int rows;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, BilateralAgreement.STATUS_ACTIVE);
            stmt.setString(2, localZoneId);
            stmt.setString(3, remoteZoneId);
            stmt.setString(4, BilateralAgreement.STATUS_PENDING);
            rows = stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to activate agreement: {}", e.getMessage());
            return false;
        }
        if (rows == 0) {
            return false;
        }
        var sync = grantSync;
        if (sync != null) {
            getAgreement(localZoneId, remoteZoneId).ifPresent(sync::onSaved);
        }
        return true;
    }

    // --- Epoch fence (spec/tla/PeerHandshakeFenced.tla) -------------------------
    //
    // The status-guard above (activateAgreementIfPending) closes the dangerous
    // resurrect-revoked bug, but the model proves the full fix is a monotone
    // EPOCH carried on every transition: half-open divergence then becomes
    // unreachable without depending on the reconcile loop. The methods below are
    // the epoch-ordered transitions. Comparison is (counter, mintingZone)
    // lexicographic — see BilateralAgreement.isNewerEpoch. Legacy peers/rows carry
    // epoch 0 / owner "", which compares as oldest, so the fence degrades to the
    // status-guard behaviour and stays backward-compatible.

    /**
     * The epoch a fresh local proposal should carry: one past whatever we currently
     * hold for this pair (or 1 if we hold nothing). Monotonic per (local, remote).
     */
    public long nextProposalEpoch(String localZoneId, String remoteZoneId) {
        return getAgreement(localZoneId, remoteZoneId).map(a -> a.epoch() + 1).orElse(1L);
    }

    /**
     * Fenced inbound Accept: move PENDING -> ACTIVE only if the Accept is for our
     * current-or-newer attempt (its token is not strictly older than ours) and we
     * are actually PENDING. Never resurrects REVOKED/NONE; a stale or redelivered
     * Accept for a superseded epoch is ignored. Returns true iff activated.
     */
    public boolean applyInboundAccept(String localZoneId, String remoteZoneId,
                                      long msgEpoch, String msgOwner) {
        var existing = getAgreement(localZoneId, remoteZoneId);
        if (existing.isEmpty()) return false;                 // never proposed (NONE)
        var a = existing.get();
        if (!a.isPending()) return false;                     // don't resurrect/re-activate
        // Apply the strict stale-check only when the peer is fence-aware (epoch > 0).
        // A pre-fence peer sends no epoch (0); we then fall back to the PENDING-only
        // status guard so a fenced zone still interoperates with an un-upgraded peer.
        if (msgEpoch > 0
                && BilateralAgreement.isNewerEpoch(a.epoch(), a.epochOwner(), msgEpoch, normalize(msgOwner))) {
            return false;                                     // our token strictly newer => stale Accept
        }
        long newEpoch = Math.max(a.epoch(), msgEpoch);
        String newOwner = newEpoch == a.epoch() ? a.epochOwner() : normalize(msgOwner);
        saveAgreement(a.withEpoch(newEpoch, newOwner)
            .toStatus(BilateralAgreement.STATUS_ACTIVE));
        var sync = grantSync;
        if (sync != null) getAgreement(localZoneId, remoteZoneId).ifPresent(sync::onSaved);
        return true;
    }

    /**
     * Fenced inbound Revoke: move to REVOKED only if the Revoke is not strictly
     * older than our current token. A stale revoke for a superseded epoch (we have
     * since re-proposed at a higher epoch) is ignored. Returns true iff applied.
     */
    public boolean applyInboundRevoke(String localZoneId, String remoteZoneId,
                                      long msgEpoch, String msgOwner) {
        var existing = getAgreement(localZoneId, remoteZoneId);
        if (existing.isEmpty()) {
            // No local record — record the revocation so a later stale Accept can't
            // create an active agreement out of nothing.
            return false;
        }
        var a = existing.get();
        // Strict stale-check only for a fence-aware peer (epoch > 0); a pre-fence
        // Revoke (epoch 0) applies unconditionally, matching legacy behaviour.
        if (msgEpoch > 0
                && BilateralAgreement.isNewerEpoch(a.epoch(), a.epochOwner(), msgEpoch, normalize(msgOwner))) {
            return false;                                     // our token strictly newer => stale Revoke
        }
        long newEpoch = Math.max(a.epoch(), msgEpoch);
        String newOwner = newEpoch == a.epoch() ? a.epochOwner() : normalize(msgOwner);
        saveAgreement(a.withEpoch(newEpoch, newOwner)
            .toStatus(BilateralAgreement.STATUS_REVOKED));
        var sync = grantSync;
        if (sync != null) sync.onStatusChanged(localZoneId, remoteZoneId, BilateralAgreement.STATUS_REVOKED);
        return true;
    }

    private static String normalize(String owner) {
        return owner == null ? "" : owner;
    }

    public int countActiveAgreements(String localZoneId) {
        var sql = "SELECT COUNT(*) FROM bilateral_agreements WHERE local_zone_id = ? AND status = 'active'";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, localZoneId);
            var rs = stmt.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to count agreements: {}", e.getMessage());
        }
        return 0;
    }

    // --- Zone Manifests ---

    public void saveManifest(ZoneManifest manifest) {
        var sql = "INSERT INTO zone_manifests "
            + "(zone_id, zone_name, public_key, nats_url, artery_port, capabilities, discovered_at, last_seen_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (zone_id) DO UPDATE SET "
            + "zone_name = excluded.zone_name, "
            + "public_key = excluded.public_key, "
            + "nats_url = excluded.nats_url, "
            + "artery_port = excluded.artery_port, "
            + "capabilities = excluded.capabilities, "
            + "last_seen_at = excluded.last_seen_at";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, manifest.zoneId());
            stmt.setString(2, manifest.zoneName());
            stmt.setString(3, manifest.publicKey());
            stmt.setString(4, manifest.natsUrl());
            stmt.setInt(5, manifest.arteryPort());
            stmt.setString(6, String.join(",", manifest.capabilities()));
            long now = Instant.now().getEpochSecond();
            stmt.setLong(7, now);
            stmt.setLong(8, now);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save manifest: {}", e.getMessage());
        }
    }

    public Optional<ZoneManifest> getManifest(String zoneId) {
        var sql = "SELECT * FROM zone_manifests WHERE zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, zoneId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(manifestFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get manifest: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public List<ZoneManifest> listManifests() {
        var sql = "SELECT * FROM zone_manifests ORDER BY zone_name";
        var result = new ArrayList<ZoneManifest>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            var rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(manifestFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list manifests: {}", e.getMessage());
        }
        return result;
    }

    // --- Transit Tokens ---

    public void saveTransitToken(TransitToken token) {
        var sql = "INSERT INTO transit_tokens "
            + "(token_id, agent_id, agent_name, source_zone_id, target_zone_id, "
            + "trust_level, issued_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token.tokenId());
            stmt.setString(2, token.agentId());
            stmt.setString(3, token.agentName());
            stmt.setString(4, token.sourceZoneId());
            stmt.setString(5, token.targetZoneId());
            stmt.setString(6, token.trustLevel());
            stmt.setLong(7, token.issuedAt().getEpochSecond());
            stmt.setLong(8, token.expiresAt().getEpochSecond());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save transit token: {}", e.getMessage());
        }
    }

    public Optional<TransitToken> validateTransitToken(String tokenId) {
        var sql = "SELECT * FROM transit_tokens WHERE token_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tokenId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var token = transitTokenFromRow(rs);
                if (token.isValid()) {
                    return Optional.of(token);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to validate transit token: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void cleanExpiredTokens() {
        var sql = "DELETE FROM transit_tokens WHERE expires_at < ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, Instant.now().getEpochSecond());
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                log.debug("Cleaned {} expired transit tokens", deleted);
            }
        } catch (SQLException e) {
            log.error("Failed to clean expired tokens: {}", e.getMessage());
        }
    }

    public List<TransitToken> listActiveTransitTokens(String targetZoneId) {
        var sql = "SELECT * FROM transit_tokens WHERE target_zone_id = ? AND expires_at > ?";
        var result = new ArrayList<TransitToken>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetZoneId);
            stmt.setLong(2, Instant.now().getEpochSecond());
            var rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(transitTokenFromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list active transit tokens: {}", e.getMessage());
        }
        return result;
    }

    // --- Row mappers ---

    private BilateralAgreement agreementFromRow(ResultSet rs) throws SQLException {
        long expiresEpoch = rs.getLong("expires_at");
        var expiresAt = rs.wasNull() || expiresEpoch == 0 ? null : Instant.ofEpochSecond(expiresEpoch);
        long fenceEpoch = rs.getLong("epoch");
        String fenceOwner = rs.getString("epoch_owner");
        return new BilateralAgreement(
            rs.getString("local_zone_id"),
            rs.getString("remote_zone_id"),
            rs.getString("remote_public_key"),
            rs.getString("status"),
            rs.getString("trust_level"),
            Instant.ofEpochSecond(rs.getLong("agreed_at")),
            expiresAt,
            QuotaPolicy.forTrustLevel(rs.getString("trust_level")),
            QuotaPolicy.forTrustLevel(rs.getString("trust_level")),
            fenceEpoch,
            fenceOwner == null ? "" : fenceOwner
        );
    }

    private ZoneManifest manifestFromRow(ResultSet rs) throws SQLException {
        var capsStr = rs.getString("capabilities");
        var capabilities = capsStr != null && !capsStr.isBlank()
            ? List.of(capsStr.split(","))
            : List.<String>of();
        return new ZoneManifest(
            rs.getString("zone_id"),
            rs.getString("zone_name"),
            rs.getString("public_key"),
            rs.getString("nats_url"),
            null, // httpUrl not stored in DB
            rs.getInt("artery_port"),
            capabilities,
            Instant.ofEpochSecond(rs.getLong("discovered_at"))
        );
    }

    private TransitToken transitTokenFromRow(ResultSet rs) throws SQLException {
        return new TransitToken(
            rs.getString("token_id"),
            rs.getString("agent_id"),
            rs.getString("agent_name"),
            rs.getString("source_zone_id"),
            rs.getString("target_zone_id"),
            rs.getString("trust_level"),
            Instant.ofEpochSecond(rs.getLong("issued_at")),
            Instant.ofEpochSecond(rs.getLong("expires_at"))
        );
    }
}
