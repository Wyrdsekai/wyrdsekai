package org.wyrdsekai.scripting.sandbox;

import com.sun.net.httpserver.HttpServer;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Workbench Runtime sandbox system.
 * Tests the FULL PIPELINE — real GraalJS execution with real Java API bindings.
 * No mocking. Every test builds a real sandbox context and executes real scripts.
 *
 * <p>Tests that require core module types (ActionParser, WorkbenchValidator,
 * AgentPermissions) live in {@code core/src/test/.../WorkbenchPipelineIntegrationTest.java}
 * since the scripting module does not depend on core.
 */
class WorkbenchIntegrationTest {

    /** Embedded HTTP server for tests that need a real HTTP endpoint. */
    private static HttpServer httpServer;
    private static int httpPort;

    @TempDir
    Path workspace;

    @BeforeAll
    static void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpPort = httpServer.getAddress().getPort();

        // GET /data — returns a plain text body
        httpServer.createContext("/data", exchange -> {
            var body = "hello from test server";
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });

        // GET /json-array — returns a JSON array of records
        httpServer.createContext("/json-array", exchange -> {
            var json = """
                [
                  {"name": "Alice", "score": 95},
                  {"name": "Bob", "score": 72},
                  {"name": "Charlie", "score": 88},
                  {"name": "Diana", "score": 61},
                  {"name": "Eve", "score": 90}
                ]""";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            var bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        httpServer.setExecutor(null);
        httpServer.start();
    }

    @AfterAll
    static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // Test 1: graaljs_script_uses_http_and_crypto_together
    // -----------------------------------------------------------------------

    @Test
    void graaljs_script_uses_http_and_crypto_together() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_BASIC, null)) {
            // Script fetches from the test server and hashes the response
            var script = """
                var body = http.get('http://localhost:%d/data');
                var hash = crypto.sha256(body);
                hash;
                """.formatted(httpPort);

            Value result = ctx.eval("js", script);

            // Compute the expected hash independently in Java
            String expectedBody = "hello from test server";
            String expectedHash = sha256(expectedBody);

            assertThat(result.asString()).isEqualTo(expectedHash);
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: graaljs_script_uses_db_and_fs_together
    // -----------------------------------------------------------------------

    @Test
    void graaljs_script_uses_db_and_fs_together() throws Exception {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var script = """
                var db = Database('test.db');
                db.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, value INTEGER)");
                db.execute("INSERT INTO items (name, value) VALUES (?, ?)", "sword", 100);
                db.execute("INSERT INTO items (name, value) VALUES (?, ?)", "shield", 75);

                var rows = db.query("SELECT name, value FROM items ORDER BY name");
                var result = '';
                for (var i = 0; i < rows.length; i++) {
                    result += rows[i].name + ':' + rows[i].value + '\\n';
                }
                db.close();

                fs.write('result.txt', result);
                var readBack = fs.read('result.txt');
                readBack;
                """;

            Value result = ctx.eval("js", script);

            assertThat(result.asString()).isEqualTo("shield:75\nsword:100\n");

            // Also verify the file was actually written to disk
            assertThat(workspace.resolve("result.txt")).exists();
            assertThat(Files.readString(workspace.resolve("result.txt")))
                .isEqualTo("shield:75\nsword:100\n");

            // And the SQLite database file exists
            assertThat(workspace.resolve("test.db")).exists();
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: skill_basic_cannot_access_database
    // -----------------------------------------------------------------------

    @Test
    void skill_basic_cannot_access_database() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_BASIC, null)) {
            // Database and fs should be undefined at SKILL_BASIC
            var result = ctx.eval("js", """
                var dbUndefined = (typeof Database === 'undefined');
                var fsUndefined = (typeof fs === 'undefined');
                dbUndefined + ',' + fsUndefined;
                """);

            assertThat(result.asString()).isEqualTo("true,true");
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: skill_data_cannot_access_java_interop
    // -----------------------------------------------------------------------

    @Test
    void skill_data_cannot_access_java_interop() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var result = ctx.eval("js", """
                try {
                    Java.type('java.lang.Runtime');
                    'escaped';
                } catch(e) {
                    'blocked';
                }
                """);

            assertThat(result.asString()).isEqualTo("blocked");
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: skill_full_can_access_java_interop
    // -----------------------------------------------------------------------

    @Test
    void skill_full_can_access_java_interop() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_FULL, workspace)) {
            var result = ctx.eval("js", """
                var System = Java.type('java.lang.System');
                var ts = System.currentTimeMillis();
                ts;
                """);

            assertThat(result.isNumber()).isTrue();
            long timestamp = result.asLong();
            // Should be a recent timestamp (within last minute)
            long now = System.currentTimeMillis();
            assertThat(timestamp).isBetween(now - 60_000, now + 1_000);
        }
    }

    // -----------------------------------------------------------------------
    // Test 9: data_pipeline_script_end_to_end
    // -----------------------------------------------------------------------

    @Test
    void data_pipeline_script_end_to_end() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var script = """
                // Step 1: Fetch JSON data from test server
                var jsonText = http.get('http://localhost:%d/json-array');
                var data = JSON.parse(jsonText);

                // Step 2: Store in SQLite
                var db = Database('pipeline.db');
                db.execute("CREATE TABLE scores (name TEXT, score INTEGER)");
                for (var i = 0; i < data.length; i++) {
                    db.execute("INSERT INTO scores (name, score) VALUES (?, ?)",
                        data[i].name, data[i].score);
                }

                // Step 3: Query with filter (score >= 80)
                var highScorers = db.query("SELECT name, score FROM scores WHERE score >= 80 ORDER BY score DESC");
                db.close();

                // Step 4: Write filtered results as CSV
                var csv = 'name,score\\n';
                for (var j = 0; j < highScorers.length; j++) {
                    csv += highScorers[j].name + ',' + highScorers[j].score + '\\n';
                }
                fs.write('high_scorers.csv', csv);

                // Step 5: Read back and verify
                var readBack = fs.read('high_scorers.csv');
                readBack;
                """.formatted(httpPort);

            Value result = ctx.eval("js", script);

            String csv = result.asString();
            assertThat(csv).startsWith("name,score\n");
            assertThat(csv).contains("Alice,95");
            assertThat(csv).contains("Eve,90");
            assertThat(csv).contains("Charlie,88");
            // Bob (72) and Diana (61) should NOT be in the filtered results
            assertThat(csv).doesNotContain("Bob");
            assertThat(csv).doesNotContain("Diana");

            // Verify ordering (descending by score)
            int aliceIdx = csv.indexOf("Alice,95");
            int eveIdx = csv.indexOf("Eve,90");
            int charlieIdx = csv.indexOf("Charlie,88");
            assertThat(aliceIdx).isLessThan(eveIdx);
            assertThat(eveIdx).isLessThan(charlieIdx);

            // Verify file on disk
            assertThat(workspace.resolve("high_scorers.csv")).exists();
            assertThat(workspace.resolve("pipeline.db")).exists();
        }
    }

    // -----------------------------------------------------------------------
    // Test 10: filesystem_sandbox_prevents_escape
    // -----------------------------------------------------------------------

    @Test
    void filesystem_sandbox_prevents_path_traversal_read() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            // Attempt to read outside the workspace using path traversal
            var result = ctx.eval("js", """
                var escaped = false;
                try {
                    fs.read('../../../etc/passwd');
                    escaped = true;
                } catch(e) {
                    escaped = false;
                }
                escaped ? 'ESCAPED' : 'BLOCKED';
                """);

            assertThat(result.asString()).isEqualTo("BLOCKED");
        }
    }

    @Test
    void filesystem_sandbox_prevents_path_traversal_write() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var result = ctx.eval("js", """
                var escaped = false;
                try {
                    fs.write('../../escape.txt', 'pwned');
                    escaped = true;
                } catch(e) {
                    escaped = false;
                }
                escaped ? 'ESCAPED' : 'BLOCKED';
                """);

            assertThat(result.asString()).isEqualTo("BLOCKED");

            // Double-check: no file was created outside workspace
            assertThat(workspace.getParent().resolve("escape.txt")).doesNotExist();
        }
    }

    @Test
    void filesystem_sandbox_prevents_absolute_path() {
        try (var ctx = SandboxContextBuilder.build(SandboxLevel.SKILL_DATA, workspace)) {
            var result = ctx.eval("js", """
                try {
                    fs.read('/etc/hostname');
                    'ESCAPED';
                } catch(e) {
                    'BLOCKED';
                }
                """);

            assertThat(result.asString()).isEqualTo("BLOCKED");
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
