package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.naming.BlockList;
import org.wyrdsekai.core.naming.ContactsBook;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.naming.LocalZoneRegistry;
import org.wyrdsekai.core.naming.WellKnownZoneDirectory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Admin CLI entry point for zone-naming operations
 * ({@code wyrd contacts}, {@code wyrd zones}, {@code wyrd whoami}).
 *
 * <p>Lives in the {@code server} module because it reuses {@code NodeIdentity}
 * (from {@code between}) for DID derivation. The bash wrapper at
 * {@code bin/wyrd} invokes this class with the server classpath so we don't
 * re-implement base58 / multicodec encoding there.</p>
 *
 * <p>Each subcommand exits with 0 on success, 1 on user-visible error, and
 * 2 on unexpected internal failure. Never throws past {@link #main}.</p>
 *
 * <h2>Subcommands</h2>
 *
 * <table border="1">
 *   <tr><th>Command</th><th>Behaviour</th></tr>
 *   <tr><td>{@code whoami}</td>
 *       <td>Print this household's {@code did:wyrd:z6Mk…}. Derives from
 *           {@code ~/.wyrdsekai/node-identity.json}, generating one if
 *           absent (idempotent with server boot).</td></tr>
 *   <tr><td>{@code contacts list}</td>
 *       <td>Print each contact alias, DID, default label.</td></tr>
 *   <tr><td>{@code contacts add <alias> <did> [<default-label>]}</td>
 *       <td>TOFU add — prints the DID for out-of-band verification, writes
 *           {@code ~/.wyrdsekai/contacts}.</td></tr>
 *   <tr><td>{@code contacts remove <alias>}</td>
 *       <td>Delete entry.</td></tr>
 *   <tr><td>{@code contacts rename <old> <new>}</td>
 *       <td>Rename alias in place; preserves DID + default label.</td></tr>
 *   <tr><td>{@code contacts update <alias> <new-did>}</td>
 * <td>Key rotation.</td></tr>
 *   <tr><td>{@code zones list}</td>
 *       <td>Print each registered zone label; the first is the default.</td></tr>
 *   <tr><td>{@code zones create <label>}</td>
 *       <td>Register a new zone. Rejects reserved keywords.</td></tr>
 *   <tr><td>{@code zones remove <label>}</td>
 *       <td>Unregister.</td></tr>
 * </table>
 *
 * <h2>Data directory resolution</h2>
 *
 * <p>{@code WYRDSEKAI_DATA_DIR} env var → {@code $HOME/.wyrdsekai} default.
 * Directory is created if missing so first-run CLI commands don't error on
 * {@code add}/{@code create}.</p>
 *
 * <p>Package-private {@link #run} is exposed for unit tests — pass a temp
 * dir and capture streams without touching the real home directory.</p>
 */
public final class NamingAdminMain {

    private NamingAdminMain() {}

    public static void main(String[] args) {
        var dataDir = resolveDataDir();
        int exit = run(dataDir, System.out, System.err, args);
        System.exit(exit);
    }

    static Path resolveDataDir() {
        var override = WyrdConfig.get().dataDir();
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai");
    }

    /**
     * Test-friendly entry point. Does not call {@code System.exit} — returns
     * the intended exit code instead.
     *
     * @return 0 on success, 1 on user error (usage, invalid input, not
     *         found), 2 on unexpected internal failure.
     */
    static int run(Path dataDir, PrintStream out, PrintStream err, String... args) {
        if (args.length == 0) {
            printUsage(err);
            return 1;
        }
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            err.println("[wyrd] cannot create data dir: " + dataDir + " (" + e.getMessage() + ")");
            return 2;
        }

        var cmd = args[0];
        var sub = args.length > 1 ? args[1] : "";
        try {
            return switch (cmd) {
                case "whoami" -> doWhoami(dataDir, out);
                case "contacts" -> doContacts(dataDir, out, err, sub, tail(args, 2));
                case "zones" -> doZones(dataDir, out, err, sub, tail(args, 2));
                case "block" -> doBlock(dataDir, out, err, tail(args, 1), false);
                case "unblock" -> doUnblock(dataDir, out, err, tail(args, 1));
                case "blocks" -> doListBlocks(dataDir, out);
                case "safety" -> doSafety(dataDir, out, err, sub, tail(args, 2));
                case "discover" -> doDiscover(out, err, tail(args, 1));
                case "help", "--help", "-h" -> { printUsage(out); yield 0; }
                default -> {
                    err.println("[wyrd] unknown naming command: " + cmd);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (IllegalArgumentException e) {
            // Caller-visible validation error (bad alias, reserved keyword, etc.)
            err.println("[wyrd] " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("[wyrd] internal error: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
            return 2;
        }
    }

    // ── whoami ─────────────────────────────────────────────────────────

    private static int doWhoami(Path dataDir, PrintStream out) throws Exception {
        var identityFile = dataDir.resolve("node-identity.json");
        var node = NodeIdentity.loadOrGenerate(identityFile);
        var household = HouseholdIdentity.fromSpkiBytes(node.publicKeyBytes());
        out.println(household.did());
        return 0;
    }

    // ── contacts ───────────────────────────────────────────────────────

    private static int doContacts(Path dataDir, PrintStream out, PrintStream err,
                                   String sub, String[] rest) throws Exception {
        var file = dataDir.resolve("contacts");
        var book = ContactsBook.load(file);
        switch (sub) {
            case "list", "" -> {
                if (book.size() == 0) {
                    out.println("(no contacts yet — add one with `wyrd contacts add <alias> <did>`)");
                    return 0;
                }
                renderContacts(book, out);
                return 0;
            }
            case "add" -> {
                if (rest.length < 2) {
                    err.println("Usage: wyrd contacts add <alias> <did> [<default-label>]");
                    return 1;
                }
                var defaultLabel = rest.length >= 3 ? rest[2] : null;
                book.add(rest[0], rest[1], defaultLabel);
                book.save();
                out.println("[wyrd] added contact: " + rest[0]);
                out.println("       DID: " + rest[1]);
                out.println("       (verify fingerprint out-of-band before relying on this contact)");
                return 0;
            }
            case "remove", "rm" -> {
                if (rest.length < 1) {
                    err.println("Usage: wyrd contacts remove <alias>");
                    return 1;
                }
                if (!book.remove(rest[0])) {
                    err.println("[wyrd] no contact named: " + rest[0]);
                    return 1;
                }
                book.save();
                out.println("[wyrd] removed contact: " + rest[0]);
                return 0;
            }
            case "rename" -> {
                if (rest.length < 2) {
                    err.println("Usage: wyrd contacts rename <old-alias> <new-alias>");
                    return 1;
                }
                book.rename(rest[0], rest[1]);
                book.save();
                out.println("[wyrd] renamed " + rest[0] + " → " + rest[1]);
                return 0;
            }
            case "update" -> {
                if (rest.length < 2) {
                    err.println("Usage: wyrd contacts update <alias> <new-did>");
                    return 1;
                }
                book.updateDid(rest[0], rest[1]);
                book.save();
                out.println("[wyrd] updated DID for " + rest[0]);
                return 0;
            }
            default -> {
                err.println("Unknown contacts subcommand: " + sub);
                err.println("Usage: wyrd contacts {list,add,remove,rename,update}");
                return 1;
            }
        }
    }

    private static void renderContacts(ContactsBook book, PrintStream out) {
        int maxAlias = book.list().stream()
            .mapToInt(c -> c.alias().length()).max().orElse(5);
        for (var c : book.list()) {
            var def = c.defaultLabel() == null ? "" : c.defaultLabel();
            out.println(String.format("  %-" + maxAlias + "s  %s  %s",
                c.alias(), c.did(), def).stripTrailing());
        }
    }

    // ── zones ──────────────────────────────────────────────────────────

    private static int doZones(Path dataDir, PrintStream out, PrintStream err,
                                String sub, String[] rest) throws Exception {
        var file = dataDir.resolve("my-zones");
        var reg = LocalZoneRegistry.load(file);
        switch (sub) {
            case "list", "" -> {
                if (reg.size() == 0) {
                    out.println("(no zones yet — create one with `wyrd zones create <label>`)");
                    return 0;
                }
                var def = reg.defaultLabel();
                for (var label : reg.list()) {
                    var marker = def.isPresent() && def.get().equals(label) ? "  (default)" : "";
                    out.println("  " + label + marker);
                }
                return 0;
            }
            case "create", "add" -> {
                if (rest.length < 1) {
                    err.println("Usage: wyrd zones create <label>");
                    return 1;
                }
                reg.add(rest[0]);
                reg.save();
                out.println("[wyrd] created zone: " + rest[0]);
                if (reg.size() == 1) {
                    out.println("       (first zone — set as default)");
                }
                return 0;
            }
            case "remove", "rm" -> {
                if (rest.length < 1) {
                    err.println("Usage: wyrd zones remove <label>");
                    return 1;
                }
                if (!reg.remove(rest[0])) {
                    err.println("[wyrd] no zone named: " + rest[0]);
                    return 1;
                }
                reg.save();
                out.println("[wyrd] removed zone: " + rest[0]);
                return 0;
            }
            default -> {
                err.println("Unknown zones subcommand: " + sub);
                err.println("Usage: wyrd zones {list,create,remove}");
                return 1;
            }
        }
    }

    // ── blocks (§6.2) ──────────────────────────────────────────────────

    /**
     * {@code wyrd block <did> [--revoke] [--note "..."]}. Preemptive
     * (default) or reactive (with {@code --revoke}). The revocation envelope
     * publishing required by §6.5 is the federation layer's job — this
     * command only updates the on-disk blocklist; a subsequent server
     * restart (or an in-process hook via {@code BlockListService}) picks it up.
     */
    private static int doBlock(Path dataDir, PrintStream out, PrintStream err,
                                String[] args, boolean silent) throws Exception {
        if (args.length < 1) {
            err.println("Usage: wyrd block <did> [--revoke] [--note \"...\"]");
            return 1;
        }
        var did = args[0];
        boolean revoke = false;
        String note = null;
        for (int i = 1; i < args.length; i++) {
            if ("--revoke".equals(args[i])) {
                revoke = true;
            } else if ("--note".equals(args[i]) && i + 1 < args.length) {
                note = args[++i];
            }
        }

        var file = dataDir.resolve("blocks");
        var list = BlockList.load(file);
        list.add(did, Instant.now(), revoke, note);
        list.save();

        if (!silent) {
            out.println("[wyrd] blocked " + did);
            if (revoke) {
                out.println("       (reactive — any existing agreement will be revoked on next federation sync)");
            }
            if (note != null) out.println("       note: " + note);
        }
        return 0;
    }

    private static int doUnblock(Path dataDir, PrintStream out, PrintStream err,
                                  String[] args) throws Exception {
        if (args.length < 1) {
            err.println("Usage: wyrd unblock <did>");
            return 1;
        }
        var file = dataDir.resolve("blocks");
        var list = BlockList.load(file);
        if (!list.remove(args[0])) {
            err.println("[wyrd] no block for: " + args[0]);
            return 1;
        }
        list.save();
        out.println("[wyrd] unblocked " + args[0]);
        return 0;
    }

    private static int doListBlocks(Path dataDir, PrintStream out) throws Exception {
        var file = dataDir.resolve("blocks");
        var list = BlockList.load(file);
        if (list.size() == 0) {
            out.println("(no blocks — add with `wyrd block <did> [--revoke] [--note ...]`)");
            return 0;
        }
        for (var e : list.list()) {
            var flags = e.revoke() ? " revoke" : "";
            var src = e.source() != null ? "  (via " + e.source() + ")" : "";
            var note = e.note() != null && !e.note().isBlank() ? "  note: " + e.note() : "";
            out.println("  " + e.did() + "  " + e.addedAt() + flags + src + note);
        }
        return 0;
    }

    // ── safety (§6.4 curator subscriptions) ────────────────────────────

    /**
     * Shared block-list curator subscriptions. This first slice just tracks
     * the operator's subscription list locally — the actual DHT fetch +
     * import of a curator's list lands when §5 libp2p discovery does. For
     * now: record the intent, and surface it in `wyrd safety list`.
     */
    private static int doSafety(Path dataDir, PrintStream out, PrintStream err,
                                 String sub, String[] rest) throws Exception {
        var file = dataDir.resolve("safety-subscriptions");
        switch (sub) {
            case "subscribe" -> {
                if (rest.length < 1) {
                    err.println("Usage: wyrd safety subscribe <curator-did>");
                    return 1;
                }
                var did = rest[0];
                if (!did.startsWith(HouseholdIdentity.DID_SCHEME)) {
                    err.println("[wyrd] curator DID must start with " + HouseholdIdentity.DID_SCHEME);
                    return 1;
                }
                appendLine(file, did);
                out.println("[wyrd] subscribed to curator " + did);
                out.println("       (imports land when DHT discovery wires up — spec §5)");
                return 0;
            }
            case "unsubscribe" -> {
                if (rest.length < 1) {
                    err.println("Usage: wyrd safety unsubscribe <curator-did>");
                    return 1;
                }
                boolean removed = removeLine(file, rest[0]);
                if (!removed) {
                    err.println("[wyrd] not subscribed: " + rest[0]);
                    return 1;
                }
                // Also drop any block entries imported from this curator
                // (spec §6.4 "Unsubscribing removes imported entries").
                var blocks = BlockList.load(dataDir.resolve("blocks"));
                int dropped = blocks.unsubscribeCurator(rest[0]);
                if (dropped > 0) blocks.save();
                out.println("[wyrd] unsubscribed from " + rest[0]);
                if (dropped > 0) out.println("       (removed " + dropped + " imported blocks)");
                return 0;
            }
            case "list", "" -> {
                if (!Files.isRegularFile(file)) {
                    out.println("(no curator subscriptions)");
                    return 0;
                }
                for (var line : Files.readAllLines(file)) {
                    var s = line.strip();
                    if (!s.isEmpty() && !s.startsWith("#")) out.println("  " + s);
                }
                return 0;
            }
            default -> {
                err.println("Usage: wyrd safety {subscribe,unsubscribe,list}");
                return 1;
            }
        }
    }

    /** Append a DID to a plaintext list file if not already present. Idempotent. */
    private static void appendLine(Path file, String value) throws IOException {
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.isRegularFile(file)) {
            for (var line : Files.readAllLines(file)) {
                if (line.strip().equals(value)) return;
            }
        }
        var existing = Files.isRegularFile(file) ? Files.readString(file) : "";
        Files.writeString(file, existing + value + "\n");
    }

    /** @return true if a line exactly matching {@code value} was removed. */
    private static boolean removeLine(Path file, String value) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean removed = lines.removeIf(l -> l.strip().equals(value));
        if (removed) Files.writeString(file, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        return removed;
    }

    // ── discover (§5.2) ────────────────────────────────────────────────

    /**
     * {@code wyrd discover} — browse the zone directory cached on the local
     * running server. Three forms:
     *
     * <ul>
     *   <li>{@code wyrd discover} — list recent manifests.</li>
     *   <li>{@code wyrd discover --tag <tag>} — DIDs for a tag.</li>
     *   <li>{@code wyrd discover --did <did>} — full manifest for a DID.</li>
     * </ul>
     *
     * <p>Server URL comes from {@code WYRDSEKAI_API_URL} (default
     * {@code http://localhost:7070}). Returns 1 on HTTP error, 2 on
     * connection failure.</p>
     */
    private static int doDiscover(PrintStream out, PrintStream err, String[] args) {
        String tag = null;
        String did = null;
        String capability = null;
        String searchQuery = null;
        String directUrl = null;
        String acctHandle = null;
        int limit = 20;
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if ("--tag".equals(arg) && i + 1 < args.length) {
                tag = args[++i];
            } else if ("--did".equals(arg) && i + 1 < args.length) {
                did = args[++i];
            } else if ("--capability".equals(arg) && i + 1 < args.length) {
                capability = args[++i];
            } else if ("--search".equals(arg) && i + 1 < args.length) {
                searchQuery = args[++i];
            } else if ("--limit".equals(arg) && i + 1 < args.length) {
                try { limit = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) {
                    err.println("[wyrd] --limit must be an integer");
                    return 1;
                }
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                out.println("Usage: wyrd discover [<url> | acct:<handle> | --did <did>");
                out.println("                     | --tag <tag> | --capability <name>");
                out.println("                     | --search \"<text>\"] [--limit N]");
                return 0;
            } else if (arg.startsWith("acct:") || arg.contains("@") && !arg.startsWith("--")) {
                acctHandle = arg.startsWith("acct:") ? arg : "acct:" + arg;
            } else if (arg.startsWith("http://") || arg.startsWith("https://")) {
                directUrl = arg;
            } else if (!arg.startsWith("--")) {
                // Bare hostname — treat as https:// URL.
                directUrl = "https://" + arg;
            }
        }

        // Direct URL or WebFinger — bypass rendezvous, hit the zone's own .well-known.
        if (directUrl != null) {
            return renderDirectUrl(directUrl, out, err);
        }
        if (acctHandle != null) {
            return renderAcct(acctHandle, out, err);
        }

        // System property wins over env — tests set the property to redirect;
        // operators use the env var. Default matches the Javalin bind in Main.
        var apiUrl = System.getProperty("wyrdsekai.api.url",
            System.getenv().getOrDefault("WYRDSEKAI_API_URL", "http://localhost:7070"));
        String path;
        if (did != null) {
            path = "/api/directory/" + URLEncoder.encode(did,
                StandardCharsets.UTF_8);
        } else if (tag != null) {
            path = "/api/directory/tag/" + URLEncoder.encode(tag,
                StandardCharsets.UTF_8);
        } else if (capability != null) {
            path = "/api/directory/capability/" + URLEncoder.encode(capability,
                StandardCharsets.UTF_8);
        } else if (searchQuery != null) {
            path = "/api/directory/search?q=" + URLEncoder.encode(searchQuery,
                StandardCharsets.UTF_8) + "&limit=" + limit;
        } else {
            path = "/api/directory/recent?limit=" + limit;
        }

        HttpResponse<String> resp;
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            var req = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("accept", "application/json")
                .GET().build();
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            err.println("[wyrd] cannot reach server at " + apiUrl
                + ": " + e.getClass().getSimpleName() + " — is wyrdsekai running?");
            return 2;
        }

        if (resp.statusCode() == 404) {
            err.println("[wyrd] not found");
            return 1;
        }
        if (resp.statusCode() / 100 != 2) {
            err.println("[wyrd] server returned HTTP " + resp.statusCode() + ": " + resp.body());
            return 1;
        }

        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(resp.body());
            if (did != null) {
                renderManifest(node, out);
            } else if (tag != null) {
                renderTagList(node, out);
            } else if (capability != null) {
                renderCapabilityList(node, out);
            } else if (searchQuery != null) {
                renderSearchHits(node, out);
            } else {
                renderRecentList(node, out);
            }
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] malformed server response: " + e.getMessage());
            return 2;
        }
    }

    /** Fetch manifest via {@code .well-known/wyrd-zone} on the given base URL. */
    private static int renderDirectUrl(String url, PrintStream out, PrintStream err) {
        var dir = new WellKnownZoneDirectory();
        var opt = dir.lookupUrl(url);
        if (opt.isEmpty()) {
            err.println("[wyrd] no wyrdsekai manifest at " + url
                + " (no response, HTTP error, or malformed manifest)");
            return 1;
        }
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(opt.get().toJsonBytes());
            renderManifest(node, out);
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] failed to render manifest: " + e.getMessage());
            return 2;
        }
    }

    /** Resolve {@code acct:label@host} via WebFinger and render the manifest. */
    private static int renderAcct(String handle, PrintStream out, PrintStream err) {
        var dir = new WellKnownZoneDirectory();
        var opt = dir.lookupAcct(handle);
        if (opt.isEmpty()) {
            err.println("[wyrd] WebFinger resolve failed for " + handle
                + " (no webfinger endpoint, no self link, or manifest fetch failed)");
            return 1;
        }
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(opt.get().toJsonBytes());
            renderManifest(node, out);
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] failed to render manifest: " + e.getMessage());
            return 2;
        }
    }

    private static void renderManifest(JsonNode m, PrintStream out) {
        var label = m.path("zoneLabel").asText("?");
        var display = m.path("displayName").asText(label);
        var tagline = m.path("tagline").asText("");
        var description = m.path("description").asText("");
        var did = m.path("did").asText("");
        var tags = m.path("tags");
        var refreshedAt = m.path("refreshed_at").asText("");

        out.println(display + "  [" + label + "]");
        out.println("  " + did);
        if (!tagline.isBlank()) out.println("  " + tagline);
        if (!description.isBlank()) out.println("  " + description);
        if (tags.isArray() && tags.size() > 0) {
            var sb = new StringBuilder("  tags:");
            tags.forEach(t -> sb.append(' ').append(t.asText()));
            out.println(sb);
        }
        if (!refreshedAt.isBlank()) out.println("  refreshed: " + refreshedAt);
    }

    private static void renderTagList(JsonNode node, PrintStream out) {
        var tag = node.path("tag").asText("");
        var dids = node.path("dids");
        if (!dids.isArray() || dids.size() == 0) {
            out.println("(no zones tagged '" + tag + "')");
            return;
        }
        out.println("tag '" + tag + "' — " + dids.size() + " zone(s):");
        dids.forEach(d -> out.println("  " + d.asText()));
        out.println("");
        out.println("(inspect: `wyrd discover --did <did>`)");
    }

    private static void renderCapabilityList(JsonNode node, PrintStream out) {
        var cap = node.path("capability").asText("");
        var dids = node.path("dids");
        if (!dids.isArray() || dids.size() == 0) {
            out.println("(no zones advertising capability '" + cap + "')");
            return;
        }
        out.println("capability '" + cap + "' — " + dids.size() + " zone(s):");
        dids.forEach(d -> out.println("  " + d.asText()));
        out.println("");
        out.println("(inspect: `wyrd discover --did <did>`)");
    }

    private static void renderSearchHits(JsonNode node, PrintStream out) {
        var q = node.path("q").asText("");
        var mode = node.path("mode").asText("keyword");
        var hits = node.path("hits");
        if (!hits.isArray() || hits.size() == 0) {
            out.println("(no matches for '" + q + "')");
            return;
        }
        out.println("search '" + q + "' [" + mode + "] — "
            + hits.size() + " result(s):");
        out.println("");
        int rank = 1;
        for (var hit : hits) {
            var m = hit.path("manifest");
            var score = hit.path("score").asInt(0);
            var label = m.path("zoneLabel").asText("?");
            var display = m.path("displayName").asText(label);
            var tagline = m.path("tagline").asText("");
            var did = m.path("did").asText("");
            out.println(String.format("  %d. %s  [%s]  score=%d",
                rank++, display, label, score));
            out.println("     " + did);
            if (!tagline.isBlank()) out.println("     " + tagline);
            out.println("");
        }
        out.println("(inspect: `wyrd discover --did <did>`)");
    }

    private static void renderRecentList(JsonNode node, PrintStream out) {
        var manifests = node.path("manifests");
        if (!manifests.isArray() || manifests.size() == 0) {
            out.println("(no zones in directory yet — the local cache hydrates as peers publish)");
            return;
        }
        out.println(manifests.size() + " zone(s) in directory:");
        out.println("");
        for (var m : manifests) {
            renderManifest(m, out);
            out.println("");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String[] tail(String[] args, int from) {
        if (from >= args.length) return new String[0];
        var out = new String[args.length - from];
        System.arraycopy(args, from, out, 0, out.length);
        return out;
    }

    private static void printUsage(PrintStream s) {
        s.println("wyrd naming admin — zone identity and contacts");
        s.println("");
        s.println("Commands:");
        s.println("  whoami                                  Print your household DID");
        s.println("  contacts list                           List local contacts");
        s.println("  contacts add <alias> <did> [<label>]    Add a contact (TOFU)");
        s.println("  contacts remove <alias>                 Remove a contact");
        s.println("  contacts rename <old> <new>             Rename a contact alias");
        s.println("  contacts update <alias> <new-did>       Update DID on key rotation");
        s.println("  zones list                              List your zones");
        s.println("  zones create <label>                    Register a new zone");
        s.println("  zones remove <label>                    Unregister a zone");
        s.println("  block <did> [--revoke] [--note \"...\"]   Block a DID (silent drop on intake)");
        s.println("  unblock <did>                           Remove a block");
        s.println("  blocks                                  List all blocks");
        s.println("  safety subscribe <curator-did>          Subscribe to a curator's shared blocklist");
        s.println("  safety unsubscribe <curator-did>        Unsubscribe (drops imported blocks)");
        s.println("  safety list                             List curator subscriptions");
        s.println("  discover [--tag <t> | --capability <c>  Browse the zone directory");
        s.println("           | --search \"<text>\" | --did <d>");
        s.println("           | <url> | acct:<handle>]       Direct fetch or semantic search");
    }
}
