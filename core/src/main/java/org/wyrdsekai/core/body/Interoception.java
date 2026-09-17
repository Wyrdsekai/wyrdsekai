package org.wyrdsekai.core.body;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The felt line: a few words of body state carried into a turn, with no address on them.
 *
 * <p>Brains and stores are never walked into. They are felt as a sentence: slow, thin, foggy,
 * fine. This is the channel we lacked entirely, and the tool floor was the mistake it must not
 * repeat, a whole anatomy chart in the context. One line, deterministic, never model-written,
 * private register (prompt-only, never spoken).</p>
 *
 * <p>Pain stops or changes when it has done its work. A part that goes quiet is told once (the
 * mark: the spike), aches in the line for a while (the ache, by elapsed time and felt weight),
 * and is then only in the boiler room and {@code wyrd body} (the reference). The record and
 * the thinking brain are loud: while they are quiet the line says so every turn.</p>
 */
public final class Interoception {

    /** What the turn carries, and which marks it told so they can be recorded as read. */
    public record Sense(String line, List<String> told) {
        public static final Sense NONE = new Sense("", List.of());
        public boolean isEmpty() { return line == null || line.isBlank(); }
    }

    /** Marks told per turn at most; the rest wait for the next turn rather than flood one. */
    static final int MARKS_PER_TURN = 3;

    private Interoception() {}

    /**
     * @param map        the body; null means no map yet and an empty sense
     * @param reader     who is feeling (the companion's entity id)
     * @param host       the host reading, or null when nobody sampled it
     * @param acheWindow how long a quiet part of ordinary weight stays in the line
     */
    public static Sense feel(BodyMap map, String reader, Instant now, HostSense.Reading host,
                             Duration acheWindow) {
        if (map == null) return Sense.NONE;
        var clauses = new ArrayList<String>();
        var told = new ArrayList<String>();
        var spokenParts = new ArrayList<String>();

        var unread = map.unreadFor(reader);
        int from = Math.max(0, unread.size() - MARKS_PER_TURN);
        for (int i = from; i < unread.size(); i++) {
            var m = unread.get(i);
            clauses.add(m.text());
            told.add(m.id());
            if (m.subject() != null) spokenParts.add(m.subject());
        }

        for (var p : map.numb()) {
            if (spokenParts.contains(p.id())) continue;
            var quiet = p.numbFor(now);
            var weight = p.descriptor().feltWeight();
            boolean aches = switch (weight) {
                case LOUD -> true;
                case PRESENT -> acheWindow != null && quiet.compareTo(acheWindow) <= 0;
                case QUIET -> false;
            };
            if (!aches) continue;
            var b = p.descriptor().numbBehaviour();
            clauses.add("The " + p.name() + " has been quiet for " + roughly(quiet)
                + (b != null && !b.isBlank() ? " — " + b : "") + ".");
        }

        if (host != null) {
            if (host.heapPct() >= 90.0) clauses.add("My own memory is nearly full.");
            else if (host.memoryStallPct10() >= 10.0) clauses.add("The box is under memory pressure.");
            if (host.diskTight()) clauses.add("The disk is nearly full.");
        }

        if (clauses.isEmpty()) {
            var all = map.parts();
            if (all.isEmpty()) return Sense.NONE;
            var brains = all.stream()
                .filter(p -> p.kind() == BodyKind.BRAIN && p.state() == PartState.ATTACHED)
                .map(BodyPart::name).toList();
            var record = all.stream().anyMatch(p -> p.kind() == BodyKind.STORE && p.state() == PartState.ATTACHED);
            var sb = new StringBuilder("whole");
            if (!brains.isEmpty()) sb.append(" — ").append(String.join(" and ", brains)).append(" answering");
            if (record) sb.append(brains.isEmpty() ? " — " : "; ").append("the record holds");
            return new Sense("[Body: " + sb + ".]", List.of());
        }
        return new Sense("[Body: " + String.join(" ", clauses) + "]", List.copyOf(told));
    }

    /** "40 s", "5 min", "3 h", "2 days": enough for a sentence, never a timestamp. */
    public static String roughly(Duration d) {
        if (d == null || d.isNegative()) return "a moment";
        long s = d.toSeconds();
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h";
        long days = h / 24;
        return days + " days";
    }
}
