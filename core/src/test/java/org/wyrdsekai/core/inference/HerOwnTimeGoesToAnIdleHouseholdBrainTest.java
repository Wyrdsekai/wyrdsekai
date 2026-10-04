package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Hermod first errand: a companion's own-time turn goes to another machine of the house when
 * the line here is busy with someone's turn and a household brain is advertised. A person's turn
 * never leaves; an idle line keeps her own time; a household that declines hands the turn back.
 *
 * <p>Before 2026-10-03 the carrier existed for bunshin turns only; on a one-brain node her own time
 * waited behind every person's turn, and the fairness queue was the whole answer.
 */
class HerOwnTimeGoesToAnIdleHouseholdBrainTest {

    static final ActorTestKit testKit = ActorTestKit.create(ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll static void tearDown() { testKit.shutdownTestKit(); }
    @AfterEach void noCarrier() { MeshDispatch.install(null); }

    /** The brain here: one slot, answers only when the test releases a request by a word in it. */
    static final class LocalBrain implements InferenceBackend.NatsRemote.RemoteCaller {
        record Pending(InferenceClient.ChatRequest request, CompletableFuture<InferenceClient.ChatResponse> future) {}
        final java.util.List<Pending> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public CompletableFuture<InferenceClient.ChatResponse> call(String target, String source,
                InferenceClient.ChatRequest request, java.util.function.Consumer<String> cb) {
            var f = new CompletableFuture<InferenceClient.ChatResponse>();
            pending.add(new Pending(request, f));
            return f;
        }
        /** Answers the first unanswered request whose messages carry {@code key}; waits up to 3 s for it to arrive. */
        void answer(String key, String text) throws InterruptedException {
            for (int i = 0; i < 30; i++) {
                for (var p : pending) {
                    if (p.future().isDone()) continue;
                    boolean hit = p.request().messages().stream().anyMatch(m -> m.content() != null && m.content().contains(key));
                    if (hit) { p.future().complete(response(text, 3, 4)); return; }
                }
                Thread.sleep(100);
            }
            throw new AssertionError("no pending request carries '" + key + "'");
        }
    }

    /** Another machine of the house. */
    static final class Laptop implements MeshDispatch.Carrier {
        volatile boolean present = true;
        volatile boolean takes = true;
        final AtomicInteger carried = new AtomicInteger();
        volatile String lastJson;
        @Override public boolean someoneElseCanTake() { return present; }
        @Override public String carryChat(String chatRequestJson, long tokenBudget) {
            lastJson = chatRequestJson;
            if (!takes) return null;
            carried.incrementAndGet();
            try { return Json.mapper().writeValueAsString(response("from the laptop", 5, 7)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    static InferenceClient.ChatResponse response(String text, int in, int out) {
        return new InferenceClient.ChatResponse("id", "chat.completion", 0L, "m",
            List.of(new InferenceClient.Choice(0, new InferenceClient.ChatMessage("assistant", text), "stop")),
            new InferenceClient.Usage(in, out, in + out));
    }

    private static InferenceRouter.ChatRequest chat(String id, org.apache.pekko.actor.typed.ActorRef<InferenceRouter.InferResponse> to, String text) {
        return new InferenceRouter.ChatRequest(id, "m", List.of(new InferenceClient.ChatMessage("user", text)), 64, 0.0, to, "here");
    }

    @Test
    void her_own_time_crosses_when_a_person_holds_the_line_and_a_household_brain_is_there() throws Exception {
        var brain = new LocalBrain();
        var laptop = new Laptop();
        MeshDispatch.install(laptop);
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(brain));
        router.tell(new InferenceRouter.AddRemoteBackend("here", "llama-server", "nats://here", List.of("m"), 100));
        Thread.sleep(150);
        var person = testKit.<InferenceRouter.InferResponse>createTestProbe();
        var her = testKit.<InferenceRouter.InferResponse>createTestProbe();

        // A person's turn takes the one slot and is not answered yet.
        router.tell(chat("p-1", person.ref(), "hello from a person"));
        Thread.sleep(100);
        // Her own time arrives: the laptop answers it while the person's turn is still being served.
        router.tell(chat("o-1" + InferenceRouter.OWN_TIME_TAG, her.ref(), "a thought of her own"));
        var ok = her.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
        assertThat(ok.content()).isEqualTo("from the laptop");
        assertThat(ok.promptTokens()).isEqualTo(5);
        assertThat(laptop.carried.get()).isEqualTo(1);
        assertThat(laptop.lastJson).contains("a thought of her own");
        person.expectNoMessage(Duration.ofMillis(200));
        // The person's turn is answered here when the brain answers.
        brain.answer("hello from a person", "local answer");
        assertThat(person.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5)).content()).isEqualTo("local answer");
        assertThat(InferenceRouter.snapshot().householdCarried()).isGreaterThanOrEqualTo(1);

        // An idle line keeps her own time here.
        Thread.sleep(100);
        router.tell(chat("o-2" + InferenceRouter.OWN_TIME_TAG, her.ref(), "another thought"));
        Thread.sleep(150);
        brain.answer("another thought", "answered here");
        assertThat(her.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5)).content()).isEqualTo("answered here");
        assertThat(laptop.carried.get()).as("nothing crossed").isEqualTo(1);

        // A person's turn never crosses, however busy the line.
        router.tell(chat("p-2", person.ref(), "a person again"));
        router.tell(chat("p-3", person.ref(), "a second person"));
        Thread.sleep(200);
        assertThat(laptop.carried.get()).isEqualTo(1);
        brain.answer("a person again", "one"); brain.answer("a second person", "two");
        person.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
        person.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
    }

    @Test
    void with_no_household_brain_or_a_household_that_declines_her_own_time_waits_its_turn_here() throws Exception {
        var brain = new LocalBrain();
        var laptop = new Laptop();
        laptop.present = false;
        MeshDispatch.install(laptop);
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(brain));
        router.tell(new InferenceRouter.AddRemoteBackend("here", "llama-server", "nats://here", List.of("m"), 100));
        Thread.sleep(150);
        var person = testKit.<InferenceRouter.InferResponse>createTestProbe();
        var her = testKit.<InferenceRouter.InferResponse>createTestProbe();

        router.tell(chat("p-1", person.ref(), "busy"));
        Thread.sleep(100);
        router.tell(chat("o-1" + InferenceRouter.OWN_TIME_TAG, her.ref(), "waits"));
        her.expectNoMessage(Duration.ofMillis(300));
        brain.answer("busy", "done");
        person.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
        Thread.sleep(150);
        brain.answer("waits", "served here after");
        assertThat(her.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5)).content()).isEqualTo("served here after");
        assertThat(laptop.carried.get()).isZero();

        // Present but declining: the turn comes back and is served here.
        laptop.present = true; laptop.takes = false;
        router.tell(chat("p-2", person.ref(), "busy again"));
        Thread.sleep(100);
        router.tell(chat("o-2" + InferenceRouter.OWN_TIME_TAG, her.ref(), "comes back"));
        Thread.sleep(300);
        brain.answer("busy again", "done again");
        person.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
        Thread.sleep(200);
        brain.answer("comes back", "served here at last");
        assertThat(her.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5)).content()).isEqualTo("served here at last");
    }
}
