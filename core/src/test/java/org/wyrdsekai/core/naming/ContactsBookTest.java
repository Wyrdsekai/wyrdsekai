package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContactsBookTest {

    private static final String DID_ALICE =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_BOB =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    @Test void empty_startsEmpty(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertEquals(0, book.size());
        assertTrue(book.list().isEmpty());
    }

    @Test void add_getsStoredByAlias(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, "kitchen");

        var got = book.get("alice");
        assertTrue(got.isPresent());
        assertEquals(DID_ALICE, got.get().did());
        assertEquals("kitchen", got.get().defaultLabel());
    }

    @Test void add_rejectsReservedAlias(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("home", DID_ALICE, null));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("self", DID_ALICE, null));
    }

    @Test void add_rejectsMalformedAlias(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("Alice", DID_ALICE, null));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("alice:bob", DID_ALICE, null));
    }

    @Test void add_rejectsMalformedDid(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("alice", "not-a-did", null));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("alice", "did:key:z6Mk…", null));  // wrong scheme
    }

    @Test void add_rejectsDuplicateAlias(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, null);
        var ex = assertThrows(IllegalArgumentException.class,
            () -> book.add("alice", DID_BOB, null));
        // Prevents silent overwrite of a trusted fingerprint — spec §3.3.
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test void add_rejectsReservedDefaultLabel(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertThrows(IllegalArgumentException.class,
            () -> book.add("alice", DID_ALICE, "home"));
    }

    @Test void remove_returnsTrueOnHit(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, null);
        assertTrue(book.remove("alice"));
        assertFalse(book.remove("alice"));  // already gone
        assertEquals(0, book.size());
    }

    @Test void rename_moves(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, "kitchen");
        book.rename("alice", "alice-new");

        assertTrue(book.get("alice").isEmpty());
        assertTrue(book.get("alice-new").isPresent());
        assertEquals(DID_ALICE, book.get("alice-new").get().did());
        // Default label carries over.
        assertEquals("kitchen", book.get("alice-new").get().defaultLabel());
    }

    @Test void rename_rejectsCollision(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, null);
        book.add("bob", DID_BOB, null);
        assertThrows(IllegalArgumentException.class,
            () -> book.rename("alice", "bob"));
    }

    @Test void rename_rejectsUnknown(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        assertThrows(IllegalArgumentException.class,
            () -> book.rename("nobody", "somebody"));
    }

    @Test void updateDid_keepsAliasAndDefaultLabel(@TempDir Path tmp) {
        // Spec §3.3: contact update on key rotation.
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, "kitchen");
        book.updateDid("alice", DID_BOB);
        var got = book.get("alice");
        assertTrue(got.isPresent());
        assertEquals(DID_BOB, got.get().did());
        assertEquals("kitchen", got.get().defaultLabel());
    }

    @Test void fingerprint_stripsScheme(@TempDir Path tmp) {
        var book = ContactsBook.empty(tmp.resolve("contacts"));
        book.add("alice", DID_ALICE, null);
        var contact = book.get("alice").get();
        assertFalse(contact.fingerprint().startsWith("did:"));
        assertTrue(contact.fingerprint().startsWith("z"));
        assertEquals(DID_ALICE, "did:wyrd:" + contact.fingerprint());
    }

    @Test void saveAndLoad_roundTrip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("contacts");
        var book = ContactsBook.empty(file);
        book.add("alice", DID_ALICE, "kitchen");
        book.add("bob", DID_BOB, null);
        book.save();

        assertTrue(Files.exists(file));
        var reloaded = ContactsBook.load(file);
        assertEquals(2, reloaded.size());

        var a = reloaded.get("alice");
        assertTrue(a.isPresent());
        assertEquals(DID_ALICE, a.get().did());
        assertEquals("kitchen", a.get().defaultLabel());

        var b = reloaded.get("bob");
        assertTrue(b.isPresent());
        assertEquals(DID_BOB, b.get().did());
        assertNull(b.get().defaultLabel());
    }

    @Test void saveAndLoad_preservesInsertionOrder(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("contacts");
        var book = ContactsBook.empty(file);
        book.add("alice", DID_ALICE, null);
        book.add("bob", DID_BOB, null);
        book.save();

        var reloaded = ContactsBook.load(file);
        var list = reloaded.list();
        assertEquals("alice", list.get(0).alias());
        assertEquals("bob", list.get(1).alias());
    }

    @Test void load_ignoresBlankLinesAndComments(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("contacts");
        Files.writeString(file, """
            # a comment
            alice\t""" + DID_ALICE + """
            \tkitchen

            # another comment
            bob\t""" + DID_BOB + "\n");

        var book = ContactsBook.load(file);
        assertEquals(2, book.size());
    }

    @Test void load_reportsLineOnMalformedEntry(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("contacts");
        Files.writeString(file, "alice\tnot-a-did\n");
        var ex = assertThrows(IOException.class,
            () -> ContactsBook.load(file));
        assertTrue(ex.getMessage().contains(":1:"));
    }

    @Test void load_returnsEmptyIfFileMissing(@TempDir Path tmp) throws Exception {
        var book = ContactsBook.load(tmp.resolve("nonexistent"));
        assertEquals(0, book.size());
    }
}
