package org.wyrdsekai.core.crypto;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hybrid PQC TLS configuration for Pekko Artery (§73, Phase 10A).
 * Uses JDK 25 native JSSE for X25519MLKEM768 hybrid key exchange.
 * Falls back to classical X25519 if PQC is not available.
 */
public class PqcTlsConfig {

    /** Named group preferences for TLS. */
    public enum NamedGroup {
        X25519_ML_KEM_768("x25519_ml_kem_768", true),
        X25519("x25519", false),
        SECP256R1("secp256r1", false);

        private final String jsseName;
        private final boolean postQuantum;

        NamedGroup(String jsseName, boolean postQuantum) {
            this.jsseName = jsseName;
            this.postQuantum = postQuantum;
        }

        public String jsseName() { return jsseName; }
        public boolean isPostQuantum() { return postQuantum; }
    }

    /** TLS protocol version preference. */
    public static final String TLS_1_3 = "TLSv1.3";

    /**
     * Create SSLParameters configured for hybrid PQC.
     * Prefers X25519MLKEM768 if available, falls back to classical.
     */
    public static SSLParameters hybridParameters() {
        var params = new SSLParameters();
        params.setProtocols(new String[]{TLS_1_3});
        params.setCipherSuites(PqcConfig.recommendedCipherSuites().toArray(new String[0]));

        // Set named groups preference — hybrid first
        var groups = availableNamedGroups();
        params.setNamedGroups(groups.stream().map(NamedGroup::jsseName).toArray(String[]::new));

        return params;
    }

    /**
     * Create an SSLContext with TLS 1.3 support.
     */
    public static SSLContext createContext() {
        try {
            return SSLContext.getInstance(TLS_1_3);
        } catch (NoSuchAlgorithmException e) {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException("No TLS support available", ex);
            }
        }
    }

    /**
     * List available named groups, preferring hybrid PQC.
     */
    public static List<NamedGroup> availableNamedGroups() {
        var groups = new ArrayList<NamedGroup>();
        if (PqcConfig.isPqcAvailable()) {
            groups.add(NamedGroup.X25519_ML_KEM_768);
        }
        groups.add(NamedGroup.X25519);
        groups.add(NamedGroup.SECP256R1);
        return groups;
    }

    /**
     * Generate Pekko Artery TLS configuration snippet (HOCON format).
     * Suitable for embedding in application.conf.
     */
    public static String pekkoArteryTlsConfig() {
        var sb = new StringBuilder();
        sb.append("pekko.remote.artery {\n");
        sb.append("  transport = tls-tcp\n");
        sb.append("  advanced {\n");
        sb.append("    ssl {\n");
        sb.append("      protocol = \"TLSv1.3\"\n");

        var suites = PqcConfig.recommendedCipherSuites();
        sb.append("      enabled-algorithms = [");
        for (int i = 0; i < suites.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(suites.get(i)).append("\"");
        }
        sb.append("]\n");

        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Check if the current JVM supports hybrid PQC TLS.
     */
    public static boolean isHybridPqcTlsAvailable() {
        return PqcConfig.isPqcAvailable();
    }

    /** Human-readable summary. */
    public static String describe() {
        var sb = new StringBuilder("=== TLS Configuration ===\n\n");
        sb.append("Protocol: TLS 1.3\n");
        sb.append("Hybrid PQC: ").append(isHybridPqcTlsAvailable() ? "AVAILABLE" : "NOT AVAILABLE").append("\n");
        sb.append("Named groups: ");
        var groups = availableNamedGroups();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(groups.get(i).jsseName());
            if (groups.get(i).isPostQuantum()) sb.append(" [PQ]");
        }
        sb.append("\n");
        sb.append("Cipher suites: ").append(PqcConfig.recommendedCipherSuites());
        return sb.toString();
    }
}
