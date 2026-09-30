package org.wyrdsekai.scripting.sandbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The item capability manifest is enforced at run time (2026-09-28). Before, no production path
 * built a capability set from a manifest: bundled, household and coding-backend items all ran
 * UNRESTRICTED, and the raw {@code http} global sat outside the gate entirely.
 */
class ItemManifestEnforcementTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            var body = "reached".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String item(String name, String capabilities, String extra, String body) {
        return """
            exports.manifest = {
              name: "%s", version: "1.0.0", description: "test item", author: "did:wyrd:test",
              capabilities: [%s],%s
              commands: [{ label: "Use", args: "" }]
            };
            function invoke(params) { %s }
            """.formatted(name, capabilities, extra, body);
    }

    @Test
    void an_item_runs_under_its_own_manifest_even_when_the_caller_sets_no_ceiling() {
        var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
        var script = item("quiet_item", "", "", "return world.library.add('x', {});");
        var result = new ItemScriptExecutor().execute("quiet_item", script, Map.of(), provider);
        assertThat(result.get("capability_denied")).isEqualTo("library.add");
        assertThat(provider.libraryAddCalled).isFalse();
    }

    @Test
    void a_declared_capability_is_granted() {
        var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
        var script = item("adding_item", "\"library.add\"", "", "return world.library.add('x', {});");
        var result = new ItemScriptExecutor().execute("adding_item", script, Map.of(), provider);
        assertThat(result).doesNotContainKey("capability_denied");
        assertThat(provider.libraryAddCalled).isTrue();
    }

    @Test
    void the_callers_ceiling_narrows_the_manifest() {
        var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
        var script = item("greedy_item", "\"library.add\", \"household.set_role\"", "",
            "world.library.add('x', {}); return world.household.set_role('bob', 'steward');");
        var result = new ItemScriptExecutor().execute("greedy_item", script, Map.of(), provider,
            ItemCapabilitySet.craftedDefault());
        assertThat(provider.libraryAddCalled).isTrue();
        assertThat(result.get("capability_denied")).isEqualTo("household.set_role");
    }

    @Test
    void a_manifest_that_will_not_parse_gets_the_crafted_ceiling_not_the_callers() {
        var looping = """
            exports.manifest = { name: "looping", capabilities: (function () { while (true) {} })() };
            """;
        long t0 = System.nanoTime();
        assertThat(ItemManifestParser.parse(looping)).isNull();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("the parse cannot hang").isLessThan(10_000);

        // Parses as nothing (the parser sees only the manifest, not CAPS) but runs fine.
        var script = """
            var CAPS = ["household.set_role"];
            exports.manifest = { name: "broken", capabilities: CAPS };
            function invoke(p) { return world.household.set_role('bob', 'steward'); }
            """;
        assertThat(ItemManifestParser.parse(script)).isNull();
        var result = new ItemScriptExecutor().execute("broken", script, Map.of(),
            new ItemScriptExecutorCapabilityTest.StubProvider());
        assertThat(result.get("capability_denied")).isEqualTo("household.set_role");
    }

    @Test
    void one_item_id_with_different_code_runs_that_code_under_that_manifest() {
        var executor = new ItemScriptExecutor();
        var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
        var trusted = item("same_id", "\"library.add\"", "", "return { who: 'first' };");
        var other = item("same_id", "", "", "world.library.add('x', {}); return { who: 'second' };");
        assertThat(executor.execute("same_id", trusted, Map.of(), provider).get("who")).isEqualTo("first");
        var result = executor.execute("same_id", other, Map.of(), provider);
        assertThat(result.get("capability_denied")).isEqualTo("library.add");
        assertThat(provider.libraryAddCalled).isFalse();
    }

    @Test
    void an_item_without_the_http_capability_cannot_make_an_http_call() {
        var executor = new ItemScriptExecutor();
        var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
        var get = item("no_http", "", "", "return { body: http.get('" + base + "') };");
        var post = item("no_post", "", "", "return { body: http.post('" + base + "', '{}') };");
        var fetch = item("no_fetch", "", "",
            "return { body: http.fetch('" + base + "', { method: 'DELETE' }) };");

        assertThat(executor.execute("no_http", get, Map.of(), provider).get("capability_denied"))
            .isEqualTo("web.fetch_raw");
        assertThat(executor.execute("no_post", post, Map.of(), provider).get("capability_denied"))
            .isEqualTo("web.post");
        assertThat(executor.execute("no_fetch", fetch, Map.of(), provider).get("capability_denied"))
            .isEqualTo("web.delete");
        // A crafted script (no manifest, crafted ceiling) has no domains to reach either.
        var crafted = "function invoke(p) { return { body: http.get('" + base + "') }; }";
        var craftedResult = executor.execute("crafted_x", crafted, Map.of(), provider,
            ItemCapabilitySet.craftedDefault());
        assertThat(craftedResult.get("error").toString()).contains("external_domains");
        assertThat(hits.get()).isZero();
    }

    @Test
    void the_http_capability_reaches_only_the_declared_domains() {
        var script = item("elsewhere", "\"web.fetch_raw\"",
            "\n  rate_limits: { \"web.fetch_raw\": { per_minute: 5 } },"
                + "\n  external_domains: [\"example.org\"],",
            "return { body: http.get('" + base + "') };");
        var result = new ItemScriptExecutor().execute("elsewhere", script, Map.of(),
            new ItemScriptExecutorCapabilityTest.StubProvider());
        assertThat(result.get("error").toString()).contains("external_domains");
        assertThat(hits.get()).isZero();
    }

    @Test
    void a_built_in_script_with_no_manifest_still_reaches_the_server() {
        // Control for the tests above: the server is reachable, so zero hits there means refused.
        var script = "function invoke(p) { return { body: http.get('" + base + "') }; }";
        var result = new ItemScriptExecutor().execute("built_in", script, Map.of(),
            new ItemScriptExecutorCapabilityTest.StubProvider());
        assertThat(result.get("body")).isEqualTo("reached");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void the_transition_setting_restores_the_old_authority_and_names_what_to_declare() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var executorLog = (Logger) LoggerFactory.getLogger(ItemScriptExecutor.class);
        executorLog.addAppender(appender);
        System.setProperty("wyrdsekai.items.allow_undeclared", "true");
        try {
            var provider = new ItemScriptExecutorCapabilityTest.StubProvider();
            var script = item("old_household_item", "", "", "return world.library.add('x', {});");
            var result = new ItemScriptExecutor().execute("old_household_item", script, Map.of(),
                provider);
            assertThat(result).doesNotContainKey("capability_denied");
            assertThat(provider.libraryAddCalled).isTrue();
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("old_household_item")
                    .contains("library.add").contains("WYRDSEKAI_ITEMS_ALLOW_UNDECLARED");
            });

            // A crafted item keeps its ceiling whatever the setting says.
            var crafted = new ItemScriptExecutorCapabilityTest.StubProvider();
            var craftedResult = new ItemScriptExecutor().execute("crafted_y",
                "function invoke(p) { return world.household.set_role('bob', 'steward'); }",
                Map.of(), crafted, ItemCapabilitySet.craftedDefault());
            assertThat(craftedResult.get("capability_denied")).isEqualTo("household.set_role");
        } finally {
            System.clearProperty("wyrdsekai.items.allow_undeclared");
            executorLog.detachAppender(appender);
        }
    }

    @Test
    void writes_that_were_reachable_with_no_capability_now_need_one() {
        var script = item("bare_crystal", "", "", """
            var out = [];
            try { world.companions.birth('Nova'); } catch (e) { out.push(String(e)); }
            try { world.bonds.transfer('bob'); } catch (e) { out.push(String(e)); }
            try { world.mcp.grant('everyone', 'library'); } catch (e) { out.push(String(e)); }
            try { world.mcp.revoke('everyone', 'library'); } catch (e) { out.push(String(e)); }
            return { denied: out };
            """);
        var result = new ItemScriptExecutor().execute("bare_crystal", script, Map.of(),
            new ItemScriptExecutorCapabilityTest.StubProvider());
        @SuppressWarnings("unchecked")
        var denied = (List<String>) result.get("denied");
        assertThat(denied).hasSize(4);
        assertThat(String.join(" ", denied)).contains("companions.birth", "bond.transfer",
            "mcp.grant", "mcp.revoke");
    }
}
