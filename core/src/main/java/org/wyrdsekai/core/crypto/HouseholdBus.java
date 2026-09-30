package org.wyrdsekai.core.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who may use this home's bus (NATS :4222 and the phones' websocket :4223), and the nats-server
 * settings that enforce it ( W2).
 *
 * <p>Until 2026-09-28 the bus had no login at all: anyone on the network could read every account's
 * password hash and publish an account record that made them steward. Now every client logs in:
 * the node itself, one user per household machine (issued when it joins with the household key),
 * one per paired phone (issued at pairing, D3). Passwords are random; only their bcrypt hashes go
 * into the server's settings. Phones may only use the subjects the relay already grants them, plus
 * their own reply inbox ({@code _INBOX.<user>.>}), so one person's phone cannot read the replies
 * meant for another's.</p>
 *
 * <p>Files, under {@code <data>/nats/}: {@value #USERS} (0600, the users and the node's own
 * password), {@value #INCLUDE} (the TLS, login and websocket part of nats.conf, rewritten on every
 * change; 0600, or 0640 to the {@value HouseholdTls#BUS_GROUP} group for the packaged standalone
 * service), and on a machine that joined another home, {@value #HUB} (0600: that home's address,
 * CA and this machine's login there).</p>
 */
public final class HouseholdBus {

    private static final Logger log = LoggerFactory.getLogger(HouseholdBus.class);

    public static final String DIR = "nats";
    public static final String USERS = "bus-users.json";
    public static final String INCLUDE = "household-bus.conf";
    public static final String HUB = "hub.json";
    public static final String NODE_USER = "node";
    static final String LEGACY_USER = "legacy-open";
    static final int BCRYPT_COST = 11;

    public enum Kind { MACHINE, PHONE }

    public record Credential(String user, String pass) {}

    record Entry(String user, String hash, Kind kind, String ref, long created) {}

    /** How nats-server listens; the rest comes from the users and the TLS material. */
    public record Listen(String bindHost, int clientPort, int wsPort, String zoneId, boolean lanPlaintext) {}

    /** A household machine's way into the home it joined. */
    public record HubLink(String url, String user, String pass, String caPem) {
        public X509Certificate ca() {
            try {
                return HouseholdTls.readCertificates(caPem).getFirst();
            } catch (Exception e) {
                throw new IllegalStateException("the hub's CA in " + HUB + " cannot be read", e);
            }
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, HouseholdBus> OPEN = new ConcurrentHashMap<>();

    private final Path dataDir;
    private final Path usersFile;
    private Credential node;
    private String nodeHash;
    private final List<Entry> users = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Listen listen;
    private volatile HouseholdTls.Material tls;

    private HouseholdBus(Path dataDir) {
        this.dataDir = dataDir;
        this.usersFile = dataDir.resolve(DIR).resolve(USERS);
    }

    /** The data folder every node component resolves: WYRDSEKAI_DATA_DIR, else ~/.wyrdsekai. */
    public static Path defaultDataDir() {
        var env = System.getenv("WYRDSEKAI_DATA_DIR");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".wyrdsekai");
    }

    /** This home's bus users, loaded once per data folder; makes the node's own login when missing. */
    public static HouseholdBus open(Path dataDir) throws IOException {
        var key = dataDir.toAbsolutePath().normalize();
        try {
            return OPEN.computeIfAbsent(key, k -> {
                var bus = new HouseholdBus(k);
                try {
                    bus.load();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return bus;
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    public synchronized Credential nodeCredential() {
        return node;
    }

    /**
     * Issues a login for a household machine or a paired phone. A second issue for the same
     * {@code ref} (node id, device id) replaces the first: only the newest password works.
     */
    public Credential issue(Kind kind, String ref) throws IOException {
        Credential c;
        synchronized (this) {
            var user = userName(kind, ref);
            var pass = secret();
            users.removeIf(e -> e.ref().equals(ref) && e.kind() == kind);
            users.add(new Entry(user, hash(pass), kind, ref, Instant.now().getEpochSecond()));
            save();
            c = new Credential(user, pass);
        }
        log.info("Household bus: issued a login for {} {} ({})", kind.name().toLowerCase(Locale.ROOT), ref, c.user());
        changed();
        return c;
    }

    /** Removes every login issued for {@code ref}; true when there was one. */
    public boolean revoke(String ref) throws IOException {
        boolean removed;
        synchronized (this) {
            removed = users.removeIf(e -> e.ref().equals(ref));
            if (removed) save();
        }
        if (removed) {
            log.info("Household bus: removed the login for {}", ref);
            changed();
        }
        return removed;
    }

    /** Called after every change, once the settings file is rewritten (nats-server reloads). */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    /**
     * Writes {@value #INCLUDE} for these listen settings and remembers them, so later changes rewrite
     * it the same way. {@code tls} null (the household certificate could not be made): the bus stays
     * on this machine, with login, unencrypted.
     */
    public synchronized Path writeInclude(HouseholdTls.Material tls, Listen listen) throws IOException {
        this.tls = tls;
        this.listen = listen;
        var f = dataDir.resolve(DIR).resolve(INCLUDE);
        // The packaged bus service (its own user, Group=wyrdsekai-nats) has to reach the include through
        // this folder; the files keep their own modes (the logins 0600, the include 0640 to the bus group).
        // It was created 0750 root:root on second-node, and the service failed "permission denied" (2026-09-28).
        Files.createDirectories(f.getParent());
        HouseholdTls.shareWithBusGroup(f.getParent());
        HouseholdTls.writeFile(f, render(tls, listen), "rw-------", true, dataDir);
        return f;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path includeFile() {
        return dataDir.resolve(DIR).resolve(INCLUDE);
    }

    /** The TLS, login and websocket settings for nats-server, relative-include-ready. */
    synchronized String render(HouseholdTls.Material tls, Listen l) {
        var bind = tls == null ? "127.0.0.1" : l.bindHost();
        var sb = new StringBuilder();
        sb.append("# Written by the Wyrdsekai node: the household bus's encryption and logins.\n");
        sb.append("# Rewritten whenever a household machine or a phone is paired or removed; edits do not last.\n");
        sb.append("listen: ").append(q(bind + ":" + l.clientPort())).append('\n');
        if (tls != null) {
            sb.append("tls {\n").append(tlsFiles(tls, "  ")).append("  timeout: 5\n}\n");
        }
        if (l.lanPlaintext()) {
            // Transition (WYRDSEKAI_NATS_LAN_PLAINTEXT): machines and phone apps from before 0.5.0 connect
            // in the clear and without a login, exactly as before. Anyone on the network can too.
            sb.append("allow_non_tls: true\n");
            sb.append("no_auth_user: ").append(q(LEGACY_USER)).append('\n');
        }
        sb.append("authorization {\n  users = [\n");
        // Only bcrypt hashes of passwords made at runtime go into the file.
        sb.append(String.format("    {user: %s, password: %s}\n", q(node.user()), q(nodeHash)));
        for (var e : users) {
            sb.append(String.format("    {user: %s, password: %s", q(e.user()), q(e.hash())));
            if (e.kind() == Kind.PHONE) sb.append(", permissions: ").append(phonePermissions(e.user(), l.zoneId()));
            sb.append("}\n");
        }
        if (l.lanPlaintext()) {
            // No password: nats-server gives it to clients that send none (no_auth_user).
            sb.append("    {user: ").append(q(LEGACY_USER)).append("}\n");
        }
        sb.append("  ]\n}\n");
        sb.append("websocket {\n");
        sb.append("  listen: ").append(q(bind + ":" + l.wsPort())).append('\n');
        if (tls != null && !l.lanPlaintext()) {
            sb.append("  tls {\n").append(tlsFiles(tls, "    ")).append("  }\n");
        } else {
            sb.append("  no_tls: true\n");
            if (l.lanPlaintext()) sb.append("  no_auth_user: ").append(q(LEGACY_USER)).append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * The relay's per-household phone grant (deploy/relay/registration.py), with a private inbox, plus
     * Study sync and inference under the phone's own login name: it sends Study frames as
     * {@code between.<zone>.<user>.*}, hears only frames addressed to it, and hears only answers to
     * inference streams it named {@code <user>.<id>}. Before, every phone could read every other
     * phone's Study frames, which carry that person's session token, and every inference answer in
     * the household.
     */
    static String phonePermissions(String user, String zone) {
        var pub = List.of("wyrd.zone." + zone + ".>", "wyrd.discover.>",
            "wyrd.tunnel." + zone + ".*.open", "wyrd.tunnel." + zone + ".*.up", "wyrd.tunnel." + zone + ".*.close",
            "between." + zone + "." + user + ".*.study.state", "between." + zone + "." + user + ".*.study.sync",
            "federation.inference." + zone + ".complete");
        var sub = List.of("wyrd.tunnel." + zone + ".*.down",
            "between." + zone + ".*." + user + ".study.state", "between." + zone + ".*." + user + ".study.sync",
            "federation.inference.stream." + user + ".*", "_INBOX." + user + ".>");
        return "{publish: {allow: " + list(pub) + "}, subscribe: {allow: " + list(sub) + "}, allow_responses: true}";
    }

    // ── a household machine's side: the home it joined ──────────────────────

    public static Optional<HubLink> readHubLink(Path dataDir) {
        var f = dataDir.resolve(DIR).resolve(HUB);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            var n = JSON.readTree(f.toFile());
            return Optional.of(new HubLink(n.path("url").asText(), n.path("user").asText(),
                n.path("pass").asText(), n.path("ca_pem").asText()));
        } catch (IOException e) {
            log.warn("{} cannot be read: {}", f, e.getMessage());
            return Optional.empty();
        }
    }

    public static void saveHubLink(Path dataDir, HubLink link) throws IOException {
        var n = JSON.createObjectNode();
        n.put("v", 1);
        n.put("url", link.url());
        n.put("user", link.user());
        n.put("pass", link.pass());
        n.put("ca_pem", link.caPem());
        HouseholdTls.writeFile(dataDir.resolve(DIR).resolve(HUB), JSON.writeValueAsString(n), "rw-------", false, dataDir);
    }

    /** True when two NATS URLs name the same host and port (scheme aside). */
    public static boolean sameServer(String a, String b) {
        var x = hostPort(a);
        var y = hostPort(b);
        return x != null && x.equals(y);
    }

    static String hostPort(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var u = URI.create(url.contains("://") ? url : "nats://" + url);
            if (u.getHost() == null) return null;
            return u.getHost().toLowerCase(Locale.ROOT) + ":" + (u.getPort() < 0 ? 4222 : u.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void changed() {
        var l = listen;
        if (l != null) {
            try {
                writeInclude(tls, l);
            } catch (IOException e) {
                log.error("Household bus: could not rewrite {}: {}", INCLUDE, e.getMessage());
                return;
            }
        }
        for (var r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Household bus change listener failed: {}", e.toString());
            }
        }
    }

    private synchronized void load() throws IOException {
        if (Files.isRegularFile(usersFile)) {
            var root = JSON.readTree(usersFile.toFile());
            var n = root.path("node");
            node = new Credential(n.path("user").asText(NODE_USER), n.path("pass").asText());
            nodeHash = n.path("hash").asText();
            for (var e : root.path("users")) {
                users.add(new Entry(e.path("user").asText(), e.path("hash").asText(),
                    Kind.valueOf(e.path("kind").asText("PHONE")), e.path("ref").asText(), e.path("created").asLong()));
            }
            if (node.pass().isBlank() || nodeHash.isBlank()) node = null;
        }
        if (node == null) {
            var pass = secret();
            node = new Credential(NODE_USER, pass);
            nodeHash = hash(pass);
            save();
        }
    }

    private void save() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("v", 1);
        var n = root.putObject("node");
        n.put("user", node.user());
        n.put("pass", node.pass());
        n.put("hash", nodeHash);
        ArrayNode arr = root.putArray("users");
        for (var e : users) {
            var o = arr.addObject();
            o.put("user", e.user());
            o.put("hash", e.hash());
            o.put("kind", e.kind().name());
            o.put("ref", e.ref());
            o.put("created", e.created());
        }
        HouseholdTls.writeFile(usersFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            "rw-------", false, dataDir);
    }

    static String userName(Kind kind, String ref) {
        var clean = ref == null ? "" : ref.replaceAll("[^A-Za-z0-9_-]", "");
        if (clean.isEmpty()) clean = Long.toHexString(RANDOM.nextLong());
        return (kind == Kind.PHONE ? "phone-" : "machine-") + clean;
    }

    private static String secret() {
        var b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hash(String pass) {
        return BCrypt.withDefaults().hashToString(BCRYPT_COST, pass.toCharArray());
    }

    private static String tlsFiles(HouseholdTls.Material tls, String indent) {
        return indent + "cert_file: " + q(path(tls.leafChainPem())) + "\n"
            + indent + "key_file: " + q(path(tls.leafKeyPem())) + "\n";
    }

    /** Forward slashes: nats-server reads '\' as an escape inside quotes (Windows paths). */
    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String list(List<String> items) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
