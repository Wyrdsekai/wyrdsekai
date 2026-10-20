package org.wyrdsekai.core.soul;

import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.agent.Companions;
import org.wyrdsekai.core.agent.SoulNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * {@code wyrd soul list | rename <old> <new> | archive <name> [reason]} — the souls on
 * this node, by name.
 *
 * <p>Renaming keeps the soul: same DID, same entity id, same history; only the name on
 * the manifest changes, in a new version. Archiving retires a soul from the respawn
 * sweep without deleting anything; a household that woke up with two after a rename
 * can put the accidental one to rest and keep its record.</p>
 *
 * <p>{@code identity <did-or-name> <text-file>} writes her description of herself into her
 * record: the same lines the node's door runs ({@code POST /api/soul/{did}/identity}), for
 * {@code wyrd brain move}, which runs on a stopped node (2026-10-19).</p>
 */
public final class SoulAdminMain {

    private SoulAdminMain() {}

    public static void main(String[] args) {
        if (args.length == 0) { usage(); System.exit(64); }
        var dbPath = SystemPaths.dbPath();
        var soulsDir = SystemPaths.soulsDir();
        var store = new SqlSoulStore("jdbc:sqlite:" + dbPath.toAbsolutePath());
        switch (args[0]) {
            case "list" -> list(store, soulsDir);
            case "rename" -> {
                if (args.length < 3) { usage(); System.exit(64); }
                System.exit(rename(store, soulsDir, args[1], args[2]));
            }
            case "archive" -> {
                if (args.length < 2) { usage(); System.exit(64); }
                var reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                    : "archived by the steward";
                System.exit(archive(store, soulsDir, args[1], reason));
            }
            case "identity" -> {
                if (args.length < 3) { usage(); System.exit(64); }
                System.exit(identity(store, args[1], args[2]));
            }
            default -> { usage(); System.exit(64); }
        }
    }

    private static void usage() {
        System.err.println("usage: wyrd soul list | rename <old-name> <new-name> | archive <name> [reason] | identity <did-or-name> <text-file>");
    }

    private static void list(SqlSoulStore store, Path soulsDir) {
        var souls = store.listLatest();
        if (souls.isEmpty()) { System.out.println("No souls on this node."); return; }
        System.out.printf("%-16s %-24s %-12s %-8s %s%n", "NAME", "ENTITY", "BORN HERE", "VERSION", "DID");
        for (var m : souls) {
            var p = m.profile();
            if (p == null || !"agent".equals(p.entityType())) continue;
            System.out.printf("%-16s %-24s %-12s %-8d %s%n", p.name(), p.entityId(),
                SoulNames.locallyBorn(soulsDir, m) ? "yes" : "no", m.manifestVersion(), m.did());
        }
    }

    private static SoulManifest byName(SqlSoulStore store, String name) {
        for (var m : store.listLatest()) {
            var p = m.profile();
            if (p != null && "agent".equals(p.entityType()) && p.name() != null && p.name().equalsIgnoreCase(name.trim())) return m;
        }
        return null;
    }

    private static int rename(SqlSoulStore store, Path soulsDir, String from, String to) {
        var m = byName(store, from);
        if (m == null) { System.err.println("No soul named '" + from + "' here (wyrd soul list)."); return 1; }
        if (byName(store, to) != null) { System.err.println("A soul named '" + to + "' already lives here."); return 1; }
        var slug = SoulNames.slug(to);
        if (slug.isBlank()) { System.err.println("'" + to + "' leaves no usable name."); return 1; }
        var p = m.profile();
        var renamed = Companions.forPersistedSoul(to.trim(), p.entityId(), p.archetype());
        store.store(m.withProfile(renamed).withManifestVersion(m.manifestVersion() + 1, Instant.now()));
        System.out.printf("Renamed '%s' → '%s' — same soul (%s), same entity id (%s), manifest v%d.%n",
            p.name(), to.trim(), m.did(), p.entityId(), m.manifestVersion() + 1);
        System.out.println("Set WYRDSEKAI_COMPANION_NAME" + " to '" + to.trim() + "' in the conf and restart; she keeps everything.");
        return 0;
    }

    /**
     * Her description of herself, into her record, in a new manifest version. Prints
     * {@code version=<n>} last, for the launcher. 1 to 2000 characters, as at the door.
     */
    static int identity(SqlSoulStore store, String who, String file) {
        String text;
        try {
            text = Files.readString(Path.of(file)).strip();
        } catch (IOException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
            return 1;
        }
        if (text.isEmpty() || text.length() > 2000) {
            System.err.println("text: 1 to 2000 characters (" + text.length() + " given)");
            return 1;
        }
        var m = who.startsWith("did:") ? store.latest(who).orElse(null) : byName(store, who);
        if (m == null) { System.err.println("No soul '" + who + "' here (wyrd soul list)."); return 1; }
        var next = m.withResidentIdentity(text).withManifestVersion(m.manifestVersion() + 1, Instant.now());
        store.store(next);
        var name = m.profile() != null && m.profile().name() != null ? m.profile().name() : m.did();
        System.out.printf("Her description of herself is written: %s, manifest v%d.%n", name, next.manifestVersion());
        System.out.println("version=" + next.manifestVersion());
        return 0;
    }

    private static int archive(SqlSoulStore store, Path soulsDir, String name, String reason) {
        var m = byName(store, name);
        if (m == null) { System.err.println("No soul named '" + name + "' here (wyrd soul list)."); return 1; }
        store.archive(m.did(), reason);
        System.out.printf("Archived '%s' (%s): she will not be respawned; nothing was deleted.%n", m.profile().name(), m.did());
        return 0;
    }
}
