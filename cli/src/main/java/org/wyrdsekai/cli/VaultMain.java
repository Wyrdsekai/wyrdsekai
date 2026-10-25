package org.wyrdsekai.cli;

import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.vault.Vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;

/**
 * The offline side of the vault, run by {@code wyrd vault} with the server down or up:
 *
 * <pre>
 *   key     --vault DIR                          the key file and its id
 *   list    --vault DIR
 *   restore --vault DIR --to DIR [ID|latest]      rebuild every file of a copy under a directory
 *   stage   --vault DIR --data DIR [ID|latest]    rebuild world.db and stage it for the next boot
 *   forget  --vault DIR PATH --yes                remove a store nothing reads (never the vault itself)
 * </pre>
 *
 * Staging reuses the maintenance service's marker: the server applies it before opening the
 * record, keeps the displaced database beside it, and never boot-loops on a bad copy.
 */
public final class VaultMain {

    private VaultMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(64); }
        String vaultDir = null, to = null, data = null, key = null, id = "latest";
        boolean yes = false;
        var cmd = args[0];
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--vault" -> vaultDir = args[++i];
                case "--to" -> to = args[++i];
                case "--data" -> data = args[++i];
                case "--key" -> key = args[++i];
                case "--yes" -> yes = true;
                default -> id = args[i];
            }
        }
        if (vaultDir == null) { System.err.println("--vault DIR is required"); System.exit(64); }
        if ("forget".equals(cmd)) { System.exit(forget(Path.of(vaultDir), id, yes)); }
        var dataPath = data == null ? Path.of(vaultDir).getParent() : Path.of(data);
        var keyPath = key != null ? Path.of(key) : dataPath.resolve(Vault.KEY_FILE);
        if (!Files.isRegularFile(keyPath) && !"key".equals(cmd)) {
            System.err.println("No vault key at " + keyPath + ". The store is sealed; pass the node's vault.key with --key FILE.");
            System.exit(1);
        }
        var vault = new Vault(dataPath, Path.of(vaultDir), keyPath);
        if (vault.keyMismatch() != null) { System.err.println(vault.keyMismatch()); System.exit(1); }
        switch (cmd) {
            case "key" -> {
                System.out.println("key file: " + vault.keyFile());
                System.out.println("key id:   " + vault.keyId());
                System.out.println("Every chunk and manifest in " + vault.dir() + " is sealed with this key. "
                    + "Keep a copy of the key file somewhere that is not this disk; an offsite copy of the store is unreadable without it.");
            }
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
                Files.move(db, bak, StandardCopyOption.REPLACE_EXISTING);
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

    /**
     * Remove a store nothing reads: an earlier vault at a place the vault was moved off (the
     * household node carried 145 GB of one for a month, 2026-10-25). Refuses the vault itself,
     * anything that is not a vault store, and runs only with {@code --yes}. Needs no key.
     */
    static int forget(Path vaultDir, String path, boolean yes) {
        if (path == null || "latest".equals(path)) { System.err.println("forget needs the PATH of the store to remove"); return 64; }
        var target = Path.of(path).toAbsolutePath().normalize();
        var active = vaultDir.toAbsolutePath().normalize();
        try {
            if (Files.exists(target) && Files.exists(active) && Files.isSameFile(target, active)) {
                System.err.println(target + " is the vault in use; it is not forgotten."); return 1;
            }
        } catch (IOException e) {
            if (target.equals(active)) { System.err.println(target + " is the vault in use; it is not forgotten."); return 1; }
        }
        if (target.equals(active) || active.startsWith(target)) { System.err.println(target + " holds the vault in use; it is not forgotten."); return 1; }
        if (!Files.isDirectory(target.resolve("manifests")) || !Files.isDirectory(target.resolve("chunks"))) {
            System.err.println(target + " is not a vault store (no manifests/ and chunks/ in it); nothing removed."); return 1;
        }
        long copies = 0, bytes = 0;
        try (var s = Files.list(target.resolve("manifests"))) {
            copies = s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".head.json")).count();
        } catch (IOException ignored) { /* counted as unknown */ }
        try (var s = Files.walk(target)) {
            bytes = s.filter(Files::isRegularFile).mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException ignored) { /* counted as unknown */ }
        System.out.printf("%s: %d copies, %.1f GB, not the vault in use (%s)%n", target, copies, bytes / 1e9, active);
        if (!yes) { System.out.println("Nothing removed. Run again with --yes to remove it."); return 2; }
        try {
            deleteTree(target);
        } catch (IOException e) {
            System.err.println("Could not remove " + target + ": " + e.getMessage()); return 1;
        }
        System.out.printf("Removed %s (%.1f GB freed).%n", target, bytes / 1e9);
        return 0;
    }

    private static void deleteTree(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) { } });
        }
    }

    private static void usage() {
        System.err.println("usage: VaultMain key|list|restore|stage|forget --vault DIR [--to DIR] [--data DIR] [--key FILE] [--yes] [ID|latest|PATH]");
    }
}
