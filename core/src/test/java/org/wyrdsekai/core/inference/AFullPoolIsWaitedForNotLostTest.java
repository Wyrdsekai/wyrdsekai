package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household brain serves two slots on one shared context pool. On 2026-10-23 two workshop
 * jobs held both slots, and when a companion's own time and an item got their turn the brain
 * answered "Context size has been exceeded." with no number. The router's compaction keyed on
 * that number, so nothing was trimmed, nothing was retried, and the turn was lost. Now a
 * numberless overflow is trimmed to one slot's fair share of the window and sent again; when
 * nothing more can be trimmed, the pool is waited for, a little at a time, before the honest
 * failure.
 */
class AFullPoolIsWaitedForNotLostTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));
    private static final String POOL_FULL = "Chat completion failed: HTTP 500 — {\"error\":{\"code\":500,"
        + "\"message\":\"Context size has been exceeded.\",\"type\":\"server_error\"}}";
    private static Duration poolWaitBefore;

    @BeforeAll
    static void quickWaits() {
        poolWaitBefore = InferenceRouter.POOL_WAIT;
        InferenceRouter.POOL_WAIT = Duration.ofMillis(150);
    }

    @AfterAll
    static void tearDown() {
        InferenceRouter.POOL_WAIT = poolWaitBefore;
        testKit.shutdownTestKit();
    }

    @BeforeEach
    @AfterEach
    void clean() { BackendWindows.resetForTests(); }

    private static InferenceRouter.ChatRequest ask(String id, List<InferenceClient.ChatMessage> messages,
                                                   ActorRef<InferenceRouter.InferResponse> to) {
        return new InferenceRouter.ChatRequest(id, "brain-model", messages, 256, 0.2,
            to, null, null, null, null, null, null, null, null, false);
    }

    private static InferenceClient.ChatResponse answer(String text) {
        var msg = new InferenceClient.ChatMessage("assistant", text);
        return new InferenceClient.ChatResponse("id", "chat.completion", 0L, "brain-model",
            List.of(new InferenceClient.Choice(0, msg, "stop")), new InferenceClient.Usage(1, 1, 2));
    }

    private static void until(BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 200 && !ok.getAsBoolean(); i++) Thread.sleep(25);
        assertThat(ok.getAsBoolean()).as(what).isTrue();
    }

    /** A brain of two slots on one 32K pool, as the household node runs it. */
    private ActorRef<InferenceRouter.Command> brain(InferenceBackend.NatsRemote.RemoteCaller caller) throws Exception {
        var router = testKit.spawn(InferenceRouter.create(List.of(), "brain-model", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(caller));
        router.tell(new InferenceRouter.AddRemoteBackend("brain", "llama-server", "nats://self",
            List.of("brain-model"), 5, true));
        Thread.sleep(100);
        BackendWindows.learnedFromLlamaProps("brain", "http://127.0.0.1:8200",
            "{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":2}", "/props");
        return router;
    }

    @Test
    void a_numberless_overflow_is_trimmed_to_one_slots_share_and_sent_again() throws Exception {
        var sent = new CopyOnWriteArrayList<InferenceClient.ChatRequest>();
        InferenceBackend.NatsRemote.RemoteCaller caller = (targetZone, sourceZone, request, tokenCallback) -> {
            sent.add(request);
            var f = new CompletableFuture<InferenceClient.ChatResponse>();
            if (sent.size() == 1) f.completeExceptionally(new InferenceClient.InferenceException(POOL_FULL));
            else f.complete(answer("still here"));
            return f;
        };
        var router = brain(caller);
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();

        // Her own time: a long record behind a short question — ~24K tokens, over a 16K share.
        var history = new ArrayList<InferenceClient.ChatMessage>();
        history.add(new InferenceClient.ChatMessage("system", "You are a companion. " + "word ".repeat(600)));
        for (int i = 0; i < 20; i++) {
            history.add(new InferenceClient.ChatMessage(i % 2 == 0 ? "user" : "assistant", "earlier " + "word ".repeat(600)));
        }
        history.add(new InferenceClient.ChatMessage("user", "what do you make of the day?"));
        router.tell(ask("own-time", history, probe.ref()));

        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(5));
        assertThat(ok.requestId()).isEqualTo("own-time");
        assertThat(sent).as("refused once, trimmed, sent again").hasSize(2);
        int before = sent.get(0).messages().size();
        int after = sent.get(1).messages().size();
        assertThat(after).as("the retry carried less of the record").isLessThan(before);
        assertThat(InferenceRouter.promptTokensLenient(sent.get(1)))
            .as("and fits one slot's share of the pool").isLessThanOrEqualTo(16384);
        assertThat(sent.get(1).messages().getLast().content()).endsWith("what do you make of the day?");
    }

    @Test
    void when_nothing_more_can_be_trimmed_the_pool_is_waited_for() throws Exception {
        var sent = new CopyOnWriteArrayList<InferenceClient.ChatRequest>();
        InferenceBackend.NatsRemote.RemoteCaller caller = (targetZone, sourceZone, request, tokenCallback) -> {
            sent.add(request);
            var f = new CompletableFuture<InferenceClient.ChatResponse>();
            // The pool stays full for the first three asks, then a slot's work finishes.
            if (sent.size() <= 3) f.completeExceptionally(new InferenceClient.InferenceException(POOL_FULL));
            else f.complete(answer("room at last"));
            return f;
        };
        var router = brain(caller);
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();

        // An item's call: a short system line and a short question — nothing to trim.
        router.tell(ask("item-1", List.of(
            new InferenceClient.ChatMessage("system", "Summarise the page for the shelf."),
            new InferenceClient.ChatMessage("user", "A short page about tides.")), probe.ref()));

        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(10));
        assertThat(ok.requestId()).isEqualTo("item-1");
        assertThat(ok.content()).contains("room at last");
        assertThat(sent).as("asked again after each wait until the pool had room").hasSize(4);
        until(() -> InferenceRouter.snapshot().inFlight() == 0, "the line is free again");
    }

    @Test
    void a_pool_that_never_clears_ends_in_the_honest_failure() throws Exception {
        var sent = new CopyOnWriteArrayList<InferenceClient.ChatRequest>();
        InferenceBackend.NatsRemote.RemoteCaller caller = (targetZone, sourceZone, request, tokenCallback) -> {
            sent.add(request);
            var f = new CompletableFuture<InferenceClient.ChatResponse>();
            f.completeExceptionally(new InferenceClient.InferenceException(POOL_FULL));
            return f;
        };
        var router = brain(caller);
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(ask("item-2", List.of(
            new InferenceClient.ChatMessage("system", "Summarise."),
            new InferenceClient.ChatMessage("user", "Tides.")), probe.ref()));

        var err = probe.expectMessageClass(InferenceRouter.InferError.class, Duration.ofSeconds(15));
        assertThat(err.requestId()).isEqualTo("item-2");
        assertThat(err.error()).contains("Context size has been exceeded");
        assertThat(sent).as("one ask, then one per wait").hasSize(1 + InferenceRouter.POOL_WAITS_MAX);
        until(() -> InferenceRouter.snapshot().inFlight() == 0, "the line is free again");
    }
}
