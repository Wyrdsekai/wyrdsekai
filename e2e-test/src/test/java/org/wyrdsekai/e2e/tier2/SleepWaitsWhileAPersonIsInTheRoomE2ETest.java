package org.wyrdsekai.e2e.tier2;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.wyrdsekai.core.update.ActivityGauge;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.StandInBrain;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sleep from a full day waits while a person is in her room (2026-10-05). On the household node a
 * companion's backlog reached its target mid-conversation and the 30 s grace took her home while he
 * was reading her answer. Here a real node, a quick stand-in brain, a pressure target of three
 * events and a ten-minute wait shortened to ninety seconds: she stays while the person is with her,
 * sleeps when they fall quiet or leave, and exhaustion still takes her after the grace.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SleepWaitsWhileAPersonIsInTheRoomE2ETest {

    private static final String ENTITY = "companion-wyrd";
    private static final String ROOM = "nexus";
    private static final Duration WAIT_WITH_PERSON = Duration.ofSeconds(90);

    private static StandInBrain brain;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        CompanionActor.sleepTargetForTests(3);
        CompanionActor.sleepWaitWithPersonForTests(WAIT_WITH_PERSON);
        brain = new StandInBrain(StandInBrain.Mode.TRANSLATE);
        var client = new InferenceClient(brain.url(), null, Duration.ofSeconds(120), new ApiProvider.OpenAI("llama-server"));
        server = new TestServerBootstrap(List.of(new InferenceBackend.LlamaServer("stand-in", client, 1, List.of("test-model"), null)));
        server.start();
        until(() -> ZoneGuardian.getCompanionRef(null, ENTITY) != null, Duration.ofSeconds(60), "the companion is up");
    }

    @AfterAll
    static void tearDown() {
        CompanionActor.sleepTargetForTests(null);
        CompanionActor.sleepWaitWithPersonForTests(null);
        if (server != null) server.stop();
        if (brain != null) brain.close();
    }

    @Order(1)
    @Test
    void she_stays_while_the_person_is_with_her_and_sleeps_once_they_have_been_quiet() throws Exception {
        try (var ws = personInHerRoom()) {
            settleLow(0.30);
            long sleepsBefore = ActivityGauge.sleepsStarted();
            var lastLine = talk(ws, 4);
            // The old rule: asleep 30 s after her last reply. At 50 s she is still up, with the person there.
            var trace = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                Thread.sleep(1_000);
                if (i % 5 == 4) trace.append(String.format("%ds=%.2f ", i + 1, ask().energy()));
            }
            System.out.println("[sleep-waits] energy while the person stays: " + trace);
            assertThat(Duration.between(lastLine, Instant.now())).isGreaterThan(Duration.ofSeconds(45)).isLessThan(WAIT_WITH_PERSON);
            assertThat(ActivityGauge.sleepsStarted()).as("no sleep began with the person still there").isEqualTo(sleepsBefore);
            // The person stays but says nothing more: once they have been quiet for the wait, she sleeps with them there.
            until(() -> ActivityGauge.sleepsStarted() > sleepsBefore, Duration.ofSeconds(120), "she sleeps once the person has been quiet for the wait");
        }
    }

    @Order(2)
    @Test
    void she_sleeps_within_the_grace_once_the_person_leaves() throws Exception {
        settleAfterSleep();
        server.respawnCompanion();
        Thread.sleep(3_000);
        long sleepsBefore;
        // Walking out is not leaving: she follows her bondholder from room to room, and the wait
        // holds in the new room too. Leaving is logging out, which is what closing the line does.
        try (var ws = personInHerRoom()) {
            settleLow(0.30);
            sleepsBefore = ActivityGauge.sleepsStarted();
            talk(ws, 4);
            Thread.sleep(5_000);
            assertThat(ActivityGauge.sleepsStarted()).isEqualTo(sleepsBefore);
        }
        var left = Instant.now();
        until(() -> ActivityGauge.sleepsStarted() > sleepsBefore, Duration.ofSeconds(75), "with the person gone, the grace alone decides");
        assertThat(Duration.between(left, Instant.now())).as("not before the grace").isGreaterThan(Duration.ofSeconds(25));
    }

    @Order(3)
    @Test
    void exhaustion_still_takes_her_after_the_grace_with_the_person_there() throws Exception {
        settleAfterSleep();
        server.respawnCompanion();
        Thread.sleep(3_000);
        try (var ws = personInHerRoom()) {
            talk(ws, 2);
            long sleepsBefore = ActivityGauge.sleepsStarted();
            settleLow(0.10);   // below the threshold: exhausted
            until(() -> ActivityGauge.sleepsStarted() > sleepsBefore, Duration.ofSeconds(75), "exhausted, she sleeps after the grace even in company");
        }
    }

    // ── helpers ──

    /**
     * A sleep seen begin is still running, and ends with a walk home. A reset in the middle of
     * that walk loses the reset's own move back to the common room, so the scenes wait for the
     * sleep to end and her room to hold still first.
     */
    private static void settleAfterSleep() throws Exception {
        until(() -> !ask().isSleeping(), Duration.ofSeconds(60), "her sleep has ended");
        String room = ask().roomId();
        for (int still = 0; still < 6; ) {
            Thread.sleep(500);
            var now = ask().roomId();
            if (now.equals(room)) still++; else { still = 0; room = now; }
        }
    }

    private static TestWebSocketClient personInHerRoom() throws Exception {
        // A sleep takes her home; each scene starts with her back in the common room. Asked again
        // every ten seconds: a walk asked for in the moment she is still settling is not taken.
        for (int i = 0; i < 6 && !ROOM.equals(ask().roomId()); i++) {
            ref().tell(new CompanionActor.BridgeGo(ROOM));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && !ROOM.equals(ask().roomId())) Thread.sleep(500);
        }
        assertThat(ask().roomId()).as("she is in " + ROOM).isEqualTo(ROOM);
        var ws = TestWebSocketClient.connect(server.baseUrl(), null, null, ROOM);
        ws.waitForRoomState(Duration.ofSeconds(30));
        return ws;
    }

    /** Set her energy and read it back, so the sleep can be seen as the jump that fills her. */
    private static double settleLow(double energy) throws Exception {
        ref().tell(new CompanionActor.ForceEnergy(energy));
        until(() -> Math.abs(ask().energy() - energy) < 0.05, Duration.ofSeconds(10), "energy set");
        return ask().energy();
    }

    /** The person says {@code n} lines in her room, waiting for her answer to each; when the last was said. */
    private static Instant talk(TestWebSocketClient ws, int n) throws Exception {
        Instant last = Instant.now();
        for (int i = 1; i <= n; i++) {
            ws.sendSay(ROOM, "Wyrd, this is line " + i + " of our talk.");
            last = Instant.now();
            try { ws.waitForProseFrom("Wyrd", Duration.ofSeconds(60)); } catch (Exception ignored) { /* a reply is not the point */ }
        }
        return last;
    }

    private static ActorRef<CompanionActor.Command> ref() {
        var r = ZoneGuardian.getCompanionRef(null, ENTITY);
        assertThat(r).as("the companion is running").isNotNull();
        return r;
    }

    private static CompanionActor.BridgeStateResponse ask() {
        try {
            return AskPattern.<CompanionActor.Command, CompanionActor.BridgeStateResponse>ask(ref(),
                replyTo -> new CompanionActor.BridgeQueryState(replyTo), Duration.ofSeconds(5), server.system().scheduler())
                .toCompletableFuture().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void until(BooleanSupplier ok, Duration limit, String what) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            try { if (ok.getAsBoolean()) return; } catch (RuntimeException ignored) { }
            Thread.sleep(500);
        }
        assertThat(ok.getAsBoolean()).as(what).isTrue();
    }
}
