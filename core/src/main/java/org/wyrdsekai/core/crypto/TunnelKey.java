package org.wyrdsekai.core.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * The home's long-term X25519 key for the sealed tunnel (, W3).
 *
 * <p>Made once, kept in {@code <data>/tunnel-key.json} readable by the node's user only. Its public
 * half goes into every pairing invite as {@code zk}, so a phone can tell its real home from a relay
 * pretending to be it. Losing the file means phones pair again; it is never sent anywhere.</p>
 */
public final class TunnelKey {

    private static final Logger log = LoggerFactory.getLogger(TunnelKey.class);
    public static final String FILE = "tunnel-key.json";
    private static final HexFormat HEX = HexFormat.of();

    private TunnelKey() {}

    /**
     * The folder this home's key lives in: {@code WYRDSEKAI_DATA_DIR}, else {@code ~/.wyrdsekai}.
     * Every invite reads the public half from here, so the tunnel and the sealed requests must
     * load the same file (they once used the profile's data folder, which could differ).
     */
    public static Path homeDataDir() {
        var env = System.getenv("WYRDSEKAI_DATA_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai");
    }

    public static SealedTunnel.KeyPair forThisHome() throws IOException, GeneralSecurityException {
        return loadOrCreate(homeDataDir());
    }

    public static SealedTunnel.KeyPair loadOrCreate(Path dataDir) throws IOException, GeneralSecurityException {
        if (dataDir == null) return SealedTunnel.generate();
        var f = dataDir.resolve(FILE);
        if (Files.isRegularFile(f)) {
            var m = new ObjectMapper().readTree(f.toFile());
            var kp = new SealedTunnel.KeyPair(HEX.parseHex(m.get("priv").asText()), HEX.parseHex(m.get("pub").asText()));
            if (kp.priv().length != SealedTunnel.KEY_LEN || kp.pub().length != SealedTunnel.KEY_LEN) {
                throw new GeneralSecurityException(f + " does not hold a 32-byte X25519 key pair");
            }
            return kp;
        }
        var kp = SealedTunnel.generate();
        Files.createDirectories(dataDir);
        var tmp = dataDir.resolve(FILE + ".tmp");
        Files.writeString(tmp, new ObjectMapper().writeValueAsString(Map.of(
            "v", 1, "alg", "X25519", "priv", HEX.formatHex(kp.priv()), "pub", HEX.formatHex(kp.pub()))));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the data folder's own ACL is what protects it.
        }
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // Made by a tool run as root: it stays the data folder owner's, so the node can read it.
        try {
            Files.setOwner(f, Files.getOwner(dataDir));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("tunnel key owner left as is: {}", e.toString());
        }
        log.info("Made this home's tunnel key ({}); phones pair with its public half", f);
        return kp;
    }

    /** The public half as it travels in invites: base64url without padding. */
    public static String publicForInvite(SealedTunnel.KeyPair kp) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(kp.pub());
    }

    /** Reads a key sent as base64url or standard base64, with or without padding. */
    public static byte[] decodeKey(String s) {
        if (s == null) return null;
        var t = s.trim().replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += "=";
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
