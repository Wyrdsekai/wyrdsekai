package org.wyrdsekai.core.coding;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Non-sealed permit of {@link CodingTaskBackend} reserved for test
 * fakes. Production code MUST NOT subclass this — the eight concrete
 * permits ({@link CodeZaikuBackend}, {@link OpenCodeBackend},
 * {@link OpenHandsBackend}, etc.) are the only legitimate
 * implementations of the interface.
 *
 * <p>Why this exists: {@link CodingTaskBackend} is sealed (per
 * ) so the type system enforces an exhaustive
 * switch over the recognised backend set. Test fakes need to register
 * with {@link BackendRegistry} for namespace-handler integration
 * coverage, so the sealed clause permits this one anonymous extension
 * point. Tests subclass this class directly:</p>
 *
 * <pre>
 *   var fake = new TestCodingTaskBackend() {
 *       &#64;Override public String name() { return "fake"; }
 *       // ... etc
 *   };
 *   BackendRegistry.get().register(fake);
 * </pre>
 *
 * <p>The class is intentionally abstract: every method on {@link
 * CodingTaskBackend} is declared abstract here so tests must
 * explicitly implement the surface they exercise — implicit defaults
 * would mask intent.</p>
 */
public non-sealed abstract class TestCodingTaskBackend implements CodingTaskBackend {

    @Override public abstract String name();
    @Override public abstract BackendTier tier();
    @Override public abstract CompletableFuture<TaskResult> submitTask(TaskSpec spec);
    @Override public abstract Stream<CodingArtifact> artifactsFor(String taskId);
    @Override public abstract CompletableFuture<Boolean> healthCheck();
    @Override public abstract long estimatedCu(TaskSpec spec);
}
