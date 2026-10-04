package org.wyrdsekai.core.room;

import org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord;
import org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finding what was put away. Pure.
 *
 * <p>operator, 2026-10-02: "if someone can bring them back, how do they know how to bring them back and
 * how to search for them? if I go, I want to bring back that nice room with the greenhouse, or
 * something: how do we find it" and "I think we need 2 archives: a fold/archive and a cold storage."
 * A closed-up room keeps a note of what it was and what was in it, a put-away item its name and
 * description, and both are searched by ordinary words. Two tiers: the archive (listed, searched by
 * loose words, what a companion looks through when someone asks) and cold storage (what has sat in
 * the archive a long while: still kept, shown only when asked for by that word, opened by its name).
 */
public final class MadeArchive {

    private MadeArchive() {}

    /** One thing in the archive, as a list shows it. */
    public record Found(String id, String name, String what, String madeBy, long archivedAtMs, boolean cold, int score) {}

    private static final Set<String> NOT_WORDS = Set.of("the", "and", "for", "with", "that", "this", "room", "rooms",
        "item", "items", "one", "back", "bring", "open", "reopen", "closed", "nice", "old", "some", "something", "please",
        "where", "what", "from", "have", "had", "was", "were", "you", "your", "our", "her", "his", "their");

    /** The words of a question that can find something: three letters and up, the small words left out. */
    public static List<String> words(String query) {
        var out = new LinkedHashSet<String>();
        if (query == null) return List.of();
        for (var w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !NOT_WORDS.contains(w)) out.add(w);
        }
        return new ArrayList<>(out);
    }

    static int score(List<String> words, String... fields) {
        var hay = new StringBuilder();
        for (var f : fields) if (f != null) hay.append(' ').append(f.toLowerCase(Locale.ROOT));
        int n = 0;
        for (var w : words) if (hay.indexOf(w) >= 0) n++;
        return n;
    }

    /**
     * Closed-up rooms: all of the tier when nothing is asked, else those a word of the question
     * finds, the best match first and then the most recently closed.
     */
    public static List<Found> rooms(List<RoomRecord> all, String query, boolean cold) {
        var words = words(query);
        var out = new ArrayList<Found>();
        for (var r : all) {
            if (!r.closed() || r.cold() != cold) continue;
            int s = words.isEmpty() ? 0 : score(words, r.name(), r.roomId(), r.closedSummary());
            if (!words.isEmpty() && s == 0) continue;
            out.add(new Found(r.roomId(), r.name() == null ? r.roomId() : r.name(), r.closedSummary(), r.createdBy(),
                r.closedAt(), r.cold(), s));
        }
        out.sort(Comparator.comparingInt(Found::score).reversed().thenComparing(Comparator.comparingLong(Found::archivedAtMs).reversed()));
        return out;
    }

    /** Put-away items, the same way. */
    public static List<Found> items(List<ItemRecord> all, String query, boolean cold) {
        var words = words(query);
        var out = new ArrayList<Found>();
        for (var i : all) {
            if (!i.putAway() || i.cold() != cold) continue;
            int s = words.isEmpty() ? 0 : score(words, i.shownName(), i.itemId(), i.summary());
            if (!words.isEmpty() && s == 0) continue;
            out.add(new Found(i.itemId(), i.shownName(), i.summary(), i.madeBy(), i.putAwayAt(), i.cold(), s));
        }
        out.sort(Comparator.comparingInt(Found::score).reversed().thenComparing(Comparator.comparingLong(Found::archivedAtMs).reversed()));
        return out;
    }

    /**
     * Only what holds every word of the question. A companion going somewhere "by what it was" is
     * answered this strictly: one shared word must not open a room she did not mean.
     */
    public static List<Found> holdingEveryWord(List<Found> found, String query) {
        int n = words(query).size();
        if (n == 0) return List.of();
        return found.stream().filter(f -> f.score() >= n).toList();
    }

    /** The one a question clearly means: the only match, or a match that beats every other. */
    public static Found clearly(List<Found> found) {
        if (found.isEmpty()) return null;
        if (found.size() == 1) return found.getFirst();
        return found.get(0).score() > found.get(1).score() ? found.getFirst() : null;
    }

    /** What has been in the archive past the setting and so goes to cold storage. 0 days = never. */
    public static boolean dueForCold(Long archivedAtMs, Long coldAtMs, int coldDays, long nowMs) {
        return coldDays > 0 && archivedAtMs != null && coldAtMs == null
            && nowMs - archivedAtMs > Duration.ofDays(coldDays).toMillis();
    }

    /** One line of a list: the name, how long it has been away, and what it was. */
    public static String line(Found f, long nowMs) {
        long days = Math.max(0, (nowMs - f.archivedAtMs()) / Duration.ofDays(1).toMillis());
        var what = f.what() == null || f.what().isBlank() ? "" : " — " + (f.what().length() > 110 ? f.what().substring(0, 109) + "…" : f.what());
        return f.name() + " (" + (days == 0 ? "today" : days == 1 ? "1 day" : days + " days") + ")" + what;
    }
}
