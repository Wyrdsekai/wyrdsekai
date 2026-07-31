package org.wyrdsekai.server;

import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.external.o.PhaseOAdaptersBootstrap;
import org.wyrdsekai.core.external.p.PhasePAdaptersBootstrap;
import org.wyrdsekai.core.external.q.PhaseQAdaptersBootstrap;
import org.wyrdsekai.core.external.r.PhaseRAdaptersBootstrap;
import org.wyrdsekai.core.external.s.PhaseSAdaptersBootstrap;
import org.wyrdsekai.core.external.u.PhaseUAdaptersBootstrap;
import org.wyrdsekai.core.external.v.PhaseVAdaptersBootstrap;
import org.wyrdsekai.core.external.w.PhaseWAdaptersBootstrap;
import org.wyrdsekai.core.room.TheSafe;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;

/**
 * Headless CLI for the credential Safe — the surface behind
 * {@code wyrd cred set|get|list|unset}. Same pattern as
 * {@link RelayNkeyAdminMain}: {@code bin/wyrd} shells out here so bash never
 * touches key derivation or the encrypted-at-rest file format.
 *
 * <p>Operates on the SAME store the server wires at boot (Main's W13 block):
 * {@code NodeIdentity.loadOrGenerate(dataDir/node-identity.json)} supplies the
 * Ed25519 seed, {@link TheSafe#initLocal} opens
 * {@code dataDir/credentials.safe} (600-mode, AES-GCM keyed off the seed;
 * plaintext-600 honest fallback when no identity is readable). The data dir is
 * {@link SystemPaths#dataDir()} — {@code bin/wyrd} exports
 * {@code WYRDSEKAI_DATA_DIR} so CLI and service can't disagree about where the
 * safe lives.</p>
 *
 * <p>Secret hygiene: {@code set} reads the value from stdin (a hidden console
 * prompt on a TTY, a plain line on a pipe) — never argv, so the secret never
 * lands in shell history or {@code ps} output. {@code get} answers only
 * {@code SET} / {@code (not set)} — it never prints the value.</p>
 *
 * <p>Exit codes: 0 success, 1 user error / not set, 2 internal.</p>
 */
public final class CredAdminMain {

    private CredAdminMain() {}

    public static void main(String[] args) {
        System.exit(run(SystemPaths.dataDir(), System.in, System.out, System.err, args));
    }

    static int run(Path dataDir, InputStream stdin, PrintStream out, PrintStream err,
                   String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("-h")
                || args[0].equals("--help")) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }
        var safeFile = dataDir.resolve("credentials.safe");
        var identityFile = dataDir.resolve("node-identity.json");
        // Rita re-verify 2026-07-11 (#29): the safe MUST end up owned by the
        // user the zone runs as — the data-dir owner (world.db's owner when it
        // exists, else the dir's). A root-created 600-mode safe was invisible
        // to a server running as operator: slots stored, runes/weather empty.
        var serviceOwner = dataDirOwner(dataDir);
        var me = System.getProperty("user.name");
        boolean runningAsRoot = "root".equals(me);
        // A safe (or identity) owned by another user means this zone runs
        // under a different account (.deb service = root). Writing as the
        // wrong user would either fail or fork a second, never-read safe —
        // teach the fix instead. root is exempt: it can write on the owner's
        // behalf, and we chown back to the service owner after the command.
        if (!runningAsRoot) {
            var owned = checkOwnership(safeFile, err, args)
                && checkOwnership(identityFile, err, args);
            if (!owned) return 1;
        }

        final TheSafe safe;
        boolean encrypted = false;
        try {
            byte[] keyMaterial = null;
            try {
                keyMaterial = NodeIdentity.loadOrGenerate(identityFile).privateKeySeedBytes();
                encrypted = true;
            } catch (Exception idErr) {
                err.println("[wyrd] warning: node identity unavailable (" + idErr.getMessage()
                    + ") — credentials.safe stays plaintext at 600-mode.");
            }
            safe = TheSafe.initLocal(safeFile, keyMaterial);
        } catch (Exception e) {
            err.println("[wyrd] cred: could not open " + safeFile + " — " + e.getMessage());
            return 2;
        }
        try {
            return switch (args[0]) {
                case "set"   -> doSet(safe, encrypted, stdin, out, err, args);
                case "get"   -> doGet(safe, out, err, args);
                case "list"  -> doList(safe, out, args);
                case "unset" -> doUnset(safe, out, err, args);
                default -> {
                    err.println("[wyrd] unknown cred command: " + args[0]);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (Exception e) {
            err.println("[wyrd] cred " + args[0] + " failed — " + e.getMessage());
            return 2;
        } finally {
            // Hand ownership of anything we created/touched back to the
            // service user so the running zone can actually read its own
            // safe. Only meaningful when root ran the command for a zone
            // owned by another user; a same-user run is a no-op.
            if (runningAsRoot && serviceOwner != null && !"root".equals(serviceOwner.getName())) {
                chownIfPresent(safeFile, serviceOwner, err);
                chownIfPresent(identityFile, serviceOwner, err);
            }
        }
    }

    /**
     * The user the zone's server runs as — the owner of {@code world.db}
     * when present (the strongest signal: the server wrote it), else the
     * owner of the data dir itself. {@code null} when neither can be
     * stat'ed (fresh dir a caller is about to create).
     */
    static UserPrincipal dataDirOwner(Path dataDir) {
        for (var probe : new Path[] { dataDir.resolve("world.db"), dataDir }) {
            try {
                if (Files.exists(probe)) return Files.getOwner(probe);
            } catch (Exception ignored) {
                // fall through to the next probe
            }
        }
        return null;
    }

    /** Chown {@code file} to {@code owner} when it exists; teach on failure, never abort. */
    private static void chownIfPresent(Path file, UserPrincipal owner, PrintStream err) {
        try {
            if (Files.exists(file) && !owner.equals(Files.getOwner(file))) {
                Files.setOwner(file, owner);
            }
        } catch (Exception e) {
            err.println("[wyrd] warning: could not chown " + file + " to '" + owner.getName()
                + "' (" + e.getMessage() + ") — run: sudo chown " + owner.getName() + " " + file);
        }
    }

    /** True when {@code file} is absent or owned by the current user; else teaches sudo -u. */
    private static boolean checkOwnership(Path file, PrintStream err, String[] args) {
        try {
            if (!Files.exists(file)) return true;
            var owner = Files.getOwner(file).getName();
            var me = System.getProperty("user.name");
            if (owner.equals(me)) return true;
            err.println("[wyrd] " + file + " is owned by '" + owner
                + "' (600-mode) — this zone's credentials belong to that user.");
            err.println("[wyrd] Re-run as the owning user:");
            err.println("[wyrd]   sudo -u " + owner + " wyrd cred " + String.join(" ", args));
            return false;
        } catch (Exception e) {
            // Can't even stat it — same teaching path, without the owner name.
            err.println("[wyrd] cannot read " + file + " (" + e.getMessage()
                + ") — if the zone runs as another user, try: sudo -u <service-user> wyrd cred "
                + String.join(" ", args));
            return false;
        }
    }

    // wyrd cred set <slot>   (value read from stdin — hidden prompt on a TTY)
    private static int doSet(TheSafe safe, boolean encrypted, InputStream stdin,
                             PrintStream out, PrintStream err, String[] args) throws Exception {
        var slot = args.length > 1 ? args[1] : null;
        if (slot == null || slot.isBlank()) {
            err.println("Usage: wyrd cred set <slot>   (e.g. wyrd cred set github.token)");
            return 1;
        }
        String value;
        var console = System.console();
        if (console != null) {
            // TTY: hidden prompt — the secret is never echoed.
            var chars = console.readPassword("Value for '%s' (input hidden): ", slot);
            value = chars == null ? null : new String(chars);
        } else {
            // Pipe / redirect: read one line, e.g. `wyrd cred set x < value.txt`.
            var reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
            value = reader.readLine();
        }
        if (value == null || value.isBlank()) {
            err.println("[wyrd] no value given — credential unchanged.");
            return 1;
        }
        safe.storeSlot(slot, value);
        out.println("[wyrd] credential '" + slot + "' stored ("
            + (encrypted ? "encrypted-at-rest" : "plaintext-600 fallback") + ").");
        out.println("[wyrd] takes effect on next `wyrd restart` (the scroll's cred verbs are immediate).");
        return 0;
    }

    // wyrd cred get <slot> — prints SET / (not set), never the value.
    private static int doGet(TheSafe safe, PrintStream out, PrintStream err, String[] args) {
        var slot = args.length > 1 ? args[1] : null;
        if (slot == null || slot.isBlank()) {
            err.println("Usage: wyrd cred get <slot>");
            return 1;
        }
        if (safe.readSlot(slot).isPresent()) {
            out.println("SET");
            return 0;
        }
        out.println("(not set)");
        return 1;
    }

    // wyrd cred list [--all] — slot ids only, never values.
    private static int doList(TheSafe safe, PrintStream out, String[] args) {
        boolean all = args.length > 1
            && ("--all".equals(args[1]) || "all".equals(args[1]) || "-a".equals(args[1]));
        var stored = safe.listSlots();

        if (!all) {
            if (stored.isEmpty()) {
                out.println("(no credentials stored)");
            } else {
                stored.stream().sorted().forEach(out::println);
            }
            // Discoverability (2026-07-31): listing only what is SET left no
            // way to learn which slots exist at all.
            out.println();
            out.println("(`wyrd cred list --all` shows every slot this build understands)");
            return 0;
        }

        // Every slot the registered adapters declare. The bootstraps are
        // idempotent and side-effect-free, so priming them here just to read
        // the inventory is safe in a short-lived CLI process.
        primeAdapters();
        var known = ExternalAdapterRegistry.get().credentialSlots();
        if (known.isEmpty()) {
            out.println("(no adapters registered — is this build complete?)");
            return 0;
        }
        int width = known.keySet().stream().mapToInt(String::length).max().orElse(20);
        out.printf("%-" + width + "s  %-6s  %s%n", "SLOT", "STATE", "USED BY");
        for (var e : known.entrySet()) {
            var state = stored.contains(e.getKey()) ? "SET" : "unset";
            out.printf("%-" + width + "s  %-6s  %s%n", e.getKey(), state, e.getValue());
        }
        out.println();
        out.println("Set one with:  wyrd cred set <slot>     (value read from a hidden prompt)");
        out.println("A slot can also be supplied as WYRDSEKAI_CRED_<SLOT> in the environment");
        out.println("(uppercased, '.' and '-' become '_').");
        return 0;
    }

    /**
     * Register the built-in external adapters so their credential slots can be
     * enumerated. Each phase bootstrap is idempotent, needs no DB/network, and
     * only constructs adapter objects — Phase T is skipped because it requires
     * runtime wiring and declares no credential slot anyway.
     */
    private static void primeAdapters() {
        PhaseOAdaptersBootstrap.init();
        PhasePAdaptersBootstrap.init();
        PhaseQAdaptersBootstrap.init();
        PhaseRAdaptersBootstrap.init();
        PhaseSAdaptersBootstrap.register();
        PhaseUAdaptersBootstrap.init();
        PhaseVAdaptersBootstrap.init();
        PhaseWAdaptersBootstrap.init();
    }

    // wyrd cred unset <slot>
    private static int doUnset(TheSafe safe, PrintStream out, PrintStream err, String[] args) {
        var slot = args.length > 1 ? args[1] : null;
        if (slot == null || slot.isBlank()) {
            err.println("Usage: wyrd cred unset <slot>");
            return 1;
        }
        if (safe.removeSlot(slot)) {
            out.println("[wyrd] credential '" + slot + "' removed.");
            out.println("[wyrd] takes effect on next `wyrd restart` (the scroll's cred verbs are immediate).");
            return 0;
        }
        out.println("(not set)");
        return 1;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: wyrd cred <set|get|list|unset> [slot]");
        out.println();
        out.println("Steward credential slots for item scripts (external adapters), stored in");
        out.println("the zone's Safe (dataDir/credentials.safe, 600-mode, encrypted at rest");
        out.println("with the node identity key).");
        out.println();
        out.println("  set <slot>     store a secret; the value is read from stdin with a");
        out.println("                 hidden prompt (never from argv). e.g. wyrd cred set github.token");
        out.println("  get <slot>     prints SET or (not set) — never the value");
        out.println("  list           list stored slot names (never values)");
        out.println("  list --all     every slot this build understands, SET or unset,");
        out.println("                 with the adapter that uses it");
        out.println("  unset <slot>   remove a slot");
        out.println();
        out.println("Changes take effect on next `wyrd restart` (the scroll's cred verbs are immediate).");
    }
}
