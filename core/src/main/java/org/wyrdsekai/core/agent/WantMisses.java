package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a want of hers reached for and did not find, kept on the want, so that a want can end.
 *
 * <p>A want ended only when it was satisfied or when she stopped naming it. Nothing tied what
 * came of an act to the want that caused it: on the household node "finish reading the Eve Robert
 * Lewis epub" was chosen again for two days after her search returned nothing and she had said so,
 * and 61% of wants were chosen again within three ticks. Rephrased, the same want was a new row
 * with nothing remembered, so misses are counted per thing reached for (the search's words, the
 * room's name), across her wants of the last day, not per row.
 *
 * <p>Set down is not never. The want is closed with what was looked for and why; she is told; for
 * a day a newly named want that reaches for the same thing is not taken up; after that, or with
 * her person's help, it can be named again.
 */
final class WantMisses {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SET_DOWN_PREFIX = "set down: ";
    private static final Pattern SEARCHED = Pattern.compile("^searched for \"(.*)\", found nothing$");
    private static final Pattern EMPTY_RESULT = Pattern.compile(
        "(?i)^\\s*(no results found|the (provided )?sources (do not|don't) (cover|contain)|"
        + "i (do not|don't) have anything on|nothing (was )?found|library search '.*' found no results)");

    private WantMisses() {}

    /** One thing reached for and not found. */
    record Miss(String key, String what, Instant at) {}

    /** A want that was set down, as the naming pass needs it. */
    record SetDown(String text, String key, String what, Instant at) {}

    /** The words of a search that found nothing, from the outcome a search notes; null otherwise. */
    static String queryOfEmptySearch(String outcome) {
        if (outcome == null) return null;
        var m = SEARCHED.matcher(outcome.strip());
        return m.matches() ? m.group(1) : null;
    }

    /** True when a tool's answer says, in the house's own fixed words, that it found nothing. */
    static boolean looksEmpty(String toolAnswer) {
        return toolAnswer != null && EMPTY_RESULT.matcher(toolAnswer).find();
    }

    /** The key a miss is counted under: what was reached for, in lower case, spaces collapsed. */
    static String key(String reachedFor) {
        if (reachedFor == null) return "";
        return reachedFor.strip().toLowerCase(Locale.ROOT).replaceAll("['’]s\\b", "")
            .replaceAll("[\"'“”‘’]", "").replaceAll("\\s+", " ");
    }

    static Want withMiss(Want w, String key, String what, Instant at) {
        var o = resonance(w);
        var arr = o.withArray("misses");
        var m = arr.addObject();
        m.put("key", key);
        m.put("what", what == null ? "" : what);
        m.put("at", at.toEpochMilli());
        return withResonance(w, o);
    }

    static List<Miss> missesOn(Want w) {
        var out = new ArrayList<Miss>();
        var arr = resonance(w).path("misses");
        if (arr.isArray()) {
            for (var n : arr) {
                out.add(new Miss(n.path("key").asText(""), n.path("what").asText(""),
                    Instant.ofEpochMilli(n.path("at").asLong(0))));
            }
        }
        return out;
    }

    /** How many times this thing was reached for and not found, across these wants, since then. */
    static int count(List<Want> wants, String key, Instant since) {
        int n = 0;
        for (var w : wants) {
            for (var m : missesOn(w)) {
                if (m.key().equals(key) && !m.at().isBefore(since)) n++;
            }
        }
        return n;
    }

    /** The want closed, with what was looked for kept on it so the naming pass can read it. */
    static Want setDown(Want w, String key, String what, int times) {
        var o = resonance(w);
        o.put("setDownKey", key);
        o.put("setDownWhat", what == null ? "" : what);
        return withResonance(w, o).abandoned(reason(times, what));
    }

    static String reason(int times, String what) {
        return SET_DOWN_PREFIX + "looked " + times + " times and it was not there (" + what + ")";
    }

    /** The wants set down within the window, newest first, from her closed wants. */
    static List<SetDown> setDownSince(List<Want> closed, Instant since) {
        var out = new ArrayList<SetDown>();
        for (var w : closed) {
            if (w.status() != Want.Status.ABANDONED || w.satisfactionNote() == null
                || !w.satisfactionNote().startsWith(SET_DOWN_PREFIX)) continue;
            if (w.satisfiedAt() == null || w.satisfiedAt().isBefore(since)) continue;
            var o = resonance(w);
            var key = o.path("setDownKey").asText("");
            if (key.isBlank()) continue;
            out.add(new SetDown(w.text(), key, o.path("setDownWhat").asText(""), w.satisfiedAt()));
        }
        return out;
    }

    /**
     * Whether a newly named want reaches for a thing that was set down: its words are the same, or
     * every word of what was looked for (three letters and up) is in it.
     */
    static boolean reachesFor(String candidate, SetDown down) {
        if (candidate == null || down == null) return false;
        var c = key(candidate);
        if (c.equals(key(down.text()))) return true;
        int words = 0;
        for (var word : down.key().split(" ")) {
            if (word.length() < 3) continue;
            words++;
            if (!Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(c).find()) return false;
        }
        return words > 0;
    }

    /** What she is told, once, when a want is set down. */
    static String toldLine(String wantText, int times, String what) {
        return "You set down \"" + wantText + "\": you looked " + times + " times and it was not there (" + what
            + "). It is not open now. If it still matters, your person may be able to help; you can ask them when they are here.";
    }

    /** The line the naming pass carries, so what is closed is visibly closed and the choice stays hers. */
    static String namingLine(List<SetDown> downs) {
        if (downs == null || downs.isEmpty()) return null;
        var sb = new StringBuilder("Set down for now, because you looked and it was not there: ");
        int n = 0;
        for (var d : downs) {
            if (n++ > 0) sb.append("; ");
            sb.append('"').append(d.text()).append('"');
            if (n >= 3) break;
        }
        return sb.append(". Leave these until something changes or your person can help.").toString();
    }

    static Duration window(int hours) {
        return Duration.ofHours(Math.max(1, hours));
    }

    private static ObjectNode resonance(Want w) {
        try {
            var raw = w.driveResonance();
            if (raw != null && !raw.isBlank() && JSON.readTree(raw) instanceof ObjectNode o) return o.deepCopy();
        } catch (Exception ignored) {
            // A resonance that is not a JSON object is kept beside what is added.
        }
        var o = JSON.createObjectNode();
        if (w.driveResonance() != null && !w.driveResonance().isBlank()) o.put("raw", w.driveResonance());
        return o;
    }

    private static Want withResonance(Want w, ObjectNode o) {
        return new Want(w.wantId(), w.agentDid(), w.text(), o.toString(), w.feltWeight(), w.status(),
            w.bornAt(), w.lastVisitedAt(), w.visitCount(), w.satisfiedAt(), w.satisfactionNote(), w.parentWantId());
    }
}
