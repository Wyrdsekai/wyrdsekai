package org.wyrdsekai.core.memory;

import org.wyrdsekai.core.identity.PersonIds;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a memory came from: who told it, and whether it was said privately.
 *
 * <p>The companion carried one person's private statements into other people's
 * conversations (audit W4, 2026-09-28): facts, working memory and the history were stored
 * with no teller, and every turn read all of them. Every memory now records its origin, and
 * every read is filtered by the person the turn answers ({@link MemoryReader}).</p>
 *
 * <ul>
 *   <li>{@link Visibility#PRIVATE} — said to her privately (a tell, a whisper, a phone
 *       message, a letter, a line in a Study or with no one else in the room). Read again only
 *       in turns that answer the teller.</li>
 *   <li>{@link Visibility#OPEN} — said openly in a shared room with others present, or her own
 *       experience that is not anyone's private words. Read in any turn.</li>
 *   <li>{@link Visibility#UNKNOWN} — written before origins were recorded, and the text does not
 *       show who said it. Read only in turns with her bondholder.</li>
 * </ul>
 *
 * @param tellerDid the person (canonical DID where it resolves) whose words these are; null for
 *                  her own experiences and for rows whose teller cannot be known
 */
public record MemoryOrigin(String tellerDid, Visibility visibility) {

    public enum Visibility {
        OPEN, PRIVATE, UNKNOWN;

        /** The value stored in the {@code visibility} column and index field. */
        public String column() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Visibility fromColumn(String v) {
            if (v == null) return UNKNOWN;
            return switch (v.trim().toLowerCase(Locale.ROOT)) {
                case "open" -> OPEN;
                case "private" -> PRIVATE;
                default -> UNKNOWN;
            };
        }
    }

    /** Audience value of an open memory. */
    public static final String AUDIENCE_OPEN = "open";
    /** Audience value of a memory whose teller is unknown: her bondholder's turns only. */
    public static final String AUDIENCE_BONDHOLDER = "bondholder";
    /** Prefix of the audience value of a private memory: {@code person:<did>}. */
    public static final String AUDIENCE_PERSON = "person:";

    /** Her own experience, not anyone's private words. */
    public static final MemoryOrigin OWN = new MemoryOrigin(null, Visibility.OPEN);
    /** Teller not known. */
    public static final MemoryOrigin UNKNOWN = new MemoryOrigin(null, Visibility.UNKNOWN);

    public MemoryOrigin {
        if (visibility == null) visibility = Visibility.UNKNOWN;
        if (tellerDid != null && tellerDid.isBlank()) tellerDid = null;
        // A private memory with no teller has no one it may go back to.
        if (visibility == Visibility.PRIVATE && tellerDid == null) visibility = Visibility.UNKNOWN;
    }

    /** Said to her privately by this person. */
    public static MemoryOrigin privateTo(String personId) {
        return new MemoryOrigin(canonical(personId), Visibility.PRIVATE);
    }

    /** Said openly, with others present, by this person. */
    public static MemoryOrigin openFrom(String personId) {
        return new MemoryOrigin(canonical(personId), Visibility.OPEN);
    }

    public boolean isPrivate() {
        return visibility == Visibility.PRIVATE;
    }

    /** The single value a search filters on: who may read this memory. */
    public String audience() {
        return switch (visibility) {
            case OPEN -> AUDIENCE_OPEN;
            case PRIVATE -> AUDIENCE_PERSON + tellerDid;
            case UNKNOWN -> AUDIENCE_BONDHOLDER;
        };
    }

    /** Rebuild an origin from stored columns. */
    public static MemoryOrigin of(String tellerDid, String visibility) {
        return new MemoryOrigin(tellerDid, Visibility.fromColumn(visibility));
    }

    /** From a stored audience value; a missing one is treated as unknown. */
    public static MemoryOrigin fromAudience(String audience) {
        if (audience == null || audience.isBlank()) return UNKNOWN;
        if (AUDIENCE_OPEN.equals(audience)) return OWN;
        if (audience.startsWith(AUDIENCE_PERSON)) {
            return new MemoryOrigin(audience.substring(AUDIENCE_PERSON.length()), Visibility.PRIVATE);
        }
        return UNKNOWN;
    }

    // ── Rows written before origins were recorded ────────────────────────────

    /** Working-memory lines that name the person they were a private exchange with. */
    private static final List<Pattern> PRIVATE_EXCHANGE = List.of(
        Pattern.compile("^\\[User fact\\] (.+?): "),
        Pattern.compile("^Replied to (.+?) via tell\\b"),
        Pattern.compile("^Left note for (.+?) on their Study desk"),
        Pattern.compile("^\\[PENDING REPLY\\] Report to (.+?) via\\b"),
        Pattern.compile("^Wrote back to (.+?): "),
        Pattern.compile("^Wrote to (.+?) while they were away: "),
        Pattern.compile("^Wrote to (.+?)'s journal: "),
        Pattern.compile("^Whispered to (.+?)$"),
        Pattern.compile("^(.+?) wrote to me: "));

    private static final Pattern CLOCK_PREFIX = Pattern.compile("^\\d{2}:\\d{2} ");

    /**
     * The origin of a memory written before origins were recorded, inferred from its text.
     * A line that names the person it was a private exchange with ("[User fact] Alice: …",
     * "Replied to Alice via tell: …") is private to that person, when the name resolves to a
     * person of this household. Everything else is {@link #UNKNOWN}: whose turn it came from
     * cannot be known, so it is read only in turns with her bondholder.
     */
    public static MemoryOrigin inferLegacy(String text) {
        if (text == null || text.isBlank()) return UNKNOWN;
        var t = CLOCK_PREFIX.matcher(text.strip()).replaceFirst("");
        for (var p : PRIVATE_EXCHANGE) {
            var m = p.matcher(t);
            if (!m.find()) continue;
            var who = m.group(1).strip();
            var person = resolvedPerson(who);
            return person == null ? UNKNOWN : new MemoryOrigin(person, Visibility.PRIVATE);
        }
        return UNKNOWN;
    }

    /** The person a name or id resolves to, or null when it does not resolve. */
    private static String resolvedPerson(String who) {
        if (who == null || who.isBlank()) return null;
        if (who.startsWith("did:")) return who;
        String canonical;
        try {
            canonical = PersonIds.canonical(who);
        } catch (RuntimeException e) {
            return null;
        }
        if (canonical == null || canonical.equals(who)) return null;
        return canonical;
    }

    private static String canonical(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            var c = PersonIds.canonical(id);
            return c == null || c.isBlank() ? id : c;
        } catch (RuntimeException e) {
            return id;
        }
    }
}
