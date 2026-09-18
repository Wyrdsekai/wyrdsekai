package org.wyrdsekai.core.vault;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.BackupOrchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The vault, where the copies are kept.
 *
 * <p>Three rules. <b>Classify</b>: the self (the record, identity keys, souls, memories,
 * adapters, the household's people and their studies) goes in; derivable state (search
 * indexes) is re-derived on restore; replaceable state (models) is fetched by hash. The nightly
 * copy of a 174 GB index was a classification failure, and this table is the answer to it.
 * <b>Continuous</b>: every pass takes a consistent copy of the record ({@code VACUUM INTO})
 * and stores it as content-addressed, content-defined chunks (about 4 MiB), so an unchanged
 * file costs nothing, an inserted page changes one chunk and not every chunk after it, and the
 * copy is never more than one pass behind. <b>Consistent</b>: a manifest names the exact
 * chunks of every file at one moment, and a restore reassembles and verifies them.</p>
 *
 * <p>Retention keeps every copy for two hours, one an hour for a day, one a day for a week, one a week for five weeks,
 * one a month for a year, and event copies (before an update, before a restore, before a risky
 * act) for as long as they are flagged. A drill restores the newest copy into scratch and
 * checks that it opens and holds the household. Restore is repair: what is displaced is kept.</p>
 *
 * <p>The vault directory is plain files, so a second copy is a file copy: {@code wyrd vault
 * sync} pushes it to a vault node or offsite with rsync.</p>
 */
public final class Vault {

    private static final Logger log = LoggerFactory.getLogger(Vault.class);
    private static final ObjectMapper JSON = Json.mapper().copy()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** Content-defined chunking: cut where the data says, so an inserted page moves one
     *  boundary and not every boundary after it. Min 1 MiB, about 4 MiB on average, max 8 MiB. */
    static final int MIN_CHUNK = 1024 * 1024;
    static final int MAX_CHUNK = 8 * 1024 * 1024;
    static final long CUT_MASK = (1L << 22) - 1;          // one cut per 4 MiB of input on average
    private static final long[] GEAR = gear();

    private static long[] gear() {
        // Fixed for all time: a different table changes where old copies would have been cut,
        // which costs dedupe, never correctness (chunks are named by their content).
        var r = new java.util.Random(0x5EEDBEEFL);
        var t = new long[256];
        for (int i = 0; i < 256; i++) t[i] = r.nextLong();
        return t;
    }

    /** The length of the next chunk in {@code b[0..len)}: at least MIN, at most MAX, at a cut. */
    static int cutPoint(byte[] b, int len) {
        if (len <= MIN_CHUNK) return len;
        int end = Math.min(len, MAX_CHUNK);
        long h = 0;
        for (int i = 0; i < end; i++) {
            h = (h << 1) + GEAR[b[i] & 0xff];
            if (i >= MIN_CHUNK && (h & CUT_MASK) == 0) return i + 1;
        }
        return end;
    }

    /** What class a path is, and so what the vault does with it. */
    public enum Class { SELF, DERIVABLE, REPLACEABLE }

    /** One file in a manifest: its path in the data directory and the chunks that make it. */
    public record Entry(String path, long size, long mtime, List<String> chunks) {}

    /** The result of a drill: did the newest copy come up, and what was in it. */
    public record Drill(Instant at, boolean ok, String detail) {}

    /** One moment in the vault. {@code keep} copies never expire by the tiers. */
    public record Manifest(String id, Instant at, String reason, boolean keep, List<Entry> files,
                           Map<String, List<String>> classes, Drill drill) {
        public long bytes() { return files.stream().mapToLong(Entry::size).sum(); }
        Manifest withDrill(Drill d) { return new Manifest(id, at, reason, keep, files, classes, d); }
    }

    private static volatile Vault instance;
    public static Vault get() { return instance; }
    public static void install(Vault v) { instance = v; }
    public static void resetForTests() { instance = null; }

    private final Path dataDir;
    private final Path vaultDir;
    private final Path keyFile;
    private final VaultCipher cipher;
    /** Set when the store was sealed with a key other than ours; nothing is written or read until it is resolved. */
    private final String keyMismatch;
    private volatile Instant lastOk;
    private volatile String lastError;
    private boolean resealed;

    /** The key lives beside the data, never inside the store: {@code <data>/vault.key}. */
    public Vault(Path dataDir, Path vaultDir) {
        this(dataDir, vaultDir, dataDir.resolve(KEY_FILE));
    }

    public Vault(Path dataDir, Path vaultDir, Path keyFile) {
        this.dataDir = dataDir;
        this.vaultDir = vaultDir;
        this.keyFile = keyFile;
        VaultCipher c;
        try {
            c = VaultCipher.load(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("the vault key at " + keyFile + " could not be read or made: " + e.getMessage(), e);
        }
        this.cipher = c;
        this.keyMismatch = checkKeyId();
        if (keyMismatch != null) { lastError = keyMismatch; log.error("Vault: {}", keyMismatch); }
    }

    public static final String KEY_FILE = "vault.key";
    private static final String KEY_ID_FILE = "key.id";

    /**
     * The store remembers the fingerprint of the key that sealed it. A different key means
     * this is someone else's store, or the key file was lost and a new one made; either way
     * writing with the new key would leave a store no single key can open.
     */
    private String checkKeyId() {
        var f = vaultDir.resolve(KEY_ID_FILE);
        try {
            if (Files.isRegularFile(f)) {
                var have = Files.readString(f).strip();
                if (!have.isEmpty() && !have.equals(cipher.id())) {
                    return "the vault at " + vaultDir + " was sealed with key " + have + "; the key at " + keyFile
                        + " is " + cipher.id() + ". Put the original key file back, or point at it with --key.";
                }
                return null;
            }
            Files.createDirectories(vaultDir);
            Files.writeString(f, cipher.id() + "\n");
        } catch (IOException e) {
            log.warn("vault: key id not recorded: {}", e.getMessage());
        }
        return null;
    }

    public Path dir() { return vaultDir; }
    public Path keyFile() { return keyFile; }
    /** Eight hex characters naming the key; the same on every copy of the store. */
    public String keyId() { return cipher.id(); }
    public String keyMismatch() { return keyMismatch; }
    public Instant lastOk() { return lastOk; }
    public String lastError() { return lastError; }

    // ── classification ──

    /** Relative paths in the data directory that are the self. Missing ones are skipped. */
    static final List<String> SELF_FILES = List.of(
        "world.db", "library.db", "node-identity.json", "credentials.safe", "profile.toml", "wyrdsekai.conf",
        "env", "nats.conf", "mcp-services.json", "operator.token", "session.token", "data-version.json",
        "embedding-model.txt", "latest-wyrdsekai.txt", "latest-codezaiku.txt", "latest-researchzosho.txt",
        "self-update.json", "ssh_host_key", "ssh_host_key.pub", "steward-feed.jsonl", "brainstem/events.jsonl");
    static final List<String> SELF_DIRS = List.of(
        "agents", "classifiers", "souls", "substrate", "items", "recipes", "adapters",
        "data/story", "data/biography", "data/study", "study", "household",
        "chronicles", "coding-workspaces", "skills", "scripts", "sleepwrite", "training", "library",
        "vault");   // the Vault room's shelf of scrolls, not this store
    /** Re-derived on restore: indexes, queues, caches, predictions, the bus's own state. */
    static final List<String> DERIVABLE = List.of("search", "data/search", "embeddings", "themed-descriptions.json",
        "manifest_audit.json", "logs", "brainstem/heartbeat", "oracle", "ingest", "jetstream",
        "library.db-wal", "library.db-shm", "world.db-wal", "world.db-shm");
    /** Fetched again by hash: weights, bundles, knowledge packs, and the copies themselves. */
    static final List<String> REPLACEABLE = List.of("models", "coding-cli-bundle", "backups", "vault-store", "packs",
        "mlx-venv", "sleepwrite-venv", "venv", ".venv");   // trainer environments are rebuilt, never copied

    static Class classify(String rel) {
        for (var p : REPLACEABLE) if (rel.equals(p) || rel.startsWith(p + "/")) return Class.REPLACEABLE;
        for (var p : DERIVABLE) if (rel.equals(p) || rel.startsWith(p + "/")) return Class.DERIVABLE;
        return Class.SELF;
    }

    // ── snapshot ──

    /** Take a copy now. Never throws; a failure is logged, recorded, and returns empty. */
    public synchronized Optional<Manifest> snapshot(String reason, boolean keep) {
        var at = Instant.now();
        var id = ID.format(at) + (keep ? "-keep" : "");
        Path scratch = null;
        if (keyMismatch != null) { lastError = keyMismatch; return Optional.empty(); }
        try {
            Files.createDirectories(vaultDir.resolve("chunks"));
            Files.createDirectories(vaultDir.resolve("manifests"));
            if (!resealed) { resealPlain(); resealed = true; }
            scratch = Files.createTempDirectory(vaultDir, ".snap-");
            var entries = new ArrayList<Entry>();
            var seenTop = new HashSet<String>();
            seenTop.add(KEY_FILE);   // the one file that must never ride inside the store

            // Databases come from a consistent copy: VACUUM INTO captures the WAL. The record
            // must be there; a smaller database that is missing is skipped like any other file.
            var orchestrator = new BackupOrchestrator(scratch);
            for (var rel : SELF_FILES) {
                seenTop.add(rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel);
                var f = dataDir.resolve(rel);
                if (!Files.isRegularFile(f)) continue;
                if (rel.endsWith(".db")) {
                    var copy = orchestrator.snapshot(f).map(BackupOrchestrator.BackupManifest::location).orElse(null);
                    if (copy == null) {
                        if (rel.equals("world.db")) throw new IOException("could not take a consistent copy of world.db");
                        log.warn("vault: no consistent copy of {}; skipped this pass", rel);
                        continue;
                    }
                    entries.add(store(rel, copy));
                } else {
                    entries.add(store(rel, f));
                }
            }
            for (var rel : SELF_DIRS) {
                var d = dataDir.resolve(rel);
                seenTop.add(rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel);
                if (!Files.isDirectory(d)) continue;
                Files.walkFileTree(d, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            try { entries.add(store(dataDir.relativize(file).toString().replace('\\', '/'), file)); }
                            catch (IOException e) { log.warn("vault: skipped {}: {}", file, e.getMessage()); }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            // What was NOT vaulted, by class, so a restore knows what to re-derive or fetch,
            // and a steward can see what is unclassified.
            var classes = new LinkedHashMap<String, List<String>>();
            var derivable = new ArrayList<String>(); var replaceable = new ArrayList<String>(); var unclassified = new ArrayList<String>();
            try (var top = Files.list(dataDir)) {
                for (var p : top.sorted().toList()) {
                    var rel = p.getFileName().toString();
                    switch (classify(rel)) {
                        case DERIVABLE -> derivable.add(rel);
                        case REPLACEABLE -> replaceable.add(rel);
                        case SELF -> { if (!seenTop.contains(rel) && !rel.startsWith(".")) unclassified.add(rel); }
                    }
                }
            }
            classes.put("derivable", derivable);
            classes.put("replaceable", replaceable);
            classes.put("unclassified", unclassified);
            var m = new Manifest(id, at, reason == null ? "continuous" : reason, keep, List.copyOf(entries), classes, null);
            writeManifest(m);
            lastOk = Instant.now();
            lastError = null;
            log.info("Vault: copy {} ({}, {} files, {} MB){}", id, m.reason(), entries.size(), m.bytes() / 1_000_000,
                unclassified.isEmpty() ? "" : " — unclassified: " + unclassified);
            return Optional.of(m);
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("Vault: copy failed ({}): {}", reason, e.toString());
            return Optional.empty();
        } finally {
            if (scratch != null) deleteTree(scratch);
        }
    }

    /** Chunk one file into the store; unchanged chunks cost a read and a hash, never a write. */
    private Entry store(String rel, Path file) throws IOException {
        var chunks = new ArrayList<String>();
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            var buf = new byte[MAX_CHUNK];
            int filled = readFully(in, buf, 0);
            while (filled > 0) {
                int n = cutPoint(buf, filled);
                size += n;
                var sha = sha256(buf, n);
                var dest = chunkPath(sha);
                if (!Files.exists(dest)) {
                    Files.createDirectories(dest.getParent());
                    var tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
                    Files.write(tmp, cipher.seal(buf, 0, n));
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                chunks.add(sha);
                System.arraycopy(buf, n, buf, 0, filled - n);
                filled -= n;
                filled += readFully(in, buf, filled);
            }
        }
        return new Entry(rel, size, Files.getLastModifiedTime(file).toMillis(), List.copyOf(chunks));
    }

    private static int readFully(InputStream in, byte[] buf, int from) throws IOException {
        int total = 0;
        while (from + total < buf.length) {
            int n = in.read(buf, from + total, buf.length - from - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private Path chunkPath(String sha) {
        return vaultDir.resolve("chunks").resolve(sha.substring(0, 2)).resolve(sha);
    }

    static String sha256(byte[] buf, int len) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(buf, 0, len);
            var sb = new StringBuilder();
            for (var b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── manifests ──

    private void writeManifest(Manifest m) throws IOException {
        var f = vaultDir.resolve("manifests").resolve(m.id() + ".json");
        var tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, cipher.seal(JSON.writeValueAsBytes(m)));
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Manifest readManifest(Path f) throws IOException {
        return JSON.readValue(cipher.open(Files.readAllBytes(f)), Manifest.class);
    }

    /**
     * Files written before sealing are plain; seal them in place, once, so the whole store
     * is unreadable without the key. Cheap when there is nothing to do: four bytes per file.
     */
    synchronized int resealPlain() {
        int n = 0;
        for (var sub : List.of("chunks", "manifests")) {
            var dir = vaultDir.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                for (var f : walk.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".tmp")).toList()) {
                    try {
                        byte[] head;
                        try (var in = Files.newInputStream(f)) { head = in.readNBytes(VaultCipher.MAGIC.length); }
                        if (head.length == VaultCipher.MAGIC.length && Arrays.equals(head, VaultCipher.MAGIC)) continue;
                        var tmp = f.resolveSibling(f.getFileName() + ".tmp");
                        Files.write(tmp, cipher.seal(Files.readAllBytes(f)));
                        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    } catch (IOException e) {
                        log.warn("vault: could not seal {}: {}", f, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("vault: reseal walk failed: {}", e.getMessage());
            }
        }
        if (n > 0) log.info("Vault: sealed {} file(s) written before the key existed", n);
        return n;
    }

    /** Every manifest, newest first. */
    public List<Manifest> list() {
        var out = new ArrayList<Manifest>();
        var dir = vaultDir.resolve("manifests");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (var f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                try { out.add(readManifest(f)); }
                catch (IOException e) { log.warn("vault: unreadable manifest {}: {}", f, e.getMessage()); }
            }
        } catch (IOException e) {
            log.warn("vault: could not list manifests: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Manifest::at).reversed());
        return out;
    }

    public Optional<Manifest> latest() {
        return list().stream().findFirst();
    }

    public Optional<Manifest> find(String id) {
        if (id == null || id.isBlank() || "latest".equals(id)) return latest();
        return list().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    // ── restore ──

    /**
     * Reassemble every file of a manifest under {@code target}, verifying each chunk's hash.
     * Returns the files that could not be rebuilt; empty means the copy is whole.
     */
    public List<String> restoreTo(Manifest m, Path target) {
        var failed = new ArrayList<String>();
        for (var e : m.files()) {
            var dest = target.resolve(e.path());
            try {
                Files.createDirectories(dest.getParent());
                var tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
                long written = 0;
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    for (var sha : e.chunks()) {
                        var bytes = cipher.open(Files.readAllBytes(chunkPath(sha)));
                        if (!sha256(bytes, bytes.length).equals(sha)) throw new IOException("chunk " + sha + " does not match its name");
                        out.write(bytes);
                        written += bytes.length;
                    }
                }
                if (written != e.size()) throw new IOException("size " + written + " != " + e.size());
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                failed.add(e.path() + ": " + ex.getMessage());
            }
        }
        return failed;
    }

    // ── the drill ──

    /**
     * Restore the newest copy into scratch and boot the record: integrity, and whether the
     * household is in it. The verdict is written into the manifest and returned.
     */
    public synchronized Optional<Drill> drill() {
        var m = latest().orElse(null);
        if (m == null) return Optional.empty();
        var scratch = vaultDir.resolve(".drill");
        deleteTree(scratch);
        var detail = new StringBuilder();
        boolean ok = true;
        try {
            Files.createDirectories(scratch);
            var failed = restoreTo(m, scratch);
            if (!failed.isEmpty()) { ok = false; detail.append("could not rebuild: ").append(failed).append("; "); }
            var db = scratch.resolve("world.db");
            if (Files.exists(db)) {
                try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db); var st = conn.createStatement()) {
                    try (var rs = st.executeQuery("PRAGMA integrity_check")) {
                        var v = rs.next() ? rs.getString(1) : "?";
                        if (!"ok".equals(v)) { ok = false; }
                        detail.append("integrity ").append(v).append("; ");
                    }
                    detail.append(count(st, "companions", "companions")).append(count(st, "users", "people"))
                        .append(count(st, "residency", "residencies")).append(count(st, "soul_manifests", "souls"));
                } catch (Exception e) {
                    ok = false; detail.append("the record did not open: ").append(e.getMessage()).append("; ");
                }
            } else {
                ok = false; detail.append("no world.db in the copy; ");
            }
            var identity = scratch.resolve("node-identity.json");
            if (Files.exists(identity)) {
                // The household's keypair file: a node id and a public key, the private halves
                // encrypted. Readable means it parses and names the key.
                boolean readable;
                try {
                    var node = JSON.readTree(identity.toFile());
                    readable = node.hasNonNull("publicKey") && !node.get("publicKey").asText().isBlank()
                        || node.hasNonNull("nodeId") && !node.get("nodeId").asText().isBlank()
                        || node.hasNonNull("did");
                } catch (Exception e) {
                    readable = false;
                }
                detail.append(readable ? "identity present" : "identity unreadable");
                if (!readable) ok = false;
            } else {
                detail.append("no node identity in the copy");
            }
        } catch (Exception e) {
            ok = false; detail.append("drill failed: ").append(e);
        } finally {
            deleteTree(scratch);
        }
        var d = new Drill(Instant.now(), ok, detail.toString().strip());
        try { writeManifest(m.withDrill(d)); } catch (IOException e) { log.warn("vault: drill verdict not written: {}", e.getMessage()); }
        var map = BodyMap.get();
        if (map != null) {
            map.mark("vault", "drill", null, ok
                ? "The vault was tested: the newest copy comes up and the household is in it (" + d.detail() + ")."
                : "The vault was tested and the newest copy did not come up whole: " + d.detail() + ". The steward should look.",
                "manifest=" + m.id() + " ok=" + ok);
        }
        log.info("Vault drill {}: {} ({})", ok ? "PASSED" : "FAILED", m.id(), d.detail());
        return Optional.of(d);
    }

    private static String count(java.sql.Statement st, String table, String label) {
        try (var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) + " " + label + "; " : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── retention ──

    /**
     * Keep: every copy from the last two hours; the newest per hour for a day; the newest per
     * day for a week; the newest per ISO week for five weeks; the newest per month for a year;
     * every flagged copy forever. Then drop the chunks nothing references. Returns how many
     * manifests were removed.
     */
    public synchronized int prune() {
        return prune(Instant.now());
    }

    synchronized int prune(Instant now) {
        var all = list();
        var keep = new HashSet<String>();
        var newestPerHour = new TreeMap<String, Manifest>();
        var newestPerDay = new TreeMap<String, Manifest>();
        var newestPerWeek = new TreeMap<String, Manifest>();
        var newestPerMonth = new TreeMap<String, Manifest>();
        for (var m : all) {
            if (m.keep()) { keep.add(m.id()); continue; }
            var age = Duration.between(m.at(), now);
            var z = m.at().atZone(ZoneOffset.UTC);
            if (age.compareTo(Duration.ofHours(2)) <= 0) { keep.add(m.id()); continue; }
            if (age.compareTo(Duration.ofDays(1)) <= 0) { newestPerHour.merge(z.toLocalDate() + "T" + z.getHour(), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(7)) <= 0) { newestPerDay.merge(z.toLocalDate().toString(), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(35)) <= 0) { newestPerWeek.merge(z.getYear() + "-W" + z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(400)) <= 0) { newestPerMonth.merge(z.getYear() + "-" + z.getMonthValue(), m, Vault::newer); }
        }
        for (var m : newestPerHour.values()) keep.add(m.id());
        for (var m : newestPerDay.values()) keep.add(m.id());
        for (var m : newestPerWeek.values()) keep.add(m.id());
        for (var m : newestPerMonth.values()) keep.add(m.id());
        int removed = 0;
        var referenced = new HashSet<String>();
        for (var m : all) {
            if (keep.contains(m.id())) {
                for (var e : m.files()) referenced.addAll(e.chunks());
                continue;
            }
            try {
                Files.deleteIfExists(vaultDir.resolve("manifests").resolve(m.id() + ".json"));
                removed++;
            } catch (IOException e) {
                log.warn("vault: could not remove manifest {}: {}", m.id(), e.getMessage());
            }
        }
        var chunks = vaultDir.resolve("chunks");
        if (Files.isDirectory(chunks)) {
            try {
                Files.walkFileTree(chunks, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        var name = file.getFileName().toString();
                        if (!name.endsWith(".tmp") && !referenced.contains(name)) {
                            try { Files.delete(file); } catch (IOException ignored) { /* next prune */ }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.warn("vault: chunk sweep failed: {}", e.getMessage());
            }
        }
        if (removed > 0) log.info("Vault: pruned {} manifest(s); {} kept", removed, keep.size());
        return removed;
    }

    private static Manifest newer(Manifest a, Manifest b) {
        return a.at().isAfter(b.at()) ? a : b;
    }

    // ── status ──

    public Map<String, Object> status() {
        var out = new LinkedHashMap<String, Object>();
        var all = list();
        out.put("dir", vaultDir.toString());
        out.put("copies", all.size());
        out.put("kept", all.stream().filter(Manifest::keep).count());
        out.put("latest", all.isEmpty() ? null : all.get(0).at().toString());
        out.put("latestId", all.isEmpty() ? null : all.get(0).id());
        out.put("latestBytes", all.isEmpty() ? 0 : all.get(0).bytes());
        out.put("chunkStoreBytes", storeBytes());
        var drilled = all.stream().filter(m -> m.drill() != null).max(Comparator.comparing(m -> m.drill().at()));
        out.put("lastDrill", drilled.map(m -> m.drill().at().toString()).orElse(null));
        out.put("lastDrillOk", drilled.map(m -> m.drill().ok()).orElse(null));
        out.put("lastDrillDetail", drilled.map(m -> m.drill().detail()).orElse(""));
        out.put("lastOk", lastOk == null ? null : lastOk.toString());
        out.put("lastError", lastError == null ? "" : lastError);
        out.put("keyId", cipher.id());
        out.put("keyFile", keyFile.toString());
        out.put("keyMismatch", keyMismatch == null ? "" : keyMismatch);
        if (!all.isEmpty()) out.put("unclassified", all.get(0).classes().getOrDefault("unclassified", List.of()));
        return out;
    }

    private long storeBytes() {
        var chunks = vaultDir.resolve("chunks");
        if (!Files.isDirectory(chunks)) return 0;
        final long[] total = {0};
        try {
            Files.walkFileTree(chunks, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { /* partial is fine */ }
        return total[0];
    }

    /** True when the newest drill is older than {@code every} or there has never been one. */
    public boolean drillDue(Duration every) {
        return list().stream().filter(m -> m.drill() != null).map(m -> m.drill().at())
            .max(Comparator.naturalOrder())
            .map(t -> Duration.between(t, Instant.now()).compareTo(every) > 0)
            .orElse(true);
    }

    private static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) return;
        try {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            log.debug("vault: scratch not removed: {}", e.getMessage());
        }
    }
}
