package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authentication requirements for a skill.
 *
 * @param credentialKey Key name in The Safe for retrieving the credential
 * @param type          Auth mechanism type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillAuth(
    @JsonProperty("credentialKey") String credentialKey,
    @JsonProperty("type") AuthType type
) {
    @JsonCreator
    public SkillAuth {}

    /** No auth required (local services). */
    public static final SkillAuth NONE = new SkillAuth(null, AuthType.NONE);

    public static SkillAuth apiKey(String safeKey) {
        return new SkillAuth(safeKey, AuthType.API_KEY);
    }

    public static SkillAuth oauth(String safeKey) {
        return new SkillAuth(safeKey, AuthType.OAUTH_DEVICE_FLOW);
    }

    public static SkillAuth localBridge(String safeKey) {
        return new SkillAuth(safeKey, AuthType.LOCAL_BRIDGE);
    }
}
