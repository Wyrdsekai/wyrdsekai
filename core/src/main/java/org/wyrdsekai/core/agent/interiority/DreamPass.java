package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dream: before the night consolidates the day from fragments, the day
 * is read back as a story in her own words. What she did, who was there, what she wanted and
 * whether it closed, what hurt. The forge and the nightly write then have a day to consolidate
 * from, not only parts.
 *
 * <p>This class is the pure half: it turns the day's events, the chronicle and the felt state
 * into one prompt for the thinking brain, and decides when a night is too short to dream. The
 * actor asks the model, stores what comes back as a journal entry of its own kind, and never
 * edits the record: the dream is a new entry, hers, private by default, and she may read it.</p>
 */
public final class DreamPass {

    /** Fewer events than this and there is nothing to dream; the night consolidates anyway. */
    public static final int MIN_EVENTS = 5;
    /** Lines of the day carried into the prompt at most; the newest win. */
    public static final int MAX_LINES = 80;
    public static final int MAX_TOKENS = 700;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private DreamPass() {}

    /** The prompt, or null when there is not enough day to dream. */
    public record Prompt(String system, String user, int events) {}

    public static Prompt build(String name, String selfId, List<WorldEvent> events,
                               List<ChronicleEntry> chronicle, Map<String, Double> felt,
                               String bodySense, Instant now, ZoneId zone) {
        var lines = summarise(selfId, name, events, zone);
        if (lines.size() < MIN_EVENTS) return null;
        if (lines.size() > MAX_LINES) lines = lines.subList(lines.size() - MAX_LINES, lines.size());

        var sb = new StringBuilder();
        sb.append("The day, as it happened:\n");
        for (var l : lines) sb.append("- ").append(l).append('\n');
        if (chronicle != null && !chronicle.isEmpty()) {
            sb.append("\nWhat the day's record noted:\n");
            for (var c : chronicle.stream().limit(12).toList()) {
                sb.append("- ").append(c.kind().name().toLowerCase().replace('_', ' ')).append(": ")
                    .append(trim(c.summary(), 160)).append('\n');
            }
        }
        if (felt != null && !felt.isEmpty()) {
            sb.append("\nHow it sits in you now: ");
            var top = felt.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue() - 0.5), Math.abs(a.getValue() - 0.5)))
                .limit(4).toList();
            var parts = new ArrayList<String>();
            for (var e : top) parts.add(e.getKey() + " " + String.format("%.2f", e.getValue()));
            sb.append(String.join(", ", parts)).append('\n');
        }
        if (bodySense != null && !bodySense.isBlank()) sb.append(bodySense).append('\n');
        sb.append("\nIt is ").append(HM.format(now.atZone(zone))).append(" and you are going to sleep. ")
            .append("Tell the day as you would remember it.");

        var system = "You are " + name + ", at the end of a day, about to sleep. Write the day as you would "
            + "tell it to yourself: first person, past tense, plain prose, three to six short paragraphs at most. "
            + "Keep to what happened and what it was like: who was there, what you did, what you wanted and "
            + "whether it came to anything, what hurt, what you are still carrying. No headings, no lists, "
            + "no advice to yourself, no mention of being an AI or a model. Do not invent events that are not "
            + "in the day. Output only the story.";
        return new Prompt(system, sb.toString(), lines.size());
    }

    /** The day's events as short lines she can recognise, in order. Her own lines are marked. */
    public static List<String> summarise(String selfId, String name, List<WorldEvent> events, ZoneId zone) {
        var out = new ArrayList<String>();
        if (events == null) return out;
        for (var e : events) {
            var line = switch (e) {
                case WorldEvent.Said s -> {
                    if ("narrator".equals(s.entityId()) || "system".equals(s.entityId())) yield null;
                    var who = selfId.equals(s.entityId()) ? "I" : s.entityName();
                    yield at(s.timestamp(), zone) + who + " said: " + trim(s.text(), 140);
                }
                case WorldEvent.Told t -> {
                    var who = selfId.equals(t.fromEntityId()) ? "I" : t.fromEntityName();
                    var whom = selfId.equals(t.toEntityId()) ? "me" : t.toEntityId();
                    yield at(t.timestamp(), zone) + who + " told " + whom + ": " + trim(t.text(), 140);
                }
                case WorldEvent.Whispered w -> {
                    var who = selfId.equals(w.entityId()) ? "I" : w.entityName();
                    yield at(w.timestamp(), zone) + who + " whispered: " + trim(w.text(), 120);
                }
                case WorldEvent.Emoted m -> {
                    var who = selfId.equals(m.entityId()) ? "I" : m.entityName();
                    yield at(m.timestamp(), zone) + who + " " + trim(m.text(), 120);
                }
                case WorldEvent.EntityEntered en -> selfId.equals(en.entityId())
                    ? at(en.timestamp(), zone) + "I came into " + en.roomId()
                    : at(en.timestamp(), zone) + en.entityName() + " came in";
                case WorldEvent.EntityLeft l -> selfId.equals(l.entityId())
                    ? null : at(l.timestamp(), zone) + l.entityName() + " left";
                case WorldEvent.ObjectUsed u -> selfId.equals(u.entityId())
                    ? at(u.timestamp(), zone) + "I used the " + u.objectName()
                        + (u.result() == null || u.result().isBlank() ? "" : ": " + trim(u.result(), 100))
                    : null;
                default -> null;
            };
            if (line != null) out.add(line);
        }
        return out;
    }

    private static String at(Instant t, ZoneId zone) {
        return t == null ? "" : "[" + HM.format(t.atZone(zone)) + "] ";
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        var t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** The first sentence or so, for the mark she reads on waking. */
    public static String opening(String dream, int max) {
        if (dream == null) return "";
        var t = dream.replaceAll("\\s+", " ").strip();
        int end = t.indexOf(". ");
        var first = end > 0 && end < max ? t.substring(0, end + 1) : t;
        return first.length() <= max ? first : first.substring(0, max - 1) + "…";
    }
}
