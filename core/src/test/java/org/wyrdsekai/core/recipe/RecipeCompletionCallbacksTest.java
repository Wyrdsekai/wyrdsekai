package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The one-shot recipe-completion callback registry ( wire). */
class RecipeCompletionCallbacksTest {

    @BeforeEach @AfterEach
    void clean() { RecipeCompletionCallbacks.resetForTests(); }

    @Test
    void firesOnceWithSucceededFlagThenRemoves() {
        var calls = new AtomicInteger();
        var sawSucceeded = new AtomicReference<Boolean>();
        RecipeCompletionCallbacks.register("q1", ok -> { calls.incrementAndGet(); sawSucceeded.set(ok); });
        assertEquals(1, RecipeCompletionCallbacks.pending());

        RecipeCompletionCallbacks.fireAndRemove("q1", true);
        assertEquals(1, calls.get());
        assertTrue(sawSucceeded.get());
        assertEquals(0, RecipeCompletionCallbacks.pending(), "fired callback is removed");

        // A second terminal for the same id is a no-op (one-shot).
        RecipeCompletionCallbacks.fireAndRemove("q1", false);
        assertEquals(1, calls.get());
    }

    @Test
    void failedOutcomePassesFalse() {
        var ok = new AtomicReference<Boolean>();
        RecipeCompletionCallbacks.register("q2", ok::set);
        RecipeCompletionCallbacks.fireAndRemove("q2", false);
        assertFalse(ok.get());
    }

    @Test
    void cancelDropsWithoutFiring() {
        var fired = new AtomicBoolean();
        RecipeCompletionCallbacks.register("q3", ok -> fired.set(true));
        RecipeCompletionCallbacks.cancel("q3");
        RecipeCompletionCallbacks.fireAndRemove("q3", true);
        assertFalse(fired.get(), "cancelled callback never fires");
        assertEquals(0, RecipeCompletionCallbacks.pending());
    }

    @Test
    void unknownIdAndNullsAreNoOps() {
        assertDoesNotThrow(() -> RecipeCompletionCallbacks.fireAndRemove("nope", true));
        assertDoesNotThrow(() -> RecipeCompletionCallbacks.register(null, ok -> {}));
        assertDoesNotThrow(() -> RecipeCompletionCallbacks.register("q", null));
        assertEquals(0, RecipeCompletionCallbacks.pending());
    }

    @Test
    void throwingCallbackIsSwallowedAndStillRemoved() {
        RecipeCompletionCallbacks.register("boom", ok -> { throw new RuntimeException("x"); });
        assertDoesNotThrow(() -> RecipeCompletionCallbacks.fireAndRemove("boom", true));
        assertEquals(0, RecipeCompletionCallbacks.pending(), "a throwing callback is still removed");
    }
}
