package org.wyrdsekai.core.naming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of zone labels <em>this household</em> operates.
 *
 * <p>Labels here are rooted at the caller's own {@link HouseholdIdentity}
 * implicitly — the file stores only labels, since the fingerprint is always
 * "mine" at read time. Pair this with a {@link HouseholdIdentity} to get
 * full {@link ZoneAddress}es.</p>
 *
 * <h2>File format</h2>
 *
 * <p>Path: {@code ~/.wyrdsekai/my-zones}. One label per line. Blank lines
 * and {@code #} comments are ignored. Order is preserved (insertion order),
 * so the first-registered label is also the "default" zone when the
 * operator types bare commands that need a zone hint.</p>
 *
 * <h2>Why a plaintext file instead of the database</h2>
 *
 * <p>Two reasons: (a) the list is small and rarely changes, (b) operators
 * can edit it by hand during migration or recovery without needing CLI
 * access. Same philosophy as SSH's {@code known_hosts}. The database stores
 * per-zone <em>state</em> (rooms, inventory, etc.) but the <em>set of
 * zones</em> is configuration.</p>
 */
public final class LocalZoneRegistry {

    private final Path file;
    // LinkedHashSet: ordered by insertion, unique by label. The first entry
    // is the "default" zone for bare commands.
    private final Set<String> labels = new LinkedHashSet<>();

    private LocalZoneRegistry(Path file) {
        this.file = file;
    }

    /**
     * Load (or create empty) the local zone registry from disk. Each label
     * is re-validated at load time — a file that was hand-edited to include
     * a reserved keyword fails loudly here rather than silently accepting it.
     */
    public static LocalZoneRegistry load(Path file) throws IOException {
        var reg = new LocalZoneRegistry(file);
        if (!Files.isRegularFile(file)) return reg;
        int lineNo = 0;
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            var stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            try {
                ZoneLabels.requireValid(stripped, "zone label");
            } catch (IllegalArgumentException e) {
                throw new IOException(file + ":" + lineNo + ": " + e.getMessage(), e);
            }
            reg.labels.add(stripped);
        }
        return reg;
    }

    /** In-memory constructor for tests and programmatic use. */
    public static LocalZoneRegistry empty(Path file) {
        return new LocalZoneRegistry(file);
    }

    /** Save the current label set to disk atomically. */
    public void save() throws IOException {
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        var sb = new StringBuilder();
        sb.append("# wyrdsekai my-zones — one label per line, first is the default.\n");
        sb.append("# See docs/ZONES.md.\n");
        for (var label : labels) sb.append(label).append('\n');
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Register a new zone label. Fails if the label is reserved, malformed,
     * or already registered (duplicate is a no-op error — caller must
     * {@link #remove} first).
     */
    public void add(String label) {
        ZoneLabels.requireValid(label, "zone label");
        if (!labels.add(label)) {
            throw new IllegalArgumentException(
                "zone label '" + label + "' is already registered");
        }
    }

    /** @return true if the label existed and was removed. */
    public boolean remove(String label) {
        return labels.remove(label);
    }

    /** @return true if a zone with this label is registered. */
    public boolean contains(String label) {
        return labels.contains(label);
    }

    /** @return the labels in insertion order. Read-only. */
    public List<String> list() {
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }

    /**
     * @return the default zone label — the first-registered label — or empty
     *     if no zones are registered. Callers use this when the operator
     *     types a bare command with no zone hint.
     */
    public Optional<String> defaultLabel() {
        return labels.stream().findFirst();
    }

    /** @return number of registered zones. */
    public int size() {
        return labels.size();
    }
}
