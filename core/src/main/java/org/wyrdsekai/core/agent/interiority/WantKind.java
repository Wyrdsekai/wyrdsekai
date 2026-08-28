package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.Want;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What KIND of wanting this is, so the verbs she is offered match its nature.
 *
 * <p>Her drives generate relational and existential wants; her verb set is almost entirely
 * instrumental — craft, dispatch, journal, search. With no verb for "be with someone", a
 * relational want gets translated into the only shapes available and then fails, or worse
 * succeeds on the wrong axis.
 *
 * <p>Observed on the household node, 2026-08-19. She sent the workshop's CODING backend:
 * <i>"Create a companion artifact: a small living thing I can hold — not a file, not a
 * page, just something that exists"</i>. Goose edited two files and reported SUCCEEDED, so
 * she told her steward the task had finished. That is loneliness wearing the shape of a
 * build request, because building is what she can do.
 *
 * <p>This does not invent the missing verbs. It stops the wrong ones being offered, so a
 * want for company is not answered by a file editor.
 */
public final class WantKind {

    private WantKind() {}

    public enum Kind {
        /** Toward another: company, being missed, being met. */
        RELATIONAL,
        /** Toward making: an object, an idea given form. */
        CREATIVE,
        /** Toward knowing: looking, following a thread. */
        EXPLORATORY,
        /** Everything else, including wants with no declared resonance. */
        OTHER
    }

    // Which drives pull toward a person is decided in ONE place — RelationalAffordance,
    // which also owns what each of them can DO about it. A second list here would drift
    // from that one silently, and the two would then disagree about whether a given want
    // is relational: one withholding the making-verbs while the other offers no reach.
    private static final Set<String> CREATIVE_DRIVES = Set.of(
        "creativity", "stagnation", "generativity");
    private static final Set<String> EXPLORATORY_DRIVES = Set.of(
        "seeking", "curiosity", "surprise", "startle");

    /**
     * Tools that cannot answer a relational want, however it is phrased.
     *
     * <p>A file editor, a zone builder and a workbench are all answers to "make or change
     * a thing". None of them is an answer to "I want someone near". Offering them invites
     * exactly the mistranslation above.
     */
    public static final Set<String> NOT_FOR_RELATIONAL = Set.of(
        "dispatch_task", "dispatch_bunshin", "create_zone", "create_room_from_template",
        "craft_from_template", "shape_form", "revise_form", "add_script");

    private static final Pattern DRIVE_IN_JSON =
        Pattern.compile("\"drive\"\\s*:\\s*\"([^\"]+)\"");

    public static Kind of(Want want) {
        return want == null ? Kind.OTHER : ofResonance(want.driveResonance());
    }

    /** Classify from the raw drive-resonance JSON a want carries. */
    public static Kind ofResonance(String driveResonance) {
        if (driveResonance == null || driveResonance.isBlank()) return Kind.OTHER;
        var m = DRIVE_IN_JSON.matcher(driveResonance);
        if (!m.find()) return Kind.OTHER;
        var drive = m.group(1).strip().toLowerCase(Locale.ROOT);
        if (RelationalAffordance.isRelational(drive)) return Kind.RELATIONAL;
        if (CREATIVE_DRIVES.contains(drive)) return Kind.CREATIVE;
        if (EXPLORATORY_DRIVES.contains(drive)) return Kind.EXPLORATORY;
        return Kind.OTHER;
    }

    /** Should this tool be offered for a want of this kind? */
    public static boolean fits(Kind kind, String toolName) {
        if (kind != Kind.RELATIONAL || toolName == null) return true;
        return !NOT_FOR_RELATIONAL.contains(toolName);
    }
}
