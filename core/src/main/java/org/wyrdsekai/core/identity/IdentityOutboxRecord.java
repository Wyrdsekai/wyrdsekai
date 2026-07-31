package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * signed identity outbox record (Nostr NIP-65 analogue).
 *
 * <p>One signed envelope per DID declaring where the identity publishes and
 * where to reach it. Public by design; anyone can fetch and verify. Replaces
 * the scattered "where is alice?" lookups across {@code wyrd whoami}, contacts
 * CLI, federation agreements with a single canonical signed record.
 *
 * <p>Verification: extract the Ed25519 public key directly from the DID via
 * {@link DidKey#publicKeyFromDid}, then verify the {@code sig} field against
 * the canonical signing bytes ({@link #signingData}).
 *
 * <p>Latest-wins ordering: a higher {@code updatedAt} (sender wall-clock,
 * unix-ms) beats earlier. Ties broken lexicographically by signature — but
 * a tie is essentially impossible in practice (two different signatures over
 * different bytes can't match unless something pathological happened).
 *
 * <p>NB: this record is the wire format. The local-canonical storage shape
 * is {@code identity_outbox(did PK, record_json, updated_at, received_at)} —
 * see {@link IdentityOutboxStore}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityOutboxRecord(
    String did,
    String displayName,
    String primaryZone,
    List<String> writeZones,
    List<String> readZones,
    List<ChannelRef> channels,
    long updatedAt,
    String sig
) {

    /** Cross-protocol identity reference (e.g. type="nostr", address="npub1..."). */
    public record ChannelRef(String type, String address) {}

    /**
     * Mapper for canonical signing bytes. Same ObjectMapper config every time:
     * field order is fixed by the explicit map we build in {@link #signingData},
     * not by Jackson reflection, so this is safe.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Mapper for envelope serialization (record_json column + REST body).
     * Sorts keys so on-the-wire byte-identical for identical content.
     */
    private static final ObjectMapper WIRE_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Canonical bytes covered by the Ed25519 signature.
     * Excludes the {@code sig} field. Field order is fixed here, not by Jackson:
     * {did, displayName, primaryZone, writeZones, readZones, channels, updatedAt}.
     */
    static byte[] signingData(String did, String displayName, String primaryZone,
                              List<String> writeZones, List<String> readZones,
                              List<ChannelRef> channels, long updatedAt) {
        var m = new LinkedHashMap<String, Object>();
        m.put("did", did);
        m.put("displayName", displayName);
        m.put("primaryZone", primaryZone);
        m.put("writeZones", writeZones == null ? List.of() : writeZones);
        m.put("readZones", readZones == null ? List.of() : readZones);
        m.put("channels", channels == null ? List.of() : channels);
        m.put("updatedAt", updatedAt);
        try {
            return MAPPER.writeValueAsBytes(m);
        } catch (Exception e) {
            throw new RuntimeException("Canonical serialization failed", e);
        }
    }

    /** Bytes for re-signing this record's content. */
    public byte[] signingBytes() {
        return signingData(did, displayName, primaryZone, writeZones, readZones, channels, updatedAt);
    }

    /**
     * Sign and produce a complete record.
     *
     * @param did            full {@code did:key:z…} string of the signer
     * @param displayName    human-readable name (may be empty)
     * @param primaryZone    canonical home zone id
     * @param writeZones     zones this identity publishes into
     * @param readZones      zones to consult when looking for this identity
     * @param channels       cross-protocol identity refs (may be empty)
     * @param updatedAt      unix-ms; caller responsible for monotonicity
     * @param ed25519Private JDK Ed25519 private key matching the DID's pubkey
     */
    public static IdentityOutboxRecord sign(
        String did, String displayName, String primaryZone,
        List<String> writeZones, List<String> readZones,
        List<ChannelRef> channels, long updatedAt,
        PrivateKey ed25519Private
    ) {
        var data = signingData(did, displayName, primaryZone, writeZones, readZones, channels, updatedAt);
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(ed25519Private);
            sig.update(data);
            var bytes = sig.sign();
            var sigB64 = Base64.getEncoder().encodeToString(bytes);
            return new IdentityOutboxRecord(
                did, displayName, primaryZone,
                writeZones == null ? List.of() : List.copyOf(writeZones),
                readZones == null ? List.of() : List.copyOf(readZones),
                channels == null ? List.of() : List.copyOf(channels),
                updatedAt, sigB64);
        } catch (Exception e) {
            throw new RuntimeException("Sign failed", e);
        }
    }

    /**
     * Verify this record's signature against its DID's derivable public key.
     * Self-contained: the DID resolves to the verifier key. No registry lookup.
     *
     * @return true if signature valid AND key can be parsed from the DID.
     */
    public boolean verify() {
        var pub = DidKey.publicKeyFromDid(did);
        if (pub.isEmpty()) return false;
        return verifyAgainst(pub.get());
    }

    /** Verify against a caller-supplied public key (used in tests). */
    public boolean verifyAgainst(PublicKey publicKey) {
        if (sig == null || sig.isBlank()) return false;
        try {
            var data = signingBytes();
            var sigBytes = Base64.getDecoder().decode(sig);
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /** Wire-format JSON for storage / REST. Stable field ordering. */
    public String toWireJson() {
        try {
            return WIRE_MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Serialize failed", e);
        }
    }

    /** Parse from wire JSON. Does not verify; call {@link #verify()} after. */
    public static Optional<IdentityOutboxRecord> fromWireJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.of(WIRE_MAPPER.readValue(json, IdentityOutboxRecord.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** UTF-8 wire bytes; convenience for HTTP body writes. */
    public byte[] toWireBytes() {
        return toWireJson().getBytes(StandardCharsets.UTF_8);
    }
}
