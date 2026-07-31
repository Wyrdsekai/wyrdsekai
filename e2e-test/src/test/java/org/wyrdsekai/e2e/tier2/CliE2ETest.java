package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.cli.Connection;
import org.wyrdsekai.cli.InputHandler;
import org.wyrdsekai.cli.Renderer;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI E2E test — exercises the real {@code wyrd} CLI components
 * (Connection, InputHandler, Renderer) against a live test server
 * with real inference.
 *
 * <p>This is the "human interface" test: verifies that what a user
 * would see in their terminal is correct and that the CLI's own
 * WebSocket client, command parser, and renderer work end-to-end.
 */
@Tag("e2e")
class CliE2ETest {

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-cli");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void cli_connects_and_renders_room_state() throws Exception {
        var output = new ByteArrayOutputStream();
        var printStream = new PrintStream(output);
        var roomStateLatch = new CountDownLatch(1);

        var renderer = new Renderer(printStream, false);
        var inputHandler = new InputHandler(null, printStream);

        var connection = new Connection("localhost", server.port(),
            msg -> {
                renderer.render(msg);
                if (msg instanceof S2CMessage.RoomState rs) {
                    inputHandler.setCurrentRoomId(rs.room().roomId());
                    roomStateLatch.countDown();
                }
            },
            state -> {});

        inputHandler.setConnection(connection);

        try {
            connection.connect();
            assertTrue(connection.awaitConnected(10_000),
                "[HARD] CLI Connection should connect to server");

            assertTrue(roomStateLatch.await(10, TimeUnit.SECONDS),
                "[HARD] CLI should receive RoomState");

            var rendered = output.toString();
            assertTrue(rendered.contains("Nexus"),
                "[HARD] Rendered output should contain room name 'Nexus'");
            assertTrue(rendered.contains("east") || rendered.contains("Exit"),
                "[HARD] Rendered output should show exits");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void cli_sends_speech_and_renders_response() throws Exception {
        var output = new ByteArrayOutputStream();
        var printStream = new PrintStream(output);
        var roomStateLatch = new CountDownLatch(1);
        var proseLatch = new CountDownLatch(2); // greeting + response
        var proseMessages = new CopyOnWriteArrayList<S2CMessage.Prose>();

        var renderer = new Renderer(printStream, false);
        var inputHandler = new InputHandler(null, printStream);

        var connection = new Connection("localhost", server.port(),
            msg -> {
                renderer.render(msg);
                if (msg instanceof S2CMessage.RoomState rs) {
                    inputHandler.setCurrentRoomId(rs.room().roomId());
                    roomStateLatch.countDown();
                } else if (msg instanceof S2CMessage.Prose prose) {
                    proseMessages.add(prose);
                    proseLatch.countDown();
                }
            },
            state -> {});

        inputHandler.setConnection(connection);

        try {
            connection.connect();
            assertTrue(connection.awaitConnected(10_000),
                "[HARD] CLI should connect");

            assertTrue(roomStateLatch.await(10, TimeUnit.SECONDS),
                "[HARD] CLI should receive RoomState");

            // Wait for greeting
            Thread.sleep(2000);

            // Send speech via InputHandler (same path as real CLI)
            inputHandler.handle("Hello, what is this place?");

            // Wait for agent response
            assertTrue(proseLatch.await(90, TimeUnit.SECONDS),
                "[HARD] CLI should receive agent prose response");

            assertFalse(proseMessages.isEmpty(),
                "[HARD] Should have received at least one prose message");

            // Soft: check response quality
            var lastProse = proseMessages.getLast();
            if (lastProse.text() == null || lastProse.text().length() < 20) {
                System.out.println("[E2E WARN] CLI.speech_response: response shorter than 20 chars"
                    + " (got " + (lastProse.text() == null ? "null" : lastProse.text().length()) + ")");
            }
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void cli_navigates_rooms() throws Exception {
        var output = new ByteArrayOutputStream();
        var printStream = new PrintStream(output);
        var roomStates = new CopyOnWriteArrayList<S2CMessage.RoomState>();
        var roomStateLatch = new CountDownLatch(1);

        var renderer = new Renderer(printStream, false);
        var inputHandler = new InputHandler(null, printStream);

        var connection = new Connection("localhost", server.port(),
            msg -> {
                renderer.render(msg);
                if (msg instanceof S2CMessage.RoomState rs) {
                    inputHandler.setCurrentRoomId(rs.room().roomId());
                    roomStates.add(rs);
                    roomStateLatch.countDown();
                }
            },
            state -> {});

        inputHandler.setConnection(connection);

        try {
            connection.connect();
            assertTrue(connection.awaitConnected(10_000),
                "[HARD] CLI should connect");

            assertTrue(roomStateLatch.await(10, TimeUnit.SECONDS),
                "[HARD] Should receive initial room state");

            assertEquals("nexus", roomStates.getFirst().room().roomId(),
                "[HARD] Should start in Nexus");

            // Go west via the InputHandler (same path as user typing "west").
            // Nexus → west → Terminal (east is The Docks); see TestServerBootstrap.foundationRoomSeeds.
            inputHandler.handle("west");

            // Wait for new room state
            Thread.sleep(3000);
            assertTrue(roomStates.size() >= 2,
                "[HARD] Should receive new room state after navigation");

            var secondRoom = roomStates.get(roomStates.size() - 1);
            assertEquals("terminal", secondRoom.room().roomId(),
                "[HARD] Should be in Terminal after going west");

            // Rendered output should show Terminal
            var rendered = output.toString();
            assertTrue(rendered.contains("Terminal"),
                "[HARD] Rendered output should show Terminal room");

            // Navigate back east (Terminal → east → Nexus)
            inputHandler.handle("east");
            Thread.sleep(3000);

            assertTrue(roomStates.size() >= 3,
                "[HARD] Should receive room state for return trip");

            var thirdRoom = roomStates.get(roomStates.size() - 1);
            assertEquals("nexus", thirdRoom.room().roomId(),
                "[HARD] Should be back in Nexus");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void cli_accessible_mode_renders_structured() throws Exception {
        var output = new ByteArrayOutputStream();
        var printStream = new PrintStream(output);
        var roomStateLatch = new CountDownLatch(1);

        // Accessible mode = true
        var renderer = new Renderer(printStream, true);

        var connection = new Connection("localhost", server.port(),
            msg -> {
                renderer.render(msg);
                if (msg instanceof S2CMessage.RoomState) {
                    roomStateLatch.countDown();
                }
            },
            state -> {});

        try {
            connection.connect();
            assertTrue(connection.awaitConnected(10_000),
                "[HARD] CLI should connect");

            assertTrue(roomStateLatch.await(10, TimeUnit.SECONDS),
                "[HARD] Should receive room state");

            var rendered = output.toString();

            // Accessible mode uses structured format
            assertTrue(rendered.contains("Room:"),
                "[HARD] Accessible mode should show 'Room:' prefix");
            assertTrue(rendered.contains("Description:"),
                "[HARD] Accessible mode should show 'Description:' prefix");
            assertTrue(rendered.contains("Exits:"),
                "[HARD] Accessible mode should show 'Exits:' prefix");

            // Should NOT contain ANSI codes
            assertFalse(rendered.contains("\033["),
                "[HARD] Accessible mode should not contain ANSI escape codes");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void cli_connection_lifecycle() throws Exception {
        var output = new ByteArrayOutputStream();
        var printStream = new PrintStream(output);
        var roomStateCount = new AtomicInteger(0);

        var renderer = new Renderer(printStream, false);

        var connection = new Connection("localhost", server.port(),
            msg -> {
                renderer.render(msg);
                if (msg instanceof S2CMessage.RoomState) {
                    roomStateCount.incrementAndGet();
                }
            },
            state -> {});

        try {
            connection.connect();
            assertTrue(connection.awaitConnected(10_000),
                "[HARD] CLI should connect initially");

            // Wait for room state
            Thread.sleep(2000);
            assertTrue(roomStateCount.get() >= 1,
                "[HARD] Should receive initial room state");

            assertEquals(Connection.State.CONNECTED, connection.getState(),
                "[HARD] Should be in CONNECTED state");
        } finally {
            connection.disconnect();
        }

        // Verify clean disconnect
        assertEquals(Connection.State.DISCONNECTED, connection.getState(),
            "[HARD] Should be DISCONNECTED after disconnect()");
    }
}
