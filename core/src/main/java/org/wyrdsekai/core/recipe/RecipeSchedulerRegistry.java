package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.typed.ActorRef;

/**
 * Track-C C9 — process singleton holding the live
 * {@link RecipeScheduler} ActorRef.
 *
 * <p>Lets non-actor callers — agent {@code request_recipe} handler,
 * sleep-pass gap-detection bridge, {@code wyrd recipes run} REST path
 * — drop {@link RecipeScheduler.Enqueue} / {@link
 * RecipeScheduler.ForceFire} messages without having to thread the
 * ActorRef through every caller. Same pattern as the other process
 * singletons in this codebase ({@link RecipeRunLog},
 * {@link org.wyrdsekai.core.agent.interiority.ChronicleEntryStore}).</p>
 *
 * <p>Production wiring: {@code Main.java} calls {@link #setInstance}
 * after spawning the actor (or never, when the scheduler is disabled).
 * {@link #get} returns {@code null} when no scheduler is running —
 * callers must null-check, since the fall-through is "direct dispatch"
 * (Track-A A3 path) rather than "fail loud."</p>
 */
public final class RecipeSchedulerRegistry {

    private RecipeSchedulerRegistry() {}

    private static volatile ActorRef<RecipeScheduler.Command> INSTANCE;

    public static ActorRef<RecipeScheduler.Command> get() { return INSTANCE; }

    public static void setInstance(ActorRef<RecipeScheduler.Command> ref) {
        INSTANCE = ref;
    }

    public static void resetForTests() { INSTANCE = null; }
}
