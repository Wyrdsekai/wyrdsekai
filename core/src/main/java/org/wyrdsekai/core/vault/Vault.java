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
import java.nio.channels.Channels;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;
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
    /** A database's piece: a whole number of pages for every SQLite page size up to 64 KiB. */
    static final int DB_PIECE = 64 * 1024;
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
        public Head head() { return new Head(id, at, reason, keep, drill); }
    }
    /** A copy as the listing and the retention see it: everything but its file list. */
    public record Head(String id, Instant at, String reason, boolean keep, Drill drill) {}

    /** How many manifests were read whole, and how many as heads only; for the tests. */
    static final java.util.concurrent.atomic.AtomicInteger MANIFESTS_READ_WHOLE = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger HEADS_READ = new java.util.concurrent.atomic.AtomicInteger();

    private static volatile Vault instance;
    public static Vault get() { return instance; }
    public static void install(Vault v) { instance = v; }
    public static void resetForTests() { instance = null; keepRefillsForTests = false; }

    private final Path dataDir;
    private final Path vaultDir;
    private final Path keyFile;
    private final VaultCipher cipher;
    /** Set when the store was sealed with a key other than ours; nothing is written or read until it is resolved. */
    private final String keyMismatch;
    /** Transition setting {@value #ALLOW_UNSEALED_ENV}: read and reseal plain files in a sealed store. */
    private final boolean allowUnsealed;
    /**
     * True once the store is known to be sealed throughout: a plain chunk or manifest in it was not
     * written by this vault (anyone who can write the store could plant one), so it is refused.
     */
    private volatile boolean sealedOnly;
    /** Set by {@link #resealPlain} when a plain file could not be sealed. */
    private boolean plainLeft;
    private volatile Instant lastOk;
    private volatile String lastError;
    private boolean resealed;
    /**
     * The least free space the vault leaves on its disk, in bytes; 0 = no floor. Below it the
     * index is kept only in the newest copy and, if that is not enough, a pass copies the record
     * alone. A full disk stops the vault, and the vault is the one thing that must not stop
     * (the household node's data disk was at 87% with two weeks left, 2026-10-25).
     */
    private volatile long floorBytes;
    /** How much room the vault's disk has; a seam for the tests, the file store otherwise. */
    LongSupplier freeSpace = this::usableSpace;

    /** The key lives beside the data, never inside the store: {@code <data>/vault.key}. */
    public Vault(Path dataDir, Path vaultDir) {
        this(dataDir, vaultDir, dataDir.resolve(KEY_FILE));
    }

    public Vault(Path dataDir, Path vaultDir, Path keyFile) {
        this(dataDir, vaultDir, keyFile, "true".equalsIgnoreCase(System.getenv(ALLOW_UNSEALED_ENV)));
    }

    Vault(Path dataDir, Path vaultDir, Path keyFile, boolean allowUnsealed) {
        this.dataDir = dataDir;
        this.vaultDir = vaultDir;
        this.keyFile = keyFile;
        this.allowUnsealed = allowUnsealed;
        VaultCipher c;
        try {
            c = VaultCipher.load(keyFile);
        } catch (IOException e) {
            throw new IllegalStateException("the vault key at " + keyFile + " could not be read or made: " + e.getMessage(), e);
        }
        this.cipher = c;
        this.keyMismatch = checkKeyId();
        if (keyMismatch != null) { lastError = keyMismatch; log.error("Vault: {}", keyMismatch); }
        this.sealedOnly = Files.exists(sealedMark()) || holdsSealedManifest();
        if (allowUnsealed) {
            log.warn("Vault: {} is set: plain (unsealed) files in {} are read and resealed as if this vault wrote them. "
                + "Unset it once the store is resealed.", ALLOW_UNSEALED_ENV, vaultDir);
        }
    }

    public static final String KEY_FILE = "vault.key";
    private static final String KEY_ID_FILE = "key.id";
    /**
     * Beside the key, never inside the store: written once every file in the store is sealed. It is
     * outside the store so that someone who can write the store cannot make it look unsealed again.
     */
    public static final String SEALED_MARK = "vault.sealed";
    public static final String ALLOW_UNSEALED_ENV = "WYRDSEKAI_VAULT_ALLOW_UNSEALED";

    private Path sealedMark() {
        return keyFile.resolveSibling(SEALED_MARK);
    }

    /** A store with any sealed manifest has been through sealing: nothing plain in it is ours. */
    private boolean holdsSealedManifest() {
        var dir = vaultDir.resolve("manifests");
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            for (var f : s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).toList()) {
                try (var in = Files.newInputStream(f)) {
                    var head = in.readNBytes(VaultCipher.MAGIC.length);
                    if (Arrays.equals(head, VaultCipher.MAGIC)) return true;
                }
            }
        } catch (IOException e) {
            log.warn("vault: could not look for sealed manifests: {}", e.getMessage());
        }
        return false;
    }

    private void markSealed() {
        sealedOnly = true;
        try {
            if (!Files.exists(sealedMark())) Files.writeString(sealedMark(), cipher.id() + "\n");
        } catch (IOException e) {
            log.warn("vault: could not write {}: {}", sealedMark(), e.getMessage());
        }
    }

    /** {@link VaultCipher#open}, refusing a plain file in a store that is sealed throughout. */
    private byte[] open(byte[] data, Path file) throws IOException {
        if (sealedOnly && !VaultCipher.sealed(data)) {
            if (!allowUnsealed) {
                throw new IOException(file.getFileName() + " is not sealed, and every file this vault writes is: refused. "
                    + "If you copied in a store from 0.4.0 on purpose, set " + ALLOW_UNSEALED_ENV + "=true once to read it.");
            }
            log.warn("Vault: reading unsealed {} because {} is set", file, ALLOW_UNSEALED_ENV);
        }
        return cipher.open(data);
    }

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
        "self-update.json", "ssh_host_key", "ssh_host_key.pub", "steward-feed.jsonl", "brainstem/events.jsonl",
        // Unclassified until 2026-10-04, so never copied: the tunnel's key (phones pair again without it),
        // the relay certificate the node pinned, what the body last saw of its machine and of its brain
        // (a restore onto a new machine is noticed), and her last turns, which the move gate reads.
        "tunnel-key.json", "relay-tls-seen", "hardware-seen.json", "serving-profile.last", "shadow.jsonl");
    static final List<String> SELF_DIRS = List.of(
        "agents", "classifiers", "souls", "substrate", "items", "recipes", "adapters",
        "data/story", "data/biography", "data/study", "study", "household",
        "chronicles", "skills", "scripts", "sleepwrite", "training", "library",
        "vault",    // the Vault room's shelf of scrolls, not this store
        "tls", "nats");   // the household CA phones and machines pin, and the bus's logins (W2)
    /**
     * Append-only trails: the companions' day-by-day record and the drive trace, each taken with
     * what was rotated off it ({@code drive-trace.jsonl.1}; see {@link BackupOrchestrator#isTrailFile}).
     * They grow only at the end, so after the first copy a pass stores the new tail chunk.
     */
    static final List<String> TRAILS = List.of("data/agent-activity.jsonl", "data/drive-trace.jsonl");
    /** Re-derived on restore: indexes, queues, caches, predictions, the bus's own state. */
    static final List<String> DERIVABLE = List.of("search", "data/search", "embeddings", "themed-descriptions.json",
        "manifest_audit.json", "logs", "brainstem/heartbeat", "oracle", "ingest", "jetstream",
        "library.db-wal", "library.db-shm", "world.db-wal", "world.db-shm");
    /**
     * Indexes that are dear: rebuilding the knowledge index took ten hours on four cards and would take
     * days on a household node's one, so it is carried, not derived (2026-09-26). Lucene never rewrites
     * a segment file, so after the first copy a pass stores only new segments; an unchanged file is
     * reused from the previous copy by size and mtime without being read again.
     */
    static final List<String> DEAR_DIRS = List.of("search/search/knowledge", "search/search/study",
        "data/search/knowledge", "data/search/study");
    /** Fetched again by hash: weights, bundles, knowledge packs, and the copies themselves. */
    static final List<String> REPLACEABLE = List.of("models", "coding-cli-bundle", "backups", "vault-store", "packs",
        "mlx-venv", "sleepwrite-venv", "brainwrite-venv", "venv", ".venv");   // trainer environments are rebuilt, never copied

    /**
     * Trees a being writes into, walked so that nothing is read through a link (see {@link #storeTree}):
     * her coding workspaces, and her home under {@code beings/<slug>/} with the line naming who she is.
     * Her home holds what her tools keep, her familiars' memory among it; it was never copied before
     * 2026-10-04 because nothing named it.
     */
    static final List<String> BEING_TREES = List.of("coding-workspaces", "beings");

    /** Tests only: copy the refills too, to make a copy from before the rule. */
    static volatile boolean keepRefillsForTests = false;

    /** What a being's tools refill on their own: fetched again, never copied. */
    static boolean beingCache(String rel) {
        if (keepRefillsForTests) return false;
        if (!rel.startsWith("beings/")) return false;
        var parts = rel.split("/");
        for (var part : parts) if (part.equals("node_modules") || part.equals("__pycache__")) return true;
        if (parts.length < 4 || !parts[2].equals("home")) return false;
        // A virtual environment anywhere in her home is a refill, like node_modules.
        for (var part : parts) if (part.equals(".venv") || part.equals("venv")) return true;
        var inHome = String.join("/", Arrays.copyOfRange(parts, 3, parts.length));
        return inHome.equals(".cache") || inHome.startsWith(".cache/")
            || inHome.equals(".npm") || inHome.startsWith(".npm/")
            || inHome.equals(".local/lib") || inHome.startsWith(".local/lib/")
            || inHome.equals(".local/bin") || inHome.startsWith(".local/bin/")
            // The coding helper's own environments and caches: 7 GB of them in one home on the
            // household node, copied as hers (2026-10-25). It makes them again.
            || inHome.equals(".codezaiku/envs") || inHome.startsWith(".codezaiku/envs/")
            || inHome.equals(".codezaiku/cache") || inHome.startsWith(".codezaiku/cache/")
            || inHome.equals(".gradle") || inHome.startsWith(".gradle/")
            || inHome.equals(".m2") || inHome.startsWith(".m2/");
    }

    /** Whether a path in the data directory is one of the dear indexes. */
    static boolean isDear(String rel) {
        for (var d : DEAR_DIRS) if (rel.equals(d) || rel.startsWith(d + "/")) return true;
        return false;
    }

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
        // The floor: below it, older copies give up their index first; if the disk is still
        // low, this copy is the record alone. Her record is never what gives way.
        boolean recordOnly = false;
        if (floorBytes > 0 && freeSpace.getAsLong() < floorBytes) {
            log.warn("Vault: {} MB free on the vault's disk, under the floor of {} MB — the index stays only in the newest copy",
                freeSpace.getAsLong() / 1_000_000, floorBytes / 1_000_000);
            prune(Instant.now(), true);
            if (freeSpace.getAsLong() < floorBytes) {
                recordOnly = true;
                log.warn("Vault: still under the floor after the sweep — this copy holds the record alone, not the index");
            }
            var map = BodyMap.get();
            if (map != null) {
                try {
                    map.mark("vault", "disk-low", null, "The vault's disk is nearly full: " + freeSpace.getAsLong() / 1_000_000_000 + " GB free. "
                        + "Older copies have given up their search index; the record itself is still copied every pass. The steward should make room.",
                        "free=" + freeSpace.getAsLong() + " floor=" + floorBytes);
                } catch (RuntimeException ignored) { /* the mark is a courtesy */ }
            }
        }
        try {
            Files.createDirectories(vaultDir.resolve("chunks"));
            Files.createDirectories(vaultDir.resolve("manifests"));
            if (!resealed) {
                // Sealing plain files is the one-time move from a 0.4.0 store. Once the store is
                // sealed, a plain file was put there by someone else and is not adopted.
                if (!sealedOnly || allowUnsealed) resealPlain();
                if (!plainLeft) markSealed();
                resealed = true;
            }
            scratch = Files.createTempDirectory(vaultDir, ".snap-");
            var entries = new ArrayList<Entry>();
            var seenTop = new HashSet<String>();
            seenTop.add(KEY_FILE);   // the one file that must never ride inside the store
            seenTop.add(SEALED_MARK);

            // Databases come from a consistent copy: a page copy that captures the WAL. The record
            // must be there; a smaller database that is missing is skipped like any other file.
            var orchestrator = new BackupOrchestrator(scratch);
            var previous = previousEntries();
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
                    entries.add(store(rel, f, previous));
                }
            }
            for (var rel : SELF_DIRS) {
                var d = dataDir.resolve(rel);
                seenTop.add(rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel);
                if (!Files.isDirectory(d)) continue;
                Files.walkFileTree(d, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            try { entries.add(store(dataDir.relativize(file).toString().replace('\\', '/'), file, previous)); }
                            catch (IOException e) { log.warn("vault: skipped {}: {}", file, e.getMessage()); }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            var refilled = new ArrayList<String>();
            for (var rel : BEING_TREES) {
                seenTop.add(rel);
                var d = dataDir.resolve(rel);
                if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
                storeTree(d, rel, entries, previous, refilled);
            }
            for (var rel : TRAILS) {
                var trail = dataDir.resolve(rel);
                seenTop.add(rel.substring(0, rel.indexOf('/')));
                if (!Files.isDirectory(trail.getParent())) continue;
                var name = trail.getFileName().toString();
                List<Path> files;
                try (var siblings = Files.list(trail.getParent())) {
                    files = siblings.filter(f -> BackupOrchestrator.isTrailFile(name, f.getFileName().toString()))
                        .filter(Files::isRegularFile).sorted().toList();
                }
                for (var f : files) {
                    try { entries.add(store(dataDir.relativize(f).toString().replace('\\', '/'), f, previous)); }
                    catch (IOException e) { log.warn("vault: skipped {}: {}", f, e.getMessage()); }
                }
            }
            // What was NOT vaulted, by class, so a restore knows what to re-derive or fetch,
            // and a steward can see what is unclassified.
            var dear = new ArrayList<String>();
            for (var rel : DEAR_DIRS) {
                var d = dataDir.resolve(rel);
                if (!Files.isDirectory(d)) continue;
                if (recordOnly) continue;
                dear.add(rel);
                Files.walkFileTree(d, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && !"write.lock".equals(file.getFileName().toString())) {
                            try { entries.add(store(dataDir.relativize(file).toString().replace('\\', '/'), file, previous)); }
                            catch (IOException e) { log.warn("vault: skipped {}: {}", file, e.getMessage()); }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
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
            classes.put("dear", dear);
            classes.put("derivable", derivable);
            replaceable.addAll(refilled);
            classes.put("replaceable", replaceable);
            classes.put("unclassified", unclassified);
            var m = new Manifest(id, at, reason == null ? "continuous" : reason, keep, List.copyOf(entries), classes, null);
            writeManifest(m);
            if (recordOnly) markThin(id);
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
        try (InputStream in = Files.newInputStream(file)) {
            var e = store(rel, in, 0);
            return new Entry(rel, e.size(), Files.getLastModifiedTime(file).toMillis(), e.chunks());
        }
    }

    private Entry store(String rel, InputStream in, long mtime) throws IOException {
        var chunks = new ArrayList<String>();
        long size = 0;
        if (isDatabase(rel)) {
            // A database copy keeps every unchanged page at its place (BackupOrchestrator's page
            // copy), so pieces cut at fixed page-aligned offsets are the same pieces as last time
            // except where a page changed. Content-defined pieces of 1-8 MB were each touched by a
            // few scattered page changes: a copy of a database with 8 MB changed still wrote
            // 200-560 MB (household node, 2026-10-26).
            var piece = new byte[DB_PIECE];
            int n;
            while ((n = readFully(in, piece, 0)) > 0) {
                size += n;
                chunks.add(putChunk(piece, n));
                if (n < piece.length) break;
            }
            return new Entry(rel, size, mtime, List.copyOf(chunks));
        }
        var buf = new byte[MAX_CHUNK];
        int filled = readFully(in, buf, 0);
        while (filled > 0) {
            int n = cutPoint(buf, filled);
            size += n;
            chunks.add(putChunk(buf, n));
            System.arraycopy(buf, n, buf, 0, filled - n);
            filled -= n;
            filled += readFully(in, buf, filled);
        }
        return new Entry(rel, size, mtime, List.copyOf(chunks));
    }

    /** One piece into the store, named by its content; a piece already there costs a hash, never a write. */
    private String putChunk(byte[] b, int n) throws IOException {
        var sha = sha256(b, n);
        var dest = chunkPath(sha);
        if (!Files.exists(dest)) {
            Files.createDirectories(dest.getParent());
            var tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
            Files.write(tmp, cipher.seal(b, 0, n));
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return sha;
    }

    /** The databases the vault copies page for page: cut at fixed, page-aligned offsets. */
    static boolean isDatabase(String rel) {
        return rel != null && rel.endsWith(".db") && !rel.contains("/");
    }

    /**
     * Store a tree a being can write into. The vault reads as root and a being decides what is in
     * her tree, so nothing here is read through a symbolic link: each directory is opened relative to
     * the one above it and each file without following a link, and a link, socket or pipe is passed
     * over. A link planted or swapped in mid-walk then fails to open instead of handing the store a
     * file only root may read, which a restore would put back in her home as hers. What her tools
     * refill on their own ({@link #beingCache}) is named in {@code refilled} and not copied.
     */
    private void storeTree(Path dir, String rel, List<Entry> entries, Map<String, Entry> previous,
                           List<String> refilled) throws IOException {
        try (var ds = Files.newDirectoryStream(dir)) {
            if (ds instanceof SecureDirectoryStream<Path> sds) {
                storeTree(sds, rel, entries, previous, refilled, 0);
                return;
            }
        }
        // No directory handles on this system (Windows): no being users there either. Still no links.
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                var r = (rel + "/" + dir.relativize(d).toString().replace('\\', '/')).replaceAll("/$", "");
                if (!d.equals(dir) && beingCache(r)) { refilled.add(r); return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                var r = rel + "/" + dir.relativize(file).toString().replace('\\', '/');
                if (attrs.isRegularFile() && !beingCache(r)) {
                    try { entries.add(store(r, file, previous)); }
                    catch (IOException e) { log.warn("vault: skipped {}: {}", file, e.getMessage()); }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void storeTree(SecureDirectoryStream<Path> dir, String rel, List<Entry> entries, Map<String, Entry> previous,
                           List<String> refilled, int depth) {
        if (depth > 64) { log.warn("vault: {} is nested past 64 levels; the rest is skipped", rel); return; }
        for (var p : dir) {
            var name = p.getFileName();
            var childRel = rel + "/" + name;
            BasicFileAttributes attrs;
            try {
                attrs = dir.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            } catch (IOException e) {
                continue;
            }
            if (attrs.isDirectory()) {
                if (beingCache(childRel)) { refilled.add(childRel); continue; }
                try (var sub = dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    storeTree(sub, childRel, entries, previous, refilled, depth + 1);
                } catch (IOException e) {
                    log.warn("vault: skipped {}: {}", childRel, e.getMessage());
                }
            } else if (attrs.isRegularFile() && !beingCache(childRel) && !"write.lock".equals(name.toString())) {
                var prev = previous == null ? null : previous.get(childRel);
                if (prev != null && prev.size() == attrs.size() && prev.mtime() == attrs.lastModifiedTime().toMillis()
                        && chunksPresent(prev)) {
                    entries.add(prev);
                    continue;
                }
                try (var ch = dir.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                     var in = Channels.newInputStream(ch)) {
                    entries.add(store(childRel, in, attrs.lastModifiedTime().toMillis()));
                } catch (IOException e) {
                    log.warn("vault: skipped {}: {}", childRel, e.getMessage());
                }
            }
        }
    }

    /** The previous copy's entries by path, so an unchanged file costs a stat and not a read. */
    private Map<String, Entry> previousEntries() {
        var out = new HashMap<String, Entry>();
        try {
            latest().ifPresent(m -> { for (var e : m.files()) out.putIfAbsent(e.path(), e); });
        } catch (Exception e) {
            log.debug("vault: previous copy not read: {}", e.toString());
        }
        return out;
    }

    /** {@link #store(String, Path)}, reusing the previous copy's entry when size and mtime are unchanged
     *  and its chunks are still in the store. */
    private Entry store(String rel, Path file, Map<String, Entry> previous) throws IOException {
        var prev = previous == null ? null : previous.get(rel);
        if (prev != null) {
            var attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.size() == prev.size() && attrs.lastModifiedTime().toMillis() == prev.mtime() && chunksPresent(prev)) {
                return prev;
            }
        }
        return store(rel, file);
    }

    private boolean chunksPresent(Entry e) {
        for (var sha : e.chunks()) if (!Files.exists(chunkPath(sha))) return false;
        return true;
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
        writeSidecars(m);
    }

    /**
     * Beside each manifest, two small sealed files: its head, and the names of the chunks it
     * references. A pass of the vault reads these, kilobytes, and never the manifests themselves:
     * reading 157 copies of 80,000 files whole on every pass ran the household node out of memory
     * and ended the vault's timer (2026-10-23). A copy from before this gets its sidecars the
     * first time it is read.
     */
    private void writeSidecars(Manifest m) throws IOException {
        var head = headFile(m.id());
        var tmp = head.resolveSibling(head.getFileName() + ".tmp");
        Files.write(tmp, cipher.seal(JSON.writeValueAsBytes(m.head())));
        Files.move(tmp, head, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        var names = new java.util.TreeSet<String>();
        if (m.files() != null) for (var e : m.files()) if (e != null && e.chunks() != null) names.addAll(e.chunks());
        var chunks = chunksFile(m.id());
        tmp = chunks.resolveSibling(chunks.getFileName() + ".tmp");
        Files.write(tmp, cipher.seal(String.join("\n", names).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.move(tmp, chunks, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // The chunks that belong to the dear indexes alone, so the retention can let a kept copy's
        // index go while its record stays (2026-10-25).
        var dear = new java.util.TreeSet<String>();
        if (m.files() != null) for (var e : m.files()) if (e != null && e.chunks() != null && isDear(e.path())) dear.addAll(e.chunks());
        var dearFile = dearFile(m.id());
        tmp = dearFile.resolveSibling(dearFile.getFileName() + ".tmp");
        Files.write(tmp, cipher.seal(String.join("\n", dear).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.move(tmp, dearFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // The chunks that belong only to what her tools refill (a copy from before the rule holds
        // them): what new copies skip, old copies let go (2026-10-26).
        var skip = new java.util.TreeSet<String>();
        var kept = new HashSet<String>();
        if (m.files() != null) {
            for (var e : m.files()) {
                if (e == null || e.chunks() == null) continue;
                if (isRefill(e.path())) skip.addAll(e.chunks()); else kept.addAll(e.chunks());
            }
        }
        skip.removeAll(kept);
        if (keepRefillsForTests) return;   // a copy from before the rule has no such sidecar; the backfill gives it one
        var skipFile = skipFile(m.id());
        tmp = skipFile.resolveSibling(skipFile.getFileName() + ".tmp");
        Files.write(tmp, cipher.seal(String.join("\n", skip).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.move(tmp, skipFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A path a copy may hold that today's copies skip: refilled by her tools, never hers. */
    static boolean isRefill(String rel) {
        return rel != null && rel.startsWith("beings/") && beingCache(rel);
    }
    private Path skipFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".skip"); }
    private Path leanFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".lean"); }
    /** A copy that let go of what its tools refill. */
    public boolean isLean(String id) { return id != null && Files.isRegularFile(leanFile(id)); }
    private void markLean(String id) {
        try { Files.writeString(leanFile(id), Instant.now().toString()); }
        catch (IOException e) { log.debug("vault: lean mark for {} not written: {}", id, e.getMessage()); }
    }
    /** The refill chunks of a copy, from its sidecar; empty (not known) for a copy the backfill has not reached. */
    private Optional<Set<String>> skipChunksOf(String id) throws IOException {
        var side = skipFile(id);
        if (!Files.isRegularFile(side)) return Optional.empty();
        var text = new String(open(Files.readAllBytes(side), side), java.nio.charset.StandardCharsets.UTF_8);
        var out = new HashSet<String>();
        for (var line : text.split("\n")) if (!line.isBlank()) out.add(line.strip());
        return Optional.of(out);
    }
    private Path headFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".head.json"); }
    private Path chunksFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".chunks"); }
    private Path dearFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".dear"); }
    private Path thinFile(String id) { return vaultDir.resolve("manifests").resolve(id + ".thin"); }

    /** A copy whose index chunks were let go: it restores the record; the index is rebuilt. */
    public boolean isThin(String id) { return id != null && Files.isRegularFile(thinFile(id)); }
    private void markThin(String id) {
        try { Files.writeString(thinFile(id), Instant.now().toString()); }
        catch (IOException e) { log.debug("vault: thin mark for {} not written: {}", id, e.getMessage()); }
    }
    /**
     * The dear chunks of a copy, from its sidecar. A copy from before the sidecar is not read whole
     * here (the pass that read every copy whole ended the vault once); empty means "not known yet",
     * and the caller keeps everything of that copy until {@link #backfillDearSidecars} has reached it.
     */
    private Optional<Set<String>> dearChunksOf(String id) throws IOException {
        var side = dearFile(id);
        if (!Files.isRegularFile(side)) return Optional.empty();
        var text = new String(open(Files.readAllBytes(side), side), java.nio.charset.StandardCharsets.UTF_8);
        var out = new HashSet<String>();
        for (var line : text.split("\n")) if (!line.isBlank()) out.add(line.strip());
        return Optional.of(out);
    }
    /** Give up to {@code max} older copies their dear sidecar: one whole read each, once. */
    int backfillDearSidecars(int max) {
        int done = 0;
        var dir = vaultDir.resolve("manifests");
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            for (var f : s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).sorted().toList()) {
                if (done >= max) break;
                var id = idOf(f);
                if (Files.isRegularFile(dearFile(id)) && Files.isRegularFile(skipFile(id))) continue;
                try { writeSidecars(readManifest(f)); done++; }
                catch (IOException e) { log.debug("vault: dear sidecar for {} not written: {}", id, e.getMessage()); }
            }
        } catch (IOException e) {
            log.debug("vault: dear backfill: {}", e.getMessage());
        }
        return done;
    }
    private static String idOf(Path manifest) {
        var n = manifest.getFileName().toString();
        return n.endsWith(".json") ? n.substring(0, n.length() - ".json".length()) : n;
    }

    private Manifest readManifest(Path f) throws IOException {
        MANIFESTS_READ_WHOLE.incrementAndGet();
        return JSON.readValue(open(Files.readAllBytes(f), f), Manifest.class);
    }
    /** The head of a copy: from its sidecar; a copy without one is read whole, once, and gets its sidecars. */
    private Head readHead(Path f) throws IOException {
        var side = headFile(idOf(f));
        if (Files.isRegularFile(side)) {
            HEADS_READ.incrementAndGet();
            return JSON.readValue(open(Files.readAllBytes(side), side), Head.class);
        }
        var m = readManifest(f);
        try { writeSidecars(m); } catch (IOException e) { log.debug("vault: sidecars for {} not written: {}", m.id(), e.getMessage()); }
        return m.head();
    }
    /** The chunk names a copy references: from its sidecar; a copy without one is read whole, once, and gets its sidecars. */
    private Set<String> chunkNamesOf(String id) throws IOException {
        var side = chunksFile(id);
        if (Files.isRegularFile(side)) {
            var text = new String(open(Files.readAllBytes(side), side), java.nio.charset.StandardCharsets.UTF_8);
            var out = new HashSet<String>();
            for (var line : text.split("\n")) if (!line.isBlank()) out.add(line.strip());
            return out;
        }
        var f = manifestFile(id).orElseThrow(() -> new IOException("no manifest " + id));
        var m = readManifest(f);
        try { writeSidecars(m); } catch (IOException e) { log.debug("vault: sidecars for {} not written: {}", m.id(), e.getMessage()); }
        var out = new HashSet<String>();
        if (m.files() != null) for (var e : m.files()) if (e != null && e.chunks() != null) out.addAll(e.chunks());
        return out;
    }
    /** The one manifest file of a copy, by id; empty when the id is not a plain name or there is no such copy. */
    private Optional<Path> manifestFile(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) return Optional.empty();
        var f = vaultDir.resolve("manifests").resolve(id + ".json");
        return Files.isRegularFile(f) ? Optional.of(f) : Optional.empty();
    }

    /**
     * Files written before sealing are plain; seal them in place, once, so the whole store
     * is unreadable without the key. Cheap when there is nothing to do: four bytes per file.
     */
    synchronized int resealPlain() {
        int n = 0;
        plainLeft = false;
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
                        plainLeft = true;
                        log.warn("vault: could not seal {}: {}", f, e.getMessage());
                    }
                }
            } catch (IOException e) {
                plainLeft = true;
                log.warn("vault: reseal walk failed: {}", e.getMessage());
            }
        }
        if (n > 0) log.info("Vault: sealed {} file(s) written before the key existed", n);
        return n;
    }

    /**
     * Every manifest, whole, newest first. This holds every copy's file list at once: the household
     * node's store had 157 copies of 80,000 files each, and reading them all to find the newest, on
     * every pass, filled a 2 GB heap and ran full collections every fifteen minutes (2026-10-22).
     * The listing, the newest copy and the retention now go through {@link #heads()}; this is for
     * the few callers that need every file of every copy.
     */
    public List<Manifest> list() {
        var out = new ArrayList<Manifest>();
        var dir = vaultDir.resolve("manifests");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (var f : s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).toList()) {
                try { out.add(readManifest(f)); }
                catch (IOException e) { log.warn("vault: unreadable manifest {}: {}", f, e.getMessage()); }
            }
        } catch (IOException e) {
            log.warn("vault: could not list manifests: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Manifest::at).reversed());
        return out;
    }
    /** Every copy's head, newest first; no file list is held. */
    public List<Head> heads() {
        var out = new ArrayList<Head>();
        var dir = vaultDir.resolve("manifests");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (var f : s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).toList()) {
                try { out.add(readHead(f)); }
                catch (IOException e) { log.warn("vault: unreadable manifest {}: {}", f, e.getMessage()); }
            }
        } catch (IOException e) {
            log.warn("vault: could not list manifests: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Head::at).reversed());
        return out;
    }
    /** The newest copy, whole: one manifest read, after the heads. */
    public Optional<Manifest> latest() {
        return heads().stream().findFirst().flatMap(h -> find(h.id()));
    }
    /** One copy, whole, by id: its own file and no other. */
    public Optional<Manifest> find(String id) {
        if (id == null || id.isBlank() || "latest".equals(id)) return latest();
        var f = manifestFile(id);
        if (f.isEmpty()) return Optional.empty();
        try {
            return Optional.of(readManifest(f.get()));
        } catch (IOException e) {
            log.warn("vault: unreadable manifest {}: {}", f.get(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── restore ──

    /**
     * The drill's rebuild: entries under the {@code verifyOnly} prefixes are read and hash-checked chunk
     * by chunk and not written out. The knowledge index is 93 GB on the household node; the drill has to
     * prove the copy comes up whole, not make a second copy of it on the disk to do so.
     */
    public List<String> restoreTo(Manifest m, Path target, List<String> verifyOnly) {
        var failed = new ArrayList<String>();
        var written = new ArrayList<Entry>();
        for (var e : m.files()) {
            boolean verify = verifyOnly != null && verifyOnly.stream().anyMatch(p -> e.path().startsWith(p + "/"));
            if (!verify) { written.add(e); continue; }
            try {
                long seen = 0;
                for (var sha : e.chunks()) {
                    var bytes = open(Files.readAllBytes(chunkPath(sha)), chunkPath(sha));
                    if (!sha256(bytes, bytes.length).equals(sha)) throw new IOException("chunk " + sha + " does not match its name");
                    seen += bytes.length;
                }
                if (seen != e.size()) throw new IOException("size " + seen + " != " + e.size());
            } catch (IOException ex) {
                failed.add(e.path() + ": " + ex.getMessage());
            }
        }
        failed.addAll(restoreTo(new Manifest(m.id(), m.at(), m.reason(), m.keep(), List.copyOf(written), m.classes(), m.drill()), target));
        return failed;
    }

    /**
     * Reassemble every file of a manifest under {@code target}, verifying each chunk's hash.
     * Returns the files that could not be rebuilt; empty means the copy is whole.
     */
    public List<String> restoreTo(Manifest m, Path target) {
        var failed = new ArrayList<String>();
        boolean thin = isThin(m.id());
        boolean saidThin = false;
        boolean saidRefill = false;
        for (var e : m.files()) {
            if (isRefill(e.path())) {
                // Today's copies do not hold these; an older copy's are let go. Her tools make them again.
                if (!saidRefill) { log.info("vault: copy {}: what her tools refill (environments, caches) is not restored; they make it again", m.id()); saidRefill = true; }
                continue;
            }
            if (thin && isDear(e.path())) {
                // This copy gave its index up to make room; the record is whole and the index is
                // rebuilt on the first start after a restore.
                if (!saidThin) { log.info("vault: copy {} holds the record only; the search index is not in it and is rebuilt on first start", m.id()); saidThin = true; }
                continue;
            }
            var dest = target.resolve(e.path()).normalize();
            try {
                if (!dest.startsWith(target.normalize())) throw new IOException("path leaves the restore directory");
                Files.createDirectories(dest.getParent());
                var tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
                long written = 0;
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    for (var sha : e.chunks()) {
                        var bytes = open(Files.readAllBytes(chunkPath(sha)), chunkPath(sha));
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
        return drill(false);
    }

    /**
     * The record alone, every week: the newest copy's database, identity and souls come up in
     * scratch, without the hours a hash pass over the index takes. The question that matters,
     * "can she be restored", is answered often; the index is checked on the monthly drill.
     */
    public synchronized Optional<Drill> recordDrill() {
        return drill(true);
    }

    static final String RECORD_DRILL = "record: ";

    private Optional<Drill> drill(boolean recordOnly) {
        var m = latest().orElse(null);
        if (m == null) return Optional.empty();
        var scratch = vaultDir.resolve(".drill");
        deleteTree(scratch);
        var detail = new StringBuilder(recordOnly ? RECORD_DRILL : "");
        boolean ok = true;
        try {
            Files.createDirectories(scratch);
            var toRestore = recordOnly
                ? new Manifest(m.id(), m.at(), m.reason(), m.keep(),
                    m.files().stream().filter(e -> !isDear(e.path())).toList(), m.classes(), m.drill())
                : m;
            var failed = restoreTo(toRestore, scratch, DEAR_DIRS);
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
        log.info("Vault {}drill {}: {} ({})", recordOnly ? "record " : "", ok ? "PASSED" : "FAILED", m.id(), d.detail());
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
        return prune(Instant.now(), false);
    }

    synchronized int prune(Instant now) {
        return prune(now, false);
    }

    /**
     * The tiers decide which copies stay whole. A flagged copy outside the tiers stays forever, but
     * for her record alone: its index chunks go with the tiers like any other copy's. The 120
     * "before an update" copies on the household node each pinned the 127 GB index as it stood that
     * minute, for good; her record in them is a few gigabytes (2026-10-25). With
     * {@code indexOnlyInNewest} (the disk is under the floor) only the newest tiered copy keeps
     * its index.
     */
    synchronized int prune(Instant now, boolean indexOnlyInNewest) {
        var all = heads();
        var tier = new HashSet<String>();      // whole, by age
        var flagged = new HashSet<String>();   // forever, for the record
        var newestPerHour = new TreeMap<String, Head>();
        var newestPerDay = new TreeMap<String, Head>();
        var newestPerWeek = new TreeMap<String, Head>();
        var newestPerMonth = new TreeMap<String, Head>();
        Head newest = null;
        for (var m : all) {
            if (newest == null || m.at().isAfter(newest.at())) newest = m;
            if (m.keep()) flagged.add(m.id());
            var age = Duration.between(m.at(), now);
            var z = m.at().atZone(ZoneOffset.UTC);
            if (age.compareTo(Duration.ofHours(2)) <= 0) { tier.add(m.id()); continue; }
            if (age.compareTo(Duration.ofDays(1)) <= 0) { newestPerHour.merge(z.toLocalDate() + "T" + z.getHour(), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(7)) <= 0) { newestPerDay.merge(z.toLocalDate().toString(), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(35)) <= 0) { newestPerWeek.merge(z.getYear() + "-W" + z.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), m, Vault::newer); continue; }
            if (age.compareTo(Duration.ofDays(400)) <= 0) { newestPerMonth.merge(z.getYear() + "-" + z.getMonthValue(), m, Vault::newer); }
        }
        for (var m : newestPerHour.values()) tier.add(m.id());
        for (var m : newestPerDay.values()) tier.add(m.id());
        for (var m : newestPerWeek.values()) tier.add(m.id());
        for (var m : newestPerMonth.values()) tier.add(m.id());
        // Which copies keep their index: the tiers, or under the floor the newest alone.
        var indexKept = new HashSet<String>(indexOnlyInNewest && newest != null ? Set.of(newest.id()) : tier);
        var keep = new HashSet<String>(tier);
        keep.addAll(flagged);
        backfillDearSidecars(3);   // older copies learn which of their chunks are the index, a few per pass
        int removed = 0;
        int thinned = 0;
        int leaned = 0;
        var referenced = new HashSet<String>();
        for (var m : all) {
            if (keep.contains(m.id())) {
                // A kept copy's chunk names, from its sidecar; never its file list.
                try {
                    var names = chunkNamesOf(m.id());
                    // What her tools refill is let go of in every copy, whole or thin: 7 GB of the
                    // coding helper's environments sat in the copies from before the rule (2026-10-26).
                    var skip = skipChunksOf(m.id());
                    if (skip.isPresent() && !skip.get().isEmpty()) {
                        names.removeAll(skip.get());
                        if (!isLean(m.id())) { markLean(m.id()); leaned++; }
                    }
                    if (!indexKept.contains(m.id())) {
                        var dear = dearChunksOf(m.id());
                        if (dear.isPresent()) {
                            names.removeAll(dear.get());
                            if (!dear.get().isEmpty() && !isThin(m.id())) { markThin(m.id()); thinned++; }
                        }   // not known yet: everything of this copy stays until its sidecar exists
                    }
                    referenced.addAll(names);
                } catch (IOException e) {
                    log.warn("vault: kept copy {} not read for its chunks; nothing is swept: {}", m.id(), e.getMessage());
                    return removed;   // a sweep that cannot see a kept copy's chunks must not run
                }
                continue;
            }
            try {
                Files.deleteIfExists(vaultDir.resolve("manifests").resolve(m.id() + ".json"));
                Files.deleteIfExists(headFile(m.id()));
                Files.deleteIfExists(chunksFile(m.id()));
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
        if (removed > 0 || thinned > 0 || leaned > 0) {
            log.info("Vault: pruned {} manifest(s); {} kept{}{}{}", removed, keep.size(),
                thinned > 0 ? ", " + thinned + " older kept copies now hold the record only" : "",
                leaned > 0 ? ", " + leaned + " older copies let go of what their tools refill" : "",
                indexOnlyInNewest ? " (under the floor: the index stays in the newest copy alone)" : "");
        }
        noteSize();
        return removed;
    }

    // ── the disk ──

    /** The vault leaves at least this much free on its disk; 0 turns the floor off. */
    public Vault withFloor(long bytes) { this.floorBytes = Math.max(0, bytes); return this; }
    public long floorBytes() { return floorBytes; }
    public long freeBytes() { return freeSpace.getAsLong(); }

    private long usableSpace() {
        try {
            var probe = Files.isDirectory(vaultDir) ? vaultDir : vaultDir.getParent();
            return Files.getFileStore(probe).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE;   // unknown is not low
        }
    }

    /** One line per pass: when, how big the store is, how much room is left — for the rate on the status page. */
    private void noteSize() {
        try {
            var line = Instant.now().toEpochMilli() + " " + storeBytes() + " " + freeSpace.getAsLong() + "\n";
            Files.writeString(vaultDir.resolve("size.log"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("vault: size not noted: {}", e.getMessage());
        }
    }

    /** Bytes the store grows a day, from the last week of size notes; 0 when there is not a day of them. */
    long growthPerDay() {
        var f = vaultDir.resolve("size.log");
        if (!Files.isRegularFile(f)) return 0;
        try {
            long first = -1, firstAt = 0, last = -1, lastAt = 0;
            long since = Instant.now().minus(Duration.ofDays(7)).toEpochMilli();
            for (var line : Files.readAllLines(f)) {
                var parts = line.trim().split(" ");
                if (parts.length < 3) continue;
                long at = Long.parseLong(parts[0]), size = Long.parseLong(parts[1]);
                if (at < since) continue;
                if (first < 0) { first = size; firstAt = at; }
                last = size; lastAt = at;
            }
            if (first < 0 || lastAt - firstAt < Duration.ofHours(12).toMillis()) return 0;
            return Math.max(0, (last - first) * Duration.ofDays(1).toMillis() / (lastAt - firstAt));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * A store at the old default place, {@code <data>/vault-store}, when the vault lives elsewhere:
     * copies nothing reads, on a disk they were moved off. Named on the status page; removed by
     * {@code wyrd vault forget}. The household node carried 145 GB of one for a month (2026-10-25).
     */
    public Optional<Map<String, Object>> orphanStore() {
        var old = dataDir.resolve("vault-store");
        try {
            if (Files.isSameFile(old, vaultDir)) return Optional.empty();
        } catch (IOException e) {
            if (old.toAbsolutePath().normalize().equals(vaultDir.toAbsolutePath().normalize())) return Optional.empty();
        }
        var manifests = old.resolve("manifests");
        if (!Files.isDirectory(manifests)) return Optional.empty();
        long copies = 0, bytes = 0;
        try (var s = Files.list(manifests)) {
            copies = s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).count();
        } catch (IOException ignored) { /* partial is fine */ }
        try {
            final long[] total = {0};
            Files.walkFileTree(old, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) { total[0] += attrs.size(); return FileVisitResult.CONTINUE; }
            });
            bytes = total[0];
        } catch (IOException ignored) { /* partial is fine */ }
        var out = new LinkedHashMap<String, Object>();
        out.put("dir", old.toString());
        out.put("copies", copies);
        out.put("bytes", bytes);
        return Optional.of(out);
    }

    private static Head newer(Head a, Head b) {
        return a.at().isAfter(b.at()) ? a : b;
    }

    // ── status ──

    public Map<String, Object> status() {
        var out = new LinkedHashMap<String, Object>();
        var all = heads();
        var newest = all.isEmpty() ? Optional.<Manifest>empty() : find(all.get(0).id());
        out.put("dir", vaultDir.toString());
        out.put("copies", all.size());
        out.put("kept", all.stream().filter(Head::keep).count());
        out.put("latest", all.isEmpty() ? null : all.get(0).at().toString());
        out.put("latestId", all.isEmpty() ? null : all.get(0).id());
        out.put("latestBytes", newest.map(Manifest::bytes).orElse(0L));
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
        newest.ifPresent(m -> out.put("unclassified", m.classes() == null ? List.of() : m.classes().getOrDefault("unclassified", List.of())));
        // The disk: what is left, the floor, how fast the store grows and so how long that is.
        long free = freeSpace.getAsLong();
        out.put("freeBytes", free == Long.MAX_VALUE ? null : free);
        out.put("floorBytes", floorBytes);
        long growth = growthPerDay();
        out.put("growthPerDayBytes", growth);
        out.put("daysLeft", growth > 0 && free != Long.MAX_VALUE ? Math.max(0, (free - floorBytes) / growth) : null);
        out.put("thinKept", all.stream().filter(h -> h.keep() && isThin(h.id())).count());
        orphanStore().ifPresent(o -> out.put("orphanStore", o));
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
        return heads().stream().filter(m -> m.drill() != null && !m.drill().detail().startsWith(RECORD_DRILL)).map(m -> m.drill().at())
            .max(Comparator.naturalOrder())
            .map(t -> Duration.between(t, Instant.now()).compareTo(every) > 0)
            .orElse(true);
    }

    /** True when no drill of either kind is younger than {@code every}. */
    public boolean recordDrillDue(Duration every) {
        return heads().stream().filter(m -> m.drill() != null).map(m -> m.drill().at())
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
