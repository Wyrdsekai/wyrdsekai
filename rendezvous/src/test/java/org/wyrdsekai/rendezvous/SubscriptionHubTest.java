package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionHubTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    /** Capturing sink used for assertions. */
    static final class RecordingSink implements SubscriptionHub.SseSink {
        final long id;
        final List<String> events = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean(false);

        RecordingSink(long id) { this.id = id; }

        @Override public long id() { return id; }
        @Override public void send(String event, String jsonData) {
            if (closed.get()) throw new IllegalStateException("sink closed");
            events.add(event);
            payloads.add(jsonData);
        }
        @Override public void close() { closed.set(true); }
        @Override public boolean isClosed() { return closed.get(); }
    }

    private static final AtomicLong ID = new AtomicLong();
    private static RecordingSink sink() { return new RecordingSink(ID.incrementAndGet()); }

    private static ZoneManifestV1 manifest(String did, String label,
                                            List<String> tags,
                                            ZoneManifestV1.Capabilities caps) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display", null,
            "tag", "desc", tags, caps, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
    }

    @Test void tagFilter_matchesAndEmitsAdded() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter("social", null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), true);

        assertEquals(1, s.events.size());
        assertEquals("added", s.events.get(0));
        assertTrue(s.payloads.get(0).contains(DID_A));
    }

    @Test void tagFilter_nonMatchingSkipped() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter("social", null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("work"), null), true);

        assertEquals(0, s.events.size());
    }

    @Test void capabilityFilter_matchesOnRoomLabel() {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(), Map.of(), Map.of());
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter(null, "library"));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of(), caps), true);

        assertEquals(1, s.events.size());
        assertEquals("added", s.events.get(0));
    }

    @Test void capabilityFilter_matchesOnAgentSkill() {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(),
            List.of(new ZoneManifestV1.PublicAgent("kettle", "Kettle", "companion",
                "Helpful.", List.of("recipe-lookup"))),
            Map.of(), Map.of());
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter(null, "recipe-lookup"));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of(), caps), true);

        assertEquals(1, s.events.size());
    }

    @Test void updated_emittedForNonNewManifest() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter("social", null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), false);

        assertEquals("updated", s.events.get(0));
    }

    @Test void removed_carriesDidPayload() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter("social", null));

        hub.notifyRemoved(manifest(DID_A, "kitchen", List.of("social"), null));

        assertEquals("removed", s.events.get(0));
        assertTrue(s.payloads.get(0).contains(DID_A));
        assertTrue(s.payloads.get(0).contains("did"));
    }

    @Test void multipleSubscribers_allMatchingReceive() {
        var hub = new SubscriptionHub();
        var s1 = sink();
        var s2 = sink();
        var s3 = sink();  // different filter, should NOT receive
        hub.subscribe(s1, new SubscriptionHub.Filter("social", null));
        hub.subscribe(s2, new SubscriptionHub.Filter("social", null));
        hub.subscribe(s3, new SubscriptionHub.Filter("work", null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), true);

        assertEquals(1, s1.events.size());
        assertEquals(1, s2.events.size());
        assertEquals(0, s3.events.size());
    }

    @Test void unsubscribe_stopsDelivery() {
        var hub = new SubscriptionHub();
        var s = sink();
        var id = hub.subscribe(s, new SubscriptionHub.Filter("social", null));
        hub.unsubscribe(id);

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), true);

        assertEquals(0, s.events.size());
        assertEquals(0, hub.activeSubscriberCount());
    }

    @Test void closedSink_removedOnNextDispatch() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter("social", null));
        s.close();

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), true);

        assertEquals(0, s.events.size(), "closed sink must not receive");
        assertEquals(0, hub.activeSubscriberCount(), "closed sink cleaned up");
    }

    @Test void emptyFilter_matchesAll() {
        var hub = new SubscriptionHub();
        var s = sink();
        hub.subscribe(s, new SubscriptionHub.Filter(null, null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("anything"), null), true);
        hub.notifyPublished(manifest(DID_B, "garage", List.of("work"), null), true);

        assertEquals(2, s.events.size());
    }

    @Test void sinkThrows_removedAndDoesntBlockOthers() {
        var hub = new SubscriptionHub();
        var throwing = new SubscriptionHub.SseSink() {
            @Override public long id() { return 999L; }
            @Override public void send(String e, String d) { throw new RuntimeException("boom"); }
            @Override public void close() { /* no-op */ }
            @Override public boolean isClosed() { return false; }
        };
        var healthy = sink();
        hub.subscribe(throwing, new SubscriptionHub.Filter("social", null));
        hub.subscribe(healthy, new SubscriptionHub.Filter("social", null));

        hub.notifyPublished(manifest(DID_A, "kitchen", List.of("social"), null), true);

        assertEquals(1, healthy.events.size(),
            "healthy subscriber receives despite misbehaving neighbor");
        // Throwing one was removed on error.
        assertEquals(1, hub.activeSubscriberCount());
    }
}
