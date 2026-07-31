package org.wyrdsekai.core.coding;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Entry point invoked by the {@code wyrd coding ...} bash subcommand
 * (see {@code bin/wyrd}). All bundle-management commands route through
 * here so the trust + retry + sha256 logic lives in one place.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — success</li>
 *   <li>1 — operational failure (manifest missing, sha256 mismatch,
 *           network error, install conflict)</li>
 *   <li>2 — usage error (unknown subcommand, missing arg)</li>
 * </ul>
 *
 * <p>Keep stdout free of decorative formatting — the bash wrapper relays
 * verbatim. Hard errors land on stderr.</p>
 */
public final class CodingCli {

    public static void main(String[] args) {
        int rc = new CodingCli(System.out, System.err).run(args);
        System.exit(rc);
    }

    private final PrintStream out;
    private final PrintStream err;

    public CodingCli(PrintStream out, PrintStream err) {
        this(out, err, CommandRunner.realCommandRunner(),
             () -> System.console() != null);
    }

    /**
     * Test-friendly constructor: lets tests inject a fake
     * {@link CommandRunner} (so {@code wyrd coding login} can be
     * exercised without spawning real OAuth flows) and a TTY probe
     * (so headless-guard logic can be exercised on a real terminal).
     */
    public CodingCli(PrintStream out, PrintStream err,
                     CommandRunner commandRunner, TtyProbe ttyProbe) {
        this.out = out;
        this.err = err;
        this.commandRunner = commandRunner;
        this.ttyProbe = ttyProbe;
    }

    private final CommandRunner commandRunner;
    private final TtyProbe ttyProbe;

    /**
     * "Is stdin attached to a TTY?" probe. Production wiring is
     * {@code System.console() != null}; tests pass a constant.
     */
    @FunctionalInterface
    public interface TtyProbe { boolean isTty(); }

    public int run(String[] args) {
        if (args == null || args.length == 0) {
            return printUsage();
        }
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (sub) {
                case "list", "ls"        -> doList(rest);
                case "status"            -> doStatus(rest);
                case "install"           -> doInstall(rest);
                case "uninstall", "remove", "rm" -> doUninstall(rest);
                case "update"            -> doUpdate(rest);
                case "download-bundle"   -> doDownloadBundle(rest);
                case "login"             -> doLogin(rest);
                case "help", "-h", "--help" -> printUsage();
                default -> {
                    err.println("wyrd coding: unknown subcommand '" + sub + "'");
                    yield printUsage();
                }
            };
        } catch (BundleManifest.ManifestValidationException e) {
            err.println("manifest error: " + e.getMessage());
            return 1;
        } catch (BundleInstaller.InstallException e) {
            err.println("install error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return 1;
        }
    }

    // ── Subcommands ──

    private int doList(String[] args) throws IOException {
        BundleManifest manifest = loadManifest();
        Path destRoot = resolveDestinationRoot(args);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));
        List<String> installed = installer.listInstalled(destRoot);

        out.println("Coding backends (manifest v" + manifest.manifestVersion() + "):");
        for (Map.Entry<String, BackendBundleEntry> entry : manifest.backends().entrySet()) {
            String name = entry.getKey();
            BackendBundleEntry b = entry.getValue();
            String marker = markerFor(b, installed);
            String detail = formatLine(b);
            out.printf("  %s %-12s %s%n", marker, name, detail);
        }
        return 0;
    }

    private int doStatus(String[] args) throws IOException {
        BundleManifest manifest = loadManifest();
        Path destRoot = resolveDestinationRoot(args);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));

        out.printf("%-14s %-10s %-12s %s%n", "BACKEND", "STATE", "VERSION", "PATH/NOTE");
        for (Map.Entry<String, BackendBundleEntry> entry : manifest.backends().entrySet()) {
            String name = entry.getKey();
            BackendBundleEntry b = entry.getValue();
            BundleInstaller.Status st = installer.getStatus(name, destRoot);
            String state;
            String detail;
            if (b.bundled()) {
                state = "bundled";
                detail = b.path() == null ? "" : b.path();
            } else if (b.configOnly()) {
                state = "config";
                detail = "cloud SaaS — API key only";
            } else if (b.dockerImage() != null && !b.dockerImage().isBlank()) {
                state = "helper";
                detail = b.setupCommand() == null ? b.dockerImage() : b.setupCommand();
            } else if (st.installed()) {
                state = "installed";
                detail = st.path().toString();
            } else {
                state = "available";
                detail = (b.sizeMb() == null ? "?" : b.sizeMb()) + " MB";
            }
            String version = st.installed() && st.version() != null
                    ? st.version()
                    : (b.version() == null ? "-" : b.version());
            out.printf("%-14s %-10s %-12s %s%n", name, state, version, detail);
        }
        return 0;
    }

    private int doInstall(String[] args) throws IOException {
        boolean force = false;
        String name = null;
        for (String a : args) {
            if ("--force".equals(a) || "-f".equals(a)) force = true;
            else if (a.startsWith("--")) {
                err.println("unknown flag: " + a);
                return 2;
            } else if (name == null) name = a;
        }
        if (name == null) {
            err.println("usage: wyrd coding install <backend> [--force]");
            return 2;
        }
        BundleManifest manifest = loadManifest();
        Path destRoot = resolveDestinationRoot(new String[0]);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));
        Path installed = installer.installBackend(name.toLowerCase(Locale.ROOT), destRoot, force);
        out.println("installed " + name + " -> " + installed);
        return 0;
    }

    private int doUninstall(String[] args) throws IOException {
        if (args.length < 1) {
            err.println("usage: wyrd coding uninstall <backend>");
            return 2;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        BundleManifest manifest = loadManifest();
        Path destRoot = resolveDestinationRoot(new String[0]);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));
        boolean removed = installer.uninstallBackend(name, destRoot);
        if (removed) {
            out.println("uninstalled " + name);
            return 0;
        }
        out.println(name + " was not installed");
        return 0;
    }

    private int doUpdate(String[] args) throws IOException {
        if (args.length < 1) {
            err.println("usage: wyrd coding update <backend>");
            return 2;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        BundleManifest manifest = loadManifest();
        Path destRoot = resolveDestinationRoot(new String[0]);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));
        var newPath = installer.updateBackend(name, destRoot);
        if (newPath.isEmpty()) {
            out.println(name + " is already current");
        } else {
            out.println("updated " + name + " -> " + newPath.get());
        }
        return 0;
    }

    /**
     * {@code wyrd coding download-bundle [--platforms ...] [--backends ...] [--cache-dir <path>]}
     * — pre-fetches every (backend, platform) pair into the air-gap cache
     * Skips bundled / docker-image /
     * config-only entries. Refuses with exit 1 on any sha256 mismatch so
     * a corrupt cache never survives the run.
     */
    private int doDownloadBundle(String[] args) throws IOException {
        Set<String> platformFilter = null;     // null = all platforms in manifest
        Set<String> backendFilter  = null;     // null = all installable backends
        Path cacheOverride = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--platforms" -> {
                    if (i + 1 >= args.length) {
                        err.println("usage: wyrd coding download-bundle --platforms linux-x64,darwin-arm64");
                        return 2;
                    }
                    platformFilter = splitCsv(args[++i]);
                }
                case "--backends" -> {
                    if (i + 1 >= args.length) {
                        err.println("usage: wyrd coding download-bundle --backends goose,codex");
                        return 2;
                    }
                    backendFilter = splitCsv(args[++i]);
                }
                case "--cache-dir" -> {
                    if (i + 1 >= args.length) {
                        err.println("usage: wyrd coding download-bundle --cache-dir <path>");
                        return 2;
                    }
                    cacheOverride = Path.of(args[++i]);
                }
                case "-h", "--help" -> {
                    out.println("usage: wyrd coding download-bundle [options]");
                    out.println();
                    out.println("Pre-fetch every downloadable backend archive into the air-gap");
                    out.println("cache so an offline household can install without network access.");
                    out.println();
                    out.println("Options:");
                    out.println("  --platforms <csv>   Limit to listed <platform>-<arch> keys");
                    out.println("                      (e.g. linux-x64,darwin-arm64)");
                    out.println("  --backends  <csv>   Limit to listed backend names (e.g. goose,codex)");
                    out.println("  --cache-dir <path>  Override the cache destination");
                    out.println("                      (default: <data-dir>/coding-cli-bundle/cache/)");
                    return 0;
                }
                default -> {
                    if (a.startsWith("--")) {
                        err.println("unknown flag: " + a);
                        return 2;
                    }
                    err.println("unexpected positional arg: " + a);
                    return 2;
                }
            }
        }

        BundleManifest manifest = loadManifest();

        // Build the (backend, platform) work list.
        record Job(String backend, String platformArch) {}
        List<Job> jobs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<String, BackendBundleEntry> e : manifest.backends().entrySet()) {
            String name = e.getKey();
            BackendBundleEntry b = e.getValue();
            if (backendFilter != null && !backendFilter.contains(name)) continue;
            if (!b.isInstallable()) {
                skipped.add(name + " (" + skipReason(b) + ")");
                continue;
            }
            Map<String, String> shas = b.sha256PerPlatform();
            if (shas == null || shas.isEmpty()) {
                skipped.add(name + " (no sha256_per_platform)");
                continue;
            }
            // Preserve manifest insertion order for deterministic output.
            for (String pa : shas.keySet()) {
                if (platformFilter != null && !platformFilter.contains(pa)) continue;
                jobs.add(new Job(name, pa));
            }
        }

        // If the user asked for backends/platforms that don't exist in the
        // manifest, fail fast — the silent-empty-list footgun bit us once.
        if (backendFilter != null) {
            for (String want : backendFilter) {
                if (!manifest.backends().containsKey(want)) {
                    err.println("download-bundle error: backend '" + want
                            + "' is not in the manifest. Run `wyrd coding list`.");
                    return 1;
                }
            }
        }
        if (jobs.isEmpty()) {
            out.println("download-bundle: nothing to do");
            for (String s : skipped) out.println("  skipped: " + s);
            return 0;
        }

        Path destRoot = resolveDestinationRoot(new String[0]);
        Path cacheDir = (cacheOverride != null) ? cacheOverride : destRoot.resolve("cache");
        Files.createDirectories(cacheDir);
        AirGapBundleCache cache = new AirGapBundleCache(cacheDir);
        BundleInstaller installer = new BundleInstaller(manifest, cache);

        out.println("download-bundle: " + jobs.size()
                + " archive(s) -> " + cacheDir);
        for (String s : skipped) out.println("  skipped: " + s);

        int idx = 0;
        int failures = 0;
        long totalBytes = 0L;
        int hits = 0;
        for (Job j : jobs) {
            idx++;
            String prefix = "[" + idx + "/" + jobs.size() + "] " + j.backend + " " + j.platformArch;
            try {
                BundleInstaller.DownloadResult r =
                        installer.downloadOnly(j.backend, j.platformArch);
                totalBytes += r.bytes();
                if (!r.downloaded()) hits++;
                String tag = r.downloaded() ? "downloaded" : "cache-hit";
                out.printf("%s ... %s %s (%s)%n",
                        prefix, formatMb(r.bytes()), "OK", tag);
            } catch (BundleInstaller.InstallException ex) {
                failures++;
                err.printf("%s ... FAIL %s%n", prefix, ex.getMessage());
            }
        }

        out.printf("download-bundle: %d ok (%d downloaded, %d cached), %d failed; %s total%n",
                jobs.size() - failures, jobs.size() - failures - hits, hits, failures,
                formatMb(totalBytes));
        if (failures > 0) {
            err.println("download-bundle: refusing to leave a partially-corrupt cache; "
                    + "fix the failing entries and re-run.");
            return 1;
        }
        return 0;
    }

    /**
     * {@code wyrd coding login <backend> [--force]} — wraps the
     * backend's native OAuth flow.
     *
     * <p>Exit codes:
     * <ul>
     *   <li>0 — login subprocess exited 0</li>
     *   <li>1 — operational refusal: API-key-only backend, binary not
     *       installed, no auth block in manifest, downstream non-zero
     *       exit</li>
     *   <li>2 — usage error: missing arg, unknown backend, unknown flag</li>
     *   <li>3 — headless guard: OAuth flow needs a browser and we're
     *       on a non-TTY host (override with {@code --force})</li>
     * </ul>
     */
    private int doLogin(String[] args) throws IOException {
        boolean force = false;
        String backendName = null;
        for (String a : args) {
            if ("--force".equals(a) || "-f".equals(a)) {
                force = true;
            } else if (a.startsWith("--")) {
                err.println("unknown flag: " + a);
                return 2;
            } else if (backendName == null) {
                backendName = a;
            }
        }
        if (backendName == null) {
            err.println("usage: wyrd coding login <backend> [--force]");
            err.println("       OAuth-supporting backends: " + listOAuthBackends());
            return 2;
        }
        String name = backendName.toLowerCase(Locale.ROOT);
        BundleManifest manifest = loadManifest();
        var entryOpt = manifest.get(name);
        if (entryOpt.isEmpty()) {
            err.println("login error: unknown backend '" + name
                    + "'. Run `wyrd coding list` to see available backends.");
            return 2;
        }
        BackendBundleEntry entry = entryOpt.get();
        var auth = entry.auth();

        // 1. Refuse for API-key-only backends.
        if (auth == null || auth.oauth() == null) {
            String envVar = (auth != null && auth.apiKey() != null)
                    ? auth.apiKey().envVar()
                    : "<provider env var>";
            err.println("Backend '" + name
                    + "' is API-key-only; set " + envVar
                    + " in your Key Chest instead.");
            return 1;
        }

        // 2. Verify binary is installed (or bundled / setup-helper).
        Path destRoot = resolveDestinationRoot(new String[0]);
        BundleInstaller installer = new BundleInstaller(manifest, makeCache(destRoot));
        BundleInstaller.Status status = installer.getStatus(name, destRoot);
        boolean haveBinary = entry.bundled()
                || (entry.dockerImage() != null && !entry.dockerImage().isBlank())
                || status.installed();
        if (!haveBinary) {
            err.println("Backend '" + name + "' is not installed. "
                    + "Run `wyrd coding install " + name + "` first.");
            return 1;
        }

        // 3. Headless guard.
        if (!auth.oauth().headlessSupported() && !ttyProbe.isTty() && !force) {
            err.println(name + "'s OAuth flow needs a browser; "
                    + "run this on a desktop or use --force to attempt anyway.");
            return 3;
        }
        if (!auth.oauth().headlessSupported() && force) {
            out.println("warning: " + name + " OAuth flow is browser-dependent; "
                    + "proceeding anyway because --force was passed.");
        }

        // 4. Spawn the backend's native login flow with inherited stdio.
        List<String> argv = splitCommand(auth.oauth().command());
        if (argv.isEmpty()) {
            err.println("login error: backend '" + name
                    + "' has an empty oauth.command in the manifest");
            return 1;
        }
        out.println("Launching: " + String.join(" ", argv));
        if (auth.oauth().note() != null && !auth.oauth().note().isBlank()) {
            out.println("Note: " + auth.oauth().note());
        }
        int exit;
        try {
            exit = commandRunner.run(argv);
        } catch (IOException e) {
            err.println("login error: failed to spawn '" + argv.get(0)
                    + "': " + e.getMessage()
                    + " (is the backend on PATH? Try `wyrd coding install " + name + "`)");
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("login interrupted");
            return 1;
        }
        if (exit != 0) {
            err.println("login subprocess exited " + exit
                    + " — credentials NOT updated.");
            return exit;
        }

        // 5. Confirm by re-checking the credential path.
        String credPath = auth.oauth().credentialPath();
        boolean live = credPath != null
                && oauthCredentialsLookLive(DefaultAuthResolver.expandUserHome(credPath));
        if (live) {
            out.println("Login complete. Credentials at: " + credPath);
        } else {
            out.println("warning: login subprocess exited 0 but no credentials "
                    + "found at " + credPath + " — verify with `"
                    + argv.get(0) + " auth status` (or equivalent).");
        }
        return 0;
    }

    private static boolean oauthCredentialsLookLive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    return stream.findAny().isPresent();
                }
            }
            if (Files.isRegularFile(path)) {
                return Files.size(path) > 0;
            }
        } catch (IOException ignore) {
            return false;
        }
        return false;
    }

    /**
     * Tokenise an oauth.command string into argv. Plain whitespace
     * splitting — the manifest never embeds shell quoting.
     */
    static List<String> splitCommand(String command) {
        if (command == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : command.trim().split("\\s+")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out;
    }

    /** Comma-joined list of manifest backends with a non-null oauth block. */
    private String listOAuthBackends() {
        try {
            BundleManifest m = loadManifest();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, BackendBundleEntry> e : m.backends().entrySet()) {
                var a = e.getValue().auth();
                if (a != null && a.oauth() != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey());
                }
            }
            return sb.length() == 0 ? "(none)" : sb.toString();
        } catch (IOException e) {
            return "(manifest unreadable)";
        }
    }

    private int printUsage() {
        out.println("usage: wyrd coding <command> [args]");
        out.println();
        out.println("Commands:");
        out.println("  list                       Show available + installed backends");
        out.println("  status                     Tabular status of every backend");
        out.println("  install <backend> [-f]     Download + verify + install a backend");
        out.println("  uninstall <backend>        Remove an installed backend");
        out.println("  update <backend>           Re-install if manifest version differs");
        out.println("  download-bundle [opts]     Pre-fetch every backend archive into the");
        out.println("                             air-gap cache (--platforms / --backends /");
        out.println("                             --cache-dir; see `download-bundle --help`)");
        out.println("  login <backend> [--force]  OAuth-login to a paid backend "
                + "(claude-sdk/codex/...)");
        return 0;
    }

    private static Set<String> splitCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String tok : csv.split(",")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String skipReason(BackendBundleEntry b) {
        if (b.bundled()) return "bundled — already in install image";
        if (b.configOnly()) return "config-only — cloud SaaS, no binary";
        if (b.dockerImage() != null && !b.dockerImage().isBlank())
            return "docker-image — pulled by `" + (b.setupCommand() == null
                    ? "wyrd setup " + b.name() : b.setupCommand()) + "`";
        return "non-installable";
    }

    private static String formatMb(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%d KB", Math.max(1L, bytes / 1024L));
        }
        return String.format(Locale.ROOT, "%d MB", bytes / (1024L * 1024L));
    }

    // ── Helpers ──

    private BundleManifest loadManifest() throws IOException {
        // Test-friendly system-property override (env vars are tricky to
        // set from inside the JVM under modern JDKs without --add-opens).
        String prop = System.getProperty("wyrdsekai.coding.bundle.manifest");
        Path path = (prop != null && !prop.isBlank())
                ? Path.of(prop)
                : BundleManifest.resolveDefaultManifestPath();
        return BundleManifest.load(path);
    }

    /**
     * The install root: typically {@code <data-dir>/coding-cli-bundle/}.
     * Resolution order:
     * <ol>
     *   <li>{@code --root <path>} CLI flag (escape hatch / tests)</li>
     *   <li>{@code wyrdsekai.coding.bundle.root} system property (tests)</li>
     *   <li>{@code WYRDSEKAI_DATA_DIR} env var (matches bin/wyrd)</li>
     *   <li>{@code ~/.wyrdsekai/coding-cli-bundle/}</li>
     * </ol>
     */
    private Path resolveDestinationRoot(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--root".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        String prop = System.getProperty("wyrdsekai.coding.bundle.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = System.getProperty("user.home", ".") + "/.wyrdsekai";
        }
        return Path.of(dataDir, "coding-cli-bundle");
    }

    private AirGapBundleCache makeCache(Path destRoot) {
        Path cacheDir = destRoot.resolve("cache");
        try { Files.createDirectories(cacheDir); } catch (IOException ignore) {}
        return new AirGapBundleCache(cacheDir);
    }

    /** Glyph in front of the list line — bundled / installed / available. */
    private static String markerFor(BackendBundleEntry b, List<String> installed) {
        if (b.bundled()) return "*";
        if (installed.contains(b.name())) return "+";
        if (b.configOnly()) return ".";
        if (b.dockerImage() != null) return ".";
        return "-";
    }

    private static String formatLine(BackendBundleEntry b) {
        StringBuilder sb = new StringBuilder();
        if (b.version() != null) sb.append("v").append(b.version()).append(" ");
        if (b.bundled()) sb.append("(bundled)");
        else if (b.configOnly()) sb.append("(config-only — API key)");
        else if (b.dockerImage() != null) sb.append("(setup: ")
                .append(b.setupCommand() == null ? "wyrd setup " + b.name() : b.setupCommand())
                .append(")");
        else if (b.isNpmDistribution()) sb.append("(npm install -g ")
                .append(b.npmPackage() == null ? "?" : b.npmPackage())
                .append(")");
        else sb.append("(downloadable, ~")
                .append(b.sizeMb() == null ? "?" : b.sizeMb())
                .append(" MB)");
        if (b.tosWarning() != null) sb.append(" — ").append(b.tosWarning());
        return sb.toString();
    }
}
