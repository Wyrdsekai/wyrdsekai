package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class PermissionOverridesTest {

    @TempDir Path tmp;

    @Test
    void promote_raises_tier_in_audit_log() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        po.promote("library.add", null, 5, "paranoid");
        // Tier 2 default → Tier 5 promote
        assertEquals(5, po.effectiveTierFor("library.add", null));
        var audit = po.auditLog();
        assertEquals(1, audit.size());
        assertEquals("library.add", audit.getFirst().capability());
    }

    @Test
    void demote_lowers_tier() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        po.demote("web.post", "trusted_item", 4, "audited author");
        assertEquals(4, po.effectiveTierFor("web.post", "trusted_item"));
        // System-wide unchanged
        assertEquals(5, po.effectiveTierFor("web.post", null));
    }

    @Test
    void per_item_override_wins_over_system_wide() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        po.promote("library.add", null, 5, "system-wide paranoia");
        po.demote("library.add", "trusted_clipper", 2, "vetted");
        assertEquals(2, po.effectiveTierFor("library.add", "trusted_clipper"));
        assertEquals(5, po.effectiveTierFor("library.add", "other_item"));
    }

    @Test
    void tier7_floor_invariant_holds() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        // Try to demote council.vote (Tier 7) to 3 — should floor to 6
        po.demote("council.vote", null, 3, "stupid demote");
        assertEquals(6, po.effectiveTierFor("council.vote", null));
    }

    @Test
    void tier1_cannot_promote_above_4() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        assertThrows(IllegalArgumentException.class,
            () -> po.promote("self.did", null, 6, "unreasonable"));
    }

    @Test
    void require_ritual_flag() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        po.requireRitual("market.list_offer", null, "ceremony for outflow");
        assertTrue(po.requiresRitual("market.list_offer", null));
        assertFalse(po.requiresRitual("library.add", null));
    }

    @Test
    void parse_toml_system_wide() throws IOException {
        var file = tmp.resolve("permissions.toml");
        Files.writeString(file, """
            [system_wide]
            "library.add" = { tier = 5, reason = "paranoid library curation" }
            "web.post" = { tier = 4, reason = "trust web posts a bit more" }
            """);
        var po = new PermissionOverrides(file);
        po.load();
        assertEquals(5, po.effectiveTierFor("library.add", null));
        assertEquals(4, po.effectiveTierFor("web.post", null));
    }

    @Test
    void parse_toml_per_item() throws IOException {
        var file = tmp.resolve("permissions.toml");
        Files.writeString(file, """
            [items."research_clipper"]
            "drive.mark" = { tier = 4, reason = "audited" }
            """);
        var po = new PermissionOverrides(file);
        po.load();
        assertEquals(4, po.effectiveTierFor("drive.mark", "research_clipper"));
        // Other item still has default tier
        assertEquals(5, po.effectiveTierFor("drive.mark", "other"));
    }

    @Test
    void hot_reload_picks_up_changes() throws IOException, InterruptedException {
        var file = tmp.resolve("permissions.toml");
        Files.writeString(file, """
            [system_wide]
            "library.add" = { tier = 3, reason = "initial" }
            """);
        var po = new PermissionOverrides(file);
        po.checkReload();
        assertEquals(3, po.effectiveTierFor("library.add", null));

        // Sleep briefly so mtime registers as changed across filesystems
        Thread.sleep(20);
        Files.writeString(file, """
            [system_wide]
            "library.add" = { tier = 5, reason = "updated" }
            """);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
            System.currentTimeMillis() + 1000));
        boolean reloaded = po.checkReload();
        assertTrue(reloaded);
        assertEquals(5, po.effectiveTierFor("library.add", null));
    }

    @Test
    void save_and_reload_roundtrip() throws IOException {
        var file = tmp.resolve("permissions.toml");
        var po = new PermissionOverrides(file);
        po.promote("library.add", null, 5, "paranoid");
        po.demote("drive.mark", "test_item", 4, "audited");
        po.save();

        var po2 = new PermissionOverrides(file);
        po2.load();
        assertEquals(5, po2.effectiveTierFor("library.add", null));
        assertEquals(4, po2.effectiveTierFor("drive.mark", "test_item"));
    }

    @Test
    void unknown_capability_default_tier_falls_back() {
        var po = new PermissionOverrides(tmp.resolve("permissions.toml"));
        // Validator default for unknown caps is Tier 5
        assertEquals(5, po.effectiveTierFor("brand.new.cap", null));
    }
}
