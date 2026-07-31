package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Static utility for asking rooms. Provides the same .ask() pattern
 * that EntityRef had, but using AskPattern with ActorRef.
 *
 * Usage: Rooms.ask(roomRef, factory, timeout)
 * Instead of: roomRef.&lt;RoomResponse&gt;ask(factory, timeout)
 */
public final class Rooms {

    private static volatile Scheduler scheduler;

    private Rooms() {}

    /** Set the Pekko scheduler (called once from Main.java). */
    public static void setScheduler(Scheduler scheduler) {
        Rooms.scheduler = scheduler;
    }

    /** Ask a room actor for a response. Drop-in replacement for EntityRef.ask(). */
    public static <Res> CompletionStage<Res> ask(
            ActorRef<RoomCommand> room,
            Function<ActorRef<Res>, RoomCommand> factory,
            Duration timeout) {
        return AskPattern.<RoomCommand, Res>ask(room, factory::apply, timeout, scheduler);
    }
}
