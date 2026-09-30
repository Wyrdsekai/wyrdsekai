package org.wyrdsekai.between.recipe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.TestSchema;
import org.wyrdsekai.between.federation.BilateralAgreement;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.ZoneSignedMessages;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: a borrow request named its zone in plain JSON, and any agreement row (even pending
 * or revoked) counted as trust. Now the request is signed by the borrowing zone and the lender runs it
 * only for a zone it holds an ACTIVE agreement with, signed with that zone's pinned key.
 */
@Tag("integration")
class NatsRecipeBorrowSigningTest {

    @TempDir Path tmp;
    private FederationService lenderService;
    private NodeIdentity alpha;
    private NodeIdentity stranger;
    private NatsRecipeBorrowTest.FakeTransport transport;
    private final AtomicBoolean ran = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        lenderService = new FederationService(TestSchema.freshDb());
        alpha = NodeIdentity.loadOrGenerate(tmp.resolve("alpha.json"));
        stranger = NodeIdentity.loadOrGenerate(tmp.resolve("stranger.json"));
        var beta = NodeIdentity.loadOrGenerate(tmp.resolve("beta.json"));
        lenderService.saveAgreement(new BilateralAgreement("beta", "alpha", alpha.publicKeyBase64(),
            BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST, Instant.now(), null));
        transport = new NatsRecipeBorrowTest.FakeTransport();
        var wiring = new CrossZoneRecipeWiring("beta", () -> transport, lenderService, n -> null, 5, beta);
        var server = new NatsRecipeServer(transport, "beta", wiring::isTrusted,
            r -> { ran.set(true); return new NatsRecipeServer.Outcome("SUCCESS", "ok", "run-1"); });
        server.setSeal(new ZoneSignedMessages(lenderService, "beta", beta, "recipe borrow"));
        server.start();
    }

    private NatsRecipeClient client(String zone, NodeIdentity signer) {
        var c = new NatsRecipeClient(transport, 1);
        if (signer != null) c.setSeal(new ZoneSignedMessages(null, zone, signer, "recipe borrow"));
        return c;
    }

    @Test
    void aSignedRequestFromAnAgreedZoneRuns() throws Exception {
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft", Map.of(), null);
        var resp = client("alpha", alpha).borrow("beta", req).get(5, TimeUnit.SECONDS);
        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(ran).isTrue();
    }

    @Test
    void anUnsignedRequestIsDeniedAndNeverRuns() throws Exception {
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft", Map.of(), null);
        assertThat(client("alpha", null).borrow("beta", req).get(5, TimeUnit.SECONDS).status()).isEqualTo("DENIED");
        assertThat(ran).isFalse();
    }

    @Test
    void aRequestSignedByAnotherKeyIsDeniedAndNeverRuns() throws Exception {
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft", Map.of(), null);
        assertThat(client("alpha", stranger).borrow("beta", req).get(5, TimeUnit.SECONDS).status()).isEqualTo("DENIED");
        assertThat(ran).isFalse();
    }

    @Test
    void aRevokedOrPendingAgreementIsNotAYes() throws Exception {
        lenderService.updateAgreementStatus("beta", "alpha", BilateralAgreement.STATUS_PENDING);
        var req = NatsRecipeClient.build("alpha", "did:agent", "run-emit-rft", Map.of(), null);
        assertThat(client("alpha", alpha).borrow("beta", req).get(5, TimeUnit.SECONDS).status()).isEqualTo("DENIED");
        assertThat(ran).isFalse();
    }
}
