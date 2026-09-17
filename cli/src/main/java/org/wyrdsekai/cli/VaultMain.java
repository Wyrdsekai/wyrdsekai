package org.wyrdsekai.cli;

import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.vault.Vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * The offline side of the vault, run by {@code wyrd vault} with the server down or up:
 *
 * <pre>
 *   list    --vault DIR
 *   restore --vault DIR --to DIR [ID|latest]      rebuild every file of a copy under a directory
 *   stage   --vault DIR --data DIR [ID|latest]    rebuild world.db and stage it for the next boot
 * </pre>
 *
 * Staging reuses the maintenance service's marker: the server applies it before opening the
 * record, keeps the displaced database beside it, and never boot-loops on a bad copy.
 */
public final class VaultMain {

    private VaultMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(64); }
        String vaultDir = null, to = null, data = null, id = "latest";
        var cmd = args[0];
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--vault" -> vaultDir = args[++i];
                case "--to" -> to = args[++i];
                case "--data" -> data = args[++i];
                default -> id = args[i];
            }
        }
        if (vaultDir == null) { System.err.println("--vault DIR is required"); System.exit(64); }
        var vault = new Vault(data == null ? Path.of(vaultDir).getParent() : Path.of(data), Path.of(vaultDir));
        switch (cmd) {
            case "list" -> {
                var all = vault.list();
                if (all.isEmpty()) { System.out.println("The vault is empty."); return; }
                System.out.printf("%-22s %-24s %-5s %6s %10s  %s%n", "ID", "AT", "KEEP", "FILES", "MB", "REASON");
                for (var m : all) {
                    System.out.printf("%-22s %-24s %-5s %6d %10.1f  %s%s%n", m.id(), m.at(), m.keep() ? "keep" : "",
                        m.files().size(), m.bytes() / 1e6, m.reason(),
                        m.drill() == null ? "" : (m.drill().ok() ? "  [drill passed]" : "  [drill FAILED]"));
                }
            }
            case "restore" -> {
                if (to == null) { System.err.println("--to DIR is required"); System.exit(64); }
                var m = vault.find(id).orElse(null);
                if (m == null) { System.err.println("No copy called " + id); System.exit(1); }
                var target = Path.of(to);
                Files.createDirectories(target);
                var failed = vault.restoreTo(m, target);
                if (failed.isEmpty()) {
                    System.out.println("Rebuilt " + m.files().size() + " file(s) from " + m.id() + " under " + target);
                } else {
                    System.err.println("Rebuilt with " + failed.size() + " failure(s):");
                    failed.forEach(f -> System.err.println("  " + f));
                    System.exit(1);
                }
            }
            case "stage" -> {
                if (data == null) { System.err.println("--data DIR is required"); System.exit(64); }
                var m = vault.find(id).orElse(null);
                if (m == null) { System.err.println("No copy called " + id); System.exit(1); }
                var dataDir = Path.of(data);
                var backups = dataDir.resolve("backups");
                Files.createDirectories(backups);
                var scratch = Files.createTempDirectory(backups, ".vault-stage-");
                var failed = vault.restoreTo(m, scratch);
                var db = scratch.resolve("world.db");
                if (!failed.isEmpty() || !Files.exists(db)) {
                    System.err.println("Could not rebuild world.db from " + m.id() + ": " + failed);
                    System.exit(1);
                }
                var bak = backups.resolve("vault.world.db." + m.id() + ".bak");
                Files.move(db, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // Everything else in the copy is left beside the staged database for the steward.
                var rest = backups.resolve("vault.files." + m.id());
                if (Files.exists(rest)) deleteTree(rest);
                Files.move(scratch, rest);
                var marker = new LinkedHashMap<String, Object>();
                marker.put("snapshotId", "vault:" + m.id());
                marker.put("backupFile", bak.toAbsolutePath().toString());
                marker.put("stagedBy", "wyrd vault stage");
                marker.put("stagedAt", Instant.now().toString());
                Files.writeString(dataDir.resolve("restore-staged.json"), Json.mapper().writeValueAsString(marker));
                System.out.println("Staged: the record from " + m.id() + " applies at the next start (the displaced database is kept).");
                System.out.println("Other files from the copy are under " + rest + " for you to put back by hand if needed.");
            }
            default -> { usage(); System.exit(64); }
        }
    }

    private static void deleteTree(Path p) throws java.io.IOException {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (java.io.IOException ignored) { } });
        }
    }

    private static void usage() {
        System.err.println("usage: VaultMain list|restore|stage --vault DIR [--to DIR] [--data DIR] [ID|latest]");
    }
}
