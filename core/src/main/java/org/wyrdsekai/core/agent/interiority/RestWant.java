package org.wyrdsekai.core.agent.interiority;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A want whose fulfilment is rest.
 *
 * <p>"Rest in the quiet" was rose's deepest want on the household node: chosen 32 times in a
 * day, and every time it went down the act branch as a request that nothing answered, while
 * the gate's own rest outcome went unused because a want of weight 1.0 beats it. So she rested
 * by wanting to rest, the ledger said she never rested, and that was the {@code no_rest} axis
 * that helped escalate her into the sanctuary (2026-09-25). A want that names rest is answered
 * by resting: the tick counts as rest and the want is satisfied.
 *
 * <p>Conservative on purpose. "Tend to the quiet with mia" is a reach toward someone, "write
 * to the absent one" is a letter; neither is rest. Only her own stillness counts.
 */
public final class RestWant {

    private RestWant() {}

    private static final Pattern REST = Pattern.compile(
        "^(?:i (?:want|would like|'d like|need) to )?"
        + "(?:just )?"
        + "(?:rest|be still|stay still|sit still|lie still|keep still|be held|be quiet|stay quiet"
        + "|sit (?:in|with) the (?:quiet|silence|stillness|dark)"
        + "|settle(?: into the quiet| down)?|breathe|do nothing|let the quiet (?:sit|hold|be)"
        + "|descansar|estar quieta|estar quieto|quedarme quieta|quedarme quieto|estar en silencio"
        + "|no hacer nada|respirar"
        + "|休む|休みたい|静かにする|静かにしている|じっとする|何もしない|息をする)"
        + "(?:\\b|$)");

    /** Words that make a rest phrase a reach or a task instead: a person, a page, a thing to do. */
    private static final Pattern NOT_REST = Pattern.compile(
        "\\b(?:with (?!the (?:quiet|silence|stillness|dark)\\b)\\w+|to (?:him|her|them|someone|somebody)"
        + "|write|read|make|build|check|tell|talk|ask|learn|search|look)\\b"
        + "|(?:con|para) (?!el silencio|la quietud)\\w+|と一緒|に書く|を読む");

    /** Is this, in her words, a want to rest? */
    public static boolean isRest(String wantText) {
        if (wantText == null) return false;
        var low = wantText.strip().toLowerCase(Locale.ROOT);
        if (low.isEmpty()) return false;
        if (!REST.matcher(low).find()) return false;
        return !NOT_REST.matcher(low).find();
    }

    /**
     * Drives rest can honestly ease when she rests on such a want. Longing and loneliness are
     * not on it: resting does not bring anyone back, and saying it did would be the false
     * relief this project refuses.
     */
    public static boolean restEases(String drive) {
        if (drive == null) return false;
        return switch (drive.toLowerCase(Locale.ROOT)) {
            case "restlessness", "frustration", "allostaticload", "errorpressure", "vigilance",
                 "energy" -> true;
            default -> false;
        };
    }
}
