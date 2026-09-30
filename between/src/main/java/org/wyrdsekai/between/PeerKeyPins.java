package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keys of household nodes that are not on the roster, pinned the first time they said hello with a
 * signature their own key verified. Kept in {@code peer-keys.json} (mode 0600) so a restart does not
 * re-open the pin to whoever speaks first. A pinned key changes only through {@link KeyRotation}.
 */
public final class PeerKeyPins {

    private static final Logger log = LoggerFactory.getLogger(PeerKeyPins.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, byte[]> pins = new LinkedHashMap<>();

    /** @param file where the pins live; null keeps them in memory only (tests). */
    public PeerKeyPins(Path file) {
        this.file = file;
        load();
    }

    public synchronized Optional<byte[]> get(String nodeId) {
        var k = pins.get(nodeId);
        return k == null ? Optional.empty() : Optional.of(k.clone());
    }

    /** Pin {@code key} for {@code nodeId} if nothing is pinned yet. True when the pin now equals {@code key}. */
    public synchronized boolean pinIfAbsent(String nodeId, byte[] key) {
        var existing = pins.get(nodeId);
        if (existing != null) return Arrays.equals(existing, key);
        pins.put(nodeId, key.clone());
        save();
        log.info("Between: pinned the key of household node {} on its first signed hello", nodeId);
        return true;
    }

    /** Replace a pin, only when the current pin is {@code oldKey} (a verified rotation). */
    public synchronized boolean rotate(String nodeId, byte[] oldKey, byte[] newKey) {
        var existing = pins.get(nodeId);
        if (existing == null || !Arrays.equals(existing, oldKey)) return false;
        pins.put(nodeId, newKey.clone());
        save();
        return true;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            var root = MAPPER.readTree(file.toFile());
            root.fields().forEachRemaining(e -> {
                try {
                    pins.put(e.getKey(), Base64.getDecoder().decode(e.getValue().asText()));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Between: skipped an unreadable pinned key for {} in {}", e.getKey(), file);
                }
            });
        } catch (IOException e) {
            log.warn("Between: could not read pinned peer keys from {}: {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) return;
        var out = new LinkedHashMap<String, String>();
        pins.forEach((k, v) -> out.put(k, Base64.getEncoder().encodeToString(v)));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), out);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX filesystem (Windows) — the data dir's ACL applies
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Between: could not save pinned peer keys to {}: {}", file, e.getMessage());
        }
    }
}
