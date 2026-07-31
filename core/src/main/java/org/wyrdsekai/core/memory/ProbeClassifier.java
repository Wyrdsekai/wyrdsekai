package org.wyrdsekai.core.memory;

import java.util.regex.Pattern;

/**
 * classifies recall-shape user questions into a
 * {@link ProbeIntent} that {@link MemoryEntityStore} can resolve via SQL.
 *
 * <p>Pattern-based. Low-cost filter: on match, {@link EntityResolver} runs a
 * single indexed query and returns a deterministic answer before Lucene
 * retrieval. On miss (null), V1 hybrid search runs unchanged.</p>
 *
 * <p>Patterns map directly to the 11 categories in {@link EntityExtractor}.</p>
 */
public final class ProbeClassifier {

    public enum Temporal { LATEST, EARLIEST, ANY }

    public record ProbeIntent(String entityType, String entityRole, Temporal temporal) {}

    private ProbeClassifier() {}

    // Patterns are grouped by entity_type. Order matters: more specific first.
    // All patterns match case-insensitively and tolerate leading/trailing prose
    // (via .*? and \\b anchors where needed).

    private record Rule(Pattern pattern, String entityType, String entityRole, Temporal temporal) {}

    private static Rule rule(String regex, String type, String role, Temporal temporal) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), type, role, temporal);
    }

    // Shared fragments reused across many rules:
    //   RECALL_OPEN catches natural-phrasing recall leads ("remind me", "do you
    //   remember", "tell me", "what did I say/tell/mention") that the narrow
    //   patterns missed. Rules that use these fragments require the entity
    //   keyword to still appear elsewhere — prevents false-positives on
    //   conversational openers like "tell me a story".
    private static final String RECALL_OPEN =
            "(?:what\\s+(?:did\\s+|have\\s+)?i\\s+(?:say|tell(?:\\s+you)?|mention|share)"
            + "|remind\\s+me(?:\\s+(?:what|about|of))?"
            + "|do\\s+you\\s+(?:remember|recall|know)"
            + "|tell\\s+me\\s+(?:about|what)"
            + "|what\\s+(?:was|is|were)\\s+(?:that|my|the))";

    private static final Rule[] RULES = new Rule[]{
            // PET
            rule("\\bwhat\\s+(?:was|is|were)\\s+(?:my|the)\\s+(?:cat|dog|pet)'?s?\\s+name\\b",
                    "pet", "name", Temporal.ANY),
            rule("\\bdo\\s+i\\s+have\\s+(?:a|any)\\s+(?:cat|dog|pet)",
                    "pet", null, Temporal.ANY),
            rule("\\bwhat\\s+kind\\s+of\\s+pet",
                    "pet", "type", Temporal.ANY),
            // Natural phrasings — "tell me about my cat", "remind me what my dog's name is",
            // "do you remember my pet", "what did I say about my dog"
            rule("\\btell\\s+me\\s+about\\s+my\\s+(?:cat|dog|pet|kitten|puppy)\\b",
                    "pet", null, Temporal.ANY),
            rule(RECALL_OPEN + ".*\\b(?:cat|dog|pet|kitten|puppy)\\b",
                    "pet", null, Temporal.ANY),
            rule("\\bmy\\s+(?:cat|dog|pet|kitten|puppy)['\\s].*name\\b",
                    "pet", "name", Temporal.ANY),

            // OCCUPATION
            rule("\\bwhat(?:'s|\\s+is)\\s+my\\s+(?:current\\s+)?(?:job|occupation|work|role|profession)",
                    "occupation", "current", Temporal.LATEST),
            rule("\\bwhat\\s+do\\s+i\\s+do\\s+for\\s+(?:a\\s+)?(?:living|work)",
                    "occupation", "current", Temporal.LATEST),
            rule("\\bwhere\\s+do\\s+i\\s+work",
                    "occupation", "current", Temporal.LATEST),
            // "tell me about my job", "remind me what I do for work", "what did I say about work"
            rule("\\btell\\s+me\\s+about\\s+my\\s+(?:job|work|career)\\b",
                    "occupation", "current", Temporal.LATEST),
            rule(RECALL_OPEN + ".*\\b(?:job|work|career|occupation|profession)\\b",
                    "occupation", "current", Temporal.LATEST),

            // LOCATION (hometown)
            rule("\\bwhere\\s+did\\s+i\\s+grow\\s+up",
                    "location", "hometown", Temporal.ANY),
            rule("\\bwhere(?:'s|\\s+is)\\s+my\\s+home(?:town)?",
                    "location", "hometown", Temporal.ANY),
            rule("\\bwhere\\s+am\\s+i\\s+from",
                    "location", "hometown", Temporal.ANY),
            rule(RECALL_OPEN + ".*\\b(?:hometown|grew\\s+up|home\\s+town)\\b",
                    "location", "hometown", Temporal.ANY),

            // LOCATION (current)
            rule("\\bwhere\\s+do\\s+i\\s+(?:live|currently\\s+live)",
                    "location", "current", Temporal.LATEST),
            rule("\\bwhat\\s+city\\s+(?:am\\s+i\\s+in|do\\s+i\\s+live\\s+in)",
                    "location", "current", Temporal.LATEST),

            // BOOK
            rule("\\bwhat\\s+book\\s+(?:am\\s+i|was\\s+i|have\\s+i\\s+been)\\s+reading",
                    "book", "reading", Temporal.LATEST),
            rule("\\bwhat(?:'m|\\s+am)\\s+i\\s+reading",
                    "book", "reading", Temporal.LATEST),
            // "remind me what I'm reading", "what book did I mention"
            rule(RECALL_OPEN + ".*\\b(?:book|reading|novel)\\b",
                    "book", "reading", Temporal.LATEST),

            // ALLERGY
            rule("\\bdo\\s+i\\s+have\\s+any\\s+(?:food\\s+)?allerg(?:y|ies)",
                    "allergy", null, Temporal.ANY),
            rule("\\bwhat(?:'m|\\s+am)\\s+i\\s+allergic\\s+to",
                    "allergy", null, Temporal.ANY),
            // "did I mention my allergy", "remind me about my allergies"
            rule("\\bdid\\s+i\\s+mention\\s+(?:my\\s+)?allerg",
                    "allergy", null, Temporal.ANY),
            rule(RECALL_OPEN + ".*\\ballerg",
                    "allergy", null, Temporal.ANY),

            // FAMILY / VISIT
            rule("\\bis\\s+(?:anyone|anybody)\\s+(?:coming\\s+to\\s+)?visit",
                    "family", null, Temporal.LATEST),
            rule("\\bwho(?:'s|\\s+is)\\s+(?:coming|visiting)\\s+(?:to\\s+visit|this\\s+weekend|soon|next)",
                    "family", null, Temporal.LATEST),
            // "who's coming over", "any visitors this weekend"
            rule("\\bwho(?:'s|\\s+is)\\s+coming\\s+over\\b",
                    "family", null, Temporal.LATEST),
            rule("\\bany\\s+(?:visitors|guests)\\s+(?:coming|soon|this\\s+weekend|next)\\b",
                    "family", null, Temporal.LATEST),
            rule(RECALL_OPEN + ".*\\b(?:sister|brother|mom|dad|mother|father|family)\\b.*\\b(?:visit|coming)\\b",
                    "family", null, Temporal.LATEST),

            // LANGUAGE
            rule("\\bwhat\\s+language\\s+(?:am\\s+i|was\\s+i|have\\s+i\\s+been)\\s+(?:learning|trying\\s+to\\s+learn)",
                    "language", "learning", Temporal.LATEST),
            rule("\\bwhat(?:'m|\\s+am)\\s+i\\s+learning",
                    "language", "learning", Temporal.LATEST),
            rule(RECALL_OPEN + ".*\\b(?:language|learning)\\b",
                    "language", "learning", Temporal.LATEST),
            rule("\\bam\\s+i\\s+(?:still\\s+)?(?:learning|studying)\\b",
                    "language", "learning", Temporal.LATEST),

            // VENUE
            rule("\\bwhat(?:'s|\\s+is)\\s+(?:the\\s+name\\s+of\\s+)?(?:the\\s+|my\\s+favorite\\s+)?coffee\\s+shop",
                    "venue", "favorite", Temporal.ANY),
            rule("\\bwhat(?:'s|\\s+is)\\s+my\\s+favorite\\s+(?:bar|restaurant|cafe|venue|place)",
                    "venue", "favorite", Temporal.ANY),
            // "tell me about my favorite cafe", "remind me about that coffee shop"
            rule("\\btell\\s+me\\s+about\\s+(?:my\\s+favorite\\s+|that\\s+)?(?:bar|restaurant|cafe|coffee\\s+shop|venue)\\b",
                    "venue", "favorite", Temporal.ANY),
            rule(RECALL_OPEN + ".*\\b(?:coffee\\s+shop|cafe|bar|restaurant|favorite\\s+place)\\b",
                    "venue", "favorite", Temporal.ANY),
    };

    /**
     * Classify a user question into a structured probe intent.
     *
     * @return ProbeIntent if the question matches a recall-shape pattern,
     *         or null if it should fall through to V1 Lucene retrieval.
     */
    public static ProbeIntent classify(String probeText) {
        if (probeText == null || probeText.isBlank()) return null;
        var normalized = probeText.trim();
        for (var rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return new ProbeIntent(rule.entityType(), rule.entityRole(), rule.temporal());
            }
        }
        return null;
    }
}
