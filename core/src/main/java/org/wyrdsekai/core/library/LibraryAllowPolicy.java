package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.identity.PersonIds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Who may cause which {@code allow} value to be sent to the household's library.
 *
 * <p>The library (ResearchZosho) withholds some work unless a call carries {@code allow}:
 * {@code explicit}, {@code howto}, and {@code self-harm} (a question its check read as someone
 * asking about harming themselves). Which person may cause which of these to be sent is the
 * household's decision, made here, by the steward's rules, and not the library's. Wyrdsekai calls
 * the library with one household token for every person and every companion, so the library's
 * own reader levels cannot tell one person in this household from another; nothing here relies on
 * them, and a library linked with a more permissive token changes nothing below.
 *
 * <p>The rule:
 * <ul>
 *   <li>a member under parental controls (a child): nothing, through any door;</li>
 *   <li>any other member (an adult): {@code self-harm} only, and only through their own
 *       {@code research yes} ({@link Door#OWN_YES});</li>
 *   <li>the companion, an item, a room script, a skill, an outside MCP client, or anyone the
 *       household cannot name as a person: nothing, ever.</li>
 * </ul>
 * A call asking for anything outside what its caller may send goes out with no {@code allow} at
 * all. {@link LibraryConsent} consults this for the person's yes, and
 * {@code McpGatewayService} consults it for every call to the librarian.
 */
public final class LibraryAllowPolicy {

    private static final Logger log = LoggerFactory.getLogger(LibraryAllowPolicy.class);

    public static final String EXPLICIT = "explicit";
    public static final String HOWTO = "howto";
    public static final String SELF_HARM = "self-harm";

    /** Who is behind a call to the library. */
    public enum Caller {
        /** A member under parental controls. */
        CHILD,
        /** A member with no parental controls. */
        ADULT,
        /** Not a person saying yes themselves: the companion, an item, a script, a skill, an outside client. */
        AGENT
    }

    /** How a call reaches the library. */
    public enum Door {
        /** The person typed {@code research yes} to a question the library asked them about. */
        OWN_YES,
        /** Every other way. */
        OTHER
    }

    /** Whether a member is under parental controls (production: {@link ParentalControlService}). */
    static volatile Predicate<String> underParentalControls = LibraryAllowPolicy::parentalControlsSet;

    private LibraryAllowPolicy() {}

    /** The allow values {@code who} may cause to be sent through {@code door}. */
    public static Set<String> mayCause(Caller who, Door door) {
        if (who == null || door == null) return Set.of();
        return switch (who) {
            case ADULT -> door == Door.OWN_YES ? Set.of(SELF_HARM) : Set.of();
            case CHILD, AGENT -> Set.of();
        };
    }

    /**
     * What goes out as {@code allow} when {@code who} asks for {@code requested} through
     * {@code door}: the request as it is when every value in it may be sent, and nothing at all
     * otherwise (a request carrying one value its caller may not send is not trimmed to the rest).
     */
    public static List<String> toSend(Caller who, Door door, Object requested) {
        var values = values(requested);
        if (values.isEmpty()) return List.of();
        var permitted = mayCause(who, door);
        return permitted.containsAll(values) ? List.copyOf(values) : List.of();
    }

    /**
     * Who a person id is: {@link Caller#CHILD} under parental controls, and also when that
     * cannot be answered (unknown is not "adult"); {@link Caller#ADULT} otherwise; no id is not a
     * person ({@link Caller#AGENT}).
     */
    public static Caller personOf(String personId) {
        if (personId == null || personId.isBlank()) return Caller.AGENT;
        try {
            return underParentalControls.test(personId) ? Caller.CHILD : Caller.ADULT;
        } catch (RuntimeException e) {
            log.warn("[library] parental-controls check failed for {}: {}", personId, e.toString());
            return Caller.CHILD;
        }
    }

    /** Test hook: decide who is under parental controls. */
    public static void setParentalControlsForTests(Predicate<String> check) {
        underParentalControls = check == null ? LibraryAllowPolicy::parentalControlsSet : check;
    }

    /** Test hook: back to the production parental-controls check. */
    public static void resetForTests() {
        underParentalControls = LibraryAllowPolicy::parentalControlsSet;
    }

    private static List<String> values(Object requested) {
        var out = new ArrayList<String>();
        if (requested instanceof Collection<?> c) {
            for (var v : c) if (v != null) out.add(String.valueOf(v).strip());
        } else if (requested instanceof String s && !s.isBlank()) {
            out.add(s.strip());
        } else if (requested instanceof Object[] arr) {
            for (var v : arr) if (v != null) out.add(String.valueOf(v).strip());
        } else if (requested != null) {
            out.add(String.valueOf(requested).strip());
        }
        return out;
    }

    private static boolean parentalControlsSet(String personId) {
        return parentalControlsOf(personId).isPresent();
    }

    /** The member's parental controls, looked up by the id given and by its canonical id. */
    static Optional<ParentalControlService.Controls> parentalControlsOf(String personId) {
        var parental = ParentalControlService.get();
        if (parental == null || personId == null) return Optional.empty();
        var own = parental.controlsFor(personId);
        if (own.isPresent()) return own;
        var canonical = PersonIds.canonical(personId);
        return canonical != null && !canonical.equals(personId) ? parental.controlsFor(canonical) : Optional.empty();
    }
}
