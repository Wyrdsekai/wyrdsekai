package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.KeyRotation;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.TestSchema;
import org.wyrdsekai.core.naming.HouseholdIdentity;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: federation verification looked the sender's key up by node id in a map keyed by zone
 * id (so it never ran), skipped unknown senders, only logged mismatches, and let a manifest overwrite the
 * stored key. ZoneGate now verifies every message against the key pinned for the zone it claims.
 */
@Tag("integration")
class ZoneGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir Path tmp;
    private FederationService service;
    private NodeIdentity beta;
    private NodeIdentity gamma;
    private NodeIdentity impostor;
    private final Map<String, String> contacts = new HashMap<>();
    private ZoneGate gate;

    @BeforeEach
    void setUp() throws Exception {
        service = new FederationService(TestSchema.freshDb());
        beta = NodeIdentity.loadOrGenerate(tmp.resolve("beta.json"));
        gamma = NodeIdentity.loadOrGenerate(tmp.resolve("gamma.json"));
        impostor = NodeIdentity.loadOrGenerate(tmp.resolve("impostor.json"));
        gate = new ZoneGate(service, "alpha", z -> Optional.ofNullable(contacts.get(z)));
    }

    /** Every envelope crosses the wire first: verification must hold for what actually arrives. */
    private Optional<ZoneGate.Verified> verify(BetweenEnvelope env, String type) {
        return gate.verify(BetweenEnvelope.fromBytes(env.toBytes()), type);
    }

    private static ZoneManifest manifest(String zone, NodeIdentity keyOwner) {
        return new ZoneManifest(zone, zone + " household", keyOwner.publicKeyBase64(),
            null, null, 0, List.of(), Instant.now());
    }

    private static ObjectNode payload(String type) {
        var p = MAPPER.createObjectNode();
        p.put("type", type);
        return p;
    }

    private static BetweenEnvelope propose(String zone, NodeIdentity keyOwner, NodeIdentity signer) {
        var p = payload("propose");
        p.set("proposer", MAPPER.valueToTree(manifest(zone, keyOwner)));
        p.put("trustLevel", "citizen");
        return BetweenEnvelope.create(signer.nodeId(), null, p, signer);
    }

    private static BetweenEnvelope fromZone(String type, String zone, NodeIdentity signer) {
        var p = payload(type);
        p.put("zoneId", zone);
        p.put("senderZone", zone);
        return BetweenEnvelope.create(signer.nodeId(), null, p, signer);
    }

    private void activeAgreement(String zone, NodeIdentity key) {
        service.saveAgreement(new BilateralAgreement("alpha", zone, key.publicKeyBase64(),
            BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
    }

    @Test
    void aFirstProposalIsSelfSignedAndCarriesTheKeyToPin() {
        var v = verify(propose("beta", beta, beta), "propose");
        assertThat(v).isPresent();
        assertThat(v.get().zoneId()).isEqualTo("beta");
        assertThat(v.get().keyToPin()).isEqualTo(beta.publicKeyBase64());
        // A proposal whose envelope another key signed is not first contact, it is a forgery.
        assertThat(verify(propose("beta", beta, impostor), "propose")).isEmpty();
    }

    @Test
    void aManifestCannotOverwriteAPinnedKey() {
        activeAgreement("beta", beta);
        service.saveManifest(manifest("beta", beta));

        var p = payload("manifest");
        p.set("manifest", MAPPER.valueToTree(manifest("beta", impostor)));
        assertThat(verify(BetweenEnvelope.create(impostor.nodeId(), null, p, impostor), "manifest"))
            .as("impostor's manifest, impostor's signature").isEmpty();
        assertThat(verify(BetweenEnvelope.create(beta.nodeId(), null, p, beta), "manifest"))
            .as("a new key in a manifest, even signed by the old key").isEmpty();

        var same = payload("manifest");
        same.set("manifest", MAPPER.valueToTree(manifest("beta", beta)));
        assertThat(verify(BetweenEnvelope.create(beta.nodeId(), null, same, beta), "manifest")).isPresent();
        assertThat(service.pinnedZoneKey("alpha", "beta")).contains(beta.publicKeyBase64());
    }

    @Test
    void aForgedMessageIsDropped() {
        activeAgreement("beta", beta);
        assertThat(verify(fromZone("revoke", "beta", impostor), "revoke")).isEmpty();
        var ok = verify(fromZone("revoke", "beta", beta), "revoke");
        assertThat(ok).isPresent();
        assertThat(ok.get().zoneId()).isEqualTo("beta");
    }

    @Test
    void aMessageFromAZoneWithNoPinnedKeyIsDropped() {
        assertThat(verify(fromZone("agreement_query", "gamma", gamma), "agreement_query")).isEmpty();
    }

    @Test
    void aPartnerCannotSpeakForAnotherZone() {
        // gamma is a partner too, but a revoke naming beta must be signed with beta's key.
        activeAgreement("beta", beta);
        activeAgreement("gamma", gamma);
        assertThat(verify(fromZone("revoke", "beta", gamma), "revoke")).isEmpty();
    }

    @Test
    void anUnnamedReplyIsAttributedToTheZoneWhoseKeySignedIt() {
        activeAgreement("beta", beta);
        activeAgreement("gamma", gamma);
        var reply = payload("agreement_query_reply");
        reply.put("queryId", "q-1");
        var v = verify(BetweenEnvelope.create(gamma.nodeId(), null, reply, gamma), "agreement_query_reply");
        assertThat(v).isPresent();
        assertThat(v.get().zoneId()).isEqualTo("gamma");
        assertThat(verify(BetweenEnvelope.create(impostor.nodeId(), null, reply, impostor),
            "agreement_query_reply")).isEmpty();
    }

    @Test
    void aProposalWithAnotherKeyIsDroppedWhileTheAgreementIsLive() {
        activeAgreement("beta", beta);
        assertThat(verify(propose("beta", impostor, impostor), "propose")).isEmpty();

        // Once the agreement is revoked, a reinstalled zone may propose afresh with a new key;
        // it lands as pending and waits for the steward.
        service.updateAgreementStatus("alpha", "beta", BilateralAgreement.STATUS_REVOKED);
        assertThat(verify(propose("beta", impostor, impostor), "propose")).isPresent();
    }

    @Test
    void anAcceptFromAZoneWeNeverProposedToIsDropped() {
        var p = payload("accept");
        p.put("zoneId", "beta");
        p.set("acceptor", MAPPER.valueToTree(manifest("beta", beta)));
        assertThat(verify(BetweenEnvelope.create(beta.nodeId(), null, p, beta), "accept")).isEmpty();

        // We proposed (pending, no key yet): the accept pins beta's key.
        service.saveAgreement(new BilateralAgreement("alpha", "beta", "",
            BilateralAgreement.STATUS_PENDING, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
        var v = verify(BetweenEnvelope.create(beta.nodeId(), null, p, beta), "accept");
        assertThat(v).isPresent();
        assertThat(v.get().keyToPin()).isEqualTo(beta.publicKeyBase64());
    }

    @Test
    void aFirstContactKeyMustMatchTheContactOnFile() {
        contacts.put("beta", HouseholdIdentity.fromSpkiBytes(beta.publicKeyBytes()).did());
        assertThat(verify(propose("beta", impostor, impostor), "propose")).isEmpty();
        assertThat(verify(propose("beta", beta, beta), "propose")).isPresent();
    }

    @Test
    void aZoneKeyChangesOnlyThroughARotationSignedByTheOldKey() throws Exception {
        activeAgreement("beta", beta);
        var next = NodeIdentity.loadOrGenerate(tmp.resolve("next.json"));
        var p = payload(KeyRotation.TYPE);
        p.put("zoneId", "beta");
        p.put("senderZone", "beta");
        p.put("newPublicKey", next.publicKeyBase64());
        p.put("newKeySig", Base64.getEncoder().encodeToString(next.sign(
            KeyRotation.statement("beta", beta.publicKeyBytes(), next.publicKeyBytes()))));

        assertThat(verify(BetweenEnvelope.create(next.nodeId(), null, p, next), KeyRotation.TYPE))
            .as("signed by the new key only").isEmpty();
        var v = verify(BetweenEnvelope.create(beta.nodeId(), null, p, beta), KeyRotation.TYPE);
        assertThat(v).isPresent();
        assertThat(v.get().rotatedKey()).isEqualTo(next.publicKeyBase64());

        service.rotateZoneKey("alpha", "beta", v.get().rotatedKey());
        assertThat(verify(fromZone("revoke", "beta", beta), "revoke")).isEmpty();
        assertThat(verify(fromZone("revoke", "beta", next), "revoke")).isPresent();
    }

    @Test
    void reProposingKeepsThePinnedKey() {
        activeAgreement("beta", beta);
        assertThat(service.pinnedZoneKey("alpha", "beta")).contains(beta.publicKeyBase64());
        // FederationActor.doProposeFresh now saves the pinned key, not "".
        service.saveAgreement(new BilateralAgreement("alpha", "beta",
            service.pinnedZoneKey("alpha", "beta").orElse(""),
            BilateralAgreement.STATUS_PENDING, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
        var p = payload("accept");
        p.put("zoneId", "beta");
        p.set("acceptor", MAPPER.valueToTree(manifest("beta", impostor)));
        assertThat(verify(BetweenEnvelope.create(impostor.nodeId(), null, p, impostor), "accept")).isEmpty();
    }
}
