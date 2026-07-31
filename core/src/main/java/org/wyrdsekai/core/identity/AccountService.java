package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Business logic for player account management.
 *
 * <p>Sits between the {@link AccountStore} (persistence) and the WebSocket/HTTP layers.
 * Handles account creation, DID-based authentication, device auto-login, and activity tracking.</p>
 *
 * <p>Authentication is peer-to-peer: verify an Ed25519 signature against the account's DID
 * public key. No central auth server. Device auto-login allows household devices to map to
 * accounts without re-authentication.</p>
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountStore store;

    public AccountService(AccountStore store) {
        this.store = store;
    }

    /**
     * Create a new player account with a generated DID.
     *
     * @param displayName human-readable name
     * @return the newly created account
     */
    public PlayerAccount createAccount(String displayName) {
        var account = PlayerAccount.create(displayName);
        store.save(account);
        log.info("Player account created: {} ({})", displayName, account.did());
        return account;
    }

    /**
     * Authenticate by DID signature verification.
     * Used for cross-device login where the player proves ownership of their DID.
     *
     * @param did        the account's DID:key identifier
     * @param signature  Ed25519 signature over the challenge bytes
     * @param challenge  the challenge that was signed
     * @return the account if signature is valid, empty otherwise
     */
    public Optional<PlayerAccount> authenticate(String did, byte[] signature, byte[] challenge) {
        var accountOpt = store.findByDid(did);
        if (accountOpt.isEmpty()) {
            log.debug("Authentication failed — unknown DID: {}", did);
            return Optional.empty();
        }

        try {
            // Extract raw 32-byte public key from the DID
            var multibaseKey = did.substring("did:key:".length());
            var rawPubKey = DidKey.rawPublicKeyFromMultibase(multibaseKey);

            // Reconstruct X.509-encoded public key for JDK verification
            var spki = new byte[44];
            var header = new byte[]{
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
                0x70, 0x03, 0x21, 0x00
            };
            System.arraycopy(header, 0, spki, 0, 12);
            System.arraycopy(rawPubKey, 0, spki, 12, 32);

            var pubKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(spki));

            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(challenge);
            if (!verifier.verify(signature)) {
                log.debug("Authentication failed — invalid signature for DID: {}", did);
                return Optional.empty();
            }

            // Update last seen
            store.updateLastSeen(did, Instant.now());
            log.info("Authenticated player via DID signature: {}", did);
            return accountOpt;

        } catch (Exception e) {
            log.warn("Authentication failed — crypto error for DID {}: {}", did, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Auto-login by device ID.
     * Returns the account configured for this device, if any.
     *
     * @param deviceId device identifier (e.g., machine ID or hardware fingerprint)
     * @return the associated account, or empty if no auto-login configured
     */
    public Optional<PlayerAccount> autoLogin(String deviceId) {
        var didOpt = store.findAccountForDevice(deviceId);
        if (didOpt.isEmpty()) return Optional.empty();

        var accountOpt = store.findByDid(didOpt.get());
        if (accountOpt.isPresent()) {
            store.updateLastSeen(accountOpt.get().did(), Instant.now());
            log.debug("Auto-login: device {} -> {}", deviceId, accountOpt.get().displayName());
        }
        return accountOpt;
    }

    /**
     * Register a device for auto-login to an account.
     *
     * @param did      the account's DID
     * @param deviceId the device to register
     */
    public void registerDevice(String did, String deviceId) {
        store.registerDevice(did, deviceId);
        log.info("Device {} registered for auto-login to {}", deviceId, did);
    }

    /**
     * Record activity for an account (updates last_seen).
     */
    public void recordActivity(String did) {
        store.updateLastSeen(did, Instant.now());
    }

    /**
     * Look up an account by DID.
     */
    public Optional<PlayerAccount> findByDid(String did) {
        return store.findByDid(did);
    }

    /**
     * Look up an account by display name.
     */
    public Optional<PlayerAccount> findByName(String name) {
        return store.findByName(name);
    }
}
