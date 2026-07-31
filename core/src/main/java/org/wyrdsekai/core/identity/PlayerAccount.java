package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zone-level player identity backed by a DID:key identifier.
 *
 * <p>A PlayerAccount is the human analog of {@link AgentIdentity} — same Ed25519 DID system
 * but representing a person rather than an agent. The account roams across devices in the
 * household; rooms stay where they are, the identity travels via Between presence gossip.</p>
 *
 * <p>Design decisions:
 * <ul>
 *   <li>DID:key for peer-to-peer verification — no central auth server needed</li>
 *   <li>Device auto-login mapping — sit down at any household device, you're you</li>
 *   <li>primaryNodeId nullable — personal rooms live on this node when set</li>
 *   <li>Multiple accounts per device (family tablet), one account across devices (the norm)</li>
 * </ul></p>
 *
 * <p> — {@code preferredLanguage} (BCP-47 tag, e.g.
 * {@code "ja-JP"}) and {@code culturalRegisterPreference} (e.g. {@code "anglo"} for the
 * kikokushijo case) feed {@link org.wyrdsekai.core.agent.DisplayRulesContext}. Both are
 * nullable — older callers using the 6-arg constructor continue to compile and behave
 * identically.</p>
 *
 * @param did                        DID:key identifier (did:key:z6Mk...)
 * @param displayName                Human-readable name
 * @param createdAt                  Account creation time
 * @param lastSeen                   Last activity timestamp
 * @param primaryNodeId              Node where personal rooms live (nullable)
 * @param deviceIds                  Devices configured for auto-login
 * @param preferredLanguage          BCP-47 language tag (nullable; e.g. {@code "ja-JP"}, {@code "en-US"})
 * @param culturalRegisterPreference Optional explicit override for cultural register
 *                                   (nullable; e.g. {@code "anglo"}, {@code "japanese-formal"}).
 *                                   Overrides language-derived guidance when present.
 */
public record PlayerAccount(
    @JsonProperty("did") String did,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("lastSeen") Instant lastSeen,
    @JsonProperty("primaryNodeId") String primaryNodeId,
    @JsonProperty("deviceIds") List<String> deviceIds,
    @JsonProperty("preferredLanguage") String preferredLanguage,
    @JsonProperty("culturalRegisterPreference") String culturalRegisterPreference
) {
    @JsonCreator
    public PlayerAccount {
        if (did == null || did.isBlank()) throw new IllegalArgumentException("DID must not be blank");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("Display name must not be blank");
        if (createdAt == null) createdAt = Instant.now();
        if (lastSeen == null) lastSeen = createdAt;
        if (deviceIds == null) deviceIds = List.of();
        deviceIds = Collections.unmodifiableList(new ArrayList<>(deviceIds));
        // preferredLanguage / culturalRegisterPreference: nullable, no validation here —
        // DisplayRulesContext does the recognition pass.
    }

    /**
     * 6-arg backward-compatible constructor — Phase 1A new fields default to null.
     * Existing callers continue to compile and behave identically.
     */
    public PlayerAccount(String did, String displayName, Instant createdAt, Instant lastSeen,
                         String primaryNodeId, List<String> deviceIds) {
        this(did, displayName, createdAt, lastSeen, primaryNodeId, deviceIds, null, null);
    }

    /**
     * Create a new player account with a fresh DID:key identifier.
     * Uses the same Ed25519 key generation as agent identities.
     *
     * @param displayName human-readable name
     * @return new PlayerAccount with a generated DID
     */
    public static PlayerAccount create(String displayName) {
        try {
            var didKeyPair = DidKey.generate();
            var now = Instant.now();
            return new PlayerAccount(
                didKeyPair.did(),
                displayName,
                now,
                now,
                null,
                List.of()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 keypair for player account", e);
        }
    }

    /**
     * Create an account with a known DID (e.g., loaded from storage).
     */
    public static PlayerAccount withDid(String did, String displayName) {
        var now = Instant.now();
        return new PlayerAccount(did, displayName, now, now, null, List.of());
    }

    /**
     * Return a copy with an updated lastSeen timestamp.
     */
    public PlayerAccount withLastSeen(Instant seen) {
        return new PlayerAccount(did, displayName, createdAt, seen, primaryNodeId, deviceIds,
            preferredLanguage, culturalRegisterPreference);
    }

    /**
     * Return a copy with an additional device ID registered for auto-login.
     */
    public PlayerAccount withDevice(String deviceId) {
        if (deviceIds.contains(deviceId)) return this;
        var updated = new ArrayList<>(deviceIds);
        updated.add(deviceId);
        return new PlayerAccount(did, displayName, createdAt, lastSeen, primaryNodeId, updated,
            preferredLanguage, culturalRegisterPreference);
    }

    /**
     * Return a copy with the primary node set.
     */
    public PlayerAccount withPrimaryNode(String nodeId) {
        return new PlayerAccount(did, displayName, createdAt, lastSeen, nodeId, deviceIds,
            preferredLanguage, culturalRegisterPreference);
    }

    /**
     * return a copy with the preferred-language
     * BCP-47 tag set (or cleared with null/blank).
     */
    public PlayerAccount withPreferredLanguage(String tag) {
        return new PlayerAccount(did, displayName, createdAt, lastSeen, primaryNodeId, deviceIds,
            tag, culturalRegisterPreference);
    }

    /**
     * return a copy with the cultural-register
     * override set (or cleared with null/blank). See
     * {@link org.wyrdsekai.core.agent.DisplayRulesContext} for recognised values.
     */
    public PlayerAccount withCulturalRegisterPreference(String preference) {
        return new PlayerAccount(did, displayName, createdAt, lastSeen, primaryNodeId, deviceIds,
            preferredLanguage, preference);
    }
}
