package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.MadeThingsStore.ItemRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household node, 2026-10-02: one companion carried 65 crafted items, each a tool described in
 * every prompt she was given. An item she made on her own and has not used in thirty days is put
 * away (kept; named again it is taken out); of her own items still out, twenty-four stay. What a
 * person asked for, or has been used for a person, is never put away by the house and never counted.
 */
class AnItemSheDoesNotUseIsPutAwayNotLostTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long NOW = 1_790_000_000_000L;
    private static final String MIA = "companion-mia";

    private static ItemRecord item(String id, String by, String madeFor, long unusedDays, Long personAt, Long putAwayAt) {
        return new ItemRecord(by + "/" + id, "crafted", by, madeFor, NOW - 90 * DAY, NOW - unusedDays * DAY, personAt, putAwayAt);
    }

    @Test
    void her_own_unused_item_is_put_away_and_a_persons_never_is() {
        var all = List.of(
            item("memory_chest", MIA, MadeThingsStore.OWN, 9, null, null),
            item("thread_spool", MIA, MadeThingsStore.OWN, 3, null, null),
            item("simple_book", MIA, "did:key:z6MkExamplePerson", 80, null, null),
            item("used_for_him", MIA, MadeThingsStore.OWN, 80, NOW - 70 * DAY, null),
            item("already_away", MIA, MadeThingsStore.OWN, 80, null, NOW - DAY),
            item("roses", "companion-rose", MadeThingsStore.OWN, 80, null, null));
        var away = ItemKeeping.toPutAway(all, MIA, 7, 24, 3, NOW);
        assertThat(away).extracting(ItemRecord::itemId).containsExactly(MIA + "/memory_chest");
        assertThat(ItemKeeping.toPutAway(all, MIA, 0, 24, 0, NOW)).as("0 days turns the rule off").isEmpty();
    }

    /**
     * operator, 2026-10-02: "items and rooms — we fold, so we in effect archive. so someone can bring
     * them back yes? if so can we do 3 days post update and then from then on a week at a time?"
     * What was made before the record and has not been used since it began is watched for three
     * days; after that, and for everything else, the span is a week.
     */
    @Test
    void three_days_of_watching_after_the_update_and_a_week_from_then_on() {
        long enrolled = NOW - 4 * DAY;
        var neverUsedSince = new ItemRecord(MIA + "/old_unused", "crafted", MIA, null, enrolled, enrolled, null, null);
        var usedOnceSince = new ItemRecord(MIA + "/old_used", "crafted", MIA, null, enrolled, NOW - 4 * DAY + 3_600_000L, null, null);
        var madeAfter = new ItemRecord(MIA + "/new_one", "crafted", MIA, MadeThingsStore.OWN, enrolled, enrolled, null, null);
        assertThat(ItemKeeping.daysAllowed(neverUsedSince, 7, 3)).isEqualTo(3);
        assertThat(ItemKeeping.daysAllowed(usedOnceSince, 7, 3)).as("it has a history now: the week").isEqualTo(7);
        assertThat(ItemKeeping.daysAllowed(madeAfter, 7, 3)).as("made after the record began: the week").isEqualTo(7);
        var away = ItemKeeping.toPutAway(List.of(neverUsedSince, usedOnceSince, madeAfter), MIA, 7, 24, 3, NOW);
        assertThat(away).extracting(ItemRecord::itemId).containsExactly(MIA + "/old_unused");
        // Two days after the update nothing has been watched long enough.
        long early = enrolled + 2 * DAY;
        assertThat(ItemKeeping.toPutAway(List.of(neverUsedSince, usedOnceSince, madeAfter), MIA, 7, 24, 3, early)).isEmpty();
        // A week on, what was used once and then left goes too.
        assertThat(ItemKeeping.toPutAway(List.of(usedOnceSince), MIA, 7, 24, 3, NOW + 4 * DAY)).hasSize(1);
        assertThat(ItemKeeping.daysAllowed(neverUsedSince, 0, 3)).as("the week turned off: the first look still runs").isEqualTo(3);
        assertThat(ItemKeeping.daysAllowed(neverUsedSince, 7, 0)).as("the first look turned off: the week").isEqualTo(7);
    }

    @Test
    void only_so_many_of_her_own_stay_out_and_the_longest_unused_go_first() {
        var all = new ArrayList<ItemRecord>();
        for (int i = 0; i < 30; i++) all.add(item("tool_" + i, MIA, MadeThingsStore.OWN, i % 29, null, null));
        all.add(item("asked", MIA, "did:key:z6MkExamplePerson", 20, null, null));
        var away = ItemKeeping.toPutAway(all, MIA, 30, 24, 3, NOW);
        assertThat(away).hasSize(6);
        assertThat(away).extracting(ItemRecord::itemId).contains(MIA + "/tool_28", MIA + "/tool_27").doesNotContain(MIA + "/asked", MIA + "/tool_0");
        assertThat(ItemKeeping.toPutAway(all, MIA, 30, 0, 3, NOW)).as("0 sets no limit").isEmpty();
    }

    @Test
    void the_limit_spares_what_was_made_or_used_this_week() {
        // The night after the upgrade: sixty-five items, every one recorded today with no history.
        var all = new ArrayList<ItemRecord>();
        for (int i = 0; i < 65; i++) {
            all.add(new ItemRecord(MIA + "/tool_" + i, "crafted", MIA, null, NOW - DAY / 2, NOW - DAY / 2, null, null));
        }
        assertThat(ItemKeeping.toPutAway(all, MIA, 7, 24, 3, NOW)).as("nothing is chosen by nothing").isEmpty();
        // Four days on, the forty-five she has not touched since the record began are put away; the
        // twenty she used stay out.
        var later = new ArrayList<ItemRecord>();
        for (int i = 0; i < 65; i++) {
            long lastUsed = i < 20 ? NOW - DAY : NOW - 4 * DAY;
            later.add(new ItemRecord(MIA + "/tool_" + i, "crafted", MIA, null, NOW - 4 * DAY, lastUsed, null, null));
        }
        var away = ItemKeeping.toPutAway(later, MIA, 7, 24, 3, NOW);
        assertThat(away).hasSize(45);
        assertThat(away).extracting(ItemRecord::itemId).doesNotContain(MIA + "/tool_0", MIA + "/tool_19");
    }

    @Test
    void she_is_told_in_one_sentence_and_how_to_get_one_back() {
        assertThat(ItemKeeping.toldLine(List.of("Memory Chest")))
            .isEqualTo("Put away, because you had not used it in a long while: Memory Chest. It is kept. "
                + "Name one to use it and it is taken out again.");
        assertThat(ItemKeeping.toldLine(List.of("A", "B", "C", "D", "E", "F", "G", "H")))
            .contains("A, B, C, D, E, F and 2 more").contains("They are kept.");
        assertThat(ItemKeeping.toldLine(List.of())).isNull();
    }

    @Test
    void the_companion_records_uses_puts_away_and_takes_out() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("madeStore.itemMade(itemKey(toolItem.id()), \"crafted\", profile.entityId(),");
        assertThat(ca).contains("madeStore.itemUsed(itemKey(itemId), System.currentTimeMillis(),\n"
            + "                        toolFor != null && toolFor != NO_ONE && isHumanTrigger(toolFor));");
        // Its name and what it does are recorded even when it is put away: that is what it is found by later.
        assertThat(ca).contains("madeStore.enrollItem(key, \"crafted\", agentProfile.entityId(), inv.objectName(), inv.description());\n"
            + "                }\n                if (putAway.contains(key)) continue;");
        // Brought back by a person or the steward, it is on her menu at once, not after her next sleep.
        assertThat(ca).contains(".onMessage(ItemsBroughtBack.class, this::onItemsBroughtBack)");
        assertThat(ca).contains("herItemsPutAway = true;\n            takeOutWhatTheStewardBroughtBack(store);");
        assertThat(ca).contains("closeUpHerQuietRooms();\n        putAwayHerUnusedItems();\n        restHerQuietProjects();\n        refreshHouseFacts();");
        assertThat(ca).contains("var takenOut = takeOutPutAwayItem(normalized);\n        if (takenOut != null) return takenOut;\n        return null;");
    }
}
