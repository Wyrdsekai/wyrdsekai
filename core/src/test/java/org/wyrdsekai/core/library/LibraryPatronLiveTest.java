package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The patron client against a REAL librarian over stdio — the live half of the conformance
 * harness. Enabled by {@code WYRDSEKAI_LIBRARY_LIVE_CMD} (the command that starts the MCP
 * server, e.g. {@code /path/to/codezaiku mcp}); {@code WYRDSEKAI_LIBRARY_LIVE_DID} is the
 * patron identity to present (default a throwaway). Read-only except one clearly labelled
 * draft submission, which the library's own review is free to retire.
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIBRARY_LIVE_CMD", matches = ".+")
class LibraryPatronLiveTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static Process proc;
    private static BufferedWriter in;
    private static BufferedReader out;
    private static final AtomicInteger SEQ = new AtomicInteger(10);
    private static final Object STDIO_LOCK = new Object();
    private static LibraryPatron patron;
    private static String did;

    /** MCP over stdio, newline-delimited JSON-RPC, as the reference librarian speaks it. */
    static final LibraryPatron.Transport STDIO = (tool, args) -> {
        var req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        int id = SEQ.incrementAndGet();
        req.put("id", id);
        req.put("method", "tools/call");
        var params = req.putObject("params");
        params.put("name", tool);
        params.set("arguments", M.valueToTree(args));
        synchronized (STDIO_LOCK) {
            in.write(M.writeValueAsString(req));
            in.write('\n');
            in.flush();
            String line;
            while ((line = out.readLine()) != null) {
                if (line.isBlank()) continue;
                var node = M.readTree(line);
                if (node.path("id").asInt(-1) != id) continue;
                if (node.has("error")) {
                    var err = node.get("error");
                    throw new LibraryProvider.ProtocolError(
                        err.path("data").path("code").asText("unavailable"), err.path("message").asText());
                }
                var res = node.path("result");
                if (res.has("structuredContent")) return M.writeValueAsString(res.get("structuredContent"));
                var content = res.path("content");
                return content.isArray() && content.size() > 0 ? content.get(0).path("text").asText() : "";
            }
            throw new IllegalStateException("librarian closed the stream");
        }
    };

    @BeforeAll
    static void start() throws Exception {
        var cmd = System.getenv("WYRDSEKAI_LIBRARY_LIVE_CMD").trim().split("\\s+");
        did = System.getenv().getOrDefault("WYRDSEKAI_LIBRARY_LIVE_DID", "did:key:wyrdsekai-live-test");
        proc = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"wyrdsekai-conformance\",\"version\":\"0\"}}}\n");
        in.flush();
        var init = M.readTree(out.readLine());
        assertTrue(init.path("result").path("capabilities").has("tools"), init.toString());
        patron = new LibraryPatron(STDIO, new LibraryPatron.Patron(did, "Wyrdsekai conformance", "wyrdsekai"));
    }

    @AfterAll
    static void stop() {
        if (proc != null) proc.destroy();
    }

    @Test
    void status_pins_a_compatible_contract_and_names_the_library() {
        var st = patron.status().orElseThrow(() -> new AssertionError("status refused or unreachable"));
        assertTrue(st.libraryId().startsWith("lib_"), st.libraryId());
        assertTrue(st.compatible(), st.contract());
        assertNotNull(st.libraryName());
    }

    @Test
    void ask_returns_cited_entries_or_an_honest_nothing() {
        var pkg = patron.ask("what does direct translation do to keigo register", 4).orElseThrow();
        assertNotNull(pkg.libraryId());
        if (!pkg.holdsNothing()) {
            var e = pkg.entries().getFirst();
            assertNotNull(e.id());
            assertNotNull(e.kind());
            assertNotNull(e.state());
            assertFalse(e.sources().isEmpty(), "an entry travels with its sources");
            assertTrue(e.sources().stream().noneMatch(s -> "n/a".equalsIgnoreCase(s.edition())), "no n/a editions on the wire");
        }
        var nothing = patron.ask("zebra crossings on the moon in 1740", 3).orElseThrow();
        assertTrue(nothing.holdsNothing(), "a question the shelf cannot bear on must come back as nothing");
        assertTrue(nothing.entries().isEmpty());
    }

    @Test
    void search_pages_get_reads_and_the_verbatim_path_works() {
        var sr = patron.search("retrieval", 2, null, null).orElseThrow();
        assertFalse(sr.hits().isEmpty());
        var h = sr.hits().getFirst();
        assertNotNull(h.id()); assertNotNull(h.kind()); assertNotNull(h.state());
        if (sr.nextCursor() != null) {
            var page2 = patron.search("retrieval", 2, null, sr.nextCursor()).orElseThrow();
            assertTrue(page2.hits().stream().noneMatch(x -> x.id().equals(h.id())), "page 2 does not repeat page 1");
        }
        var full = patron.get(h.id()).orElseThrow();
        assertEquals(h.id(), full.id());
        var withRaw = full.sources().stream().filter(s -> s.locator() != null).findFirst();
        withRaw.ifPresent(s -> {
            var raw = patron.read(s.locator(), 500);
            raw.ifPresent(r -> assertTrue(r.text().length() <= 500));
        });
    }

    @Test
    void recall_notices_page_from_a_cursor() {
        var ch = patron.changes("0", 50).orElseThrow();
        assertNotNull(ch.nextCursor());
        for (var c : ch.changes()) { assertNotNull(c.id()); assertNotNull(c.event()); }
    }

    @Test
    void established_distinguishes_held_from_absent_and_errors_carry_codes() {
        var est = patron.established("direct translation flattens register").orElseThrow();
        assertTrue(List.of("established", "disputed", "not_established").contains(est.verdict()));
        assertNotNull(est.unreviewed());

        var nf = assertThrows(LibraryProvider.ProtocolError.class,
            () -> STDIO.call("library_get", Map.of("id", "F-9999-does-not-exist")));
        assertEquals("not_found", nf.code);
        var bad = assertThrows(LibraryProvider.ProtocolError.class,
            () -> STDIO.call("library_search", Map.of("query", "")));
        assertEquals("invalid_args", bad.code);
        var ns = assertThrows(LibraryProvider.ProtocolError.class,
            () -> STDIO.call("library_submit", Map.of("claim", "an unsourced claim about nothing", "sources", List.of(),
                "patron", Map.of("did", did, "runtime", "wyrdsekai"))));
        assertEquals("no_sources", ns.code);
    }

    /** Writes into the other library — only when explicitly asked, so reruns don't litter it. */
    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIBRARY_LIVE_WRITE", matches = "1|true")
    void a_sourced_submission_enters_as_a_draft_attributed_to_the_patron() {
        var sub = patron.submit(
            "Wyrdsekai conformance check: a patron submission enters the librarian's review as a draft. (safe to retire)",
            "extraction", List.of(new LibraryPatron.Source("https://example.invalid/wyrdsekai-conformance", null)), "low");
        if (sub.isEmpty()) return;   // read-only patron: forbidden is a valid outcome for an unlisted did
        assertEquals("draft", sub.get().state());
        var back = patron.get(sub.get().id()).orElseThrow();
        assertEquals("draft", back.state());
        assertEquals("patron:" + did, back.writer());
    }
}
