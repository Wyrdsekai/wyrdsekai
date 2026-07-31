package org.wyrdsekai.core.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code wyrd state dump} — Phase 0 of.
 *
 * <p>Read-only walker that emits a unified JSON snapshot of "what this
 * household currently believes about itself" by inspecting every store at
 * once: {@code world.db}, {@code souls/}, {@code node-identity.json},
 * {@code wyrdsekai.conf}, {@code contacts}, and the derivative caches
 * (Lucene, packs, scripts, vault).
 *
 * <p>Fixes nothing. Makes fragmentation <i>visible</i>. Forcing function
 * for Phases 1–4: every drift bug after this command exists gets diagnosed
 * from one snapshot instead of four log tails.
 *
 * <p>Output:
 * <ul>
 *   <li>Default: pretty-printed JSON to stdout.</li>
 *   <li>{@code --summary}: human-readable counts + fragmentation alerts.</li>
 *   <li>{@code --out FILE}: write JSON to file instead of stdout.</li>
 * </ul>
 *
 * <p>Secrets policy: never emits the encrypted private key, the
 * SSH host key, or {@code TOKEN}-suffixed config values. Public keys,
 * DIDs, and structural metadata are fair game.
 */
public final class StateDumpMain {

    private static final String VERSION = "1.0";

    /** State-bearing tables that get row-detail expansion (DIDs, statuses, etc.). */
    private static final Set<String> DETAIL_TABLES = Set.of(
        "users", "bonds", "bilateral_agreements", "foreign_identities",
        "residency", "invites", "soul_manifests", "grants", "zone_manifests",
        "transit_tokens", "household_config", "paired_devices"
    );

    /** Tables we skip detail for — large, append-only, or noisy. */
    private static final Set<String> SKIP_DETAIL = Set.of(
        "event_journal", "event_tag", "snapshot", "durable_state",
        "audit_log", "ledger_transactions", "vitality_snapshots",
        "channel_processed", "memory_edges", "memory_entities"
    );

    private StateDumpMain() {}

    public static void main(String[] args) throws Exception {
        boolean summary = false;
        Path outFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--summary", "-s" -> summary = true;
                case "--out", "-o" -> outFile = Path.of(args[++i]);
                case "--help", "-h" -> { printHelp(); return; }
                default -> {
                    System.err.println("Unknown arg: " + args[i]);
                    printHelp();
                    System.exit(2);
                }
            }
        }

        ObjectNode root = walk();

        if (summary) {
            printSummary(root, System.out);
            return;
        }

        String json = Json.mapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(root);
        if (outFile != null) {
            Files.writeString(outFile, json);
            System.err.println("Wrote " + outFile.toAbsolutePath());
        } else {
            System.out.println(json);
        }
    }

    private static void printHelp() {
        System.out.println("""
            wyrd state dump — unified snapshot of household runtime state.

            Usage: wyrd state dump [--summary|-s] [--out FILE]

            Options:
              --summary, -s    Print human-readable counts + fragmentation alerts.
              --out, -o FILE   Write JSON to FILE instead of stdout.
              --help, -h       Show this help.

            Walks: world.db, souls/, node-identity.json, wyrdsekai.conf,
            contacts, and derivative cache directories. Read-only.
            """);
    }

    // ── Walker ────────────────────────────────────────────────────────────

    private static ObjectNode walk() {
        var m = Json.mapper();
        ObjectNode root = m.createObjectNode();

        root.set("meta", buildMeta(m.createObjectNode()));
        root.set("node", walkNodeIdentity(m.createObjectNode()));
        root.set("config", walkConfig(m.createObjectNode()));
        root.set("database", walkDatabase(m.createObjectNode()));
        root.set("souls", walkSouls(m.createObjectNode()));
        root.set("contacts", walkContacts(m.createObjectNode()));
        root.set("filesystem", walkFilesystem(m.createObjectNode()));
        root.set("fragmentation", buildFragmentation(root));

        return root;
    }

    private static ObjectNode buildMeta(ObjectNode out) {
        out.put("tool", "wyrd state dump");
        out.put("version", VERSION);
        out.put("timestamp", Instant.now().toString());
        out.put("host", hostname());
        out.put("data_dir", SystemPaths.dataDir().toString());
        out.put("system_service_mode", SystemPaths.isSystemService());
        return out;
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private static ObjectNode walkNodeIdentity(ObjectNode out) {
        Path p = SystemPaths.nodeIdentityFile();
        out.put("path", p.toString());
        if (!Files.exists(p)) {
            out.put("present", false);
            return out;
        }
        out.put("present", true);
        try {
            JsonNode n = Json.mapper().readTree(p.toFile());
            if (n.has("nodeId")) out.put("node_id", n.get("nodeId").asText());
            if (n.has("publicKey")) out.put("public_key", n.get("publicKey").asText());
            if (n.has("keyFormat")) out.put("key_format", n.get("keyFormat").asText());
            if (n.has("keyDerivation")) {
                JsonNode kd = n.get("keyDerivation");
                ObjectNode kdOut = Json.mapper().createObjectNode();
                if (kd.has("algorithm")) kdOut.put("algorithm", kd.get("algorithm").asText());
                if (kd.has("iterations")) kdOut.put("iterations", kd.get("iterations").asInt());
                out.set("key_derivation", kdOut);
            }
            // Never emit encryptedPrivateKey or salt.
        } catch (IOException e) {
            out.put("read_error", e.getMessage());
        }
        return out;
    }

    private static ObjectNode walkConfig(ObjectNode out) {
        Path p = SystemPaths.configFile();
        out.put("path", p.toString());
        out.put("present", Files.exists(p));
        if (!Files.exists(p)) return out;
        try {
            ArrayNode keys = out.putArray("keys");
            for (String line : Files.readAllLines(p)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                String key = s.substring(0, eq).trim();
                String val = s.substring(eq + 1).trim();
                if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                ObjectNode entry = keys.addObject();
                entry.put("key", key);
                entry.put("value", maskSecret(key, val));
            }
        } catch (IOException e) {
            out.put("read_error", e.getMessage());
        }
        return out;
    }

    private static String maskSecret(String key, String val) {
        String upper = key.toUpperCase();
        boolean secret = upper.contains("TOKEN") || upper.contains("PASSWORD")
            || upper.contains("SECRET") || upper.contains("KEY");
        if (!secret) return val;
        if (val.length() <= 6) return "***";
        return val.substring(0, 4) + "***" + val.substring(val.length() - 2);
    }

    private static ObjectNode walkDatabase(ObjectNode out) {
        Path p = SystemPaths.dbPath();
        out.put("path", p.toString());
        out.put("present", Files.exists(p));
        if (!Files.exists(p)) return out;
        try {
            out.put("size_bytes", Files.size(p));
        } catch (IOException ignored) {}

        String url = "jdbc:sqlite:" + p.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            // List tables.
            List<String> tables = new ArrayList<>();
            try (var stmt = conn.prepareStatement(
                    "SELECT name FROM sqlite_master WHERE type='table' "
                  + "AND name NOT LIKE 'sqlite_%' ORDER BY name");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            out.put("table_count", tables.size());
            ObjectNode tablesOut = out.putObject("tables");
            for (String t : tables) {
                tablesOut.set(t, walkTable(conn, t));
            }
        } catch (SQLException e) {
            out.put("connect_error", e.getMessage());
        }
        return out;
    }

    private static ObjectNode walkTable(Connection conn, String table) {
        ObjectNode t = Json.mapper().createObjectNode();
        try (var stmt = conn.prepareStatement("SELECT COUNT(*) FROM \"" + table + "\"");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) t.put("rows", rs.getInt(1));
        } catch (SQLException e) {
            t.put("error", e.getMessage());
            return t;
        }
        if (SKIP_DETAIL.contains(table) || !DETAIL_TABLES.contains(table)) return t;

        // Detail expansion for state-bearing tables.
        try {
            switch (table) {
                case "users" -> detailUsers(conn, t);
                case "bonds" -> detailBonds(conn, t);
                case "bilateral_agreements" -> detailAgreements(conn, t);
                case "foreign_identities" -> detailForeignIdentities(conn, t);
                case "residency" -> detailResidency(conn, t);
                case "invites" -> detailInvites(conn, t);
                case "soul_manifests" -> detailSoulManifests(conn, t);
                case "grants" -> detailGrants(conn, t);
                case "zone_manifests" -> detailZoneManifests(conn, t);
                case "transit_tokens" -> detailTransitTokens(conn, t);
                case "household_config" -> detailHouseholdConfig(conn, t);
                case "paired_devices" -> detailPairedDevices(conn, t);
                default -> { /* row count only */ }
            }
        } catch (SQLException e) {
            t.put("detail_error", e.getMessage());
        }
        return t;
    }

    private static void detailUsers(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT username, role, created_at FROM users ORDER BY created_at");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("username", rs.getString(1));
                r.put("role", rs.getString(2));
                r.put("created_at", rs.getString(3));
            }
        }
    }

    private static void detailBonds(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT bond_id, holder_user_id, companion_did, depth FROM bonds");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("bond_id", rs.getString(1));
                r.put("holder_user_id", rs.getString(2));
                r.put("companion_did", rs.getString(3));
                r.put("depth", rs.getInt(4));
            }
        }
    }

    private static void detailAgreements(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT partner_zone, status, agreed_at FROM bilateral_agreements");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("partner_zone", rs.getString(1));
                r.put("status", rs.getString(2));
                r.put("agreed_at", rs.getString(3));
            }
        }
    }

    private static void detailForeignIdentities(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("dids");
        try (var stmt = conn.prepareStatement("SELECT did, home_zone FROM foreign_identities");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("did", rs.getString(1));
                r.put("home_zone", rs.getString(2));
            }
        }
    }

    private static void detailResidency(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT did, zone_id, role, granted_at FROM residency");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("did", rs.getString(1));
                r.put("zone_id", rs.getString(2));
                r.put("role", rs.getString(3));
                r.put("granted_at", rs.getLong(4));
            }
        }
    }

    private static void detailInvites(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT intended_name, role, expires_at, redeemed_at, "
              + "CASE WHEN created_by IS NULL THEN 'bootstrap' ELSE created_by END "
              + "FROM invites ORDER BY expires_at DESC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("intended_name", rs.getString(1));
                r.put("role", rs.getString(2));
                r.put("expires_at", rs.getString(3));
                r.put("redeemed_at", rs.getString(4));
                r.put("created_by", rs.getString(5));
            }
        }
    }

    private static void detailSoulManifests(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("manifests");
        try (var stmt = conn.prepareStatement(
                "SELECT did, MAX(version), MAX(forged_at) FROM soul_manifests "
              + "WHERE archived = 0 GROUP BY did");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("did", rs.getString(1));
                r.put("version", rs.getInt(2));
                r.put("forged_at", rs.getString(3));
            }
        }
    }

    private static void detailGrants(Connection conn, ObjectNode t) throws SQLException {
        // Don't dump every grant — just a summary by capability + counts.
        ArrayNode arr = t.putArray("by_capability");
        try (var stmt = conn.prepareStatement(
                "SELECT capability, COUNT(*) FROM grants WHERE revoked_at IS NULL "
              + "GROUP BY capability ORDER BY COUNT(*) DESC");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("capability", rs.getString(1));
                r.put("active", rs.getInt(2));
            }
        }
    }

    private static void detailZoneManifests(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("zones");
        try (var stmt = conn.prepareStatement(
                "SELECT zone_id, version, published_at FROM zone_manifests");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("zone_id", rs.getString(1));
                r.put("version", rs.getInt(2));
                r.put("published_at", rs.getString(3));
            }
        }
    }

    private static void detailTransitTokens(Connection conn, ObjectNode t) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM transit_tokens WHERE used = 0");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) t.put("unused_tokens", rs.getInt(1));
        }
    }

    private static void detailHouseholdConfig(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("entries");
        try (var stmt = conn.prepareStatement(
                "SELECT key, value FROM household_config");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String k = rs.getString(1);
                ObjectNode r = arr.addObject();
                r.put("key", k);
                r.put("value", maskSecret(k, rs.getString(2)));
            }
        }
    }

    private static void detailPairedDevices(Connection conn, ObjectNode t) throws SQLException {
        ArrayNode arr = t.putArray("rows_detail");
        try (var stmt = conn.prepareStatement(
                "SELECT user_id, device_label, paired_at FROM paired_devices");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ObjectNode r = arr.addObject();
                r.put("user_id", rs.getString(1));
                r.put("device_label", rs.getString(2));
                r.put("paired_at", rs.getString(3));
            }
        }
    }

    // ── Souls filesystem walker ──────────────────────────────────────────

    private static ObjectNode walkSouls(ObjectNode out) {
        Path dir = SystemPaths.soulsDir();
        out.put("path", dir.toString());
        if (!Files.isDirectory(dir)) {
            out.put("present", false);
            return out;
        }
        out.put("present", true);
        ArrayNode manifests = out.putArray("manifests");
        ArrayNode didFiles = out.putArray("did_files");
        ArrayNode otherJson = out.putArray("other_json");
        ArrayNode subdirs = out.putArray("subdirs");
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    ObjectNode d = subdirs.addObject();
                    d.put("name", name);
                    d.put("file_count", countFiles(entry));
                } else if (name.endsWith(".did")) {
                    ObjectNode r = didFiles.addObject();
                    r.put("file", name);
                    try { r.put("did", Files.readString(entry).trim()); }
                    catch (IOException ignored) {}
                } else if (name.endsWith(".json") && Files.isRegularFile(entry)) {
                    ObjectNode inspected = inspectJson(entry);
                    if (inspected.path("kind").asText("").equals("soul_manifest")) {
                        manifests.add(inspected);
                    } else {
                        otherJson.add(inspected);
                    }
                }
            }
        } catch (IOException e) {
            out.put("read_error", e.getMessage());
        }
        return out;
    }

    /**
     * Inspect a .json file under souls/ and classify it. The dir today holds
     * a mix: actual soul manifests, CfC neural weight files, and forge
     * intermediates. We discriminate by top-level keys instead of trusting
     * the filename — manifests have {@code identity}/{@code profile}, CfC
     * weights have {@code w1}/{@code b1}.
     */
    private static ObjectNode inspectJson(Path file) {
        ObjectNode r = Json.mapper().createObjectNode();
        r.put("file", file.getFileName().toString());
        try {
            r.put("size_bytes", Files.size(file));
            JsonNode m = Json.mapper().readTree(file.toFile());

            if (m.has("w1") && m.has("b1")) {
                r.put("kind", "cfc_weights");
                if (m.has("w1") && m.get("w1").isArray()) {
                    r.put("w1_size", m.get("w1").size());
                }
                return r;
            }
            if (m.has("identity") || m.has("profile") || m.has("manifest")) {
                r.put("kind", "soul_manifest");
                JsonNode identity = m.path("identity");
                if (identity.has("did")) r.put("did", identity.get("did").asText());
                if (identity.has("companionName")) r.put("name", identity.get("companionName").asText());
                if (m.has("version")) r.put("version", m.get("version").asInt());
                if (m.has("forgedAt")) r.put("forged_at", m.get("forgedAt").asText());
                r.put("has_profile", !m.path("profile").isMissingNode());
                JsonNode voice = m.path("voiceProfile");
                r.put("has_voice_profile", !voice.isMissingNode() && !voice.isNull());
                JsonNode fragments = m.path("fragments");
                r.put("fragment_count", fragments.isArray() ? fragments.size() : 0);
                JsonNode bonds = m.path("bonds");
                r.put("embedded_bonds", bonds.isArray() ? bonds.size() : 0);
                JsonNode genome = m.path("genome");
                r.put("has_genome", !genome.isMissingNode() && !genome.isNull());
                return r;
            }
            r.put("kind", "unknown");
            ArrayNode keys = r.putArray("top_keys");
            m.fieldNames().forEachRemaining(keys::add);
        } catch (IOException e) {
            r.put("read_error", e.getMessage());
        }
        return r;
    }

    private static long countFiles(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) { return 0; }
    }

    // ── Contacts ─────────────────────────────────────────────────────────

    private static ObjectNode walkContacts(ObjectNode out) {
        Path p = SystemPaths.contactsFile();
        out.put("path", p.toString());
        out.put("present", Files.exists(p));
        if (!Files.exists(p)) return out;
        ArrayNode arr = out.putArray("entries");
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                String[] parts = s.split("\\s+", 3);
                if (parts.length < 2) continue;
                ObjectNode e = arr.addObject();
                e.put("alias", parts[0]);
                e.put("did", parts[1]);
                if (parts.length >= 3) e.put("label", parts[2]);
            }
        } catch (IOException e) {
            out.put("read_error", e.getMessage());
        }
        return out;
    }

    // ── Filesystem (derivative caches) ───────────────────────────────────

    private static ObjectNode walkFilesystem(ObjectNode out) {
        var paths = new TreeMap<String, Path>();
        paths.put("search", SystemPaths.searchDir());
        paths.put("packs", SystemPaths.packsDir());
        paths.put("scripts", SystemPaths.scriptsDir());
        paths.put("vault", SystemPaths.vaultDir());
        paths.put("backups", SystemPaths.backupsDir());
        paths.put("library_db", SystemPaths.libraryDb());
        paths.put("jetstream", SystemPaths.dataDir().resolve("jetstream"));
        paths.put("adapters", SystemPaths.dataDir().resolve("adapters"));
        paths.put("classifiers", SystemPaths.dataDir().resolve("classifiers"));

        for (var entry : paths.entrySet()) {
            Path p = entry.getValue();
            ObjectNode n = out.putObject(entry.getKey());
            n.put("path", p.toString());
            if (!Files.exists(p)) {
                n.put("present", false);
                continue;
            }
            n.put("present", true);
            long size = directorySize(p);
            n.put("size_bytes", size);
            n.put("size_human", humanBytes(size));
        }
        return out;
    }

    private static long directorySize(Path p) {
        if (!Files.exists(p)) return 0;
        if (Files.isRegularFile(p)) {
            try { return Files.size(p); } catch (IOException e) { return 0; }
        }
        try (var walk = Files.walk(p)) {
            return walk.filter(Files::isRegularFile)
                .mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } })
                .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ── Fragmentation report ─────────────────────────────────────────────

    private static ArrayNode buildFragmentation(ObjectNode root) {
        ArrayNode out = Json.mapper().createArrayNode();

        int dbSoulRows = root.path("database").path("tables")
            .path("soul_manifests").path("rows").asInt(0);
        int dbDistinctDids = root.path("database").path("tables")
            .path("soul_manifests").path("manifests").size();
        int fsManifestCount = root.path("souls").path("manifests").size();
        int fsOtherJson = root.path("souls").path("other_json").size();
        int dbBondRows = root.path("database").path("tables")
            .path("bonds").path("rows").asInt(0);
        int embeddedBondTotal = 0;
        int voiceProfilesEmbedded = 0;
        int fragmentsEmbedded = 0;
        for (JsonNode m : root.path("souls").path("manifests")) {
            embeddedBondTotal += m.path("embedded_bonds").asInt(0);
            if (m.path("has_voice_profile").asBoolean(false)) voiceProfilesEmbedded++;
            fragmentsEmbedded += m.path("fragment_count").asInt(0);
        }

        // Issue 1: soul manifest blob is in two places (DB + filesystem).
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "soul_manifest_blob");
            i.put("canonical_target", "world.db:soul_manifests");
            i.put("shadow_store", "souls/companion-*.json");
            i.put("db_total_rows", dbSoulRows);
            i.put("db_distinct_dids", dbDistinctDids);
            i.put("fs_manifest_files", fsManifestCount);
            i.put("fs_other_json_files", fsOtherJson);
            i.put("note", fsManifestCount == 0 && dbDistinctDids > 0
                ? "DB-canonical on this node (no fs manifest files)"
                : "split storage — both DB and filesystem hold manifest data");
            i.put("phase", "F7b Phase 3 — drop filesystem manifests");
        }
        // Issue 2: bonds are in DB and embedded in manifests.
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "bonds");
            i.put("canonical_target", "world.db:bonds");
            i.put("shadow_store", "souls/*.json:bonds field");
            i.put("db_rows", dbBondRows);
            i.put("manifest_embedded_total", embeddedBondTotal);
            i.put("phase", "F7b Phase 1 — bonds canonical in SQL; manifest "
                + "rebuild on serialize");
        }
        // Issue 3: voice profiles only in manifests (no DB table yet).
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "voice_profiles");
            i.put("canonical_target", "(none — no voice_profiles table)");
            i.put("shadow_store", "souls/*.json:voiceProfile field");
            i.put("manifests_with_voice", voiceProfilesEmbedded);
            i.put("phase", "F7b Phase 2 — factor into voice_profiles table");
        }
        // Issue 4: soul fragments only in manifests.
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "soul_fragments");
            i.put("canonical_target", "(none — no soul_fragments table)");
            i.put("shadow_store", "souls/*.json:fragments field");
            i.put("total_fragments_embedded", fragmentsEmbedded);
            i.put("phase", "F7b Phase 2 — factor into soul_fragments table");
        }
        // Issue 5: companion DID has no canonical home (no companions table).
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "companion_did");
            i.put("canonical_target", "(none — no companions table)");
            ArrayNode appears = i.putArray("appears_in");
            appears.add("souls/*.did files");
            appears.add("souls/*.json:identity.did");
            appears.add("world.db:soul_manifests.did");
            appears.add("world.db:bonds.companion_did");
            appears.add("world.db:foreign_identities.did");
            i.put("phase", "F7b Phase 4 — create companions table");
        }
        // Issue 6: node identity public key only in file.
        {
            ObjectNode i = out.addObject();
            i.put("attribute", "node_public_key");
            i.put("canonical_target", "node-identity.json (private key)");
            i.put("shadow_store", "(none yet — Phase 4 mirrors public part to households table)");
            i.put("phase", "F7b Phase 4 — mirror public key to households table");
        }

        return out;
    }

    // ── Summary view ─────────────────────────────────────────────────────

    private static void printSummary(ObjectNode root, PrintStream out) {
        out.println("# wyrd state — summary");
        out.println();
        out.printf("Host:       %s%n", root.path("meta").path("host").asText());
        out.printf("Data dir:   %s%n", root.path("meta").path("data_dir").asText());
        out.printf("Timestamp:  %s%n", root.path("meta").path("timestamp").asText());
        out.println();

        // Node
        JsonNode node = root.path("node");
        out.println("## Node identity");
        if (node.path("present").asBoolean()) {
            out.printf("  node_id:    %s%n", node.path("node_id").asText("?"));
            String pub = node.path("public_key").asText("");
            if (pub.length() > 16) pub = pub.substring(0, 16) + "…";
            out.printf("  public_key: %s%n", pub);
        } else {
            out.println("  (no node-identity.json present)");
        }
        out.println();

        // Database
        JsonNode db = root.path("database");
        out.println("## Database (" + db.path("path").asText() + ")");
        out.printf("  tables: %d%n", db.path("table_count").asInt(0));
        printRow(out, db, "users");
        printRow(out, db, "bonds");
        printRow(out, db, "bilateral_agreements");
        printRow(out, db, "foreign_identities");
        printRow(out, db, "residency");
        printRow(out, db, "soul_manifests");
        printRow(out, db, "invites");
        printRow(out, db, "grants");
        printRow(out, db, "zone_manifests");
        out.println();

        // DB-side manifests (canonical on most dev boxes today).
        JsonNode dbManifests = db.path("tables").path("soul_manifests").path("manifests");
        if (dbManifests.isArray() && !dbManifests.isEmpty()) {
            out.println("## Soul manifests (in world.db:soul_manifests)");
            for (JsonNode m : dbManifests) {
                out.printf("    - did=%s  v=%d  forged=%s%n",
                    m.path("did").asText("?"),
                    m.path("version").asInt(0),
                    m.path("forged_at").asText(""));
            }
            out.println();
        }

        // Souls filesystem
        JsonNode souls = root.path("souls");
        out.println("## souls/ filesystem (" + souls.path("path").asText() + ")");
        out.printf("  soul-manifest .json files: %d%n", souls.path("manifests").size());
        for (JsonNode m : souls.path("manifests")) {
            out.printf("    - %s  (did=%s, v=%d, fragments=%d, bonds=%d)%n",
                m.path("name").asText(m.path("file").asText()),
                m.path("did").asText("?"),
                m.path("version").asInt(0),
                m.path("fragment_count").asInt(0),
                m.path("embedded_bonds").asInt(0));
        }
        if (souls.path("other_json").size() > 0) {
            out.printf("  other .json files: %d (CfC weights, intermediates, etc.)%n",
                souls.path("other_json").size());
            for (JsonNode m : souls.path("other_json")) {
                out.printf("    - %s  (kind=%s)%n",
                    m.path("file").asText(),
                    m.path("kind").asText("unknown"));
            }
        }
        if (souls.path("did_files").size() > 0) {
            out.printf("  .did files: %d%n", souls.path("did_files").size());
        }
        if (souls.path("subdirs").size() > 0) {
            out.print("  subdirs: ");
            var first = true;
            for (JsonNode s : souls.path("subdirs")) {
                if (!first) out.print(", ");
                out.printf("%s (%d files)", s.path("name").asText(), s.path("file_count").asInt(0));
                first = false;
            }
            out.println();
        }
        out.println();

        // Filesystem
        JsonNode fs = root.path("filesystem");
        out.println("## Derivative caches");
        for (var it = fs.fields(); it.hasNext();) {
            var e = it.next();
            if (e.getValue().path("present").asBoolean()) {
                out.printf("  %-12s %s%n", e.getKey() + ":",
                    e.getValue().path("size_human").asText("?"));
            }
        }
        out.println();

        // Fragmentation
        out.println("## Fragmentation (F7b Phase 0 visibility)");
        for (JsonNode i : root.path("fragmentation")) {
            out.printf("  ⚠ %s%n", i.path("attribute").asText());
            out.printf("      canonical: %s%n", i.path("canonical_target").asText());
            String shadow = i.path("shadow_store").asText("");
            if (!shadow.isEmpty()) out.printf("      shadow:    %s%n", shadow);
            String phase = i.path("phase").asText("");
            if (!phase.isEmpty()) out.printf("      → %s%n", phase);
        }
        out.println();
        out.println("(Stores listed as shadow are written but not yet read from.)");
    }

    private static void printRow(PrintStream out, JsonNode db, String table) {
        JsonNode t = db.path("tables").path(table);
        if (t.isMissingNode()) return;
        out.printf("  %-22s %d rows%n", table + ":", t.path("rows").asInt(0));
    }
}
