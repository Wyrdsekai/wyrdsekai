package org.wyrdsekai.server.ssh;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;

import java.net.InetSocketAddress;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.security.LoginRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.soul.BondRitual;
import org.wyrdsekai.server.session.ClientConnectionRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SSH adapter for Wyrdsekai.
 * Provides secure remote access to the MUD via standard SSH clients.
 * <p>
 * Uses Apache SSHD with:
 * <ul>
 *   <li>Password authentication via {@link AuthService#login(String, String)}</li>
 *   <li>Public key authentication via {@code ~/.wyrdsekai/authorized_keys}</li>
 *   <li>Host key persisted at {@code ~/.wyrdsekai/ssh_host_key} (survives restarts)</li>
 * </ul>
 * <p>
 * Each SSH session gets a {@link WyrdShellCommand} that bridges to the actor system,
 * reusing the same session logic as the Telnet adapter.
 */
public class SshAdapter {

    private static final Logger log = LoggerFactory.getLogger(SshAdapter.class);

    /**
     * Attached to an SSH session by the password authenticator when the user
     * authenticated via an invite token (password = invite code). The shell
     * reads this and runs the create-and-redeem flow instead of the normal
     * login. phase 2.
     */
    public static final AttributeRepository.AttributeKey<InviteService.Invite> INVITE_AUTH_KEY =
        new AttributeRepository.AttributeKey<>();

    /**
     * Stashed by the publickey authenticator on every connection attempt
     * (whether the key was accepted or not). The shell reads it during invite
     * redemption to auto-register the client's pubkey for keyless future
     * logins. F4 phase 2.
     */
    public static final AttributeRepository.AttributeKey<PublicKey> OFFERED_PUBKEY_KEY =
        new AttributeRepository.AttributeKey<>();

    /**
     * Set by the publickey authenticator when the offered key resolved to an
     * owning account (per-account key binding). The shell logs in AS this
     * user id — NOT the typed ssh username — closing the impersonation hole.
     */
    public static final AttributeRepository.AttributeKey<String> PUBKEY_OWNER_USERID =
        new AttributeRepository.AttributeKey<>();

    private volatile SshServer sshd;

    /**
     * Start the SSH server on the given port.
     *
     * @param port             TCP port to listen on (default: 7022)
     * @param system           Pekko actor system
     * @param authService      for password authentication
     * @param wardService      for room permission checks
     * @param inventoryService for player inventory
     */
    private InviteService inviteService; // nullable
    // #12 (2026-07-19 OSS hardening) — brute-force throttle for SSH password auth,
    // keyed per source IP and per targeted account.
    private final LoginRateLimiter loginLimiter = new LoginRateLimiter();
    /** Human phrasing of a remaining lockout, for the operator-facing log. */
    private static String lockoutMessage(long waitMs) {
        long waitMin = Math.max(1, (waitMs + 59_999) / 60_000);
        return "about " + waitMin + " more minute" + (waitMin == 1 ? "" : "s") + " to run";
    }
    // Cross-zone transit plumbing — optional, set via setTransitContext.
    private volatile String localZoneId;
    private volatile RelaySessionTransport relayTransport;
    private volatile ClientConnectionRegistry connectionRegistry;

    // Scripted-item plumbing — optional, set via setScriptContext.
    private volatile HomeClient homeClient;
    private volatile FederationService federationService;
    private volatile BondRitual bondRitual;

    /**
     * Wire cross-zone transit dependencies so SSH clients can run
     * {@code say travel <zone>}. Without this, transit commands from SSH
     * fail gracefully (federation unavailable).
     */
    public void setTransitContext(String localZoneId,
                                  RelaySessionTransport relayTransport,
                                  ClientConnectionRegistry registry) {
        this.localZoneId = localZoneId;
        this.relayTransport = relayTransport;
        this.connectionRegistry = registry;
    }

    /** #12 — best-effort source IP for rate-limit keying; "unknown" if unavailable. */
    private static String clientIp(ServerSession session) {
        try {
            var addr = session == null ? null : session.getClientAddress();
            if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
            return addr == null ? "unknown" : addr.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Wire scripted-item invocation context so SSH clients can invoke pinned
     * scripted Study furnishings (Embers, Board, Quill, …) via
     * {@code use <name>} / {@code examine <name>} / {@code read <name>}.
     */
    public void setScriptContext(HomeClient homeClient,
                                 FederationService federationService,
                                 BondRitual bondRitual) {
        this.homeClient = homeClient;
        this.federationService = federationService;
        this.bondRitual = bondRitual;
    }

    public void start(int port, ActorSystem<?> system,
                      AuthService authService, WardService wardService,
                      InventoryService inventoryService) {
        start(port, system, authService, null, wardService, inventoryService);
    }

    public void start(int port, ActorSystem<?> system,
                      AuthService authService,
                      InviteService inviteService,
                      WardService wardService,
                      InventoryService inventoryService) {
        this.inviteService = inviteService;
        try {
            sshd = SshServer.setUpDefaultServer();
            sshd.setPort(port);

            // Host key — persists across restarts so clients don't get TOFU warnings
            var hostKeyPath = SystemPaths.dataDir().resolve("ssh_host_key");
            Files.createDirectories(hostKeyPath.getParent());
            sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));

            // Password authentication. phase 2:
            // tries existing-user login first, then invite-token-as-password
            // for the canonical join path. The user does
            //   ssh <intendedName>@host -p 7022
            // with the invite code as the password. If the code matches a
            // pending invite for that intendedName, the session is allowed
            // and INVITE_AUTH_KEY is attached so WyrdShellCommand can run
            // the create-and-redeem flow.
            final var inviteSvc = inviteService;
            sshd.setPasswordAuthenticator((username, password, session) -> {
                // #12 (2026-07-19) — brute-force throttle, per source IP + per
                // targeted account. A locked key is denied without touching bcrypt.
                var ipKey = "ip:" + clientIp(session);
                var acctKey = "acct:" + (username == null ? "" : username.toLowerCase());
                if (loginLimiter.anyLocked(ipKey, acctKey)) {
                    log.warn("SSH auth throttled for '{}' from {} — too many recent failures",
                        username, clientIp(session));
                    // TELL the user. A throttled connection that merely closes
                    // is indistinguishable from a broken server — one mistyped
                    // password then makes every later session look like "the
                    // SSH surface renders nothing", which is exactly the false
                    // bug report this cost us (2026-07-25). SSH_MSG_USERAUTH_BANNER
                    // is delivered by the client before the auth prompt.
                    // NOTE: the client sees only a closed connection. SSHD emits
                    // the welcome banner before authentication runs, so a lockout
                    // discovered DURING auth cannot be explained over the wire
                    // (tried and failed, 2026-07-25 — see KNOWN_ISSUES). Until a
                    // keyboard-interactive path carries it, this log line is the
                    // only explanation, and the docs warn about the symptom:
                    // one mistyped password makes every later session look like
                    // "the SSH surface renders nothing".
                    log.warn("SSH lockout for '{}' has {}", username,
                        lockoutMessage(loginLimiter.lockRemainingMs(ipKey, acctKey)));
                    return false;
                }
                var result = authService.login(username, password);
                if (result.isPresent()) {
                    loginLimiter.recordSuccessAll(ipKey, acctKey);
                    return true;
                }
                if (inviteSvc != null && username != null && password != null) {
                    var trimmed = password.trim().toLowerCase();
                    var match = inviteSvc.listPendingInvites().stream()
                        .filter(i -> i.code().equals(trimmed))
                        .filter(i -> username.equalsIgnoreCase(i.intendedName()))
                        .findFirst();
                    if (match.isPresent()) {
                        session.setAttribute(INVITE_AUTH_KEY, match.get());
                        loginLimiter.recordSuccessAll(ipKey, acctKey);
                        log.info("SSH invite-token auth: '{}' (role={}, invite={})",
                            username, match.get().role(), match.get().id());
                        return true;
                    }
                }
                loginLimiter.recordFailureAll(ipKey, acctKey);
                return false;
            });

            // Public key authentication — bound to the OWNING account, resolved
            // LIVE per connection.
            //
            // SECURITY (fixed 2026-07-03): the old model was a global,
            // key-ONLY authorized_keys accept-list, loaded ONCE at start. That
            // meant (a) any registered key could log in as ANY username —
            // `ssh steward@host` with a member's key impersonated the steward —
            // and (b) a newly added key needed a service restart. Now:
            //   1. resolve the offered key to its owner via authService (a live
            //      DB lookup → no restart, and the shell logs in AS that owner,
            //      not the typed username);
            //   2. the global authorized_keys file is honored ONLY while no
            //      account exists yet (the pre-place-your-key first-steward
            //      bootstrap), re-read live so it too needs no restart. Once any
            //      account exists the global file is ignored.
            // The offered key is ALWAYS stashed so the shell can bind it to the
            // account during bootstrap / invite redemption.
            sshd.setPublickeyAuthenticator((username, key, session) -> {
                session.setAttribute(OFFERED_PUBKEY_KEY, key);
                var keyLine = sshKeyLine(key);
                if (keyLine != null) {
                    // #17 (2026-07-19) — a key_line may now be bound to more than
                    // one account (composite PK); the handshake proved possession,
                    // so we authenticate the account whose username matches the
                    // one requested. A key still authenticates ITS OWN account
                    // only (2026-07-18): `ssh alice@host` with the steward's key
                    // won't land in the steward account.
                    var owners = authService.findUsersBySshKey(keyLine);
                    if (!owners.isEmpty()) {
                        for (var owner : owners) {
                            if (pubkeyUsernameMatches(username, owner)) {
                                session.setAttribute(PUBKEY_OWNER_USERID, owner.id());
                                return true;
                            }
                        }
                        log.warn("SSH pubkey is bound to {} account(s) but none match "
                            + "requested username '{}' — refusing (a key authenticates "
                            + "only its own account)", owners.size(), username);
                        return false;
                    }
                }
                // First-user bootstrap only — pre-placed key creates the steward.
                if (authService.isFirstUser()) {
                    return loadAuthorizedKeys().stream().anyMatch(ak -> ak.equals(key));
                }
                return false;
            });
            log.info("SSH pubkey auth enabled (per-account keys, live)");

            // Shell factory — each connection gets a WyrdShellCommand.
            // Transit context is injected per-instance so federation updates
            // (e.g. relay reconnect) propagate to newly-minted sessions.
            sshd.setShellFactory(channel -> {
                var cmd = new WyrdShellCommand(system, authService, inviteService,
                    wardService, inventoryService);
                if (localZoneId != null || relayTransport != null || connectionRegistry != null) {
                    cmd.setTransitContext(localZoneId, relayTransport, connectionRegistry);
                }
                if (homeClient != null || federationService != null || bondRitual != null) {
                    cmd.setScriptContext(homeClient, federationService, bondRitual);
                }
                return cmd;
            });

            // Idle timeout: MINA defaults to 10 minutes, which kills a
            // contemplative companion session mid-thought and disconnects with
            // SSH2_DISCONNECT_PROTOCOL_ERROR — which OpenSSH renders as the
            // alarming "Protocol error or corrupt packet" (second-node 2026-07-08: not
            // corruption, just an idle kick). Give a generous window and send
            // server heartbeats so idle-but-alive sessions (and NAT/relay hops)
            // stay up. Override with WYRDSEKAI_SSH_IDLE_MINUTES (0 = never idle-out).
            long idleMin = 120;
            try {
                idleMin = Long.parseLong(System.getenv()
                    .getOrDefault("WYRDSEKAI_SSH_IDLE_MINUTES", "120"));
            } catch (NumberFormatException ignored) { /* keep default */ }
            idleMin = Math.max(0, idleMin);
            CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ofMinutes(idleMin));
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(sshd, Duration.ofMinutes(idleMin + 5));
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(sshd, Duration.ofSeconds(60));

            sshd.start();
            log.info("SSH adapter listening on port {} (idle timeout {}m, heartbeat 60s)", port, idleMin);

        } catch (IOException e) {
            log.error("Failed to start SSH adapter on port {}: {}", port, e.getMessage());
        }
    }

    /**
     * Stop the SSH server gracefully.
     */
    public void stop() {
        if (sshd != null) {
            try {
                sshd.stop();
            } catch (IOException e) {
                log.warn("Error stopping SSH adapter: {}", e.getMessage());
            }
        }
        log.info("SSH adapter stopped");
    }

    /**
     * Append a public key to {@code ~/.wyrdsekai/authorized_keys} with an
     * OpenSSH-formatted line and an identifying comment. Idempotent — if the
     * exact key is already present, this is a no-op.
     *
     * <p>Used by {@link WyrdShellCommand} at invite-redemption time and by
     * the first-steward bootstrap flow, so that subsequent logins for the
     * same user are keyless.</p>
     *
     * <p>Returns true if a new entry was written; false if it was already
     * present or could not be encoded.</p>
     */
    /**
     * Canonical {@code "<type> <base64>"} form of a public key (no comment) —
     * the opaque identity used to bind a key to an account in
     * {@link AuthService#addSshKey} / {@link AuthService#findUserBySshKey}.
     * Returns null if the key can't be encoded.
     */
    /**
     * Does the SSH-requested {@code username} name the account this key is
     * bound to? Case-insensitive on the username; the account id is accepted
     * too (scripted clients that address accounts by id). Null/blank requested
     * names never match — identity is asserted, not inferred from the key.
     */
    static boolean pubkeyUsernameMatches(String requested, AuthService.User owner) {
        if (requested == null || requested.isBlank() || owner == null) return false;
        var r = requested.trim();
        return r.equalsIgnoreCase(owner.username()) || r.equals(owner.id());
    }

    public static String sshKeyLine(PublicKey key) {
        if (key == null) return null;
        try {
            var sb = new StringBuilder();
            PublicKeyEntry.appendPublicKeyEntry(sb, key);
            var parts = sb.toString().trim().split("\\s+");
            return parts.length >= 2 ? parts[0] + " " + parts[1] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize a pasted/loaded OpenSSH key line ({@code "<type> <base64> [comment]"})
     * to the canonical {@code "<type> <base64>"} identity used to bind keys —
     * the SAME form {@link #sshKeyLine(PublicKey)} produces for that key, so a
     * key added via {@code wyrd key add} matches what the authenticator computes.
     * Validates the type + base64; returns null on anything malformed.
     */
    public static String sshKeyLineFromOpenSsh(String line) {
        if (line == null || line.isBlank()) return null;
        var parts = line.trim().split("\\s+");
        if (parts.length < 2) return null;
        var type = parts[0];
        if (!type.equals("ssh-ed25519") && !type.equals("ssh-rsa") && !type.startsWith("ecdsa-sha2-")) {
            return null;
        }
        try {
            Base64.getDecoder().decode(parts[1]);
        } catch (Exception e) {
            return null;
        }
        return type + " " + parts[1];
    }

    public static boolean appendAuthorizedKey(PublicKey key, String comment) {
        if (key == null) return false;
        try {
            var line = new StringBuilder();
            PublicKeyEntry.appendPublicKeyEntry(line, key);
            if (comment != null && !comment.isBlank()) {
                line.append(" ").append(comment.trim());
            }
            line.append('\n');
            var path = SystemPaths.dataDir().resolve("authorized_keys");
            Files.createDirectories(path.getParent());
            // Idempotency check: skip if any existing line matches the key
            // portion (everything before any trailing comment).
            if (Files.exists(path)) {
                var keyPart = line.toString().split("\\s+", 3);
                var match = keyPart.length >= 2 ? keyPart[0] + " " + keyPart[1] : null;
                if (match != null) {
                    try (var br = Files.newBufferedReader(path)) {
                        String existing;
                        while ((existing = br.readLine()) != null) {
                            if (existing.startsWith(match)) {
                                return false; // already present
                            }
                        }
                    }
                }
            }
            Files.writeString(path, line.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            try { Files.setPosixFilePermissions(path,
                PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) { /* non-POSIX filesystem */ }
            log.info("authorized_keys: appended key for '{}'", comment);
            return true;
        } catch (Exception e) {
            log.warn("Failed to append authorized key: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Load public keys from {@code ~/.wyrdsekai/authorized_keys} (OpenSSH format).
     * Each line: {@code <key-type> <base64-key> [comment]}
     * Supports ssh-rsa, ssh-ed25519, ecdsa-sha2-*.
     *
     * @return list of parsed public keys, empty if file doesn't exist or has no valid keys
     */
    private List<PublicKey> loadAuthorizedKeys() {
        var path = SystemPaths.dataDir().resolve("authorized_keys");
        if (!Files.exists(path)) {
            log.debug("No authorized_keys file at {}", path);
            return List.of();
        }

        var keys = new ArrayList<PublicKey>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                var parts = line.split("\\s+");
                if (parts.length < 2) continue;

                var keyType = parts[0];
                var keyData = parts[1];

                try {
                    var decoded = Base64.getDecoder().decode(keyData);
                    var key = parseOpenSshPublicKey(keyType, decoded);
                    if (key != null) {
                        keys.add(key);
                    } else {
                        log.warn("authorized_keys line {}: unsupported key type '{}'", lineNum, keyType);
                    }
                } catch (Exception e) {
                    log.warn("authorized_keys line {}: failed to parse key: {}", lineNum, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read authorized_keys at {}: {}", path, e.getMessage());
        }
        return keys;
    }

    /**
     * Parse an OpenSSH public key blob into a Java PublicKey.
     * The blob format is: [4-byte length][key-type-string][key-specific-data]
     */
    private PublicKey parseOpenSshPublicKey(String keyType, byte[] blob) {
        try {
            return switch (keyType) {
                case "ssh-rsa" -> decodeRsaPublicKey(blob);
                case "ssh-ed25519" -> decodeEd25519PublicKey(blob);
                case String s when s.startsWith("ecdsa-sha2-") -> decodeEcPublicKey(blob, s);
                default -> null;
            };
        } catch (Exception e) {
            log.debug("Failed to decode {} key: {}", keyType, e.getMessage());
            return null;
        }
    }

    /** Decode an RSA public key from OpenSSH wire format. */
    private PublicKey decodeRsaPublicKey(byte[] blob) throws GeneralSecurityException {
        var buf = ByteBuffer.wrap(blob);
        // Skip key type string
        var typeLen = buf.getInt();
        buf.position(buf.position() + typeLen);
        // Read exponent
        var eLen = buf.getInt();
        var eBytes = new byte[eLen];
        buf.get(eBytes);
        // Read modulus
        var nLen = buf.getInt();
        var nBytes = new byte[nLen];
        buf.get(nBytes);

        var e = new BigInteger(1, eBytes);
        var n = new BigInteger(1, nBytes);
        var spec = new RSAPublicKeySpec(n, e);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /** Decode an Ed25519 public key from OpenSSH wire format. */
    private PublicKey decodeEd25519PublicKey(byte[] blob) throws GeneralSecurityException {
        var buf = ByteBuffer.wrap(blob);
        // Skip key type string
        var typeLen = buf.getInt();
        buf.position(buf.position() + typeLen);
        // Read key data (32 bytes)
        var keyLen = buf.getInt();
        var keyBytes = new byte[keyLen];
        buf.get(keyBytes);

        // Ed25519 raw public key → X.509 SubjectPublicKeyInfo encoding
        // OID 1.3.101.112 = Ed25519
        var x509Header = new byte[] {
            0x30, 0x2a, // SEQUENCE (42 bytes)
            0x30, 0x05, // SEQUENCE (5 bytes)
            0x06, 0x03, 0x2b, 0x65, 0x70, // OID 1.3.101.112
            0x03, 0x21, 0x00 // BIT STRING (33 bytes, no unused bits)
        };
        var x509Encoded = new byte[x509Header.length + keyBytes.length];
        System.arraycopy(x509Header, 0, x509Encoded, 0, x509Header.length);
        System.arraycopy(keyBytes, 0, x509Encoded, x509Header.length, keyBytes.length);

        var spec = new X509EncodedKeySpec(x509Encoded);
        return KeyFactory.getInstance("Ed25519").generatePublic(spec);
    }

    /** Decode an ECDSA public key from OpenSSH wire format. */
    private PublicKey decodeEcPublicKey(byte[] blob, String keyType) throws GeneralSecurityException {
        var buf = ByteBuffer.wrap(blob);
        // Skip key type string
        var typeLen = buf.getInt();
        buf.position(buf.position() + typeLen);
        // Skip curve name
        var curveLen = buf.getInt();
        var curveBytes = new byte[curveLen];
        buf.get(curveBytes);
        var curveName = new String(curveBytes);
        // Read EC point (uncompressed: 0x04 || x || y)
        var pointLen = buf.getInt();
        var pointBytes = new byte[pointLen];
        buf.get(pointBytes);

        // Map OpenSSH curve name to JCA
        var jcaCurve = switch (curveName) {
            case "nistp256" -> "secp256r1";
            case "nistp384" -> "secp384r1";
            case "nistp521" -> "secp521r1";
            default -> throw new GeneralSecurityException("Unknown EC curve: " + curveName);
        };

        var paramSpec = new ECGenParameterSpec(jcaCurve);
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(paramSpec);
        // We need the ECParameterSpec from a generated pair
        var ecParams = ((ECPublicKey)
            kpg.generateKeyPair().getPublic()).getParams();

        var ecPoint = decodeUncompressedPoint(pointBytes, ecParams);
        var ecSpec = new ECPublicKeySpec(ecPoint, ecParams);
        return KeyFactory.getInstance("EC").generatePublic(ecSpec);
    }

    private ECPoint decodeUncompressedPoint(
            byte[] pointBytes, ECParameterSpec params) {
        if (pointBytes[0] != 0x04) {
            throw new IllegalArgumentException("Only uncompressed EC points supported");
        }
        int fieldSize = (params.getOrder().bitLength() + 7) / 8;
        var x = new BigInteger(1, pointBytes, 1, fieldSize);
        var y = new BigInteger(1, pointBytes, 1 + fieldSize, fieldSize);
        return new ECPoint(x, y);
    }
}
