package org.wyrdsekai.core.inference;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a model request knows about today: every request declares one.
 *
 * <p>A companion always knows the date and the time (decided 2026-03-28,
 * {@code TimeContext}). That stopped being true as prompt paths were added
 * around the one place that said it: the conversation lane, the ReAct loops,
 * bunshins, want proposals and item calls carried no date, and PromptAssembler's
 * time layer was trimmable. With no date the model believes it is 2024; on
 * 2026-09-22 Mia searched her own subject for "2024 2025", the library card
 * echoed the years back, and every later search repeated them.</p>
 *
 * <p>So the date is no longer something each prompt builder remembers to add.
 * Every request to the router says which of three it is, and the router stamps
 * the outgoing copy:</p>
 * <ul>
 *   <li>{@link Mode#DATE_TIME} — she speaks, thinks or acts: the line
 *       {@code [Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]} opens the
 *       LAST user message. That is the one position that is close to generation,
 *       survives compaction (the last user message is protected, a shear keeps
 *       its head), and sits after everything the single slot can reuse from its
 *       prompt cache. The stored history never holds it.</li>
 *   <li>{@link Mode#DATE} — a single-shot request made for her (think_deeply, a
 *       familiar, an item's summary): {@code Today is Wednesday, 23 September 2026.}
 *       opens the leading system message. It changes once a day.</li>
 *   <li>{@link Mode#NONE} — a rewrite, a classifier, a pass-through: nothing is
 *       added, because the time would leak into her words.</li>
 * </ul>
 *
 * <p>A request that declares nothing gets DATE_TIME as of the moment it is sent:
 * a forgotten path still knows the day. {@code EveryModelRequestKnowsWhatDayItIsTest}
 * makes every request in the tree declare one anyway.</p>
 *
 * @param asOf the moment the line states; a ReAct loop fixes it when the loop
 *             opens, so the line reads the same on every step (it stays on the loop's
 *             request while steps add tool results; a nudge sent as a user turn takes it,
 *             and that step re-reads from there). null = when sent.
 */
public record NowLine(Mode mode, Instant asOf) {

    public enum Mode { DATE_TIME, DATE, NONE }

    public static final NowLine NONE = new NowLine(Mode.NONE, null);

    static final String OPEN = "[Now: ";
    static final String TODAY = "Today is ";

    private static final DateTimeFormatter DAY_MONTH_YEAR =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR_MINUTE =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter ZONE_NAME =
        DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    public NowLine {
        if (mode == null) mode = Mode.DATE_TIME;
    }

    /** She speaks, thinks or acts: date and time, as of now. */
    public static NowLine dateTime() { return new NowLine(Mode.DATE_TIME, null); }

    /** Date and time as of a fixed moment (a loop's opening, a scene's own time). */
    public static NowLine dateTime(Instant asOf) { return new NowLine(Mode.DATE_TIME, asOf); }

    /** A single-shot request made for her: the date, as of now. */
    public static NowLine date() { return new NowLine(Mode.DATE, null); }

    /** The date as of a fixed moment. */
    public static NowLine date(Instant asOf) { return new NowLine(Mode.DATE, asOf); }

    /** The zone every stamp is read in: the node's own. */
    public static ZoneId zone() { return ZoneId.systemDefault(); }

    /**
     * {@code [Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]}. The
     * zone is part of the time: her trail, mail and findings are stamped in UTC,
     * people reach her from other zones, and the offset moves with daylight saving.
     */
    public static String dateTimeText(Instant at, ZoneId zone) {
        var t = ZonedDateTime.ofInstant(at, zone);
        return OPEN + weekday(t) + " " + t.format(DAY_MONTH_YEAR) + ", "
            + t.format(HOUR_MINUTE) + " " + zoneText(t) + ", " + partOfDay(t.getHour()) + "]";
    }

    /** {@code EDT (UTC-4)}; {@code UTC+5:30} where the zone has no short name of its own. */
    static String zoneText(ZonedDateTime t) {
        int total = t.getOffset().getTotalSeconds();
        int hours = Math.abs(total) / 3600, minutes = Math.abs(total) % 3600 / 60;
        var offset = "UTC" + (total < 0 ? "-" : "+") + hours
            + (minutes == 0 ? "" : ":" + (minutes < 10 ? "0" : "") + minutes);
        if (total == 0) offset = "UTC";
        var name = t.format(ZONE_NAME);
        boolean named = name.chars().allMatch(Character::isLetter)
            && !name.startsWith("GMT") && !name.startsWith("UTC");
        return named ? name + " (" + offset + ")" : offset;
    }

    /** {@code Today is Wednesday, 23 September 2026.} */
    public static String dateText(Instant at, ZoneId zone) {
        var t = ZonedDateTime.ofInstant(at, zone);
        return TODAY + weekday(t) + ", " + t.format(DAY_MONTH_YEAR) + ".";
    }

    public static String partOfDay(int hour) {
        if (hour >= 5 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        if (hour >= 21 || hour < 2) return "night";
        return "late night";
    }

    private static String weekday(ZonedDateTime t) {
        return t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /**
     * The outgoing copy of {@code messages} with this line in place. The input
     * list is never changed. A list that already carries the line (a retry, a
     * request forwarded from another node) is returned as it is.
     */
    public List<InferenceClient.ChatMessage> stamp(List<InferenceClient.ChatMessage> messages,
                                                   ZoneId zone) {
        if (mode == Mode.NONE || messages == null) return messages;
        var at = asOf != null ? asOf : Instant.now();
        var out = new ArrayList<>(messages);
        if (mode == Mode.DATE) {
            var line = dateText(at, zone);
            if (!out.isEmpty() && isRole(out.get(0), "system")) {
                var sys = out.get(0);
                var body = sys.content() == null ? "" : sys.content();
                if (body.startsWith(TODAY)) return messages;
                out.set(0, new InferenceClient.ChatMessage("system",
                    body.isEmpty() ? line : line + "\n" + body, sys.toolCalls(), sys.toolCallId()));
            } else {
                out.add(0, new InferenceClient.ChatMessage("system", line));
            }
            return out;
        }
        var line = dateTimeText(at, zone);
        for (int i = out.size() - 1; i >= 0; i--) {
            var m = out.get(i);
            if (!isRole(m, "user")) continue;
            var body = m.content() == null ? "" : m.content();
            if (body.startsWith(OPEN)) return messages;
            out.set(i, new InferenceClient.ChatMessage("user", line + "\n" + body,
                m.toolCalls(), m.toolCallId()));
            return out;
        }
        // No user turn at all: the line is one, after a leading system message
        // (the place the router's ensureUserTurn puts its own).
        int first = !out.isEmpty() && isRole(out.get(0), "system") ? 1 : 0;
        out.add(first, new InferenceClient.ChatMessage("user", line));
        return out;
    }

    private static boolean isRole(InferenceClient.ChatMessage m, String role) {
        return m != null && role.equals(m.role());
    }
}
