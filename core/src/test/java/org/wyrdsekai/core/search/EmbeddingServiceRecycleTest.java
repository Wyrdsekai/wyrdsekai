package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the periodic OrtSession recycle behavior — wyrdsekai's defense
 * against ONNX Runtime's documented memory accumulation in long-running services
 * (CodePlane diagnostic Issue 2; ORT issues #5176, #6058, #11118, #22271, #26831).
 *
 * <p>Strategy: rebuild OrtSession from disk every {@code RECYCLE_HOURS}, atomic
 * volatile-swap, close the previous session after a grace period so in-flight
 * inference calls complete without exception.
 */
class EmbeddingServiceRecycleTest {

    private static EmbeddingService service;

    @BeforeAll
    static void setUp() {
        service = EmbeddingService.init();
        Assumptions.assumeTrue(service != null,
            "EmbeddingService not available (model missing?)");
    }

    @AfterAll
    static void tearDown() {
        if (service != null) service.close();
    }

    @Test
    void recycle_increments_counter() {
        int before = service.recycleCount();
        service.recycle();
        assertThat(service.recycleCount()).isEqualTo(before + 1);
    }

    @Test
    void recycle_preserves_embedding_output() {
        // The model + weights are identical before and after recycle (we just
        // rebuild the session from the same bundled bytes), so the same input
        // must produce a numerically-identical embedding.
        var input = "wyrdsekai recycles its embedding session every 24 hours";
        var before = service.embed(input);
        service.recycle();
        var after = service.embed(input);

        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i))
                .as("embedding[%d] should be unchanged across recycle", i)
                .isCloseTo(before.get(i), within(1e-5f));
        }
    }

    @Test
    void embed_after_multiple_recycles_works() {
        // Sequential recycles shouldn't accumulate state issues. Embeddings
        // remain functional after several swaps.
        for (int i = 0; i < 3; i++) {
            service.recycle();
        }
        var emb = service.embed("still working after multiple recycles");
        assertThat(emb).hasSize(384);
        // Sanity: not all zeros (would indicate the embedder broke)
        boolean anyNonZero = false;
        for (var v : emb) if (Math.abs(v) > 1e-6f) { anyNonZero = true; break; }
        assertThat(anyNonZero).as("embedding should not be all zeros after recycles").isTrue();
    }

    @Test
    void embed_during_active_session_is_thread_safe() throws InterruptedException {
        // Run a recycle on one thread while another thread is hammering embed().
        // The volatile session reference + ORT's documented thread-safe inference
        // means in-flight calls succeed regardless of when the swap happens.
        var failures = new AtomicInteger(0);
        var stop = new AtomicBoolean(false);

        var embedder = new Thread(() -> {
            int i = 0;
            while (!stop.get() && i < 50) {
                try {
                    var r = service.embed("concurrent embed test " + i);
                    if (r.size() != 384) failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
                i++;
            }
        });

        embedder.start();
        Thread.sleep(50); // let it warm up
        service.recycle();
        Thread.sleep(50);
        service.recycle();
        stop.set(true);
        embedder.join(5000);

        assertThat(failures.get())
            .as("no embed calls should fail during concurrent recycle")
            .isZero();
    }

    @Test
    void recycle_hours_is_observable() {
        // Default 24h, env-overridable via WYRDSEKAI_EMBEDDING_RECYCLE_HOURS.
        // We don't set the env var in tests, so should be the default.
        assertThat(EmbeddingService.recycleHours()).isPositive();
        assertThat(EmbeddingService.recycleHours())
            .as("recycle interval should be at least 1 hour")
            .isGreaterThanOrEqualTo(1L);
    }
}
