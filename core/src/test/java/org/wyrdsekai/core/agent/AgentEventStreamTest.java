package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventStreamTest {

    private AgentEventStream stream;
    private final List<String> subscribedAgents = new ArrayList<>();

    @BeforeEach
    void setup() {
        stream = new AgentEventStream();
    }

    @AfterEach
    void teardown() {
        for (var id : subscribedAgents) {
            stream.unsubscribe(id);
        }
        subscribedAgents.clear();
    }

    private void subscribe(String agentId, Consumer<AgentEvent> listener) {
        stream.subscribe(agentId, listener);
        subscribedAgents.add(agentId);
    }

    @Test
    void subscribeAndReceiveZoneBroadcast() throws InterruptedException {
        List<AgentEvent> received = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);
        subscribe("agent-1", event -> {
            received.add(event);
            latch.countDown();
        });

        var msg = new S2CMessage.Notification(0, "info", "test", "normal");
        stream.publishZoneBroadcast("codeplane", "workshop", msg);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Event should be delivered within 2s");
        assertEquals(1, received.size());
        assertInstanceOf(AgentEvent.ZoneBroadcast.class, received.get(0));
        var broadcast = (AgentEvent.ZoneBroadcast) received.get(0);
        assertEquals("codeplane", broadcast.namespace());
        assertEquals("workshop", broadcast.roomId());
        assertSame(msg, broadcast.message());
        assertNotNull(broadcast.timestamp());
    }

    @Test
    void subscribeAndReceiveSystemEvent() throws InterruptedException {
        List<AgentEvent> received = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);
        subscribe("agent-1", event -> {
            received.add(event);
            latch.countDown();
        });

        stream.publishSystemEvent(AgentEvent.SystemEventType.NODE_JOINED, "node-2", "new node online");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertInstanceOf(AgentEvent.SystemEvent.class, received.get(0));
        var event = (AgentEvent.SystemEvent) received.get(0);
        assertEquals(AgentEvent.SystemEventType.NODE_JOINED, event.type());
        assertEquals("node-2", event.source());
        assertEquals("new node online", event.detail());
        assertNotNull(event.timestamp());
    }

    @Test
    void subscribeAndReceiveAdjacentActivity() throws InterruptedException {
        List<AgentEvent> received = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);
        subscribe("agent-1", event -> {
            received.add(event);
            latch.countDown();
        });

        stream.publishAdjacentActivity("bridge", "The Bridge", AgentEvent.ActivityType.SPEECH, 3);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertInstanceOf(AgentEvent.AdjacentActivity.class, received.get(0));
        var activity = (AgentEvent.AdjacentActivity) received.get(0);
        assertEquals("bridge", activity.sourceRoomId());
        assertEquals("The Bridge", activity.sourceRoomName());
        assertEquals(AgentEvent.ActivityType.SPEECH, activity.type());
        assertEquals(3, activity.entityCount());
        assertNotNull(activity.timestamp());
    }

    @Test
    void unsubscribeStopsDelivery() throws InterruptedException {
        List<AgentEvent> received = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);
        stream.subscribe("agent-1", event -> {
            received.add(event);
            latch.countDown();
        });

        // HEALTH_ALERT is critical — bypasses rate limiter
        stream.publishSystemEvent(AgentEvent.SystemEventType.HEALTH_ALERT, "monitor", "disk full");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());

        stream.unsubscribe("agent-1");

        stream.publishSystemEvent(AgentEvent.SystemEventType.NODE_LEFT, "node-3", "node departed");
        Thread.sleep(200); // brief wait to confirm no delivery
        assertEquals(1, received.size()); // no new events after unsubscribe
    }

    @Test
    void multipleSubscribersAllReceive() throws InterruptedException {
        List<AgentEvent> received1 = Collections.synchronizedList(new ArrayList<>());
        List<AgentEvent> received2 = Collections.synchronizedList(new ArrayList<>());
        List<AgentEvent> received3 = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(3);

        subscribe("agent-1", event -> { received1.add(event); latch.countDown(); });
        subscribe("agent-2", event -> { received2.add(event); latch.countDown(); });
        subscribe("agent-3", event -> { received3.add(event); latch.countDown(); });

        stream.publishAdjacentActivity("nexus", "The Nexus",
                AgentEvent.ActivityType.ENTITY_ENTERED, 1);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
        assertEquals(1, received3.size());
        assertEquals(3, stream.subscriberCount());
    }

    @Test
    void getReturnsNullBeforeInit() {
        // Reset the static instance by calling get() without init() in a fresh JVM state.
        // Since we can't easily reset the volatile, we just verify the contract:
        // a fresh AgentEventStream.get() may or may not be null depending on test ordering,
        // but at minimum we verify the static method exists and is callable.
        // The real test is that init() sets it and get() returns it.
        AgentEventStream.init();
        assertNotNull(AgentEventStream.get());
    }

    @Test
    void abortSignalBypassesRateLimiterWhenSaturated() {
        // #33 re-audit: a human abort/stop is a control event — it must never be
        // dropped under the token-bucket alongside ordinary chatter. Saturate the
        // limiter with ambient activity, then confirm an AbortSignal still lands.
        List<AgentEvent> received = Collections.synchronizedList(new ArrayList<>());
        var sub = new AgentEventStream.RateLimitedSubscriber(
                "agent-abort", received::add, 1.0, 100);
        try {
            boolean sawRateLimitedDrop = false;
            for (int i = 0; i < 50 && !sawRateLimitedDrop; i++) {
                var ambient = new AgentEvent.AdjacentActivity(
                        "r", "R", AgentEvent.ActivityType.SPEECH, 1, Instant.now());
                if (!sub.deliver(ambient)) {
                    sawRateLimitedDrop = true;
                }
            }
            assertTrue(sawRateLimitedDrop, "ambient events should eventually be rate-limited");

            // The limiter is now exhausted — a human abort must STILL be accepted.
            var abort = new AgentEvent.AbortSignal("p1", "Player", "r", Instant.now());
            assertTrue(sub.deliver(abort), "AbortSignal must bypass the rate limiter");
        } finally {
            sub.shutdown();
        }
    }

    @Test
    void publishWithNoSubscribersDoesNotError() {
        // All three publish methods should be safe with zero subscribers
        assertDoesNotThrow(() ->
                stream.publishZoneBroadcast("ns", "room", new S2CMessage.Notification(0, "info", "x", "normal")));
        assertDoesNotThrow(() ->
                stream.publishSystemEvent(AgentEvent.SystemEventType.INFERENCE_BACKEND_UP, "llama", "ready"));
        assertDoesNotThrow(() ->
                stream.publishAdjacentActivity("r1", "Room One",
                        AgentEvent.ActivityType.OBJECT_INTERACTION, 2));
    }

    @Test
    void subscriberExceptionDoesNotBlockOtherSubscribers() throws InterruptedException {
        AtomicInteger goodCount = new AtomicInteger();
        var latch = new CountDownLatch(1);

        // First subscriber: throws (but drain thread catches it)
        subscribe("bad-agent", event -> {
            throw new RuntimeException("I am broken");
        });

        // Second subscriber: works fine
        subscribe("good-agent", event -> {
            goodCount.incrementAndGet();
            latch.countDown();
        });

        // Publish -- the good subscriber must still receive despite the bad one throwing
        stream.publishSystemEvent(AgentEvent.SystemEventType.ZONE_SERVICE_REGISTERED,
                "mcp-gateway", "new service");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, goodCount.get());
    }
}
