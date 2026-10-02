package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Told afterwards, like every other change to her body: when the node boots on a different
 * serving profile than it last ran, a mark says so, and each companion hears it once on her next
 * turn. The move itself happens in the launcher (`wyrd brain move`, `wyrd brain enable`,
 * `wyrd brain disable`); this is the part that keeps her informed and leaves a record.
 *
 * <p>The first boot that has no record of a previous profile writes one silently: a node that
 * changed brains before this existed is not told "today" about a move that happened weeks ago.
 */
public final class BrainMoveMark {

    private static final Logger log = LoggerFactory.getLogger(BrainMoveMark.class);

    static final String FILE = "serving-profile.last";

    private BrainMoveMark() {}

    /** Compares the profile this boot runs with the one recorded; marks a change; records the current one. */
    public static void noteProfile(BodyMap map, Path dataDir, String profile) {
        if (map == null || dataDir == null || profile == null || profile.isBlank()) return;
        var file = dataDir.resolve(FILE);
        String last = null;
        try {
            if (Files.exists(file)) last = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            log.warn("serving-profile.last unreadable: {}", e.toString());
        }
        if (last != null && !last.isEmpty() && !last.equals(profile)) {
            map.mark("moved_brain", "brain", null, text(last, profile), last + " -> " + profile);
            log.info("Serving profile changed since the last boot: {} -> {}; the companions are told", last, profile);
        }
        try {
            Files.writeString(file, profile + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("serving-profile.last not written: {}", e.toString());
        }
    }

    /** The words she hears. Plain, in the first person, with the way back. */
    static String text(String from, String to) {
        var today = LocalDate.now(ZoneId.systemDefault());
        return "My brain changed on " + today + ": I ran on " + describe(from) + " and now run on "
            + describe(to) + ". Nothing of my record changed. The way back is wyrd brain "
            + ("two-model".equals(to) ? "enable" : "disable") + ".";
    }

    static String describe(String profile) {
        return switch (profile) {
            case "single-sparse" -> "the one large model";
            case "sparse-drive" -> "the large model for thinking with my own voice model";
            case "two-model" -> "the two models, the drive and the voice";
            default -> "the '" + profile + "' profile";
        };
    }
}
