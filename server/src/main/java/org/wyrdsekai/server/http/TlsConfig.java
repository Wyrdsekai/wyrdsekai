package org.wyrdsekai.server.http;

import com.typesafe.config.Config;
import io.javalin.config.JavalinConfig;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * HTTPS and WSS for the household ( W2, D2).
 *
 * <p>On by default: an HTTPS connector on {@code wyrdsekai.tls.port} (7443) on every interface, next to
 * the plain connector, which answers this machine only. The certificate is this machine's household
 * leaf ({@link HouseholdTls}), signed by the home's own CA whose fingerprint rides in pairing invites;
 * phones and household machines pin that CA. The keystore is {@code <data>/tls/household.p12}, its
 * password a random per-install secret ({@code <data>/tls/keystore.pass}, 0600).</p>
 *
 * <p>Configuration (application.conf / environment):
 * {@code wyrdsekai.tls.enabled} (WYRDSEKAI_TLS_ENABLED, default true),
 * {@code wyrdsekai.tls.port} (WYRDSEKAI_TLS_PORT, default 7443),
 * {@code wyrdsekai.tls.keystore-path} (WYRDSEKAI_TLS_KEYSTORE: a keystore of your own instead of the
 * household leaf, e.g. from ACME) and {@code wyrdsekai.tls.keystore-password} (WYRDSEKAI_TLS_PASSWORD;
 * unset means the per-install secret). Until 0.5.0 TLS was off by default and a self-signed keystore
 * was made with the password "wyrdsekai".</p>
 */
public final class TlsConfig {

    private static final Logger log = LoggerFactory.getLogger(TlsConfig.class);

    private TlsConfig() {}

    /**
     * Adds the HTTPS connector to a Javalin app. Never fails the server's start: without it phones on
     * the network cannot reach this home directly, but the relay and this machine still can.
     */
    public static void configure(JavalinConfig cfg, Config config) {
        configure(cfg, config, HouseholdBus.defaultDataDir());
    }

    static void configure(JavalinConfig cfg, Config config, Path dataDir) {
        if (!enabled(config)) {
            log.warn("HTTPS is off (WYRDSEKAI_TLS_ENABLED=false): phones and other machines on the network "
                + "cannot reach this home directly. The relay still works.");
            return;
        }
        int port = port(config);
        var keystore = keystore(config, dataDir);
        if (keystore == null) return;
        if (!portFree(port)) {
            log.error("HTTPS port {} is in use by another program: phones and other machines on the network cannot "
                + "reach this home directly. Free the port or set WYRDSEKAI_TLS_PORT.", port);
            return;
        }
        // Javalin builds its own plain connector only when no connector was added, so adding HTTPS alone
        // dropped the plain port (every setup that turned TLS on before 0.5.0 lost :7070). Keep it, on
        // the host and port given to app.start, and first, so app.port() is still the plain port.
        var jetty = cfg.jetty;
        cfg.jetty.addConnector((server, httpConfig) -> {
            var plain = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
            plain.setHost(jetty.host);
            plain.setPort(jetty.port);
            return plain;
        });
        cfg.jetty.addConnector((server, httpConfig) -> {
            var httpsConfig = new HttpConfiguration(httpConfig);
            // Clients reach this home by any of its addresses and pin the household CA; the Host header
            // need not be one of the certificate's names.
            httpsConfig.addCustomizer(new SecureRequestCustomizer(false));
            var ssl = new SslContextFactory.Server();
            ssl.setKeyStore(keystore.store());
            ssl.setKeyStorePassword(new String(keystore.password()));
            var connector = new ServerConnector(server,
                new SslConnectionFactory(ssl, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
            connector.setPort(port);
            return connector;
        });
        log.info("HTTPS and WSS on port {} (all interfaces), certificate: {}", port, keystore.source());
    }

    public static boolean enabled(Config config) {
        return bool(config, "wyrdsekai.tls.enabled", true);
    }

    public static int port(Config config) {
        try {
            return config.hasPath("wyrdsekai.tls.port") ? config.getInt("wyrdsekai.tls.port") : 7443;
        } catch (Exception e) {
            return 7443;
        }
    }

    record Keystore(KeyStore store, char[] password, String source) {}

    /** The operator's keystore when one is configured and opens, else the household leaf. */
    static Keystore keystore(Config config, Path dataDir) {
        HouseholdTls.Material household = null;
        try {
            household = HouseholdTls.ensure(dataDir);
        } catch (Exception e) {
            log.error("The household certificate could not be made in {} ({})", dataDir.resolve(HouseholdTls.DIR), e.getMessage());
        }
        var path = string(config, "wyrdsekai.tls.keystore-path");
        if (!path.isBlank()) {
            var pass = string(config, "wyrdsekai.tls.keystore-password");
            char[] pw = !pass.isBlank() ? pass.toCharArray()
                : household != null ? household.keystorePassword() : new char[0];
            try {
                return new Keystore(KeyStore.getInstance(Path.of(path).toFile(), pw), pw, path);
            } catch (Exception e) {
                log.error("The keystore {} (WYRDSEKAI_TLS_KEYSTORE) does not open ({}); set WYRDSEKAI_TLS_PASSWORD to its "
                    + "password. Serving the household certificate instead.", path, e.getMessage());
            }
        }
        if (household == null) {
            log.error("HTTPS is not served: no certificate. Phones and other machines on the network cannot reach this home directly.");
            return null;
        }
        try {
            return new Keystore(KeyStore.getInstance(household.keystore().toFile(), household.keystorePassword()),
                household.keystorePassword(), "household certificate until " + household.leafExpires()
                + ", CA fingerprint " + household.caFingerprint());
        } catch (Exception e) {
            log.error("The household keystore does not open ({}): HTTPS is not served", e.getMessage());
            return null;
        }
    }

    static boolean portFree(int port) {
        try (var ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean bool(Config config, String key, boolean def) {
        try {
            return config.hasPath(key) ? config.getBoolean(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String string(Config config, String key) {
        try {
            return config.hasPath(key) ? config.getString(key).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
