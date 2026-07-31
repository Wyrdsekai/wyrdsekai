package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Immutable description of a zone's public identity.
 * Exchanged during federation handshake and stored in zone_manifests table.
 *
 * Optionally signed with Ed25519 for cryptographic verification.
 * The signature covers the canonical string:
 * {@code zoneId|zoneName|publicKey|natsUrl|httpUrl|arteryPort|capabilities|createdAt}
 */
public record ZoneManifest(
    @JsonProperty("zoneId") String zoneId,
    @JsonProperty("zoneName") String zoneName,
    @JsonProperty("publicKey") String publicKey,
    @JsonProperty("natsUrl") String natsUrl,
    @JsonProperty("httpUrl") String httpUrl,
    @JsonProperty("arteryPort") int arteryPort,
    @JsonProperty("capabilities") List<String> capabilities,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("signature") String signature,
    @JsonProperty("aestheticPreset") String aestheticPreset,
    /**
     * build/version info for mesh-drift
     * detection and handshake compatibility check. Nullable for backward
     * compat with peers running pre-F14 code (treated as "unknown" — log
     * a warning, allow handshake). Excluded from {@link #canonicalString()}
     * so existing Ed25519 signatures still verify.
     */
    @JsonProperty("buildVersion") BuildVersion buildVersion,
    /**
     * the relays this zone is reachable on, so a
     * federation peer can compute the shared relay (RelayPathSelector). Carries
     * only dial address + CA fingerprint + visibility — never user/token. Nullable
     * for pre-multihoming peers. Excluded from {@link #canonicalString()} so
     * existing Ed25519 signatures still verify, exactly like buildVersion.
     */
    @JsonProperty("relays") List<RelayAdvert> relays
) {

    /**
     * one advertised relay leg (no secrets).
     *
     * {@code @JsonIgnoreProperties(ignoreUnknown=true)} so a peer running newer
     * code that adds a field to this advert doesn't make an older peer's strict
     * deserializer reject the whole proposal (federation cross-version forward
     * compat — a non-empty relays list is itself recent, so this skew is live).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelayAdvert(
        @JsonProperty("url") String url,
        @JsonProperty("caFingerprint") String caFingerprint,
        @JsonProperty("visibility") String visibility   // "private" | "public"
    ) {
        // @JsonIgnore: this is a derived convenience accessor, NOT a wire field.
        // Without it Jackson serialises a phantom "public" property (bean naming
        // of isPublic()), which a peer's strict record deserializer then rejects
        // as an unknown field — the exact bug that blocked α↔β federation once
        // zones began advertising their relays.
        @JsonIgnore
        public boolean isPublic() { return "public".equalsIgnoreCase(visibility); }
    }

    /** F14: build/version info embedded in the federation handshake. */
    public record BuildVersion(
        @JsonProperty("appVersion") String appVersion,
        @JsonProperty("buildHash") String buildHash,
        @JsonProperty("gitSha") String gitSha,
        @JsonProperty("buildTimestamp") Instant buildTimestamp,
        @JsonProperty("wireProtocol") int wireProtocol,
        @JsonProperty("federationSchema") int federationSchema,
        @JsonProperty("gitDirty") boolean gitDirty
    ) {}

    /** Backward-compatible constructor without signature/aesthetic. */
    public ZoneManifest(String zoneId, String zoneName, String publicKey,
                        String natsUrl, String httpUrl, int arteryPort,
                        List<String> capabilities, Instant createdAt) {
        this(zoneId, zoneName, publicKey, natsUrl, httpUrl, arteryPort,
             capabilities, createdAt, null, null, null, null);
    }

    /** Backward-compatible constructor without aesthetic. */
    public ZoneManifest(String zoneId, String zoneName, String publicKey,
                        String natsUrl, String httpUrl, int arteryPort,
                        List<String> capabilities, Instant createdAt, String signature) {
        this(zoneId, zoneName, publicKey, natsUrl, httpUrl, arteryPort,
             capabilities, createdAt, signature, null, null, null);
    }

    /** Backward-compatible constructor without buildVersion (pre-F14 callers). */
    public ZoneManifest(String zoneId, String zoneName, String publicKey,
                        String natsUrl, String httpUrl, int arteryPort,
                        List<String> capabilities, Instant createdAt,
                        String signature, String aestheticPreset) {
        this(zoneId, zoneName, publicKey, natsUrl, httpUrl, arteryPort,
             capabilities, createdAt, signature, aestheticPreset, null, null);
    }

    /** Backward-compatible constructor without relays (pre-multihoming callers). */
    public ZoneManifest(String zoneId, String zoneName, String publicKey,
                        String natsUrl, String httpUrl, int arteryPort,
                        List<String> capabilities, Instant createdAt,
                        String signature, String aestheticPreset, BuildVersion buildVersion) {
        this(zoneId, zoneName, publicKey, natsUrl, httpUrl, arteryPort,
             capabilities, createdAt, signature, aestheticPreset, buildVersion, null);
    }

    public ZoneManifest {
        if (zoneId == null || zoneId.isBlank()) throw new IllegalArgumentException("zoneId required");
        if (zoneName == null || zoneName.isBlank()) throw new IllegalArgumentException("zoneName required");
        if (publicKey == null || publicKey.isBlank()) throw new IllegalArgumentException("publicKey required");
        if (capabilities == null) capabilities = List.of();
        if (createdAt == null) createdAt = Instant.now();
        if (relays == null) relays = List.of();
        // signature, aestheticPreset, buildVersion can be null (backward compat)
    }

    /** Return a copy with buildVersion set. F14. */
    public ZoneManifest withBuildVersion(BuildVersion bv) {
        return new ZoneManifest(zoneId, zoneName, publicKey, natsUrl, httpUrl,
            arteryPort, capabilities, createdAt, signature, aestheticPreset, bv, relays);
    }

    /** Return a copy with the advertised relay set. */
    public ZoneManifest withRelays(List<RelayAdvert> r) {
        return new ZoneManifest(zoneId, zoneName, publicKey, natsUrl, httpUrl,
            arteryPort, capabilities, createdAt, signature, aestheticPreset, buildVersion, r);
    }

    /**
     * Signs an unsigned manifest with the given Ed25519 private key.
     * Returns a new ZoneManifest with the signature field filled in.
     */
    public static ZoneManifest sign(ZoneManifest unsigned, PrivateKey key) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(unsigned.canonicalBytes());
            var signatureStr = Base64.getEncoder().encodeToString(sig.sign());
            return new ZoneManifest(
                unsigned.zoneId(), unsigned.zoneName(), unsigned.publicKey(),
                unsigned.natsUrl(), unsigned.httpUrl(), unsigned.arteryPort(),
                unsigned.capabilities(), unsigned.createdAt(), signatureStr,
                unsigned.aestheticPreset(), unsigned.buildVersion(), unsigned.relays()
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign zone manifest", e);
        }
    }

    /**
     * Verifies the Ed25519 signature against the given public key.
     * Returns false if the manifest is unsigned (signature is null).
     */
    public boolean verify(PublicKey key) {
        if (signature == null) return false;
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(canonicalBytes());
            return sig.verify(Base64.getDecoder().decode(signature));
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            return false;
        }
    }

    /**
     * Canonical string used for signing:
     * zoneId|zoneName|publicKey|natsUrl|httpUrl|arteryPort|capabilities|createdAt
     */
    String canonicalString() {
        return zoneId + "|" + zoneName + "|" + publicKey + "|" + natsUrl + "|" + httpUrl
            + "|" + arteryPort + "|" + String.join(",", capabilities) + "|" + createdAt;
    }

    private byte[] canonicalBytes() {
        return canonicalString().getBytes(StandardCharsets.UTF_8);
    }
}
