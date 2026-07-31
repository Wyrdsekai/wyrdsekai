package org.wyrdsekai.e2e.tier4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.LlamaDockerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Phone voice relay — simulated VoiceTranscription routes through real inference.
 * Two real llama-server Docker containers: 0.6B (phone) + 4B (desktop).
 * Uses own fixtures (not shared pool) because voice_with_local_fallback
 * stops/restarts the desktop container.
 */
@Tag("relay")
class PhoneVoiceRelayTest {

    private static final String PHONE_MODEL = "Qwen3-0.6B-Q8_0.gguf";
    private static final String DESKTOP_MODEL = "Qwen3-4B-Q4_K_M.gguf";

    private static LlamaDockerFixture llamaPhone;
    private static LlamaDockerFixture llamaDesktop;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaDockerFixture.assumeAvailable();
        LlamaDockerFixture.assumeModelAvailable(PHONE_MODEL);
        LlamaDockerFixture.assumeModelAvailable(DESKTOP_MODEL);

        llamaPhone = new LlamaDockerFixture(
            "phone-voice", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaDesktop = new LlamaDockerFixture(
            "desktop-voice", DESKTOP_MODEL, PortAllocator.allocate(), 4096);

        llamaPhone.start();
        llamaDesktop.start();

        var desktopBackend = llamaDesktop.createBackend("desktop-relay", 10);
        var phoneBackend = llamaPhone.createBackend("phone-local", 100);

        server = new TestServerBootstrap(List.of(desktopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (llamaDesktop != null) llamaDesktop.stop();
        if (llamaPhone != null) llamaPhone.stop();
    }

    @Test
    void voice_transcription_routes_to_room() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            // Simulate voice input as text
            ws.sendSay("nexus", "Hello, can you hear me? This is a voice test.");
            var response = ws.waitForProse(Duration.ofSeconds(60));
            assertProseReceived(response, "voice transcription response");
        }
    }

    @Test
    void voice_with_local_fallback() throws Exception {
        // Desktop goes down
        llamaDesktop.stop();

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(90));

            ws.sendSay("nexus", "Voice test with local model.");
            var response = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(response,
                "[HARD] Should still respond via phone-local when desktop offline");
        }

        // Restart
        llamaDesktop.restart();
    }
}
