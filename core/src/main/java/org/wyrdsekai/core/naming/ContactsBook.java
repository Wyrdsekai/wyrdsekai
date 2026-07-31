package org.wyrdsekai.core.naming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A user's local address book of <em>other</em> households, keyed by
 * user-chosen aliases.
 *
 * <p>Exactly the SSH {@code known_hosts} model — one file per user, no
 * global registry, TOFU (trust on first use) when adding. Two users can
 * both call someone {@code alice}; the names never escape their owner's
 * filesystem so collision is structurally impossible (
 * §2.2).</p>
 *
 * <h2>File format</h2>
 *
 * <p>Path: {@code ~/.wyrdsekai/contacts}. One entry per line. Blank lines
 * and lines starting with {@code #} are ignored. An entry is:</p>
 *
 * <pre>
 * alias    did:wyrd:z6Mk…    [default-label]
 * </pre>
 *
 * <p>Fields are whitespace-separated. The optional third field is the
 * default zone label to use when the user types a bare {@code travel alias}
 * (no {@code :label}). If omitted, bare-alias resolution fails and the user
 * is asked to specify a label explicitly.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>This class is not thread-safe. The file is expected to be edited by
 * a single operator at a time (CLI) or read-only from a companion process.
 * If we later need concurrent writes, wrap in a file lock at {@link #save}
 * time.</p>
 */
public final class ContactsBook {

    /**
     * A single entry in the contacts book.
     *
     * @param alias        user-chosen local nickname
     * @param did          fully-qualified {@code did:wyrd:z6Mk…} for the
     *                     contact's household (no zone label — a contact
     *                     identifies a household, not a specific zone)
     * @param defaultLabel optional default zone label for bare
     *                     {@code travel alias}; nullable
     */
    public record Contact(String alias, String did, String defaultLabel) {
        public Contact {
            ZoneLabels.requireValid(alias, "contact alias");
            if (did == null || !did.startsWith(HouseholdIdentity.DID_SCHEME)) {
                throw new IllegalArgumentException(
                    "did must start with '" + HouseholdIdentity.DID_SCHEME + "': " + did);
            }
            // Default label, if present, must be a valid label. Reserved
            // keywords are rejected here too — a contact can't have a zone
            // called `home`.
            if (defaultLabel != null && !defaultLabel.isEmpty()) {
                ZoneLabels.requireValid(defaultLabel, "default zone label");
            }
        }

        /** @return the fingerprint portion of the DID ({@code z6Mk…}). */
        public String fingerprint() {
            return did.substring(HouseholdIdentity.DID_SCHEME.length());
        }
    }

    private final Path file;
    // LinkedHashMap preserves file-insertion order — matters for `contacts list`
    // output (users expect newest-added to show up last, matching what they typed).
    private final Map<String, Contact> contacts = new LinkedHashMap<>();

    private ContactsBook(Path file) {
        this.file = file;
    }

    /**
     * Load (or create empty) a contacts book from disk.
     *
     * @throws IOException if the file exists but cannot be read, or contains
     *                     malformed entries that can't be parsed.
     */
    public static ContactsBook load(Path file) throws IOException {
        var book = new ContactsBook(file);
        if (!Files.isRegularFile(file)) return book;
        int lineNo = 0;
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            var stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            var parts = stripped.split("\\s+");
            if (parts.length < 2) {
                throw new IOException(
                    file + ":" + lineNo + ": expected '<alias> <did> [default-label]', got: " + line);
            }
            try {
                var alias = parts[0];
                var did = parts[1];
                var def = parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : null;
                book.contacts.put(alias, new Contact(alias, did, def));
            } catch (IllegalArgumentException e) {
                throw new IOException(file + ":" + lineNo + ": " + e.getMessage(), e);
            }
        }
        return book;
    }

    /**
     * In-memory constructor for tests or programmatic use. The {@code file}
     * is a hint for later {@link #save()} and doesn't need to exist yet.
     */
    public static ContactsBook empty(Path file) {
        return new ContactsBook(file);
    }

    /**
     * Save the current contacts to disk, overwriting atomically (write to a
     * temp file then move).
     *
     * @throws IOException on I/O failure. Partial writes are never observable
     *                     — either the old file is intact or the new file is.
     */
    public void save() throws IOException {
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        var sb = new StringBuilder();
        sb.append("# wyrdsekai contacts — alias  did:wyrd:…  [default-label]\n");
        sb.append("# See docs/ZONES.md.\n");
        for (var c : contacts.values()) {
            sb.append(c.alias()).append('\t').append(c.did());
            if (c.defaultLabel() != null) sb.append('\t').append(c.defaultLabel());
            sb.append('\n');
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /** @return the contact saved under {@code alias}, or empty. */
    public Optional<Contact> get(String alias) {
        return Optional.ofNullable(contacts.get(alias));
    }

    /** @return all contacts in insertion order. Read-only view. */
    public List<Contact> list() {
        return Collections.unmodifiableList(new ArrayList<>(contacts.values()));
    }

    /**
     * Add a new contact. Fails if the alias already exists — callers must
     * explicitly {@link #remove} or {@link #rename} first. This prevents
     * accidental overwrite of a trusted fingerprint on a typo.
     *
     * @throws IllegalArgumentException if the alias is reserved or malformed,
     *                                  the DID is malformed, or the alias is
     *                                  already taken.
     */
    public void add(String alias, String did, String defaultLabel) {
        if (contacts.containsKey(alias)) {
            throw new IllegalArgumentException(
                "alias '" + alias + "' already exists; use `remove` or `rename` first");
        }
        contacts.put(alias, new Contact(alias, did, defaultLabel));
    }

    /** @return true if the contact existed and was removed. */
    public boolean remove(String alias) {
        return contacts.remove(alias) != null;
    }

    /**
     * Rename a contact in place. The new alias must not already exist.
     * @throws IllegalArgumentException if {@code oldAlias} is unknown, the
     *     new alias is reserved/malformed, or the new alias is taken.
     */
    public void rename(String oldAlias, String newAlias) {
        ZoneLabels.requireValid(newAlias, "contact alias");
        if (!contacts.containsKey(oldAlias)) {
            throw new IllegalArgumentException("unknown contact: " + oldAlias);
        }
        if (contacts.containsKey(newAlias)) {
            throw new IllegalArgumentException(
                "alias '" + newAlias + "' already exists");
        }
        // Rebuild preserving order — LinkedHashMap has no in-place rename.
        var rebuilt = new LinkedHashMap<String, Contact>();
        for (var e : contacts.entrySet()) {
            if (e.getKey().equals(oldAlias)) {
                rebuilt.put(newAlias,
                    new Contact(newAlias, e.getValue().did(), e.getValue().defaultLabel()));
            } else {
                rebuilt.put(e.getKey(), e.getValue());
            }
        }
        contacts.clear();
        contacts.putAll(rebuilt);
    }

    /**
     * Update a contact's DID — used when someone rotates their keypair and
     * tells you out-of-band ({@code contact update alice <new-did>} per
     * ). Fails if the alias doesn't exist.
     */
    public void updateDid(String alias, String newDid) {
        var existing = contacts.get(alias);
        if (existing == null) {
            throw new IllegalArgumentException("unknown contact: " + alias);
        }
        contacts.put(alias, new Contact(alias, newDid, existing.defaultLabel()));
    }

    /** @return number of contacts (primarily for tests). */
    public int size() {
        return contacts.size();
    }
}
