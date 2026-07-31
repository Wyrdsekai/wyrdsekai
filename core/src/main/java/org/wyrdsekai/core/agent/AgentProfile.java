package org.wyrdsekai.core.agent;

/**
 * Agent identity and LLM parameters.
 * Simplified from CodePlane's AgentDefinition — no family, tools, permissions, wiring.
 * M1+ will load these from YAML files (AgentProfileLoader).
 *
 * @param name               Display name (visible in room entity list)
 * @param entityId           Unique entity ID in rooms
 * @param entityType         Entity type ("agent")
 * @param description        Short description for entity list
 * @param systemPrompt       Full system prompt (personality, capabilities, constraints)
 * @param contextWindowTokens Model's context window size
 * @param maxResponseTokens  Max tokens to generate per response
 * @param temperature        Sampling temperature
 * @param did                DID:key identifier (nullable — assigned when AgentIdentity is generated, §85.1)
 * @param archetype          Agent archetype name (nullable — scholar/guardian/artisan/diplomat/
 *                           explorer/steward). When set, the companion is born with that
 *                           archetype's drive temperament ({@code DriveEngine.forArchetype}) and
 *                           tank genome ({@code GenomeProfile.forArchetype}) so it lives, decays,
 *                           and acts as a distinct individual. Null → the neutral default (no
 *                           temperament), unchanged behaviour.
 */
public record AgentProfile(
    String name,
    String entityId,
    String entityType,
    String description,
    String systemPrompt,
    int contextWindowTokens,
    int maxResponseTokens,
    double temperature,
    String did,
    String archetype
) {
    /** Backward-compatible constructor without DID or archetype. */
    public AgentProfile(String name, String entityId, String entityType, String description,
                        String systemPrompt, int contextWindowTokens, int maxResponseTokens,
                        double temperature) {
        this(name, entityId, entityType, description, systemPrompt,
             contextWindowTokens, maxResponseTokens, temperature, null, null);
    }

    /** Backward-compatible constructor with DID but no archetype. */
    public AgentProfile(String name, String entityId, String entityType, String description,
                        String systemPrompt, int contextWindowTokens, int maxResponseTokens,
                        double temperature, String did) {
        this(name, entityId, entityType, description, systemPrompt,
             contextWindowTokens, maxResponseTokens, temperature, did, null);
    }

    /** Return a copy with the DID set (preserves archetype). */
    public AgentProfile withDid(String did) {
        return new AgentProfile(name, entityId, entityType, description, systemPrompt,
            contextWindowTokens, maxResponseTokens, temperature, did, archetype);
    }

    /** Return a copy with the archetype set (preserves DID). */
    public AgentProfile withArchetype(String archetype) {
        return new AgentProfile(name, entityId, entityType, description, systemPrompt,
            contextWindowTokens, maxResponseTokens, temperature, did, archetype);
    }

    /**
     * Return a copy renamed to {@code newName}. The system prompt's
     * self-references follow the name; entityId and DID stay — a rename
     * changes what the companion answers to, not who it is
     */
    public AgentProfile withName(String newName) {
        return new AgentProfile(newName, entityId, entityType, description,
            systemPrompt == null ? null : systemPrompt.replace(name, newName),
            contextWindowTokens, maxResponseTokens, temperature, did, archetype);
    }
}
