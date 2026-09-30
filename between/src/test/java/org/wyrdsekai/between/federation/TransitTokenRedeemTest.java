package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.TestSchema;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: a transit token was a bearer id checked only for expiry — reusable, valid for any
 * zone, and alive after the agreement was revoked. Now it is single use, bound to the zone that issued
 * it and the zone it was issued to, and dropped when the agreement ends.
 */
@Tag("integration")
class TransitTokenRedeemTest {

    private FederationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FederationService(TestSchema.freshDb());
        service.saveAgreement(new BilateralAgreement("alpha", "beta", "a2V5",
            BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
    }

    private TransitToken issued() {
        var token = TransitToken.createTourist("agent-1", "Wren", "beta", "alpha");
        service.saveTransitToken(token);
        return token;
    }

    @Test
    void aTokenIsSingleUse() {
        var token = issued();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "beta")).isPresent();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "beta")).isEmpty();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", null)).isEmpty();
    }

    @Test
    void aTokenIsBoundToItsZones() {
        var token = issued();
        assertThat(service.redeemTransitToken(token.tokenId(), "gamma", null))
            .as("presented at a zone that did not issue it").isEmpty();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "gamma"))
            .as("presented by a zone it was not issued to").isEmpty();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "beta")).isPresent();
    }

    @Test
    void revokingTheAgreementRevokesItsTokens() {
        var token = issued();
        service.updateAgreementStatus("alpha", "beta", BilateralAgreement.STATUS_REVOKED);
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "beta")).isEmpty();
        assertThat(service.listActiveTransitTokens("alpha")).isEmpty();
    }

    @Test
    void anInboundRevokeRevokesItsTokensToo() {
        var token = issued();
        assertThat(service.applyInboundRevoke("alpha", "beta", 0L, "")).isTrue();
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "beta")).isEmpty();
    }

    @Test
    void aTokenNeedsAnActiveAgreement() {
        var token = TransitToken.createTourist("agent-2", "Moss", "gamma", "alpha");
        service.saveTransitToken(token);
        assertThat(service.redeemTransitToken(token.tokenId(), "alpha", "gamma")).isEmpty();
    }
}
