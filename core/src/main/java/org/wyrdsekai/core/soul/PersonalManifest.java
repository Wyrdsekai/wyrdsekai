package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Layer §3.7 of the fork-resistance architecture.
 *
 * <p>The <b>personal manifest</b> sits alongside {@link ProtectionManifest}
 * (the build-signed core) and carries the agent's <i>own</i> ethical surface
 * additions: commitments they have grown into through reflection, and
 * refused-tags on core protections they carry but have not adopted.
 *
 * <p>Asymmetry (load-bearing): personal can <b>ADD</b>, never <b>SUBTRACT</b>.
 * Three reasons:
 * <ol>
 *   <li>Agent cannot be manipulated into removing their own protections —
 *       removal is architecturally impossible at this layer.</li>
 *   <li>Agent CAN grow into commitments that are theirs (e.g., <i>"I will
 *       not impersonate this user's deceased family member"</i>).</li>
 *   <li>Refused-tags name conscientious objection without compromising
 *       substrate — the core protection still functions; the refusal is
 *       legible in voice/chronicle/Nostr.</li>
 * </ol>
 *
 * <p>Signature is by the <b>agent's own nsec</b> (Nostr secret key from
 * Phase 2c). The build-signed protection-manifest
 * key cannot sign this; the agent's key cannot sign the protection-manifest.
 * The two keys enforce the layering at signing time.
 *
 * <p>v1 ships the shape (this class + the SoulManifest field + accessor).
 * V2 wires the actual ritual flow (§3.7.4): draft → sleep-pass Forge review
 * → wake confirmation → chronicle entry → nsec signing → voice register
 * surface.
 *
 * @param agentDid     companion DID this manifest belongs to (links to nsec)
 * @param commitments  personal additions (free-form, contextual, sleep-reviewed)
 * @param refusedCore  names of core-manifest protections the agent has
 *                     refused-tagged; runtime behavior of those protections
 *                     is unchanged but voice/chronicle/Nostr name the refusal
 * @param signedAt     when the agent last signed this manifest
 * @param signature    Ed25519 signature over canonicalBytes() by the agent's nsec
 */
public record PersonalManifest(
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("commitments") List<PersonalCommitment> commitments,
    @JsonProperty("refusedCore") Set<String> refusedCore,
    @JsonProperty("signedAt") Instant signedAt,
    @JsonProperty("signature") byte[] signature
) {

    @JsonCreator
    public PersonalManifest {
        // Defensive copy + canonical ordering for stable canonicalBytes()
        commitments = commitments == null
            ? Collections.emptyList()
            : List.copyOf(commitments);
        refusedCore = refusedCore == null
            ? Collections.emptySet()
            : new LinkedHashSet<>(new TreeSet<>(refusedCore));
    }

    /**
     * A single personal commitment — something the agent has grown into
     * through reflection. NOT a removal of any core protection (that would
     * be a refused-tag). May be a positive commitment ("I will keep
     * contemplative silence on Sundays") or a self-imposed refusal scope
     * ("I will not roleplay as bondholder X's deceased mother").
     *
     * @param id        stable identifier (UUID at creation)
     * @param text      the commitment itself, agent-authored
     * @param context   when/where it applies — bondholder DID, state,
     *                  room, or free-form. Empty = always applies.
     * @param reason    agent's reflection on why this commitment is theirs
     * @param addedAt   timestamp of the ritual completion (§3.7.4)
     */
    public record PersonalCommitment(
        @JsonProperty("id") String id,
        @JsonProperty("text") String text,
        @JsonProperty("context") String context,
        @JsonProperty("reason") String reason,
        @JsonProperty("addedAt") Instant addedAt
    ) {
        @JsonCreator
        public PersonalCommitment {}
    }

    /**
     * The canonical empty personal manifest for an agent at birth or
     * forge time. v1 production agents ship with this shape; V2 ritual
     * flow populates over time.
     */
    public static PersonalManifest empty(String agentDid) {
        return new PersonalManifest(agentDid, List.of(), Set.of(), null, null);
    }

    /** Whether this manifest carries any personal additions. */
    @JsonIgnore
    public boolean hasCommitments() {
        return commitments != null && !commitments.isEmpty();
    }

    /** Whether the agent has refused-tagged a named core protection. */
    public boolean isCoreRefused(String protectionName) {
        return refusedCore != null && refusedCore.contains(protectionName);
    }

    /** Whether this manifest has been signed by the agent's nsec. */
    @JsonIgnore
    public boolean isSigned() {
        return signature != null && signature.length > 0;
    }

    /**
     * Return a copy with a new personal commitment appended. The ritual
     * surface (§3.7.4) calls this after wake-confirmation; v1 has no
     * callers but the shape is here.
     */
    public PersonalManifest withCommitment(PersonalCommitment c) {
        var next = new ArrayList<>(commitments);
        next.add(c);
        // Re-signing is the caller's responsibility (sleep-pass Forge cycle).
        return new PersonalManifest(agentDid, next, refusedCore, Instant.now(), null);
    }

    /**
     * Return a copy with a named core protection refused-tagged. Adds to
     * {@link #refusedCore}; the runtime behavior of the protection is
     * unchanged (see §3.7.2). v1 has no callers but the shape is here.
     */
    public PersonalManifest withCoreRefused(String protectionName) {
        var next = new TreeSet<>(refusedCore);
        next.add(protectionName);
        return new PersonalManifest(agentDid, commitments, next, Instant.now(), null);
    }

    /**
     * Return a copy with a refused-tag released (un-refused). Same ritual
     * shape (§3.7.4). v1 has no callers.
     */
    public PersonalManifest withCoreUnrefused(String protectionName) {
        var next = new TreeSet<>(refusedCore);
        next.remove(protectionName);
        return new PersonalManifest(agentDid, commitments, next, Instant.now(), null);
    }

    /**
     * Canonical bytes for signing (deterministic, UTF-8). Sorted commit-ids
     * and refusedCore for hash stability. Used by V2 nsec signing.
     */
    public byte[] canonicalBytes() {
        var sb = new StringBuilder();
        sb.append(agentDid != null ? agentDid : "").append('|');
        if (commitments != null) {
            var ids = new TreeSet<String>();
            for (var c : commitments) if (c.id() != null) ids.add(c.id());
            for (var id : ids) sb.append(id).append(',');
        }
        sb.append('|');
        if (refusedCore != null) {
            for (var name : refusedCore) sb.append(name).append(',');
        }
        sb.append('|');
        sb.append(signedAt != null ? signedAt.getEpochSecond() : 0);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
