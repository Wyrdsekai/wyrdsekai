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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional TLS configuration for the Javalin/Jetty server.
 *
 * When enabled, adds an HTTPS connector alongside the default HTTP connector.
 * When disabled, does nothing (Javalin uses its default HTTP server).
 *
 * Configuration (application.conf):
 *   wyrdsekai.tls.enabled = true
 *   wyrdsekai.tls.port = 7443
 *   wyrdsekai.tls.keystore-path = "/path/to/keystore.jks"
 *   wyrdsekai.tls.keystore-password = "changeit"
 *
 * If no keystore exists at the configured path and auto-generate is true,
 * a self-signed certificate is generated via the JDK keytool. This is
 * suitable for development and LAN deployments (household servers).
 *
 * For internet-facing deployments, use a reverse proxy (Caddy, nginx) for
 * TLS termination, or provide a proper keystore from Let's Encrypt / ACME.
 */
public final class TlsConfig {

    private static final Logger log = LoggerFactory.getLogger(TlsConfig.class);

    private TlsConfig() {}

    /**
     * Configure TLS on a Javalin app via {@code cfg.jetty.addConnector()}.
     * No-op if TLS is disabled or not configured.
     *
     * @param cfg    Javalin config (inside the create callback)
     * @param config Full application config
     */
    public static void configure(JavalinConfig cfg, Config config) {
        boolean enabled;
        try {
            enabled = config.getBoolean("wyrdsekai.tls.enabled");
        } catch (Exception e) {
            return; // TLS not configured
        }
        if (!enabled) return;

        int tlsPort = 7443;
        String keystorePath;
        String keystorePassword;
        boolean autoGenerate;

        try {
            tlsPort = config.getInt("wyrdsekai.tls.port");
        } catch (Exception ignored) {}
        try {
            keystorePath = config.getString("wyrdsekai.tls.keystore-path");
            if (keystorePath.isEmpty()) {
                keystorePath = System.getProperty("user.home") + "/.wyrdsekai/keystore.jks";
            }
        } catch (Exception e) {
            keystorePath = System.getProperty("user.home") + "/.wyrdsekai/keystore.jks";
        }
        try {
            keystorePassword = config.getString("wyrdsekai.tls.keystore-password");
        } catch (Exception e) {
            keystorePassword = "wyrdsekai";
        }
        try {
            autoGenerate = config.getBoolean("wyrdsekai.tls.auto-generate");
        } catch (Exception e) {
            autoGenerate = true;
        }

        // Auto-generate self-signed cert if keystore missing
        var ksPath = Path.of(keystorePath);
        if (!Files.exists(ksPath)) {
            if (autoGenerate) {
                generateSelfSignedKeystore(ksPath, keystorePassword);
            } else {
                log.error("TLS keystore not found: {} (auto-generate disabled)", keystorePath);
                return;
            }
        }

        if (!Files.exists(ksPath)) {
            log.error("TLS keystore still missing after generation attempt: {}", keystorePath);
            return;
        }

        // Add HTTPS connector via Javalin 6 API
        final var ksPathStr = keystorePath;
        final var ksPass = keystorePassword;
        final var port = tlsPort;
        cfg.jetty.addConnector((server, httpConfig) -> {
            var httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            var sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(ksPathStr);
            sslContextFactory.setKeyStorePassword(ksPass);

            var connector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                new HttpConnectionFactory(httpsConfig));
            connector.setPort(port);
            return connector;
        });

        log.info("TLS enabled — HTTPS on port {}, keystore: {}", tlsPort, keystorePath);
    }

    /**
     * Generate a self-signed keystore via JDK keytool.
     * Suitable for development and LAN household deployments.
     */
    private static void generateSelfSignedKeystore(Path keystorePath, String password) {
        try {
            Files.createDirectories(keystorePath.getParent());
            var hostname = InetAddress.getLocalHost().getHostName();

            var process = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "wyrdsekai",
                "-keyalg", "EC",
                "-keysize", "256",
                "-validity", "3650",
                "-keystore", keystorePath.toString(),
                "-storepass", password,
                "-dname", "CN=" + hostname + ",O=Wyrdsekai,L=Home",
                "-ext", "san=dns:localhost,dns:" + hostname + ",ip:127.0.0.1"
            ).redirectErrorStream(true).start();

            var output = new String(process.getInputStream().readAllBytes());
            var exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("Self-signed TLS keystore generated: {}", keystorePath);
            } else {
                log.error("keytool failed (exit {}): {}", exitCode, output);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate self-signed keystore: {}", e.getMessage());
        }
    }
}
