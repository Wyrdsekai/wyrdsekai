package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the three-tier bud sync extensions to SoulLayer (section 95.6).
 *
 * - Tier 1: Headlines (continuous, ~200B)
 * - Tier 2: Warm Handoff (device switch, <2s)
 * - Tier 3: Sleep Sync (full Forge consolidation)
 */
class SoulSyncTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Nested
    class HeadlineTests {

        @Test
        void publish_and_query_headline() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HeadlineResult.class);

            var headline = new SoulLayer.HeadlineMessage(
                "did:key:home-server", "Chatting with Alice about gardening",
                new double[]{0.7, 0.5, 0.8}, 42, Instant.now().getEpochSecond());

            layer.tell(new SoulLayer.PublishHeadline("family-1", headline));
            layer.tell(new SoulLayer.GetHeadline("family-1", "did:key:home-server", probe.getRef()));

            var result = probe.receiveMessage();
            assertThat(result.found()).isTrue();
            assertThat(result.headline().budDid()).isEqualTo("did:key:home-server");
            assertThat(result.headline().summary()).isEqualTo("Chatting with Alice about gardening");
            assertThat(result.headline().itemCount()).isEqualTo(42);
        }

        @Test
        void query_missing_headline_returns_not_found() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HeadlineResult.class);

            layer.tell(new SoulLayer.GetHeadline("family-x", "did:key:nobody", probe.getRef()));

            assertThat(probe.receiveMessage().found()).isFalse();
        }

        @Test
        void multiple_headlines_in_same_family() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.FamilyHeadlines.class);

            long now = Instant.now().getEpochSecond();
            layer.tell(new SoulLayer.PublishHeadline("family-1",
                new SoulLayer.HeadlineMessage("did:key:home-server", "summary-1",
                    new double[]{0.5}, 10, now)));
            layer.tell(new SoulLayer.PublishHeadline("family-1",
                new SoulLayer.HeadlineMessage("did:key:alice", "summary-2",
                    new double[]{0.6}, 20, now)));

            layer.tell(new SoulLayer.GetFamilyHeadlines("family-1", probe.getRef()));
            var result = probe.receiveMessage();
            assertThat(result.familyId()).isEqualTo("family-1");
            assertThat(result.headlines()).hasSize(2);
            assertThat(result.headlines()).containsKey("did:key:home-server");
            assertThat(result.headlines()).containsKey("did:key:alice");
        }

        @Test
        void get_family_headlines_empty_family() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.FamilyHeadlines.class);

            layer.tell(new SoulLayer.GetFamilyHeadlines("nonexistent", probe.getRef()));

            assertThat(probe.receiveMessage().headlines()).isEmpty();
        }

        @Test
        void subscriber_receives_published_headline() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.HeadlineMessage.class);

            // Subscribe first
            layer.tell(new SoulLayer.SubscribeHeadlines("family-1", listenerProbe.getRef()));

            // Then publish
            var headline = new SoulLayer.HeadlineMessage(
                "did:key:home-server", "Hello world", new double[]{0.7}, 5,
                Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.PublishHeadline("family-1", headline));

            var received = listenerProbe.receiveMessage();
            assertThat(received.budDid()).isEqualTo("did:key:home-server");
            assertThat(received.summary()).isEqualTo("Hello world");
        }

        @Test
        void receive_headline_from_peer() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HeadlineResult.class);

            long now = Instant.now().getEpochSecond();
            var headline = new SoulLayer.HeadlineMessage(
                "did:key:bob", "Remote bud status", new double[]{0.4}, 15, now);
            layer.tell(new SoulLayer.ReceiveHeadline("node-2", "family-1", headline));

            layer.tell(new SoulLayer.GetHeadline("family-1", "did:key:bob", probe.getRef()));
            var result = probe.receiveMessage();
            assertThat(result.found()).isTrue();
            assertThat(result.headline().summary()).isEqualTo("Remote bud status");
        }

        @Test
        void newer_headline_overwrites_older() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HeadlineResult.class);

            long t1 = Instant.now().getEpochSecond();
            long t2 = t1 + 60;

            layer.tell(new SoulLayer.ReceiveHeadline("node-2", "family-1",
                new SoulLayer.HeadlineMessage("did:key:home-server", "old", new double[]{}, 1, t1)));
            layer.tell(new SoulLayer.ReceiveHeadline("node-3", "family-1",
                new SoulLayer.HeadlineMessage("did:key:home-server", "new", new double[]{}, 5, t2)));

            layer.tell(new SoulLayer.GetHeadline("family-1", "did:key:home-server", probe.getRef()));
            assertThat(probe.receiveMessage().headline().summary()).isEqualTo("new");
        }

        @Test
        void older_headline_does_not_overwrite_newer() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HeadlineResult.class);

            long t1 = Instant.now().getEpochSecond();
            long t2 = t1 + 60;

            // Newer first
            layer.tell(new SoulLayer.ReceiveHeadline("node-3", "family-1",
                new SoulLayer.HeadlineMessage("did:key:home-server", "newer", new double[]{}, 5, t2)));
            // Then older
            layer.tell(new SoulLayer.ReceiveHeadline("node-2", "family-1",
                new SoulLayer.HeadlineMessage("did:key:home-server", "older", new double[]{}, 1, t1)));

            layer.tell(new SoulLayer.GetHeadline("family-1", "did:key:home-server", probe.getRef()));
            assertThat(probe.receiveMessage().headline().summary()).isEqualTo("newer");
        }

        @Test
        void subscriber_notified_on_receive_from_peer() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.HeadlineMessage.class);

            layer.tell(new SoulLayer.SubscribeHeadlines("family-1", listenerProbe.getRef()));

            long now = Instant.now().getEpochSecond();
            layer.tell(new SoulLayer.ReceiveHeadline("node-2", "family-1",
                new SoulLayer.HeadlineMessage("did:key:bob", "peer status",
                    new double[]{0.3}, 8, now)));

            var received = listenerProbe.receiveMessage();
            assertThat(received.budDid()).isEqualTo("did:key:bob");
            assertThat(received.summary()).isEqualTo("peer status");
        }
    }

    @Nested
    class WarmHandoffTests {

        @Test
        void initiate_handoff_notifies_subscriber() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.HandoffPayload.class);

            // Subscribe for handoffs to this bud
            layer.tell(new SoulLayer.SubscribeWarmHandoff("did:key:home-server", listenerProbe.getRef()));

            // Initiate handoff
            var payload = new SoulLayer.HandoffPayload(
                "did:key:home-server", "{\"did\":\"did:key:home-server\",\"v\":3}",
                List.of("hash1", "hash2"), Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateWarmHandoff("did:key:home-server", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.budDid()).isEqualTo("did:key:home-server");
            assertThat(received.inventoryHashes()).containsExactly("hash1", "hash2");
            assertThat(received.manifestJson()).contains("did:key:home-server");
        }

        @Test
        void receive_handoff_from_peer_notifies_subscriber() {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.HandoffPayload.class);

            layer.tell(new SoulLayer.SubscribeWarmHandoff("did:key:home-server", listenerProbe.getRef()));

            var payload = new SoulLayer.HandoffPayload(
                "did:key:home-server", "{\"manifest\":true}",
                List.of("h1"), Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.ReceiveWarmHandoff("node-1", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.budDid()).isEqualTo("did:key:home-server");
            assertThat(received.manifestJson()).isEqualTo("{\"manifest\":true}");
        }

        @Test
        void handoff_with_empty_inventory() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.HandoffPayload.class);

            layer.tell(new SoulLayer.SubscribeWarmHandoff("did:key:bob", listenerProbe.getRef()));

            var payload = new SoulLayer.HandoffPayload(
                "did:key:bob", "{}", List.of(), Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateWarmHandoff("did:key:bob", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.inventoryHashes()).isEmpty();
        }

        @Test
        void handoff_without_subscriber_does_not_fail() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));

            // No subscriber registered — should not throw
            var payload = new SoulLayer.HandoffPayload(
                "did:key:nobody", "{}", List.of(), Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateWarmHandoff("did:key:nobody", payload));

            // If we get here without the actor crashing, the test passes.
            // Verify the actor is still alive by sending a benign message.
            var probe = testKit.createTestProbe(SoulLayer.HostedAgents.class);
            layer.tell(new SoulLayer.ListHosted(probe.getRef()));
            assertThat(probe.receiveMessage()).isNotNull();
        }
    }

    @Nested
    class SleepSyncTests {

        @Test
        void initiate_sleep_sync_notifies_subscriber() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.SleepSyncPayload.class);

            layer.tell(new SoulLayer.SubscribeSleepSync("family-1", listenerProbe.getRef()));

            var payload = new SoulLayer.SleepSyncPayload(
                "family-1", "did:key:home-server",
                List.of("{\"item\":\"memory-1\"}", "{\"item\":\"memory-2\"}"),
                List.of("{\"tombstone\":\"hash-x\"}"),
                Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateSleepSync("family-1", "did:key:home-server", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.familyId()).isEqualTo("family-1");
            assertThat(received.budDid()).isEqualTo("did:key:home-server");
            assertThat(received.itemJsons()).hasSize(2);
            assertThat(received.tombstoneJsons()).hasSize(1);
        }

        @Test
        void receive_sleep_sync_from_peer_notifies_subscriber() {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.SleepSyncPayload.class);

            layer.tell(new SoulLayer.SubscribeSleepSync("family-1", listenerProbe.getRef()));

            var payload = new SoulLayer.SleepSyncPayload(
                "family-1", "did:key:alice",
                List.of("{\"memory\":\"dream\"}"),
                List.of(),
                Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.ReceiveSleepSync("node-1", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.budDid()).isEqualTo("did:key:alice");
            assertThat(received.itemJsons()).hasSize(1);
            assertThat(received.tombstoneJsons()).isEmpty();
        }

        @Test
        void sleep_sync_without_subscriber_does_not_fail() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));

            var payload = new SoulLayer.SleepSyncPayload(
                "family-orphan", "did:key:orphan",
                List.of(), List.of(), Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateSleepSync("family-orphan", "did:key:orphan", payload));

            // Verify actor still alive
            var probe = testKit.createTestProbe(SoulLayer.HostedAgents.class);
            layer.tell(new SoulLayer.ListHosted(probe.getRef()));
            assertThat(probe.receiveMessage()).isNotNull();
        }

        @Test
        void sleep_sync_with_tombstones_only() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var listenerProbe = testKit.createTestProbe(SoulLayer.SleepSyncPayload.class);

            layer.tell(new SoulLayer.SubscribeSleepSync("family-1", listenerProbe.getRef()));

            var payload = new SoulLayer.SleepSyncPayload(
                "family-1", "did:key:home-server",
                List.of(),
                List.of("{\"hash\":\"abc\"}", "{\"hash\":\"def\"}"),
                Instant.now().getEpochSecond());
            layer.tell(new SoulLayer.InitiateSleepSync("family-1", "did:key:home-server", payload));

            var received = listenerProbe.receiveMessage();
            assertThat(received.itemJsons()).isEmpty();
            assertThat(received.tombstoneJsons()).hasSize(2);
        }
    }
}
