package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.scripting.api.BridgeDataProvider;
import org.wyrdsekai.scripting.api.WorldApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The house is copied every few minutes and a copy is opened again now and then to prove it whole,
 * and none of that could be seen from inside the world: a companion had the workshop make a toy
 * vault and took it for the household's safety net (household node, 2026-11-05). The Archive and
 * her house_backups tool read the same record.
 */
class TheHouseBackupsCanBeSeenFromInsideTest {

    @AfterEach
    void tearDown() { Vault.resetForTests(); }

    private static Path household(Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data);
        SchemaInitializer.initialize(data.resolve("world.db"));
        Files.writeString(data.resolve("node-identity.json"), "{\"nodeId\":\"n1\",\"publicKey\":\"pk\"}");
        return data;
    }

    @Test
    void the_record_says_what_the_house_has_kept_and_whether_a_copy_opened_whole(@TempDir Path dir) throws Exception {
        assertThat(HouseArchive.describe(null, Instant.now(), ZoneId.of("UTC")))
            .isEqualTo("This house keeps no copies of itself: its vault is turned off.");

        var vault = new Vault(household(dir), dir.resolve("vault"));
        assertThat(HouseArchive.describe(vault, Instant.now(), ZoneId.of("UTC")))
            .startsWith("The vault of the house is on, and has not made its first copy yet.")
            .endsWith("The house makes these copies itself. Nothing anyone builds has to hold them.");

        vault.snapshot("first", true).orElseThrow();
        vault.snapshot("second", false).orElseThrow();
        var later = Instant.now().plus(Duration.ofMinutes(12));
        var record = HouseArchive.describe(vault, later, ZoneId.of("UTC"));
        assertThat(record)
            .contains("The house keeps copies of itself. The newest was made 12 minutes ago (")
            .contains("It holds 2 copies; 1 of them are kept for good.")
            .contains("No copy has been opened again to test it yet.");

        vault.recordDrill().orElseThrow();
        assertThat(HouseArchive.describe(vault, later, ZoneId.of("UTC")))
            .containsPattern("The last test, on .+, opened a copy again and found it whole\\.");
    }

    @Test
    void how_long_ago_reads_the_way_it_is_said() {
        assertThat(HouseArchive.ago(Duration.ofSeconds(20))).isEqualTo("a moment ago");
        assertThat(HouseArchive.ago(Duration.ofMinutes(1))).isEqualTo("a minute ago");
        assertThat(HouseArchive.ago(Duration.ofMinutes(47))).isEqualTo("47 minutes ago");
        assertThat(HouseArchive.ago(Duration.ofMinutes(61))).isEqualTo("an hour ago");
        assertThat(HouseArchive.ago(Duration.ofHours(30))).isEqualTo("30 hours ago");
        assertThat(HouseArchive.ago(Duration.ofDays(3))).isEqualTo("3 days ago");
    }

    @Test
    void only_the_archive_reads_the_record_to_a_room() {
        var provider = new BridgeDataProvider() {
            public String formatRoomList() { return ""; }
            public String formatWards(String roomId) { return ""; }
            public String formatGrant(String roomId, String principal, String permission) { return ""; }
            public String formatRevoke(String roomId, String principal, String permission) { return ""; }
            public int roomCount() { return 0; }
            public int userCount() { return 0; }
            public int wardCount() { return 0; }
            @Override public String formatArchive() { return "THE RECORD"; }
        };
        var archive = new WorldApi("archive");
        archive.setBridgeDataProvider(provider);
        assertThat(archive.getArchive()).isEqualTo("THE RECORD");
        var nexus = new WorldApi("nexus");
        nexus.setBridgeDataProvider(provider);
        assertThat(nexus.getArchive()).startsWith("[");
    }
}
