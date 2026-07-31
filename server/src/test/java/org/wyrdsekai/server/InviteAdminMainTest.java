package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the headless invite admin CLI against a real SQLite database.
 *
 * <p>Uses the test-friendly {@link InviteAdminMain#run(String, PrintStream,
 * PrintStream, String...)} overload — never {@code main} — so {@link
 * System#exit} can't kill the test JVM. The JDBC URL is injected directly
 * rather than through env, keeping tests deterministic on JDK 25 where
 * env mutation is brittle.</p>
 */
class InviteAdminMainTest {

    private record CapturedRun(int exit, String stdout, String stderr) {}

    private static CapturedRun run(String jdbc, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var o = new PrintStream(out, true, StandardCharsets.UTF_8);
        var e = new PrintStream(err, true, StandardCharsets.UTF_8);
        int exit = InviteAdminMain.run(jdbc, o, e, args);
        return new CapturedRun(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String freshDbWithSteward(Path dir, String stewardUsername) {
        var dbPath = dir.resolve("world.db");
        var jdbc = SchemaInitializer.initialize(dbPath);
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var sess = auth.register(stewardUsername, "test-pass", stewardUsername, "steward");
        assertTrue(sess.isPresent(), "steward registration should succeed");
        return jdbc;
    }

    @Test void noArgs_printsUsageAndExits1(@TempDir Path dir) {
        var r = run(freshDbWithSteward(dir, "operator"));
        assertEquals(1, r.exit);
        assertTrue(r.stdout.contains("invite create"), "usage should mention 'create': " + r.stdout);
    }

    @Test void help_exits0(@TempDir Path dir) {
        var r = run("jdbc:sqlite::memory:", "help");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("invite create"));
    }

    @Test void unknownCommand_exits1(@TempDir Path dir) {
        var r = run("jdbc:sqlite::memory:", "nonsense");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("unknown"));
    }

    @Test void create_withSteward_printsCodeOnStdout(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "create", "TestGuest", "--as", "operator");
        assertEquals(0, r.exit, "expected success but got stderr: " + r.stderr);
        var code = r.stdout.trim();
        assertFalse(code.isBlank(), "stdout should print invite code");
        var invites = new InviteService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var all = invites.listInvites();
        assertEquals(1, all.size());
        assertEquals(code, all.get(0).code());
        assertEquals("TestGuest", all.get(0).intendedName());
        assertEquals("member", all.get(0).role());
    }

    @Test void create_autopicksFirstSteward_whenNoAsFlag(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "create", "AutoGuest");
        assertEquals(0, r.exit, "stderr: " + r.stderr);
        assertFalse(r.stdout.trim().isBlank());
    }

    @Test void create_missingName_exits1(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "create");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("Usage:") || r.stderr.contains("requires a name"),
            "stderr should show usage or name-required message: " + r.stderr);
    }

    @Test void create_unknownStewardUser_exits1(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "create", "Guest", "--as", "ghost");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("not found"), "stderr: " + r.stderr);
    }

    @Test void create_asNonSteward_exits1(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        auth.register("regular", "pass-pass", "Regular", "member");
        var r = run(jdbc, "create", "Guest", "--as", "regular");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("not a steward"), "stderr: " + r.stderr);
    }

    @Test void create_nullJdbc_exits2(@TempDir Path dir) {
        var r = run((String) null, "create", "Guest");
        assertEquals(2, r.exit);
        assertTrue(r.stderr.contains("no WYRDSEKAI_JDBC_URL"), "stderr: " + r.stderr);
    }

    @Test void create_customRoleAndTtl_persistsBoth(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "create", "ChildGuest", "--role", "child", "--ttl-hours", "1");
        assertEquals(0, r.exit, "stderr: " + r.stderr);
        var invites = new InviteService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var all = invites.listInvites();
        assertEquals(1, all.size());
        assertEquals("child", all.get(0).role());
        // TTL 1h → expires_at within ~1h of now
        var secondsUntilExpiry = Duration.between(
            Instant.now(), all.get(0).expiresAt()).getSeconds();
        assertTrue(secondsUntilExpiry > 3000 && secondsUntilExpiry <= 3600,
            "ttl should be ~1h, got " + secondsUntilExpiry + "s");
    }

    @Test void list_empty_printsPlaceholder(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "list");
        assertEquals(0, r.exit);
        assertTrue(r.stdout.contains("no invites"));
    }

    @Test void list_afterCreate_showsInvite(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var create = run(jdbc, "create", "TestGuest");
        assertEquals(0, create.exit);
        var code = create.stdout.trim();
        var list = run(jdbc, "list");
        assertEquals(0, list.exit);
        assertTrue(list.stdout.contains("TestGuest"), "stdout: " + list.stdout);
        assertTrue(list.stdout.contains(code), "stdout should include code: " + list.stdout);
        assertTrue(list.stdout.contains("pending"), "stdout: " + list.stdout);
    }

    // ── F4 phase 2: bootstrap subcommand ────────────────────────────────

    @Test void bootstrap_freshDb_printsCode(@TempDir Path dir) {
        var dbPath = dir.resolve("world.db");
        var jdbc = SchemaInitializer.initialize(dbPath);
        var r = run(jdbc, "bootstrap", "--ttl-hours", "1");
        assertEquals(0, r.exit, "expected success on empty DB but got: " + r.stderr);
        var code = r.stdout.trim();
        assertFalse(code.isBlank(), "stdout should print invite code");
        // Verify invite landed with steward role + null created_by.
        var invites = new InviteService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var pending = invites.listPendingInvites();
        assertEquals(1, pending.size());
        assertEquals(code, pending.get(0).code());
        assertEquals("steward", pending.get(0).role());
        assertEquals("steward", pending.get(0).intendedName());
        assertNull(pending.get(0).createdBy(), "bootstrap invite should have null created_by");
    }

    @Test void bootstrap_customName_usesIt(@TempDir Path dir) {
        var dbPath = dir.resolve("world.db");
        var jdbc = SchemaInitializer.initialize(dbPath);
        var r = run(jdbc, "bootstrap", "--name", "operator");
        assertEquals(0, r.exit, "stderr: " + r.stderr);
        var invites = new InviteService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        assertEquals("operator", invites.listPendingInvites().get(0).intendedName());
    }

    @Test void bootstrap_afterUserExists_refuses(@TempDir Path dir) {
        var jdbc = freshDbWithSteward(dir, "operator");
        var r = run(jdbc, "bootstrap");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("already exist") || r.stderr.contains("Bootstrap invite refused"),
            "stderr: " + r.stderr);
    }
}
