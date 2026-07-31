package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies federation-relay routing for form + tool copies.
 *
 * <p>We swap in a capture publisher so the outbound leg doesn't need a real
 * NATS; the inbound leg is driven directly against the same captured bytes,
 * proving serialize ↔ deserialize symmetry. .</p>
 */
class CrossZoneCopyServiceTest {

    private static final String LOCAL_ZONE = "alpha";
    private static final String REMOTE_ZONE = "beta";

    private CrossZoneCopyService service;
    private List<Captured> captured;

    private record Captured(String subject, byte[] payload) {}

    @BeforeEach
    void setUp() {
        CrossZoneCopyService.resetForTests();
        ForeignCopyInbox.resetForTests();
        ForeignToolInbox.resetForTests();
        CrossZoneCopyService.init(LOCAL_ZONE);
        service = CrossZoneCopyService.get();
        captured = new ArrayList<>();
        BiConsumer<String, byte[]> publisher = (subject, bytes) -> captured.add(new Captured(subject, bytes));
        service.setRelayPublisher(publisher);
    }

    @AfterEach
    void tearDown() {
        CrossZoneCopyService.resetForTests();
        ForeignCopyInbox.resetForTests();
        ForeignToolInbox.resetForTests();
    }

    @Test
    void sendFormCopy_to_local_zone_is_noop() {
        var form = ThoughtForm.author("did:key:zA", "helper",
            "Be helpful.", Set.of("note"), "");
        boolean routed = service.sendFormCopy(LOCAL_ZONE, form,
            "did:key:zA", "did:key:zB", FormTransfer.Intent.GIFT, "howdy");
        assertThat(routed).isFalse();
        assertThat(captured).isEmpty();
    }

    @Test
    void sendFormCopy_publishes_to_federation_subject() {
        var form = ThoughtForm.author("did:key:zA", "researcher",
            "Find sources.", Set.of("web_search"),
            "returns 3 sources with citations");
        boolean routed = service.sendFormCopy(REMOTE_ZONE, form,
            "did:key:zA", "beta:did:key:zB", FormTransfer.Intent.TEACHING, "hand-off");
        assertThat(routed).isTrue();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).subject()).isEqualTo("federation.beta.familiar_copy");
    }

    @Test
    void receiveFormCopy_round_trips_into_local_inbox() {
        var form = ThoughtForm.author("did:key:zA", "runner",
            "Do the thing.", Set.of("note"), "");
        service.sendFormCopy(REMOTE_ZONE, form, "did:key:zA", "beta:did:key:zB",
            FormTransfer.Intent.GIFT, "inbound");
        var payload = captured.get(0).payload();

        // Simulate the remote zone receiving this payload
        service.receiveFormCopy(payload);

        var pending = ForeignCopyInbox.get().drain("beta:did:key:zB");
        assertThat(pending).hasSize(1);
        var p = pending.get(0);
        assertThat(p.form().name()).isEqualTo("runner");
        assertThat(p.form().systemPrompt()).isEqualTo("Do the thing.");
        assertThat(p.form().toolSurface()).containsExactly("note");
        assertThat(p.senderDid()).isEqualTo("did:key:zA");
        assertThat(p.recipientDid()).isEqualTo("beta:did:key:zB");
        assertThat(p.intent()).isEqualTo(FormTransfer.Intent.GIFT);
        assertThat(p.note()).isEqualTo("inbound");
    }

    @Test
    void sendFormCopy_without_publisher_reports_failure() {
        service.setRelayPublisher(null);
        var form = ThoughtForm.author("did:key:zA", "x", "Task.",
            Set.of(), "");
        boolean routed = service.sendFormCopy(REMOTE_ZONE, form,
            "did:key:zA", "beta:did:key:zB", FormTransfer.Intent.GIFT, null);
        assertThat(routed).isFalse();
    }

    @Test
    void receiveFormCopy_with_garbage_does_not_throw() {
        service.receiveFormCopy("{\"oops\":true}".getBytes());
        service.receiveFormCopy("".getBytes());
        // No exception, no deliveries
        assertThat(ForeignCopyInbox.get().pendingCount("anyone")).isZero();
    }
}
