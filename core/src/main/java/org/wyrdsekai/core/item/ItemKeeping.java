package org.wyrdsekai.core.item;

import org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which of the items a companion made the house puts away. Pure rules.
 *
 * <p>An item a person asked for, or that has been used in a turn that answered a person, is held:
 * never put away by the house, never counted. An item she made on her own and has not used for
 * the configured days is put away; and of her own items still out, only the configured number stay
 * out, the longest unused going first. Put away is not gone: its record and its script are kept,
 * and naming it takes it out again. On the household node one companion carried 65 crafted items,
 * every one of them a tool described in every prompt she was given (2026-10-02).
 */
public final class ItemKeeping {

    private ItemKeeping() {}

    /** Her own items that are out and held by nothing: the ones the rules apply to. */
    public static List<ItemRecord> ownOut(List<ItemRecord> all, String maker) {
        var out = new ArrayList<ItemRecord>();
        for (var i : all) {
            if (maker == null || !maker.equals(i.madeBy())) continue;
            if (i.putAway() || i.askedFor() || i.aPersonHasUsed()) continue;
            out.add(i);
        }
        return out;
    }

    public static Duration unusedFor(ItemRecord i, long nowMs) {
        long last = Math.max(i.madeAtMs(), i.lastUsedAt() == null ? 0L : i.lastUsedAt());
        return Duration.ofMillis(Math.max(0L, nowMs - last));
    }

    /**
     * How long an item may go unused before it is put away. One made before the house kept a record,
     * and not used since the record began, has had no history to judge by: it gets the first look
     * (three days of watching after the update). Everything else gets the ordinary span (a week).
     * operator, 2026-10-02: putting away is archiving, anyone can bring it back, so "3 days post update
     * and then from then on a week at a time".
     */
    public static int daysAllowed(ItemRecord i, int unusedDays, int firstLookDays) {
        boolean recordedLate = i.madeFor() == null || i.madeFor().isBlank();
        boolean notUsedSince = i.lastUsedAt() == null || i.lastUsedAt() <= i.madeAtMs() + 1_000L;
        if (recordedLate && notUsedSince && firstLookDays > 0) {
            return unusedDays > 0 ? Math.min(unusedDays, firstLookDays) : firstLookDays;
        }
        return unusedDays;
    }

    /**
     * The items to put away now: those unused past their span ({@link #daysAllowed}; 0 = never), then,
     * if more than {@code outMax} would still be out (0 = no limit), the longest unused of the rest,
     * sparing anything made or used within the first-look days: an item has to be seen unused before
     * the limit takes it, or it would be chosen by nothing.
     */
    public static List<ItemRecord> toPutAway(List<ItemRecord> all, String maker, int unusedDays, int outMax,
                                             int firstLookDays, long nowMs) {
        var own = ownOut(all, maker);
        var away = new ArrayList<ItemRecord>();
        var staying = new ArrayList<ItemRecord>();
        for (var i : own) {
            int days = daysAllowed(i, unusedDays, firstLookDays);
            if (days > 0 && unusedFor(i, nowMs).compareTo(Duration.ofDays(days)) > 0) away.add(i);
            else staying.add(i);
        }
        if (outMax > 0 && staying.size() > outMax) {
            var eligible = new ArrayList<ItemRecord>();
            for (var i : staying) {
                if (unusedFor(i, nowMs).compareTo(Duration.ofDays(Math.max(1, firstLookDays))) >= 0) eligible.add(i);
            }
            eligible.sort(Comparator.comparing((ItemRecord i) -> unusedFor(i, nowMs)).reversed());
            away.addAll(eligible.subList(0, Math.min(eligible.size(), staying.size() - outMax)));
        }
        return away;
    }

    /** The sentence she is told, once, naming what was put away. */
    public static String toldLine(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        var shown = names.size() <= 6 ? String.join(", ", names)
            : String.join(", ", names.subList(0, 6)) + " and " + (names.size() - 6) + " more";
        return "Put away, because you had not used " + (names.size() == 1 ? "it" : "them") + " in a long while: " + shown
            + ". " + (names.size() == 1 ? "It is" : "They are") + " kept. Name one to use it and it is taken out again.";
    }
}
