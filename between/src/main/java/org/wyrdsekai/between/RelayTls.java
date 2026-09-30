package org.wyrdsekai.between;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.RelayLegConfig;
import org.wyrdsekai.core.config.WyrdConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The link from a household to its relay, encrypted.
 *
 * <p>Until 0.5.0 every node-to-relay link (the Between bridge, the phone tunnel's session transport,
 * the MCP relay link and the peer-training link) spoke plain NATS on the relay's port 4222. The
 * phone's own leg to the relay was TLS, but from the relay to the household the conversation crossed
 * the internet readable, and a password-mode household's relay password with it (2026-09-28).</p>
 *
 * <p>Each relay link is now TLS. The relay's certificate is pinned: by the fingerprint the household took
 * when it joined ({@code WYRDSEKAI_RELAY_FINGERPRINT}, or the leg's own), or by a pin in
 * {@value #PINS_FILE} under the data folder (the join writes the relay's authority there from the
 * register reply). A pin matches when it is the certificate the relay serves, or the relay's own
 * authority and that authority signed the served certificate. With no pin at all the first certificate
 * is trusted and pinned (trust on first use); a different certificate later is refused.</p>
 *
 * <p>A relay that does not offer TLS (not updated) is reached in the clear with a warning, unless
 * {@code WYRDSEKAI_RELAY_REQUIRE_TLS=true}, or this relay was reached encrypted before, or the household
 * holds a pin for it: then the missing TLS offer is refused as a possible downgrade
 * ({@code WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_LINK=true} allows a pinned relay that never offered TLS, while it
 * updates). Loopback addresses are the node's own bus and stay plain.</p>
 *
 * <p>Every link also takes the reply inbox {@code _INBOX.<its relay user>} (the NKey, or the password
 * user), because the relay lets each user read only its own inbox.</p>
 */
public final class RelayTls {

    private static final Logger log = LoggerFactory.getLogger(RelayTls.class);

    public static final String REQUIRE_ENV = "WYRDSEKAI_RELAY_REQUIRE_TLS";
    public static final String ALLOW_PLAINTEXT_LINK_ENV = "WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_LINK";
    public static final String USE_NKEY_ENV = "WYRDSEKAI_RELAY_USE_NKEY";
    static final String SEEN_FILE = "relay-tls-seen";
    static final String PINS_FILE = "relay-tls-pins";
    private static final String MISMATCH = "relay certificate mismatch";

    /** What a relay link ended up as. */
    public enum Mode { LOCAL, PINNED, UNVERIFIED, PLAIN }

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static volatile boolean seenLoaded = false;

    private RelayTls() {}

    /** Connects to the relay at {@code url}, encrypted, pinned by the fingerprints on file for it. */
    public static Connection connect(Options.Builder builder, String url) throws IOException, InterruptedException {
        return connect(builder, url, pinsFor(url));
    }

    public static Connection connect(Options.Builder builder, String url, String fingerprint)
            throws IOException, InterruptedException {
        return connect(builder, url, fingerprint == null || fingerprint.isBlank() ? List.of() : List.of(fingerprint));
    }

    static Connection connect(Options.Builder builder, String url, List<String> pins)
            throws IOException, InterruptedException {
        withOwnInbox(builder);
        var where = hostPort(url);
        if (isLocal(url)) return Nats.connect(builder.build());
        boolean pinned = !pins.isEmpty();
        // The relay says in its first line whether it offers TLS. jnats does not keep the reason a first
        // connect failed, so the choice is made from that line before connecting.
        if (offersTls(url) == Boolean.FALSE) return inTheClear(builder, where, pinned);
        try {
            var firstUse = pinned ? null : new FirstUse();
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{pinned ? pinner(pins) : firstUse}, null);
            var conn = Nats.connect(builder.sslContext(ctx).build());
            remember(where);
            if (pinned) {
                log.info("Relay link to {} is encrypted; the relay's certificate matches the fingerprint on file", where);
            } else {
                var pin = firstUse.pin();
                recordPin(where, pin);
                log.warn("Relay link to {} is encrypted. No fingerprint was on file for this relay, so its "
                    + "certificate was trusted on first use and pinned ({}…, in {}); a different certificate "
                    + "later is refused", where, pin.substring(0, 16), PINS_FILE);
            }
            return conn;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS setup for the relay link failed: " + e.getMessage(), e);
        } catch (IOException e) {
            if (causedBy(e, MISMATCH)) {
                log.error("Relay {} presented a certificate that does not match the fingerprint on file: the link "
                    + "is refused (someone may be in the way). If the relay was really reinstalled, join it again "
                    + "(wyrd relay join), which writes the new fingerprint", where);
                throw e;
            }
            if (!relayOffersNoTls(e)) throw e;
            return inTheClear(builder, where, pinned);
        }
    }

    /** A relay that offers no TLS: in the clear with a warning, or refused (required, pinned, or a downgrade). */
    private static Connection inTheClear(Options.Builder builder, String where, boolean pinned)
            throws IOException, InterruptedException {
        if (requireTls()) {
            log.error("Relay {} does not offer encryption and {}=true: the link is refused. "
                + "Update the relay (sudo sh relay.sh update)", where, REQUIRE_ENV);
            throw new IOException("SSL connection wanted by client: relay " + where + " offers no TLS");
        }
        if (seenBefore(where)) {
            log.error("Relay {} did not offer encryption, though it did before: the link is refused as a "
                + "possible downgrade. If the relay was really reinstalled without it, update the relay "
                + "(sudo sh relay.sh update), or remove {} from {} under the data folder", where, where, SEEN_FILE);
            throw new IOException("SSL connection wanted by client: relay " + where + " offered TLS before");
        }
        if (pinned && !flag(ALLOW_PLAINTEXT_LINK_ENV)) {
            log.error("Relay {} did not offer encryption, but this household holds its certificate fingerprint: "
                + "the link is refused as a possible downgrade. If the relay really runs an old version, update it "
                + "(sudo sh relay.sh update); to connect in the clear until then, set {}=true", where,
                ALLOW_PLAINTEXT_LINK_ENV);
            throw new IOException("SSL connection wanted by client: relay " + where + " is pinned but offers no TLS");
        }
        log.warn("Relay {} does not offer encryption yet: this link crosses the network in the clear, "
            + "conversations and relay password included. Update the relay (sudo sh relay.sh update); "
            + "set {}=true to refuse unencrypted relays", where, REQUIRE_ENV);
        return Nats.connect(builder.sslContext(null).build());
    }

    /**
     * Gives the connection the reply inbox {@code _INBOX.<relay user>}: the NKey public key when the link
     * signs with an NKey, else the password user. The relay grants each user only its own inbox.
     */
    static void withOwnInbox(Options.Builder builder) {
        try {
            var o = builder.build();
            String user = null;
            if (o.getAuthHandler() != null) {
                var id = o.getAuthHandler().getID();
                if (id != null && id.length > 0) user = new String(id);
            }
            if (user == null) user = o.getUsername();
            if (user != null && !user.isBlank()) builder.inboxPrefix(inboxPrefixFor(user));
        } catch (RuntimeException e) {
            log.debug("relay inbox prefix not set: {}", e.toString());
        }
    }

    /** {@code _INBOX.<user>} (SHARED_DECISIONS D4). */
    public static String inboxPrefixFor(String relayUser) {
        return "_INBOX." + relayUser;
    }

    /**
     * Whether a relay leg authenticates with the node's NKey: yes, unless {@code WYRDSEKAI_RELAY_USE_NKEY=false},
     * or the setting is unset and the leg holds a relay password (a password-mode registration, which keeps
     * working as before).
     */
    public static boolean useNkey(String legToken) {
        return useNkey(System.getenv(USE_NKEY_ENV), legToken);
    }

    /** As {@link #useNkey(String)} with the setting read elsewhere (a conf file). */
    public static boolean useNkey(String setting, String legToken) {
        if (setting != null && !setting.isBlank()) return isTrue(setting);
        return legToken == null || legToken.isBlank();
    }

    /**
     * What the relay's first line says: TRUE when it requires or offers TLS, FALSE when it offers neither,
     * null when the line could not be read (the relay is down or slow) — then TLS is tried and jnats reports.
     */
    static Boolean offersTls(String url) {
        var h = host(url);
        int port;
        try {
            var u = URI.create(url.contains("://") ? url : "nats://" + url);
            port = u.getPort() < 0 ? 4222 : u.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
        try (var sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress(h, port), 5000);
            sock.setSoTimeout(5000);
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            return infoOffersTls(in.readLine());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The NATS INFO line: {@code INFO {"tls_required":true,…}} or {@code "tls_available":true}. */
    static Boolean infoOffersTls(String line) {
        if (line == null || !line.startsWith("INFO ")) return null;
        var compact = line.replace(" ", "");
        return compact.contains("\"tls_required\":true") || compact.contains("\"tls_available\":true");
    }

    /** The fingerprints on file for this relay URL: its leg's configured fingerprint, then the pin store. */
    static List<String> pinsFor(String url) {
        var where = hostPort(url);
        var out = new ArrayList<String>();
        try {
            for (RelayLegConfig leg : WyrdConfig.get().relayLegs()) {
                if (where.equals(hostPort(leg.url())) && leg.caFingerprint() != null && !leg.caFingerprint().isBlank()
                        && !leg.caFingerprint().equalsIgnoreCase("none")) {
                    out.add(leg.caFingerprint());
                }
            }
        } catch (RuntimeException e) {
            log.debug("relay fingerprint lookup failed: {}", e.toString());
        }
        out.addAll(storedPins(where));
        return out;
    }

    /**
     * Accepts the relay when the certificate it serves has the pinned fingerprint, or when a certificate
     * in its chain has it, is an authority, and signed the served certificate. A chain that merely carries
     * the pinned certificate beside someone else's is refused: only the served certificate's key is proven.
     */
    static X509TrustManager pinner(String fingerprint) {
        return pinner(List.of(fingerprint));
    }

    /** As {@link #pinner(String)}, accepting the relay when any one of {@code fingerprints} matches. */
    static X509TrustManager pinner(List<String> fingerprints) {
        var wanted = fingerprints.stream().map(RelayTls::normalize).toList();
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certificates are not used");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("the relay sent no certificate");
                CertificateException why = null;
                for (var want : wanted) {
                    try {
                        matches(chain, want);
                        return;
                    } catch (CertificateException e) {
                        why = e;
                    }
                }
                throw new CertificateException(MISMATCH + ": " + (why == null ? "no fingerprint" : why.getMessage()), why);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /** Passes when the served certificate has fingerprint {@code want}, or an authority in the chain has it and signed it. */
    static void matches(X509Certificate[] chain, String want) throws CertificateException {
        var served = chain[0];
        if (want.equals(sha256(served))) return;
        for (var c : chain) {
            if (!want.equals(sha256(c))) continue;
            if (c.getBasicConstraints() < 0) {
                throw new CertificateException("the pinned certificate is not the relay's authority");
            }
            try {
                served.verify(c.getPublicKey());
            } catch (Exception e) {
                throw new CertificateException("the served certificate was not signed by the pinned relay authority", e);
            }
            return;
        }
        throw new CertificateException("the relay's certificate does not match the fingerprint on file");
    }

    /**
     * Trust on first use: the first chain the relay serves is accepted and its pin kept (the relay's own
     * authority when the chain carries one that signed the served certificate, else the served certificate);
     * every later handshake on this link (jnats reconnects) must match that pin.
     */
    static final class FirstUse implements X509TrustManager {
        private volatile String pin;

        String pin() {
            return pin;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public synchronized void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("the relay sent no certificate");
            if (pin != null) {
                try {
                    matches(chain, pin);
                    return;
                } catch (CertificateException e) {
                    throw new CertificateException(MISMATCH + ": " + e.getMessage(), e);
                }
            }
            pin = pinOf(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** The pin to keep for a chain: its authority when that authority signed the served certificate, else the leaf. */
    static String pinOf(X509Certificate[] chain) throws CertificateException {
        var served = chain[0];
        for (int i = chain.length - 1; i >= 1; i--) {
            var c = chain[i];
            if (c.getBasicConstraints() < 0) continue;
            try {
                served.verify(c.getPublicKey());
                return sha256(c);
            } catch (Exception ignored) {
                // not the served certificate's issuer
            }
        }
        return sha256(served);
    }

    static String sha256(X509Certificate c) throws CertificateException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    /** {@code AB:CD:…}, {@code abcd…}, {@code sha256:…} and {@code SHA256 Fingerprint=…} all name the same hash. */
    static String normalize(String fingerprint) {
        var f = fingerprint.trim();
        int eq = f.lastIndexOf('=');
        if (eq >= 0) f = f.substring(eq + 1);
        if (f.toLowerCase(Locale.ROOT).startsWith("sha256:")) f = f.substring(7);
        return f.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** jnats refuses a TLS-configured connection to a server that offers no TLS with this message. */
    static boolean relayOffersNoTls(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("SSL connection wanted by client")) return true;
            for (var s : t.getSuppressed()) if (relayOffersNoTls(s)) return true;
        }
        return false;
    }

    static boolean isLocal(String url) {
        var h = host(url);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }

    static String host(String url) {
        try {
            var h = URI.create(url.contains("://") ? url : "nats://" + url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static String hostPort(String url) {
        try {
            var u = URI.create(url.contains("://") ? url : "nats://" + url);
            var h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return h + ":" + (u.getPort() < 0 ? 4222 : u.getPort());
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static boolean requireTls() {
        var v = System.getenv(REQUIRE_ENV);
        if (v == null) v = System.getProperty("wyrdsekai.relay.require.tls");
        return v != null && isTrue(v);
    }

    private static boolean flag(String env) {
        var v = System.getenv(env);
        if (v == null) v = System.getProperty(env.toLowerCase(Locale.ROOT).replace('_', '.'));
        return v != null && isTrue(v);
    }

    private static boolean isTrue(String v) {
        var t = v.trim();
        return t.equalsIgnoreCase("true") || t.equals("1") || t.equalsIgnoreCase("yes");
    }

    private static boolean causedBy(Throwable e, String marker) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (String.valueOf(t.getMessage()).contains(marker)) return true;
            for (var s : t.getSuppressed()) if (causedBy(s, marker)) return true;
        }
        return false;
    }

    // ── relays reached encrypted before: a later missing TLS offer is a downgrade ──

    /** The node's data folder, resolved the way Main does (setting, else {@code ~/.wyrdsekai}). */
    private static Path dataDir() {
        try {
            var dir = WyrdConfig.get().dataDir();
            if (dir != null && !dir.isBlank()) return Path.of(dir);
        } catch (RuntimeException e) {
            log.debug("data folder lookup failed: {}", e.toString());
        }
        var home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".wyrdsekai");
    }

    private static Path seenFile() {
        var dir = dataDir();
        return dir == null ? null : dir.resolve(SEEN_FILE);
    }

    private static void loadSeen() {
        if (seenLoaded) return;
        seenLoaded = true;
        var f = seenFile();
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            for (var line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) SEEN.add(line.trim());
        } catch (IOException e) {
            log.debug("{} not read: {}", SEEN_FILE, e.toString());
        }
    }

    static boolean seenBefore(String where) {
        loadSeen();
        return SEEN.contains(where);
    }

    static void remember(String where) {
        loadSeen();
        if (!SEEN.add(where)) return;
        var f = seenFile();
        if (f == null) return;
        try {
            Files.writeString(f, where + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("{} not written: {}", SEEN_FILE, e.toString());
        }
    }

    // ── pins: host:port and a SHA-256 fingerprint per line; written at join and on first use ──

    private static Path pinsFile() {
        var dir = dataDir();
        return dir == null ? null : dir.resolve(PINS_FILE);
    }

    static List<String> storedPins(String where) {
        return storedPins(pinsFile(), where);
    }

    static List<String> storedPins(Path file, String where) {
        var out = new ArrayList<String>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals(where)) out.add(parts[1]);
            }
        } catch (IOException e) {
            log.debug("{} not read: {}", PINS_FILE, e.toString());
        }
        return out;
    }

    private static void recordPin(String where, String fingerprint) {
        var f = pinsFile();
        if (f != null) appendPin(f, where, fingerprint);
    }

    /**
     * Keeps {@code fingerprint} as a pin for the relay at {@code hostPort} (the zone port, e.g.
     * {@code relay.example.org:4222}) in {@value #PINS_FILE} under {@code dataDir}. The join calls this with the
     * relay authority from the register reply, so the node pins the zone link from first contact.
     */
    public static void recordPin(Path dataDir, String hostPort, String fingerprint) {
        appendPin(dataDir.resolve(PINS_FILE), hostPort(hostPort), fingerprint);
    }

    static synchronized void appendPin(Path file, String where, String fingerprint) {
        var fp = normalize(fingerprint);
        if (fp.isEmpty() || storedPins(file, where).contains(fp)) return;
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX filesystem
                }
            }
            Files.writeString(file, where + " " + fp + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Relay pin for {} not written to {}: {}", where, file, e.toString());
        }
    }

    /** Test hook. */
    static void forgetForTests() { SEEN.clear(); seenLoaded = true; }
}
