package org.wyrdsekai.e2e.tier3;

import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.familiar.CrossZoneCopyService;
import org.wyrdsekai.core.familiar.ForeignCopyInbox;
import org.wyrdsekai.core.familiar.ForeignToolInbox;
import org.wyrdsekai.core.familiar.FormTransfer;
import org.wyrdsekai.core.familiar.Tanks;
import org.wyrdsekai.core.familiar.ThoughtForm;
import org.wyrdsekai.core.soul.SoulItem;
import org.wyrdsekai.e2e.infra.EmbeddedNatsRelay;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the full cross-zone form-copy + tool-copy loop through NATS federation.
 *
 * <p>. This is the test that would have caught the
 * "outbound publish without inbound subscribe" gap shipped in Batch G.</p>
 *
 * <p>Uses an embedded nats-server; both the sending service (Zone A) and the
 * receiving service (Zone B) connect to it. Zone A constructs a form, calls
 * {@link CrossZoneCopyService#sendFormCopy}, NATS delivers to Zone B's
 * subscription which calls {@link CrossZoneCopyService#receiveFormCopy}, and
 * the result lands in {@link ForeignCopyInbox}.</p>
 */
class CrossZoneCopyE2ETest {

    private static EmbeddedNatsRelay relay;

    @BeforeAll
    static void startRelay() throws Exception {
        relay = new EmbeddedNatsRelay();
        relay.start();
    }

    @AfterAll
    static void stopRelay() {
        if (relay != null) relay.stop();
    }

    @BeforeEach
    void cleanInboxes() {
        ForeignCopyInbox.resetForTests();
        ForeignToolInbox.resetForTests();
    }

    @Test
    void form_copy_round_trips_across_zones() throws Exception {
        var alphaService = new CrossZoneCopyService("alpha");
        var betaService = new CrossZoneCopyService("beta");

        try (var alphaConn = Nats.connect(natsOptions());
             var betaConn = Nats.connect(natsOptions())) {

            // Zone A publishes on federation.<target>.familiar_copy
            alphaService.setRelayPublisher((subject, payload) ->
                alphaConn.publish(subject, payload));

            // Zone B subscribes to its own inbound subject
            var dispatcher = betaConn.createDispatcher(msg ->
                betaService.receiveFormCopy(msg.getData()));
            dispatcher.subscribe("federation.beta.familiar_copy");

            // Give NATS a moment for the subscription to register
            betaConn.flush(Duration.ofSeconds(2));

            // Zone A constructs + gives a copy
            var form = ThoughtForm.author(
                "did:key:zAuthorA", "cross_zone_researcher",
                "Research topics and return sources.",
                Set.of("web_search"),
                "returns at least 3 URLs");
            var recipientDid = "beta:did:key:zRecipientB";
            var intent = FormTransfer.Intent.TEACHING;
            var copy = FormTransfer.copy(form, recipientDid, intent, "cross-zone hand-off");

            boolean routed = alphaService.sendFormCopy(
                "beta", copy, "did:key:zAuthorA", recipientDid,
                intent, "cross-zone hand-off");
            assertThat(routed).isTrue();
            alphaConn.flush(Duration.ofSeconds(2));

            // Beta's inbox should have it shortly after the publish round-trips
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var pending = ForeignCopyInbox.get().drain(recipientDid);
                    assertThat(pending).hasSize(1);
                    var delivered = pending.get(0);
                    assertThat(delivered.form().name()).isEqualTo("cross_zone_researcher");
                    assertThat(delivered.form().systemPrompt()).contains("Research");
                    assertThat(delivered.form().toolSurface()).containsExactly("web_search");
                    assertThat(delivered.senderDid()).isEqualTo("did:key:zAuthorA");
                    assertThat(delivered.recipientDid()).isEqualTo(recipientDid);
                    assertThat(delivered.intent()).isEqualTo(intent);
                    assertThat(delivered.note()).isEqualTo("cross-zone hand-off");
                });
        }
    }

    @Test
    void tool_copy_round_trips_across_zones() throws Exception {
        var alphaService = new CrossZoneCopyService("alpha");
        var betaService = new CrossZoneCopyService("beta");

        try (var alphaConn = Nats.connect(natsOptions());
             var betaConn = Nats.connect(natsOptions())) {

            alphaService.setRelayPublisher((subject, payload) ->
                alphaConn.publish(subject, payload));

            var dispatcher = betaConn.createDispatcher(msg ->
                betaService.receiveToolCopy(msg.getData()));
            dispatcher.subscribe("federation.beta.familiar_tool");
            betaConn.flush(Duration.ofSeconds(2));

            // Build a minimal SoulItem representing a skill tool
            var tool = SoulItem.create(
                "skill", "quick_summary",
                "A skill that summarises quickly.",
                "did:key:zAuthorA",
                0.5);

            var recipientDid = "beta:did:key:zRecipientB";
            boolean routed = alphaService.sendToolCopy(
                "beta", tool, "did:key:zAuthorA", recipientDid,
                FormTransfer.Intent.GIFT, "cross-zone gift");
            assertThat(routed).isTrue();
            alphaConn.flush(Duration.ofSeconds(2));

            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var pending = ForeignToolInbox.get().drain(recipientDid);
                    assertThat(pending).hasSize(1);
                    var delivered = pending.get(0);
                    assertThat(delivered.item().label()).isEqualTo("quick_summary");
                    assertThat(delivered.senderDid()).isEqualTo("did:key:zAuthorA");
                    assertThat(delivered.intent()).isEqualTo(FormTransfer.Intent.GIFT);
                });
        }
    }

    @Test
    void outbound_without_relay_publisher_returns_false() {
        var service = new CrossZoneCopyService("alpha");
        // No setRelayPublisher — routing should fail gracefully
        var form = ThoughtForm.author("did:key:zA", "x", "Task.",
            Set.of(), "");
        var routed = service.sendFormCopy("beta", form,
            "did:key:zA", "beta:did:key:zB",
            FormTransfer.Intent.GIFT, null);
        assertThat(routed).isFalse();
    }

    private static Options natsOptions() {
        return new Options.Builder()
            .server(relay.url())
            .connectionTimeout(Duration.ofSeconds(5))
            .build();
    }
}
