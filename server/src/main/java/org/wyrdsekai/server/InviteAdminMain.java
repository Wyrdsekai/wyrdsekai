package org.wyrdsekai.server;

import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Headless {@code wyrd invite} admin CLI.
 *
 * <p>Creates invite codes without a steward session cookie — closes the
 * gap where new accounts couldn't be minted after
 * {@code openRegistration} auto-closed. The operator already has filesystem
 * access to the data directory, so direct {@link InviteService} usage is
 * appropriate; the DB schema still pins {@code created_by} to a real steward
 * via FK, so we pick the first steward (or one named via {@code --as}).</p>
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   wyrd invite create &lt;name&gt; [--role member|guest|child] [--ttl-hours 24] [--as &lt;steward-username&gt;]
 *   wyrd invite list
 * </pre>
 *
 * <p>On success {@code create} prints the 4-word passphrase on stdout (nothing
 * else — machine-parseable). Wraps {@link System#exit}: {@code 0} success,
 * {@code 1} user error, {@code 2} internal.</p>
 */
public final class InviteAdminMain {

    private InviteAdminMain() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err, args));
    }

    static int run(PrintStream out, PrintStream err, String... args) {
        return run(resolveJdbcUrl(), out, err, args);
    }

    /**
     * Test-friendly entry: caller supplies the JDBC URL instead of going through
     * env lookup. Production {@link #main} uses {@link #resolveJdbcUrl()}.
     */
    static int run(String jdbcUrl, PrintStream out, PrintStream err, String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }
        var cmd = args[0];
        try {
            return switch (cmd) {
                case "create" -> doCreate(jdbcUrl, out, err, tail(args, 1));
                case "bootstrap" -> doBootstrap(jdbcUrl, out, err, tail(args, 1));
                case "list"   -> doList(jdbcUrl, out, err);
                default -> {
                    err.println("[wyrd] unknown invite command: " + cmd);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (Exception e) {
            err.println("[wyrd] invite command failed: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Mint a steward-bootstrap invite. Only succeeds on a fresh install (no
     * users yet). Used by .deb postinst / .pkg postinstall to give the
     * operator a one-shot token for first SSH login.
     * phase 2.
     */
    private static int doBootstrap(String jdbcUrl, PrintStream out, PrintStream err, String[] args) {
        String intendedName = "steward";
        long ttlHours = 24;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--name" -> intendedName = requireArg(args, ++i, "--name", err);
                case "--ttl-hours" -> ttlHours = Long.parseLong(requireArg(args, ++i, "--ttl-hours", err));
                default -> {
                    err.println("[wyrd] unexpected arg: " + args[i]);
                    return 1;
                }
            }
        }
        if (jdbcUrl == null) {
            err.println("[wyrd] no WYRDSEKAI_JDBC_URL configured and default SQLite not found at "
                + defaultSqlitePath());
            return 2;
        }
        var dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        var invites = new InviteService(jdbcUrl, dialect);
        try {
            var invite = invites.createBootstrapInvite(intendedName, ttlHours * 3600L);
            // Stdout: code only — machine-parseable for postinst piping.
            out.println(invite.code());
            err.println("[wyrd] steward-bootstrap invite minted (intendedName=" + intendedName
                + ", ttl=" + ttlHours + "h, expires=" + invite.expiresAt() + ")");
            err.println("[wyrd] Use within " + ttlHours + "h: ssh " + intendedName
                + "@<host> -p 7022  (password = the code printed to stdout)");
            return 0;
        } catch (IllegalStateException e) {
            err.println("[wyrd] " + e.getMessage());
            return 1;
        }
    }

    private static int doCreate(String jdbcUrl, PrintStream out, PrintStream err, String[] args) {
        if (args.length == 0) {
            err.println("Usage: wyrd invite create <name> [--role <role>] [--ttl-hours <n>] [--as <steward>]");
            return 1;
        }
        String intendedName = null;
        String role = "member";
        long ttlHours = 24;
        String stewardUsername = null;
        for (int i = 0; i < args.length; i++) {
            var a = args[i];
            switch (a) {
                case "--role" -> role = requireArg(args, ++i, "--role", err);
                case "--ttl-hours" -> ttlHours = Long.parseLong(requireArg(args, ++i, "--ttl-hours", err));
                case "--as" -> stewardUsername = requireArg(args, ++i, "--as", err);
                default -> {
                    if (intendedName == null) intendedName = a;
                    else {
                        err.println("[wyrd] unexpected arg: " + a);
                        return 1;
                    }
                }
            }
        }
        if (intendedName == null || intendedName.isBlank()) {
            err.println("[wyrd] invite create requires a name (first positional arg)");
            return 1;
        }

        if (jdbcUrl == null) {
            err.println("[wyrd] no WYRDSEKAI_JDBC_URL configured and default SQLite not found at "
                + defaultSqlitePath());
            return 2;
        }

        var dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        var auth = new AuthService(jdbcUrl, dialect);
        String stewardId;
        if (stewardUsername != null) {
            var user = auth.findUserByUsername(stewardUsername);
            if (user.isEmpty()) {
                err.println("[wyrd] steward user not found: " + stewardUsername);
                return 1;
            }
            if (!"steward".equals(user.get().role())) {
                err.println("[wyrd] user " + stewardUsername + " is not a steward (role="
                    + user.get().role() + ")");
                return 1;
            }
            stewardId = user.get().id();
        } else {
            stewardId = findAnySteward(jdbcUrl);
            if (stewardId == null) {
                err.println("[wyrd] no steward account exists — create one via redeem flow first");
                return 1;
            }
        }

        var invites = new InviteService(jdbcUrl, dialect);
        var expirySeconds = ttlHours * 3600L;
        var invite = invites.createInvite(intendedName, role, stewardId, expirySeconds);
        // Stdout: code only — machine-parseable. Details to stderr.
        out.println(invite.code());
        err.println("[wyrd] invite minted for '" + intendedName + "' (role=" + role
            + ", ttl=" + ttlHours + "h, id=" + invite.id() + ")");
        return 0;
    }

    private static int doList(String jdbcUrl, PrintStream out, PrintStream err) {
        if (jdbcUrl == null) {
            err.println("[wyrd] no database configured");
            return 2;
        }
        var dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        var invites = new InviteService(jdbcUrl, dialect);
        var list = invites.listInvites();
        if (list.isEmpty()) {
            out.println("(no invites)");
            return 0;
        }
        out.printf("%-36s %-20s %-20s %-8s %s%n", "ID", "NAME", "CODE", "ROLE", "STATUS");
        for (var inv : list) {
            var status = inv.consumedBy() != null ? "consumed"
                : inv.expiresAt().isBefore(Instant.now()) ? "expired" : "pending";
            out.printf("%-36s %-20s %-20s %-8s %s%n",
                inv.id(), truncate(inv.intendedName(), 20),
                inv.code(), inv.role(), status);
        }
        return 0;
    }

    /** Find any steward id (deterministic by creation order). */
    private static String findAnySteward(String jdbcUrl) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT id FROM users WHERE role = 'steward' ORDER BY created_at LIMIT 1")) {
            var rs = stmt.executeQuery();
            return rs.next() ? rs.getString("id") : null;
        } catch (SQLException e) {
            return null;
        }
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
        out.println("  wyrd invite create <name> [--role member|guest|child] [--ttl-hours 24] [--as <steward>]");
        out.println("  wyrd invite bootstrap [--name steward] [--ttl-hours 24]");
        out.println("  wyrd invite list");
        out.println();
        out.println("Creates invite codes headlessly (no browser / steward session required).");
        out.println("'create' requires an existing steward; 'bootstrap' is for fresh installs only.");
        out.println("On success, `create`/`bootstrap` print the passphrase to stdout — all else to stderr.");
    }
}
