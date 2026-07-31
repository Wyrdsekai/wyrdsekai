package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpServiceRegistry;
import org.wyrdsekai.core.room.RoomMcpBridge;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.study.StudyMountRegistry;
import org.wyrdsekai.core.study.StudySkillService;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STUDY SHELL/MOUNT live E2E (W1, task #23) — the whole
 * newly built "skill" MCP surface, end to end through the real study room
 * script over a real WebSocket session:
 *
 * <ol>
 *   <li><b>fs_mount command path</b> — "mount &lt;dir&gt; as docs" spoken in the
 *       Study → study.js emits {@code command:fs_mount} → RoomActor routes it
 *       to {@link org.wyrdsekai.core.study.StudyShellBridge} → the persisted
 *       {@link StudyMountRegistry} carries the shelf (this exact leg was
 *       dropped-on-the-floor theater before W1).</li>
 *   <li><b>study.fs.list / study.fs.read</b> — both straight through
 *       {@link McpGatewayService#execute} (the service registration contract)
 *       and in-room through the desk shell ({@code desk:ls} / {@code desk:cat}
 *       → {@code world.mcp("skill", …)} via the newly installed
 *       {@link RoomMcpBridge}).</li>
 *   <li><b>take</b> — {@code desk:take docs/&lt;file&gt;} imports the file into the
 *       acting entity's inventory; the row (with content) is asserted straight
 *       in the household database, not from the narration.</li>
 * </ol>
 *
 * <p>No LLM needed — WireMock serves inference; every assertion is mechanism.</p>
 */
@Tag("tier2")
class StudyShellLiveE2ETest {

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(15);
    private static final String FILE_CONTENT =
        "The manor keeps its own counsel.\nShelf-file for the take test — line two.\n";

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;
    private static McpGatewayService gateway;
    private static StudyMountRegistry mountRegistry;
    private static Path mountedDir;

    @BeforeAll
    static void setUp() throws Exception {
        // ── The W1 wiring Main.java does at startup, replicated for the test
        // bootstrap (TestServerBootstrap predates the gateway): a fresh
        // persisted mount table, the local "skill" service registered on a
        // real gateway, and the gateway bridged to room scripts' world.mcp().
        // Must happen BEFORE server.start() — RoomScriptEngine snapshots
        // RoomMcpBridge.get() when each room actor is created.
        mountRegistry = new StudyMountRegistry(
            Files.createTempFile("study-mounts-e2e-", ".json"));
        StudyMountRegistry.install(mountRegistry);
        gateway = new McpGatewayService(new McpServiceRegistry(),
            (endpoint, tool, params, auth) -> {
                throw new IllegalStateException(
                    "no remote MCP transport in this test — 'skill' must route in-process");
            });
        StudySkillService.register(gateway, mountRegistry);
        RoomMcpBridge.install(gateway);

        // ── The directory the steward will mount as a shelf ──
        mountedDir = Files.createTempDirectory("study-shelf-e2e-");
        Files.writeString(mountedDir.resolve("notes.txt"), FILE_CONTENT);
        Files.writeString(mountedDir.resolve("ledger.md"), "# Ledger\nnothing owed\n");

        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome to The Study.", 20, 15);
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null);

        var studySeed = new ZoneGuardian.RoomSeed("study", "The Study",
            "Your private quarters. A heavy desk stands against one wall; "
            + "document shelves line the opposite wall.",
            List.of(new Exit("west", "nexus", "The Nexus")),
            List.of(
                new RoomObject("study-desk", "heavy desk",
                    "A desk with a schedule board", false),
                new RoomObject("study-shelves", "mounted shelves",
                    "Document shelves along one wall", false)));

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of(studySeed));
        server.start();

        // StudyShellBridge resolves the inventory database from this property
        // first (production reads WyrdConfig; the test server's DB is a temp file).
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("wyrdsekai.jdbc.url");
        RoomMcpBridge.install(null);
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    private TestWebSocketClient enterStudy() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));
        ws.sendGo("nexus", "in");
        var studyState = ws.waitForRoomState(Duration.ofSeconds(10));
        assertNotNull(studyState, "[HARD] Should receive Study room state");
        assertEquals("study", studyState.path("room").path("roomId").asText(),
            "[HARD] Should be in The Study");
        return ws;
    }

    private void drainProse(TestWebSocketClient ws) {
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.drainMessages();
    }

    /** Say something in the Study and return the next narrator prose text. */
    private String sayAndAwaitNarrator(TestWebSocketClient ws, String text) {
        ws.sendSay("study", text);
        var prose = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
        assertNotNull(prose, "[HARD] narrator should answer '" + text + "'");
        return prose.path("text").asText();
    }

    @Test
    void mount_list_read_take_full_shelf_flow() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            // ── 1. fs_mount command path: script emission → RoomActor →
            //       StudyShellBridge → persisted host-side registry ──
            var mountReply = sayAndAwaitNarrator(ws,
                "mount " + mountedDir + " as docs");
            assertTrue(mountReply.contains("Shelf 'docs' now holds"),
                "[HARD] host-side bridge must confirm the mount (was theater before W1); got: "
                + mountReply);
            assertEquals(mountedDir.toRealPath().toString(),
                Path.of(mountRegistry.mountsFor("study").get("docs")).toRealPath().toString(),
                "[HARD] fs_mount must land in the persisted StudyMountRegistry");

            // ── 2a. study.fs.list / study.fs.read through the gateway's own
            //        execute() contract (what world.mcp resolves to) ──
            var list = gateway.execute("agent-e2e", "zone-e2e", "skill",
                "study.fs.list", Map.of("path", "docs", "_room", "study"));
            assertTrue(list.success(),
                "[HARD] study.fs.list must succeed; error: " + list.error());
            assertTrue(list.data().contains("notes.txt"),
                "[HARD] listing must show the shelf file; got: " + list.data());

            var read = gateway.execute("agent-e2e", "zone-e2e", "skill",
                "study.fs.read", Map.of("path", "docs/notes.txt", "_room", "study"));
            assertTrue(read.success(),
                "[HARD] study.fs.read must succeed; error: " + read.error());
            assertEquals(FILE_CONTENT, read.data(),
                "[HARD] study.fs.read must return the exact file content");

            // Sandbox: escaping the shelf must be refused with a teaching error.
            var escape = gateway.execute("agent-e2e", "zone-e2e", "skill",
                "study.fs.read", Map.of("path", "/etc/passwd", "_room", "study"));
            assertFalse(escape.success(),
                "[HARD] absolute host paths must be refused by the shelf sandbox");

            // ── 3. take → inventory item with content ──
            var takeReply = sayAndAwaitNarrator(ws, "desk:take docs/notes.txt");
            assertTrue(takeReply.contains("Taken: notes.txt"),
                "[HARD] take must confirm the import (bridge verifies the row before narrating); got: "
                + takeReply);

            // Mechanism assertion: the row exists in the household database and
            // carries the full file content (content rides in `description`).
            try (var conn = DriverManager.getConnection(server.jdbcUrl());
                 var stmt = conn.prepareStatement(
                     "SELECT entity_id, object_name, description FROM inventory WHERE object_id = ?")) {
                stmt.setString(1, "file-docs-notes.txt");
                try (var rs = stmt.executeQuery()) {
                    assertTrue(rs.next(),
                        "[HARD] taken file must exist as an inventory row (object_id=file-docs-notes.txt)");
                    assertEquals("notes.txt", rs.getString("object_name"));
                    assertEquals(FILE_CONTENT, rs.getString("description"),
                        "[HARD] imported inventory item must carry the file content");
                    var owner = rs.getString("entity_id");
                    assertNotNull(owner);
                    assertFalse(owner.isBlank(),
                        "[HARD] the row must belong to the acting entity");
                }
            }
        }
    }

    /**
     * KNOWN-RED (wiring gap found by this suite, 2026-07-11): the desk shell's
     * {@code ls}/{@code cat} reach the gateway (the "Local MCP call … study.fs.list"
     * debug line fires and succeeds), but the result envelope comes back to the
     * script as a {@code java.util.Map} host object — and the ROOM sandbox
     * ({@code ScriptSandbox.createContext}) builds {@code HostAccess.EXPLICIT}
     * WITHOUT {@code allowMapAccess(true)}/{@code allowListAccess(true)}, unlike
     * {@code ItemScriptExecutor.createContext} which sets both. So
     * {@code result.success} is {@code undefined} in study.js, every branch
     * falls to its not-found fallback, and the narration is "Not found: docs"
     * even though the listing succeeded host-side.
     *
     * <p>Fix belongs in {@code scripting/src/main/java/org/wyrdsekai/scripting/
     * sandbox/ScriptSandbox.java} (mirror the item executor's host-access
     * flags, or return a ProxyObject from WorldApi.mcp). This test stays red
     * until the world.mcp RESULT leg is readable in-room — the offering leg
     * (gateway reachable, service resolving) is proven green above.</p>
     */
    @Test
    void desk_shell_ls_and_cat_render_shelf_content_in_room() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);
            sayAndAwaitNarrator(ws, "mount " + mountedDir + " as docs");

            var lsReply = sayAndAwaitNarrator(ws, "desk:ls docs");
            assertTrue(lsReply.contains("notes.txt"),
                "[HARD] desk:ls must render the mounted shelf listing through world.mcp "
                + "(room-sandbox host-access gap — see javadoc); got: " + lsReply);

            var catReply = sayAndAwaitNarrator(ws, "desk:cat docs/notes.txt");
            assertTrue(catReply.contains("The manor keeps its own counsel."),
                "[HARD] desk:cat must render the file content through world.mcp "
                + "(room-sandbox host-access gap — see javadoc); got: " + catReply);
        }
    }

    @Test
    void unmount_removes_shelf_host_side() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            var tempDir = Files.createTempDirectory("study-shelf-unmount-");
            Files.writeString(tempDir.resolve("a.txt"), "a");
            sayAndAwaitNarrator(ws, "mount " + tempDir + " as scratch");
            assertTrue(mountRegistry.mountsFor("study").containsKey("scratch"),
                "[HARD] mount must land before unmount is tested");

            var unmountReply = sayAndAwaitNarrator(ws, "unmount scratch");
            assertFalse(mountRegistry.mountsFor("study").containsKey("scratch"),
                "[HARD] fs_unmount must remove the shelf from the host-side registry; narration: "
                + unmountReply);

            // Reading through the gone shelf must now refuse with a teaching error.
            var read = gateway.execute("agent-e2e", "zone-e2e", "skill",
                "study.fs.read", Map.of("path", "scratch/a.txt", "_room", "study"));
            assertFalse(read.success(),
                "[HARD] reads through an unmounted shelf must be refused");
        }
    }
}
