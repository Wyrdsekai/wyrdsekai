package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.LibraryProvider;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inbound library door: JSON in, an MCP content block out, protocol errors as MCP
 * error results with their stable code — and no world tools on the same registry.
 */
class LibraryToolsTest {

    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path dir;
    private WyrdLuceneStore store;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(dir, 384);
        store.ensureAllCollections();
        store.insertKnowledge("wiki:1", "simple-wikipedia", "Obsidian",
            "Obsidian is a naturally occurring volcanic glass.", "simple-wikipedia", "geology", null);
        store.insertKnowledge("shelf:1", "study-share-books", "A private page",
            "Something from the steward's own shelf.", "study-share-books", "fiction", null);
        store.commitAll();
        registry = new McpToolRegistry(false);
        LibraryTools.register(registry, new LibraryProvider(store, "zone-1", "Test library", (String) null,
            pack -> "simple-wikipedia".equals(pack)));
    }

    @AfterEach void tearDown() throws Exception { store.close(); }

    @Test
    void only_the_library_tools_are_on_the_door() {
        var names = registry.listTools().stream().map(McpToolRegistry.ToolDef::name).toList();
        assertTrue(names.containsAll(LibraryProvider.TOOLS));
        assertEquals(LibraryProvider.TOOLS.size(), names.size(), "no room.* / world.* tools on the library door: " + names);
    }

    @Test
    void ask_returns_a_content_block_carrying_the_package() throws Exception {
        var res = registry.call("library_ask", M.readTree("{\"question\":\"obsidian\",\"k\":3}"));
        assertFalse(res.path("isError").asBoolean(false));
        var text = res.path("content").get(0).path("text").asText();
        var pkg = M.readTree(text);
        assertEquals("zone-1", pkg.path("library_id").asText());
        assertEquals("1.0", pkg.path("contract").asText());
        assertEquals("wiki:1", pkg.path("entries").get(0).path("id").asText());

        var nothing = M.readTree(registry.call("library_ask", M.readTree("{\"question\":\"private page steward\"}"))
            .path("content").get(0).path("text").asText());
        assertTrue(nothing.path("holds_nothing").asBoolean(), "the private shelf never answers outside");
    }

    @Test
    void protocol_errors_are_mcp_errors_with_the_stable_code() throws Exception {
        var res = registry.call("library_read", M.readTree("{\"locator\":\"shelf:1\"}"));
        assertTrue(res.path("isError").asBoolean());
        assertEquals("forbidden", res.path("data").path("code").asText());
        var bad = registry.call("library_search", M.readTree("{}"));
        assertEquals("invalid_args", bad.path("data").path("code").asText());
        var none = registry.call("library_submit", M.readTree("{\"claim\":\"x\",\"sources\":[]}"));
        assertEquals("unavailable", none.path("data").path("code").asText(), "no owner: this library takes no submissions");
    }
}
