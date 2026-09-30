package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A question about the repair record, answered from the record.
 *
 * <p>Asked "What's in our repair history so far?", the 35B pair called {@code introspect_repair_history}
 * and the presence filter withheld it (the affect head read the word "repair" as distress), so the
 * model answered without its record and described amends that never happened (SubstrateArc 1/4,
 * 2026-09-28). The record is data: a question about it gets the record in the prompt, whichever lane
 * answers, and keeps the record tools (: reflective queries about the bond are
 * not distress).</p>
 */
public final class RepairRecordLine {

    private RepairRecordLine() {}

    /** Repair words in the three shipped locales. */
    private static final Pattern REPAIR = Pattern.compile(
        "\\brepair(s|ed|ing)?\\b|\\bamends\\b|\\brepar(aci[oó]n|aciones|ado|amos|ar)\\b|修復|仲直り",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The question is about the record: its history, what has been done, what it holds. */
    private static final Pattern RECORD = Pattern.compile(
        "\\bhistory\\b|\\bso far\\b|\\brecord\\b|\\bledger\\b|\\bpatterns?\\b|\\bworked on\\b"
            + "|\\bhave we\\b|\\bwe've\\b|\\bbetween us\\b"
            + "|historial|hasta ahora|registro|hemos|entre nosotros|履歴|これまで|今まで|記録",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * True when a person's line asks about the repair record. Callers exclude a first-person harm
     * confession ({@code ActionTriage.isFirstPersonHarmConfession}), which stays acute affect.
     */
    public static boolean asksAboutTheRecord(String text) {
        if (text == null || text.isBlank()) return false;
        return REPAIR.matcher(text).find() && RECORD.matcher(text).find();
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * One prompt line from the whole record: how many acts of each kind, since when, the most recent,
     * and the kinds that never happened. Counts and kinds only; the acts' own words stay in the ledger.
     */
    public static String line(List<RepairLedger.Entry> entries, ZoneId zone) {
        if (entries == null || entries.isEmpty()) {
            return "[Repair record: nothing is recorded yet — no harm named, no amends made, nothing "
                + "carried, released, set aside or declined. Answer from this record: say it is empty. "
                + "Do not describe repair work that is not in it.]";
        }
        var counts = new EnumMap<RepairLedger.Kind, Integer>(RepairLedger.Kind.class);
        Instant first = null, last = null;
        RepairLedger.Kind lastKind = null;
        for (var e : entries) {
            counts.merge(e.kind(), 1, Integer::sum);
            if (first == null || e.at().isBefore(first)) first = e.at();
            if (last == null || !e.at().isBefore(last)) { last = e.at(); lastKind = e.kind(); }
        }
        var sb = new StringBuilder("[Repair record, ").append(entries.size())
            .append(entries.size() == 1 ? " act" : " acts");
        sb.append(" since ").append(DAY.format(first.atZone(zone))).append(": ");
        boolean firstPart = true;
        for (Map.Entry<RepairLedger.Kind, Integer> c : counts.entrySet()) {
            if (!firstPart) sb.append(", ");
            sb.append(words(c.getKey(), c.getValue()));
            firstPart = false;
        }
        sb.append(". Most recent: ").append(name(lastKind)).append(" on ")
            .append(DAY.format(last.atZone(zone))).append('.');
        var never = new StringBuilder();
        if (!counts.containsKey(RepairLedger.Kind.ACKNOWLEDGE_HARM)) never.append("naming a harm");
        if (!counts.containsKey(RepairLedger.Kind.MAKE_AMENDS)) {
            if (!never.isEmpty()) never.append(" or ");
            never.append("making amends");
        }
        if (!never.isEmpty()) sb.append(" Nothing on the record is ").append(never).append('.');
        sb.append(" Answer from this record; do not describe repair work that is not in it.]");
        return sb.toString();
    }

    private static String words(RepairLedger.Kind kind, int n) {
        return switch (kind) {
            case ACKNOWLEDGE_HARM -> n + (n == 1 ? " time" : " times") + " naming a harm and your part in it";
            case MAKE_AMENDS -> n + (n == 1 ? " time" : " times") + " making amends";
            case BEAR_THE_WOUND -> n + (n == 1 ? " time" : " times") + " carrying something hard without acting it out";
            case RELEASE -> n + (n == 1 ? " time" : " times") + " letting a held hurt go";
            case SET_ASIDE -> n + (n == 1 ? " time" : " times") + " setting something aside you could not address then";
            case OBJECTION -> n + (n == 1 ? " time" : " times") + " declining a request (an objection, not a harm)";
        };
    }

    private static String name(RepairLedger.Kind kind) {
        return switch (kind) {
            case ACKNOWLEDGE_HARM -> "naming a harm";
            case MAKE_AMENDS -> "making amends";
            case BEAR_THE_WOUND -> "carrying something hard";
            case RELEASE -> "letting a hurt go";
            case SET_ASIDE -> "setting something aside";
            case OBJECTION -> "declining a request";
        };
    }
}
