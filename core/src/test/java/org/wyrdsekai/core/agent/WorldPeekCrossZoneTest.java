package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Phase 2b — cross-zone {@code world.peek} routing
 * via {@link CrossZonePeekService}.
 *
 * <p>Asserts the failure modes per task §D:
 * <ul>
 *   <li>Service not initialised → null+log (caller is responsible)</li>
 *   <li>Caller not wired → null+log</li>
 *   <li>Caller returns snapshot → returned to script</li>
 *   <li>Caller returns null (auth denied / unknown room) → null</li>
 *   <li>Timeout → null</li>
 *   <li>SecurityException → null</li>
 *   <li>Other exception → null</li>
 *   <li>Local-zone same as target → null+log (caller bug)</li>
 * </ul>
 */
class WorldPeekCrossZoneTest {

    @BeforeEach
    void setUp() {
        CrossZonePeekService.resetForTests();
        CrossZonePeekService.init("alpha");
    }

    @AfterEach
    void tearDown() {
        CrossZonePeekService.resetForTests();
    }

    @Test void caller_returns_snapshot_passed_through() {
        var svc = CrossZonePeekService.get();
        var snap = new LinkedHashMap<String, Object>();
        snap.put("name", "Beta Foyer");
        snap.put("description", "");
        snap.put("exits", List.of("south"));
        snap.put("entities", List.of());
        snap.put("items", List.of());

        svc.setCaller((target, source, alias) -> {
            assertThat(target).isEqualTo("beta");
            assertThat(source).isEqualTo("alpha");
            assertThat(alias).isEqualTo("foyer");
            return CompletableFuture.completedFuture(snap);
        });

        var result = svc.peek("beta", "foyer");
        assertThat(result).isSameAs(snap);
    }

    @Test void timeout_returns_null() {
        var svc = CrossZonePeekService.get();
        // Never-completing future + short timeout.
        svc.setCaller((target, source, alias) -> new CompletableFuture<>());

        var result = svc.peek("beta", "foyer", Duration.ofMillis(50));
        assertThat(result).isNull();
    }

    @Test void caller_not_wired_returns_null() {
        // No setCaller() call — caller is null.
        var svc = CrossZonePeekService.get();
        var result = svc.peek("beta", "foyer");
        assertThat(result).isNull();
    }

    @Test void security_exception_returns_null() {
        var svc = CrossZonePeekService.get();
        svc.setCaller((target, source, alias) -> {
            var f = new CompletableFuture<Map<String, Object>>();
            f.completeExceptionally(new SecurityException("warded room"));
            return f;
        });
        var result = svc.peek("beta", "foyer");
        assertThat(result).isNull();
    }

    @Test void other_exception_returns_null() {
        var svc = CrossZonePeekService.get();
        svc.setCaller((target, source, alias) -> {
            var f = new CompletableFuture<Map<String, Object>>();
            f.completeExceptionally(new RuntimeException("relay borked"));
            return f;
        });
        var result = svc.peek("beta", "foyer");
        assertThat(result).isNull();
    }

    @Test void caller_returns_null_passes_through_as_null() {
        // Remote replied "no such room" or "not authorized" — null result,
        // not an exception. Per spec §8 task §D auth/grant denial returns
        // null + warn log.
        var svc = CrossZonePeekService.get();
        svc.setCaller((target, source, alias) -> CompletableFuture.completedFuture(null));
        var result = svc.peek("beta", "foyer");
        assertThat(result).isNull();
    }

    @Test void same_zone_target_returns_null_caller_bug() {
        // Caller misroute: passing the local zone to a cross-zone service
        // should fast-fail with null, not loop back.
        var svc = CrossZonePeekService.get();
        var called = new AtomicInteger(0);
        svc.setCaller((target, source, alias) -> {
            called.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of());
        });
        var result = svc.peek("alpha", "foyer");
        assertThat(result).isNull();
        assertThat(called.get()).isEqualTo(0);  // caller wasn't invoked
    }

    @Test void empty_zone_or_alias_returns_null() {
        var svc = CrossZonePeekService.get();
        svc.setCaller((target, source, alias) -> CompletableFuture.completedFuture(Map.of()));

        assertThat(svc.peek(null, "foyer")).isNull();
        assertThat(svc.peek("", "foyer")).isNull();
        assertThat(svc.peek("beta", null)).isNull();
        assertThat(svc.peek("beta", "")).isNull();
    }

    @Test void caller_passes_localZone_as_source() {
        var svc = CrossZonePeekService.get();
        var capturedSource = new AtomicReference<String>();
        svc.setCaller((target, source, alias) -> {
            capturedSource.set(source);
            return CompletableFuture.completedFuture(Map.of("name", "Beta Foyer"));
        });
        svc.peek("beta", "foyer");
        assertThat(capturedSource.get()).isEqualTo("alpha");
    }

    @Test void null_future_from_caller_returns_null() {
        var svc = CrossZonePeekService.get();
        svc.setCaller((target, source, alias) -> null);
        var result = svc.peek("beta", "foyer");
        assertThat(result).isNull();
    }

    @Test void uninitialised_service_get_returns_null_singleton() {
        CrossZonePeekService.resetForTests();
        assertThat(CrossZonePeekService.get()).isNull();
    }
}
