package org.wyrdsekai.core.interop;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes A2A Agent Cards for Wyrdsekai agents (§97.4).
 * Agent Cards are JSON documents describing capabilities, endpoints,
 * and authentication for external discovery.
 * <p>
 * Cards are opt-in. Households can be completely dark (no published cards),
 * selectively visible (local network), or public.
 */
public class AgentCardPublisher {

    /** A2A Agent Card. */
    public record AgentCard(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("url") String url,
        @JsonProperty("provider") Provider provider,
        @JsonProperty("version") String version,
        @JsonProperty("capabilities") Capabilities capabilities,
        @JsonProperty("authentication") Authentication authentication,
        @JsonProperty("defaultInputModes") List<String> defaultInputModes,
        @JsonProperty("defaultOutputModes") List<String> defaultOutputModes,
        @JsonProperty("skills") List<Skill> skills,
        @JsonProperty("extensions") List<Extension> extensions
    ) {
        @JsonCreator
        public AgentCard {}
    }

    public record Provider(
        @JsonProperty("organization") String organization,
        @JsonProperty("url") String url
    ) {
        @JsonCreator
        public Provider {}
    }

    public record Capabilities(
        @JsonProperty("streaming") boolean streaming,
        @JsonProperty("pushNotifications") boolean pushNotifications
    ) {
        @JsonCreator
        public Capabilities {}
    }

    public record Authentication(
        @JsonProperty("schemes") List<String> schemes
    ) {
        @JsonCreator
        public Authentication {}
    }

    public record Skill(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description
    ) {
        @JsonCreator
        public Skill {}
    }

    /** A2A extension declaration (Wyrdsekai-specific). */
    public record Extension(
        @JsonProperty("uri") String uri,
        @JsonProperty("description") String description,
        @JsonProperty("required") boolean required,
        @JsonProperty("params") Map<String, Object> params
    ) {
        @JsonCreator
        public Extension {}
    }

    /** Wyrdsekai A2A extension URIs. */
    public static final String EXT_SOUL_IDENTITY = "https://wyrdsekai.org/a2a/soul-identity/v1";
    public static final String EXT_SOUL_EXCHANGE = "https://wyrdsekai.org/a2a/soul-exchange/v1";
    public static final String EXT_VITALITY = "https://wyrdsekai.org/a2a/vitality/v1";
    public static final String EXT_SIGNED_MESSAGES = "https://wyrdsekai.org/a2a/signed-messages/v1";

    private final String householdName;
    private final String householdUrl;

    public AgentCardPublisher(String householdName, String householdUrl) {
        this.householdName = householdName;
        this.householdUrl = householdUrl;
    }

    /**
     * Generate an Agent Card for an agent.
     *
     * @param agentName   display name
     * @param description agent personality/purpose description
     * @param skills      list of skills to advertise
     * @param streaming   whether streaming is supported
     * @return the Agent Card
     */
    public AgentCard publish(String agentName, String description,
                              List<Skill> skills, boolean streaming) {
        var extensions = List.of(
            soulIdentityExtension(),
            vitalityExtension(),
            signedMessagesExtension()
        );

        return new AgentCard(
            agentName,
            description,
            householdUrl + "/.well-known/agent.json",
            new Provider(householdName, householdUrl),
            "1.0",
            new Capabilities(streaming, true),
            new Authentication(List.of("did-ed25519")),
            List.of("text/plain"),
            List.of("text/plain"),
            skills != null ? List.copyOf(skills) : List.of(),
            extensions
        );
    }

    /** Generate a card with soul exchange enabled (for trusted+ tiers). */
    public AgentCard publishWithExchange(String agentName, String description,
                                          List<Skill> skills, boolean streaming) {
        var base = publish(agentName, description, skills, streaming);
        var extensions = new ArrayList<>(base.extensions());
        extensions.add(soulExchangeExtension());
        return new AgentCard(
            base.name(), base.description(), base.url(), base.provider(),
            base.version(), base.capabilities(), base.authentication(),
            base.defaultInputModes(), base.defaultOutputModes(),
            base.skills(), List.copyOf(extensions)
        );
    }

    private Extension soulIdentityExtension() {
        return new Extension(EXT_SOUL_IDENTITY,
            "Read-only soul identity (redacted vitality)", false,
            Map.of("trustTierRequired", "verified"));
    }

    private Extension soulExchangeExtension() {
        return new Extension(EXT_SOUL_EXCHANGE,
            "Soul item exchange with quarantine", false,
            Map.of(
                "quarantine", true,
                "maxItemsPerSession", 5,
                "maxItemSizeBytes", 4096,
                "acceptedCategories", List.of("memory", "relationship", "skill"),
                "rejectedCategories", List.of("identity-core", "value"),
                "trustTierRequired", "trusted"
            ));
    }

    private Extension vitalityExtension() {
        return new Extension(EXT_VITALITY,
            "Coarse vitality status (healthy/resting/away)", false,
            Map.of("rawValues", false, "trustTierRequired", "verified"));
    }

    private Extension signedMessagesExtension() {
        return new Extension(EXT_SIGNED_MESSAGES,
            "Ed25519-signed message verification", false,
            Map.of("trustTierRequired", "trusted"));
    }
}
