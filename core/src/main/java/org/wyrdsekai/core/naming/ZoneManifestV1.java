package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MCP-style structured zone manifest for the directory.
 *
 * <p>Single source of truth for both human visitors and AI agents
 * discovering a zone. Same shape, same content, two readers — an operator
 * writes it once and both audiences reason over it. Published to the DHT
 * once per zone; refreshed every ~24h; signed by the household keypair.</p>
 *
 * <p>Shape matches the JSON example in spec §5.2:</p>
 * <pre>
 * {
 *   "schema_version": "wyrd-zone-manifest/1",
 *   "did": "did:wyrd:z6Mk…",
 *   "zoneLabel": "kitchen",
 *   "displayName": "Alice's Kitchen",
 *   "icon": "☕",
 *   "tagline": "Afternoon tea...",      // ≤120 chars
 *   "description": "A warm...",          // ≤500 chars
 *   "tags": ["family", "social"],
 *   "capabilities": { ... },
 *   "contact": { ... },
 *   "mcp_endpoint": null,
 *   "agreements_count": 14,
 *   "created_at": "...",
 *   "refreshed_at": "...",
 *   "signature": "ed25519:..."
 * }
 * </pre>
 *
 * <p>Wave-10 partial scope: the record + JSON codec + size-cap validation
 * ship now. Tag-keyed DHT secondary entries, signature verification, and
 * libp2p publish/subscribe land in the dedicated directory integration
 * wave (separate task, requires jvm-libp2p wiring).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "schema_version", "did", "zoneLabel", "displayName", "icon",
    "tagline", "description", "tags",
    "capabilities", "contact", "mcp_endpoint",
    "agreements_count", "reputation", "attestations",
    "created_at", "refreshed_at", "signature"
})
public record ZoneManifestV1(
    @JsonProperty("schema_version") String schemaVersion,
    @JsonProperty("did") String did,
    @JsonProperty("zoneLabel") String zoneLabel,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("icon") @JsonInclude(JsonInclude.Include.NON_NULL) String icon,
    @JsonProperty("tagline") String tagline,
    @JsonProperty("description") String description,
    @JsonProperty("tags") List<String> tags,
    @JsonProperty("capabilities") Capabilities capabilities,
    @JsonProperty("contact") Contact contact,
    @JsonProperty("mcp_endpoint") @JsonInclude(JsonInclude.Include.NON_NULL) String mcpEndpoint,
    @JsonProperty("agreements_count") int agreementsCount,
    @JsonProperty("reputation") @JsonInclude(JsonInclude.Include.NON_NULL) Reputation reputation,
    @JsonProperty("attestations") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Attestation> attestations,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("refreshed_at") String refreshedAt,
    @JsonProperty("signature") @JsonInclude(JsonInclude.Include.NON_NULL) String signature
) {

    /**
     * Backward-compatible constructor for callers that don't supply
     * {@link #reputation} / {@link #attestations}. Defaults both to null.
     */
    public ZoneManifestV1(
            String schemaVersion, String did, String zoneLabel,
            String displayName, String icon, String tagline, String description,
            List<String> tags, Capabilities capabilities, Contact contact,
            String mcpEndpoint, int agreementsCount,
            String createdAt, String refreshedAt, String signature) {
        this(schemaVersion, did, zoneLabel, displayName, icon, tagline, description,
            tags, capabilities, contact, mcpEndpoint, agreementsCount,
            null, null, createdAt, refreshedAt, signature);
    }

    /** Current schema version. Stays v1 as long as known-field semantics don't change. */
    public static final String SCHEMA_VERSION = "wyrd-zone-manifest/1";

    /** Soft limit — target size for a signed manifest (§5.2 "Size budget"). */
    public static final int SIZE_TARGET_BYTES = 2048;
    /** Hard limit — manifests larger than this are rejected for DHT publish. */
    public static final int SIZE_HARD_CAP_BYTES = 4096;

    /** Max {@link #tagline} length per spec §5.2. */
    public static final int TAGLINE_MAX = 120;
    /** Max {@link #description} length per spec §5.2. */
    public static final int DESCRIPTION_MAX = 500;

    /**
     * Capabilities block (§5.2). Describes what visitors can reach from
     * the Parlor, what companions they'll encounter, and what tiers the
     * zone offers.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(
        @JsonProperty("parlor") ParlorInfo parlor,
        @JsonProperty("rooms") List<PublicRoom> rooms,
        @JsonProperty("agents") List<PublicAgent> agents,
        @JsonProperty("hours") Map<String, String> hours,
        @JsonProperty("tiers") Map<String, TierCaps> tiers
    ) {}

    public record ParlorInfo(
        @JsonProperty("roomId") String roomId,
        @JsonProperty("tagline") String tagline,
        @JsonProperty("description") String description
    ) {}

    public record PublicRoom(
        @JsonProperty("label") String label,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description
    ) {}

    /**
     * A visible host/companion agent. {@link #skills} is new in the
     * expanded manifest (§5.3); older callers can use the 4-arg
     * constructor and get an empty skills list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PublicAgent(
        @JsonProperty("label") String label,
        @JsonProperty("name") String name,
        @JsonProperty("role") String role,
        @JsonProperty("description") String description,
        @JsonProperty("skills") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> skills
    ) {
        /** Back-compat: no skills list. */
        public PublicAgent(String label, String name, String role, String description) {
            this(label, name, role, description, null);
        }
    }

    /**
     * Per-tier caps. {@link #costCuPerRequest} is the ComputeUnit cost
     * an agent pays per inference call under this tier — advertised so
     * agents can filter by cost before proposing federation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierCaps(
        @JsonProperty("ttl") String ttl,
        @JsonProperty("caps") Map<String, Object> caps,
        @JsonProperty("cost_cu_per_request") @JsonInclude(JsonInclude.Include.NON_NULL) Integer costCuPerRequest
    ) {
        /** Back-compat: no cost advertised. */
        public TierCaps(String ttl, Map<String, Object> caps) {
            this(ttl, caps, null);
        }
    }

    /**
     * Contact semantics. {@link #endpoint} is the zone's public HTTPS
     * URL (used to fetch {@code .well-known/wyrd-zone} and open direct
     * connections when reachable). {@link #relay} is the fallback
     * {@code relay://host/routing-id} hint for NAT'd zones — peers
     * reach the zone through the named relay's session layer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(
        @JsonProperty("endpoint") @JsonInclude(JsonInclude.Include.NON_NULL) String endpoint,
        @JsonProperty("relay") @JsonInclude(JsonInclude.Include.NON_NULL) String relay,
        @JsonProperty("proposal_subject") String proposalSubject,
        @JsonProperty("response_expected") String responseExpected,
        @JsonProperty("knock_guidance") String knockGuidance
    ) {
        /** Back-compat: no endpoint/relay hints. */
        public Contact(String proposalSubject, String responseExpected, String knockGuidance) {
            this(null, null, proposalSubject, responseExpected, knockGuidance);
        }
    }

    /**
     * Aggregated reputation surface (§5.3, §5.10). Populated from
     * {@code AttestationService} at publish time. {@link #score} is a
     * 0-1 weighted average; {@link #samples} counts contributing
     * attestations; {@link #categories} breaks the score out by
     * category (e.g., "reliable", "helpful", "honest") so agents can
     * rank by the axis they care about.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reputation(
        @JsonProperty("score") double score,
        @JsonProperty("samples") int samples,
        @JsonProperty("categories") @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Double> categories
    ) {}

    /**
     * A top-weight attestation from another household. Manifests carry
     * only the top ~5 by weight to fit the size budget; the full list
     * is queryable from a rendezvous's per-DID attestation endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attestation(
        @JsonProperty("from") String from,
        @JsonProperty("category") String category,
        @JsonProperty("weight") double weight,
        @JsonProperty("at") String at
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Enforce spec §5.2 invariants. Throws {@link IllegalStateException}
     * with a caller-visible reason on any violation.
     */
    public void validate() {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalStateException(
                "unsupported schema_version: '" + schemaVersion + "' (expected "
                    + SCHEMA_VERSION + ")");
        }
        if (did == null || !did.startsWith(HouseholdIdentity.DID_SCHEME)) {
            throw new IllegalStateException("did must start with "
                + HouseholdIdentity.DID_SCHEME + ": " + did);
        }
        ZoneLabels.requireValid(zoneLabel, "zoneLabel");
        if (tagline != null && tagline.length() > TAGLINE_MAX) {
            throw new IllegalStateException(
                "tagline must be <= " + TAGLINE_MAX + " chars (got " + tagline.length() + ")");
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new IllegalStateException(
                "description must be <= " + DESCRIPTION_MAX + " chars (got "
                    + description.length() + ")");
        }
    }

    /**
     * Serialize to JSON bytes. Rejects if the result exceeds
     * {@link #SIZE_HARD_CAP_BYTES} — caller must trim fields (usually
     * description, tag list) and retry. Throws on serialization failure.
     */
    public byte[] toJsonBytes() {
        validate();
        try {
            var bytes = MAPPER.writeValueAsBytes(this);
            if (bytes.length > SIZE_HARD_CAP_BYTES) {
                throw new IllegalStateException(
                    "manifest exceeds hard cap: " + bytes.length + " > "
                        + SIZE_HARD_CAP_BYTES + " bytes. Trim description/tags or move room "
                        + "catalog behind the optional mcp_endpoint (SPEC §5.2 'Size budget').");
            }
            return bytes;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize manifest: " + e.getMessage(), e);
        }
    }

    /** Serialize to JSON string (UTF-8). See {@link #toJsonBytes()}. */
    public String toJsonString() {
        return new String(toJsonBytes(), StandardCharsets.UTF_8);
    }

    /**
     * @return estimated byte size of the JSON form, without rejecting if
     *     over cap. Used by operators previewing a manifest before publish.
     */
    public int estimatedSizeBytes() {
        try {
            return MAPPER.writeValueAsBytes(this).length;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parse a manifest from JSON bytes. Rejects unknown {@code schema_version}. */
    public static ZoneManifestV1 fromJsonBytes(byte[] data) {
        try {
            var m = MAPPER.readValue(data, ZoneManifestV1.class);
            m.validate();
            return m;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse manifest: " + e.getMessage(), e);
        }
    }

    /** Parse from a JSON string (UTF-8). */
    public static ZoneManifestV1 fromJsonString(String json) {
        return fromJsonBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the bytes that a signature covers — every field except
     * {@link #signature} itself. Callers sign these bytes with the
     * household keypair then set {@link #signature}. Signature verify uses
     * the same bytes.
     */
    public byte[] signingBytes() {
        var unsigned = new ZoneManifestV1(
            schemaVersion, did, zoneLabel, displayName, icon,
            tagline, description, tags, capabilities, contact,
            mcpEndpoint, agreementsCount, reputation, attestations,
            createdAt, refreshedAt,
            null  // strip signature
        );
        try {
            return MAPPER.writeValueAsBytes(unsigned);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute signing bytes: "
                + e.getMessage(), e);
        }
    }

    /** @return a copy with {@link #signature} replaced. Useful post-sign. */
    public ZoneManifestV1 withSignature(String sig) {
        return new ZoneManifestV1(
            schemaVersion, did, zoneLabel, displayName, icon,
            tagline, description, tags, capabilities, contact,
            mcpEndpoint, agreementsCount, reputation, attestations,
            createdAt, refreshedAt, sig);
    }

    /** @return a copy with reputation/attestations replaced. Used at publish time. */
    public ZoneManifestV1 withReputation(Reputation rep, List<Attestation> atts) {
        return new ZoneManifestV1(
            schemaVersion, did, zoneLabel, displayName, icon,
            tagline, description, tags, capabilities, contact,
            mcpEndpoint, agreementsCount, rep, atts,
            createdAt, refreshedAt, signature);
    }
}
