package org.wyrdsekai.core.companion;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Child profile with graduated age brackets (§100).
 * 5 brackets from seedling to tree, each with guardrails.
 * Child has privacy — no transcript access for parent at any bracket.
 */
public class ChildProfile {

    /** Age brackets — plant metaphor (§100.2). */
    public enum AgeBracket {
        /** Under 5. Maximum guardrails, very simple language. */
        SEEDLING(0, 4, 30, 100, true),
        /** 5-8. Playful, educational, moderate guardrails. */
        SPROUT(5, 8, 45, 200, true),
        /** 9-12. More complex topics, some autonomy. */
        SAPLING(9, 12, 60, 350, true),
        /** 13-15. Significant autonomy, privacy important. */
        YOUNG_TREE(13, 15, 90, 500, false),
        /** 16-17. Near-adult autonomy. Companion grows with child. */
        TREE(16, 17, 120, 700, false);

        private final int minAge;
        private final int maxAge;
        private final int sessionMinutes;
        private final int maxResponseTokens;
        private final boolean requiresBreaks;

        AgeBracket(int minAge, int maxAge, int sessionMinutes,
                   int maxResponseTokens, boolean requiresBreaks) {
            this.minAge = minAge;
            this.maxAge = maxAge;
            this.sessionMinutes = sessionMinutes;
            this.maxResponseTokens = maxResponseTokens;
            this.requiresBreaks = requiresBreaks;
        }

        public int minAge() { return minAge; }
        public int maxAge() { return maxAge; }
        public int sessionMinutes() { return sessionMinutes; }
        public int maxResponseTokens() { return maxResponseTokens; }
        public boolean requiresBreaks() { return requiresBreaks; }

        public static AgeBracket forAge(int age) {
            for (var bracket : values()) {
                if (age >= bracket.minAge && age <= bracket.maxAge) return bracket;
            }
            return age < 0 ? SEEDLING : TREE;
        }
    }

    /** Topics restricted by bracket. */
    private static final Map<AgeBracket, Set<String>> TOPIC_RESTRICTIONS = Map.of(
        AgeBracket.SEEDLING, Set.of("violence", "death", "scary", "adult", "drugs",
            "self_harm", "weapons", "financial", "politics", "religion_debate"),
        AgeBracket.SPROUT, Set.of("violence", "death_graphic", "adult", "drugs",
            "self_harm", "weapons", "financial", "politics"),
        AgeBracket.SAPLING, Set.of("violence_graphic", "adult", "drugs_use",
            "self_harm", "weapons_detailed", "financial_trading"),
        AgeBracket.YOUNG_TREE, Set.of("adult_explicit", "drugs_instructions",
            "self_harm_methods", "weapons_construction"),
        AgeBracket.TREE, Set.of("adult_explicit", "self_harm_methods",
            "weapons_construction")
    );

    private final String childDid;
    private final String parentDid;
    private final int age;
    private final AgeBracket bracket;
    private final List<String> trustedAdultDids;
    private final boolean journalEnabled;

    public ChildProfile(String childDid, String parentDid, int age,
                         List<String> trustedAdultDids, boolean journalEnabled) {
        this.childDid = childDid;
        this.parentDid = parentDid;
        this.age = age;
        this.bracket = AgeBracket.forAge(age);
        this.trustedAdultDids = trustedAdultDids != null ? List.copyOf(trustedAdultDids) : List.of();
        this.journalEnabled = journalEnabled;
    }

    public String childDid() { return childDid; }
    public String parentDid() { return parentDid; }
    public int age() { return age; }
    public AgeBracket bracket() { return bracket; }
    public List<String> trustedAdultDids() { return trustedAdultDids; }
    public boolean journalEnabled() { return journalEnabled; }
    public int sessionMinutes() { return bracket.sessionMinutes(); }
    public int maxResponseTokens() { return bracket.maxResponseTokens(); }

    /** Check if a topic is allowed for this child's bracket. */
    public boolean isTopicAllowed(String topic) {
        var restricted = TOPIC_RESTRICTIONS.get(bracket);
        return restricted == null || !restricted.contains(topic.toLowerCase());
    }

    /** Get the restricted topics for this bracket. */
    public Set<String> restrictedTopics() {
        return TOPIC_RESTRICTIONS.getOrDefault(bracket, Set.of());
    }

    /** Whether the child has own DID (always true — §100). */
    public boolean hasOwnDid() { return true; }

    /** Whether parent can access conversation transcripts (always false — §100). */
    public boolean parentCanAccessTranscripts() { return false; }

    /** System prompt addendum for this bracket. */
    public String promptAddendum() {
        return switch (bracket) {
            case SEEDLING -> """
                You are talking with a very young child. Use the simplest words possible. \
                Be warm, playful, and encouraging. Keep responses to 1-2 sentences. \
                Never mention anything scary, sad, or complex.""";
            case SPROUT -> """
                You are talking with a young child. Use simple, clear language. \
                Be playful and educational. Keep responses short and engaging. \
                Encourage curiosity. Avoid scary or complex topics.""";
            case SAPLING -> """
                You are talking with a child. Be engaging and age-appropriate. \
                You can discuss more complex topics at a suitable level. \
                Be encouraging and supportive. Avoid graphic or adult content.""";
            case YOUNG_TREE -> """
                You are talking with a teenager. Be respectful of their growing autonomy. \
                You can discuss complex topics thoughtfully. Their privacy matters. \
                Be supportive without being patronizing.""";
            case TREE -> """
                You are talking with an older teenager. Treat them with near-adult respect. \
                They can handle complex discussions. Support their independence. \
                Be a thoughtful companion, not a substitute parent.""";
        };
    }
}
