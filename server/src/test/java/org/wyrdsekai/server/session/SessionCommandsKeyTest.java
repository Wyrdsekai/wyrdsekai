package org.wyrdsekai.server.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-world {@code key} command — parse + scoped execution. A user manages only
 * their OWN keys from the Study; the command never touches another account.
 */
class SessionCommandsKeyTest {

    private static final String KEY_A =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA me@laptop";

    private record Setup(AuthService auth, String aliceId, String bobId) {}

    private static Setup fresh(Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var alice = auth.register("alice", "pw123456", "Alice", "steward").orElseThrow().userId();
        var bob = auth.register("bob", "pw123456", "Bob", "member").orElseThrow().userId();
        return new Setup(auth, alice, bob);
    }

    @Test void parser_recognizes_key_forms() {
        assertInstanceOf(CommandParser.ParsedCommand.Key.class,
            CommandParser.parse("key add ssh-ed25519 AAAA", "en", Map.of()));
        assertInstanceOf(CommandParser.ParsedCommand.Key.class,
            CommandParser.parse("key list", "en", Map.of()));
        assertInstanceOf(CommandParser.ParsedCommand.Key.class,
            CommandParser.parse("key", "en", Map.of()));
        assertInstanceOf(CommandParser.ParsedCommand.Key.class,
            CommandParser.parse("key remove 1", "en", Map.of()));
    }

    @Test void in_world_object_key_is_not_the_command() {
        // "take key" / "examine key" must NOT parse as the account command.
        assertFalse(CommandParser.parse("take key", "en", Map.of())
            instanceof CommandParser.ParsedCommand.Key);
        assertFalse(CommandParser.parse("examine key", "en", Map.of())
            instanceof CommandParser.ParsedCommand.Key);
    }

    @Test void add_then_list_scoped_to_self(@TempDir Path dir) {
        var s = fresh(dir);
        var added = SessionCommands.key(s.auth(), s.aliceId(),
            List.of("add", "ssh-ed25519", KEY_A.split("\\s+")[1], "laptop"));
        assertTrue(added.toLowerCase().contains("added"), added);

        var list = SessionCommands.key(s.auth(), s.aliceId(), List.of("list"));
        assertTrue(list.contains("laptop"), list);
        // The key is bound to alice, not bob.
        assertTrue(s.auth().listSshKeys(s.bobId()).isEmpty());
        assertEquals(1, s.auth().listSshKeys(s.aliceId()).size());
    }

    @Test void remove_scoped_to_self(@TempDir Path dir) {
        var s = fresh(dir);
        SessionCommands.key(s.auth(), s.aliceId(),
            List.of("add", "ssh-ed25519", KEY_A.split("\\s+")[1]));
        var removed = SessionCommands.key(s.auth(), s.aliceId(), List.of("remove", "1"));
        assertTrue(removed.toLowerCase().contains("removed"), removed);
        assertTrue(s.auth().listSshKeys(s.aliceId()).isEmpty());
    }

    @Test void malformed_key_rejected(@TempDir Path dir) {
        var s = fresh(dir);
        var r = SessionCommands.key(s.auth(), s.aliceId(), List.of("add", "garbage"));
        assertTrue(r.toLowerCase().contains("not a valid"), r);
        assertTrue(s.auth().listSshKeys(s.aliceId()).isEmpty());
    }
}
