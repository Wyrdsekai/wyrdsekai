package org.wyrdsekai.core.external.t;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.item.ItemScheduleService;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the definitive re-audit fix (#33-1): before the fix,
 * {@link PhaseTAdaptersBootstrap#init} constructed a {@link ScheduledListenerBridge}
 * but never called {@link ScheduledListenerBridge#wireFireListener()}, so the
 * scheduler's fire-listener slot stayed null — a scheduled timer fired + logged
 * but the inbound hook was never dispatched (silent false-success).
 *
 * <p>This drives the real production path: init with an actor-backed
 * {@link ItemScheduleService}, subscribe a {@code scheduled} inbound listener
 * via the adapter, and assert that a real timer fire dispatches an
 * {@code InboundEvent} of kind {@code "scheduled"} to the dispatch service.</p>
 */
class ScheduledListenerBridgeTest {

    private ActorTestKit testKit;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("ScheduledListenerBridgeTest");
        ItemScheduleService.resetForTesting();
        PhaseTAdaptersBootstrap.resetForTests();
        ExternalAdapterRegistry.get().unregister("inbound");
    }

    @AfterEach
    void tearDown() {
        PhaseTAdaptersBootstrap.resetForTests();
        ExternalAdapterRegistry.get().unregister("inbound");
        ItemScheduleService.resetForTesting();
        testKit.shutdownTestKit();
    }

    @Test
    void scheduled_fire_dispatches_inbound_event() throws Exception {
        var schedule = ItemScheduleService.get(testKit.system(), null);

        // init must wire the fire-listener (the fix). In-memory registry (jdbc null).
        var listener = PhaseTAdaptersBootstrap.init(null, testKit.system(), schedule);
        assertThat(listener).isNotNull();

        var latch = new CountDownLatch(1);
        var seenKind = new AtomicReference<String>();
        InboundDispatchService.get().setAuditListener((event, outcome) -> {
            if ("scheduled".equals(event.kind())) {
                seenKind.set(event.kind());
                latch.countDown();
            }
        });

        // Subscribe a scheduled listener that fires every second.
        var resp = ExternalAdapterRegistry.get().invoke(new AdapterRequest(
            "inbound", "scheduled",
            new HashMap<>(Map.of(
                "hookName", "tick",
                "agentId", "did:wyrd:steward",
                "opts", new HashMap<>(Map.of("intervalSeconds", 1)))),
            ItemCapabilitySet.UNRESTRICTED, "test_item"));
        assertThat(resp.success()).isTrue();

        // The timer fires ~1s from now; give it generous headroom.
        boolean fired = latch.await(4, TimeUnit.SECONDS);
        assertThat(fired)
            .as("a scheduled timer fire should dispatch a 'scheduled' inbound event")
            .isTrue();
        assertThat(seenKind.get()).isEqualTo("scheduled");
    }
}
