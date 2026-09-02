package org.wyrdsekai.core.coding;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a coding backend's executable on this machine.
 *
 * <p>Every backend used to carry its own copy of this, and every copy looked in
 * exactly two bundle directories before giving up and handing the bare name to
 * PATH. That is not enough on a real node, and the way it fails is the worst
 * kind: a backend registers only when its binary is reachable and healthy, so a
 * binary we cannot see makes the backend simply NOT APPEAR. Nothing errors,
 * nothing logs a reason, and the operator is left comparing a config that looks
 * right against a chain that does not contain it.</p>
 *
 * <p>That happened for real. CodeZaiku's own installer defaults to a per-user
 * prefix ({@code ~/.local}), while the wyrdsekai service runs from systemd with
 * {@code HOME=/root} and a deliberately short PATH. The tool was installed, the
 * operator had run the documented command, and we still could not see it —
 * because we were looking in two places it had no reason to be.</p>
 *
 * <p>So look where installers actually put things, in order of how deliberate
 * the placement was:</p>
 *
 * <ol>
 *   <li>the node's bundle directory — an operator putting a binary HERE means
 *       "use this one", so it outranks everything;</li>
 *   <li>system prefixes ({@code /usr/local/bin}, {@code /opt/<name>/bin}) — a
 *       root install, shared by every user on the box;</li>
 *   <li>the service account's own {@code ~/.local/bin};</li>
 *   <li>the DATA DIRECTORY OWNER's {@code ~/.local/bin} — on a household node
 *       the person who owns the household's data is the person who installed
 *       its tools, and under systemd that is exactly the home the service does
 *       not have;</li>
 *   <li>the bare name, left to PATH, as before.</li>
 * </ol>
 *
 * <p>A candidate is accepted only if it is a regular executable file and not
 * world-writable — we are choosing something to execute, and a directory anyone
 * can write to is not a place to pick one up from.</p>
 */
public final class BackendExecutableResolver {

    private static final Logger log = LoggerFactory.getLogger(BackendExecutableResolver.class);

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private BackendExecutableResolver() { }

    /**
     * The path to run for {@code name}, or {@code name} itself when nothing was
     * found and PATH is the remaining hope.
     */
    public static String resolve(String name) {
        for (var c : candidates(name)) {
            if (usable(c)) {
                if (!c.toString().equals(name)) {
                    log.info("coding backend '{}' resolved to {}", name, c);
                }
                return c.toString();
            }
        }
        if (WINDOWS) {
            // Last resort before the bare name: an npm-installed tool on
            // Windows is a `.cmd` shim (pi -> pi.cmd), and CreateProcess will
            // not exec a bare name for it the way a POSIX shell resolves one.
            // Walk PATH ourselves for the suffixed launchers.
            var pathVar = System.getenv("PATH");
            if (pathVar != null) {
                for (var dir : pathVar.split(File.pathSeparator)) {
                    if (dir.isBlank()) continue;
                    for (var ext : new String[] {".exe", ".cmd", ".bat"}) {
                        var cand = Path.of(dir, name + ext);
                        if (Files.isRegularFile(cand)) {
                            log.info("coding backend '{}' resolved via PATH to {}", name, cand);
                            return cand.toString();
                        }
                    }
                }
            }
        }
        return name;
    }

    /** The search order, de-duplicated, most deliberate placement first. */
    static List<Path> candidates(String name) {
        var out = new LinkedHashSet<Path>();
        var bundleRoots = new ArrayList<String>();
        bundleRoots.add(System.getenv("WYRDSEKAI_DATA_DIR"));
        var home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) bundleRoots.add(home + "/.wyrdsekai");
        // The INSTALL prefix. Backends marked bundled:true are staged into the
        // dist's data/ tree, which the .deb lands at /opt/wyrdsekai/data and
        // the .pkg at /usr/local/wyrdsekai/data -- NOT the mutable data dir.
        // Without these roots, "bundled" would mean "shipped, verified by the
        // build gate, and then invisible to the resolver on every real
        // install": the exact silent-absence this class exists to end, one
        // directory over.
        bundleRoots.add("/opt/wyrdsekai/data");
        bundleRoots.add("/usr/local/wyrdsekai/data");
        // The Windows .msi lands the same dist tree under
        // C:\Program Files\Wyrdsekai\app, and its CLI exports that as
        // WYRDSEKAI_HOME. Without this root the bundled codezaiku was found on
        // Linux and macOS and silently absent on every Windows install
        // (0.2.2 first-install test: preference chain [goose, pi]).
        var installHome = System.getenv("WYRDSEKAI_HOME");
        if (installHome == null || installHome.isBlank()) installHome = System.getProperty("wyrdsekai.home");
        if (installHome != null && !installHome.isBlank()) bundleRoots.add(installHome + "/data");
        if (WINDOWS) {
            var pf = System.getenv("ProgramFiles");
            if (pf != null && !pf.isBlank()) bundleRoots.add(pf + "\\Wyrdsekai\\app\\data");
        }

        for (var base : bundleRoots) {
            if (base == null || base.isBlank()) continue;
            var slot = Path.of(base, "coding-cli-bundle", name);
            // Three shapes, because backends do not agree on one and the
            // installer extracts a release tarball verbatim:
            //   <slot>/<name>            a bare binary (goose)
            //   <slot>/bin/<name>        an unpacked app tree
            //   <slot>/<name>/bin/<name> a tarball that carries its own top
            //                            level directory (CodeZaiku does)
            // Without the last two, `wyrd coding install <backend>` downloads,
            // verifies and extracts happily and the binary still is not found —
            // a success message over a backend that never registers.
            for (var rel : new Path[] {
                    slot.resolve(name),
                    slot.resolve("bin").resolve(name),
                    slot.resolve(name).resolve("bin").resolve(name) }) {
                if (WINDOWS) {
                    // Suffixed launchers FIRST. Files.isExecutable answers true
                    // for any readable file on Windows, so with the bare name
                    // first the resolver happily selected the POSIX shell
                    // script that sits beside codezaiku.bat in the tarball --
                    // and CreateProcess answered error=193, "not a valid Win32
                    // application", three directories away from the cause.
                    // The launchers Windows can actually start are .exe/.bat;
                    // the bare name stays only as a last resort.
                    out.add(rel.resolveSibling(rel.getFileName() + ".exe"));
                    out.add(rel.resolveSibling(rel.getFileName() + ".bat"));
                }
                out.add(rel);
            }
            // Fourth shape: the archive names the binary after the build target
            // rather than the tool -- codex extracts to
            // `codex-x86_64-unknown-linux-musl`. The triple is in the FILE name,
            // not just the download URL, so no fixed path can predict it.
            // Take a single unambiguous <name>-* executable; refuse to guess
            // when there is more than one, because picking the wrong binary is
            // worse than reporting none.
            var prefixed = solePrefixedExecutable(slot, name);
            if (prefixed != null) out.add(prefixed);
        }
        out.add(Path.of("/usr/local/bin", name));
        out.add(Path.of("/opt", name, "bin", name));
        if (home != null && !home.isBlank()) out.add(Path.of(home, ".local", "bin", name));

        var owner = dataDirOwnerHome();
        if (owner != null) out.add(owner.resolve(".local/bin").resolve(name));

        return List.copyOf(out);
    }


    /**
     * The one executable in {@code slot} whose name starts with {@code name-},
     * or null when there is none or more than one.
     *
     * <p>Archives that embed a build target in the file name (codex ships
     * {@code codex-x86_64-unknown-linux-musl}) cannot be found by any fixed
     * path. Ambiguity is answered with null rather than a guess: two candidates
     * mean we do not know which is the tool.</p>
     */
    private static Path solePrefixedExecutable(Path slot, String name) {
        if (!Files.isDirectory(slot)) return null;
        try (var entries = Files.list(slot)) {
            var matches = entries
                .filter(p -> p.getFileName().toString().startsWith(name + "-"))
                .filter(Files::isRegularFile)
                .filter(Files::isExecutable)
                .limit(2)
                .toList();
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * The home directory of whoever owns the data directory, or null.
     *
     * <p>Under systemd the service's own {@code HOME} is useless for this — it
     * is {@code /root}, while the tools were installed by the human whose data
     * this is. The data directory's owner names that person without us having
     * to guess or read a login database.</p>
     */
    private static Path dataDirOwnerHome() {
        try {
            var dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
            if (dataDir == null || dataDir.isBlank()) return null;
            var attrs = Files.readAttributes(Path.of(dataDir), PosixFileAttributes.class);
            var user = attrs.owner().getName();
            if (user == null || user.isBlank() || "root".equals(user)) return null;
            var home = Path.of("/home", user);
            return Files.isDirectory(home) ? home : null;
        } catch (Exception e) {
            return null;      // unreadable, non-POSIX, or no such dir — just skip this source
        }
    }

    /** A regular, executable, not-world-writable file. */
    private static boolean usable(Path p) {
        try {
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) return false;
            var perms = Files.readAttributes(p, PosixFileAttributes.class).permissions();
            if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                log.warn("ignoring world-writable candidate for a coding backend: {}", p);
                return false;
            }
            return true;
        } catch (UnsupportedOperationException e) {
            return Files.isRegularFile(p) && Files.isExecutable(p);   // non-POSIX filesystem
        } catch (Exception e) {
            return false;
        }
    }
}
