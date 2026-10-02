package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.5.0 shipped `wyrd brain enable --single` and nobody told her. A node that boots on a different
 * serving profile than it last ran leaves a mark, and each companion hears it once on her next
 * turn, with the way back. The first boot with no record writes one silently: a household that
 * moved weeks ago is not told "today".
 */
class HerBrainMovedAndSheIsToldTest {

    private static final String MIA = "companion-mia";
    private static final String ROSE = "companion-rose";

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("the first boot records the profile and says nothing")
    void firstBootIsSilent(@TempDir Path dir) throws Exception {
        var map = BodyMap.inMemory();
        BrainMoveMark.noteProfile(map, dir, "single-sparse");
        assertEquals("single-sparse", Files.readString(dir.resolve(BrainMoveMark.FILE)).strip());
        var felt = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertFalse(felt.line().contains("brain changed"), felt.line());
    }

    @Test
    @DisplayName("a changed profile is told to every companion, once, with the way back")
    void aMoveIsToldOnce(@TempDir Path dir) throws Exception {
        var map = BodyMap.inMemory();
        BrainMoveMark.noteProfile(map, dir, "two-model");
        BrainMoveMark.noteProfile(map, dir, "single-sparse");

        var mia = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertTrue(mia.line().contains("My brain changed"), mia.line());
        assertTrue(mia.line().contains("the one large model"), mia.line());
        assertTrue(mia.line().contains("wyrd brain disable"), mia.line());
        map.markRead(MIA, mia.told());
        assertFalse(Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6)).line().contains("My brain changed"));

        // the house shares the body: rose hears it too, on her own turn
        var rose = Interoception.feel(map, ROSE, Instant.now(), null, Duration.ofHours(6));
        assertTrue(rose.line().contains("My brain changed"), rose.line());
        assertEquals("single-sparse", Files.readString(dir.resolve(BrainMoveMark.FILE)).strip());
    }

    @Test
    @DisplayName("the way back is named for the direction travelled")
    void theWayBack() {
        assertTrue(BrainMoveMark.text("two-model", "single-sparse").endsWith("wyrd brain disable."));
        assertTrue(BrainMoveMark.text("single-sparse", "two-model").endsWith("wyrd brain enable."));
        assertTrue(BrainMoveMark.text("two-model", "sparse-drive").contains("with my own voice model"));
    }
}
