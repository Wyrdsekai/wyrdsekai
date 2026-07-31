package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultCodingBackendProvider#backendAvailable} must report a
 * healthy backend as available even when its health probe is slow to warm (a cold CLI
 * subprocess spawn — Pi/OpenCode/OpenHands/Goose all shell out to {@code <bin> --version}).
 * The Workshop room's narration (and any {@code world.codingBackendAvailable(...)} caller)
 * depends on this; a too-tight probe timeout silently suppresses all subprocess backends.
 */
class DefaultCodingBackendProviderTest {

    /** Fake backend whose healthCheck resolves {@code true} after {@code healthDelayMs}. */
    private static TestCodingTaskBackend fake(String name, long healthDelayMs) {
        return new TestCodingTaskBackend() {
            @Override public String name() { return name; }
            @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }
            @Override public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
                return CompletableFuture.completedFuture(
                        new TaskResult(spec.taskId(), name, TaskStatus.SUCCEEDED, "", List.of(), 0L, 0L));
            }
            @Override public Stream<CodingArtifact> artifactsFor(String taskId) { return Stream.empty(); }
            @Override public CompletableFuture<Boolean> healthCheck() {
                return CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(healthDelayMs); } catch (InterruptedException ignored) { }
                    return true;
                });
            }
            @Override public long estimatedCu(TaskSpec spec) { return 0L; }
        };
    }

    @Test void available_true_for_fast_healthy_backend() {
        var reg = BackendRegistry.get();
        var b = fake("test-fast-" + UUID.randomUUID(), 0);
        reg.register(b);
        assertTrue(new DefaultCodingBackendProvider(reg).backendAvailable(b.name()));
    }

    @Test void unknown_backend_is_unavailable() {
        assertFalse(new DefaultCodingBackendProvider(BackendRegistry.get())
                .backendAvailable("definitely-not-registered-" + UUID.randomUUID()));
    }

    @Test void available_true_even_when_cold_healthcheck_is_slow() {
        // A healthy CLI backend whose cold `--version` probe takes ~600ms (subprocess
        // spawn under a loaded JVM) MUST still report available. The original 250ms
        // probe timeout made this falsely report unavailable, which suppressed Workshop
        // narration for every subprocess backend (Pi/OpenCode/OpenHands/Goose).
        var reg = BackendRegistry.get();
        var b = fake("test-slow-" + UUID.randomUUID(), 600);
        reg.register(b);
        assertTrue(new DefaultCodingBackendProvider(reg).backendAvailable(b.name()),
                "a healthy backend with a >250ms cold health probe must report available");
    }
}
