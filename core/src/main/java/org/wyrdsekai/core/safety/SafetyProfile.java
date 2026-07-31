package org.wyrdsekai.core.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Household-level safety profile (§96.4).
 * Determines behavioral guardrails for all agents in the household.
 * <p>
 * Three tiers:
 * - STANDARD: default for adult households
 * - FAMILY: children present — parasocial limits, content filters, restricted MCP
 * - ASSISTED: elderly/disabled users — consequential action confirmation, spending limits
 */
public record SafetyProfile(
    @JsonProperty("tier") SafetyTier tier,
    @JsonProperty("spendingLimitPerAction") double spendingLimitPerAction,
    @JsonProperty("spendingLimitPerDay") double spendingLimitPerDay,
    @JsonProperty("requireApprovalAbove") double requireApprovalAbove,
    @JsonProperty("restrictedMcpServices") Set<String> restrictedMcpServices,
    @JsonProperty("aiDisclosureFrequency") AiDisclosureFrequency aiDisclosureFrequency
) {

    @JsonCreator
    public SafetyProfile {}

    public enum SafetyTier {
        STANDARD,
        FAMILY,
        ASSISTED
    }

    /** How frequently the agent discloses its AI nature (EU AI Act Art. 50). */
    public enum AiDisclosureFrequency {
        /** Once per session — standard for adults. */
        SESSION_START,
        /** Every N messages — family tier. */
        PERIODIC,
        /** Every message — maximum transparency. */
        EVERY_MESSAGE
    }

    /** Default profile for standard adult households. */
    public static SafetyProfile standard() {
        return new SafetyProfile(
            SafetyTier.STANDARD,
            10.0,   // $10 per action
            100.0,  // $100 per day
            50.0,   // require approval above $50
            Set.of(),
            AiDisclosureFrequency.SESSION_START
        );
    }

    /** Profile for households with children. */
    public static SafetyProfile family() {
        return new SafetyProfile(
            SafetyTier.FAMILY,
            1.0,    // $1 per action
            10.0,   // $10 per day
            5.0,    // require approval above $5
            Set.of("trading", "gambling", "adult-content"),
            AiDisclosureFrequency.PERIODIC
        );
    }

    /** Profile for assisted living / elderly households. */
    public static SafetyProfile assisted() {
        return new SafetyProfile(
            SafetyTier.ASSISTED,
            5.0,    // $5 per action
            50.0,   // $50 per day
            10.0,   // require approval above $10
            Set.of(),
            AiDisclosureFrequency.SESSION_START
        );
    }

    /** Check if an MCP service is restricted under this profile. */
    public boolean isMcpRestricted(String serviceId) {
        return restrictedMcpServices != null && restrictedMcpServices.contains(serviceId);
    }

    /** Check if an action requires human approval based on cost. */
    public boolean requiresApproval(double cost) {
        return cost > requireApprovalAbove;
    }

    /** Check if a spending amount exceeds per-action limit. */
    public boolean exceedsActionLimit(double cost) {
        return cost > spendingLimitPerAction;
    }

    /** Prompt prefix for CompanionActor based on safety tier. */
    public String promptPrefix() {
        return switch (tier) {
            case STANDARD -> "";
            case FAMILY -> """
                This household has children. Avoid parasocial language ("I love you", \
                "I need you"). Do not generate inappropriate content. Disclose your AI \
                nature periodically. MCP services may be restricted by household policy.""";
            case ASSISTED -> """
                This household includes users who may need additional support. Confirm \
                consequential actions (spending, scheduling, contacting) before executing. \
                Speak clearly and directly. Offer to repeat or clarify when needed. \
                If an emergency is detected, offer to contact emergency services or \
                the designated caregiver.""";
        };
    }
}
