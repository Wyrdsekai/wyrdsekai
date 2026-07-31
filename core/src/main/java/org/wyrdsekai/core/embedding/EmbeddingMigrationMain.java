package org.wyrdsekai.core.embedding;

import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.search.EmbeddingService;

import java.io.PrintStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CLI entry point for {@code wyrd embed-migrate}. Mirrors {@code NamingAdminMain}'s
 * shape — exit 0 success, 1 user-visible error, 2 internal failure. Never throws
 * past {@link #main}.
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code --plan} — dry-run, print pending tables + estimated rows.</li>
 *   <li>{@code --run} — execute the migration.</li>
 *   <li>{@code --status} — print current per-table state.</li>
 *   <li>{@code --reset <table>} — clear state for one table to force re-migration.</li>
 * </ul>
 */
public final class EmbeddingMigrationMain {

    private EmbeddingMigrationMain() {}

    public static void main(String[] args) {
        int exit = run(System.out, System.err, args);
        System.exit(exit);
    }

    /** Test-friendly entry — does not call System.exit. */
    public static int run(PrintStream out, PrintStream err, String[] args) {
        try {
            if (args == null || args.length == 0) {
                printUsage(out);
                return 1;
            }
            var cmd = args[0];
            switch (cmd) {
                case "--plan", "plan" -> { return cmdPlan(out, err); }
                case "--run", "run" -> { return cmdRun(out, err); }
                case "--status", "status" -> { return cmdStatus(out, err); }
                case "--reset", "reset" -> {
                    if (args.length < 2) {
                        err.println("error: --reset requires a table name");
                        printUsage(err);
                        return 1;
                    }
                    return cmdReset(out, err, args[1]);
                }
                case "--help", "-h", "help" -> {
                    printUsage(out);
                    return 0;
                }
                default -> {
                    err.println("unknown subcommand: " + cmd);
                    printUsage(err);
                    return 1;
                }
            }
        } catch (Exception e) {
            err.println("internal error: " + e.getMessage());
            e.printStackTrace(err);
            return 2;
        }
    }

    // ── Commands ────────────────────────────────────────────────────────

    private static int cmdPlan(PrintStream out, PrintStream err) {
        var jdbcUrl = resolveJdbcUrl(err);
        if (jdbcUrl == null) return 1;
        var modelVersion = EmbeddingService.currentModelVersion();
        out.println("Embedding migration plan");
        out.println("  target model: " + modelVersion);
        out.println("  jdbc:         " + jdbcUrl);
        out.println();

        EmbeddingMigration mig;
        try {
            mig = EmbeddingMigration.createDefault(jdbcUrl);
        } catch (IllegalStateException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
        var plan = mig.plan();
        if (plan.entries().isEmpty()) {
            out.println("(no migrators registered)");
            return 0;
        }
        out.printf("%-28s %12s %18s %s%n",
            "TABLE", "EST. ROWS", "STATUS", "LAST CURSOR");
        out.println("-".repeat(80));
        for (var e : plan.entries()) {
            String status = e.alreadyComplete() ? "ALREADY DONE"
                : (e.lastProcessedId() != null ? "RESUMABLE" : "PENDING");
            String cursor = e.lastProcessedId() == null ? "-" : truncate(e.lastProcessedId(), 24);
            out.printf("%-28s %12d %18s %s%n",
                e.tableName(),
                Math.max(0, e.estimatedRows()),
                status,
                cursor);
        }
        out.println();
        out.println("total rows pending: " + plan.totalRows());
        out.println();
        out.println("dry-run only — pass --run to execute.");
        return 0;
    }

    private static int cmdRun(PrintStream out, PrintStream err) {
        var jdbcUrl = resolveJdbcUrl(err);
        if (jdbcUrl == null) return 1;

        out.println("Embedding migration: starting");
        out.println("  target model: " + EmbeddingService.currentModelVersion());
        out.println("  jdbc:         " + jdbcUrl);
        out.println();

        EmbeddingMigration mig;
        try {
            mig = EmbeddingMigration.createDefault(jdbcUrl);
        } catch (IllegalStateException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }

        var report = mig.run((table, processed, total) -> {
            // Print one-line progress per batch. Total may be -1 (unknown) so guard.
            if (total > 0) {
                out.printf("  [%s] %d / %d%n", table, processed, total);
            } else {
                out.printf("  [%s] %d%n", table, processed);
            }
        });

        out.println();
        out.println("Migration complete:");
        for (var s : report.tables()) {
            String suffix = s.skipped() ? "(already done)" : "";
            out.printf("  %-28s %d rows %s%n", s.tableName(), s.rowsMigrated(), suffix);
        }
        out.println("  total: " + report.totalRows() + " rows");
        return 0;
    }

    private static int cmdStatus(PrintStream out, PrintStream err) {
        var jdbcUrl = resolveJdbcUrl(err);
        if (jdbcUrl == null) return 1;
        var mig = new EmbeddingMigration(jdbcUrl,
            t -> { throw new UnsupportedOperationException("status only"); },
            EmbeddingService.currentModelVersion(),
            List.of(
                new SoulFragmentEmbeddingMigrator(),
                new ArtifactSignificanceEmbeddingMigrator()));
        var states = mig.status();
        if (states.isEmpty()) {
            out.println("no migration state recorded — try --plan");
            return 0;
        }
        out.printf("%-28s %-32s %-10s %-25s %s%n",
            "TABLE", "MODEL", "ROWS", "STARTED", "COMPLETED");
        out.println("-".repeat(110));
        for (var s : states) {
            out.printf("%-28s %-32s %-10d %-25s %s%n",
                s.tableName(),
                truncate(s.modelVersion(), 30),
                s.processedCount(),
                fmt(s.startedAt()),
                s.completedAt() == null ? "-" : fmt(s.completedAt()));
        }
        return 0;
    }

    private static int cmdReset(PrintStream out, PrintStream err, String table) {
        var jdbcUrl = resolveJdbcUrl(err);
        if (jdbcUrl == null) return 1;
        var mig = new EmbeddingMigration(jdbcUrl,
            t -> { throw new UnsupportedOperationException("reset only"); },
            EmbeddingService.currentModelVersion(),
            List.of(
                new SoulFragmentEmbeddingMigrator(),
                new ArtifactSignificanceEmbeddingMigrator()));
        var registered = mig.registeredTables();
        if (!registered.contains(table)) {
            err.println("error: unknown table '" + table + "'");
            err.println("registered tables: " + String.join(", ", registered));
            return 1;
        }
        boolean removed = mig.reset(table);
        if (removed) {
            out.println("reset state for " + table + " — next --run will re-migrate it");
        } else {
            out.println("no state recorded for " + table + " (nothing to reset)");
        }
        return 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String resolveJdbcUrl(PrintStream err) {
        var fromConfig = WyrdConfig.get().jdbcUrl();
        if (fromConfig != null && !fromConfig.isBlank()) return fromConfig;
        // Fallback: dataDir/world.db so a fresh install can still run --plan.
        var p = SystemPaths.dbPath();
        return "jdbc:sqlite:" + p.toAbsolutePath();
    }

    private static String fmt(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static void printUsage(PrintStream out) {
        out.println("usage: wyrd embed-migrate <command>");
        out.println();
        out.println("commands:");
        out.println("  --plan              show pending migration without writing");
        out.println("  --run               execute the migration");
        out.println("  --status            show current per-table state");
        out.println("  --reset <table>     clear state for one table to force re-run");
        out.println();
        out.println("Re-embeds all stored embeddings with the currently bundled multilingual");
        out.println("model. Resumable, idempotent, atomic per batch (100 rows).");
    }
}
