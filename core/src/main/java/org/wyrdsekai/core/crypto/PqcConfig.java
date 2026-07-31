package org.wyrdsekai.core.crypto;

import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-Quantum Cryptography configuration (§73).
 * Uses JDK 25 native support for ML-KEM (JEP 496) and ML-DSA (JEP 497).
 * Provides configuration for Pekko Artery TLS with hybrid PQC.
 */
public class PqcConfig {

    /** Supported key exchange algorithms. */
    public enum KeyExchange {
        X25519("X25519", false),
        X25519_ML_KEM_768("X25519MLKEM768", true),
        ML_KEM_768("ML-KEM-768", true);

        private final String jceName;
        private final boolean postQuantum;

        KeyExchange(String jceName, boolean postQuantum) {
            this.jceName = jceName;
            this.postQuantum = postQuantum;
        }

        public String jceName() { return jceName; }
        public boolean isPostQuantum() { return postQuantum; }
    }

    /** Supported signature algorithms. */
    public enum SignatureAlgorithm {
        ED25519("Ed25519", false),
        ML_DSA_65("ML-DSA-65", true);

        private final String jceName;
        private final boolean postQuantum;

        SignatureAlgorithm(String jceName, boolean postQuantum) {
            this.jceName = jceName;
            this.postQuantum = postQuantum;
        }

        public String jceName() { return jceName; }
        public boolean isPostQuantum() { return postQuantum; }
    }

    /** Check if the JDK supports post-quantum algorithms. */
    public static boolean isPqcAvailable() {
        try {
            // JEP 496: ML-KEM is available as a KeyPairGenerator
            KeyPairGenerator.getInstance("ML-KEM");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** List available key exchange algorithms. */
    public static List<KeyExchange> availableKeyExchanges() {
        var available = new ArrayList<KeyExchange>();
        available.add(KeyExchange.X25519); // Always available
        if (isPqcAvailable()) {
            available.add(KeyExchange.X25519_ML_KEM_768);
            available.add(KeyExchange.ML_KEM_768);
        }
        return available;
    }

    /** List available signature algorithms. */
    public static List<SignatureAlgorithm> availableSignatures() {
        var available = new ArrayList<SignatureAlgorithm>();
        available.add(SignatureAlgorithm.ED25519); // Always available
        try {
            Signature.getInstance("ML-DSA-65");
            available.add(SignatureAlgorithm.ML_DSA_65);
        } catch (Exception e) {
            // Not available
        }
        return available;
    }

    /** Get recommended TLS cipher suites for Pekko Artery. */
    public static List<String> recommendedCipherSuites() {
        var suites = new ArrayList<String>();
        // Prefer hybrid PQC if available
        if (isPqcAvailable()) {
            suites.add("TLS_AES_256_GCM_SHA384");
        }
        suites.add("TLS_AES_128_GCM_SHA256");
        suites.add("TLS_CHACHA20_POLY1305_SHA256");
        return suites;
    }

    /** Human-readable summary of crypto capabilities. */
    public static String describe() {
        var sb = new StringBuilder("=== Cryptographic Capabilities ===\n\n");
        sb.append("PQC available: ").append(isPqcAvailable()).append("\n");
        sb.append("Key exchange: ").append(availableKeyExchanges()).append("\n");
        sb.append("Signatures: ").append(availableSignatures()).append("\n");
        sb.append("Java version: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Security providers:\n");
        for (var provider : Security.getProviders()) {
            sb.append("  ").append(provider.getName())
                .append(" v").append(provider.getVersionStr()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
