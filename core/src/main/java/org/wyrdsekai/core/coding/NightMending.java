package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.household.QuietHours;
import org.wyrdsekai.core.item.BrokenItems;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.update.ActivityGauge;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The workshop mends what is broken while the house is quiet. After a companion's sleep, and
 * only then, the broken household items are taken one after another through
 * {@link ItemContractRepair#repairPlaced}: a copy is repaired, the placed file changes only when
 * the copy comes out whole, the old version is kept, and she is told in the morning what was
 * mended and what could not be.
 *
 * <p>It is bounded by time, not by count. Mending uses the thinking brain she thinks with, so
 * it runs inside a nightly budget of minutes ({@code WYRDSEKAI_ITEM_MEND_MINUTES}, default 45,
 * 0 turns it off), stops taking new items the moment someone else is using inference, and when
 * the household keeps quiet hours it only works inside them. An item that fails to mend
 * {@link BrokenItems#MAX_ATTEMPTS} times unchanged is left for a person.</p>
 */
public final class NightMending {

    private static final Logger log = LoggerFactory.getLogger(NightMending.class);
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile LocalDate budgetDay;
    private static volatile Duration spentToday = Duration.ZERO;

    private NightMending() {}

    /** Called when a sleep cycle completes. Never blocks: the mending runs on its own thread. */
    public static void afterSleep() {
        int minutes = WyrdConfig.get().itemMendMinutes();
        if (minutes <= 0) return;
        var quiet = QuietHours.spec();
        if (quiet != null && !quiet.isBlank() && !"off".equalsIgnoreCase(quiet) && !QuietHours.isQuiet()) return;
        if (!running.compareAndSet(false, true)) return;
        final boolean keepsQuietHours = quiet != null && !quiet.isBlank() && !"off".equalsIgnoreCase(quiet);
        Thread.ofVirtual().name("night-mending").start(() -> {
            try {
                // She wakes speaking: the chronicle, a polish, her first turn all follow the sleep
                // cycle within seconds. The first night this ran it sampled the brain at that
                // moment, found it busy, and stopped. So the loop waits for a settled quiet
                // before each item, for up to a window, and only the window's end is "tonight
                // is over". Quiet hours, when kept, end the window too.
                mend(Duration.ofMinutes(minutes),
                    () -> ActivityGauge.inferenceInFlight() == 0 && (!keepsQuietHours || QuietHours.isQuiet()),
                    BodyMap.get(), ScriptedItemLoader.householdItemsDir(), SETTLE, POLL, WINDOW);
            } finally {
                running.set(false);
            }
        });
    }

    /** One item, now, at her own asking. Never blocks the caller; she is told by a mark how it went. */
    public static boolean mendNow(String item) {
        var entry = BrokenItems.find().stream().filter(e -> e.item().equals(item)).findFirst().orElse(null);
        if (entry == null) return false;
        Thread.ofVirtual().name("mending-" + item).start(() -> mendOne(entry, BodyMap.get(), "at the mending bench"));
        return true;
    }

    /** How long the brain must have been idle before an item is started. */
    static final Duration SETTLE = Duration.ofMinutes(2);
    /** How often the loop looks. */
    static final Duration POLL = Duration.ofSeconds(15);
    /** How long after the sleep the workshop keeps waiting for a quiet moment. */
    static final Duration WINDOW = Duration.ofHours(3);

    /** The loop, with its clock and its quiet-check injected for tests. Returns how many were mended. */
    static int mend(Duration budget, BooleanSupplier quietEnough, BodyMap map) {
        return mend(budget, quietEnough, map, ScriptedItemLoader.householdItemsDir());
    }

    /** As {@link #mend(Duration, BooleanSupplier, BodyMap, Path, Duration, Duration, Duration)} with no waiting: a busy sample ends the night. */
    static int mend(Duration budget, BooleanSupplier quietEnough, BodyMap map, Path itemsDir) {
        return mend(budget, quietEnough, map, itemsDir, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    /**
     * @param budget      working minutes for the night, across items
     * @param quietEnough whether the brain is free right now
     * @param settle      how long {@code quietEnough} must hold before an item is started
     * @param poll        how long to wait between looks (zero = look again at once)
     * @param window      how long after the start the loop keeps waiting for quiet
     */
    static int mend(Duration budget, BooleanSupplier quietEnough, BodyMap map, Path itemsDir,
                    Duration settle, Duration poll, Duration window) {
        var today = LocalDate.now();
        if (!today.equals(budgetDay)) { budgetDay = today; spentToday = Duration.ZERO; }
        int mended = 0;
        var deadline = Instant.now().plus(window);
        ActivityGauge.maintenanceStarted();
        try {
            for (var entry : BrokenItems.mendable(itemsDir)) {
                if (spentToday.compareTo(budget) >= 0) { log.info("[night-mending] the night's {} min are spent", budget.toMinutes()); break; }
                if (!awaitQuiet(quietEnough, settle, poll, deadline)) {
                    log.info("[night-mending] the house did not go quiet within {} min; {} mended, the rest waits for another night",
                        window.toMinutes(), mended);
                    break;
                }
                var started = Instant.now();
                if (mendOne(entry, map, "while the house was quiet")) mended++;
                spentToday = spentToday.plus(Duration.between(started, Instant.now()));
            }
        } finally {
            ActivityGauge.maintenanceFinished();
        }
        return mended;
    }

    /**
     * Wait until the brain has been free for {@code settle}, looking every {@code poll}. False
     * when the deadline passes first. With a zero poll, the first free sample counts as settled
     * (there is no time for a settle to elapse) and a busy sample past the deadline ends it.
     */
    static boolean awaitQuiet(BooleanSupplier quietEnough, Duration settle, Duration poll, Instant deadline) {
        Instant quietSince = null;
        while (true) {
            var now = Instant.now();
            if (quietEnough.getAsBoolean()) {
                if (quietSince == null) quietSince = now;
                if (poll.isZero() || Duration.between(quietSince, now).compareTo(settle) >= 0) return true;
            } else {
                quietSince = null;
                if (!now.isBefore(deadline)) return false;
            }
            if (!poll.isZero()) {
                try { Thread.sleep(poll.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
    }

    private static boolean mendOne(BrokenItems.Entry entry, BodyMap map, String when) {
        var before = BrokenItems.fileHash(entry.file());
        var r = ItemContractRepair.repairPlaced(entry.file());
        if ("no coding backend is registered to repair with".equals(r.note())) return false;   // nothing was tried; nothing to count
        BrokenItems.attempted(entry, before, r.fixed());
        var name = entry.item().replace('_', ' ');
        if (r.fixed()) {
            try { ScriptedItemLoader.get().register(entry.file()); } catch (RuntimeException ignored) { /* the next load picks it up */ }
        }
        if (map != null) {
            try {
                map.mark("item", entry.item(), null, r.fixed()
                    ? "The workshop mended the " + name + " " + when + "; it works now. The version it replaced is kept."
                    : "The workshop tried to mend the " + name + " " + when + " and could not; it is as it was.",
                    r.fixed() ? "fixed" : String.join(" | ", r.after()));
            } catch (RuntimeException ignored) { /* the mark is a courtesy */ }
        }
        log.info("[night-mending] {}: {}", entry.item(), r.fixed() ? "mended" : "not mended — " + r.note());
        return r.fixed();
    }

    static void resetForTests() { budgetDay = null; spentToday = Duration.ZERO; running.set(false); }
}
