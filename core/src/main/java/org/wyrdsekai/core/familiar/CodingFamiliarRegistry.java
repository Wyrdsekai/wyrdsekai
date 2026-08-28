package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.JsonAtomicWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * disk-backed registry for Coding
 * Familiar identities.
 *
 * <p>Each bondholder's Coding Familiar persists to a single JSON file at
 * {@code <souls-dir>/familiars/codezaiku-<sanitized-bondholder-did>.json}.
 * One file per bondholder is right because the familiar's identity is
 * <em>per-bondholder</em>, not per-zone, not per-project — same bondholder
 * across multiple project portals shares the same Coding Familiar.</p>
 *
 * <p>Writes go through {@link JsonAtomicWriter} so a crash mid-persist
 * leaves the prior file intact rather than truncating the identity. Read
 * caches into an in-memory map for the lifetime of the registry instance;
 * tests and tools that need to see fresh-from-disk values should construct
 * a new registry.</p>
 *
 * <p>The DID-to-path sanitizer replaces any character that cannot safely
 * appear in a filename with {@code _}. DIDs in practice are URL-safe but
 * may contain {@code :}, which is reserved on Windows — the sanitizer
 * keeps the on-disk shape portable.</p>
 */
public final class CodingFamiliarRegistry {

    private static final Logger log = LoggerFactory.getLogger(CodingFamiliarRegistry.class);

    /** Subdirectory under souls root. */
    public static final String FAMILIARS_SUBDIR = "familiars";

    /** Filename prefix — distinguishes Coding Familiar files from other familiar kinds. */
    public static final String FILE_PREFIX = "codezaiku-";

    /** The pre-rename filename prefix. Still LISTED and still OPENED. */
    public static final String LEGACY_FILE_PREFIX = "codeplane-";

    /** Filename extension. */
    public static final String FILE_EXT = ".json";

    private final Path familiarsDir;
    private final ConcurrentHashMap<String, CodingFamiliarIdentity> byBondholderDid =
        new ConcurrentHashMap<>();

    /**
     * @param soulsRoot path containing the {@code familiars/} subdirectory.
     *                  Typically {@code <data-dir>/souls/} where
     *                  {@code data-dir} is from {@code WyrdConfig.dataDir()}.
     */
    public CodingFamiliarRegistry(Path soulsRoot) {
        if (soulsRoot == null) {
            throw new IllegalArgumentException("soulsRoot required");
        }
        this.familiarsDir = soulsRoot.resolve(FAMILIARS_SUBDIR);
    }

    /** The directory where Coding Familiar files live. Useful for backup wiring. */
    public Path familiarsDir() {
        return familiarsDir;
    }

    /**
     * Filename for a bondholder's Coding Familiar JSON. Public so backup +
     * test code can locate the file without re-deriving the sanitizer.
     */
    public Path fileFor(String bondholderDid) {
        if (bondholderDid == null || bondholderDid.isBlank()) {
            throw new IllegalArgumentException("bondholderDid required");
        }
        var current = familiarsDir.resolve(FILE_PREFIX + sanitize(bondholderDid) + FILE_EXT);
        if (Files.exists(current)) return current;
        // A familiar summoned before the rename lives under the old prefix.
        // Nothing migrates the file, so look for it rather than silently
        // treating that bondholder as having no familiar and summoning a
        // second one.
        var legacy = familiarsDir.resolve(
            LEGACY_FILE_PREFIX + sanitize(bondholderDid) + FILE_EXT);
        return Files.exists(legacy) ? legacy : current;
    }

    /** Replace any character outside {@code [a-zA-Z0-9._-]} with {@code _}. */
    private static String sanitize(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Look up an identity by bondholder DID. Loads from disk lazily on
     * first access if not yet cached. Returns {@code Optional.empty()} if
     * no file exists.
     */
    public Optional<CodingFamiliarIdentity> get(String bondholderDid) {
        if (bondholderDid == null || bondholderDid.isBlank()) {
            return Optional.empty();
        }
        var cached = byBondholderDid.get(bondholderDid);
        if (cached != null) return Optional.of(cached);

        var file = fileFor(bondholderDid);
        if (!Files.exists(file)) return Optional.empty();

        try {
            var mapper = newMapper();
            var loaded = mapper.readValue(file.toFile(), CodingFamiliarIdentity.class);
            byBondholderDid.put(bondholderDid, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            // Fail clean — identity files are precious; refuse to overwrite a
            // file we can't parse, but don't crash the caller either. The
            // bondholder will see a fresh-summon flow until the file is
            // fixed or deleted by hand.
            log.error("Failed to read Coding Familiar identity for {} from {}: {}",
                bondholderDid, file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persist an identity atomically. Updates the in-memory cache on
     * success. Throws {@link IOException} so callers can choose whether
     * to fail-loud (first-summon ceremony should; tick-rate updates
     * probably shouldn't).
     */
    public void save(CodingFamiliarIdentity identity) throws IOException {
        if (identity == null) {
            throw new IllegalArgumentException("identity required");
        }
        var file = fileFor(identity.bondholderDid());
        JsonAtomicWriter.write(file, identity);
        byBondholderDid.put(identity.bondholderDid(), identity);
        log.info("Persisted Coding Familiar identity {} ({}) -> {}",
            identity.name(), identity.did(), file);
    }

    /**
     * List all bondholder DIDs that have a Coding Familiar on disk. Reads
     * directory listings each call so newly-arrived files (e.g. via a
     * backup restore) are visible.
     */
    public List<String> list() {
        if (!Files.exists(familiarsDir)) return List.of();
        var out = new ArrayList<String>();
        try (var stream = Files.list(familiarsDir)) {
            stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> (n.startsWith(FILE_PREFIX) || n.startsWith(LEGACY_FILE_PREFIX))
                             && n.endsWith(FILE_EXT))
                .forEach(n -> {
                    // Recover the sanitized bondholder slug. We don't have a
                    // reverse-sanitizer (lossy by design), so the truth-source
                    // for bondholder DID is inside the JSON file itself.
                    var sanitized = n.substring(
                        n.startsWith(FILE_PREFIX)
                            ? FILE_PREFIX.length() : LEGACY_FILE_PREFIX.length(),
                        n.length() - FILE_EXT.length());
                    try {
                        var loaded = newMapper().readValue(
                            familiarsDir.resolve(n).toFile(),
                            CodingFamiliarIdentity.class);
                        out.add(loaded.bondholderDid());
                        byBondholderDid.put(loaded.bondholderDid(), loaded);
                    } catch (IOException e) {
                        log.warn("Skipping unreadable Coding Familiar file {}: {}",
                            n, e.getMessage());
                        // Don't add the sanitized slug — it isn't a DID.
                        // Caller asked for DIDs, so unreadable files are
                        // silently skipped, but we leave a log line.
                        out.add("<unreadable:" + sanitized + ">");
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to list Coding Familiar directory {}: {}",
                familiarsDir, e.getMessage());
        }
        return List.copyOf(out);
    }

    /**
     * Clear the in-memory cache. Tests use this between runs; production
     * code shouldn't need it (cache invalidation on save is automatic).
     */
    public void clearCache() {
        byBondholderDid.clear();
    }

    private static ObjectMapper newMapper() {
        var m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }
}
