package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cross-zone notification forwarding and buffering.
 * Tests the full path: NotificationService → forwarder (via EntityRegistry state) → buffer on failure → flush.
 */
class NotificationForwardingTest {

    @BeforeEach
    void setup() {
        NotificationService.init();
        EntityRegistry.init();
    }

    @Test
    void present_player_delivered_locally() {
        var localDeliveries = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> localDeliveries.add(n));

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");

        NotificationService.get().notify("alice", "hello", "normal", "system");

        assertEquals(1, localDeliveries.size());
        assertEquals("hello", localDeliveries.get(0).message());
    }

    @Test
    void present_player_no_live_session_persisted_not_claimed_sent() {
        // #33 re-audit (honest-over-silent): the deliverer reports FALSE — the
        // player is present in-world but has no live session (e.g. SSH-only /
        // just disconnected). The notification must be buffered for their next
        // connect, NEVER logged as "sent".
        var localDeliveries = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> {
            localDeliveries.add(n);
            return false;  // no live session took it
        });

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");

        NotificationService.get().notify("alice", "you have mail", "normal", "system");

        // Deliverer was invoked but reported no live session → persisted for later.
        assertEquals(1, localDeliveries.size());
        assertEquals(1, NotificationService.get().bufferedCountFor("alice"));

        // On reconnect the buffered note flushes.
        var flushed = NotificationService.get().flushBuffered("alice");
        assertEquals(1, flushed.size());
        assertEquals("you have mail", flushed.get(0).message());
        assertEquals(0, NotificationService.get().bufferedCountFor("alice"));
    }

    @Test
    void traveling_player_forwarded_to_remote_zone() {
        var forwardCount = new AtomicInteger(0);
        var localDeliveries = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> localDeliveries.add(n));
        NotificationService.get().setRemoteForwarder((did, zone, n) -> {
            forwardCount.incrementAndGet();
            return true;
        });

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");
        EntityRegistry.get().setTraveling("alice", "beta");

        NotificationService.get().notify("alice", "urgent update", "critical", "wyrd");

        assertEquals(1, forwardCount.get());
        assertEquals(0, localDeliveries.size());  // not delivered locally
    }

    @Test
    void buffered_when_forwarding_fails() {
        NotificationService.get().setDeliveryCallback((did, n) -> true);
        NotificationService.get().setRemoteForwarder((did, zone, n) -> false);  // always fail

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");
        EntityRegistry.get().setTraveling("alice", "beta");

        NotificationService.get().notify("alice", "msg1", "normal", "wyrd");
        NotificationService.get().notify("alice", "msg2", "normal", "wyrd");

        assertEquals(2, NotificationService.get().bufferedCountFor("alice"));
    }

    @Test
    void flush_buffered_on_return() {
        var delivered = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> delivered.add(n));
        NotificationService.get().setRemoteForwarder((did, zone, n) -> false);

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");
        EntityRegistry.get().setTraveling("alice", "beta");

        NotificationService.get().notify("alice", "msg1", "normal", "wyrd");
        NotificationService.get().notify("alice", "msg2", "critical", "wyrd");

        assertEquals(2, NotificationService.get().bufferedCountFor("alice"));

        // Return home
        EntityRegistry.get().setReturned("alice");
        var flushed = NotificationService.get().flushBuffered("alice");

        assertEquals(2, flushed.size());
        assertEquals(2, delivered.size());
        assertEquals(0, NotificationService.get().bufferedCountFor("alice"));
    }

    @Test
    void buffer_is_bounded() {
        NotificationService.get().setDeliveryCallback((did, n) -> true);
        NotificationService.get().setRemoteForwarder((did, zone, n) -> false);

        EntityRegistry.get().enter("alice", "Alice", "player", "nexus");
        EntityRegistry.get().setTraveling("alice", "beta");

        // 100 notifications should be bounded to MAX_BUFFERED_PER_PLAYER (50)
        for (int i = 0; i < 100; i++) {
            NotificationService.get().notify("alice", "msg" + i, "normal", "wyrd");
        }

        assertEquals(50, NotificationService.get().bufferedCountFor("alice"));
    }

    @Test
    void visitor_from_foreign_zone_forwarded_home() {
        var forwardedTo = new ArrayList<String>();
        NotificationService.get().setDeliveryCallback((did, n) -> true);
        NotificationService.get().setRemoteForwarder((did, zone, n) -> {
            forwardedTo.add(zone);
            return true;
        });

        // Visitor: enters this zone but has a different home zone
        EntityRegistry.get().enter("bob", "Bob", "visitor", "docks");
        EntityRegistry.get().setHomeZone("bob", "gamma");  // visitor from zone gamma

        // Set local zone env to something different
        // (Test env default is "local" — homeZone "gamma" != "local" triggers forward)

        NotificationService.get().notify("bob", "hello visitor", "normal", "local_wyrd");

        // Should forward to bob's home zone (gamma)
        assertEquals(1, forwardedTo.size());
        assertEquals("gamma", forwardedTo.get(0));
    }

    @Test
    void all_broadcast_not_forwarded() {
        var forwardCount = new AtomicInteger(0);
        var localDeliveries = new ArrayList<S2CMessage.Notification>();
        NotificationService.get().setDeliveryCallback((did, n) -> localDeliveries.add(n));
        NotificationService.get().setRemoteForwarder((did, zone, n) -> {
            forwardCount.incrementAndGet();
            return true;
        });

        NotificationService.get().notifyAll("system message", "ambient", "system");

        // "all" notifications are always local-broadcast, never forwarded
        assertEquals(0, forwardCount.get());
        assertEquals(1, localDeliveries.size());
    }
}
