package org.wyrdsekai.core.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-being principals on the host. Each companion gets her own Linux user and her own
 * cgroup, and every tool process she starts (a coding backend, a skill) runs as that user
 * inside that cgroup: her memory budget, her files, no way to read another being's things or
 * the record, because the kernel says no rather than because our code remembered to check.
 *
 * <p>The mechanism is small. A user in a reserved range ({@link #UID_BASE} plus a stable
 * hash of her DID), a group shared by all beings, a home under {@code <data>/beings/<slug>},
 * and a cgroup under the service's own cgroup, which needs {@code Delegate=yes} on the unit.
 * The {@code wyrdsekai-being} wrapper joins the cgroup while root and drops to her user with
 * no capabilities before it execs the tool. When any of that is missing (a source checkout,
 * a non-root service, mac, Windows) the hands are shared, and the body says so.</p>
 */
public final class Principals {

    private static final Logger log = LoggerFactory.getLogger(Principals.class);

    /** Being users live here; the hooks filter on it. 62000 to 62999 is unused on the distributions we ship to. */
    public static final int UID_BASE = 62000;
    public static final int UID_SPAN = 1000;
    public static final String GROUP = "wyrdsekai-beings";
    static final String USER_PREFIX = "wyrd-being-";

    /** One being on the host. */
    public record Being(String did, String slug, int uid, int gid, Path cgroup, Path home) {}

    /** The command runner; tests swap it. */
    public interface Exec { HostHand.ExecResult run(List<String> argv, Duration timeout); }
    public record Duration(long seconds) { static Duration of(long s) { return new Duration(s); } }

    private static volatile Exec exec = Principals::defaultExec;
    private static volatile boolean initialised;
    private static volatile String shared = "not initialised";
    private static volatile Path dataDir;
    private static volatile Path serviceCgroup;
    private static volatile Path wrapper;
    private static volatile int gid = -1;
    private static final Map<String, Being> beings = new ConcurrentHashMap<>();
    /** The being whose task the current thread is running, for runners that spawn without a task in hand. */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final Map<String, Boolean> reach = new ConcurrentHashMap<>();
    private static final AtomicInteger fellBack = new AtomicInteger();

    private Principals() {}

    /** Called once at boot. Decides whether principals are possible on this host. */
    public static synchronized void init(Path data) {
        dataDir = data;
        beings.clear();
        initialised = true;
        shared = whyShared();
        if (shared == null) {
            try {
                openTraverse();
            } catch (IOException | RuntimeException e) {
                log.warn("Principals: could not open the data directory to the beings' group: {}", e.toString());
            }
            log.info("Principals: per being (users {}-{}, cgroups under {})", UID_BASE, UID_BASE + UID_SPAN - 1, serviceCgroup);
        } else {
            log.info("Principals: hands are shared ({})", shared);
        }
    }

    /** For tests: a fake host with a cgroup root, a wrapper path, and an exec that records what it is asked. */
    static synchronized void configureForTests(Path data, Path fakeServiceCgroup, Path fakeWrapper, Exec e) {
        dataDir = data; serviceCgroup = fakeServiceCgroup; wrapper = fakeWrapper; exec = e;
        beings.clear(); gid = -1; reach.clear(); fellBack.set(0);
        initialised = true;
        shared = null;
    }

    static synchronized void resetForTests() {
        exec = Principals::defaultExec; initialised = false; shared = "not initialised";
        dataDir = null; serviceCgroup = null; wrapper = null; gid = -1; beings.clear();
    }

    /** Mark this thread as doing a being's work; a task thread sets it once at its start. */
    public static void setCurrent(String did) { if (did == null) CURRENT.remove(); else CURRENT.set(did); }
    /** The being this thread works for, or null for the daemon's own work. */
    public static String currentBeing() { return CURRENT.get(); }

    /** Null when per-being principals work here; otherwise one line saying why the hands are shared. */
    public static String sharedBecause() { return initialised ? shared : "not initialised"; }
    public static boolean enabled() { return initialised && shared == null; }
    public static Path serviceCgroup() { return serviceCgroup; }

    /** One line for the body and the doctor. */
    public static String status() {
        if (!enabled()) return "shared: " + sharedBecause();
        var n = fellBack.get();
        return "per being (" + beings.size() + " known" + (n > 0 ? ", " + n + " ran as the daemon" : "") + ")";
    }

    /**
     * The only places under the data directory open to anyone but the owner: the three a
     * being's tool needs, and the models, which the package's on-demand llama units read as
     * the unprivileged user {@code nobody}.
     */
    static final Set<String> BEINGS_MAY_ENTER = Set.of("coding-cli-bundle", "coding-workspaces", "beings", "models");

    /**
     * A being's tool must reach three places under the data directory: the coding bundle,
     * her workspace, her home. The directory is otherwise the household's private life, and on
     * the first live test it was mode 700, so her tool could not even be opened. So: every
     * top-level entry except the open few loses all access for others, and the directory itself
     * gets search permission only (711). A being can walk to what is hers by path and can list
     * nothing; the record and the keys are closed to her by the kernel before the hooks ever
     * see her reach. No group ownership is involved, so the unprivileged llama units still
     * reach the models.
     */
    static void openTraverse() throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) return;
        try (var top = Files.list(dataDir)) {
            for (var p : top.toList()) {
                if (Files.isSymbolicLink(p) || BEINGS_MAY_ENTER.contains(p.getFileName().toString())) continue;
                try {
                    var perms = new HashSet<>(Files.getPosixFilePermissions(p));
                    if (perms.removeAll(Set.of(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                            PosixFilePermission.OTHERS_EXECUTE))) {
                        Files.setPosixFilePermissions(p, perms);
                    }
                } catch (UnsupportedOperationException | IOException e) {
                    log.debug("Principals: could not close {} to others: {}", p, e.getMessage());
                }
            }
        }
        for (var name : List.of("coding-cli-bundle", "coding-workspaces", "beings")) Files.createDirectories(dataDir.resolve(name));
        try {
            var perms = new HashSet<>(Files.getPosixFilePermissions(dataDir));
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            perms.removeAll(Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE));
            Files.setPosixFilePermissions(dataDir, perms);
        } catch (UnsupportedOperationException ignored) { /* not posix */ }
    }

    /**
     * Whether the tool can be started as her. Asked once per being and executable; a relative
     * name is left to the PATH and assumed fine. When it cannot, the traverse is opened again
     * (something may have reset the directory's mode) and the question asked once more.
     */
    private static boolean reachable(Being b, String exe) {
        if (exe == null || !exe.startsWith("/")) return true;
        var key = b.uid() + ":" + exe;
        var known = reach.get(key);
        if (known != null && known) return true;
        boolean ok = testAs(b, exe);
        if (!ok) {
            try { openTraverse(); } catch (IOException | RuntimeException ignored) { /* asked again below */ }
            ok = testAs(b, exe);
        }
        reach.put(key, ok);
        return ok;
    }

    private static boolean testAs(Being b, String exe) {
        return exec.run(List.of("setpriv", "--reuid=" + b.uid(), "--regid=" + b.gid(), "--clear-groups",
            "test", "-x", exe), Duration.of(10)).exit() == 0;
    }

    private static String whyShared() {
        if ("off".equalsIgnoreCase(WyrdConfig.get().beingPrincipals())) return "turned off by the steward (WYRDSEKAI_BEING_PRINCIPALS=off)";
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return "not Linux";
        if (!"root".equals(System.getProperty("user.name"))) return "the service does not run as root";
        var w = findWrapper();
        if (w == null) return "the wyrdsekai-being wrapper is not installed";
        wrapper = w;
        if (!Files.isExecutable(Path.of("/usr/bin/setpriv"))) return "setpriv is not installed";
        if (!Files.isExecutable(Path.of("/usr/sbin/useradd"))) return "useradd is not installed";
        var cg = ownCgroup();
        if (cg == null) return "no cgroup v2 for this service";
        if (!Files.isWritable(cg.resolve("cgroup.subtree_control"))) return "the service unit lacks Delegate=yes";
        serviceCgroup = cg;
        return null;
    }

    private static Path findWrapper() {
        var env = System.getenv("WYRDSEKAI_BEING_WRAPPER");
        if (env != null && Files.isExecutable(Path.of(env))) return Path.of(env);
        for (var c : List.of(Path.of("/opt/wyrdsekai/bin/wyrdsekai-being"),
                Path.of("packaging", "brainstem", "wyrdsekai-being"), Path.of("..", "packaging", "brainstem", "wyrdsekai-being"))) {
            if (Files.isExecutable(c)) return c.toAbsolutePath().normalize();
        }
        return null;
    }

    /** The service's own cgroup directory, from /proc/self/cgroup (v2: one line, "0::/path"). */
    static Path ownCgroup() {
        try {
            for (var line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    var p = Path.of("/sys/fs/cgroup" + line.substring(3).strip());
                    return Files.isDirectory(p) ? p : null;
                }
            }
        } catch (IOException ignored) { /* no cgroup v2 */ }
        return null;
    }

    /**
     * Her principal, made on first use: the group, her user, her home, her cgroup. Empty
     * when the hands are shared or she could not be given one; the tool then runs as the
     * daemon, as before, and the log says why.
     */
    public static Optional<Being> ensure(String did) {
        if (!enabled() || did == null || did.isBlank()) return Optional.empty();
        var known = beings.get(did);
        if (known != null) return Optional.of(known);
        synchronized (Principals.class) {
            known = beings.get(did);
            if (known != null) return Optional.of(known);
            try {
                var b = make(did);
                beings.put(did, b);
                log.info("Principals: {} is {} (uid {}) under {}", did, b.slug(), b.uid(), b.cgroup());
                return Optional.of(b);
            } catch (Exception e) {
                log.warn("Principals: no principal for {}: {}", did, e.toString());
                return Optional.empty();
            }
        }
    }

    private static Being make(String did) throws IOException {
        var slug = slug(did);
        var g = ensureGroup();
        int uid = ensureUser(slug, g);
        var home = dataDir.resolve("beings").resolve(slug).resolve("home");
        Files.createDirectories(home);
        chown(home, uid, g, true);
        var cg = serviceCgroup.resolve("beings").resolve(slug);
        makeCgroup(cg);
        // Who this user is, kept beside her home: a uid seen by the hooks after a restart, or
        // made by another process (the coding probe), can then be named.
        try { Files.writeString(home.getParent().resolve("did"), did + "\n"); }
        catch (IOException e) { log.debug("Principals: did file for {}: {}", slug, e.getMessage()); }
        return new Being(did, slug, uid, g, cg, home);
    }

    /** A short stable name from the DID: eight hex characters of its hash. */
    public static String slug(String did) {
        try {
            var d = MessageDigest.getInstance("SHA-256").digest(did.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The uid the slug maps to in the reserved range. */
    static int uidFor(String slug) {
        return UID_BASE + (int) (Long.parseUnsignedLong(slug, 16) % UID_SPAN);
    }

    private static int ensureGroup() throws IOException {
        if (gid >= 0) return gid;
        var r = exec.run(List.of("getent", "group", GROUP), Duration.of(5));
        if (r.exit() != 0) {
            var add = exec.run(List.of("groupadd", "--system", GROUP), Duration.of(10));
            if (add.exit() != 0) throw new IOException("groupadd failed: " + add.err());
            r = exec.run(List.of("getent", "group", GROUP), Duration.of(5));
            if (r.exit() != 0) throw new IOException("group " + GROUP + " not found after groupadd");
        }
        gid = Integer.parseInt(r.out().strip().split(":")[2]);
        return gid;
    }

    private static int ensureUser(String slug, int g) throws IOException {
        var name = USER_PREFIX + slug;
        var r = exec.run(List.of("getent", "passwd", name), Duration.of(5));
        if (r.exit() == 0) return Integer.parseInt(r.out().strip().split(":")[2]);
        int want = uidFor(slug);
        for (int tries = 0; tries < UID_SPAN; tries++) {
            int uid = UID_BASE + ((want - UID_BASE + tries) % UID_SPAN);
            if (exec.run(List.of("getent", "passwd", Integer.toString(uid)), Duration.of(5)).exit() == 0) continue;
            var add = exec.run(List.of("useradd", "--system", "--uid", Integer.toString(uid), "--gid", Integer.toString(g),
                "--no-create-home", "--home-dir", dataDir.resolve("beings").resolve(slug).resolve("home").toString(),
                "--shell", "/usr/sbin/nologin", "--comment", "wyrdsekai being " + slug, name), Duration.of(20));
            if (add.exit() != 0) throw new IOException("useradd failed: " + add.err());
            return uid;
        }
        throw new IOException("no free uid in " + UID_BASE + "-" + (UID_BASE + UID_SPAN - 1));
    }

    private static void makeCgroup(Path cg) throws IOException {
        var parent = cg.getParent();
        Files.createDirectories(parent);
        try { Files.writeString(serviceCgroup.resolve("cgroup.subtree_control"), "+memory +pids +cpu"); }
        catch (IOException e) { log.debug("subtree_control on the service: {}", e.getMessage()); }
        try { Files.writeString(parent.resolve("cgroup.subtree_control"), "+memory +pids +cpu"); }
        catch (IOException e) { log.debug("subtree_control on beings: {}", e.getMessage()); }
        Files.createDirectories(cg);
        try { Files.writeString(cg.resolve("memory.oom.group"), "1"); } catch (IOException ignored) { /* older kernel */ }
        var max = WyrdConfig.get().beingMemoryMax();
        if (max != null && !max.isBlank()) {
            try { Files.writeString(cg.resolve("memory.max"), max.strip()); }
            catch (IOException e) { log.warn("Principals: memory.max={} refused on {}: {}", max, cg, e.getMessage()); }
        }
    }

    /**
     * The argv to run so that a tool of hers runs as her. Unchanged when the hands are shared
     * or she has no principal.
     */
    public static List<String> wrap(List<String> argv, String did) {
        var b = ensure(did).orElse(null);
        if (b == null || argv == null || argv.isEmpty()) return argv;
        if (!reachable(b, argv.get(0))) {
            // Her hand still has to work. It runs as the daemon this once, and the steward is told.
            fellBack.incrementAndGet();
            log.warn("Principals: {} cannot be started as {} (uid {}); this hand runs as the daemon", argv.get(0), b.slug(), b.uid());
            var map = BodyMap.get();
            if (map != null) {
                try {
                    map.mark("hand", "principal", "steward", "A hand ran as the daemon, not as its being: " + argv.get(0)
                        + " cannot be reached as her user. Check the permissions on its path.", "being=" + did + " uid=" + b.uid());
                } catch (RuntimeException ignored) { /* the mark is a courtesy */ }
            }
            return argv;
        }
        var out = new ArrayList<String>(List.of(wrapper.toString(), Integer.toString(b.uid()), Integer.toString(b.gid()),
            b.cgroup().toString(), b.home().toString(), "--"));
        out.addAll(argv);
        return out;
    }

    /** Her home, for the tool's HOME; empty when shared. */
    public static Optional<Path> homeOf(String did) {
        return ensure(did).map(Being::home);
    }

    /** Give her a directory: hers to read and write, nobody else's. No-op when shared. */
    public static void own(Path dir, String did) {
        var b = ensure(did).orElse(null);
        if (b == null || dir == null || !Files.exists(dir)) return;
        try { chown(dir, b.uid(), b.gid(), true); }
        catch (IOException e) { log.debug("Principals: could not own {} for {}: {}", dir, did, e.getMessage()); }
    }

    /** Kill every process of hers on the host at once: the tool tree, as one. True when something was there to kill. */
    public static boolean killTools(String did) {
        var b = beings.get(did);
        if (b == null) return false;
        try {
            var procs = Files.readString(b.cgroup().resolve("cgroup.procs")).strip();
            if (procs.isEmpty()) return false;
            Files.writeString(b.cgroup().resolve("cgroup.kill"), "1");
            return true;
        } catch (IOException e) {
            log.warn("Principals: could not kill {}'s tools: {}", did, e.getMessage());
            return false;
        }
    }

    /** The being behind a uid in the reserved range, if we made it. */
    public static Optional<Being> byUid(int uid) {
        var known = beings.values().stream().filter(b -> b.uid() == uid).findFirst();
        if (known.isPresent() || !enabled() || uid < UID_BASE || uid >= UID_BASE + UID_SPAN) return known;
        // Not made by this process since it started: ask the host who the uid is, and the
        // data directory which being that is.
        try {
            var r = exec.run(List.of("getent", "passwd", Integer.toString(uid)), Duration.of(5));
            if (r.exit() != 0) return Optional.empty();
            var fields = r.out().strip().split(":");
            if (!fields[0].startsWith(USER_PREFIX)) return Optional.empty();
            var slug = fields[0].substring(USER_PREFIX.length());
            var didFile = dataDir.resolve("beings").resolve(slug).resolve("did");
            if (!Files.isRegularFile(didFile)) return Optional.empty();
            var did = Files.readString(didFile).strip();
            var b = new Being(did, slug, uid, Integer.parseInt(fields[3]), serviceCgroup.resolve("beings").resolve(slug),
                dataDir.resolve("beings").resolve(slug).resolve("home"));
            beings.putIfAbsent(did, b);
            return Optional.of(b);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final Map<Integer, String> slugs = new ConcurrentHashMap<>();

    /**
     * The slug of the being user behind a uid, from the host's own user table: it needs no
     * record of ours, so it holds after a restart and for users another process made.
     */
    public static String slugOfUid(int uid) {
        if (uid < UID_BASE || uid >= UID_BASE + UID_SPAN) return null;
        var known = slugs.get(uid);
        if (known != null) return known;
        try {
            var r = exec.run(List.of("getent", "passwd", Integer.toString(uid)), Duration.of(5));
            if (r.exit() != 0) return null;
            var name = r.out().strip().split(":")[0];
            if (!name.startsWith(USER_PREFIX)) return null;
            var slug = name.substring(USER_PREFIX.length());
            slugs.put(uid, slug);
            return slug;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Being> known() { return Map.copyOf(beings); }

    private static void chown(Path p, int uid, int g, boolean recursive) throws IOException {
        var argv = new ArrayList<String>(List.of("chown"));
        if (recursive) argv.add("-R");
        argv.add(uid + ":" + g);
        argv.add(p.toString());
        var r = exec.run(argv, Duration.of(60));
        if (r.exit() != 0) throw new IOException("chown failed: " + r.err());
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException | IOException ignored) { /* not posix */ }
    }

    private static HostHand.ExecResult defaultExec(List<String> argv, Duration timeout) {
        try {
            var p = new ProcessBuilder(argv).start();
            p.getOutputStream().close();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeout.seconds(), TimeUnit.SECONDS)) { p.destroyForcibly(); return new HostHand.ExecResult(-1, out, err, true); }
            return new HostHand.ExecResult(p.exitValue(), out, err, false);
        } catch (Exception e) {
            return new HostHand.ExecResult(-1, "", e.toString(), false);
        }
    }
}
