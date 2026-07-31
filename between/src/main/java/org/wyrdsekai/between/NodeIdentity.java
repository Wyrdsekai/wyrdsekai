package org.wyrdsekai.between;

import io.nats.client.AuthHandler;
import io.nats.client.NKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Node identity based on Ed25519 keypair.
 * Uses standard JDK crypto (JEP 339, Java 15+). No external dependencies.
 * Generated on first boot, stored encrypted at ~/.wyrdsekai/node-identity.json.
 *
 * <p> canonical: {@code node-identity.json} for
 * the encrypted private key. The file deliberately stays outside
 * {@code world.db} — different backup policy, different filesystem
 * permissions, different rotation rituals. F7b Phase 4 will mirror the
 * <i>public</i> key into a {@code households} table for queries, but
 * the private key never leaves this file.</p>
 */
public final class NodeIdentity {

    private static final Logger log = LoggerFactory.getLogger(NodeIdentity.class);
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;

    private final String nodeId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    /**
     * NATS NKey seed — a separate Ed25519 keypair used for relay NATS auth.
     * Coexists with {@code privateKey}/{@code publicKey} (which sign Between protocol envelopes).
     * Both keys live in the same encrypted file ({@code node-identity.json}); same backup,
     * same lifecycle. The NKey seed is a 58-character string (NATS-format,
     * Base32 + CRC16) that jnats's {@link NKey#fromSeed(char[])} accepts directly.
     *
     * <p>Lazy: null until first {@link #nkeyAuthHandler()} call OR until loaded from a file
     * that already had {@code nkeySeed} persisted. On first lazy-create the file is re-saved
     * with the new seed so subsequent loads are deterministic.</p>
     */
    private volatile char[] nkeySeed;

    /**
     * X25519 keypair (raw PKCS#8 private / X.509-SPKI public) used for the multi-node zone-secret
     * grant ( #1184): a zone holder ECDH-wraps the 32-byte zone master to a joining node's
     * X25519 public key so both nodes derive the identical argot key. Separate keypair from the
     * Ed25519 signing key + the NATS NKey — same encrypted file, same lifecycle. Lazy like
     * {@link #nkeySeed}: null until first {@link #x25519PublicKeyBytes()} call OR loaded from a file
     * that already persisted it; on first lazy-create the file is re-saved.
     */
    private volatile byte[] x25519PrivPkcs8;
    private volatile byte[] x25519PubSpki;

    /** Path the identity was loaded from (or will be saved to). Used for lazy NKey persistence. */
    private final Path identityFilePath;

    private NodeIdentity(String nodeId, PrivateKey privateKey, PublicKey publicKey,
                         char[] nkeySeed, byte[] x25519PrivPkcs8, byte[] x25519PubSpki,
                         Path identityFilePath) {
        this.nodeId = nodeId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.nkeySeed = nkeySeed;
        this.x25519PrivPkcs8 = x25519PrivPkcs8;
        this.x25519PubSpki = x25519PubSpki;
        this.identityFilePath = identityFilePath;
    }

    /**
     * Load identity from file, or generate a new one if the file doesn't exist.
     */
    public static NodeIdentity loadOrGenerate(Path identityFile) throws IOException {
        if (Files.isRegularFile(identityFile)) {
            return load(identityFile);
        }
        return generate(identityFile);
    }

    private static NodeIdentity generate(Path identityFile) throws IOException {
        try {
            var nodeId = UUID.randomUUID().toString();

            var kpg = KeyPairGenerator.getInstance("Ed25519");
            var keyPair = kpg.generateKeyPair();

            // Generate the NATS NKey seed alongside. Different Ed25519 keypair, same lifecycle.
            // jnats produces a 58-char Base32+CRC16 seed string; we persist it encrypted in the
            // same file alongside the PKCS8 between-protocol key.
            char[] nkeySeed;
            try {
                var nkey = NKey.createUser(new SecureRandom());
                nkeySeed = nkey.getSeed();
            } catch (Exception e) {
                throw new IOException("Failed to generate NATS NKey for relay auth", e);
            }

            // X25519 grant keypair (#1184) — generated alongside, persisted in the same file.
            byte[] x25519Priv, x25519Pub;
            try {
                var xkp = KeyPairGenerator.getInstance("X25519").generateKeyPair();
                x25519Priv = xkp.getPrivate().getEncoded();
                x25519Pub = xkp.getPublic().getEncoded();
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("X25519 not available in this JDK", e);
            }

            var identity = new NodeIdentity(nodeId, keyPair.getPrivate(), keyPair.getPublic(),
                nkeySeed, x25519Priv, x25519Pub, identityFile);
            identity.save(identityFile);

            log.info("Generated new node identity: {} (public key: {})",
                nodeId, fingerprint(keyPair.getPublic().getEncoded()));

            return identity;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Ed25519 not available in this JDK", e);
        }
    }

    private static NodeIdentity load(Path identityFile) throws IOException {
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(identityFile.toFile());

            var nodeId = root.get("nodeId").asText();
            var publicKeyBytes = Base64.getDecoder().decode(root.get("publicKey").asText());
            var encryptedPrivateKey = Base64.getDecoder().decode(root.get("encryptedPrivateKey").asText());

            var kd = root.get("keyDerivation");
            var salt = Base64.getDecoder().decode(kd.get("salt").asText());
            var iterations = kd.get("iterations").asInt();

            var derivedKey = deriveKey(salt, iterations);
            var privateKeyBytes = decrypt(encryptedPrivateKey, derivedKey);

            var keyFactory = KeyFactory.getInstance("Ed25519");
            var privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            var publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            // load NKey seed if present. Older identity files (pre-NKey)
            // lack this field; nkeySeed stays null and gets lazy-created on first
            // nkeyAuthHandler() call. Load doesn't fail on missing field.
            char[] nkeySeed = null;
            if (root.has("encryptedNkeySeed")) {
                try {
                    var encryptedSeed = Base64.getDecoder().decode(
                        root.get("encryptedNkeySeed").asText());
                    var seedBytes = decrypt(encryptedSeed, derivedKey);
                    nkeySeed = new String(seedBytes, StandardCharsets.UTF_8)
                        .toCharArray();
                    Arrays.fill(seedBytes, (byte) 0);
                } catch (Exception e) {
                    log.warn("Failed to decrypt NKey seed from {} — will regenerate on use: {}",
                        identityFile, e.getMessage());
                    nkeySeed = null;
                }
            }

            // #1184: load the X25519 grant keypair if present. Older files lack it; stays null and
            // lazy-creates on first x25519PublicKeyBytes() call. Private is encrypted; public is clear.
            byte[] x25519Priv = null, x25519Pub = null;
            if (root.has("encryptedX25519PrivateKey") && root.has("x25519PublicKey")) {
                try {
                    x25519Priv = decrypt(
                        Base64.getDecoder().decode(root.get("encryptedX25519PrivateKey").asText()),
                        derivedKey);
                    x25519Pub = Base64.getDecoder().decode(root.get("x25519PublicKey").asText());
                } catch (Exception e) {
                    log.warn("Failed to decrypt X25519 grant key from {} — will regenerate on use: {}",
                        identityFile, e.getMessage());
                    x25519Priv = null; x25519Pub = null;
                }
            }

            log.info("Loaded node identity: {} (public key: {}{})",
                nodeId, fingerprint(publicKeyBytes),
                nkeySeed != null ? ", with relay NKey" : ", relay NKey will be generated lazily");

            return new NodeIdentity(nodeId, privateKey, publicKey, nkeySeed,
                x25519Priv, x25519Pub, identityFile);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load node identity keys", e);
        }
    }

    private void save(Path identityFile) throws IOException {
        Files.createDirectories(identityFile.getParent());

        var salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);

        var derivedKey = deriveKey(salt, PBKDF2_ITERATIONS);
        // Store private key in PKCS8 format (standard JDK encoding)
        var encryptedPrivateKey = encrypt(privateKey.getEncoded(), derivedKey);

        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("nodeId", nodeId);
        root.put("publicKey", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        root.put("encryptedPrivateKey", Base64.getEncoder().encodeToString(encryptedPrivateKey));
        root.put("keyFormat", "PKCS8");

        // persist NKey seed encrypted with same derived key. Skip if
        // nkeySeed is null (old-format file being saved before lazy-create kicks in).
        if (nkeySeed != null) {
            var seedBytes = new String(nkeySeed)
                .getBytes(StandardCharsets.UTF_8);
            try {
                var encryptedNkeySeed = encrypt(seedBytes, derivedKey);
                root.put("encryptedNkeySeed",
                    Base64.getEncoder().encodeToString(encryptedNkeySeed));
            } finally {
                Arrays.fill(seedBytes, (byte) 0);
            }
        }

        // #1184: persist the X25519 grant keypair — private encrypted with the same derived key,
        // public in clear (it's exchanged openly anyway). Skip if not yet lazy-created.
        if (x25519PrivPkcs8 != null && x25519PubSpki != null) {
            root.put("encryptedX25519PrivateKey",
                Base64.getEncoder().encodeToString(encrypt(x25519PrivPkcs8, derivedKey)));
            root.put("x25519PublicKey", Base64.getEncoder().encodeToString(x25519PubSpki));
        }

        var kd = mapper.createObjectNode();
        kd.put("salt", Base64.getEncoder().encodeToString(salt));
        kd.put("iterations", PBKDF2_ITERATIONS);
        kd.put("algorithm", "PBKDF2WithHmacSHA256");
        root.set("keyDerivation", kd);

        mapper.writerWithDefaultPrettyPrinter().writeValue(identityFile.toFile(), root);
    }

    // --- Public API ---

    public String nodeId() {
        return nodeId;
    }

    public byte[] publicKeyBytes() {
        return publicKey.getEncoded();
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Raw 32-byte Ed25519 private-key seed.
     *
     * <p>Used for HKDF-derived secondary keys (e.g. Nostr secp256k1 via
     * {@code NostrKey.deriveFromEd25519PrivateKey}). The seed is the last
     * 32 bytes of the JDK PKCS#8 encoding; same shape used by
     * {@code AgentIdentity.extractRawPrivateKey}.
     *
     * <p><b>Sensitive.</b> Treat like a private key — don't log or pass over
     * the wire. Only callers that need to derive secondary keys should use it.
     */
    public byte[] privateKeySeedBytes() {
        var encoded = privateKey.getEncoded();
        var seed = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, seed, 0, 32);
        return seed;
    }

    /**
     * This node's X25519 grant <b>public</b> key (X.509/SPKI bytes) — shared openly (households
     * mirror / federation handshake) so a zone holder can ECDH-wrap the zone master to it
     * ( #1184, {@code ZoneSecretService.grantTo}). Lazy-creates + persists on first call.
     */
    public synchronized byte[] x25519PublicKeyBytes() {
        ensureX25519();
        return x25519PubSpki.clone();
    }

    /** Base64 of {@link #x25519PublicKeyBytes()} — the form mirrored into the households table. */
    public synchronized String x25519PublicKeyBase64() {
        return Base64.getEncoder().encodeToString(x25519PublicKeyBytes());
    }

    /**
     * This node's X25519 grant <b>private</b> key (PKCS#8 bytes). <b>Sensitive</b> — like
     * {@link #privateKeySeedBytes()}, only the grant orchestration uses it to ECDH-unwrap a zone
     * master received from a holder. Never log it or put it on the wire.
     */
    public synchronized byte[] x25519PrivateKeyPkcs8() {
        ensureX25519();
        return x25519PrivPkcs8.clone();
    }

    /** Lazy-create the X25519 grant keypair if missing and persist immediately (mirrors {@link #ensureNkey()}). */
    private void ensureX25519() {
        if (x25519PrivPkcs8 != null && x25519PubSpki != null) return;
        try {
            var xkp = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            this.x25519PrivPkcs8 = xkp.getPrivate().getEncoded();
            this.x25519PubSpki = xkp.getPublic().getEncoded();
            log.info("Generated X25519 grant keypair for node {} on first use; persisting", nodeId);
            if (identityFilePath != null) save(identityFilePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to lazy-generate X25519 grant keypair", e);
        }
    }

    /**
     * Derive a {@code did:key:z6Mk…} identifier for this node from its Ed25519
     * public key, matching the W3C did:key spec used by {@code DidKey} in core.
     *
     * <p>This is the canonical DID for the node's "own" identity (the one
     * signing Between envelopes). Companion DIDs are separate records.
     */
    public String did() {
        // Inline the same multicodec+base58btc encoding as core.DidKey.fromPublicKey
        // to avoid a between→core dependency. Format: did:key:z + base58btc([0xed,0x01] + raw32)
        var raw32 = new byte[32];
        var encoded = publicKey.getEncoded();   // 44-byte SPKI
        System.arraycopy(encoded, encoded.length - 32, raw32, 0, 32);
        var multi = new byte[34];
        multi[0] = (byte) 0xed; multi[1] = 0x01;
        System.arraycopy(raw32, 0, multi, 2, 32);
        return "did:key:z" + base58Encode(multi);
    }

    private static String base58Encode(byte[] input) {
        var alpha = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) leadingZeros++;
        var number = input.clone();
        var encoded = new char[number.length * 2];
        int outputStart = encoded.length;
        for (int inputStart = leadingZeros; inputStart < number.length; ) {
            int remainder = 0;
            for (int i = inputStart; i < number.length; i++) {
                int digit = number[i] & 0xFF;
                int temp = remainder * 256 + digit;
                number[i] = (byte) (temp / 58);
                remainder = temp % 58;
            }
            encoded[--outputStart] = alpha.charAt(remainder);
            if (number[inputStart] == 0) inputStart++;
        }
        while (outputStart < encoded.length && encoded[outputStart] == alpha.charAt(0)) outputStart++;
        while (--leadingZeros >= 0) encoded[--outputStart] = alpha.charAt(0);
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    /**
     * Sign data with this node's private key.
     * @return Ed25519 signature (64 bytes)
     */
    public byte[] sign(byte[] data) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    /**
     * NATS NKey public key for relay auth — the 56-character {@code U...} string the
     * relay registers in {@code relay.conf}. Idempotent; safe to call repeatedly.
     * Lazy-creates the seed if absent (e.g. loading an older identity file) and
     * re-saves the file on first generation.
     *
     * <p>This is the *public* identity sent to the relay during {@code /register-nkey}.
     * The corresponding seed never leaves the node.</p>
     */
    public synchronized String nkeyPublicKey() {
        ensureNkey();
        try {
            var nkey = NKey.fromSeed(nkeySeed);
            return new String(nkey.getPublicKey());
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive NKey public key", e);
        }
    }

    /**
     * / R2.1 — the canonical {@code did:key:z…}
     * derived <strong>from the relay NKey</strong> (NOT from {@link #did()}'s
     * Between-protocol key). This is the DID the relay stamps on this node's
     * registration ({@code nkey_to_did}) and the one against which the signed
     * {@code /admin} + {@code claim-owner} surfaces verify signatures
     * (signatures made with {@link #nkeyAuthHandler()}). Use THIS as the acting
     * DID for any relay admin/claim call — the NKey is what authenticates.
     *
     * <p>Mirrors the relay's {@code nkey_to_did}: base32-decode the {@code U…}
     * pubkey → strip the 1-byte prefix + 2-byte CRC16 trailer → bytes[1:33] →
     * {@code did:key:z + base58btc([0xed,0x01] + raw32)}.</p>
     */
    public synchronized String nkeyDid() {
        var pubkey = nkeyPublicKey();
        var decoded = base32Decode(pubkey);
        if (decoded.length != 35) {
            throw new IllegalStateException("NKey pubkey is not 35 bytes after base32 decode");
        }
        var raw32 = new byte[32];
        System.arraycopy(decoded, 1, raw32, 0, 32);  // [1:33]
        var multi = new byte[34];
        multi[0] = (byte) 0xed; multi[1] = 0x01;
        System.arraycopy(raw32, 0, multi, 2, 32);
        return "did:key:z" + base58Encode(multi);
    }

    /** RFC 4648 base32 (no padding) decode — NATS NKey alphabet is standard base32. */
    private static byte[] base32Decode(String s) {
        var alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var clean = s.trim().replace("=", "");
        int buffer = 0, bitsLeft = 0;
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < clean.length(); i++) {
            int val = alpha.indexOf(Character.toUpperCase(clean.charAt(i)));
            if (val < 0) throw new IllegalArgumentException("invalid base32 char: " + clean.charAt(i));
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /**
     * NATS auth handler implementing the NKey challenge-response flow.
     * Returns a fresh handler per call — the AuthHandler is stateless beyond holding
     * a reference to the seed-loaded NKey. Used by {@link RelayBridge} when present;
     * falls back to user/password auth when this NodeIdentity isn't wired (legacy mode).
     *
     * <p>: this is the wire-side adapter from per-node identity
     * to NATS authentication. The drift class — shared password rotation between
     * client env and relay.conf — is eliminated because there is no shared secret;
     * only the public key is exchanged at registration time, and the relay verifies
     * each connection's nonce signature against the registered public key.</p>
     */
    public synchronized AuthHandler nkeyAuthHandler() {
        ensureNkey();
        final char[] seed = nkeySeed.clone();  // defensive: AuthHandler must hold its own copy
        return new AuthHandler() {
            @Override
            public byte[] sign(byte[] nonce) {
                try {
                    var nk = NKey.fromSeed(seed);
                    return nk.sign(nonce);
                } catch (Exception e) {
                    throw new RuntimeException("NKey sign failed", e);
                }
            }
            @Override
            public char[] getID() {
                try {
                    return NKey.fromSeed(seed).getPublicKey();
                } catch (Exception e) {
                    throw new RuntimeException("NKey getID failed", e);
                }
            }
            @Override
            public char[] getJWT() {
                return null;  // NKey-only; no JWT scope used by wyrdsekai relay
            }
        };
    }

    /**
     * Lazy-create the NKey seed if missing and persist immediately. Called by both
     * public NKey API methods so callers never see a null seed. First call after
     * loading a pre-NKey identity file also re-saves the file with the new seed
     * encrypted alongside the PKCS8 key.
     */
    private void ensureNkey() {
        if (nkeySeed != null) return;
        try {
            var nkey = NKey.createUser(new SecureRandom());
            this.nkeySeed = nkey.getSeed();
            log.info("Generated relay NKey for node {} on first use; persisting", nodeId);
            if (identityFilePath != null) {
                save(identityFilePath);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to lazy-generate NKey seed for relay auth", e);
        }
    }

    /**
     * Verify a signature against a peer's public key.
     * @param peerPublicKey X.509-encoded Ed25519 public key
     */
    public static boolean verify(byte[] data, byte[] signature, byte[] peerPublicKey) {
        try {
            var keyFactory = KeyFactory.getInstance("Ed25519");
            var pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(peerPublicKey));

            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // --- Crypto helpers ---

    private static byte[] deriveKey(byte[] salt, int iterations) {
        try {
            var machineId = getMachineIdentifier();
            var spec = new PBEKeySpec(machineId.toCharArray(), salt, iterations, AES_KEY_BITS);
            var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    private static byte[] encrypt(byte[] plaintext, byte[] key) {
        try {
            var iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext);

            // Prepend IV to ciphertext
            var result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private static byte[] decrypt(byte[] ivAndCiphertext, byte[] key) {
        try {
            var iv = new byte[GCM_IV_BYTES];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_BYTES);

            var ciphertext = new byte[ivAndCiphertext.length - GCM_IV_BYTES];
            System.arraycopy(ivAndCiphertext, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — identity file may be from another machine", e);
        }
    }

    private static String getMachineIdentifier() {
        // Windows: MachineGuid from registry
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                var proc = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                    "/v", "MachineGuid")
                    .redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() == 0) {
                    var matcher = Pattern.compile(
                        "MachineGuid\\s+REG_SZ\\s+(\\S+)").matcher(output);
                    if (matcher.find()) {
                        return matcher.group(1) + ":"
                            + InetAddress.getLocalHost().getHostName();
                    }
                }
            } catch (Exception ignored) {}
        }

        // Linux: /etc/machine-id
        try {
            var machineIdFile = Path.of("/etc/machine-id");
            if (Files.isReadable(machineIdFile)) {
                return Files.readString(machineIdFile).trim() + ":"
                    + InetAddress.getLocalHost().getHostName();
            }
        } catch (Exception ignored) {}

        // macOS: IOPlatformSerialNumber (via ioreg)
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try {
                var proc = new ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    .redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes());
                if (proc.waitFor() == 0) {
                    var matcher = Pattern.compile(
                        "\"IOPlatformSerialNumber\"\\s*=\\s*\"([^\"]+)\"").matcher(output);
                    if (matcher.find()) {
                        return matcher.group(1) + ":"
                            + InetAddress.getLocalHost().getHostName();
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback: hostname + user.name + os.arch
        return System.getProperty("user.name", "unknown") + ":"
            + System.getProperty("os.arch", "unknown") + ":"
            + getHostname();
    }

    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static String fingerprint(byte[] publicKey) {
        var b64 = Base64.getEncoder().encodeToString(publicKey);
        return b64.substring(0, Math.min(12, b64.length())) + "...";
    }
}
