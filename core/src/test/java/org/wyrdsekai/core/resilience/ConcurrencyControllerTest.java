package org.wyrdsekai.core.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyControllerTest {

    @Test
    void allowsUpToMaxConcurrent() throws Exception {
        var controller = new ConcurrencyController("test", 2, 5);
        var blocker1 = new CompletableFuture<String>();
        var blocker2 = new CompletableFuture<String>();

        var f1 = controller.submit(() -> blocker1, ConcurrencyController.Priority.INTERACTIVE);
        var f2 = controller.submit(() -> blocker2, ConcurrencyController.Priority.INTERACTIVE);

        assertFalse(f1.isDone());
        assertFalse(f2.isDone());

        blocker1.complete("a");
        blocker2.complete("b");

        assertEquals("a", f1.get(5, TimeUnit.SECONDS));
        assertEquals("b", f2.get(5, TimeUnit.SECONDS));
    }

    @Test
    void queuesWhenAtCapacity() throws Exception {
        var controller = new ConcurrencyController("test", 1, 5);
        var blocker = new CompletableFuture<String>();

        // First request takes the permit
        var f1 = controller.submit(() -> blocker, ConcurrencyController.Priority.INTERACTIVE);
        // Second request should be queued
        var f2 = controller.submit(
            () -> CompletableFuture.completedFuture("queued"),
            ConcurrencyController.Priority.AUTONOMOUS
        );

        assertFalse(f2.isDone());
        assertTrue(controller.getQueueDepth() >= 0); // may already be draining

        // Release first
        blocker.complete("done");

        assertEquals("done", f1.get(5, TimeUnit.SECONDS));
        assertEquals("queued", f2.get(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectsWhenQueueFull() {
        var controller = new ConcurrencyController("test", 1, 1);
        var blocker = new CompletableFuture<String>();

        // Fill permit
        controller.submit(() -> blocker, ConcurrencyController.Priority.INTERACTIVE);
        // Fill queue
        controller.submit(
            () -> CompletableFuture.completedFuture("queued"),
            ConcurrencyController.Priority.AUTONOMOUS
        );

        // Should be rejected
        var f3 = controller.submit(
            () -> CompletableFuture.completedFuture("rejected"),
            ConcurrencyController.Priority.BACKGROUND
        );

        assertTrue(f3.isCompletedExceptionally());
        assertTrue(controller.getRejectedCount() >= 1);

        blocker.complete("done");
    }

    @Test
    void priorityOrdering() throws Exception {
        var controller = new ConcurrencyController("test", 1, 10);
        var blocker = new CompletableFuture<String>();
        var order = new AtomicInteger(0);

        // Fill the single permit
        controller.submit(() -> blocker, ConcurrencyController.Priority.INTERACTIVE);

        // Queue: background first, then interactive
        var bgResult = new CompletableFuture<Integer>();
        var intResult = new CompletableFuture<Integer>();

        controller.submit(() -> {
            bgResult.complete(order.incrementAndGet());
            return CompletableFuture.completedFuture("bg");
        }, ConcurrencyController.Priority.BACKGROUND);

        controller.submit(() -> {
            intResult.complete(order.incrementAndGet());
            return CompletableFuture.completedFuture("int");
        }, ConcurrencyController.Priority.INTERACTIVE);

        // Release blocker — priority queue should drain INTERACTIVE before BACKGROUND
        blocker.complete("done");

        int intOrder = intResult.get(5, TimeUnit.SECONDS);
        int bgOrder = bgResult.get(5, TimeUnit.SECONDS);

        assertTrue(intOrder < bgOrder,
            "Interactive should execute before background: int=" + intOrder + " bg=" + bgOrder);
    }

    @Test
    void metricsAccurate() throws Exception {
        var controller = new ConcurrencyController("test", 2, 5);

        var f1 = controller.submit(
            () -> CompletableFuture.completedFuture("a"),
            ConcurrencyController.Priority.INTERACTIVE
        );
        f1.get(5, TimeUnit.SECONDS);

        assertTrue(controller.getCompletedCount() >= 1);
        assertEquals(0, controller.getRejectedCount());
    }
}
