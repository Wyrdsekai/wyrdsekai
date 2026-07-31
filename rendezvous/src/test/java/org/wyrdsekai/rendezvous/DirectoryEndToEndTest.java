package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.CompositeZoneDirectory;
import org.wyrdsekai.core.naming.FederatedZoneDirectory;
import org.wyrdsekai.core.naming.RendezvousZoneDirectory;
import org.wyrdsekai.core.naming.WellKnownZoneDirectory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the directory architecture — a real rendezvous
 * process + a {@link CompositeZoneDirectory} wiring together three
 * real backends (NATS deliberately excluded; NATS integration lives
 * in its own test). Exercises the whole publish → query → tombstone
 * cycle over the wire.
 *
 * <p>This is the software-level proxy for the Tier 3 live-mesh verify:
 * we can't run four real households in a unit test, but we can wire
 * four software-level directory clients against one or more real
 * Javalin endpoints and prove the composition logic holds.</p>
 */
class DirectoryEndToEndTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private RendezvousMain.Handle rendezvous;
    private HttpClient http;

    @BeforeEach void startRendezvous() {
        rendezvous = RendezvousMain.start(0, 1000, 50, 3600);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach void stopRendezvous() {
        if (rendezvous != null) rendezvous.close();
    }

    private String rendezvousBase() {
        return "http://127.0.0.1:" + rendezvous.port();
    }

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display", null,
            "tag", "description", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
    }

    @Test void compositePublish_fansOutToRendezvous() throws Exception {
        var rendezvousClient = new RendezvousZoneDirectory(
            () -> List.of(rendezvousBase()));
        var composite = new CompositeZoneDirectory(List.of(rendezvousClient));

        var m = manifest(DID_A, "kitchen", List.of("social"));
        composite.publish(m);

        // Verify the rendezvous received it by querying directly over HTTP.
        var resp = http.send(
            HttpRequest.newBuilder(URI.create(
                rendezvousBase() + "/api/directory/"
                    + URLEncoder.encode(DID_A, StandardCharsets.UTF_8)))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test void compositeLookup_returnsFromRendezvous() throws Exception {
        // Publish directly to rendezvous, look up via composite.
        var m = manifest(DID_A, "kitchen", List.of());
        http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(m.toJsonString()))
            .build(), HttpResponse.BodyHandlers.ofString());

        var composite = new CompositeZoneDirectory(List.of(
            new RendezvousZoneDirectory(() -> List.of(rendezvousBase()))));
        var opt = composite.lookup(DID_A);
        assertTrue(opt.isPresent());
        assertEquals("kitchen", opt.get().zoneLabel());
    }

    @Test void compositeTombstone_fansOutAndRemoves() throws Exception {
        var rendezvousClient = new RendezvousZoneDirectory(
            () -> List.of(rendezvousBase()));
        var composite = new CompositeZoneDirectory(List.of(rendezvousClient));

        composite.publish(manifest(DID_A, "kitchen", List.of()));
        assertTrue(composite.lookup(DID_A).isPresent());

        composite.unpublish(DID_A);
        // Rendezvous may need a moment but tombstone is synchronous.
        var after = composite.lookup(DID_A);
        assertTrue(after.isEmpty());
    }

    @Test void federatedBackend_pullsFromRendezvous() throws Exception {
        // Publish to rendezvous, start FederatedZoneDirectory pointed at it
        // as a peer, trigger a pull, verify ingest.
        var m = manifest(DID_A, "kitchen", List.of("social"));
        http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(m.toJsonString()))
            .build(), HttpResponse.BodyHandlers.ofString());

        var federated = new FederatedZoneDirectory(() -> List.of(rendezvousBase()));
        federated.pullOnce();

        assertEquals(1, federated.cacheSize());
        assertEquals(DID_A, federated.lookup(DID_A).orElseThrow().did());
    }

    @Test void wellKnownBackend_composedWithRendezvous_bothResolve() throws Exception {
        // Rendezvous has DID_A published.
        http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                manifest(DID_A, "kitchen", List.of()).toJsonString()))
            .build(), HttpResponse.BodyHandlers.ofString());

        // Composite pairs WellKnown (empty) + Rendezvous (populated).
        // Lookup should succeed via Rendezvous.
        var composite = new CompositeZoneDirectory(List.of(
            new WellKnownZoneDirectory(),
            new RendezvousZoneDirectory(() -> List.of(rendezvousBase()))
        ));
        assertTrue(composite.lookup(DID_A).isPresent());
    }

    @Test void multiZoneFlow_publishViaRendezvous_federatedPeerDiscovers() throws Exception {
        // Software-level multi-zone: Zone A publishes to rendezvous.
        // Zone B's FederatedZoneDirectory pulls from rendezvous (as if it
        // were an upstream peer) and learns about A. This is the
        // pre-mesh verification of the `publish → federated pull → learn`
        // pipeline; live mesh verify (#254) exercises the same path
        // across real nodes.
        http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                manifest(DID_A, "kitchen", List.of("social")).toJsonString()))
            .build(), HttpResponse.BodyHandlers.ofString());

        Thread.sleep(60);  // past rate-limit window

        http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                manifest(DID_B, "garage", List.of("work")).toJsonString()))
            .build(), HttpResponse.BodyHandlers.ofString());

        // "Zone C" starts with nothing; pulls from the rendezvous.
        var zoneC = new FederatedZoneDirectory(() -> List.of(rendezvousBase()));
        zoneC.pullOnce();

        assertEquals(2, zoneC.cacheSize());
        assertEquals(List.of(DID_A), zoneC.discoverByTag("social"));
        assertEquals(List.of(DID_B), zoneC.discoverByTag("work"));
    }

    @Test void publishThenSubscribe_sseStreamReceivesUpdate() throws Exception {
        // Real SSE stream end-to-end — connect, subscribe filter by tag,
        // publish matching manifest, verify event arrives within timeout.
        var url = rendezvousBase() + "/api/directory/subscribe?tag=social";
        var conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("accept", "text/event-stream");
        conn.setReadTimeout(5000);
        try (var in = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            // Kick off publish on a separate thread so the SSE client can
            // actually receive while we're reading.
            var pubThread = new Thread(() -> {
                try {
                    Thread.sleep(100);
                    http.send(HttpRequest.newBuilder(URI.create(rendezvousBase() + "/publish"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                            manifest(DID_A, "kitchen", List.of("social")).toJsonString()))
                        .build(), HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) { /* test will fail on assertion below */ }
            });
            pubThread.setDaemon(true);
            pubThread.start();

            // Read SSE lines until we see an "added" event for our DID.
            // Javalin's SSE sends as: "event: <name>\ndata: <json>\n\n".
            boolean sawAdded = false;
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
                var line = in.readLine();
                if (line == null) break;
                if (line.startsWith("event:") && line.contains("added")) {
                    sawAdded = true;
                }
                if (sawAdded && line.startsWith("data:") && line.contains(DID_A)) {
                    return;  // test passes
                }
            }
            fail("SSE subscriber did not receive added event for " + DID_A
                + " within timeout");
        } finally {
            conn.disconnect();
        }
    }
}
