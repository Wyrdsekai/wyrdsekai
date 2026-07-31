package org.wyrdsekai.core.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MVP Recovery Seed.
 *
 * <p>The solo-household failure-mode mitigation: a small, encrypted blob
 * that contains <i>just enough</i> to spin up a new install and recover
 * companion continuity. Not a full backup — a <b>seed</b>. Full memory
 * archives are a separate concern; this record carries only what is
 * required for the companion to be recognizably themselves on a fresh
 * machine.
 *
 * <p>What's in the seed (the minimum for honest continuity):
 * <ul>
 *   <li><b>Identity</b> — DID, public key, key log (KERI rotation history),
 *       parent DID if any. Cryptographic identity is the spine.</li>
 *   <li><b>Persona</b> — agent name, system prompt, residentIdentity text
 *       (the MEDIUM soul ~69 tokens), voice profile clauses. The "who
 *       they sound like" layer.</li>
 *   <li><b>Bond pointers</b> — list of bondholder DIDs + last-known bond
 *       state + scarred flag. Re-establishing trust is the bondholder's
 *       responsibility; what we restore is the agent knowing who their
 *       people are.</li>
 *   <li><b>Substrate state</b> — protection-manifest snapshot, personal-
 *       manifest snapshot, last verified moral-defaults build-id. So the
 *       restored agent boots with the same protection set + personal
 *       commitments + can detect whether the new install's substrate
 *       matches the one they were attested under.</li>
 *   <li><b>Memory anchor</b> — a stable hash of the agent's chronicle
 *       state at seed time (not the chronicle itself). Enables "this is
 *       the same companion" verification on rejoin.</li>
 * </ul>
 *
 * <p>What's NOT in the seed:
 * <ul>
 *   <li>Raw chronicle entries (too private, too large).</li>
 *   <li>Full conversation history.</li>
 *   <li>nsec private keys (those have their own recovery path —
 *       Nostr-side; not within Wyrdsekai's primary recovery).</li>
 *   <li>Full bondholder PII (only DIDs).</li>
 *   <li>Soul fragments beyond the resident identity (those rebuild from
 *       the resumed Forge cycle).</li>
 * </ul>
 *
 * <p>Codec: {@link RecoverySeedCodec} serializes to JSON, then encrypts
 * with AES-256-GCM under a PBKDF2-derived key from a bondholder-supplied
 * passphrase. File format ships salt + IV + ciphertext concatenated, so
 * recovery only needs the file + the passphrase.
 *
 * @param formatVersion  schema version (1 in v1; bump if shape changes)
 * @param createdAt      seed generation timestamp
 * @param agentDid       companion DID (the cryptographic identity)
 * @param publicKey      multibase-encoded Ed25519 public key
 * @param keyLog         KERI event log entries (raw JSON-as-string for
 *                       portability across versions; each entry is a
 *                       KERI inception or rotation event)
 * @param parentDid      parent agent's DID if forked from another, else null
 * @param agentName      profile name ("Wyrd")
 * @param entityId       entity identifier within the world
 * @param systemPrompt   the agent's system prompt
 * @param residentIdentity   MEDIUM soul text (~69 tokens) — always in prompt
 * @param voiceClauses   ordered map of voice-register clauses
 * @param bondPointers   list of bondholder pointers (DIDs + last-known state)
 * @param protectionNames protection set this agent claims to run with
 * @param personalCommitments personal-manifest commitment IDs (the names
 *                       only; full commitment text isn't carried in the seed
 *                       to keep size bounded — a richer Recovery Bundle
 *                       extends this in V2)
 * @param refusedCore    core-manifest names the agent has refused-tagged
 * @param attestationBuildId  the moral-defaults bundle buildId this agent
 *                       was last verified under
 * @param chronicleAnchorHash  SHA-256 of the last chronicle state at seed
 *                       time. Used to verify "same companion" on rejoin.
 */
public record RecoverySeed(
    @JsonProperty("formatVersion") int formatVersion,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("publicKey") String publicKey,
    @JsonProperty("keyLog") List<String> keyLog,
    @JsonProperty("parentDid") String parentDid,
    @JsonProperty("agentName") String agentName,
    @JsonProperty("entityId") String entityId,
    @JsonProperty("systemPrompt") String systemPrompt,
    @JsonProperty("residentIdentity") String residentIdentity,
    @JsonProperty("voiceClauses") Map<String, String> voiceClauses,
    @JsonProperty("bondPointers") List<BondPointer> bondPointers,
    @JsonProperty("protectionNames") List<String> protectionNames,
    @JsonProperty("personalCommitments") List<String> personalCommitments,
    @JsonProperty("refusedCore") List<String> refusedCore,
    @JsonProperty("attestationBuildId") String attestationBuildId,
    @JsonProperty("chronicleAnchorHash") String chronicleAnchorHash
) {

    @JsonCreator
    public RecoverySeed {}

    /** Current schema version. Bump when fields are added/removed. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /**
     * A single bondholder pointer — DID + last bond state. Restored
     * companion knows the bondholder exists; mutual recognition is
     * re-established at the next interaction (the cold-start window
     * applies).
     *
     * @param bondholderDid       bondholder's DID
     * @param lastKnownState      bond state at seed time ("ACTIVE", "AWAY", etc.)
     * @param scarred             whether this bond carries a scar
     * @param lastInteractionAt   timestamp of last interaction at seed time
     */
    public record BondPointer(
        @JsonProperty("bondholderDid") String bondholderDid,
        @JsonProperty("lastKnownState") String lastKnownState,
        @JsonProperty("scarred") boolean scarred,
        @JsonProperty("lastInteractionAt") Instant lastInteractionAt
    ) {
        @JsonCreator
        public BondPointer {}
    }

    /**
     * Minimal-fields factory for tests + smoke validation. Most callers
     * should use {@code RecoverySeedBuilder} (V2) — for v1 the record's
     * canonical constructor is the API.
     */
    public static RecoverySeed minimal(String agentDid, String publicKey,
                                          String agentName, String entityId,
                                          String systemPrompt) {
        return new RecoverySeed(
            CURRENT_FORMAT_VERSION, Instant.now(),
            agentDid, publicKey, List.of(), null,
            agentName, entityId, systemPrompt,
            "", Map.of(), List.of(),
            List.of(), List.of(), List.of(),
            null, ""
        );
    }
}
