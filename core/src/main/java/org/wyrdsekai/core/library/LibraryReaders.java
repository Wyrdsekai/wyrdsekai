package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who may read the household's library door over HTTP (LIBRARY_PROTOCOL.md, "Serving it").
 *
 * <p>The same shape as the reference librarian's reader list: a name, a did, a level, and the
 * sha256 of a bearer token that is shown once. A peer librarian is added with
 * {@code wyrd library reader add <name>}; the token it is given goes into that librarian's
 * peer list. Anonymous callers read what the license gate lets travel; a token names the
 * patron and, at {@code write}, may submit drafts. One file, {@code library-readers.json},
 * read on every resolve — it is small and the door is not hot.
 */
public final class LibraryReaders {

    private static final Logger log = LoggerFactory.getLogger(LibraryReaders.class);
    private static final ObjectMapper M = new ObjectMapper();
    public static final String FILE = "library-readers.json";

    public enum Level { read, write }

    public record Reader(String name, String did, Level level, String tokenHash) {}

    private final Path file;

    public LibraryReaders(Path dataDir) { this.file = dataDir.resolve(FILE); }

    public Path file() { return file; }

    public synchronized List<Reader> list() {
        var out = new ArrayList<Reader>();
        if (!Files.isRegularFile(file)) return out;
        try {
            var root = M.readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (var r : root.path("readers")) {
                var lvl = "write".equalsIgnoreCase(r.path("level").asText("read")) ? Level.write : Level.read;
                out.add(new Reader(r.path("name").asText(""), r.path("did").asText(""), lvl, r.path("token_hash").asText("")));
            }
        } catch (IOException e) {
            log.warn("[library-readers] unreadable {}: {}", file, e.toString());
        }
        return out;
    }

    /** Add (or replace by name) a reader and issue its token — returned once; only the hash is kept. */
    public synchronized String issue(String name, String did, Level level) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("a reader needs a name");
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var kept = new ArrayList<Reader>();
        for (var r : list()) if (!r.name().equalsIgnoreCase(name)) kept.add(r);
        kept.add(new Reader(name.strip(), did == null ? "" : did.strip(), level == null ? Level.read : level, sha256(token)));
        write(kept);
        return token;
    }

    public synchronized boolean remove(String name) throws IOException {
        var kept = new ArrayList<Reader>();
        boolean found = false;
        for (var r : list()) { if (r.name().equalsIgnoreCase(name)) found = true; else kept.add(r); }
        if (found) write(kept);
        return found;
    }

    /** The reader a bearer token proves, or empty. */
    public Optional<Reader> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        var h = sha256(token.strip());
        for (var r : list()) if (MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), r.tokenHash().getBytes(StandardCharsets.UTF_8))) return Optional.of(r);
        return Optional.empty();
    }

    private void write(List<Reader> readers) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        var arr = new ArrayList<Map<String, Object>>();
        for (var r : readers) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", r.name()); m.put("did", r.did()); m.put("level", r.level().name()); m.put("token_hash", r.tokenHash());
            arr.add(m);
        }
        root.put("readers", arr);
        Files.createDirectories(file.getParent());
        Files.writeString(file, M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The bearer token in an Authorization header value, or null. */
    public static String bearer(String authorization) {
        if (authorization == null) return null;
        var a = authorization.strip();
        return a.regionMatches(true, 0, "Bearer ", 0, 7) ? a.substring(7).strip() : null;
    }

    public static Level levelOf(String s) {
        return s != null && s.strip().toLowerCase(Locale.ROOT).equals("write") ? Level.write : Level.read;
    }
}
