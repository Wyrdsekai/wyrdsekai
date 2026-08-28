package org.wyrdsekai.server.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * A node-local token that lets the machine's operator use steward commands without an
 * interactive login.
 *
 * <p>Steward-gated CLI verbs ({@code wyrd consent}, {@code wyrd forge}, …) required a
 * session token obtained by {@code wyrd login}. On a household node nobody does that:
 * the steward operates the box over SSH as the service user and talks to the world
 * through its own door. The result was a feature that shipped, passed its tests, and
 * had never once been reachable — the consent route had zero calls in the four days
 * after it landed (found 2026-08-18). The gate was correct and the path a person walks
 * was untested.
 *
 * <p>The boundary this rests on is the filesystem: the token lives in the data
 * directory at mode 0600 owned by the service user. Anyone who can read it can already
 * read {@code world.db} and stop the service, so it grants no capability that wasn't
 * already held — it stops treating a local operator as a remote client. Presentation is
 * still required and still checked from loopback only, which is stricter than the
 * existing admin-route convention of trusting bare loopback.
 */
public final class OperatorToken {

    private static final Logger log = LoggerFactory.getLogger(OperatorToken.class);
    private static final String FILE_NAME = "operator.token";

    private static volatile String token;

    private OperatorToken() {}

    /**
     * Create the token if absent and load it. Safe to call repeatedly; a token that
     * already exists is reused so operator shells keep working across restarts.
     */
    public static synchronized void ensure(Path dataDir) {
        if (dataDir == null) return;
        try {
            var file = dataDir.resolve(FILE_NAME);
            if (Files.exists(file)) {
                token = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!token.isBlank()) return;
            }
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            var fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.createDirectories(dataDir);
            Files.writeString(file, fresh, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException e) {
                log.warn("Operator token created but POSIX permissions are unavailable on "
                    + "this filesystem — restrict {} by hand", file);
            }
            token = fresh;
            log.info("Operator token ready at {} (0600) — local steward commands work "
                + "without an interactive login", file);
        } catch (Exception e) {
            log.warn("Could not establish an operator token: {} — steward CLI commands "
                + "will require `wyrd login`", e.toString());
        }
    }

    /** True when the presented value is this node's operator token. */
    public static boolean matches(String candidate) {
        var current = token;
        if (current == null || current.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            current.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** Test seam. */
    static void setForTesting(String value) {
        token = value;
    }
}
