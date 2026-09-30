package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.TestSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-zone tells travel signed; the receiver takes them only from an agreed zone's pinned key, once. */
@Tag("integration")
class ZoneSignedMessagesTest {

    private static final byte[] TELL =
        "{\"fromEntityId\":\"e\",\"fromEntityName\":\"Moss\",\"fromZone\":\"beta\",\"targetName\":\"wren\",\"text\":\"hi\"}"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir Path tmp;
    private FederationService homeService;
    private NodeIdentity betaNode;
    private NodeIdentity impostor;

    @BeforeEach
    void setUp() throws Exception {
        homeService = new FederationService(TestSchema.freshDb());
        betaNode = NodeIdentity.loadOrGenerate(tmp.resolve("beta.json"));
        impostor = NodeIdentity.loadOrGenerate(tmp.resolve("impostor.json"));
        homeService.saveAgreement(new BilateralAgreement("home", "beta", betaNode.publicKeyBase64(),
            BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
    }

    private ZoneSignedMessages receiver() {
        return new ZoneSignedMessages(homeService, "home", null, "tell");
    }

    @Test
    void aTellFromAnAgreedZoneOpensOnceWithItsZone() {
        var sealed = new ZoneSignedMessages(null, "beta", betaNode, "tell").seal(TELL);
        var rx = receiver();
        var opened = rx.open(sealed);
        assertThat(opened).isPresent();
        assertThat(opened.get().zoneId()).isEqualTo("beta");
        assertThat(opened.get().message().path("text").asText()).isEqualTo("hi");
        assertThat(rx.open(sealed)).as("replayed").isEmpty();
    }

    @Test
    void anUnsignedTellIsDropped() {
        assertThat(receiver().open(TELL)).isEmpty();
    }

    @Test
    void aTellSignedByTheWrongKeyIsDropped() {
        var sealed = new ZoneSignedMessages(null, "beta", impostor, "tell").seal(TELL);
        assertThat(receiver().open(sealed)).isEmpty();
    }

    @Test
    void aTellClaimingThisZoneIsDropped() {
        var sealed = new ZoneSignedMessages(null, "home", betaNode, "tell").seal(TELL);
        assertThat(receiver().open(sealed)).isEmpty();
    }

    @Test
    void aTellFromARevokedPartnerIsDropped() {
        homeService.updateAgreementStatus("home", "beta", BilateralAgreement.STATUS_REVOKED);
        var sealed = new ZoneSignedMessages(null, "beta", betaNode, "tell").seal(TELL);
        assertThat(receiver().open(sealed)).isEmpty();
    }
}
