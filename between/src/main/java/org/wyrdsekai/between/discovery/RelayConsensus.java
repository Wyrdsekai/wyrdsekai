package org.wyrdsekai.between.discovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/**
 * Directory authority voting for relay consensus.
 *
 * N authority nodes (default 3) each independently sign the relay list.
 * Clients require M-of-N valid signatures (default 2-of-3) to accept the list.
 *
 * Prevents a single compromised authority key from poisoning the directory.
 * The consensus is published periodically (e.g., hourly) and cached locally.
 *
 * Authority keys are well-known Ed25519 public keys, hardcoded or fetched via DNS TXT.
 */
public class RelayConsensus {

    /**
     * A relay entry in the consensus.
     *
     * <p>: {@code relayFingerprint} carries
     * the SHA-256 of the relay's TLS leaf cert. The joining household
     * pins this fingerprint <i>before</i> the TLS handshake, so a
     * compromised DNS or BGP hijack can't redirect the join to a
     * different cert. Nullable for backward compat with pre-F2.2
     * authorities; pre-F2.2 entries fall back to TOFU-on-first-connect
     * behavior with a WARN log.
     */
    public record RelayEntry(
        @JsonProperty("url") String url,
        @JsonProperty("publicRelay") boolean publicRelay,
        @JsonProperty("capacity") int capacity,
        @JsonProperty("registered") int registered,
        @JsonProperty("region") String region,
        @JsonProperty("operatorZoneId") String operatorZoneId,
        @JsonProperty("relayFingerprint") String relayFingerprint
    ) {
        @JsonCreator
        public RelayEntry {}

        /** Backward-compat ctor without fingerprint (pre-F2.2 wire). */
        public RelayEntry(String url, boolean publicRelay, int capacity,
                          int registered, String region, String operatorZoneId) {
            this(url, publicRelay, capacity, registered, region, operatorZoneId, null);
        }

        public boolean hasCapacity() {
            return registered < capacity;
        }

        public double utilizationPercent() {
            return capacity > 0 ? (registered * 100.0 / capacity) : 100.0;
        }
    }

    /** A signed vote from a directory authority. */
    public record AuthorityVote(
        @JsonProperty("authorityId") String authorityId,
        @JsonProperty("authorityPublicKey") String authorityPublicKey,
        @JsonProperty("signature") String signature,
        @JsonProperty("votedAt") Instant votedAt
    ) {
        @JsonCreator
        public AuthorityVote {}
    }

    /** The consensus document — relay list + authority votes. */
    public record ConsensusDocument(
        @JsonProperty("version") int version,
        @JsonProperty("relays") List<RelayEntry> relays,
        @JsonProperty("votes") List<AuthorityVote> votes,
        @JsonProperty("generatedAt") Instant generatedAt,
        @JsonProperty("expiresAt") Instant expiresAt
    ) {
        @JsonCreator
        public ConsensusDocument {}

        /** Check if this consensus has expired. */
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        /** Get only public relays with capacity. */
        public List<RelayEntry> availablePublicRelays() {
            return relays.stream()
                .filter(r -> r.publicRelay && r.hasCapacity())
                .toList();
        }

        /**
         * Canonical bytes for signature verification.
         *
         * <p>F2.2: {@code relayFingerprint} is appended to each entry so
         * authority votes commit to the fingerprint they observed. A
         * later doc with a different fingerprint won't verify against
         * older signatures even if URL+capacity are identical — closes
         * the "authority signed once, attacker swaps cert later" attack.
         * Pre-F2.2 entries with null fingerprint serialize as empty
         * string for backward compat (verifies against pre-F2.2 votes).
         */
        byte[] canonicalBytes() {
            var sb = new StringBuilder();
            sb.append("v").append(version).append("|");
            sb.append(generatedAt).append("|");
            for (var relay : relays) {
                sb.append(relay.url).append(",")
                  .append(relay.publicRelay).append(",")
                  .append(relay.capacity).append(",")
                  .append(relay.registered).append(",")
                  .append(relay.region != null ? relay.region : "").append(",")
                  .append(relay.relayFingerprint != null ? relay.relayFingerprint : "")
                  .append(";");
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Configuration for the authority voting system. */
    public record AuthorityConfig(
        List<String> authorityPublicKeys,
        int requiredVotes
    ) {
        /** Default: 2-of-3 voting. */
        public static AuthorityConfig defaultConfig(String key1, String key2, String key3) {
            return new AuthorityConfig(List.of(key1, key2, key3), 2);
        }

        /** Single authority (self-signed, for development). */
        public static AuthorityConfig single(String key) {
            return new AuthorityConfig(List.of(key), 1);
        }

        /** Validate that enough votes are present and valid. */
        public boolean isValid() {
            return authorityPublicKeys != null
                && !authorityPublicKeys.isEmpty()
                && requiredVotes > 0
                && requiredVotes <= authorityPublicKeys.size();
        }
    }

    private final AuthorityConfig config;
    private volatile ConsensusDocument currentConsensus;
    private volatile ConsensusDocument cachedConsensus; // fallback if current expires

    public RelayConsensus(AuthorityConfig config) {
        this.config = config;
    }

    /** Create with single authority (development mode). */
    public static RelayConsensus singleAuthority(String publicKey) {
        return new RelayConsensus(AuthorityConfig.single(publicKey));
    }

    /**
     * Verify a consensus document against the authority config.
     *
     * @return number of valid votes (>= requiredVotes means consensus is accepted)
     */
    public int verifyVotes(ConsensusDocument doc) {
        if (doc == null || doc.votes == null) return 0;

        int validVotes = 0;
        var canonicalBytes = doc.canonicalBytes();

        for (var vote : doc.votes) {
            // Check authority is in our config
            if (!config.authorityPublicKeys.contains(vote.authorityPublicKey)) {
                continue;
            }

            // Verify Ed25519 signature
            if (vote.signature != null && verifyEd25519(
                    canonicalBytes, vote.signature, vote.authorityPublicKey)) {
                validVotes++;
            }
        }

        return validVotes;
    }

    /**
     * Accept a consensus document if it has enough valid votes.
     *
     * @return true if accepted
     */
    public boolean acceptConsensus(ConsensusDocument doc) {
        int validVotes = verifyVotes(doc);
        if (validVotes >= config.requiredVotes) {
            this.cachedConsensus = this.currentConsensus;
            this.currentConsensus = doc;
            return true;
        }
        return false;
    }

    /** Get the current consensus (may be expired — check isExpired()). */
    public Optional<ConsensusDocument> currentConsensus() {
        if (currentConsensus != null && !currentConsensus.isExpired()) {
            return Optional.of(currentConsensus);
        }
        // Fall back to cached if current expired
        if (cachedConsensus != null) {
            return Optional.of(cachedConsensus);
        }
        return Optional.empty();
    }

    /** Get available public relays from the current consensus. */
    public List<RelayEntry> availableRelays() {
        return currentConsensus()
            .map(ConsensusDocument::availablePublicRelays)
            .orElse(List.of());
    }

    /** Authority config. */
    public AuthorityConfig config() {
        return config;
    }

    // --- Authority-side: create and sign consensus ---

    /**
     * Create an unsigned consensus document.
     */
    public static ConsensusDocument createConsensus(List<RelayEntry> relays, int version) {
        return new ConsensusDocument(version, relays, new ArrayList<>(),
            Instant.now(), Instant.now().plusSeconds(3600));
    }

    /**
     * Sign a consensus document as an authority.
     *
     * @param doc         the document to sign
     * @param authorityId this authority's ID
     * @param publicKey   this authority's public key (multibase)
     * @param privateKey  this authority's private key
     * @return the document with this authority's vote appended
     */
    public static ConsensusDocument signConsensus(ConsensusDocument doc,
                                                    String authorityId,
                                                    String publicKey,
                                                    PrivateKey privateKey) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(doc.canonicalBytes());
            var signatureStr = Base64.getEncoder().encodeToString(sig.sign());

            var vote = new AuthorityVote(authorityId, publicKey, signatureStr, Instant.now());

            var votes = new ArrayList<>(doc.votes);
            votes.add(vote);

            return new ConsensusDocument(doc.version, doc.relays, votes,
                doc.generatedAt, doc.expiresAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign consensus", e);
        }
    }

    // --- Crypto helpers ---

    private static boolean verifyEd25519(byte[] data, String signatureBase64, String publicKeyMultibase) {
        try {
            var sig = Signature.getInstance("Ed25519");
            // Decode public key from multibase (simplified — assumes base64)
            var keyBytes = Base64.getDecoder().decode(publicKeyMultibase);
            var keySpec = new X509EncodedKeySpec(keyBytes);
            var keyFactory = KeyFactory.getInstance("Ed25519");
            var pubKey = keyFactory.generatePublic(keySpec);

            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }
}
