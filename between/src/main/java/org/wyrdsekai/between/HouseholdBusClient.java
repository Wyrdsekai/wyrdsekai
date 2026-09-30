package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How this node's own NATS clients log in to a household bus ( W2).
 *
 * <ul>
 *   <li>The home this machine joined with {@code wyrd join} ({@code <data>/nats/hub.json}): always
 *       TLS, trusting only that home's CA, with the login it issued. Never in the clear on the network.</li>
 *   <li>This machine's own bus: the node's login, and TLS trusting this home's CA when the bus offers
 *       it. What the bus asks for is read from its first line, so a bus from before 0.5.0 (or a test
 *       server) on this machine is still reached as before.</li>
 *   <li>Any other server: left as the caller configured it.</li>
 * </ul>
 */
public final class HouseholdBusClient {

    private static final Logger log = LoggerFactory.getLogger(HouseholdBusClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private HouseholdBusClient() {}

    public static Options.Builder secure(Options.Builder b, String url) {
        return secure(b, url, HouseholdBus.defaultDataDir());
    }

    static Options.Builder secure(Options.Builder b, String url, Path dataDir) {
        try {
            var hub = HouseholdBus.readHubLink(dataDir);
            if (hub.isPresent() && HouseholdBus.sameServer(url, hub.get().url())) {
                return b.userInfo(hub.get().user(), hub.get().pass())
                    .sslContext(HouseholdTls.clientContext(hub.get().ca()));
            }
            var host = host(url);
            if (host == null || !isThisMachine(host)) return b;
            var info = info(url);
            if (info == null) return b;
            boolean tls = info.path("tls_required").asBoolean(false) || info.path("tls_available").asBoolean(false);
            boolean auth = info.path("auth_required").asBoolean(false);
            if (!tls && !auth) return b;
            var node = HouseholdBus.open(dataDir).nodeCredential();
            b.userInfo(node.user(), node.pass());
            if (tls) {
                var ca = HouseholdTls.readCa(dataDir);
                if (ca != null) b.sslContext(HouseholdTls.clientContext(ca));
            }
            return b;
        } catch (Exception e) {
            log.warn("Household bus login for {} could not be prepared: {}", url, e.getMessage());
            return b;
        }
    }

    private static boolean isThisMachine(String host) {
        var h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]")) return true;
        return HouseholdTls.localNames().contains(h);
    }

    private static String host(String url) {
        try {
            return URI.create(url.contains("://") ? url : "nats://" + url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The server's INFO line, read before any TLS; null when it cannot be read. */
    static JsonNode info(String url) {
        try {
            var u = URI.create(url.contains("://") ? url : "nats://" + url);
            try (var sock = new Socket()) {
                sock.connect(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 4222 : u.getPort()), 3000);
                sock.setSoTimeout(3000);
                var line = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (line == null || !line.startsWith("INFO ")) return null;
                return JSON.readTree(line.substring(5));
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
