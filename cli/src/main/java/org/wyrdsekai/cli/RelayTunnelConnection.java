package org.wyrdsekai.cli;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.crypto.SealedRequest;
import org.wyrdsekai.core.crypto.SealedTunnel;
import org.wyrdsekai.core.crypto.TunnelKey;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * the CLI/TUI reaching a NAT'd, relay-only zone.
 *
 * <p>Instead of a direct {@code ws://zonehost/ws}, this dials the relay's NATS
 * bus and tunnels a FULL session over {@code wyrd.tunnel.{zone}.{session}.*}: it
 * publishes the terminal's C2S frames to {@code .up}, subscribes the zone's S2C
 * frames on {@code .down}, and the zone bridges that tunnel into its own
 * {@code /ws} (see {@code TunnelSessionHandler}). The world is byte-identical to
 * a LAN client — movement, items, rooms, companions — because it IS the real
 * zone session, just carried over the dumb pipe.
 *
 * <p>Auth: {@link #loginOverRelay} runs the same {@code wyrd.zone.{zone}.mcp.login}
 * request/reply the phone uses, mints a session token, and that token authenticates
 * the zone-side loopback {@code /ws}. Mirrors RelayTunnelServerConnection (KMP/RN).
 *
 * <p>Both are sealed end to end to the home's tunnel key {@code zk} from the invite
 * ( W3): the login as a sealed request, the session as a
 * sealed tunnel, so the relay sees neither the password, the token nor the session.
 * Without the key this terminal sends nothing readable unless the transition
 * settings allow it (WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS for the login,
 * WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT for the session).
 */
public final class RelayTunnelConnection implements WyrdSession {

    private static final Logger log = LoggerFactory.getLogger(RelayTunnelConnection.class);

    private final String relayUrl;     // nats:// or tls:// to the relay
    private final String natsUser;     // relay transport account (e.g. relay_phone)
    private final String natsPass;
    private final String caFingerprint; // pinned household-CA SHA-256 (colon-hex), or null/"none" for system trust
    private final String zoneId;
    private final byte[] zoneKey;       // the home's tunnel key (the invite's zk); null = unsealed
    private final Consumer<S2CMessage> messageHandler;
    private final Consumer<Connection.Status> stateHandler; // nullable

    private volatile String sessionId = UUID.randomUUID().toString().replace("-", "");
    private volatile String base;
    // The sealed tunnel: this session's key until the home answers, then both directions.
    private final Object upLock = new Object();
    private volatile SealedTunnel.KeyPair ephemeral;
    private volatile SealedTunnel.Opened channel;
    private volatile CountDownLatch sealedLatch = new CountDownLatch(1);
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    private volatile Connection nc;
    private volatile Dispatcher dispatcher;
    private volatile String token;
    private volatile boolean opened = false;
    private volatile boolean shutdownRequested = false;

    public RelayTunnelConnection(String relayUrl, String natsUser, String natsPass,
                                 String caFingerprint, String zoneId, byte[] zoneKey,
                                 Consumer<S2CMessage> messageHandler,
                                 Consumer<Connection.Status> stateHandler) {
        this.relayUrl = relayUrl;
        this.zoneKey = zoneKey;
        this.natsUser = natsUser;
        this.natsPass = natsPass;
        this.caFingerprint = caFingerprint;
        this.zoneId = zoneId;
        this.messageHandler = messageHandler;
        this.stateHandler = stateHandler;
        this.base = "wyrd.tunnel." + zoneId + "." + sessionId;
    }

    /** Open the relay NATS connection (idempotent). Required before login/connect. */
    private synchronized void ensureNats() throws Exception {
        if (nc != null && nc.getStatus() == Connection.Status.CONNECTED) return;
        var builder = new Options.Builder()
            .server(relayUrl)
            .connectionName("wyrd-cli-tunnel")
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(8));
        // The relay transport account is optional — an open/no-auth relay (e.g. a
        // zone's own loopback bus) takes no userInfo; jnats NPEs on a null user.
        if (natsUser != null && !natsUser.isBlank()) {
            builder.userInfo(natsUser, natsPass == null ? "" : natsPass);
            // The relay lets each account read only its own reply inbox (_INBOX.<account>.>).
            builder.inboxPrefix("_INBOX." + natsUser);
        }
        // A household relay presents a household-CA-issued leaf over wss:// — the JVM
        // default trust store rejects it (PKIX path building failed). Pin the invite's
        // CA fingerprint as the phone clients do (HouseholdTrustManager): the chain must
        // validate up to the pinned CA and the leaf must name the relay host. A relay
        // with a public-CA cert needs no pin.
        if (caFingerprint != null && !caFingerprint.isBlank()
                && !caFingerprint.equalsIgnoreCase("none")) {
            builder.sslContext(buildPinnedSslContext(caFingerprint, URI.create(relayUrl).getHost()));
        }
        nc = Nats.connect(builder.build());
    }

    /** Fingerprint-pinned TLS: see {@link #pinnedTrust}. */
    private static SSLContext buildPinnedSslContext(String expectedFingerprint, String host) throws Exception {
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinnedTrust(expectedFingerprint, host, null)}, null);
        return ctx;
    }

    /**
     * Trusts the relay when its chain validates, signature by signature, up to the pinned certificate (the
     * invite's household CA, or the served certificate itself), and the served certificate names
     * {@code host}. A chain that only carries the pinned CA beside someone else's certificate is refused:
     * the CA certificate is public, so its presence proves nothing. {@code at} fixes the validation time
     * (tests); null means now.
     */
    static X509TrustManager pinnedTrust(String expectedFingerprint, String host, Date at) {
        var expectedHex = expectedFingerprint.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("client certificates are not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Empty cert chain");
                }
                int anchor = -1;
                for (int i = 0; i < chain.length && anchor < 0; i++) {
                    if (sha256Hex(chain[i]).equals(expectedHex)) anchor = i;
                }
                if (anchor < 0) {
                    throw new CertificateException("Fingerprint mismatch — no cert in the "
                        + chain.length + "-cert chain matches pinned " + expectedHex.substring(0, 16) + "…");
                }
                if (anchor == 0) {
                    if (at == null) chain[0].checkValidity(); else chain[0].checkValidity(at);
                } else {
                    if (chain[anchor].getBasicConstraints() < 0) {
                        throw new CertificateException("the pinned certificate is not a certificate authority");
                    }
                    try {
                        var path = CertificateFactory.getInstance("X.509")
                            .generateCertPath(Arrays.asList(chain).subList(0, anchor));
                        var params = new PKIXParameters(Set.of(new TrustAnchor(chain[anchor], null)));
                        params.setRevocationEnabled(false);
                        if (at != null) params.setDate(at);
                        CertPathValidator.getInstance("PKIX").validate(path, params);
                    } catch (GeneralSecurityException e) {
                        throw new CertificateException("the relay's certificate does not validate to the pinned CA: "
                            + e.getMessage(), e);
                    }
                }
                checkHostname(chain[0], host);
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    private static String sha256Hex(X509Certificate cert) throws CertificateException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 not available", e);
        }
    }

    /** The served certificate must name {@code host}: an IP address SAN for an IP, a DNS SAN otherwise. */
    static void checkHostname(X509Certificate leaf, String host) throws CertificateException {
        if (host == null || host.isBlank()) {
            throw new CertificateException("no relay host to check the certificate against");
        }
        var h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean isIp = h.matches("[0-9.]+") || h.contains(":");
        var sans = leaf.getSubjectAlternativeNames();
        if (sans != null) {
            for (var san : sans) {
                int type = (Integer) san.get(0);
                var value = String.valueOf(san.get(1));
                if (isIp && type == 7 && sameAddress(value, h)) return;
                if (!isIp && type == 2 && dnsMatches(value, h)) return;
            }
        }
        throw new CertificateException("the relay's certificate is not for " + host);
    }

    private static boolean sameAddress(String a, String b) {
        try {
            return Arrays.equals(InetAddress.getByName(a).getAddress(), InetAddress.getByName(b).getAddress());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean dnsMatches(String pattern, String host) {
        var p = pattern.toLowerCase(Locale.ROOT);
        var hn = host.toLowerCase(Locale.ROOT);
        if (p.startsWith("*.")) {
            int dot = hn.indexOf('.');
            return dot > 0 && hn.substring(dot).equals(p.substring(1));
        }
        return p.equals(hn);
    }

    /**
     * Authenticate over the relay (no direct HTTP to the NAT'd zone). Runs
     * {@code wyrd.zone.{zone}.mcp.login} and stores the returned session token.
     * @return true on success.
     */
    public boolean loginOverRelay(String username, String password) {
        try {
            ensureNats();
            var body = Json.mapper().createObjectNode();
            body.put("username", username);
            body.put("password", password);
            var subject = "wyrd.zone." + zoneId + ".mcp.login";
            byte[] replyJson;
            if (zoneKey != null) {
                var out = SealedRequest.seal(zoneKey, subject, Json.mapper().writeValueAsBytes(body));
                var reply = nc.request(subject, out.wire(), Duration.ofSeconds(8));
                if (reply == null) { log.warn("mcp.login over relay: no responder"); return false; }
                try {
                    replyJson = out.openReply(reply.getData());
                } catch (GeneralSecurityException e) {
                    log.warn("mcp.login over relay: {}", e.getMessage());
                    return false;
                }
            } else if (allowed(ALLOW_PLAINTEXT_REQUESTS)) {
                log.warn("mcp.login over the relay without the home's key: the relay can read the password "
                    + "(allowed by {}=true)", ALLOW_PLAINTEXT_REQUESTS);
                var reply = nc.request(subject, Json.mapper().writeValueAsBytes(body), Duration.ofSeconds(8));
                if (reply == null) { log.warn("mcp.login over relay: no responder"); return false; }
                replyJson = reply.getData();
            } else {
                log.warn("mcp.login over relay not sent: without the home's key (--zone-key) the password would "
                    + "cross the relay readable");
                return false;
            }
            var node = Json.mapper().readTree(replyJson);
            if (!node.path("ok").asBoolean(false)) {
                log.warn("mcp.login over relay failed: {}", node.path("error").asText("?"));
                return false;
            }
            this.token = node.path("token").asText(null);
            return token != null && !token.isBlank();
        } catch (Exception e) {
            log.warn("loginOverRelay error: {}", e.getMessage());
            return false;
        }
    }

    /** Before 0.5.0 the login crossed the relay readable; this terminal sends it so only when this is true. */
    static final String ALLOW_PLAINTEXT_REQUESTS = "WYRDSEKAI_RELAY_ALLOW_PLAINTEXT_REQUESTS";
    /** Likewise for the session itself. */
    static final String ALLOW_PLAINTEXT_TUNNEL = "WYRDSEKAI_TUNNEL_ALLOW_PLAINTEXT";

    static boolean allowed(String setting) {
        var v = System.getenv(setting);
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    @Override
    public void connect() {
        try {
            if (zoneKey == null && !allowed(ALLOW_PLAINTEXT_TUNNEL)) {
                log.error("Relay tunnel not opened: without the home's key (--zone-key) the session would cross the relay readable");
                return;
            }
            ensureNats();
            dispatcher = nc.createDispatcher(this::onDown);
            if (!openSession()) {
                log.error("Relay tunnel: the home did not answer the sealed opening");
                return;
            }
            opened = true;
            connectedLatch.countDown();
            if (stateHandler != null) stateHandler.accept(Connection.Status.CONNECTED);
        } catch (Exception e) {
            log.error("Relay tunnel connect failed: {}", e.getMessage());
        }
    }

    /**
     * Announces this session. Sealed: {@code {"v":2,"e":<our key>}}, then waits for the home's key and sends
     * the token (or nothing, for a guest) in the first sealed frame. Unsealed: the token rides the open.
     */
    private boolean openSession() throws Exception {
        dispatcher.subscribe(base + ".down");
        if (zoneKey == null) {
            log.warn("Relay tunnel without the home's key: the relay can read this session (allowed by {}=true)",
                ALLOW_PLAINTEXT_TUNNEL);
            var open = Json.mapper().createObjectNode();
            if (token != null && !token.isBlank()) open.put("token", token);
            nc.publish(base + ".open", Json.mapper().writeValueAsBytes(open));
            nc.flush(Duration.ofSeconds(3));
            return true;
        }
        channel = null;
        sealedLatch = new CountDownLatch(1);
        ephemeral = SealedTunnel.generate();
        nc.publish(base + ".open", ("{\"v\":2,\"e\":\""
            + Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeral.pub()) + "\"}").getBytes(StandardCharsets.UTF_8));
        nc.flush(Duration.ofSeconds(3));
        return sealedLatch.await(10, TimeUnit.SECONDS);
    }

    private void onDown(Message msg) {
        try {
            if (!msg.getSubject().equals(base + ".down")) return;   // a session this terminal has left
            var data = msg.getData();
            if (zoneKey != null) {
                var ch = channel;
                if (ch == null) {
                    var hello = Json.mapper().readTree(data);
                    var e = TunnelKey.decodeKey(hello.path("e").asText(null));
                    if (hello.path("v").asInt(0) == 2 && e != null && e.length == SealedTunnel.KEY_LEN) {
                        ch = SealedTunnel.complete(ephemeral, zoneKey, e, sessionId);
                        var first = Json.mapper().createObjectNode();
                        if (token != null && !token.isBlank()) first.put("token", token);
                        synchronized (upLock) {
                            channel = ch;
                            nc.publish(base + ".up", ch.up().seal(Json.mapper().writeValueAsBytes(first),
                                (base + ".up").getBytes(StandardCharsets.UTF_8)));
                        }
                        sealedLatch.countDown();
                        return;
                    }
                    // Not the home's key: a plain refusal (tunnel_busy, ...) is shown; nothing is sent.
                } else {
                    try {
                        data = ch.down().open(data, msg.getSubject().getBytes(StandardCharsets.UTF_8));
                    } catch (GeneralSecurityException ex) {
                        log.warn("Relay tunnel: a frame did not open ({}); closing the session", ex.getMessage());
                        Thread.ofVirtual().start(this::disconnect);   // not on the NATS dispatcher thread
                        return;
                    }
                }
            }
            var json = new String(data, StandardCharsets.UTF_8);
            var s2c = Json.mapper().readValue(json, S2CMessage.class);
            messageHandler.accept(s2c);
        } catch (Exception e) {
            log.debug("S2C parse error on tunnel down: {}", e.getMessage());
        }
    }

    @Override
    public void send(C2SMessage msg) {
        if (!opened || nc == null) { log.warn("Cannot send: tunnel not open"); return; }
        try {
            var frame = Json.mapper().writeValueAsBytes(msg);
            if (zoneKey == null) {
                nc.publish(base + ".up", frame);
                return;
            }
            synchronized (upLock) {
                var ch = channel;
                if (ch == null) { log.warn("Cannot send: tunnel not sealed yet"); return; }
                nc.publish(base + ".up", ch.up().seal(frame, (base + ".up").getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.error("Failed to send over tunnel", e);
        }
    }

    @Override
    public boolean awaitConnected(long timeoutMs) {
        try { return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    @Override
    public boolean awaitClosed(long timeoutMs) {
        try { return closeLatch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    @Override
    public void prepareClose() { shutdownRequested = true; }

    @Override
    public void disconnect() {
        shutdownRequested = true;
        try {
            if (nc != null && opened) {
                nc.publish(base + ".close", new byte[0]);
                nc.flush(Duration.ofSeconds(2));
            }
        } catch (Exception ignored) { /* best effort */ }
        try { if (nc != null) nc.close(); } catch (Exception ignored) {}
        closeLatch.countDown();
    }

    @Override
    public String newId() { return UUID.randomUUID().toString().substring(0, 8); }

    @Override
    public void setToken(String token) { this.token = token; }

    /** Re-open the tunnel under the current token (unsealed: same session id; sealed: a new session). */
    @Override
    public void reconnectWithToken() {
        try {
            if (nc != null && opened && zoneKey != null) {
                // A sealed session carries its token in its first frame: a new token is a new session.
                nc.publish(base + ".close", new byte[0]);
                dispatcher.unsubscribe(base + ".down");
                sessionId = UUID.randomUUID().toString().replace("-", "");
                base = "wyrd.tunnel." + zoneId + "." + sessionId;
                if (!openSession()) log.warn("Relay tunnel: the home did not answer the sealed opening");
                return;
            }
            if (nc != null && opened) {
                var open = Json.mapper().createObjectNode();
                if (token != null && !token.isBlank()) open.put("token", token);
                nc.publish(base + ".open", Json.mapper().writeValueAsBytes(open));
                nc.flush(Duration.ofSeconds(2));
            }
        } catch (Exception e) { log.warn("reconnectWithToken error: {}", e.getMessage()); }
    }
}
