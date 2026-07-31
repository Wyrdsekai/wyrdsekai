package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.soul.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("needs-nats")
class FederationServiceTest {

    private FederationService service;

    @BeforeEach void setUp() throws SQLException {
        var dbName = "fed-test-" + UUID.randomUUID().toString().substring(0, 8);
        var jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        initSchema(jdbcUrl);
        service = new FederationService(jdbcUrl);
    }

    @SuppressWarnings("resource") // Intentionally keeping connection open for in-memory DB
    private void initSchema(String jdbcUrl) throws SQLException {
        // Do NOT close this connection — it keeps the shared-cache in-memory DB alive
        var conn = DriverManager.getConnection(jdbcUrl);
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bilateral_agreements(
                  local_zone_id   TEXT NOT NULL,
                  remote_zone_id  TEXT NOT NULL,
                  remote_public_key TEXT NOT NULL DEFAULT '',
                  status          TEXT NOT NULL DEFAULT 'pending',
                  trust_level     TEXT NOT NULL DEFAULT 'tourist',
                  agreed_at       INTEGER NOT NULL DEFAULT 0,
                  expires_at      INTEGER,
                  PRIMARY KEY (local_zone_id, remote_zone_id)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS zone_manifests(
                  zone_id        TEXT PRIMARY KEY,
                  zone_name      TEXT NOT NULL,
                  public_key     TEXT NOT NULL,
                  nats_url       TEXT,
                  artery_port    INTEGER DEFAULT 0,
                  capabilities   TEXT DEFAULT '',
                  discovered_at  INTEGER NOT NULL DEFAULT 0,
                  last_seen_at   INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transit_tokens(
                  token_id       TEXT PRIMARY KEY,
                  agent_id       TEXT NOT NULL,
                  agent_name     TEXT NOT NULL,
                  source_zone_id TEXT NOT NULL,
                  target_zone_id TEXT NOT NULL,
                  trust_level    TEXT NOT NULL DEFAULT 'tourist',
                  issued_at      INTEGER NOT NULL DEFAULT 0,
                  expires_at     INTEGER NOT NULL
                )""");
        }
    }

    // --- Bilateral Agreements ---

    @Test void save_and_get_agreement() {
        var agreement = new BilateralAgreement(
            "zone-a", "zone-b", "pubkey-b", "pending", "tourist",
            Instant.now(), null);
        service.saveAgreement(agreement);

        var result = service.getAgreement("zone-a", "zone-b");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("pending");
        assertThat(result.get().trustLevel()).isEqualTo("tourist");
    }

    @Test void accept_activates() {
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pubkey", "pending", "tourist", Instant.now(), null));
        service.updateAgreementStatus("zone-a", "zone-b", "active");

        var result = service.getAgreement("zone-a", "zone-b");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("active");
    }

    @Test void revoke_terminates() {
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pubkey", "active", "tourist", Instant.now(), null));
        service.updateAgreementStatus("zone-a", "zone-b", "revoked");

        var result = service.getAgreement("zone-a", "zone-b");
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("revoked");
    }

    // --- Causality guard (PeerHandshake.tla finding P0 #1: resurrect-revoked) ---

    @Test void activateIfPending_activates_a_pending_agreement() {
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pubkey", "pending", "tourist", Instant.now(), null));

        assertThat(service.activateAgreementIfPending("zone-a", "zone-b")).isTrue();
        assertThat(service.getAgreement("zone-a", "zone-b"))
            .get().extracting(BilateralAgreement::status).isEqualTo("active");
    }

    @Test void activateIfPending_does_not_resurrect_a_revoked_agreement() {
        // A revoked agreement plus a stale/redelivered Accept must NOT go back to active.
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pubkey", "revoked", "tourist", Instant.now(), null));

        assertThat(service.activateAgreementIfPending("zone-a", "zone-b")).isFalse();
        assertThat(service.getAgreement("zone-a", "zone-b"))
            .get().extracting(BilateralAgreement::status).isEqualTo("revoked");
    }

    @Test void activateIfPending_is_a_noop_when_no_agreement_exists() {
        // An Accept for a proposal we never made (status NONE) must not create/activate one.
        assertThat(service.activateAgreementIfPending("zone-a", "zone-b")).isFalse();
        assertThat(service.getAgreement("zone-a", "zone-b")).isEmpty();
    }

    @Test void activateIfPending_is_idempotent_on_an_already_active_agreement() {
        // A redelivered Accept after a legitimate activation is a harmless no-op.
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pubkey", "active", "tourist", Instant.now(), null));

        assertThat(service.activateAgreementIfPending("zone-a", "zone-b")).isFalse();
        assertThat(service.getAgreement("zone-a", "zone-b"))
            .get().extracting(BilateralAgreement::status).isEqualTo("active");
    }

    // --- Epoch fence (spec/tla/PeerHandshakeFenced.tla) ---

    @Test void nextProposalEpoch_is_monotonic() {
        assertThat(service.nextProposalEpoch("a", "b")).isEqualTo(1L);   // nothing yet
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "pending", "tourist", Instant.now(), null).withEpoch(5L, "a"));
        assertThat(service.nextProposalEpoch("a", "b")).isEqualTo(6L);   // one past stored
    }

    @Test void applyInboundAccept_activates_pending_at_its_epoch() {
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "pending", "tourist", Instant.now(), null).withEpoch(3L, "a"));

        assertThat(service.applyInboundAccept("a", "b", 3L, "a")).isTrue();
        var r = service.getAgreement("a", "b").orElseThrow();
        assertThat(r.status()).isEqualTo("active");
        assertThat(r.epoch()).isEqualTo(3L);
    }

    @Test void applyInboundAccept_ignores_a_stale_lower_epoch_accept() {
        // We have re-proposed at epoch 4; a redelivered Accept for epoch 2 is stale.
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "pending", "tourist", Instant.now(), null).withEpoch(4L, "a"));

        assertThat(service.applyInboundAccept("a", "b", 2L, "a")).isFalse();
        assertThat(service.getAgreement("a", "b").orElseThrow().status()).isEqualTo("pending");
    }

    @Test void applyInboundAccept_does_not_resurrect_revoked_even_at_same_epoch() {
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "revoked", "tourist", Instant.now(), null).withEpoch(3L, "a"));

        assertThat(service.applyInboundAccept("a", "b", 3L, "a")).isFalse();
        assertThat(service.getAgreement("a", "b").orElseThrow().status()).isEqualTo("revoked");
    }

    @Test void applyInboundRevoke_applies_at_or_above_epoch() {
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "active", "tourist", Instant.now(), null).withEpoch(3L, "a"));

        assertThat(service.applyInboundRevoke("a", "b", 3L, "a")).isTrue();
        assertThat(service.getAgreement("a", "b").orElseThrow().status()).isEqualTo("revoked");
    }

    @Test void applyInboundRevoke_ignores_a_stale_lower_epoch_revoke() {
        // We have re-proposed at epoch 5; a stale Revoke for epoch 2 must not apply.
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "pending", "tourist", Instant.now(), null).withEpoch(5L, "a"));

        assertThat(service.applyInboundRevoke("a", "b", 2L, "b")).isFalse();
        assertThat(service.getAgreement("a", "b").orElseThrow().status()).isEqualTo("pending");
    }

    @Test void no_lost_revoke_a_late_accept_cannot_undo_a_revoke() {
        // The headline NoLostRevoke property: revoke at epoch 3, then a redelivered
        // Accept (same epoch) arrives — it must NOT bring the agreement back to active.
        service.saveAgreement(new BilateralAgreement(
            "a", "b", "k", "active", "tourist", Instant.now(), null).withEpoch(3L, "a"));
        assertThat(service.applyInboundRevoke("a", "b", 3L, "a")).isTrue();

        assertThat(service.applyInboundAccept("a", "b", 3L, "a")).isFalse();
        assertThat(service.getAgreement("a", "b").orElseThrow().status()).isEqualTo("revoked");
    }

    @Test void isNewerEpoch_breaks_counter_ties_by_minting_zone() {
        // Crossing proposals at the same counter resolve deterministically by zone id.
        assertThat(BilateralAgreement.isNewerEpoch(1L, "b", 1L, "a")).isTrue();
        assertThat(BilateralAgreement.isNewerEpoch(1L, "a", 1L, "b")).isFalse();
        assertThat(BilateralAgreement.isNewerEpoch(2L, "a", 1L, "z")).isTrue();   // counter dominates
    }

    @Test void list_agreements() {
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pub1", "active", "tourist", Instant.now(), null));
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-c", "pub2", "pending", "tourist", Instant.now(), null));

        var all = service.listAgreements("zone-a");
        assertThat(all).hasSize(2);
    }

    @Test void countActiveAgreements() {
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-b", "pub1", "active", "tourist", Instant.now(), null));
        service.saveAgreement(new BilateralAgreement(
            "zone-a", "zone-c", "pub2", "pending", "tourist", Instant.now(), null));

        assertThat(service.countActiveAgreements("zone-a")).isEqualTo(1);
    }

    // --- Zone Manifests ---

    @Test void save_and_get_manifest() {
        var manifest = new ZoneManifest(
            "zone-b", "Beta Zone", "pubkey-b", "nats://host:4222",
            null, 25520, List.of("rooms", "agents"), Instant.now());
        service.saveManifest(manifest);

        var result = service.getManifest("zone-b");
        assertThat(result).isPresent();
        assertThat(result.get().zoneName()).isEqualTo("Beta Zone");
        assertThat(result.get().capabilities()).contains("rooms");
    }

    @Test void list_manifests() {
        service.saveManifest(new ZoneManifest(
            "zone-b", "Beta", "pub1", null, null, 0, List.of(), Instant.now()));
        service.saveManifest(new ZoneManifest(
            "zone-c", "Charlie", "pub2", null, null, 0, List.of(), Instant.now()));

        assertThat(service.listManifests()).hasSize(2);
    }

    // --- Transit Tokens ---

    @Test void issue_and_validate_token() {
        var token = TransitToken.createTourist("agent-1", "Wyrd", "zone-a", "zone-b");
        service.saveTransitToken(token);

        var result = service.validateTransitToken(token.tokenId());
        assertThat(result).isPresent();
        assertThat(result.get().agentName()).isEqualTo("Wyrd");
    }

    @Test void expired_token_rejected() {
        var expired = new TransitToken(
            UUID.randomUUID().toString(), "agent-1", "Wyrd",
            "zone-a", "zone-b", "tourist",
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        service.saveTransitToken(expired);

        assertThat(service.validateTransitToken(expired.tokenId())).isEmpty();
    }

    @Test void clean_expired_tokens() {
        var expired = new TransitToken(
            UUID.randomUUID().toString(), "agent-1", "Wyrd",
            "zone-a", "zone-b", "tourist",
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        var valid = TransitToken.createTourist("agent-2", "Bob", "zone-a", "zone-b");
        service.saveTransitToken(expired);
        service.saveTransitToken(valid);

        service.cleanExpiredTokens();
        assertThat(service.listActiveTransitTokens("zone-b")).hasSize(1);
        assertThat(service.listActiveTransitTokens("zone-b").getFirst().agentName()).isEqualTo("Bob");
    }

    @Test void invalid_token_id_returns_empty() {
        assertThat(service.validateTransitToken("nonexistent")).isEmpty();
    }

    // --- Soul Verification ---

    @Test void verify_soul_manifest_with_valid_key() throws Exception {
        var householdSecret = new byte[32];
        Arrays.fill(householdSecret, (byte) 0x42);
        var identity = AgentIdentity.generate(householdSecret);
        var multibaseKey = identity.did().substring("did:key:".length());

        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, identity.did());
        var manifest = SoulManifest.forge(
            identity.did(), multibaseKey, identity.keyLog(), null, 1,
            profile, "I am a test agent.",
            List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );

        var result = service.verifySoulManifest(manifest);
        assertThat(result).isNotNull();
        assertThat(result.isValid()).isTrue();
        // Unsigned manifest with valid KERI should reach SIGNATURE_KERI
        assertThat(result.trustLevel()).isEqualTo(SoulVerifier.TrustLevel.SIGNATURE_KERI);
    }

    @Test void verify_soul_manifest_without_soul_store() throws Exception {
        // Service has no soul store set — verification should still work
        // (parent chain verification is skipped)
        var householdSecret = new byte[32];
        Arrays.fill(householdSecret, (byte) 0x42);
        var identity = AgentIdentity.generate(householdSecret);
        var multibaseKey = identity.did().substring("did:key:".length());

        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, identity.did());
        var manifest = SoulManifest.forge(
            identity.did(), multibaseKey, identity.keyLog(), null, 1,
            profile, "I am a test agent.",
            List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );

        var result = service.verifySoulManifest(manifest);
        assertThat(result).isNotNull();
        assertThat(result.isValid()).isTrue();
    }
}
