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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
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
 */
public final class RelayTunnelConnection implements WyrdSession {

    private static final Logger log = LoggerFactory.getLogger(RelayTunnelConnection.class);

    private final String relayUrl;     // nats:// or tls:// to the relay
    private final String natsUser;     // relay transport account (e.g. relay_phone)
    private final String natsPass;
    private final String caFingerprint; // pinned household-CA SHA-256 (colon-hex), or null/"none" for system trust
    private final String zoneId;
    private final Consumer<S2CMessage> messageHandler;
    private final Consumer<Connection.Status> stateHandler; // nullable

    private final String sessionId = UUID.randomUUID().toString().replace("-", "");
    private final String base;
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    private volatile Connection nc;
    private volatile Dispatcher dispatcher;
    private volatile String token;
    private volatile boolean opened = false;
    private volatile boolean shutdownRequested = false;

    public RelayTunnelConnection(String relayUrl, String natsUser, String natsPass,
                                 String caFingerprint, String zoneId,
                                 Consumer<S2CMessage> messageHandler,
                                 Consumer<Connection.Status> stateHandler) {
        this.relayUrl = relayUrl;
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
        }
        // A household relay presents a self-signed, household-CA-issued leaf over
        // wss:// — the JVM default trust store rejects it (PKIX path building
        // failed). Pin the invite's CA fingerprint exactly as the phone clients
        // do (HouseholdTrustManager) and the server's own RelayAdminClient: trust
        // the chain iff some cert in it matches the pinned SHA-256. The relay leaf
        // carries the dial host as an IP/DNS SAN, so jnats hostname verification
        // still passes on top. A relay with a public-CA cert needs no pin.
        if (caFingerprint != null && !caFingerprint.isBlank()
                && !caFingerprint.equalsIgnoreCase("none")) {
            builder.sslContext(buildPinnedSslContext(caFingerprint));
        }
        nc = Nats.connect(builder.build());
    }

    /**
     * Fingerprint-pinned TLS — mirrors {@code RelayAdminClient.buildPinnedSslContext}
     * but matches ANY cert in the presented chain (leaf OR CA) against the pinned
     * value, so the invite's {@code ca_fp} (the household CA's SHA-256) validates a
     * leaf signed by that CA without needing the CA PEM on hand.
     */
    private static SSLContext buildPinnedSslContext(String expectedFingerprint) throws Exception {
        var expectedHex = expectedFingerprint.replace(":", "").replace(" ", "").toLowerCase();
        var pinner = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("Client auth not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Empty cert chain");
                }
                try {
                    var sha = MessageDigest.getInstance("SHA-256");
                    for (var cert : chain) {
                        var actualHex = HexFormat.of().formatHex(sha.digest(cert.getEncoded()));
                        if (actualHex.equalsIgnoreCase(expectedHex)) return;
                    }
                    throw new CertificateException("Fingerprint mismatch — no cert in the "
                        + chain.length + "-cert chain matches pinned " + expectedHex.substring(0, 16) + "…");
                } catch (NoSuchAlgorithmException e) {
                    throw new CertificateException("SHA-256 not available", e);
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinner}, null);
        return ctx;
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
            var reply = nc.request("wyrd.zone." + zoneId + ".mcp.login",
                Json.mapper().writeValueAsBytes(body), Duration.ofSeconds(8));
            if (reply == null) { log.warn("mcp.login over relay: no responder"); return false; }
            var node = Json.mapper().readTree(reply.getData());
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

    @Override
    public void connect() {
        try {
            ensureNats();
            dispatcher = nc.createDispatcher(this::onDown);
            dispatcher.subscribe(base + ".down");
            // Announce the session; the token (if any) auths the zone-side /ws.
            var open = Json.mapper().createObjectNode();
            if (token != null && !token.isBlank()) open.put("token", token);
            nc.publish(base + ".open", Json.mapper().writeValueAsBytes(open));
            nc.flush(Duration.ofSeconds(3));
            opened = true;
            connectedLatch.countDown();
            if (stateHandler != null) stateHandler.accept(Connection.Status.CONNECTED);
        } catch (Exception e) {
            log.error("Relay tunnel connect failed: {}", e.getMessage());
        }
    }

    private void onDown(Message msg) {
        try {
            var json = new String(msg.getData(), StandardCharsets.UTF_8);
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
            nc.publish(base + ".up", Json.mapper().writeValueAsBytes(msg));
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

    /** Re-open the tunnel under the current token (new session id is not needed; reuse base). */
    @Override
    public void reconnectWithToken() {
        try {
            if (nc != null && opened) {
                var open = Json.mapper().createObjectNode();
                if (token != null && !token.isBlank()) open.put("token", token);
                nc.publish(base + ".open", Json.mapper().writeValueAsBytes(open));
                nc.flush(Duration.ofSeconds(2));
            }
        } catch (Exception e) { log.warn("reconnectWithToken error: {}", e.getMessage()); }
    }
}
