package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@code wyrd key add|list|remove} — the CLI over the per-account SSH-key store. */
class KeyAdminMainTest {

    private record CapturedRun(int exit, String stdout, String stderr) {}

    private static CapturedRun run(String jdbc, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = KeyAdminMain.run(jdbc,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), args);
        return new CapturedRun(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // Real ed25519 public keys (valid base64 blobs), so KeyAdminMain's
    // sshKeyLineFromOpenSsh accepts them.
    private static final String KEY_A =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA alice@laptop";
    private static final String KEY_B =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY1AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA alice@phone";

    private static String freshDb(Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        auth.register("steward", "test-pass", "Steward", "steward");
        auth.register("alice", "test-pass", "Alice", "member");
        return jdbc;
    }

    @Test void add_then_list(@TempDir Path dir) {
        var jdbc = freshDb(dir);
        var add = run(jdbc, "add", "alice", KEY_A, "--label", "laptop");
        assertEquals(0, add.exit(), add.stderr());
        assertTrue(add.stdout().contains("key added"));

        var list = run(jdbc, "list", "alice");
        assertEquals(0, list.exit());
        assertTrue(list.stdout().contains("laptop"), list.stdout());
        assertTrue(list.stdout().contains("ssh-ed25519"));
    }

    @Test void added_key_binds_to_that_account_only(@TempDir Path dir) {
        var jdbc = freshDb(dir);
        run(jdbc, "add", "alice", KEY_A);
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        // The key resolves to alice — NOT to the steward.
        var owner = auth.findUserBySshKey("ssh-ed25519 " + KEY_A.split("\\s+")[1]);
        assertTrue(owner.isPresent());
        assertEquals("alice", owner.get().username());
    }

    @Test void remove_by_index(@TempDir Path dir) {
        var jdbc = freshDb(dir);
        run(jdbc, "add", "alice", KEY_A, "--label", "laptop");
        run(jdbc, "add", "alice", KEY_B, "--label", "phone");
        assertEquals(2, new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc))
            .listSshKeys(new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc)).findUserByUsername("alice").get().id()).size());
        var rm = run(jdbc, "remove", "alice", "1");
        assertEquals(0, rm.exit(), rm.stderr());
        var list = run(jdbc, "list", "alice");
        assertTrue(list.stdout().contains("ssh-ed25519"));   // one left
    }

    @Test void unknown_user_is_rejected(@TempDir Path dir) {
        var jdbc = freshDb(dir);
        var r = run(jdbc, "add", "ghost", KEY_A);
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("no such account"));
    }

    @Test void malformed_key_is_rejected(@TempDir Path dir) {
        var jdbc = freshDb(dir);
        var r = run(jdbc, "add", "alice", "not-a-key blah");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("valid OpenSSH"));
    }

    @Test void no_db_fails_clean() {
        var r = run(null, "list", "alice");
        assertEquals(2, r.exit());
        assertTrue(r.stderr().contains("no database"));
    }
}
