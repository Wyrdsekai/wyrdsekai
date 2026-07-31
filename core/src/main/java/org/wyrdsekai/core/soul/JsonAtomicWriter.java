package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Wave 9a-PersistRefactor: shared atomic-write helper for the four
 * substrate trackers (RepairLedger, AttendantSessionTracker,
 * RepairModeTracker, ProtectionFlagTracker). Each tracker previously
 * had ~20 lines of duplicated mapper config + tmp-write + rename
 * logic; this puts the canonical implementation in one place so any
 * future fix lands once.
 *
 * <p>Atomic-write semantics: write to {@code <file>.tmp}, then atomic
 * rename. A crash mid-write leaves the prior file intact rather than
 * leaving a truncated/corrupt JSON that would fail-clean to empty —
 * which would otherwise discard ALL substrate state on any partial
 * write. Pre-existing {@code .tmp} from a prior crash is harmlessly
 * overwritten on next persist (it's a write target, not a read source).
 *
 * <p>Falls back to plain {@link StandardCopyOption#REPLACE_EXISTING} on
 * filesystems that don't support {@link StandardCopyOption#ATOMIC_MOVE}
 * (e.g. cross-device renames).
 *
 * <p>The mapper is freshly constructed per call (not cached) — JSON
 * persist is rare (sleep + PostStop) and the JSR-310 module
 * registration is cheap. Avoiding shared state keeps the helper
 * thread-safe by construction.
 */
public final class JsonAtomicWriter {

    private JsonAtomicWriter() {}

    /**
     * Serialize {@code value} to {@code file} as pretty-printed JSON
     * atomically. Creates parent directories if missing. Throws
     * {@link IllegalArgumentException} on null path.
     */
    public static void write(Path file, Object value) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        var parent = file.getParent() != null
            ? file.getParent() : file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // Some filesystems (e.g. cross-device) don't support
            // ATOMIC_MOVE; degrade to plain REPLACE_EXISTING.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
