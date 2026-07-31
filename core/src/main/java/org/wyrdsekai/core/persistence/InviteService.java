package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invite code management for household member onboarding.
 * Steward generates invite codes (6-word passphrases), new members redeem them.
 * Codes are single-use, expire after 24 hours by default.
 */
public final class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final long DEFAULT_EXPIRY_SECONDS = 24 * 3600; // 24 hours

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public record Invite(
        String id, String code, String intendedName, String role,
        String createdBy, Instant createdAt, Instant expiresAt,
        String consumedBy, Instant consumedAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
        public boolean isConsumed() {
            return consumedBy != null;
        }
        public boolean isValid() {
            return !isExpired() && !isConsumed();
        }
    }

    public InviteService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public InviteService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /**
     * Create an invite code for a new member.
     * @param intendedName the name the invite is for (informational)
     * @param role the role to assign when redeemed (member, guest, child)
     * @param createdBy the user ID of the steward creating the invite
     * @return the created invite with its passphrase code
     */
    public Invite createInvite(String intendedName, String role, String createdBy) {
        return createInvite(intendedName, role, createdBy, DEFAULT_EXPIRY_SECONDS);
    }

    /**
     * Mint a one-time steward-bootstrap invite for fresh installs.
     *
     * <p> phase 2: on a fresh install, no steward
     * exists, so {@link #createInvite} can't be called (no {@code createdBy}
     * to reference). This method bypasses the steward-FK requirement by
     * inserting {@code created_by = NULL}, but ONLY when no users exist —
     * the moment any user is registered, this method refuses.</p>
     *
     * <p>Intended call site: installer postinst running as root immediately
     * after the schema is created. The minted token is written to
     * {@code /etc/wyrdsekai/steward-bootstrap.invite} (mode 0600) and echoed
     * to the install log. The user redeems it via
     * {@code ssh <intendedName>@host} with the token as password.</p>
     *
     * @param intendedName SSH username the operator will use to connect
     *                     (typically "steward" or a name the operator picks).
     * @param expirySeconds invite TTL.
     * @throws IllegalStateException if any user already exists.
     */
    public Invite createBootstrapInvite(String intendedName, long expirySeconds) {
        // Self-gate: this method only works on fresh installs.
        try (var conn = getConnection();
             var check = conn.prepareStatement("SELECT 1 FROM users LIMIT 1")) {
            var rs = check.executeQuery();
            if (rs.next()) {
                throw new IllegalStateException(
                    "Bootstrap invite refused — users already exist. "
                    + "Use 'wyrd invite create' from a steward session instead.");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check user count for bootstrap", e);
        }

        var id = UUID.randomUUID().toString();
        var code = generatePassphrase();
        var now = Instant.now();
        var expiresAt = now.plusSeconds(expirySeconds);

        try (var conn = getConnection()) {
            var sql = "INSERT INTO invites (id, code, intended_name, role, created_by, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, NULL, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, code);
                stmt.setString(3, intendedName);
                stmt.setString(4, "steward");
                stmt.setLong(5, now.getEpochSecond());
                stmt.setLong(6, expiresAt.getEpochSecond());
                stmt.executeUpdate();
            }
            log.info("Bootstrap invite created for steward '{}', expires {}",
                intendedName, expiresAt);
            return new Invite(id, code, intendedName, "steward", null,
                now, expiresAt, null, null);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create bootstrap invite", e);
        }
    }

    /**
     * Create an invite code with custom expiry.
     */
    public Invite createInvite(String intendedName, String role, String createdBy, long expirySeconds) {
        var id = UUID.randomUUID().toString();
        var code = generatePassphrase();
        var now = Instant.now();
        var expiresAt = now.plusSeconds(expirySeconds);
        var effectiveRole = role != null ? role : "member";

        try (var conn = getConnection()) {
            var sql = "INSERT INTO invites (id, code, intended_name, role, created_by, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, code);
                stmt.setString(3, intendedName);
                stmt.setString(4, effectiveRole);
                stmt.setString(5, createdBy);
                stmt.setLong(6, now.getEpochSecond());
                stmt.setLong(7, expiresAt.getEpochSecond());
                stmt.executeUpdate();
            }
            log.info("Invite created for '{}' (role={}) by {}, expires {}",
                intendedName, effectiveRole, createdBy, expiresAt);
            return new Invite(id, code, intendedName, effectiveRole, createdBy,
                now, expiresAt, null, null);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create invite", e);
        }
    }

    /**
     * Validate and consume an invite code. Returns the invite if valid.
     * The code is consumed atomically — cannot be reused.
     * @param code the passphrase code to redeem
     * @param consumedBy the user ID of the newly created account
     */
    public Optional<Invite> redeemInvite(String code, String consumedBy) {
        try (var conn = getConnection()) {
            // Find valid (not expired, not consumed) invite
            var sql = "SELECT id, code, intended_name, role, created_by, created_at, expires_at"
                + " FROM invites WHERE code = ? AND consumed_by IS NULL AND expires_at > "
                + dialect.currentEpoch();
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, code.trim().toLowerCase());
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                var invite = new Invite(
                    rs.getString("id"), rs.getString("code"),
                    rs.getString("intended_name"), rs.getString("role"),
                    rs.getString("created_by"),
                    Instant.ofEpochSecond(rs.getLong("created_at")),
                    Instant.ofEpochSecond(rs.getLong("expires_at")),
                    null, null
                );

                // Consume the invite atomically
                var consumeSql = "UPDATE invites SET consumed_by = ?, consumed_at = "
                    + dialect.currentEpoch()
                    + " WHERE id = ? AND consumed_by IS NULL";
                try (var consumeStmt = conn.prepareStatement(consumeSql)) {
                    consumeStmt.setString(1, consumedBy);
                    consumeStmt.setString(2, invite.id());
                    var rows = consumeStmt.executeUpdate();
                    if (rows == 0) {
                        // Race condition — someone else consumed it
                        return Optional.empty();
                    }
                }

                log.info("Invite {} redeemed by {} (intended for '{}')",
                    invite.id(), consumedBy, invite.intendedName());
                return Optional.of(new Invite(invite.id(), invite.code(),
                    invite.intendedName(), invite.role(), invite.createdBy(),
                    invite.createdAt(), invite.expiresAt(), consumedBy, Instant.now()));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to redeem invite", e);
        }
    }

    /**
     * #4 (2026-07-19 OSS hardening) — atomically CLAIM an invite BEFORE creating
     * the account, closing the peek→create→consume TOCTOU. A single UPDATE gated
     * on {@code consumed_by IS NULL} lets only ONE concurrent caller win; the
     * loser gets {@code empty} and must create no account (previously the lost
     * race still created an account, so a single-use — possibly steward-role —
     * invite could mint duplicate/elevated accounts). The winner then calls
     * {@link #rebindClaim} with the created user id, or {@link #releaseClaim} if
     * account creation fails (e.g. username already taken), restoring the invite.
     *
     * @param code       the invite code
     * @param claimToken an opaque, unique-per-attempt marker stored in consumed_by
     * @return the claimed invite (carrying its role), or empty if already
     *         consumed / expired / unknown
     */
    public Optional<Invite> claimInvite(String code, String claimToken) {
        try (var conn = getConnection()) {
            var norm = code.trim().toLowerCase();
            var updateSql = "UPDATE invites SET consumed_by = ?, consumed_at = "
                + dialect.currentEpoch()
                + " WHERE code = ? AND consumed_by IS NULL AND expires_at > "
                + dialect.currentEpoch();
            try (var up = conn.prepareStatement(updateSql)) {
                up.setString(1, claimToken);
                up.setString(2, norm);
                if (up.executeUpdate() == 0) return Optional.empty();
            }
            var sel = "SELECT id, code, intended_name, role, created_by, created_at, expires_at"
                + " FROM invites WHERE code = ? AND consumed_by = ?";
            try (var st = conn.prepareStatement(sel)) {
                st.setString(1, norm);
                st.setString(2, claimToken);
                var rs = st.executeQuery();
                if (!rs.next()) return Optional.empty();
                var inv = new Invite(
                    rs.getString("id"), rs.getString("code"),
                    rs.getString("intended_name"), rs.getString("role"),
                    rs.getString("created_by"),
                    Instant.ofEpochSecond(rs.getLong("created_at")),
                    Instant.ofEpochSecond(rs.getLong("expires_at")),
                    claimToken, Instant.now());
                log.info("Invite {} claimed (intended for '{}', role={})",
                    inv.id(), inv.intendedName(), inv.role());
                return Optional.of(inv);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim invite", e);
        }
    }

    /** Release a claim (account creation failed) — restores the invite to pending. */
    public void releaseClaim(String claimToken) {
        try (var conn = getConnection()) {
            var sql = "UPDATE invites SET consumed_by = NULL, consumed_at = NULL WHERE consumed_by = ?";
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, claimToken);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release invite claim", e);
        }
    }

    /** Rebind a successful claim's {@code consumed_by} to the real user id (audit). */
    public void rebindClaim(String claimToken, String userId) {
        try (var conn = getConnection()) {
            var sql = "UPDATE invites SET consumed_by = ? WHERE consumed_by = ?";
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, userId);
                st.setString(2, claimToken);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rebind invite claim", e);
        }
    }

    /**
     * List all invites (for steward's invitation scroll).
     */
    public List<Invite> listInvites() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, code, intended_name, role, created_by, created_at, expires_at,"
                + " consumed_by, consumed_at FROM invites ORDER BY created_at DESC";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var invites = new ArrayList<Invite>();
                while (rs.next()) {
                    invites.add(new Invite(
                        rs.getString("id"), rs.getString("code"),
                        rs.getString("intended_name"), rs.getString("role"),
                        rs.getString("created_by"),
                        Instant.ofEpochSecond(rs.getLong("created_at")),
                        Instant.ofEpochSecond(rs.getLong("expires_at")),
                        rs.getString("consumed_by"),
                        rs.getLong("consumed_at") > 0
                            ? Instant.ofEpochSecond(rs.getLong("consumed_at")) : null
                    ));
                }
                return invites;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list invites", e);
        }
    }

    /**
     * List only pending (valid, not consumed, not expired) invites.
     */
    public List<Invite> listPendingInvites() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, code, intended_name, role, created_by, created_at, expires_at"
                + " FROM invites WHERE consumed_by IS NULL AND expires_at > "
                + dialect.currentEpoch()
                + " ORDER BY created_at DESC";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var invites = new ArrayList<Invite>();
                while (rs.next()) {
                    invites.add(new Invite(
                        rs.getString("id"), rs.getString("code"),
                        rs.getString("intended_name"), rs.getString("role"),
                        rs.getString("created_by"),
                        Instant.ofEpochSecond(rs.getLong("created_at")),
                        Instant.ofEpochSecond(rs.getLong("expires_at")),
                        null, null
                    ));
                }
                return invites;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list pending invites", e);
        }
    }

    /**
     * Revoke a pending invite (delete it).
     */
    public boolean revokeInvite(String inviteId) {
        try (var conn = getConnection()) {
            var sql = "DELETE FROM invites WHERE id = ? AND consumed_by IS NULL";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, inviteId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Invite {} revoked", inviteId);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke invite", e);
        }
    }

    /**
     * Clean up expired invites.
     */
    public int purgeExpired() {
        try (var conn = getConnection()) {
            var sql = "DELETE FROM invites WHERE expires_at <= " + dialect.currentEpoch()
                + " AND consumed_by IS NULL";
            try (var stmt = conn.prepareStatement(sql)) {
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.debug("Purged {} expired invites", rows);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge expired invites", e);
        }
    }

    /**
     * Replicate an invite from another node (insert directly, no generation).
     */
    public void replicateInvite(String id, String code, String intendedName,
                                 String role, String createdBy, Instant expiresAt) {
        try (var conn = getConnection()) {
            var sql = "INSERT INTO invites (id, code, intended_name, role, created_by, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, code);
                stmt.setString(3, intendedName);
                stmt.setString(4, role);
                stmt.setString(5, createdBy);
                stmt.setLong(6, Instant.now().getEpochSecond());
                stmt.setLong(7, expiresAt.getEpochSecond());
                stmt.executeUpdate();
            }
            log.info("Replicated invite for '{}' from remote node", intendedName);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                log.debug("Invite {} already exists locally, skipping", id);
                return;
            }
            log.warn("Failed to replicate invite: {}", e.getMessage());
        }
    }

    // ── Passphrase generation ──

    /**
     * Generate a 6-word passphrase from a curated word list.
     * ~77 bits of entropy (256^6 wouldn't be memorable; 256 words × 6 = enough for 24h codes).
     */
    static String generatePassphrase() {
        var rng = new SecureRandom();
        var words = new String[6];
        for (int i = 0; i < 6; i++) {
            words[i] = WORD_LIST[rng.nextInt(WORD_LIST.length)];
        }
        return String.join(" ", words);
    }

    /**
     * 256-word list of evocative, easy-to-spell English words.
     * Chosen to be unambiguous when spoken aloud and thematically appropriate.
     */
    /** Get the word list (used by AuthService for recovery key generation). */
    public static String[] getWordList() { return WORD_LIST; }

    private static final String[] WORD_LIST = {
        "amber", "anchor", "anvil", "arrow", "ash", "aurora", "axe", "basin",
        "beacon", "bell", "birch", "blade", "bloom", "bolt", "bone", "bridge",
        "bronze", "brook", "cairn", "candle", "canyon", "cedar", "chain", "chalk",
        "charm", "cherry", "cinder", "citadel", "clay", "cliff", "cloak", "cloud",
        "cobalt", "coin", "coral", "crane", "crest", "crow", "crystal", "cypress",
        "dagger", "dawn", "delta", "diamond", "dock", "dome", "door", "dove",
        "draft", "dream", "drift", "drum", "dusk", "dust", "eagle", "earth",
        "echo", "edge", "elm", "ember", "fable", "falcon", "fern", "field",
        "fire", "flame", "flint", "flood", "flower", "fog", "forge", "fort",
        "fossil", "frost", "gate", "geyser", "glacier", "glass", "glen", "globe",
        "gold", "granite", "grove", "gust", "harbor", "harp", "hawk", "hazel",
        "hearth", "hedge", "helm", "heron", "hill", "hollow", "honey", "horn",
        "ice", "ink", "iron", "island", "ivory", "ivy", "jade", "jasper",
        "jewel", "juniper", "key", "kindle", "knot", "lake", "lamp", "lark",
        "laurel", "lava", "leaf", "ledge", "light", "lily", "linen", "lodge",
        "lotus", "lynx", "maple", "marble", "marsh", "mask", "mast", "meadow",
        "mesa", "mist", "moon", "moss", "moth", "myrtle", "needle", "nest",
        "night", "north", "oak", "oar", "opal", "orbit", "orchid", "otter",
        "owl", "palm", "path", "pearl", "pebble", "pine", "plum", "pond",
        "quartz", "quill", "rain", "relay-node", "reef", "ridge", "ring", "river",
        "robin", "rock", "root", "rose", "ruby", "sage", "sand", "satin",
        "scale", "seal", "seed", "shade", "shell", "shield", "shore", "silk",
        "silver", "slate", "smoke", "snow", "solar", "south", "spark", "spire",
        "spring", "star", "steel", "stone", "storm", "stream", "summit", "sun",
        "swift", "thorn", "thunder", "tide", "timber", "torch", "tower", "trail",
        "tree", "tulip", "tusk", "valley", "veil", "velvet", "vine", "violet",
        "viper", "void", "wave", "well", "west", "whale", "wheat", "whisper",
        "willow", "wind", "wing", "winter", "wolf", "wood", "wren", "yarn",
        "yew", "zenith", "zinc", "agate", "basalt", "copper", "garnet", "lapis",
        "mica", "obsidian", "pewter", "prism", "quince", "sable", "sapphire", "scarlet",
        "sienna", "sterling", "sulfur", "teal", "topaz", "umber", "verdigris", "wisteria",
        "acorn", "birdsong", "compass", "driftwood", "feather", "fountain", "harvest", "heather",
        "lantern", "magnet", "mirror", "mosaic", "orchard", "pendulum", "plume", "rampart"
    };

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
