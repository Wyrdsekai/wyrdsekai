package org.wyrdsekai.core.story;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D.4 (and B.6.polish) — placeholder substitutor for
 * body-language templates and scene-summary felt synthesis.
 *
 * <p>The B.6 live verify surfaced a cosmetic grammar nit: the seed template
 * was {@code "The chair creaks softly as they lean back, watching the embers."}
 * RoomActor.onSetPosture naively replaced "they" with the actor's name,
 * yielding {@code "...wyrd-embodiment-test lean back..."} — wrong subject-verb
 * agreement for a 3rd-person singular name.</p>
 *
 * <p>This substitutor lets templates use explicit placeholders so verbs stay
 * correctly inflected regardless of substitution:</p>
 *
 * <ul>
 *   <li>{@code {actor}} — the actor's display name (or "they" as fallback)</li>
 *   <li>{@code {they}} / {@code {Actor}} — subject pronoun (Title case version capitalized)</li>
 *   <li>{@code {them}} — object pronoun</li>
 *   <li>{@code {their}} — possessive determiner</li>
 *   <li>{@code {theirs}} — possessive pronoun</li>
 *   <li>{@code {themself}} — reflexive</li>
 * </ul>
 *
 * <p>Templates SHOULD use {@code {actor}} as the subject when the verb
 * should agree with a singular name ("the chair creaks softly as {actor}
 * leans back"); use {@code {they}} when the sentence reads more naturally
 * with pronouns ("their eyes drifted to the window").</p>
 *
 * <p>Pronouns default to they/them/their/themself; entities may override
 * via stored pronouns (future plumbing — see {@link Pronouns#DEFAULT}).</p>
 */
public final class PostureTemplates {

    private PostureTemplates() {}

    /**
     * A pronoun set. Default is they/them/their/themself, which works for
     * any actor that hasn't declared otherwise.
     */
    public record Pronouns(
        String subject,       // they
        String object,        // them
        String possessive,    // their
        String absolutePossessive, // theirs
        String reflexive      // themself
    ) {
        public static final Pronouns DEFAULT = new Pronouns("they", "them", "their", "theirs", "themself");
        public static final Pronouns SHE_HER = new Pronouns("she", "her", "her", "hers", "herself");
        public static final Pronouns HE_HIM  = new Pronouns("he", "him", "his", "his", "himself");
        public static final Pronouns IT_ITS  = new Pronouns("it", "it", "its", "its", "itself");
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(actor|Actor|they|They|them|Them|their|Their|theirs|Theirs|themself|Themself)\\}");

    /**
     * Substitute placeholders. If {@code template} is null/blank, returns it
     * unchanged. Unknown placeholders pass through verbatim.
     *
     * @param template the template string with {placeholders}
     * @param actorName the actor's display name (used for {@code {actor}}/{@code {Actor}});
     *                  may be null, in which case the subject pronoun is used as
     *                  the {actor} fallback
     * @param pronouns the pronoun set; null = {@link Pronouns#DEFAULT}
     */
    public static String substitute(String template, String actorName, Pronouns pronouns) {
        if (template == null || template.isEmpty()) return template;
        var p = pronouns == null ? Pronouns.DEFAULT : pronouns;
        var name = actorName == null || actorName.isBlank() ? p.subject() : actorName;
        var titleName = capitalize(name);

        var m = PLACEHOLDER.matcher(template);
        var sb = new StringBuilder();
        while (m.find()) {
            var token = m.group(1);
            var replacement = switch (token) {
                case "actor"     -> name;
                case "Actor"     -> titleName;
                case "they"      -> p.subject();
                case "They"      -> capitalize(p.subject());
                case "them"      -> p.object();
                case "Them"      -> capitalize(p.object());
                case "their"     -> p.possessive();
                case "Their"     -> capitalize(p.possessive());
                case "theirs"    -> p.absolutePossessive();
                case "Theirs"    -> capitalize(p.absolutePossessive());
                case "themself"  -> p.reflexive();
                case "Themself"  -> capitalize(p.reflexive());
                default          -> m.group(0);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Convenience: substitute with a pronouns map (for future entity-stored
     * pronouns). Returns substitute() with Pronouns.DEFAULT if the map is
     * null or doesn't carry pronoun keys.
     */
    public static String substitute(String template, String actorName, Map<String, String> pronounsMap) {
        if (pronounsMap == null || pronounsMap.isEmpty()) {
            return substitute(template, actorName, Pronouns.DEFAULT);
        }
        var p = new Pronouns(
            pronounsMap.getOrDefault("subject", "they"),
            pronounsMap.getOrDefault("object", "them"),
            pronounsMap.getOrDefault("possessive", "their"),
            pronounsMap.getOrDefault("absolutePossessive", "theirs"),
            pronounsMap.getOrDefault("reflexive", "themself"));
        return substitute(template, actorName, p);
    }

    /**
     * Legacy compatibility: substitute literal "they" → actor name with a
     * minimal grammar correction (the immediately-following bare verb form
     * gets an -s suffix when followed by " lean", " stand", " sit", " look",
     * " glance", " watch", " smile", " nod", " lean", " settle", " stretch").
     * Used by RoomActor for templates that haven't been migrated to
     * {actor}/{they} placeholders yet.
     */
    public static String legacyNameSwap(String template, String actorName) {
        if (template == null || actorName == null) return template;
        // Quick lowercase 3rd-person-singular correction for common verbs after
        // a bare "they" subject. We match `\\bthey (verb)\\b` and emit `name verbs`.
        var pattern = Pattern.compile(
            "\\bthey ((?:lean|stand|sit|look|glance|watch|smile|nod|settle|stretch|move|step|turn|reach|breathe|blink|listen|wait|pause|frown|laugh|sigh|shrug|shift|gaze|stare|rise|kneel|bend|whisper|speak|tilt|cross|fold))(\\b)",
            Pattern.CASE_INSENSITIVE);
        var m = pattern.matcher(template);
        var sb = new StringBuilder();
        while (m.find()) {
            var verb = m.group(1);
            var tail = m.group(2);
            m.appendReplacement(sb,
                Matcher.quoteReplacement(actorName + " " + thirdPersonSingular(verb) + tail));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Apply 3rd-person-singular -s rule (lean→leans, watch→watches, etc.). */
    static String thirdPersonSingular(String verb) {
        if (verb == null || verb.isEmpty()) return verb;
        var lower = verb.toLowerCase();
        // -es ending: -s -ss -sh -ch -x -z, plus "go"
        if (lower.endsWith("s") || lower.endsWith("sh") || lower.endsWith("ch")
                || lower.endsWith("x") || lower.endsWith("z") || lower.equals("go")) {
            return verb + "es";
        }
        // -y after consonant → -ies (e.g. try → tries). Skip for vowel+y (stay → stays).
        if (lower.endsWith("y") && lower.length() > 1) {
            var prev = lower.charAt(lower.length() - 2);
            if ("aeiou".indexOf(prev) < 0) {
                return verb.substring(0, verb.length() - 1) + "ies";
            }
        }
        return verb + "s";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
