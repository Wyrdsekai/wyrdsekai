package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.BrainLimbs;
import org.wyrdsekai.core.body.PartState;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router's health loop is a brain's heartbeat. A backend the mesh adds is a part on the
 * map; a discovery miss marks it numb and her line will say so; the peer coming back marks it
 * back; the mesh removing it declares it gone; a turn it answers is work she can see it did.
 * None of this needs a model.
 */
class ABrainIsAPartOfTheBodyTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDownKit() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void setUp() {
        BodyMap.inMemory();
    }

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    private static InferenceBackend.NatsRemote.RemoteCaller answering(String text) {
        return (targetZone, sourceZone, request, tokenCallback) -> {
            var msg = new InferenceClient.ChatMessage("assistant", text);
            var choice = new InferenceClient.Choice(0, msg, "stop");
            var usage = new InferenceClient.Usage(3, 2, 5);
            return CompletableFuture.completedFuture(new InferenceClient.ChatResponse(
                "id", "chat.completion", System.currentTimeMillis() / 1000,
                request.model(), List.of(choice), usage));
        };
    }

    @Test
    @DisplayName("a borrowed brain: attached by the mesh, numb on a miss, back on return, gone when removed")
    void aBorrowedBrainLivesOnTheMap() {
        var map = BodyMap.get();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "wyrdsekai-3.5-9b", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(answering("hello")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "peer-9b", "llama-server", "nats://peer", List.of("wyrdsekai-3.5-9b"), 5, true));
        sleep(120);

        var id = BrainLimbs.id("peer-9b");
        var part = map.part(id).orElse(null);
        assertNotNull(part, "the mesh attached it");
        assertEquals("borrowed brain (peer-9b)", part.name());
        assertEquals(PartState.ATTACHED, part.state());

        router.tell(new InferenceRouter.SetBackendHealth("peer-9b", false));
        sleep(80);
        assertEquals(PartState.NUMB, map.part(id).orElseThrow().state(), "a discovery miss is an interrupt");
        assertTrue(map.recentMarks(1).get(0).text().contains("borrowed brain (peer-9b) went quiet"));

        router.tell(new InferenceRouter.SetBackendHealth("peer-9b", true));
        sleep(80);
        assertEquals(PartState.ATTACHED, map.part(id).orElseThrow().state());

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-body", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref()));
        probe.expectMessageClass(InferenceRouter.InferOk.class);
        sleep(80);
        assertNotNull(map.part(id).orElseThrow().lastUsed(), "a turn it answered is work she can see");

        router.tell(new InferenceRouter.RemoveRemoteBackend("peer-9b"));
        sleep(80);
        var gone = map.part(id).orElseThrow();
        assertEquals(PartState.GONE, gone.state());
        assertEquals("mesh discovery", gone.goneBy());
        testKit.stop(router);
    }

    @Test
    @DisplayName("a stranger's brain is held at the door and never selected; vouched, it thinks for her")
    void aStrangersBrainWaitsAtTheDoor() {
        var map = BodyMap.get();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "wyrdsekai-3.5-9b", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(answering("from the stranger")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-stranger-mlx", "mlx", "nats://orchard", List.of("wyrdsekai-3.5-9b"), 105, false, "did:key:z6MkStranger"));
        sleep(120);

        var id = BrainLimbs.id("remote-stranger-mlx");
        var part = map.part(id).orElseThrow();
        assertEquals(PartState.QUARANTINED, part.state(), "offered by a node the household does not know");
        assertEquals("did:key:z6MkStranger", part.descriptor().attachedBy());
        assertTrue(map.held(id));

        // The router's health loop heartbeats it; it stays held (the first live foreign node was let in this way).
        router.tell(new InferenceRouter.SetBackendHealth("remote-stranger-mlx", true));
        sleep(80);
        assertEquals(PartState.QUARANTINED, map.part(id).orElseThrow().state());

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest("req-held", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")), 64, 0.0, probe.ref()));
        var answer = probe.receiveMessage();
        assertTrue(answer instanceof InferenceRouter.InferError, "the only brain is held, so there is no brain: " + answer);

        map.vouch(id, "the steward");
        router.tell(new InferenceRouter.ChatRequest("req-vouched", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")), 64, 0.0, probe.ref()));
        probe.expectMessageClass(InferenceRouter.InferOk.class);
        testKit.stop(router);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
