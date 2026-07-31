package org.wyrdsekai.core.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.core.nostr.NostrEvent;
import org.wyrdsekai.core.nostr.NostrKey;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wave 5.3: build a signed Nostr attestation
 * event declaring this agent's active protections. Layer 3 of the
 * fork-resistance architecture — Layers 1+2 catch substrate tampering
 * locally; Layer 3 makes the substrate state <i>publicly visible</i> so
 * federation observers can see which protections each agent claims to
 * run, when each was last attested, and whose attestations stop showing
 * up (detectable absence).
 *
 * <p>Event shape per spec §5.1 (extended with §3.7 layered manifest):
 * <pre>
 *   Kind:    30078       (NIP-78 application-specific replaceable data;
 *                         only the most recent per (pubkey, d-tag) is kept)
 *   Tags:    ["d", agentDid]            (NIP-33 replaceable identifier)
 *            ["build", buildId]
 *            ["k", protection1]         (one per active core protection)
 *            ["k", protection2]
 *            ["p", commitmentId1]       (§3.7 personal commitments — agent-grown)
 *            ["refused", coreName1]     (§3.7 refused-tags — runtime unchanged)
 *            ...
 *            ["t", "wyrdsekai-attest"]  (subscriber filter helper)
 *            ["tampered", "true"|"false"|"unavailable"]
 *   Content: JSON-serialized
 *              { "agentDid": ..., "buildId": ..., "names": [...],
 *                "personal": [...], "refused": [...],
 *                "tampered": ..., "attestedAt": &lt;epoch-seconds&gt; }
 *   Signature: agent's BIP-340 schnorr over canonical event id
 * </pre>
 *
 * <p>Privacy posture (spec §5.4): no bondholder identifier, no chronicle
 * fragments, no personal facts. Just "this agent runs this build with
 * these protections at this time." The agent's per-companion NostrKey
 * (Phase 2c) is the publishing identity — the relationship between agent
 * DID and bondholder identity is not derivable from the attestation
 * alone.
 *
 * <p>This class only <i>builds</i> the event. Actual publish goes through
 * {@link org.wyrdsekai.core.nostr.NostrRelayPool#publish(NostrEvent)};
 * cadence (boot + every 7 days + on protection change) is the publisher
 * actor's concern, to be wired by a future Wave 5.3b cadence scheduler.
 */
public final class ProtectionAttestation {

    /** NIP-78 application-specific replaceable data — kept "only latest per (pubkey, d-tag)". */
    public static final int KIND = 30078;

    /** Subscriber-side filter tag ({@code #t=wyrdsekai-attest}). */
    public static final String TOPIC_TAG = "wyrdsekai-attest";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtectionAttestation() {}

    /**
     * Build a signed kind-30078 attestation event for an agent.
     *
     * @param key the companion's per-agent Nostr keypair
     * @param agentDid    the agent's DID (used as the {@code d}-tag, so the
     *                    next attestation from the same key replaces this
     *                    one rather than accumulating per spec §5.3)
     * @param buildId     release build identifier (matches the binary's
     *                    moral-defaults attestation)
     * @param names       active protections this agent runs with — usually
     *                    {@link ProtectionManifest#canonicalDefaults()},
     *                    but can be a strict subset for forks that
     *                    declare they have stripped something
     * @param tamperState one of {@code "true"} / {@code "false"} /
     *                    {@code "unavailable"} from the
     *                    {@link MoralDefaultsVerifier} boot-time check
     * @param attestedAt  unix seconds — pass {@link Instant#getEpochSecond()}
     */
    public static NostrEvent build(NostrKey key, String agentDid, String buildId,
                                    Set<String> names, String tamperState, long attestedAt) {
        return build(key, agentDid, buildId, names, Set.of(), Set.of(), tamperState, attestedAt);
    }

    /**
     * §3.7 extended build: include personal-manifest layer (commitment ids
     * + refused-tag names). At v1, callers pass empty sets for both — the
     * shape is on the wire so subscribers and tests can start aggregating
     * the federation-visible refusal signal (§3.7.6).
     *
     * @param personal      personal commitment identifiers the agent has
     *                      grown into (PersonalManifest.PersonalCommitment.id())
     * @param refusedCore   names of core protections the agent has
     *                      refused-tagged (runtime behavior unchanged)
     */
    public static NostrEvent build(NostrKey key, String agentDid, String buildId,
                                    Set<String> names, Set<String> personal,
                                    Set<String> refusedCore,
                                    String tamperState, long attestedAt) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid must not be blank");
        }
        var sortedNames = new TreeSet<>(names == null ? Set.<String>of() : names);
        var sortedPersonal = new TreeSet<>(personal == null ? Set.<String>of() : personal);
        var sortedRefused = new TreeSet<>(refusedCore == null ? Set.<String>of() : refusedCore);
        var tampered = (tamperState == null || tamperState.isBlank()) ? "unavailable" : tamperState;
        var build = buildId == null ? "" : buildId;

        var tags = new ArrayList<List<String>>();
        tags.add(List.of("d", agentDid));
        tags.add(List.of("build", build));
        tags.add(List.of("t", TOPIC_TAG));
        tags.add(List.of("tampered", tampered));
        for (var name : sortedNames) tags.add(List.of("k", name));
        for (var commitmentId : sortedPersonal) tags.add(List.of("p", commitmentId));
        for (var refusedName : sortedRefused) tags.add(List.of("refused", refusedName));

        var content = buildContentJson(agentDid, build, sortedNames, sortedPersonal,
            sortedRefused, tampered, attestedAt);
        return NostrEvent.buildAndSign(key, KIND, tags, content, attestedAt);
    }

    /**
     * Convenience: build from a {@link MoralDefaultsVerifier.Result}.
     * Used in the common boot-time path where the attestation reflects
     * exactly what the in-process verifier saw.
     *
     * <p>For {@link MoralDefaultsVerifier.Verified}, names = the
     * verified set; tampered = {@code "false"}. For {@link
     * MoralDefaultsVerifier.Tampered}, names = the runtime canonical set
     * (so observers see what this build STILL claims to provide); tampered
     * = {@code "true"} + the reason in the content json. For {@link
     * MoralDefaultsVerifier.Unavailable}, names = the runtime canonical
     * set; tampered = {@code "unavailable"}.
     */
    public static NostrEvent fromVerifierResult(
        NostrKey key, String agentDid,
        MoralDefaultsVerifier.Result result, long attestedAt
    ) {
        return switch (result) {
            case MoralDefaultsVerifier.Verified v ->
                build(key, agentDid, v.buildId(), v.names(), "false", attestedAt);
            case MoralDefaultsVerifier.Tampered t -> build(
                key, agentDid,
                System.getProperty("wyrdsekai.protection.buildId", "tampered-build"),
                ProtectionManifest.canonicalDefaults(),
                "true",
                attestedAt);
            case MoralDefaultsVerifier.Unavailable u -> build(
                key, agentDid,
                System.getProperty("wyrdsekai.protection.buildId", "unknown-build"),
                ProtectionManifest.canonicalDefaults(),
                "unavailable",
                attestedAt);
        };
    }

    /**
     * Canonical JSON content shape. Public so subscribers can know the
     * field set; written exactly as documented in the class javadoc.
     * Back-compat overload — emits empty personal/refused arrays so the
     * wire shape stays consistent (§3.7).
     */
    public static String buildContentJson(String agentDid, String buildId,
                                            Set<String> sortedNames,
                                            String tampered, long attestedAt) {
        return buildContentJson(agentDid, buildId, sortedNames,
            Set.of(), Set.of(), tampered, attestedAt);
    }

    /**
     * §3.7 extended content shape with personal + refused arrays.
     */
    public static String buildContentJson(String agentDid, String buildId,
                                            Set<String> sortedNames,
                                            Set<String> sortedPersonal,
                                            Set<String> sortedRefused,
                                            String tampered, long attestedAt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("agentDid", agentDid);
            root.put("buildId", buildId);
            var names = root.putArray("names");
            for (var name : sortedNames) names.add(name);
            var personal = root.putArray("personal");
            for (var p : sortedPersonal) personal.add(p);
            var refused = root.putArray("refused");
            for (var r : sortedRefused) refused.add(r);
            root.put("tampered", tampered);
            root.put("attestedAt", attestedAt);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize attestation content", e);
        }
    }
}
