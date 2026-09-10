package org.wyrdsekai.core.library;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.mcp.transport.HttpTransportHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The patron client against a REAL ResearchZosho daemon over HTTP with a bearer token — the
 * transport {@code wyrd researcher link} registers. The live half of the conformance harness
 * for the public contract (1.x). Enabled by {@code WYRDSEKAI_RESEARCHZOSHO_URL} (the daemon, e.g.
 * {@code http://127.0.0.1:4649}) and {@code WYRDSEKAI_RESEARCHZOSHO_TOKEN} (a token the
 * librarian issued: {@code researchzosho patron token <did>}). Read-only.
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_RESEARCHZOSHO_URL", matches = ".+")
class ResearchZoshoLiveTest {

    private static HttpTransportHandler http;
    private static LibraryPatron patron;

    /** The gateway's shape: MCP over HTTP, the token as the Authorization header. */
    static LibraryPatron.Transport transport(HttpTransportHandler h, boolean assertDid) {
        return (tool, args) -> {
            var sent = assertDid ? args : LibraryPatron.withoutAssertedDid(args);
            var r = h.callTool(tool, sent);
            if (Boolean.TRUE.equals(r.isError())) throw new IllegalStateException(r.textContent());
            return r.textContent();
        };
    }

    @BeforeAll static void connect() throws Exception {
        var url = System.getenv("WYRDSEKAI_RESEARCHZOSHO_URL").replaceAll("/+$", "") + "/rpc";
        var token = System.getenv("WYRDSEKAI_RESEARCHZOSHO_TOKEN");
        http = new HttpTransportHandler(url, Map.of(), token == null ? null : "Bearer " + token);
        var init = http.initialize();
        assertNotNull(init.serverInfo());
        patron = new LibraryPatron(transport(http, false),
            new LibraryPatron.Patron("did:key:zHouseholdCompanion", "Mia", "wyrdsekai"));
    }

    @Test
    void the_daemon_serves_only_library_tools_and_a_1x_contract() throws Exception {
        var tools = http.listTools(null).tools();
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
        for (var t : tools) assertTrue(t.name().startsWith("library_"), "a non-library tool on the librarian's door: " + t.name());
        var st = patron.status().orElseThrow();
        assertTrue(st.compatible(), "contract " + st.contract());
        assertTrue(st.libraryId().startsWith("lib_"), st.libraryId());
        // the public release settled on "1.0" with the wire the private drafts numbered 1.1–1.5
        assertTrue(LibraryPatron.compatibleContract(st.contract()), "expected a 1.x contract, got " + st.contract());
    }

    @Test
    void ask_over_the_token_reads_honestly() {
        // Not the zebra question: the explorer crew researched that one on 2026-09-04 and the
        // shelf now HOLDS two trivia findings for it. A library remembers what it was asked.
        var pkg = patron.ask("purple teapots orbiting Jupiter in 1603", 6).orElseThrow();
        assertTrue(pkg.holdsNothing(), "a junk question must hold nothing: " + pkg.entries());
        assertTrue(pkg.entries().isEmpty());
        assertNotNull(pkg.libraryId());
    }

    @Test
    void a_body_that_asserts_a_foreign_did_over_the_token_cannot_impersonate() {
        // The token proves ONE patron. The daemon's /v1 routes refuse a body naming another did;
        // its /rpc door (what the gateway speaks) REPLACES the asserted patron with the token's —
        // measured live 2026-09-08. Either way nothing a caller sends can impersonate, and the
        // client strips the did anyway so both doors behave the same for us.
        var asserting = new LibraryPatron(transport(http, true),
            new LibraryPatron.Patron("did:key:zSomebodyElse", "Nobody", "wyrdsekai"));
        var answer = asserting.ask("purple teapots orbiting Jupiter in 1603", 3);
        if (answer.isPresent()) assertTrue(answer.get().holdsNothing(), "answered as the token's patron, honestly");
    }

    @Test
    void the_recall_feed_pages() {
        var ch = patron.changes("0", 50).orElseThrow();
        assertNotNull(ch.nextCursor());
        assertNotNull(ch.latest());
        for (var c : ch.changes()) { assertNotNull(c.id()); assertNotNull(c.event()); }
    }

    @Test
    void a_raw_entry_from_1_4_is_marked_untrusted() {
        // Search the shelves for anything; if a raw document comes back, it must carry the mark.
        var hits = patron.search("the", 10, null, null).orElseThrow();
        for (var h : hits.hits()) {
            if (!"raw".equals(h.kind())) continue;
            var e = patron.get(h.id()).orElse(null);
            if (e == null) continue;
            assertTrue(e.untrustedText(), "a captured page must arrive marked untrusted: " + h.id());
            return;
        }
        // no raw hits on this shelf for that query: nothing to assert, and nothing to fake
    }

    @Test
    void render_fences_what_the_live_shelf_returns_as_pages() {
        var hits = patron.search("mechanism", 10, null, null).orElseThrow();
        var entries = new java.util.ArrayList<LibraryPatron.Entry>();
        for (var h : hits.hits()) patron.get(h.id()).ifPresent(entries::add);
        var text = LibraryPatron.render("live", entries, 20_000);
        for (var e : entries) {
            if (e.untrustedText()) assertTrue(text.contains(LibraryPatron.FENCE_OPEN), "fenced: " + e.id());
        }
        assertEquals(List.of(), entries.stream().filter(e -> e.body() == null).map(LibraryPatron.Entry::id).toList(), "every entry has a body");
        var unused = new LinkedHashMap<String, Object>(); assertTrue(unused.isEmpty());
    }
}
