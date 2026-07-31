package org.wyrdsekai.server;

import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Headless {@code wyrd bond} admin CLI.
 *
 * <p>Records a bond between a player and a companion without going through
 * the in-world ritual. Closes the live-verify gap where cross-zone follow
 * requires a primary bondholder, but the only
 * production paths to form a bond are companion-initiated (LLM-driven
 * {@code bond_ritual} action) and gradual interaction-count elevation. Both
 * are too slow for setup/test scenarios.</p>
 *
 * <p>Writes to two places so the bond is durable across restart and visible
 * to the running CompanionActor on its next manifest reload:</p>
 * <ol>
 *   <li>{@link BondStore} — the SQL row of record (used by some lifecycle paths
 *       e.g. {@code sever}).</li>
 *   <li>The companion's latest {@link SoulManifest}, version-bumped — this is
 *       the path {@code CompanionActor#restoreBondsAndCapacity} reads from on
 *       startup. Without this, a BondStore-only bond is invisible to the
 *       in-memory {@code activeBonds} map.</li>
 * </ol>
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   wyrd bond create &lt;player-username&gt; &lt;companion-did&gt; [--depth ACQUAINTANCE|ITEM|SACRED|SOUL_REF|SOUL_INGRAINED]
 *   wyrd bond list
 * </pre>
 *
 * <p>The companion DID is the {@code did:key:...} the steward sees in
 * {@code wyrd whoami} or in soul_manifests rows. {@code wyrd bond list} shows
 * bonds across all agents in the SoulStore. Default depth is
 * {@code ACQUAINTANCE} (lowest); cross-zone follow doesn't require deeper.</p>
 *
 * <p>After {@code create}, restart wyrdsekai for the new bond to be loaded
 * into the running CompanionActor: {@code wyrd restart} (source/.deb) or
 * {@code sudo launchctl kickstart -k system/com.wyrdsekai.server} (.pkg).</p>
 *
 * <p>Exit codes: {@code 0} success, {@code 1} user error, {@code 2} internal.</p>
 */
public final class BondAdminMain {

    private BondAdminMain() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err, args));
    }

    static int run(PrintStream out, PrintStream err, String... args) {
        return run(resolveJdbcUrl(), out, err, args);
    }

    /** Test-friendly entry: caller supplies the JDBC URL. */
    static int run(String jdbcUrl, PrintStream out, PrintStream err, String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }
        var cmd = args[0];
        try {
            return switch (cmd) {
                case "create" -> doCreate(jdbcUrl, out, err, tail(args, 1));
                case "list"   -> doList(jdbcUrl, out, err);
                default -> {
                    err.println("[wyrd] unknown bond command: " + cmd);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (Exception e) {
            err.println("[wyrd] bond command failed: " + e.getMessage());
            return 2;
        }
    }

    private static int doCreate(String jdbcUrl, PrintStream out, PrintStream err, String[] args) {
        if (args.length < 2) {
            err.println("Usage: wyrd bond create <player-username> <companion-did> [--depth <level>]");
            return 1;
        }
        String playerUsername = args[0];
        String companionDid = args[1];
        var depth = Bond.BondDepth.ACQUAINTANCE;
        for (int i = 2; i < args.length; i++) {
            var a = args[i];
            switch (a) {
                case "--depth" -> {
                    try {
                        depth = Bond.BondDepth.valueOf(requireArg(args, ++i, "--depth", err).toUpperCase());
                    } catch (IllegalArgumentException e) {
                        err.println("[wyrd] invalid --depth — must be one of: "
                            + Arrays.toString(Bond.BondDepth.values()));
                        return 1;
                    }
                }
                default -> {
                    err.println("[wyrd] unexpected arg: " + a);
                    return 1;
                }
            }
        }

        if (jdbcUrl == null) {
            err.println("[wyrd] no WYRDSEKAI_JDBC_URL configured and default SQLite not found at "
                + defaultSqlitePath());
            return 2;
        }

        // Resolve player → user_id (used as the bondholder DID, matches what
        // CompanionActor sees as said.entityId() in trackBondInteraction).
        var dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        var auth = new AuthService(jdbcUrl, dialect);
        var user = auth.findUserByUsername(playerUsername);
        if (user.isEmpty()) {
            err.println("[wyrd] player not found: " + playerUsername);
            return 1;
        }
        String playerDid = user.get().id();

        // Verify the companion DID resolves to a known soul manifest.
        try (var soulStore = new SqlSoulStore(jdbcUrl)) {
            Optional<SoulManifest> latest = soulStore.latest(companionDid);
            if (latest.isEmpty()) {
                err.println("[wyrd] companion DID has no soul manifest: " + companionDid);
                err.println("       Run 'wyrd bond list' to see known agent DIDs.");
                return 1;
            }
            var manifest = latest.get();

            // Build the new bond. agentADid = companion (self), agentBDid = player.
            // depth defaults to ACQUAINTANCE; for cross-zone follow only `active`
            // matters, but allow override for richer test scenarios.
            var base = Bond.acquaintance(companionDid, playerDid);
            // Re-construct with desired depth + mutualConsent=true (admin path
            // assumes both parties consent — this is steward bootstrap, not
            // an in-world negotiation).
            var bond = new Bond(base.bondId(), base.agentADid(), base.agentBDid(),
                depth, base.formedAt(), base.lastInteraction(), 0,
                /* mutualConsent */ true, /* active */ true, /* scarred */ false,
                base.state(), base.coldStartUntil(), base.posture(),
                base.relationalState());

            // 1) BondStore — the SQL row of record.
            new BondStore(jdbcUrl).save(bond);

            // 2) Soul manifest — version bumped, bonds list extended/replaced.
            //    Replace any existing bond between this pair (idempotent), keep
            //    other bonds intact.
            List<Bond> existing = manifest.bonds() != null
                ? new ArrayList<>(manifest.bonds()) : new ArrayList<>();
            existing.removeIf(b -> b.bondId().equals(bond.bondId()));
            existing.add(bond);
            var updated = manifest.withBonds(existing).bumpedVersion();
            soulStore.store(updated);

            out.printf("bond %s created: %s ↔ %s (depth=%s)%n",
                bond.bondId(), companionDid, playerDid, depth);
            err.println("[wyrd] manifest v" + manifest.manifestVersion()
                + " → v" + updated.manifestVersion()
                + " (bond appended, signature cleared until next forge)");
            err.println("[wyrd] restart wyrdsekai for the bond to load into the running CompanionActor.");
            return 0;
        }
    }

    private static int doList(String jdbcUrl, PrintStream out, PrintStream err) {
        if (jdbcUrl == null) {
            err.println("[wyrd] no database configured");
            return 2;
        }
        var bonds = new BondStore(jdbcUrl).all();
        if (bonds.isEmpty()) {
            out.println("(no bonds)");
            return 0;
        }
        out.printf("%-50s %-50s %-50s %-15s %s%n",
            "BOND_ID", "AGENT_A_DID", "AGENT_B_DID", "DEPTH", "ACTIVE");
        for (var b : bonds) {
            out.printf("%-50s %-50s %-50s %-15s %s%n",
                truncate(b.bondId(), 50),
                truncate(b.agentADid(), 50),
                truncate(b.agentBDid(), 50),
                b.depth(),
                b.active());
        }
        return 0;
    }

    /** Resolve JDBC URL: env → default sqlite path. */
    static String resolveJdbcUrl() {
        var env = WyrdConfig.get().jdbcUrl();
        if (env != null && !env.isBlank()) return env;
        var p = defaultSqlitePath();
        if (!Files.exists(p)) return null;
        return "jdbc:sqlite:" + p.toAbsolutePath();
    }

    private static Path defaultSqlitePath() {
        var dataDir = WyrdConfig.get().dataDir();
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = System.getProperty("user.home") + "/.wyrdsekai";
        }
        return Path.of(dataDir, "world.db");
    }

    private static String requireArg(String[] args, int i, String flag, PrintStream err) {
        if (i >= args.length) {
            err.println("[wyrd] " + flag + " requires a value");
            throw new IllegalArgumentException(flag + " missing value");
        }
        return args[i];
    }

    private static String[] tail(String[] arr, int from) {
        if (from >= arr.length) return new String[0];
        var out = new String[arr.length - from];
        System.arraycopy(arr, from, out, 0, out.length);
        return out;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  wyrd bond create <player-username> <companion-did> [--depth ACQUAINTANCE|ITEM|SACRED|SOUL_REF|SOUL_INGRAINED]");
        out.println("  wyrd bond list");
        out.println();
        out.println("Records a bond between a player and a companion (steward bootstrap path).");
        out.println("Writes to BondStore + companion's SoulManifest. Restart wyrdsekai after create.");
        out.println();
        out.println("Find a companion's DID with 'wyrd bond list' (lists bonded agents) or by");
        out.println("inspecting soul_manifests in world.db.");
    }
}
