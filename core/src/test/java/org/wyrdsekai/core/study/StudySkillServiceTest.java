package org.wyrdsekai.core.study;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpResult;
import org.wyrdsekai.core.mcp.McpServiceRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W1 — the Study's local "skill" MCP service. Calls run wire-level through
 * McpGatewayService.execute("skill", ...) exactly as world.mcp() does, so
 * these tests cover the local-service gateway path too.
 */
class StudySkillServiceTest {

    private static final String ROOM = "study-1";

    @TempDir Path tmp;

    private McpGatewayService gateway;
    private StudyMountRegistry registry;
    private Path shelfDir;

    @BeforeEach
    void setUp() throws Exception {
        shelfDir = Files.createDirectories(tmp.resolve("shelf"));
        Files.writeString(shelfDir.resolve("notes.txt"), "hello wyrd\nsecond line\n");
        Files.createDirectories(shelfDir.resolve("sub"));
        Files.writeString(shelfDir.resolve("sub").resolve("deep.txt"), "buried treasure\n");
        Files.write(shelfDir.resolve("scan.pdf"), new byte[] {0x25, 0x50, 0x44, 0x46});
        Files.write(shelfDir.resolve("blob.dat"), new byte[] {0x00, 0x01, 0x02, 0x03});

        registry = new StudyMountRegistry(tmp.resolve("mounts.json"));
        registry.mount(ROOM, "shelf", shelfDir.toString());

        gateway = new McpGatewayService(new McpServiceRegistry(),
            (endpoint, toolName, params, authHeader) -> {
                throw new UnsupportedOperationException("no remote transport in this test");
            });
        StudySkillService.register(gateway, registry);
    }

    private McpResult call(String tool, Map<String, Object> params) {
        var withRoom = new HashMap<>(params);
        withRoom.putIfAbsent("_room", ROOM);
        return gateway.execute("agent-1", "zone-1", "skill", tool, withRoom);
    }

    // ─── wiring ──────────────────────────────────────────────────────

    @Test
    void unknown_service_still_errors_honestly() {
        var result = gateway.execute("agent-1", "zone-1", "nope", "tool", Map.of());
        assertFalse(result.success());
        assertTrue(result.error().contains("Unknown service: nope"));
    }

    @Test
    void skill_service_is_available_after_registration() {
        assertTrue(gateway.isAvailable("skill"));
    }

    @Test
    void unknown_tool_lists_the_real_tools() {
        var result = call("study.fs.write", Map.of("path", "shelf/x"));
        assertFalse(result.success());
        assertTrue(result.error().contains("study.fs.read"), result.error());
        assertTrue(result.error().contains("vault.doc.extract"), result.error());
    }

    @Test
    void missing_room_param_teaches() {
        var result = gateway.execute("agent-1", "zone-1", "skill",
            "study.fs.read", Map.of("path", "shelf/notes.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("room of origin"), result.error());
    }

    // ─── study.fs.read ───────────────────────────────────────────────

    @Test
    void read_returns_file_content() {
        var result = call("study.fs.read", Map.of("path", "shelf/notes.txt"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("hello wyrd"));
    }

    @Test
    void read_missing_file_teaches_ls() {
        var result = call("study.fs.read", Map.of("path", "shelf/ghost.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("ls shelf"), result.error());
    }

    @Test
    void read_refuses_parent_traversal() throws Exception {
        Files.writeString(tmp.resolve("secret.txt"), "outside");
        var result = call("study.fs.read", Map.of("path", "shelf/../secret.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains(".."), result.error());
    }

    @Test
    void read_refuses_absolute_and_home_paths_with_mount_hint() {
        var absolute = call("study.fs.read", Map.of("path", "/etc/passwd"));
        assertFalse(absolute.success());
        assertTrue(absolute.error().contains("mount"), absolute.error());

        var home = call("study.fs.read", Map.of("path", "~/.wyrdsekai/study-extensions.js"));
        assertFalse(home.success());
        assertTrue(home.error().contains("mount"), home.error());
    }

    @Test
    void read_of_unmounted_shelf_names_the_mounted_ones() {
        var result = call("study.fs.read", Map.of("path", "ghost/notes.txt"));
        assertFalse(result.success());
        assertTrue(result.error().contains("shelf"), result.error());
    }

    // ─── study.fs.list ───────────────────────────────────────────────

    @Test
    void list_empty_path_lists_shelves() {
        var result = call("study.fs.list", Map.of("path", ""));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("shelf/"), result.data());
    }

    @Test
    void list_shelf_shows_files_and_dirs() {
        var result = call("study.fs.list", Map.of("path", "shelf"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("notes.txt"), result.data());
        assertTrue(result.data().contains("sub/"), result.data());
    }

    @Test
    void list_missing_dir_teaches() {
        var result = call("study.fs.list", Map.of("path", "shelf/ghost"));
        assertFalse(result.success());
        assertTrue(result.error().contains("Nothing at"), result.error());
    }

    @Test
    void list_with_no_mounts_says_how_to_mount() {
        var result = gateway.execute("agent-1", "zone-1", "skill",
            "study.fs.list", Map.of("path", "", "_room", "empty-study"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("mount"), result.data());
    }

    // ─── study.fs.search ─────────────────────────────────────────────

    @Test
    void search_by_name_finds_nested_files() {
        var result = call("study.fs.search", Map.of("query", "deep"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("shelf/sub/deep.txt"), result.data());
    }

    @Test
    void search_by_content_reports_file_and_line() {
        var result = call("study.fs.search", Map.of("query", "treasure", "type", "content"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("shelf/sub/deep.txt:1"), result.data());
        assertTrue(result.data().contains("buried treasure"), result.data());
    }

    @Test
    void grep_style_pattern_plus_path_in_one_query() {
        var result = call("study.fs.search", Map.of("query", "wyrd shelf", "type", "content"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("notes.txt"), result.data());
    }

    @Test
    void search_no_match_is_an_honest_no() {
        var result = call("study.fs.search", Map.of("query", "unfindable-xyzzy"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("No files matching"), result.data());
    }

    // ─── study.fs.mounts ─────────────────────────────────────────────

    @Test
    void mounts_returns_json_table() {
        var result = call("study.fs.mounts", Map.of());
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("\"shelf\""), result.data());
        assertTrue(result.data().contains(shelfDir.toString()), result.data());
    }

    // ─── vault.doc.extract ───────────────────────────────────────────

    @Test
    void extract_text_file_succeeds() {
        var result = call("vault.doc.extract", Map.of("itemPath", "shelf/notes.txt"));
        assertTrue(result.success(), result.error());
        assertTrue(result.data().contains("hello wyrd"));
    }

    @Test
    void extract_pdf_is_honestly_unsupported() {
        var result = call("vault.doc.extract", Map.of("itemPath", "shelf/scan.pdf"));
        assertFalse(result.success());
        assertTrue(result.error().contains(".pdf"), result.error());
        assertTrue(result.error().contains("isn't supported yet"), result.error());
    }

    @Test
    void extract_binary_blob_is_refused() {
        var result = call("vault.doc.extract", Map.of("itemPath", "shelf/blob.dat"));
        assertFalse(result.success());
        assertTrue(result.error().contains("binary"), result.error());
    }
}
