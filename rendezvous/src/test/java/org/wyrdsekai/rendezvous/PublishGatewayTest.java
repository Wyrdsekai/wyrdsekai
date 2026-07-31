package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublishGatewayTest {

    private static final String DID =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    private static ZoneManifestV1 manifest(String label) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID, label, "Display", null,
            "tag", "desc", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
    }

    @Test void acceptsValidManifest() {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 60_000);

        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
        assertEquals(1, store.size());
    }

    @Test void rejectsRapidRepublish() throws InterruptedException {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 60_000);  // 1-minute min interval

        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
        // Immediate second publish = rate-limited.
        assertEquals(PublishGateway.Result.RATE_LIMITED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
    }

    @Test void allowsRepublishAfterInterval() throws InterruptedException {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 50);  // 50ms min interval

        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
        Thread.sleep(100);
        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
    }

    @Test void rejectsMalformedManifest() {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 60_000);

        // Schema version is wrong — validate() throws.
        var bad = new ZoneManifestV1(
            "bogus-schema", DID, "kitchen", "Display", null,
            "tag", "desc", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
        assertEquals(PublishGateway.Result.REJECTED_INVALID,
            gw.publish(bad, "127.0.0.1"));
        assertEquals(0, store.size());
    }

    @Test void rateLimitIsPerDid_notPerIp() throws InterruptedException {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 60_000);

        // Publishing from IP1.
        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "192.0.2.1"));
        // Same DID from a different IP — still rate-limited, because
        // the limit is per-DID (an attacker rotating IPs mustn't bypass).
        assertEquals(PublishGateway.Result.RATE_LIMITED,
            gw.publish(manifest("kitchen"), "192.0.2.2"));
    }

    @Test void resetRateLimits_clearsTracking() throws InterruptedException {
        var store = new DirectoryStore(1000, 3600);
        var gw = new PublishGateway(store, 60_000);

        gw.publish(manifest("kitchen"), "127.0.0.1");
        assertEquals(1, gw.trackedDids());
        gw.resetRateLimits();
        assertEquals(0, gw.trackedDids());
        // Now publishing again immediately should succeed.
        assertEquals(PublishGateway.Result.ACCEPTED,
            gw.publish(manifest("kitchen"), "127.0.0.1"));
    }
}
