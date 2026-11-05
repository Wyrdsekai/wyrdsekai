package org.wyrdsekai.core.vault;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * The house's copies of itself, said plainly: what The Archive shows and what a companion reads when
 * she asks. The house is copied by its vault every few minutes and a copy is opened again now and then
 * to prove it whole, and none of it could be seen from inside the world; a companion wanted the house
 * kept safe, could not see that it was, and had the workshop make a toy vault she took for the
 * household's safety net (household node, 2026-11-05). The original plan had this room (The Archive,
 * ).
 *
 * <p>Reads only what is cheap: the copies' heads and the vault's last outcome. Never the chunk store.
 */
public final class HouseArchive {

    private HouseArchive() {}

    private static final Duration FRESH = Duration.ofMinutes(1);
    private static volatile String cached;
    private static volatile Instant cachedAt = Instant.EPOCH;
    private static volatile Locale cachedLocale;

    /** The house's copies as they stand, in the current thread's language. */
    public static String describe() {
        var now = Instant.now();
        var locale = I18n.getLocale();
        var c = cached;
        if (c != null && locale.equals(cachedLocale) && Duration.between(cachedAt, now).compareTo(FRESH) < 0) return c;
        var text = describe(Vault.get(), now, ZoneId.systemDefault());
        cached = text;
        cachedAt = now;
        cachedLocale = locale;
        return text;
    }

    static String describe(Vault vault, Instant now, ZoneId zone) {
        var lines = new ArrayList<String>();
        if (vault == null) {
            lines.add(I18n.get("archive.off"));
            return String.join("\n", lines);
        }
        var heads = vault.heads();
        if (heads.isEmpty()) {
            lines.add(I18n.get("archive.none_yet"));
        } else {
            var newest = heads.get(0).at();
            lines.add(I18n.get("archive.latest", ago(Duration.between(newest, now)), when(newest, zone)));
            lines.add(I18n.get("archive.count", heads.size(), heads.stream().filter(Vault.Head::keep).count()));
            var drilled = heads.stream().filter(h -> h.drill() != null).max(Comparator.comparing(h -> h.drill().at()));
            if (drilled.isEmpty()) {
                lines.add(I18n.get("archive.drill_none"));
            } else if (drilled.get().drill().ok()) {
                lines.add(I18n.get("archive.drill_ok", when(drilled.get().drill().at(), zone)));
            } else {
                lines.add(I18n.get("archive.drill_failed", when(drilled.get().drill().at(), zone)));
            }
        }
        var error = vault.lastError();
        if (error != null && !error.isBlank()) lines.add(I18n.get("archive.trouble"));
        var mismatch = vault.keyMismatch();
        if (mismatch != null && !mismatch.isBlank()) lines.add(I18n.get("archive.key_mismatch"));
        lines.add(I18n.get("archive.theirs"));
        return String.join("\n", lines);
    }

    static String ago(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 1) return I18n.get("archive.ago.moment");
        if (minutes < 60) return minutes == 1 ? I18n.get("archive.ago.minute") : I18n.get("archive.ago.minutes", minutes);
        long hours = minutes / 60;
        if (hours < 48) return hours == 1 ? I18n.get("archive.ago.hour") : I18n.get("archive.ago.hours", hours);
        return I18n.get("archive.ago.days", hours / 24);
    }

    private static String when(Instant at, ZoneId zone) {
        return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", I18n.getLocale()).withZone(zone).format(at);
    }
}
