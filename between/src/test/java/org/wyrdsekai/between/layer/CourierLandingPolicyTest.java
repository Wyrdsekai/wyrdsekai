package org.wyrdsekai.between.layer;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the courier receiver's landing-path policy.
 * Relative paths land in the courier inbox and cannot traverse out; absolute
 * paths need the RECEIVING steward's explicit opt-in. This is what stops an
 * enrolled-but-compromised peer from overwriting arbitrary files here.
 */
final class CourierLandingPolicyTest {

    private final Path dataDir = Path.of("/var/lib/wyrdsekai-test");

    @Test
    void relative_path_lands_in_the_courier_inbox() {
        var landed = CourierFileLayer.resolveLanding(dataDir, "drops/notes.txt", false);
        assertEquals(dataDir.resolve("courier").resolve("drops").resolve("notes.txt")
            .toAbsolutePath().normalize(), landed);
    }

    @Test
    void traversal_out_of_the_inbox_is_refused() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, "../world.db", false));
        assertTrue(ex.getMessage().contains("escapes"));
        assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, "a/../../../etc/passwd", false));
    }

    @Test
    void absolute_path_is_closed_by_default_and_names_the_remedy() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, "/etc/passwd", false));
        assertTrue(ex.getMessage().contains("allow-absolute"),
            "the refusal must tell the sender what the receiving steward would need to set");
    }

    @Test
    void absolute_path_honored_only_with_the_opt_in() {
        var landed = CourierFileLayer.resolveLanding(dataDir, "/tmp/drop.bin", true);
        assertEquals(Path.of("/tmp/drop.bin"), landed);
    }

    @Test
    void staging_area_and_blank_paths_are_refused() {
        assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, ".incoming/sneak.part", false));
        assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, "  ", false));
        assertThrows(IllegalArgumentException.class,
            () -> CourierFileLayer.resolveLanding(dataDir, null, false));
    }
}
