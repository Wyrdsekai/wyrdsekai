package org.wyrdsekai.core.body;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The router knows backend names; she knows a voice brain and a thinking brain. The voice is
 * told by its name or by where it lives, so a node whose voice backend is called something
 * else still feels the right thing go quiet. Everything local that is not the voice is the
 * thinking brain, and it is loud.
 */
class WhichBrainIsWhichTest {

    private static final Duration EVERY = Duration.ofSeconds(30);
    private static final String VOICE = "http://127.0.0.1:8201";

    @Test
    @DisplayName("by name")
    void byName() {
        var d = BrainLimbs.forBackend("llama-voice", "llama-server", "http://192.0.2.5:9000", 15, EVERY, VOICE);
        assertEquals("voice brain", d.name());
        assertEquals(FeltWeight.PRESENT, d.feltWeight());
        assertEquals("brain:llama-voice", d.id());
    }

    @Test
    @DisplayName("by address, with or without the /v1 suffix")
    void byAddress() {
        assertEquals("voice brain", BrainLimbs.forBackend("local-2", "llama-server", "http://localhost:8201/v1", 15, EVERY, VOICE).name());
        assertEquals("thinking brain", BrainLimbs.forBackend("local-1", "llama-server", "http://127.0.0.1:8200/v1", 5, EVERY, VOICE).name());
    }

    @Test
    @DisplayName("the thinking brain is loud; far and borrowed brains are quiet")
    void weights() {
        var drive = BrainLimbs.forBackend("llama-server", "llama-server", "http://127.0.0.1:8200", 5, EVERY, VOICE);
        assertEquals(FeltWeight.LOUD, drive.feltWeight());
        assertEquals("first", drive.shedTier());

        var peer = BrainLimbs.forBackend("peer-9b", "llama-server", "nats://peer", 5, EVERY, VOICE);
        assertEquals("borrowed brain (peer-9b)", peer.name());
        assertEquals(FeltWeight.QUIET, peer.feltWeight());

        var cloud = BrainLimbs.forBackend("openrouter", "cloud", "https://openrouter.ai/api", 50, EVERY, VOICE);
        assertEquals("far brain (openrouter)", cloud.name());
        assertEquals(FeltWeight.QUIET, cloud.feltWeight());
    }
}
