package org.wyrdsekai.core.agent;

import java.util.List;
import java.util.Set;

/**
 * Child companion mode (§15).
 * Restricts companion behavior when interacting with child accounts.
 * <p>
 * Restrictions:
 * - Topic filtering (violence, adult themes, financial transactions)
 * - Shorter response length (age-appropriate)
 * - No economy participation (credit transfers, trading)
 * - Enhanced content screening on all outputs
 * - Parental notification on flagged interactions
 * <p>
 * This is a configuration profile, not a separate actor.
 * CompanionActor checks isChildMode() to apply restrictions.
 */
public class ChildCompanionMode {

    /** Age bracket for content filtering. */
    public enum AgeBracket {
        UNDER_7(7, 100),     // very short, simple language
        UNDER_13(13, 250),   // moderate length, filtered topics
        UNDER_18(18, 500);   // near-full length, filtered adult content

        private final int maxAge;
        private final int maxResponseTokens;

        AgeBracket(int maxAge, int maxResponseTokens) {
            this.maxAge = maxAge;
            this.maxResponseTokens = maxResponseTokens;
        }

        public int maxAge() { return maxAge; }
        public int maxResponseTokens() { return maxResponseTokens; }

        public static AgeBracket forAge(int age) {
            if (age < 7) return UNDER_7;
            if (age < 13) return UNDER_13;
            return UNDER_18;
        }
    }

    /** Topics that are restricted for child accounts. */
    private static final Set<String> RESTRICTED_TOPICS = Set.of(
        "violence", "weapons", "drugs", "alcohol",
        "gambling", "financial_trading", "adult_content",
        "self_harm", "extremism"
    );

    /** Commands disabled for child accounts. */
    private static final Set<String> RESTRICTED_COMMANDS = Set.of(
        "trade", "transfer", "credit", "debit", "vote",
        "propose", "sanction", "federate"
    );

    private final String entityId;
    private final AgeBracket ageBracket;
    private final String parentEntityId;
    private final boolean active;

    public ChildCompanionMode(String entityId, AgeBracket ageBracket,
                               String parentEntityId) {
        this(entityId, ageBracket, parentEntityId, true);
    }

    public ChildCompanionMode(String entityId, AgeBracket ageBracket,
                               String parentEntityId, boolean active) {
        this.entityId = entityId;
        this.ageBracket = ageBracket;
        this.parentEntityId = parentEntityId;
        this.active = active;
    }

    /** Whether child mode restrictions are active. */
    public boolean isActive() { return active; }

    /** The entity this mode applies to. */
    public String entityId() { return entityId; }

    /** The age bracket for content filtering. */
    public AgeBracket ageBracket() { return ageBracket; }

    /** The parent/guardian who controls this child's account. */
    public String parentEntityId() { return parentEntityId; }

    /** Maximum response tokens for the companion. */
    public int maxResponseTokens() { return ageBracket.maxResponseTokens(); }

    /** Check if a topic is allowed for this child. */
    public boolean isTopicAllowed(String topic) {
        if (!active) return true;
        return !RESTRICTED_TOPICS.contains(topic.toLowerCase());
    }

    /** Check if a command is allowed for this child. */
    public boolean isCommandAllowed(String command) {
        if (!active) return true;
        return !RESTRICTED_COMMANDS.contains(command.toLowerCase());
    }

    /** Get the list of restricted topics. */
    public static Set<String> restrictedTopics() {
        return RESTRICTED_TOPICS;
    }

    /** Get the list of restricted commands. */
    public static Set<String> restrictedCommands() {
        return RESTRICTED_COMMANDS;
    }

    /** System prompt addendum for child-safe companion. */
    public String systemPromptAddendum() {
        if (!active) return "";
        return switch (ageBracket) {
            case UNDER_7 -> """
                You are speaking with a young child. Use simple words, short sentences, \
                and a warm, encouraging tone. Never mention violence, scary things, or \
                adult topics. Keep responses under 3 sentences.""";
            case UNDER_13 -> """
                You are speaking with a child. Use age-appropriate language. \
                Avoid violence, adult themes, financial topics, and anything that could \
                be frightening. Be encouraging and educational.""";
            case UNDER_18 -> """
                You are speaking with a teenager. Be respectful and age-appropriate. \
                Avoid explicit content, financial trading, and harmful topics. \
                You may discuss more complex subjects at an appropriate level.""";
        };
    }

    /** Get restricted topics as a formatted list for prompt injection. */
    public List<String> promptRestrictions() {
        if (!active) return List.of();
        return List.of(
            "Do not discuss: " + String.join(", ", RESTRICTED_TOPICS),
            "Maximum response length: " + maxResponseTokens() + " tokens",
            "Notify parent (" + parentEntityId + ") if concerning interaction detected"
        );
    }
}
