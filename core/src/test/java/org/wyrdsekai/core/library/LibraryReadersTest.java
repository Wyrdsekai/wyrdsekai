package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LibraryReadersTest {

    @TempDir Path dir;

    @Test
    void a_token_is_shown_once_and_only_its_hash_is_kept() throws Exception {
        var readers = new LibraryReaders(dir);
        var token = readers.issue("alice's librarian", "did:key:zAlice", LibraryReaders.Level.write);
        assertNotNull(token);
        assertTrue(token.length() >= 40);
        var text = Files.readString(readers.file());
        assertFalse(text.contains(token), "the token must not be on disk");
        assertTrue(text.contains("did:key:zAlice"));
        var r = readers.resolve(token).orElseThrow();
        assertEquals("alice's librarian", r.name());
        assertEquals(LibraryReaders.Level.write, r.level());
        assertTrue(readers.resolve("nope").isEmpty());
        assertTrue(readers.resolve(null).isEmpty());
    }

    @Test
    void reissuing_by_name_replaces_the_old_token_and_remove_forgets_it() throws Exception {
        var readers = new LibraryReaders(dir);
        var t1 = readers.issue("lab", "", LibraryReaders.Level.read);
        var t2 = readers.issue("lab", "", LibraryReaders.Level.read);
        assertTrue(readers.resolve(t1).isEmpty(), "the old token is dead");
        assertTrue(readers.resolve(t2).isPresent());
        assertEquals(1, readers.list().size());
        assertTrue(readers.remove("LAB"));
        assertTrue(readers.resolve(t2).isEmpty());
        assertFalse(readers.remove("lab"));
    }

    @Test
    void bearer_parsing_is_forgiving_about_case_and_space() {
        assertEquals("abc", LibraryReaders.bearer("Bearer abc"));
        assertEquals("abc", LibraryReaders.bearer("bearer   abc "));
        assertNull(LibraryReaders.bearer("Basic abc"));
        assertNull(LibraryReaders.bearer(null));
        assertEquals(LibraryReaders.Level.write, LibraryReaders.levelOf("WRITE"));
        assertEquals(LibraryReaders.Level.read, LibraryReaders.levelOf("anything"));
    }
}
