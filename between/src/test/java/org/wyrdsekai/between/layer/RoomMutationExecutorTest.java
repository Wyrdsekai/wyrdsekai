package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.layer.MutationRouter.ForwardedMutation;
import org.wyrdsekai.between.layer.MutationRouter.MutationResult;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Definitive re-audit fix (#33-3): before the fix, a forwarded mutation reaching
 * the primary hit {@code "No mutation handler configured"} because
 * {@link MutationRouter#setMutationHandler} had no production caller.
 * {@link RoomMutationExecutor} is the wired handler; these tests exercise it
 * against a stand-in RoomActor.
 */
class RoomMutationExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ActorTestKit testKit;

    @BeforeAll
    static void setUp() {
        testKit = ActorTestKit.create("RoomMutationExecutorTest");
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    /** A stand-in RoomActor that replies Ok to a "look" command. */
    private ActorRef<RoomCommand> okRoom() {
        return testKit.spawn(Behaviors.receive(RoomCommand.class)
            .onMessage(RoomCommand.LookRoom.class, msg -> {
                msg.replyTo().tell(new RoomResponse.Ok(
                    new RoomSnapshot("room-1", "Nexus", "A hub", "alpha",
                        List.of(), List.of(), List.of(), List.of())));
                return Behaviors.same();
            })
            .build());
    }

    private ForwardedMutation lookMutation(String key) throws Exception {
        var command = MAPPER.createObjectNode();
        command.put("type", "look");
        command.put("entityId", "did:wyrd:steward");
        return new ForwardedMutation("look", "room-1", 7L, key,
            command, "node-replica", Instant.now());
    }

    @Test
    void applies_mutation_on_resident_primary_and_publishes_success() throws Exception {
        var room = okRoom();
        var result = new AtomicReference<MutationResult>();
        var latch = new CountDownLatch(1);

        var handler = RoomMutationExecutor.build(
            roomId -> room,
            r -> { result.set(r); latch.countDown(); });

        handler.accept("room-1", lookMutation("key-ok"));

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().idempotencyKey()).isEqualTo("key-ok");
        assertThat(result.get().success()).isTrue();
        assertThat(result.get().epoch()).isEqualTo(7L);
    }

    @Test
    void reports_failure_when_room_not_resident() throws Exception {
        var result = new AtomicReference<MutationResult>();

        var handler = RoomMutationExecutor.build(
            roomId -> null,          // room not resident on this primary
            result::set);

        handler.accept("room-1", lookMutation("key-absent"));

        // Null-ref path is synchronous — no dispatch happens.
        assertThat(result.get()).isNotNull();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().reason()).contains("not resident");
        assertThat(result.get().idempotencyKey()).isEqualTo("key-absent");
    }
}
