package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A caller that has stopped waiting takes its request back. Before this a one-shot past its
 * timeout ran on at the backend; on a one-slot lane every later request started its clock behind
 * a reply nobody would read, and none could finish (a household's 0.5.4 report of the language
 * healer on a CPU voice lane, 2026-10-05).
 */
class ACancelledRequestFreesTheLineTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDown() { testKit.shutdownTestKit(); }

    private static InferenceRouter.ChatRequest ask(String id, org.apache.pekko.actor.typed.ActorRef<InferenceRouter.InferResponse> to) {
        return new InferenceRouter.ChatRequest(id, "wyrdsekai-3.5-9b",
            List.of(new InferenceClient.ChatMessage("user", "say the note in English")), 64, 0.2,
            to, null, null, null, null, null, null, null, null, false);
    }

    private static InferenceClient.ChatResponse answer(String text) {
        var msg = new InferenceClient.ChatMessage("assistant", text);
        return new InferenceClient.ChatResponse("id", "chat.completion", 0L, "wyrdsekai-3.5-9b",
            List.of(new InferenceClient.Choice(0, msg, "stop")), new InferenceClient.Usage(1, 1, 2));
    }

    private static void until(java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 100 && !ok.getAsBoolean(); i++) Thread.sleep(30);
        assertThat(ok.getAsBoolean()).as(what).isTrue();
    }

    @Test
    void cancelled_in_flight_frees_the_slot_and_cancelled_in_line_is_never_sent() throws Exception {
        var held = new CopyOnWriteArrayList<CompletableFuture<InferenceClient.ChatResponse>>();
        var sent = new CopyOnWriteArrayList<String>();
        InferenceBackend.NatsRemote.RemoteCaller caller = (targetZone, sourceZone, request, tokenCallback) -> {
            var f = new CompletableFuture<InferenceClient.ChatResponse>();
            held.add(f);
            sent.add(request.messages().getLast().content());
            return f;
        };
        var router = testKit.spawn(InferenceRouter.create(List.of(), "wyrdsekai-3.5-9b", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(caller));
        router.tell(new InferenceRouter.AddRemoteBackend("one-slot", "llama-server", "nats://self",
            List.of("wyrdsekai-3.5-9b"), 5, true));
        Thread.sleep(100);
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();

        // One slot: the first request is on the wire, the second waits in line.
        router.tell(ask("one", probe.ref()));
        until(() -> held.size() == 1, "the first request reached the backend");
        router.tell(ask("two", probe.ref()));
        until(() -> InferenceRouter.snapshot().queued() == 1, "the second request waits in line");

        // Its caller takes the waiting one back: it never reaches the backend, and the caller hears so.
        router.tell(new InferenceRouter.CancelRequest("two"));
        var gone = probe.expectMessageClass(InferenceRouter.InferError.class, Duration.ofSeconds(3));
        assertThat(gone.requestId()).isEqualTo("two");
        assertThat(held).hasSize(1);

        // Its caller takes the one on the wire back: the call itself is cancelled, the slot comes back.
        router.tell(new InferenceRouter.CancelRequest("one"));
        until(() -> held.get(0).isCancelled(), "the call at the backend is cancelled");
        var ended = probe.expectMessageClass(InferenceRouter.InferError.class, Duration.ofSeconds(3));
        assertThat(ended.requestId()).isEqualTo("one");
        until(() -> InferenceRouter.snapshot().inFlight() == 0, "the slot is free again");

        // The next request goes straight through and is answered on the same, unblamed backend.
        router.tell(ask("three", probe.ref()));
        until(() -> held.size() == 2, "the third request reached the backend");
        held.get(1).complete(answer("the note, in English"));
        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class, Duration.ofSeconds(3));
        assertThat(ok.requestId()).isEqualTo("three");
        assertThat(ok.content()).contains("in English");
        // The router stamps the day onto the message; what was asked ends each one.
        assertThat(sent).hasSize(2).allMatch(t -> t.endsWith("say the note in English"));
    }
}
